#include "node_hooks.hpp"

#include "credentials.hpp"
#include "crypto.hpp"
#include "locations.hpp"
#include "screen_rules.hpp"

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
#include "duckdb/main/secret/secret.hpp"
#include "duckdb/main/secret/secret_manager.hpp"
#include "duckdb/planner/expression/bound_function_expression.hpp"
#include "duckdb/planner/operator/logical_reset.hpp"
#include "duckdb/planner/operator/logical_set.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <iostream>
#include <map>
#include <mutex>

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
		                      handle.ctx,       text,                  handle.replica};
		installed_ = true;
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

private:
	std::mutex lock_;
	bool installed_ = false;
	Installed current_;
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

struct AuthzPlannerInfo : public PlannerExtensionInfo {
	explicit AuthzPlannerInfo(std::shared_ptr<NodeAuthz> authz_p) : authz(std::move(authz_p)) {
	}
	std::shared_ptr<NodeAuthz> authz;
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
//! over a user's when it scopes more closely (decision 23, ShadowingScope). Every secret present is
//! listed, whether or not its type's extension is loaded yet: a temporary secret keeps its type name,
//! and a persistent one is deserialized here, which autoloads its type or throws (the caller refuses).
std::vector<std::string> NodeS3SecretScopes(ClientContext &context) {
	std::vector<std::string> scopes;
	auto &manager = SecretManager::Get(context);
	for (auto &entry : manager.AllSecrets(CatalogTransaction::GetSystemCatalogTransaction(context))) {
		auto type = StringUtil::Lower(entry.secret->GetType().GetIdentifierName());
		if (entry.storage_mode == SecretManager::CONNECTION_STORAGE_NAME ||
		    (type != "s3" && type != "r2" && type != "gcs" && type != "aws")) {
			continue;
		}
		for (auto &scope : entry.secret->GetScope()) {
			scopes.push_back(scope);
		}
	}
	return scopes;
}

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
	std::vector<std::string> node_scopes;
	try {
		node_scopes = NodeS3SecretScopes(context);
	} catch (std::exception &error) {
		// The cause can name the node's secret directory: the node's log has it, the client does not.
		std::cerr << "[qod-node] listing the node's S3 secrets failed: " << ErrorData(error).RawMessage() << std::endl;
		return "QoD: this node's own S3 secrets cannot be listed, so the statement's locations cannot be checked";
	}
	for (auto &location : outcome.facts.locations) {
		if (!ShadowingScope(location.resolved, user_scopes, node_scopes).empty()) {
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
	return decided.status == DecideResult::Status::Allow ? "" : decided.Message();
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

// ---- post_bind -----------------------------------------------------------------------------------

//! quack's callbacks: changing either from a session would switch the node's checks off. The whole
//! tree, not just its root: EXPLAIN ANALYZE runs the SET or RESET it wraps.
void RefuseCallbackSetting(LogicalOperator &op) {
	const Identifier *name = nullptr;
	if (op.type == LogicalOperatorType::LOGICAL_SET) {
		name = &op.Cast<LogicalSet>().name;
	} else if (op.type == LogicalOperatorType::LOGICAL_RESET) {
		name = &op.Cast<LogicalReset>().name;
	}
	if (name && (*name == "quack_authorization_function" || *name == "quack_authentication_function")) {
		throw PermissionException("qod-node: " + name->GetIdentifierName() + " cannot be changed");
	}
	for (auto &child : op.children) {
		RefuseCallbackSetting(*child);
	}
}

void PostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	auto &context = input.context;
	if (!IsQuackSession(context)) {
		return;
	}
	auto &authz = *static_cast<AuthzPlannerInfo &>(*input.info).authz;
	auto session = context.registered_state->Get<SessionState>(SessionState::kKey);
	if (!session || !session->CurrentAt(authz.Now())) {
		throw PermissionException(kNoPrincipal);
	}
	if (statement.plan) {
		RefuseCallbackSetting(*statement.plan);
	}
}

} // namespace

void RegisterNodeAuthz(DatabaseInstance &db, std::shared_ptr<NodeAuthz> authz) {
	ExtensionLoader loader(db, "qod_node");

	TableFunction session("qod_session", {LogicalType::VARCHAR}, SessionScan, SessionBind, SessionInit);
	session.function_info = make_shared_ptr<AuthzTableInfo>(authz);
	loader.RegisterFunction(session);

	ScalarFunction screen("qod_screen", {LogicalType::VARCHAR, LogicalType::VARCHAR}, LogicalType::BOOLEAN,
	                      ScreenFunction, nullptr, nullptr, nullptr, LogicalType(LogicalTypeId::INVALID),
	                      FunctionStability::VOLATILE);
	screen.SetExtraFunctionInfo(make_shared_ptr<AuthzScalarInfo>(authz));
	loader.RegisterFunction(screen);

	PlannerExtension planner;
	planner.post_bind_function = PostBind;
	planner.planner_info = make_shared_ptr<AuthzPlannerInfo>(std::move(authz));
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

} // namespace qod
