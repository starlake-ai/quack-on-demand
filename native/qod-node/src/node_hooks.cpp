#include "node_hooks.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/function/scalar_function.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/planner/expression/bound_function_expression.hpp"
#include "duckdb/planner/operator/logical_reset.hpp"
#include "duckdb/planner/operator/logical_set.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <mutex>

namespace qod {
namespace {

using namespace duckdb;

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
		current_ = Installed {handle.principal, handle.policy_version, handle.exp, handle.nonce};
		installed_ = true;
	}

	//! True when a principal is installed and its handle has not expired at `now_ms`.
	bool CurrentAt(int64_t now_ms) {
		std::lock_guard<std::mutex> guard(lock_);
		return installed_ && current_.exp > now_ms;
	}

private:
	std::mutex lock_;
	bool installed_ = false;
	Installed current_;
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
