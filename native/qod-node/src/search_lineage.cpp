#include "search_lineage.hpp"

#include "describer.hpp"
#include "screen_rules.hpp"

#include "duckdb/catalog/catalog_entry_retriever.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/parser/expression/constant_expression.hpp"
#include "duckdb/parser/expression/function_expression.hpp"
#include "duckdb/parser/expression/subquery_expression.hpp"
#include "duckdb/parser/query_node/select_node.hpp"
#include "duckdb/parser/statement/select_statement.hpp"
#include "duckdb/parser/tableref/basetableref.hpp"
#include "duckdb/parser/tableref/subqueryref.hpp"
#include "duckdb/parser/tableref/table_function_ref.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/prepared_statement_data.hpp"
#include "duckdb/parser/parsed_expression_iterator.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/statement/execute_statement.hpp"
#include "duckdb/parser/statement/explain_statement.hpp"
#include "duckdb/parser/statement/insert_statement.hpp"
#include "duckdb/parser/statement/prepare_statement.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/expression/bound_columnref_expression.hpp"
#include "duckdb/planner/operator/logical_join.hpp"
#include "duckdb/planner/operator/logical_projection.hpp"

#include <cstdio>

namespace qod::search {

using namespace duckdb;

namespace {

// Rules 1, 2, 7 and 9 refuse the statement's shape, not the principal: an ordinary error (INVALID_ARGUMENT),
// never booked as a denial (the plan's error table, review I2, lead ruling 2026-11-17).
[[noreturn]] void NotGoverned(const std::string &why) {
	throw InvalidInputException("QoD: search_input_not_governed: " + why);
}

[[noreturn]] void WrongTable(const std::string &why) {
	throw InvalidInputException("QoD: search_input_wrong_table: " + why);
}

[[noreturn]] void NotConstant(const std::string &arg) {
	throw InvalidInputException("QoD: search_argument_not_constant: " + arg);
}

//! True for the joins DuckDB 2.0 plans for a predicate subquery at post_bind (Q3, change 2): the
//! target is their left child.
bool PredicateJoin(LogicalOperator &op) {
	switch (op.type) {
	case LogicalOperatorType::LOGICAL_CROSS_PRODUCT:
		return true;
	case LogicalOperatorType::LOGICAL_COMPARISON_JOIN:
		return op.Cast<LogicalJoin>().join_type == JoinType::MARK;
	case LogicalOperatorType::LOGICAL_DEPENDENT_JOIN: {
		auto type = op.Cast<LogicalJoin>().join_type;
		return type == JoinType::MARK || type == JoinType::SINGLE;
	}
	default:
		return false;
	}
}

bool IsJoin(LogicalOperatorType type) {
	switch (type) {
	case LogicalOperatorType::LOGICAL_CROSS_PRODUCT:
	case LogicalOperatorType::LOGICAL_COMPARISON_JOIN:
	case LogicalOperatorType::LOGICAL_DEPENDENT_JOIN:
	case LogicalOperatorType::LOGICAL_ANY_JOIN:
	case LogicalOperatorType::LOGICAL_DELIM_JOIN:
	case LogicalOperatorType::LOGICAL_ASOF_JOIN:
	case LogicalOperatorType::LOGICAL_POSITIONAL_JOIN:
		return true;
	default:
		return false;
	}
}

struct Traced {
	LogicalGet *target = nullptr;
	bool filtered = false;
};

//! Rule 1: follows the input's one output column, `binding`, from `op` down to the target scan. Reads
//! the bound column references themselves, never operator or output names (change 5).
Traced Trace(LogicalOperator &root, ColumnBinding binding) {
	Traced traced;
	LogicalOperator *op = &root;
	bool under_filter = false;
	while (true) {
		switch (op->type) {
		case LogicalOperatorType::LOGICAL_PROJECTION: {
			auto &projection = op->Cast<LogicalProjection>();
			auto column = binding.column_index.GetIndex();
			if (binding.table_index != projection.table_index || column >= projection.expressions.size()) {
				NotGoverned("a column that does not come from the target scan");
			}
			auto &expr = *projection.expressions[column];
			if (expr.GetExpressionClass() != ExpressionClass::BOUND_COLUMN_REF) {
				NotGoverned("a column computed by an expression");
			}
			binding = expr.Cast<BoundColumnRefExpression>().Binding();
			if (op->children.size() != 1) {
				NotGoverned("a projection with no input");
			}
			op = op->children[0].get();
			under_filter = false;
			break;
		}
		case LogicalOperatorType::LOGICAL_FILTER:
			if (op->children.size() != 1) {
				NotGoverned("a filter with no input");
			}
			traced.filtered = true;
			op = op->children[0].get();
			under_filter = true;
			break;
		case LogicalOperatorType::LOGICAL_GET: {
			auto &get = op->Cast<LogicalGet>();
			if (get.function.GetName().GetIdentifierName() != "ducklake_scan" || !get.GetTable()) {
				NotGoverned("a scan that is not of a DuckLake table (" + get.function.GetName().GetIdentifierName() + ")");
			}
			if (!get.children.empty()) {
				NotGoverned("a scan with an input");
			}
			if (get.extra_info.sample_options) {
				NotGoverned("a sample");
			}
			auto column = binding.column_index.GetIndex();
			auto &ids = get.GetColumnIds();
			if (binding.table_index != get.table_index || !get.projection_ids.empty() || column >= ids.size()) {
				NotGoverned("a column that does not come from the target scan");
			}
			if (!ids[column].IsRowIdColumn()) {
				NotGoverned("a column other than the target scan's rowid");
			}
			traced.target = &get;
			return traced;
		}
		default:
			if (IsJoin(op->type)) {
				if (!under_filter) {
					NotGoverned("a join outside a predicate subquery's filter");
				}
				if (!PredicateJoin(*op)) {
					NotGoverned("a join of a kind no predicate subquery plans (" + LogicalOperatorToString(op->type) + ")");
				}
				if (op->children.size() != 2) {
					NotGoverned("a join with no target side");
				}
				// The target is the left child; a filter holding several subqueries stacks their joins, each
				// over the previous one's left side (still the filter's own joins).
				op = op->children[0].get();
				break;
			}
			NotGoverned(LogicalOperatorToString(op->type));
		}
	}
}

void Find(ClientContext &context, LogicalOperator &op, bool in_secure_view, const std::string &pool,
          std::vector<SearchCall> &calls) {
	auto secure = in_secure_view || op.type == LogicalOperatorType::LOGICAL_SECURE_VIEW;
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		auto &get = op.Cast<LogicalGet>();
		auto data = SearchCallData(get);
		if (data) {
			auto pos = pool.rfind('/');
			auto last = pos == std::string::npos ? pool : pool.substr(pos + 1);
			if (StringUtil::StartsWith(last, "__br_")) {
				throw InvalidInputException("QoD: search_branch_unsupported");
			}
			if (secure) {
				NotGoverned("a search call inside a secure view");
			}
			if (get.children.size() != 1) {
				NotGoverned("a call with no input subquery");
			}
			auto &input = *get.children[0];
			auto bindings = input.GetColumnBindings();
			if (bindings.size() != 1) {
				NotGoverned("an input of " + std::to_string(bindings.size()) + " columns");
			}
			auto traced = Trace(input, bindings[0]);
			auto named = ResolveSearchTable(context, data->table);
			if (!named) {
				WrongTable(data->table + " does not name a table");
			}
			auto scanned = traced.target->GetTable();
			if (!scanned || scanned->oid != named->oid) {
				WrongTable("the input scans " + (scanned ? CanonicalTableName(*scanned) : std::string("another relation")) +
				           ", not " + CanonicalTableName(*named));
			}
			std::vector<std::string> columns;
			for (auto &given : data->columns) {
				auto column = TableColumnName(*named, given);
				if (column.empty()) {
					// A refusal of the statement's shape, not of the principal (InvalidInput, as every other site
					// of search_columns_unsupported): never booked as a denial.
					throw InvalidInputException("QoD: search_columns_unsupported: " + given + " is not a column of " +
					                            CanonicalTableName(*named));
				}
				columns.push_back(column);
			}
			SearchCall call;
			call.call = &get;
			call.target = traced.target;
			call.index = calls.size();
			call.table = CanonicalTableName(*named);
			call.columns = std::move(columns);
			call.projections_only = !traced.filtered;
			calls.push_back(std::move(call));
		}
	}
	for (auto &child : op.children) {
		if (child) {
			Find(context, *child, secure, pool, calls);
		}
	}
}

//! True when `expr` is a constant, or a list literal of constants (`['body']` parses as list_value).
bool ConstantList(const ParsedExpression &expr) {
	if (expr.GetExpressionClass() == ExpressionClass::CONSTANT) {
		return true;
	}
	if (expr.GetExpressionClass() != ExpressionClass::FUNCTION) {
		return false;
	}
	auto &call = expr.Cast<FunctionExpression>();
	auto name = StringUtil::Lower(call.GetQualifiedName().Name().GetIdentifierName());
	if (call.GetQualifiedName().Path().size() != 1 || (name != "list_value" && name != "array_value")) {
		return false;
	}
	for (auto &arg : call.GetArguments()) {
		auto child = const_cast<FunctionArgument &>(arg).GetExpressionMutable().get();
		if (arg.HasName() || !child || child->GetExpressionClass() != ExpressionClass::CONSTANT) {
			return false;
		}
	}
	return true;
}

//! The parse half of rule 1: no view, plain or secure, no join and no table function on the input's FROM
//! path. A view that selects
//! `rowid AS rid` plans exactly like the rewriter's wrap (Q3 R1b), so only the names it was bound
//! from tell them apart. Everything else on the path is the plan walk's.
void NoViewOnPath(ClientContext &context, const QueryNode &node, idx_t depth) {
	if (depth > 64) {
		NotGoverned("an input nested too deeply");
	}
	if (node.type != QueryNodeType::SELECT_NODE) {
		return;
	}
	auto &select = node.Cast<SelectNode>();
	if (!select.from_table) {
		return;
	}
	auto &ref = *select.from_table;
	if (ref.type == TableReferenceType::BASE_TABLE) {
		auto &base = ref.Cast<BaseTableRef>();
		CatalogEntryRetriever retriever(context);
		optional_ptr<CatalogEntry> entry;
		try {
			entry = retriever.GetEntry(
			    EntryLookupInfo(CatalogType::TABLE_ENTRY, Binder::BindTableName(retriever, base.GetQualifiedName())),
			    OnEntryNotFound::RETURN_NULL);
		} catch (std::exception &) {
			entry = nullptr;
		}
		if (entry && entry->type == CatalogType::VIEW_ENTRY) {
			NotGoverned("a view");
		}
	} else if (ref.type == TableReferenceType::SUBQUERY) {
		auto &sub = ref.Cast<SubqueryRef>();
		if (sub.subquery && sub.subquery->node) {
			NoViewOnPath(context, *sub.subquery->node, depth + 1);
		}
	} else if (ref.type == TableReferenceType::JOIN) {
		// A join written in FROM, of any type, comma joins included (spec 4.2 rule 1): under a WHERE a
		// comma or CROSS join plans at post_bind as FILTER over CROSS_PRODUCT, the shape an uncorrelated
		// predicate subquery takes, so only the text tells them apart (review I1).
		NotGoverned("a join written in FROM");
} else if (ref.type == TableReferenceType::TABLE_FUNCTION) {
		// Any table function on the input's FROM path (spec 4.2 rule 1), query() and query_table() above all:
		// their SQL is bound as a plan of its own, so a view or a FROM join inside them plans like the rule-3
		// wrap or a predicate subquery and the plan walk alone would admit it (security review I1).
		NotGoverned("a table function");
	}
}

//! The search calls of a statement's text, each judged by rule 7 and the parse half of rule 1.
struct TextCalls {
	explicit TextCalls(ClientContext &context_p) : context(context_p) {
	}
	ClientContext &context;
	idx_t count = 0;
	//! qod_search_build_segment calls the text shows (outside every query() literal).
	idx_t builds = 0;
	int query_depth = 0;

