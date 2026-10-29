#include "node_hooks.hpp"

#include "credentials.hpp"
#include "crypto.hpp"
#include "describer.hpp"
#include "locations.hpp"
#include "screen_rules.hpp"

#include "duckdb/catalog/catalog_entry_retriever.hpp"
#include "duckdb/catalog/catalog_transaction.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/function/scalar_function.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/main/prepared_statement_data.hpp"
#include "duckdb/main/secret/secret.hpp"
#include "duckdb/main/secret/secret_manager.hpp"
#include "duckdb/optimizer/optimizer_extension.hpp"
#include "duckdb/planner/expression/bound_function_expression.hpp"
#include "duckdb/planner/operator/logical_copy_to_file.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator/logical_prepare.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <iostream>
#include <iterator>
#include <map>
#include <mutex>
#include <optional>

namespace qod {
namespace {

using namespace duckdb;

//! This connection's user credential secrets (in connection storage, named kUserSecretPrefix and the
//! first 8 digits of their id), each with the SHA-256 of everything it holds: a client ROLLBACK can
//! restore an earlier secret under a name the current set also uses, so the names alone do not tell.
std::map<std::string, std::string> UserSecretPrints(SecretManager &manager, CatalogTransaction transaction) {
	std::map<std::string, std::string> prints;
	for (auto &entry : manager.AllSecrets(transaction)) {
		auto &name = entry.secret->GetName().GetIdentifierName();
		if (entry.storage_mode == SecretManager::CONNECTION_STORAGE_NAME && name.rfind(kUserSecretPrefix, 0) == 0) {
			prints[name] = Hex(Sha256(entry.secret->ToString(SecretDisplayType::UNREDACTED)));
		}
	}
	return prints;
}

//! The s3 secret of `credential`, with every key an s3 secret takes: httpfs fills a missing key from
//! the settings, a session's included (S3AuthResolver::ReadConfig, httpfs 77392a7), so none is left
//! for a session's SET to supply.
unique_ptr<const BaseSecret> UserSecret(const UserCredential &credential) {
	auto secret = make_uniq<KeyValueSecret>(vector<string> {credential.scope}, Identifier("s3"), Identifier("config"),
	                                        Identifier(UserSecretName(credential.id)));
	secret->redact_keys = {"secret", "session_token"};
	auto &keys = secret->secret_map;
	keys["key_id"] = Value(credential.key_id);
	keys["secret"] = Value(credential.secret);
	keys["session_token"] = Value(credential.session_token);
	keys["region"] = Value(credential.region);
	keys["endpoint"] = Value(credential.endpoint);
	keys["url_style"] = Value(credential.url_style);
	keys["use_ssl"] = Value::BOOLEAN(credential.use_ssl);
	keys["kms_key_id"] = Value("");
	keys["url_compatibility_mode"] = Value::BOOLEAN(false);
	keys["requester_pays"] = Value::BOOLEAN(false);
	return std::move(secret);
}

//! The principal installed on one connection by its qod_session calls. It stays for the
//! connection's life: a later handle may only refresh it (RefreshRefusal).
class SessionState : public ClientContextState {
public:
	static constexpr const char *kKey = "qod_node_session";

	//! Installs or refreshes `handle` (already verified for this node). Throws PermissionException
	//! when it may not install here, or was used before.
	void Install(NodeAuthz &authz, const Handle &handle, const std::string &text) {
		std::lock_guard<std::mutex> guard(lock_);
		// The same CALL bound again (DuckDB rebinds a prepared statement when the catalog changed).
		if (installed_ && current_.nonce == handle.nonce) {
			return;
		}
		auto refusal = RefreshRefusal(installed_ ? &current_ : nullptr, handle);
		if (!refusal.empty()) {
			throw PermissionException(kHandleRefused + refusal);
		}
		if (!authz.Consume(handle)) {
			throw PermissionException(kHandleRefused + std::string("it was already used"));
		}
		current_ = Installed {handle.principal, handle.policy_version, handle.exp, handle.nonce,
		                      handle.ctx,       text,                  handle.replica,    handle.path};
		installed_ = true;
		authz_ = &authz;
	}

