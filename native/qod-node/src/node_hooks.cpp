#include "node_hooks.hpp"

#include "credentials.hpp"
#include "crypto.hpp"

#include "duckdb/catalog/catalog_transaction.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/common/exception.hpp"
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
	void Install(NodeAuthz &authz, const Handle &handle) {
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
		current_ = Installed {handle.principal, handle.policy_version, handle.exp, handle.nonce, handle.ctx};
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
	Handle handle;
	try {
		handle = authz.Verify(input.inputs[0].GetValue<string>());
	} catch (std::invalid_argument &error) {
		throw PermissionException(kHandleRefused + std::string(error.what()));
	}
	context.registered_state->GetOrCreate<SessionState>(SessionState::kKey)->Install(authz, handle);
	// Decision 23: the principal's own S3 credentials, as this connection's secrets.
	context.registered_state->GetOrCreate<SessionState>(SessionState::kKey)
	    ->SyncCredentials(context, authz, handle, input.inputs[0].GetValue<string>());
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
		auto refusal = authz.Screen(connection_id.GetValue<string>(), sql.GetValue<string>());
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