	void Statement(SQLStatement &statement, idx_t depth) {
		if (depth > 8) {
			NotConstant("a statement nested too deeply");
		}
		switch (statement.type) {
		case StatementType::SELECT_STATEMENT: {
			auto &select = statement.Cast<SelectStatement>();
			if (select.node) {
				Node(*select.node, 0);
			}
			break;
		}
		case StatementType::INSERT_STATEMENT: {
			auto &insert = statement.Cast<InsertStatement>();
			if (insert.node) {
				Node(*insert.node, 0);
			}
			break;
		}
		case StatementType::EXPLAIN_STATEMENT: {
			auto &explain = statement.Cast<ExplainStatement>();
			if (explain.stmt) {
				Statement(*explain.stmt, depth + 1);
			}
			break;
		}
		case StatementType::PREPARE_STATEMENT: {
			auto &prepare = statement.Cast<PrepareStatement>();
			if (prepare.statement) {
				Statement(*prepare.statement, depth + 1);
			}
			break;
		}
		case StatementType::EXECUTE_STATEMENT: {
			// The prepared statement an EXECUTE binds again.
			auto &prepared = ClientData::Get(context).prepared_statements;
			auto found = prepared.find(statement.Cast<ExecuteStatement>().name);
			if (found != prepared.end() && found->second && found->second->unbound_statement) {
				Statement(*found->second->unbound_statement, depth + 1);
			}
			break;
		}
		default:
			// Any other statement shows no call: a plan holding one is refused (fail closed).
			break;
		}
	}