	//! Called first by every qod_session bind: until this prelude succeeds whole (Install, then
	//! SyncCredentials), no statement runs on the connection, whatever principal and secrets an earlier
	//! prelude left. A refused handle, a refused install or a failed sync all leave it so.
	void BeginPrelude() {
		std::lock_guard<std::mutex> guard(lock_);
		ready_ = false;
	}

	//! True when a principal is installed, its handle has not expired at `now_ms`, and the last prelude
	//! succeeded whole (BeginPrelude).
	bool CurrentAt(int64_t now_ms) {
		std::lock_guard<std::mutex> guard(lock_);
		return installed_ && current_.exp > now_ms && ready_;
	}

	//! A copy of the installed principal and the handle that last installed or refreshed it, while
	//! that handle is current at `now_ms` and the last prelude succeeded whole (the plan verdicts'
	//! decide calls, Task 10).
	bool Snapshot(int64_t now_ms, Installed &out) {
		std::lock_guard<std::mutex> guard(lock_);
		if (!installed_ || current_.exp <= now_ms || !ready_) {
			return false;
		}
		out = current_;
		return true;
	}

	//! Makes the connection's user credential secrets the set `handle.creds` names (decision 23): none
	//! for the manager itself or an empty digest (any present are dropped), else the set the handle's
	//! replica serves for the handle `text`, from the node's cache when it holds it, as connection
	//! secrets (scoped to the connection, transactional). Installs nothing while the secrets present are
	//! exactly the ones last made for that digest. Only a sync that succeeds makes the connection ready
	//! again (BeginPrelude). It compares what is present rather than trusting a
	//! flag: a client ROLLBACK undoes the secrets made in its transaction and restores the ones it
	//! dropped. On a failure (a refused or unavailable fetch, kUnavailableCreds included, an answer for
	//! another digest, an install that fails) the prelude is refused (the manager drops the link and
	//! retries, as for any refused handle), nothing is recorded as installed, and no statement runs on
	//! the connection (CurrentAt) until a prelude syncs them: none runs under secrets installed before.
	void SyncCredentials(ClientContext &context, NodeAuthz &authz, const Handle &handle, const string &text) {
		std::lock_guard<std::mutex> guard(lock_);
		auto wanted = handle.principal.system ? string() : handle.creds;
		auto &manager = SecretManager::Get(context);
		auto present = UserSecretPrints(manager, CatalogTransaction::GetSystemCatalogTransaction(context));
		if (synced_ && wanted == creds_ && present == secret_prints_) {
			ready_ = true;
			return;
		}
		synced_ = false;
		creds_.clear();
		secret_prints_.clear();
		scopes_.clear();
		CredentialSet set;
		if (!wanted.empty()) {
			auto fetched = authz.Credentials().Fetch(handle.replica, text, handle.principal.id, wanted);
			if (fetched.status != CredentialFetch::Status::Ok) {
				throw PermissionException(fetched.Message());
			}
			set = std::move(fetched.set);
		}
		std::vector<std::string> scopes;
		std::map<std::string, std::string> installed;
		try {
			auto transaction = CatalogTransaction::GetSystemCatalogTransaction(context);
			for (auto &entry : present) {
				manager.DropSecretByName(transaction, Identifier(entry.first), OnEntryNotFound::RETURN_NULL,
				                         SecretPersistType::TEMPORARY, Identifier(SecretManager::CONNECTION_STORAGE_NAME));
			}
			for (auto &credential : set.credentials) {
				manager.RegisterSecret(transaction, UserSecret(credential), OnCreateConflict::REPLACE_ON_CONFLICT,
				                       SecretPersistType::TEMPORARY, SecretManager::CONNECTION_STORAGE_NAME);
				scopes.push_back(credential.scope);
			}
			installed = UserSecretPrints(manager, transaction);
		} catch (std::exception &error) {
			throw PermissionException("qod-node: S3 credentials refused: they could not be installed: " +
			                          ErrorData(error).RawMessage());
		}
		if (installed.size() != set.credentials.size()) {
			throw PermissionException("qod-node: S3 credentials refused: they could not be installed");
		}
		secret_prints_ = std::move(installed);
		scopes_ = std::move(scopes);
		creds_ = wanted;
		synced_ = true;
		ready_ = true;
	}

