#include "plan_probe.hpp"

#include "duckdb/catalog/catalog_entry/schema_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/common/enums/logical_operator_type.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/prepared_statement_data.hpp"
#include "duckdb/optimizer/optimizer_extension.hpp"
#include "duckdb/planner/expression/bound_aggregate_expression.hpp"
#include "duckdb/planner/expression/bound_function_expression.hpp"
#include "duckdb/planner/expression/bound_subquery_expression.hpp"
#include "duckdb/planner/expression_iterator.hpp"
#include "duckdb/planner/logical_operator_visitor.hpp"
#include "duckdb/planner/operator/logical_delete.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator/logical_insert.hpp"
#include "duckdb/planner/operator/logical_secure_view.hpp"
#include "duckdb/planner/operator/logical_update.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <iostream>
#include <mutex>
#include <set>
#include <sstream>

using namespace duckdb;

namespace qod {
namespace {

// quack tags each client connection's context with this key before its first statement; DuckLake
// tags the internal connection it runs its metadata queries on with the second one.
constexpr const char *QuackSessionKey = "quack_session";
constexpr const char *DuckLakeInternalKey = "ducklake_internal_connection";
constexpr const char *ProbeStateKey = "qod_probe";

std::mutex output_lock;

struct Resolved {
	std::set<string> reads;
	std::set<string> writes;
	std::set<string> table_functions;
	std::set<string> functions;
	std::set<string> boundaries;
};

string Join(const std::set<string> &values) {
	std::ostringstream out;
	bool first = true;
	for (auto &value : values) {
		if (!first) {
			out << ",";
		}
		out << value;
		first = false;
	}
	return out.str();
}

string QualifiedTable(TableCatalogEntry &table) {
	return table.ParentSchema().GetQualifiedName(table.name).ToString();
}

void WalkOperator(LogicalOperator &op, Resolved &resolved);

void WalkExpression(Expression &expr, Resolved &resolved) {
	switch (expr.GetExpressionClass()) {
	case ExpressionClass::BOUND_SUBQUERY: {
		// Still an expression at post_bind: its plan is only lowered into operators afterwards.
		auto &subquery = expr.Cast<BoundSubqueryExpression>().SubqueryMutable();
		if (subquery.plan) {
			WalkOperator(*subquery.plan, resolved);
		}
		break;
	}
	case ExpressionClass::BOUND_FUNCTION:
		resolved.functions.insert(
		    expr.Cast<BoundFunctionExpression>().Function().GetDefinition()->GetQualifiedName().ToString());
		break;
	case ExpressionClass::BOUND_AGGREGATE:
		resolved.functions.insert(
		    expr.Cast<BoundAggregateExpression>().Function().GetDefinition()->GetQualifiedName().ToString());
		break;
	default:
		break;
	}
	ExpressionIterator::EnumerateChildren(expr, [&](Expression &child) { WalkExpression(child, resolved); });
}

void WalkOperator(LogicalOperator &op, Resolved &resolved) {
	switch (op.type) {
	case LogicalOperatorType::LOGICAL_GET: {
		auto &get = op.Cast<LogicalGet>();
		auto table = get.GetTable();
		if (table) {
			std::ostringstream columns;
			bool first = true;
			for (auto &column : get.GetColumnIds()) {
				if (!first) {
					columns << " ";
				}
				columns << get.GetColumnName(column).GetIdentifierName();
				first = false;
			}
			resolved.reads.insert(QualifiedTable(*table) + "(" + columns.str() + ")");
		} else {
			resolved.table_functions.insert(get.GetName());
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_INSERT:
		resolved.writes.insert("insert:" + QualifiedTable(op.Cast<LogicalInsert>().table));
		break;
	case LogicalOperatorType::LOGICAL_UPDATE:
		resolved.writes.insert("update:" + QualifiedTable(op.Cast<LogicalUpdate>().table));
		break;
	case LogicalOperatorType::LOGICAL_DELETE:
		resolved.writes.insert("delete:" + QualifiedTable(op.Cast<LogicalDelete>().table));
		break;
	case LogicalOperatorType::LOGICAL_SECURE_VIEW: {
		auto &boundary = op.Cast<LogicalSecureView>();
		resolved.boundaries.insert(boundary.has_source ? boundary.source_name.ToString() : boundary.view_name);
		break;
	}
	default:
		break;
	}
	LogicalOperatorVisitor::EnumerateExpressions(
	    op, [&](unique_ptr<Expression> *child) { WalkExpression(**child, resolved); });
	for (auto &child : op.children) {
		WalkOperator(*child, resolved);
	}
}

string ConnectionKind(ClientContext &context) {
	if (context.registered_state->Get<ClientContextState>(QuackSessionKey)) {
		return "quack-session";
	}
	if (context.registered_state->Get<ClientContextState>(DuckLakeInternalKey)) {
		return "ducklake-internal";
	}
	return "other";
}

void Report(const char *hook, ClientContext &context, LogicalOperator &plan) {
	Resolved resolved;
	WalkOperator(plan, resolved);
	std::lock_guard<std::mutex> guard(output_lock);
	std::cerr << "[" << hook << "] ctx=" << static_cast<const void *>(&context) << " kind=" << ConnectionKind(context)
	          << " root=" << LogicalOperatorToString(plan.type) << " reads=[" << Join(resolved.reads) << "] writes=["
	          << Join(resolved.writes) << "] table_functions=[" << Join(resolved.table_functions)
	          << "] functions=[" << Join(resolved.functions) << "] secure_views=[" << Join(resolved.boundaries) << "]"
	          << std::endl;
}

class ProbeState : public ClientContextState {
public:
	// DuckDB calls OnFinalizePrepare only when some registered state can request a rebind, and it
	// asks before binding (client_context.cpp CreatePreparedStatement): the state must exist before
	// the statement it is meant to see.
	bool CanRequestRebind() override {
		return true;
	}

	RebindQueryInfo OnFinalizePrepare(ClientContext &context, PreparedStatementData &prepared,
	                                  PreparedStatementMode mode) override {
		std::lock_guard<std::mutex> guard(output_lock);
		std::cerr << "[finalize] ctx=" << static_cast<const void *>(&context)
		          << " statement=" << StatementTypeToString(prepared.statement_type) << std::endl;
		return RebindQueryInfo::DO_NOT_REBIND;
	}
};

void PostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	input.context.registered_state->GetOrCreate<ProbeState>(ProbeStateKey);
	if (statement.plan) {
		Report("post_bind", input.context, *statement.plan);
	}
}

void PreOptimize(OptimizerExtensionInput &input, unique_ptr<LogicalOperator> &plan) {
	if (plan) {
		Report("pre_optimize", input.context, *plan);
	}
}

} // namespace

void RegisterProbes(DBConfig &config) {
	PlannerExtension planner;
	planner.post_bind_function = PostBind;
	PlannerExtension::Register(config, planner);
	OptimizerExtension optimizer;
	optimizer.pre_optimize_function = PreOptimize;
	OptimizerExtension::Register(config, optimizer);
}

} // namespace qod