	void Node(QueryNode &node, idx_t depth) {
		if (depth > 256) {
			NotConstant("a statement nested too deeply");
		}
		ParsedExpressionIterator::EnumerateQueryNodeChildren(
		    node, [&](unique_ptr<ParsedExpression> &child) { Expr(child, depth + 1); },
		    [&](TableRef &ref) { Ref(ref, depth + 1); });
	}

	void Expr(unique_ptr<ParsedExpression> &expr, idx_t depth) {
		if (!expr) {
			return;
		}
		if (depth > 256) {
			NotConstant("a statement nested too deeply");
		}
		if (expr->GetExpressionClass() == ExpressionClass::SUBQUERY) {
			auto &subquery = expr->Cast<SubqueryExpression>();
			if (subquery.Subquery() && subquery.Subquery()->node) {
				Node(*subquery.SubqueryMutable()->node, depth + 1);
			}
		}
		ParsedExpressionIterator::EnumerateChildren(*expr, [&](unique_ptr<ParsedExpression> &child) {
			Expr(child, depth + 1);
		});
	}

	void QueryLiteral(FunctionExpression &call, idx_t depth) {
		auto sql = qod::QueryLiteral(call);
		if (!sql) {
			NotGoverned("a query() with a computed SQL string in a statement calling a search function");
		}
		if (++query_depth > kMaxQueryNesting) {
			NotConstant("query() nested deeper than " + std::to_string(kMaxQueryNesting));
		}
		Parser parser(context);
		parser.ParseQuery(*sql);
		for (auto &statement : parser.statements) {
			Statement(*statement, 0);
		}
		query_depth--;
	}