	//! The scopes of the user credential secrets last installed; none after a failed sync.
	std::vector<std::string> CredentialScopes() {
		std::lock_guard<std::mutex> guard(lock_);
		return ready_ ? scopes_ : std::vector<std::string>();
	}

	//! The statement's SQL while a query runs on this connection, for decision 19; nullopt otherwise:
	//! GetCurrentQuery dereferences the active query unchecked, and ClientContext::BindStatement
	//! plans without one.
	std::optional<std::string> StatementText(ClientContext &context) {
		std::lock_guard<std::mutex> guard(lock_);
		if (!query_active_) {
			return std::nullopt;
		}
		return context.GetCurrentQuery();
	}

	//! The verdict the current statement runs under; kept with the prepared statement a PREPARE
	//! registers, for OnRebindPreparedStatement.
	void Admit(const Admitted &admitted, LogicalOperator &plan) {
		std::lock_guard<std::mutex> guard(lock_);
		pending_ = admitted;
		if (plan.type != LogicalOperatorType::LOGICAL_PREPARE) {
			return;
		}
		auto &prepare = plan.Cast<LogicalPrepare>();
		if (!prepare.prepared || prepare.children.empty()) {
			return;
		}
		for (auto it = prepared_.begin(); it != prepared_.end();) {
			it = it->second.statement.expired() ? prepared_.erase(it) : std::next(it);
		}
		prepared_[prepare.prepared.get()] = Stored {weak_ptr<PreparedStatementData>(prepare.prepared), admitted};
	}

	//! The manager's prelude: the handle's own install step.
	void AdmitPrelude() {
		std::lock_guard<std::mutex> guard(lock_);
		pending_ = Admitted {"", current_.principal.id, current_.policy_version, current_.ctx, kNeverExpires};
	}

	//! True when the current statement already runs under a verdict for the plan `fingerprint`
	//! describes, taken for this session and still valid.
	bool Covers(const std::string &fingerprint) {
		std::lock_guard<std::mutex> guard(lock_);
		return authz_ && pending_ && pending_->fingerprint == fingerprint &&
		       !RebindRequired(&*pending_, current_, authz_->Now());
	}

	// ---- ClientContextState
	//! True arms OnFinalizePrepare: DuckDB calls it only when a state can request a rebind
	//! (duckdb: src/main/client_context.cpp, CreatePreparedStatement).
	bool CanRequestRebind() override {
		return true;
	}

	void QueryBegin(ClientContext &context) override {
		std::lock_guard<std::mutex> guard(lock_);
		query_active_ = true;
		pending_.reset();
	}

	void QueryEnd(ClientContext &context, optional_ptr<ErrorData> error) override {
		std::lock_guard<std::mutex> guard(lock_);
		query_active_ = false;
		pending_.reset();
	}

	//! A statement that failed to bind, for a principal that is neither superuser nor system: the
	//! call-name walk's refusal replaces the binder's error, which can quote the value of a call DuckDB
	//! folded while binding (CONTRACT Addendum 4 item 32). DuckDB rethrows its own error otherwise
	//! (ClientContext::CreatePreparedStatement).
	RebindQueryInfo OnPlanningError(ClientContext &context, SQLStatement &statement, ErrorData &error) override {
		bool judged;
		{
			std::lock_guard<std::mutex> guard(lock_);
			judged = installed_ && !current_.principal.superuser && !current_.principal.system;
		}
		if (judged) {
			JudgeFailedStatement(context, statement);
		}
		return RebindQueryInfo::DO_NOT_REBIND;
	}