	void Ref(TableRef &ref, idx_t depth) {
		if (ref.type != TableReferenceType::TABLE_FUNCTION) {
			return;
		}
		auto &function = ref.Cast<TableFunctionRef>();
		if (!function.function || function.function->GetExpressionClass() != ExpressionClass::FUNCTION) {
			return;
		}
		auto &call = function.function->Cast<FunctionExpression>();
		auto name = StringUtil::Lower(call.GetQualifiedName().Name().GetIdentifierName());
		if (name == "query") {
			// DuckDB binds query()'s literal as part of the statement: its calls are the statement's own.
			QueryLiteral(call, depth);
			return;
		}
		auto searching = name == "qod_search" || name == "qod_search_matches";
		auto search_name = searching || name == "qod_search_build_segment" || name == "qod_knn" || name == "qod_hybrid";
		if (search_name && query_depth > 0) {
			// Spec 4.2 rule 1: a search function inside a query() body is refused for every principal, at any
			// depth, before its arguments are judged: rules 1 and 7 judge the statement's own text, and the
			// row-policy rewriter never rewrites inside a literal (security re-review n1).
			NotGoverned("a search function called inside a query() body");
		}
		if (name == "qod_search_build_segment") {
			builds++;
			return;
		}
		if (!searching) {
			return;
		}
		count++;
		std::vector<ParsedExpression *> positional;
		for (auto &arg : call.GetArguments()) {
			if (!arg.HasName()) {
				positional.push_back(const_cast<FunctionArgument &>(arg).GetExpressionMutable().get());
			}
		}
		if (positional.size() >= 2 && (!positional[1] || positional[1]->GetExpressionClass() != ExpressionClass::CONSTANT)) {
			NotConstant("table");
		}
		if (positional.size() >= 3 && (!positional[2] || !ConstantList(*positional[2]))) {
			NotConstant("columns");
		}
		if (!positional.empty() && positional[0] && positional[0]->GetExpressionClass() == ExpressionClass::SUBQUERY) {
			auto &input = positional[0]->Cast<SubqueryExpression>();
			if (input.Subquery() && input.Subquery()->node) {
				NoViewOnPath(context, *input.Subquery()->node, 0);
			}
		}
	}
};

} // namespace