	RebindQueryInfo OnFinalizePrepare(ClientContext &context, PreparedStatementData &prepared_statement,
	                                  PreparedStatementMode mode) override {
		std::lock_guard<std::mutex> guard(lock_);
		auto privileged = installed_ && (current_.principal.superuser || current_.principal.system);
		// A verdict for this session, still valid: not one left by another principal, policy version or
		// context, nor one that expired.
		auto now = authz_ ? authz_->Now() : 0;
		auto has_verdict = pending_.has_value() && authz_ && !RebindRequired(&*pending_, current_, now);
		auto refusal = FinalizeRefusal(installed_, privileged, has_verdict);
		auto expired = !refusal.empty() && installed_ && authz_ && pending_.has_value() &&
		               ExpiredInPlanning(&*pending_, current_, now);
		pending_.reset();
		if (expired) {
			// The verdict ran out while the statement was planned: DuckDB binds it once more
			// (ClientContext::CreatePreparedStatement), and post_bind decides it again. That second
			// pass is not finalized through here: post_bind and pre_optimize enforce it.
			return RebindQueryInfo::ATTEMPT_TO_REBIND;
		}
		if (!refusal.empty()) {
			throw PermissionException(refusal);
		}
		return RebindQueryInfo::DO_NOT_REBIND;
	}

	RebindQueryInfo OnRebindPreparedStatement(ClientContext &context, BindPreparedStatementCallbackInfo &info,
	                                          RebindQueryInfo current_rebind) override {
		std::lock_guard<std::mutex> guard(lock_);
		const Admitted *stored = nullptr;
		auto found = prepared_.find(&info.prepared_statement);
		if (found != prepared_.end() && found->second.statement.lock().get() == &info.prepared_statement) {
			stored = &found->second.admitted;
		}
		if (!installed_ || !authz_ || RebindRequired(stored, current_, authz_->Now())) {
			return RebindQueryInfo::ATTEMPT_TO_REBIND;
		}
		return RebindQueryInfo::DO_NOT_REBIND;
	}

private:
	struct Stored {
		weak_ptr<PreparedStatementData> statement;
		Admitted admitted;
	};

	std::mutex lock_;
	bool installed_ = false;
	Installed current_;
	//! The node's checks (its clock), set at the first install; outlives the connection.
	NodeAuthz *authz_ = nullptr;
	bool query_active_ = false;
	std::optional<Admitted> pending_;
	std::map<const PreparedStatementData *, Stored> prepared_;
	//! The digest the user credential secrets were last made from, their prints (UserSecretPrints)
	//! and their scopes.
	string creds_;
	std::map<std::string, std::string> secret_prints_;
	std::vector<std::string> scopes_;
	//! creds_, secret_prints_ and scopes_ describe the secrets the last successful sync installed; false
	//! from a failed sync to the next one that succeeds.
	bool synced_ = true;
	//! The last prelude succeeded whole: set false by BeginPrelude, true only by a successful sync.
	bool ready_ = false;
};

struct AuthzTableInfo : public TableFunctionInfo {
	explicit AuthzTableInfo(std::shared_ptr<NodeAuthz> authz_p) : authz(std::move(authz_p)) {
	}
	std::shared_ptr<NodeAuthz> authz;
};

struct AuthzScalarInfo : public ScalarFunctionInfo {
	explicit AuthzScalarInfo(std::shared_ptr<NodeAuthz> authz_p) : authz(std::move(authz_p)) {
	}
	std::shared_ptr<NodeAuthz> authz;
};

//! What the planner and optimizer hooks share: the identity checks and the decision callback.
struct Gate {
	std::shared_ptr<NodeAuthz> authz;
	PlanDecider decider;
};

struct GatePlannerInfo : public PlannerExtensionInfo {
	explicit GatePlannerInfo(std::shared_ptr<Gate> gate_p) : gate(std::move(gate_p)) {
	}
	std::shared_ptr<Gate> gate;
};

struct GateOptimizerInfo : public OptimizerExtensionInfo {
	explicit GateOptimizerInfo(std::shared_ptr<Gate> gate_p) : gate(std::move(gate_p)) {
	}
	std::shared_ptr<Gate> gate;
};

bool IsQuackSession(ClientContext &context) {
	return context.registered_state->Get<ClientContextState>(kQuackSessionKey) != nullptr;
}

// ---- qod_session ---------------------------------------------------------------------------------

struct SessionScanState : public GlobalTableFunctionState {
	bool done = false;
};

//! Installs at bind, so the statement's own post_bind already finds its principal.
unique_ptr<FunctionData> SessionBind(ClientContext &context, TableFunctionBindInput &input,
                                     vector<LogicalType> &return_types, vector<Identifier> &names) {
	auto &authz = *input.info->Cast<AuthzTableInfo>().authz;
	// First: whatever fails below, no statement runs on this connection until a prelude succeeds whole.
	context.registered_state->GetOrCreate<SessionState>(SessionState::kKey)->BeginPrelude();
	if (input.inputs.size() != 1 || input.inputs[0].IsNull()) {
		throw PermissionException("qod-node: qod_session takes one session handle");
	}
	auto text = input.inputs[0].GetValue<string>();
	Handle handle;
	try {
		handle = authz.Verify(text);
	} catch (std::invalid_argument &error) {
		throw PermissionException(kHandleRefused + std::string(error.what()));
	}
	auto session = context.registered_state->GetOrCreate<SessionState>(SessionState::kKey);
	session->Install(authz, handle, text);
	// Decision 16: no replacement scan for a user session, whatever the spelling (FROM 'x.parquet',
	// FROM "s3://b/x"); such a user reads a file through a reader function, which the parse screen
	// judges. No SQL setting reaches this field, and a refresh keeps the same principal.
	context.config.use_replacement_scans = handle.principal.superuser || handle.principal.system;
	// Decision 23: the principal's own S3 credentials, as this connection's secrets; the screen judges
	// the connection's s3:// locations against their scopes.
	session->SyncCredentials(context, authz, handle, text);
	authz.RecordCredentialScopes(handle.nonce, session->CredentialScopes(), handle.exp);
	// The session default path the manager expects (CONTRACT Addendum 4 item 22), signed with the
	// handle: secure views are judged against it, never against a path a statement set.
	ExpectSessionPath(context, handle.path);
	return_types.push_back(LogicalType::BOOLEAN);
	names.emplace_back("installed");
	return make_uniq<TableFunctionData>();
}

unique_ptr<GlobalTableFunctionState> SessionInit(ClientContext &context, TableFunctionInitInput &input) {
	return make_uniq<SessionScanState>();
}

void SessionScan(ClientContext &context, TableFunctionInput &input, DataChunk &output) {
	auto &state = input.global_state->Cast<SessionScanState>();
	if (state.done) {
		return;
	}
	state.done = true;
	output.data[0].Append(Value::BOOLEAN(true));
	output.CheckCardinality(1);
}

//! The scopes of this node's own S3 secrets: outside connection storage, of the types httpfs reads for
//! an s3:// URL (S3SecretConfig::SecretTypes, httpfs 77392a7). DuckDB signs a URL with one of them
//! over a user's when it scores higher (decision 23, ShadowingScope), so each scope comes with its
//! storage's tie-break offset; a secret with no scope matches every path, as an empty one. Every secret
//! present is listed, whether or not its type's extension is loaded yet: a temporary secret keeps its
//! type name, and a persistent one is deserialized here, which autoloads its type or throws (the caller
//! refuses). Connection storage is the screen connection's own, never a user's, and so is the
//! transaction storage this reads; a user's are only its qod_uc_* secrets (users may not create any,
//! and DuckDB creates no transaction secret from SQL).
std::vector<NodeSecretScope> NodeS3SecretScopes(ClientContext &context) {
	std::vector<NodeSecretScope> scopes;
	auto &manager = SecretManager::Get(context);
	for (auto &entry : manager.AllSecrets(CatalogTransaction::GetSystemCatalogTransaction(context))) {
		auto type = StringUtil::Lower(entry.secret->GetType().GetIdentifierName());
		if (entry.storage_mode == SecretManager::CONNECTION_STORAGE_NAME ||
		    (type != "s3" && type != "r2" && type != "gcs" && type != "aws")) {
			continue;
		}
		auto offset = SecretStorageOffset(entry.storage_mode);
		if (entry.secret->GetScope().empty()) {
			scopes.push_back(NodeSecretScope {"", offset});
		}
		for (auto &scope : entry.secret->GetScope()) {
			scopes.push_back(NodeSecretScope {scope, offset});
		}
	}
	return scopes;
}

std::string RefusalMessage(const DecideResult &decided);

//! The parse screen's content rules for a statement on a connection whose principal is current
//! (`recorded`): nothing for a superuser or the manager itself; else ScreenStatement, then, when the
//! statement names locations, a refusal of any s3:// one a node secret would sign over the user's own
//! (decision 23), then a screen verdict from the replica that minted the connection's handle
//! (never cached: a new database can claim a folder at any time). Empty to admit, else the refusal.
std::string ScreenContent(ClientContext &context, NodeAuthz &authz, const Installed &recorded, const string &sql) {
	if (recorded.principal.superuser || recorded.principal.system) {
		return "";
	}
	auto outcome = ScreenStatement(context, sql);
	if (!outcome.refusal.empty() || outcome.facts.locations.empty()) {
		return outcome.refusal;
	}
	auto user_scopes = authz.CredentialScopes(recorded.nonce);
	std::vector<NodeSecretScope> node_scopes;
	try {
		node_scopes = NodeS3SecretScopes(context);
	} catch (std::exception &error) {
		// The cause can name the node's secret directory: the node's log has it, the client does not.
		std::cerr << "[qod-node] listing the node's S3 secrets failed: " << ErrorData(error).RawMessage() << std::endl;
		return "QoD: this node's own S3 secrets cannot be listed, so the statement's locations cannot be checked";
	}
	for (auto &location : outcome.facts.locations) {
		if (ShadowingScope(location.resolved, user_scopes, node_scopes)) {
			return "QoD: " + location.given +
			       " would be signed with this node's own S3 keys, not with an S3 credential of yours";
		}
	}
	DecideCall call;
	call.replica = recorded.replica;
	call.handle = recorded.handle;
	call.kind = "screen";
	call.description = ScreenDescription(outcome.facts.locations);
	call.principal = recorded.principal;
	call.policy_version = recorded.policy_version;
	call.ctx = recorded.ctx;
	auto decided = authz.Decider().Decide(call);
	if (decided.status == DecideResult::Status::Allow) {
		return "";
	}
	return RefusalMessage(decided);
}

// ---- qod_screen ----------------------------------------------------------------------------------

void ScreenFunction(DataChunk &args, ExpressionState &state, Vector &result) {
	// quack runs it on a connection of its own, never on a client's: called from a session, it could
	// only be someone probing it.
	if (IsQuackSession(state.GetContext())) {
		throw PermissionException("qod-node: qod_screen is quack's authorization callback");
	}
	auto &function = state.expr.Cast<BoundFunctionExpression>().Function();
	auto &authz = *function.GetDefinition()->GetExtraFunctionInfo().Cast<AuthzScalarInfo>().authz;
	for (idx_t row = 0; row < args.size(); row++) {
		auto connection_id = args.GetValue(0, row);
		auto sql = args.GetValue(1, row);
		if (connection_id.IsNull() || sql.IsNull()) {
			throw PermissionException(kNoPrincipal);
		}
		Installed recorded;
		auto text = sql.GetValue<string>();
		auto refusal = authz.Screen(connection_id.GetValue<string>(), text, &recorded);
		if (refusal.empty() && !recorded.handle.empty()) {
			refusal = ScreenContent(state.GetContext(), authz, recorded, text);
		}
		if (!refusal.empty()) {
			throw PermissionException(refusal);
		}
		result.SetValue(row, Value::BOOLEAN(true));
	}
}

// ---- post_bind, pre_optimize -----------------------------------------------------------------------

//! The manager's prelude as DuckDB binds it: CALL qod_session(...) is a projection of that one scan.
//! Prepared through the C++ API (Connection::Prepare, which DuckDB 2.0 runs as a PREPARE under a
//! generated name) it is that projection under a PREPARE, and executed again under an EXECUTE that
//! rebound it: no verdict is stored with it (Admit is never called), so every EXECUTE rebinds it and
//! qod_session verifies its handle again. quack runs a client's text as is, so on a node only the bare
//! form arrives.
bool IsPrelude(LogicalOperator &plan) {
	if ((plan.type == LogicalOperatorType::LOGICAL_PREPARE || plan.type == LogicalOperatorType::LOGICAL_EXECUTE) &&
	    plan.children.size() == 1) {
		return IsPrelude(*plan.children[0]);
	}
	if (plan.type != LogicalOperatorType::LOGICAL_PROJECTION || plan.children.size() != 1) {
		return false;
	}
	for (auto &expr : plan.expressions) {
		if (expr->GetExpressionClass() != ExpressionClass::BOUND_COLUMN_REF) {
			return false;
		}
	}
	auto &child = *plan.children[0];
	return child.type == LogicalOperatorType::LOGICAL_GET && child.children.empty() &&
	       child.Cast<LogicalGet>().function.bind == SessionBind;
}

//! The COPY formats the parse screen trusts by name (screen_rules, kCoreCopyFormats), each with the
//! extension DuckDB's own copy function of that name is registered by (copy_csv.cpp, copy_blob.cpp,
//! parquet_extension.cpp, json_extension.cpp and copy_json.cpp at v2.0.0; empty: the core).
const std::map<std::string, std::string> &CoreCopyOrigins() {
	static const std::map<std::string, std::string> origins = {
	    {"blob", ""},      {"csv", ""},       {"geojson", "json"}, {"geojsonl", "json"},
	    {"json", "json"},  {"jsonl", "json"}, {"ndjson", "json"},  {"parquet", "parquet"}};
	return origins;
}

//! Task 8 M7: the screen admits the COPY options of a core format by its name, so a COPY TO whose
//! copy function carries a core format's name runs only when that function is DuckDB's own: its
//! catalog entry registered by the extension (or the core) that defines it.
void RefuseForeignCopyFunction(ClientContext &context, LogicalOperator &op) {
	if (op.type == LogicalOperatorType::LOGICAL_COPY_TO_FILE) {
		auto name = StringUtil::Lower(op.Cast<LogicalCopyToFile>().function.name.GetIdentifierName());
		auto origin = CoreCopyOrigins().find(name);
		if (origin != CoreCopyOrigins().end()) {
			CatalogEntryRetriever retriever(context);
			auto &catalog = Catalog::GetSystemCatalog(context);
			auto entry = catalog.GetEntry(
			    retriever, EntryLookupInfo(CatalogType::COPY_FUNCTION_ENTRY,
			                               QualifiedName(catalog.GetName(), Identifier::DefaultSchema(), Identifier(name))),
			    OnEntryNotFound::RETURN_NULL);
			if (!entry || StringUtil::Lower(entry->extension_name.GetIdentifierName()) != origin->second) {
				throw PermissionException("QoD: statement uses a COPY format " + name +
				                          " that is not DuckDB's own, which QoD does not support");
			}
		}
	}
	for (auto &child : op.children) {
		RefuseForeignCopyFunction(context, *child);
	}
}

//! A refused decision as the client reads it. Every unavailable one is retryable (a 503, a timeout, a
//! transport failure): it says so, like the credential fetch does, in the words the manager maps to a
//! retryable failure (FlightSqlRouter.NodeAuthzUnavailable).
std::string RefusalMessage(const DecideResult &decided) {
	if (decided.status != DecideResult::Status::Unavailable) {
		return decided.Message();
	}
	std::string unavailable = "authorization unavailable";
	auto rest = decided.reason.compare(0, unavailable.size(), unavailable) == 0 ? decided.reason.substr(unavailable.size())
	                                                                             : ": " + decided.reason;
	return "QoD: " + unavailable + ", retry" + rest;
}

//! The verdict on `described` for `installed`: a local allow for a superuser or system principal,
//! else the decider's (from Task 7's cache or the issuing replica). Throws the refusal.
Admitted Decide(Gate &gate, const Installed &installed, const Described &described) {
	Admitted admitted {described.fingerprint, installed.principal.id, installed.policy_version, installed.ctx,
	                   kNeverExpires};
	if (installed.principal.superuser || installed.principal.system) {
		return admitted;
	}
	DecideCall call;
	call.replica = installed.replica;
	call.handle = installed.handle;
	call.kind = "plan";
	call.description = described.canonical;
	call.principal = installed.principal;
	call.policy_version = installed.policy_version;
	call.ctx = installed.ctx;
	auto result = gate.decider(call);
	if (result.status != DecideResult::Status::Allow) {
		throw PermissionException(RefusalMessage(result));
	}
	admitted.expires_at = result.has_verdict ? result.verdict.expires_at : gate.authz->Now();
	return admitted;
}

//! Describes `plan` and makes it the statement's: the verdict already held when it covers this plan,
//! else a new one. Throws kNoPrincipal on a quack connection whose principal is not current.
void Enforce(ClientContext &context, Gate &gate, LogicalOperator &plan) {
	auto session = context.registered_state->Get<SessionState>(SessionState::kKey);
	Installed installed;
	if (!session || !session->Snapshot(gate.authz->Now(), installed)) {
		throw PermissionException(kNoPrincipal);
	}
	if (IsPrelude(plan)) {
		session->AdmitPrelude();
		return;
	}
	auto text = session->StatementText(context);
	if (!text && !installed.principal.superuser && !installed.principal.system) {
		// Item 32 B judges the call names as written: a statement whose text is out of reach is refused.
		throw PermissionException("QoD: statement uses a statement whose text cannot be read, so its calls "
		                          "cannot be judged, which QoD does not support");
	}
	auto described = Describe(context, plan, installed.principal, text ? &*text : nullptr);
	if (!installed.principal.superuser && !installed.principal.system) {
		RefuseForeignCopyFunction(context, plan);
	}
	if (session->Covers(described.fingerprint)) {
		return;
	}
	session->Admit(Decide(gate, installed, described), plan);
}

void PostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	auto &context = input.context;
	if (!IsQuackSession(context)) {
		return;
	}
	auto &gate = *static_cast<GatePlannerInfo &>(*input.info).gate;
	if (!statement.plan) {
		auto session = context.registered_state->Get<SessionState>(SessionState::kKey);
		if (!session || !session->CurrentAt(gate.authz->Now())) {
			throw PermissionException(kNoPrincipal);
		}
		return;
	}
	Enforce(context, gate, *statement.plan);
}

//! Extensions loaded after qod-node may register a post_bind of their own that changes the plan
//! (Iceberg does): the plan the optimizer receives runs only under a verdict for itself.
void PreOptimize(OptimizerExtensionInput &input, unique_ptr<LogicalOperator> &plan) {
	auto &context = input.context;
	if (!plan || !IsQuackSession(context)) {
		return;
	}
	Enforce(context, *static_cast<GateOptimizerInfo &>(*input.info).gate, *plan);
}

} // namespace