unique_ptr<FunctionData> SearchBindData::Copy() const {
	auto copy = make_uniq<SearchBindData>();
	copy->matches = matches;
	copy->table = table;
	copy->columns = columns;
	copy->query = query;
	copy->k = k;
	copy->with_score = with_score;
	copy->target = target;
	return std::move(copy);
}

bool SearchBindData::Equals(const FunctionData &other_p) const {
	auto &other = other_p.Cast<SearchBindData>();
	return matches == other.matches && table == other.table && columns == other.columns && query == other.query &&
	       k == other.k && with_score == other.with_score && target == other.target;
}

SearchBindData *SearchCallData(LogicalGet &get) {
	return get.bind_data ? dynamic_cast<SearchBindData *>(get.bind_data.get()) : nullptr;
}

optional_ptr<TableCatalogEntry> ResolveSearchTable(ClientContext &context, const std::string &table) {
	QualifiedName name;
	std::string error;
	if (!QualifiedName::TryParse(table, name, error)) {
		return nullptr;
	}
	try {
		CatalogEntryRetriever retriever(context);
		auto entry = retriever.GetEntry(EntryLookupInfo(CatalogType::TABLE_ENTRY, Binder::BindTableName(retriever, name)),
		                                OnEntryNotFound::RETURN_NULL);
		if (!entry || entry->type != CatalogType::TABLE_ENTRY) {
			return nullptr;
		}
		return &entry->Cast<TableCatalogEntry>();
	} catch (std::exception &) {
		return nullptr;
	}
}

std::string CanonicalTableName(TableCatalogEntry &table) {
	return StringUtil::Lower(table.ParentCatalog().GetName().GetIdentifierName() + "." +
	                         table.ParentSchema().name.GetIdentifierName() + "." + table.name.GetIdentifierName());
}

std::string TableColumnName(TableCatalogEntry &table, const std::string &column) {
	for (auto &definition : table.GetColumns().Logical()) {
		if (StringUtil::CIEquals(definition.Name().GetIdentifierName(), column)) {
			return definition.Name().GetIdentifierName();
		}
	}
	return "";
}

void JudgeSearchText(ClientContext &context, LogicalOperator &plan, const std::string *statement_text) {
	if (!statement_text) {
		NotConstant("the statement's text cannot be read, so its search arguments cannot be judged");
	}
	TextCalls text(context);
	try {
		Parser parser(context);
		parser.ParseQuery(*statement_text);
		for (auto &statement : parser.statements) {
			text.Statement(*statement, 0);
		}
	} catch (PermissionException &) {
		throw;
	} catch (InvalidInputException &e) {
		if (ErrorData(e).RawMessage().rfind("QoD: search_", 0) == 0) {
			throw;
		}
		NotConstant("the statement's text could not be parsed again");
	} catch (std::exception &) {
		NotConstant("the statement's text could not be parsed again");
	}
	JudgeSearchBodies(context, plan, *statement_text);
}

std::vector<SearchCall> FindSearchCalls(ClientContext &context, LogicalOperator &plan, const std::string *statement_text,
                                        const std::string &pool) {
	std::vector<SearchCall> calls;
	Find(context, plan, false, pool, calls);
	if (calls.empty()) {
		return calls;
	}
	if (!statement_text) {
		NotConstant("the statement's text cannot be read, so its search arguments cannot be judged");
	}
	TextCalls text(context);
	try {
		Parser parser(context);
		parser.ParseQuery(*statement_text);
		for (auto &statement : parser.statements) {
			text.Statement(*statement, 0);
		}
	} catch (PermissionException &) {
		throw;
	} catch (InvalidInputException &e) {
		// The walk's own refusals (rules 1 and 2) stand as they are.
		if (ErrorData(e).RawMessage().rfind("QoD: search_", 0) == 0) {
			throw;
		}
		NotConstant("the statement's text could not be parsed again");
	} catch (std::exception &) {
		NotConstant("the statement's text could not be parsed again");
	}
	// A call in a view, macro, table macro or query() body, at any depth: refused for every principal
	// (review I2). The count stays as a second, fail-closed check.
	JudgeSearchBodies(context, plan, *statement_text);
	if (text.count < calls.size()) {
		NotConstant("a search call the statement's text does not show");
	}
	return calls;
}

std::string SegmentPath(const std::string &bundle_root, const std::string &index_id, const std::string &bundle_key) {
	auto root = bundle_root;
	while (root.size() > 1 && root.back() == '/') {
		root.pop_back();
	}
	return root + "/.qod_search/" + index_id + "/" + bundle_key + ".seg";
}

} // namespace qod::search