bool SessionSnapshot(ClientContext &context, int64_t now_ms, Installed &out) {
	auto session = context.registered_state->Get<SessionState>(SessionState::kKey);
	return session && session->Snapshot(now_ms, out);
}

void RegisterNodeAuthz(DatabaseInstance &db, std::shared_ptr<NodeAuthz> authz, PlanDecider decider) {
	ExtensionLoader loader(db, "qod_node");

	TableFunction session("qod_session", {LogicalType::VARCHAR}, SessionScan, SessionBind, SessionInit);
	session.function_info = make_shared_ptr<AuthzTableInfo>(authz);
	loader.RegisterFunction(session);

	ScalarFunction screen("qod_screen", {LogicalType::VARCHAR, LogicalType::VARCHAR}, LogicalType::BOOLEAN,
	                      ScreenFunction, nullptr, nullptr, nullptr, LogicalType(LogicalTypeId::INVALID),
	                      FunctionStability::VOLATILE);
	screen.SetExtraFunctionInfo(make_shared_ptr<AuthzScalarInfo>(authz));
	loader.RegisterFunction(screen);

	auto gate = std::make_shared<Gate>();
	gate->authz = authz;
	if (decider) {
		gate->decider = std::move(decider);
	} else {
		gate->decider = [authz](const DecideCall &call) {
			return authz->Decider().Decide(call);
		};
	}

	PlannerExtension planner;
	planner.post_bind_function = PostBind;
	planner.planner_info = make_shared_ptr<GatePlannerInfo>(gate);
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);

	OptimizerExtension optimizer;
	optimizer.pre_optimize_function = PreOptimize;
	optimizer.optimizer_info = make_shared_ptr<GateOptimizerInfo>(gate);
	OptimizerExtension::Register(DBConfig::GetConfig(db), optimizer);
}

} // namespace qod
