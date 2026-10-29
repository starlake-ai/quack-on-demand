#include "describer.hpp"

#include "crypto.hpp"
#include "file_functions.hpp"
#include "function_classes.hpp"
#include "json.hpp"
#include "json_writer.hpp"
#include "locations.hpp"
#include "screen_rules.hpp"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/index_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/macro_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/schema_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/type_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/view_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry_retriever.hpp"
#include "duckdb/catalog/catalog_search_path.hpp"
#include "duckdb/common/enum_util.hpp"
#include "duckdb/common/enums/view_security_type.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/execution/operator/helper/physical_set.hpp"
#include "duckdb/function/lambda_functions.hpp"
#include "duckdb/function/scalar_macro_function.hpp"
#include "duckdb/function/table_macro_function.hpp"
#include "duckdb/function/scalar/sequence_utils.hpp"
#include "duckdb/main/attached_database.hpp"
#include "duckdb/main/client_config.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/parser/constraints/foreign_key_constraint.hpp"
#include "duckdb/parser/expression/columnref_expression.hpp"
#include "duckdb/parser/expression/constant_expression.hpp"
#include "duckdb/parser/parsed_data/alter_info.hpp"
#include "duckdb/parser/parsed_data/create_index_info.hpp"
#include "duckdb/parser/parsed_data/create_table_info.hpp"
#include "duckdb/parser/parsed_data/create_trigger_info.hpp"
#include "duckdb/parser/parsed_data/create_view_info.hpp"
#include "duckdb/parser/parsed_data/detach_info.hpp"
#include "duckdb/parser/parsed_data/drop_info.hpp"
#include "duckdb/parser/parsed_data/extra_drop_info.hpp"
#include "duckdb/parser/parsed_data/load_info.hpp"
#include "duckdb/parser/parsed_data/transaction_info.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/statement/copy_statement.hpp"
#include "duckdb/parser/statement/create_statement.hpp"
#include "duckdb/parser/statement/explain_statement.hpp"
#include "duckdb/parser/statement/prepare_statement.hpp"
#include "duckdb/parser/expression/list.hpp"
#include "duckdb/parser/parsed_expression_iterator.hpp"
#include "duckdb/parser/query_node/list.hpp"
#include "duckdb/parser/tableref/list.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/constraints/bound_check_constraint.hpp"
#include "duckdb/planner/expression/list.hpp"
#include "duckdb/planner/expression_iterator.hpp"
#include "duckdb/planner/logical_operator_visitor.hpp"
#include "duckdb/planner/operator/list.hpp"
#include "duckdb/planner/parsed_data/bound_create_table_info.hpp"

#include <algorithm>
#include <map>
#include <set>
#include <utility>
#include <vector>

namespace qod {
namespace {

using namespace duckdb;

//! The elements of an order-free array, keyed by their canonical bytes: iterating it is the
//! contract's order (std::string compares bytes as unsigned char), and an exact duplicate is kept
//! once.
using Elements = std::map<std::string, JsonValue>;

void Add(Elements &elements, JsonValue element) {
	auto canonical = CanonicalJson(element);
	elements.emplace(std::move(canonical), std::move(element));
}

JsonValue ToArray(const Elements &elements) {
	std::vector<JsonValue> values;
	for (auto &entry : elements) {
		values.push_back(entry.second);
	}
	return JsonArray(std::move(values));
}

JsonValue Strings(const std::vector<std::string> &values) {
	std::vector<JsonValue> array;
	for (auto &value : values) {
		array.push_back(JsonString(value));
	}
	return JsonArray(std::move(array));
}

JsonValue StringOrNull(const std::string &value) {
	return value.empty() ? JsonNull() : JsonString(value);
}

std::string Text(const Identifier &identifier) {
	return identifier.GetIdentifierName();
}

//! Task 2's File class, embedded as a sorted array of lowercase names.
bool IsFileFunction(const std::string &name) {
	auto lower = StringUtil::Lower(name);
	for (auto file_function : kFileFunctions) {
		if (lower == file_function) {
			return true;
		}
	}
	return false;
}

[[noreturn]] void Unsupported(const std::string &what) {
	throw PermissionException("QoD: statement uses " + what + ", which QoD does not support");
}

[[noreturn]] void Locked(const std::string &name) {
	throw PermissionException("QoD: " + name + " cannot be changed");
}

//! The objects a catalog type names in a description, or empty for one no description carries.
std::string KindOf(CatalogType type) {
	switch (type) {
	case CatalogType::TABLE_ENTRY:
		return "table";
	case CatalogType::VIEW_ENTRY:
		return "view";
	case CatalogType::SEQUENCE_ENTRY:
		return "sequence";
	case CatalogType::INDEX_ENTRY:
		return "index";
	case CatalogType::MACRO_ENTRY:
	case CatalogType::TABLE_MACRO_ENTRY:
	case CatalogType::SCALAR_FUNCTION_ENTRY:
	case CatalogType::TABLE_FUNCTION_ENTRY:
		return "macro";
	case CatalogType::TYPE_ENTRY:
		return "type";
	case CatalogType::TRIGGER_ENTRY:
		return "trigger";
	case CatalogType::SCHEMA_ENTRY:
		return "schema";
	default:
		return "";
	}
}

bool IsSchemeCharacter(char c) {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '+' || c == '-' ||
	       c == '.';
}

//! The catalogs `sql` names as a qualifier: every identifier (unquoted, lower case) a '.' follows.
//! A table alias reads as one too, which can only widen the set to a name the body already uses.
std::set<std::string> QualifiersIn(const std::string &sql) {
	std::set<std::string> names;
	auto tokens = Parser::Tokenize(sql);
	auto text = [&](idx_t i) {
		auto end = i + 1 < tokens.size() ? tokens[i + 1].start : sql.size();
		auto token = sql.substr(tokens[i].start, end - tokens[i].start);
		while (!token.empty() && StringUtil::CharacterIsSpace(token.back())) {
			token.pop_back();
		}
		return token;
	};
	for (idx_t i = 0; i + 1 < tokens.size(); i++) {
		auto type = tokens[i].type;
		if (type != SimplifiedTokenType::SIMPLIFIED_TOKEN_IDENTIFIER &&
		    type != SimplifiedTokenType::SIMPLIFIED_TOKEN_KEYWORD) {
			continue;
		}
		auto next = text(i + 1);
		if (next.empty() || next[0] != '.') {
			continue;
		}
		auto name = text(i);
		if (name.size() >= 2 && name.front() == '"' && name.back() == '"') {
			name = StringUtil::Replace(name.substr(1, name.size() - 2), "\"\"", "\"");
		}
		names.insert(StringUtil::Lower(name));
	}
	return names;
}

//! The prefix DuckDB v2.0.0 names the temporary ENUM of a dynamic PIVOT with (one with no IN list):
//! "__pivot_enum_" + a random UUID, src/parser/peg/transformer/transform_pivot.cpp:151 and :280.
constexpr const char *kPivotEnumPrefix = "__pivot_enum_";

//! A temporary type named like the ENUM a dynamic PIVOT leaves on the connection (CONTRACT Addendum 4
//! item 19): exempt from the temporary-entry rule. A secure view's definition uses built-in types only
//! (item 18) and no built-in type carries the prefix, so no body can resolve such an entry, whoever made
//! it.
bool IsPivotEnum(CatalogEntry &entry) {
	return entry.type == CatalogType::TYPE_ENTRY &&
	       StringUtil::StartsWith(StringUtil::Lower(Text(entry.name)), kPivotEnumPrefix);
}

//! The statement under the EXPLAIN and PREPARE that only carry it.
SQLStatement *Unwrapped(SQLStatement *statement) {
	while (statement && (statement->type == StatementType::EXPLAIN_STATEMENT ||
	                     statement->type == StatementType::PREPARE_STATEMENT)) {
		statement = statement->type == StatementType::EXPLAIN_STATEMENT
		                ? statement->Cast<ExplainStatement>().stmt.get()
		                : statement->Cast<PrepareStatement>().statement.get();
	}
	return statement;
}

//! A secure view's definition, read structurally (CONTRACT Addendum 4 item 14): a view body binds an
//! unqualified table name in the view's own schema first, so a user with create there could shadow it.
//! Refuses a base table reference without a schema (a CTE name in scope excepted, in the scope that
//! defines it), and a table read through a SQL string (query, query_table), whose names the walk
//! cannot see. Item 18: it reads base tables, secure views and DuckDB's own views only, never a plain
//! view, and casts to built-in types only. Collects the catalogs the definition qualifies a table with.
//!
//! Item 17: an unqualified function, macro, table macro, type or sequence name (a nextval, currval or
//! setval string included) is refused when it resolves outside the system catalog, so a user's entry in
//! the view's schema cannot shadow what the body names. A two-part name x.n is resolved as DuckDB
//! resolves it (Binder::BindTableName: ResolveCatalog, BindSchemaOrCatalog): refused when x names
//! both a database and a schema (DuckDB's "Ambiguous reference to catalog or schema"), and when x is a
//! catalog, which leaves the schema to the search path. Every lookup is DuckDB's own, through a
//! CatalogEntryRetriever holding the search path the view binder holds (Binder::GetSearchPath(catalog,
//! schema, true), bind_basetableref.cpp at v2.0.0).
class Definition {
public:
	Definition(ClientContext &context, const std::string &view_catalog, const std::string &view_schema)
	    : context_(context), view_catalog_(view_catalog), retriever_(ViewRetriever(view_catalog, view_schema)) {
	}

	void Query(const QueryNode &node, std::set<std::string> scope);

	std::set<std::string> catalogs;

private:
	unique_ptr<CatalogEntryRetriever> ViewRetriever(const std::string &catalog, const std::string &schema);
	void Ref(const TableRef &ref, const std::set<std::string> &scope);
	void Expr(const ParsedExpression &expr, const std::set<std::string> &scope);
	void Table(const BaseTableRef &table, const std::set<std::string> &scope);
	QualifiedName Resolved(const QualifiedName &name, const std::string &what);
	optional_ptr<CatalogEntry> Lookup(CatalogType type, const QualifiedName &name);
	optional_ptr<CatalogEntry> Unqualified(CatalogType type, const QualifiedName &name, const std::string &what);
	void Function(const QualifiedName &name, CatalogType type, const std::string &what);
	void Macro(CatalogEntry &entry);
	void Sequence(const FunctionExpression &call);
	void Type(const TypeExpression &type);

	ClientContext &context_;
	std::string view_catalog_;
	unique_ptr<CatalogEntryRetriever> retriever_;
	//! How deep macros may expand inside a definition (item 20).
	static constexpr int kMaxMacroDepth = 8;
	int macro_depth_ = 0;
};

unique_ptr<CatalogEntryRetriever> Definition::ViewRetriever(const std::string &catalog, const std::string &schema) {
	auto retriever = make_uniq<CatalogEntryRetriever>(context_);
	vector<CatalogSearchEntry> path;
	path.emplace_back(Identifier(catalog), Identifier(schema));
	path.emplace_back(Identifier(catalog), Identifier::InvalidSchema(), true);
	retriever->SetSearchPath(std::move(path));
	return retriever;
}

//! `name` with its qualification resolved as the binder resolves it, refused when it is ambiguous or
//! names a catalog without a schema.
QualifiedName Definition::Resolved(const QualifiedName &name, const std::string &what) {
	QualifiedName bound;
	try {
		bound = Binder::BindTableName(*retriever_, name);
	} catch (BinderException &) {
		Unsupported("a secure view whose definition names the " + what + " " + name.ToString() +
		            " ambiguously (a catalog and a schema of that name)");
	}
	if (name.Path().size() > 1 && bound.Schema().empty()) {
		Unsupported("a secure view whose definition names the " + what + " " + name.ToString() +
		            " with a catalog but without its schema");
	}
	return bound;
}

optional_ptr<CatalogEntry> Definition::Lookup(CatalogType type, const QualifiedName &name) {
	return retriever_->GetEntry(EntryLookupInfo(type, name), OnEntryNotFound::RETURN_NULL);
}

//! DuckDB's own: the system catalog's entries, and the built-ins every DuckDB catalog generates on
//! demand (DefaultTypeGenerator: an unqualified BIGINT resolves to temp.main.bigint, internal), which
//! no session can create or replace.
//! `internal` is trusted only in those two catalogs: CreateInfo serializes it, so a database file written
//! elsewhere could carry it on an entry of its own (review M-f).
bool BuiltIn(CatalogEntry &entry) {
	auto catalog = Text(entry.ParentCatalog().GetName());
	if (catalog == Text(Identifier::SystemCatalog())) {
		return true;
	}
	return entry.internal && catalog == Text(Identifier::TempCatalog());
}

//! An unqualified name of `type` the body would resolve outside the system catalog.
optional_ptr<CatalogEntry> Definition::Unqualified(CatalogType type, const QualifiedName &name,
                                                   const std::string &what) {
	auto entry = Lookup(type, name);
	if (entry && !BuiltIn(*entry)) {
		Unsupported("a secure view whose definition names the " + what + " " + Text(name.Name()) +
		            " without its schema");
	}
	return entry;
}

//! A function call as the binder looks it up: ColumnQualifier::QualifyFunction for a scalar function
//! or macro (a qualified name that finds nothing is a dot call on a column, x.lower() = lower(x), and
//! binds by the bare name), Binder::Bind(TableFunctionRef&) for a table function, and the window
//! binder's direct lookup for a window or aggregate in OVER.
void Definition::Function(const QualifiedName &name, CatalogType type, const std::string &what) {
	optional_ptr<CatalogEntry> entry;
	if (name.Path().size() <= 1) {
		entry = Unqualified(type, Binder::BindTableName(*retriever_, name), what);
	} else {
		entry = Lookup(type, Resolved(name, what));
		if (!entry && type == CatalogType::SCALAR_FUNCTION_ENTRY) {
			entry = Unqualified(type, QualifiedName(name.Name()), what);
		}
	}
	if (entry) {
		Macro(*entry);
	}
}

//! Item 20: a macro's body expands inside the boundary and runs with the owner's rights, so a macro that
//! is not DuckDB's own is held to the definition's rules, transitively. DuckDB v2.0.0 binds the body
//! where it is called, with the caller's search path: a scalar macro's expression is copied in place and
//! bound by the calling ExpressionBinder (ExpressionBinder::UnfoldMacroExpression and BindMacro,
//! src/planner/binder/expression/bind_macro_expression.cpp:170-216), a table macro's query node by a
//! child of the calling binder (Binder::BindTableMacro, src/planner/binder/query_node/
//! bind_table_macro_node.cpp:20-49; Binder::Bind(TableFunctionRef&), src/planner/binder/tableref/
//! bind_table_function.cpp:430-437), which inherits its retriever (Binder::Binder,
//! src/planner/binder.cpp:59-66). So the body is walked with this definition's retriever, its
//! parameters' defaults included, and with no CTE in scope.
void Definition::Macro(CatalogEntry &entry) {
	if ((entry.type != CatalogType::MACRO_ENTRY && entry.type != CatalogType::TABLE_MACRO_ENTRY) || BuiltIn(entry)) {
		return;
	}
	if (++macro_depth_ > kMaxMacroDepth) {
		Unsupported("a secure view whose definition nests macros too deep");
	}
	for (auto &macro : entry.Cast<MacroCatalogEntry>().macros) {
		for (auto &parameter : macro->default_parameters) {
			if (parameter.second) {
				Expr(*parameter.second, {});
			}
		}
		if (macro->type == MacroType::SCALAR_MACRO) {
			auto &body = macro->Cast<ScalarMacroFunction>().expression;
			if (body) {
				Expr(*body, {});
			}
		} else if (macro->type == MacroType::TABLE_MACRO) {
			auto &body = macro->Cast<TableMacroFunction>().query_node;
			if (body) {
				Query(*body, {});
			}
		} else {
			Unsupported("a secure view whose definition calls a macro of a kind QoD does not know");
		}
	}
	macro_depth_--;
}

//! nextval, currval and setval resolve their sequence from their first argument at bind (NextValBind,
//! BindSequence: QualifiedName::TryParse, then BindTableName with the view binder's retriever).
void Definition::Sequence(const FunctionExpression &call) {
	auto function = StringUtil::Lower(Text(call.GetQualifiedName().Name()));
	if (function != "nextval" && function != "currval" && function != "setval") {
		return;
	}
	auto &arguments = call.GetArguments();
	if (arguments.empty()) {
		return;
	}
	auto &argument = arguments[0].GetExpression();
	if (argument.GetExpressionClass() != ExpressionClass::CONSTANT) {
		Unsupported("a secure view whose definition names a sequence by an expression");
	}
	auto &literal = argument.Cast<ConstantExpression>().GetLiteral();
	if (literal.kind != LiteralKind::STRING) {
		// NULL binds no sequence; anything else does not bind.
		return;
	}
	QualifiedName name;
	string error;
	if (!QualifiedName::TryParse(literal.text, name, error)) {
		return;
	}
	if (name.Path().size() <= 1) {
		Unqualified(CatalogType::SEQUENCE_ENTRY, Binder::BindTableName(*retriever_, name), "sequence");
	} else {
		Resolved(name, "sequence");
	}
}

//! A type as ExpressionBinder::BindExpression(TypeExpression&) finds it: the resolved name, then the
//! name in the resolved catalog with any schema, then the name anywhere on the path, then system.main.
//! Item 18: the type must be built in, whether the definition qualifies it or not. A user type (an ENUM)
//! is replaced by whoever holds DDL on it, and the body then casts with the owner's rights to what that
//! user chose.
void Definition::Type(const TypeExpression &type) {
	auto &name = type.GetQualifiedName();
	auto valid = [](optional_ptr<CatalogEntry> entry) {
		return entry && entry->type == CatalogType::TYPE_ENTRY &&
		       entry->Cast<TypeCatalogEntry>().user_type.id() != LogicalTypeId::INVALID;
	};
	auto refuse = [&]() {
		Unsupported("a secure view whose definition uses the user type " + name.ToString());
	};
	if (name.Path().size() > 1) {
		auto entry = Lookup(CatalogType::TYPE_ENTRY, Resolved(name, "type"));
		if (valid(entry) && !BuiltIn(*entry)) {
			refuse();
		}
		return;
	}
	auto bound = Binder::BindTableName(*retriever_, name);
	auto entry = Lookup(CatalogType::TYPE_ENTRY, bound);
	if (!valid(entry)) {
		entry =
		    Lookup(CatalogType::TYPE_ENTRY, QualifiedName(bound.Catalog(), Identifier::InvalidSchema(), name.Name()));
	}
	if (!valid(entry)) {
		entry = Lookup(CatalogType::TYPE_ENTRY,
		               QualifiedName(Identifier::InvalidCatalog(), Identifier::InvalidSchema(), name.Name()));
	}
	if (valid(entry) && !BuiltIn(*entry)) {
		refuse();
	}
}

void Definition::Query(const QueryNode &node, std::set<std::string> scope) {
	// A WITH clause's CTEs bind in order (item 20, as DuckDB v2.0.0 binds them): each body sees the
	// enclosing scope and the CTEs before it, and its own name only when it is recursive (a
	// RECURSIVE_CTE_NODE, which adds it below). A reference to its own name in a plain CTE, or to a later
	// CTE, binds to a table of that name, so it gets every table rule. The query the clause heads, and its
	// subqueries, see every name.
	for (auto &entry : node.cte_map.map) {
		if (entry.second) {
			if (entry.second->query_node) {
				Query(*entry.second->query_node, scope);
			}
			for (auto &expr : entry.second->key_targets) {
				Expr(*expr, scope);
			}
			for (auto &expr : entry.second->payload_aggregates) {
				Expr(*expr, scope);
			}
		}
		scope.insert(StringUtil::Lower(Text(entry.first)));
	}
	switch (node.type) {
	case QueryNodeType::SELECT_NODE: {
		auto &select = node.Cast<SelectNode>();
		if (select.from_table) {
			Ref(*select.from_table, scope);
		}
		for (auto &expr : select.select_list) {
			Expr(*expr, scope);
		}
		for (auto &expr : select.groups.group_expressions) {
			Expr(*expr, scope);
		}
		for (auto *clause : {&select.where_clause, &select.having, &select.qualify}) {
			if (*clause) {
				Expr(**clause, scope);
			}
		}
		break;
	}
	case QueryNodeType::SET_OPERATION_NODE:
		for (auto &child : node.Cast<SetOperationNode>().children) {
			Query(*child, scope);
		}
		break;
	case QueryNodeType::RECURSIVE_CTE_NODE: {
		// The anchor binds before the CTE exists: its own name there is a table (UNION, UNION ALL and
		// USING KEY alike, re-review 5 R7). Only the recursive term reads the CTE.
		auto &recursive = node.Cast<RecursiveCTENode>();
		Query(*recursive.left, scope);
		scope.insert(StringUtil::Lower(Text(recursive.ctename)));
		Query(*recursive.right, scope);
		for (auto &expr : recursive.key_targets) {
			Expr(*expr, scope);
		}
		break;
	}
	case QueryNodeType::CTE_NODE: {
		auto &cte = node.Cast<CTENode>();
		if (cte.query) {
			Query(*cte.query, scope);
		}
		scope.insert(StringUtil::Lower(Text(cte.ctename)));
		if (cte.child) {
			Query(*cte.child, scope);
		}
		break;
	}
	default:
		Unsupported("a secure view whose definition is a " + EnumUtil::ToString(node.type));
	}
	// LIMIT, OFFSET, ORDER BY and DISTINCT ON expressions (read only; the iterator wants a mutable node).
	ParsedExpressionIterator::EnumerateQueryNodeModifiers(const_cast<QueryNode &>(node),
	                                                      [&](unique_ptr<ParsedExpression> &child) {
		                                                      if (child) {
			                                                      Expr(*child, scope);
		                                                      }
	                                                      });
}

void Definition::Ref(const TableRef &ref, const std::set<std::string> &scope) {
	switch (ref.type) {
	case TableReferenceType::BASE_TABLE:
		Table(ref.Cast<BaseTableRef>(), scope);
		break;
	case TableReferenceType::JOIN: {
		auto &join = ref.Cast<JoinRef>();
		Ref(*join.left, scope);
		Ref(*join.right, scope);
		if (join.condition) {
			Expr(*join.condition, scope);
		}
		for (auto &expr : join.duplicate_eliminated_columns) {
			Expr(*expr, scope);
		}
		if (join.ranking_expression) {
			Expr(*join.ranking_expression, scope);
		}
		break;
	}
	case TableReferenceType::SUBQUERY:
		Query(*ref.Cast<SubqueryRef>().subquery->node, scope);
		break;
	case TableReferenceType::TABLE_FUNCTION: {
		auto &function = *ref.Cast<TableFunctionRef>().function;
		if (function.GetExpressionClass() != ExpressionClass::FUNCTION) {
			Unsupported("a secure view whose definition calls a table function DuckDB does not name");
		}
		auto &call = function.Cast<FunctionExpression>();
		auto name = StringUtil::Lower(Text(call.GetQualifiedName().Name()));
		if (name == "query" || name == "query_table") {
			Unsupported("a secure view whose definition reads tables named in a string (" + name + ")");
		}
		Function(call.GetQualifiedName(), CatalogType::TABLE_FUNCTION_ENTRY, "table function");
		ParsedExpressionIterator::EnumerateChildren(function,
		                                            [&](const ParsedExpression &child) { Expr(child, scope); });
		break;
	}
	case TableReferenceType::EXPRESSION_LIST:
		for (auto &row : ref.Cast<ExpressionListRef>().values) {
			for (auto &expr : row) {
				Expr(*expr, scope);
			}
		}
		break;
	case TableReferenceType::PIVOT: {
		auto &pivot = ref.Cast<PivotRef>();
		Ref(*pivot.source, scope);
		for (auto &expr : pivot.aggregates) {
			Expr(*expr, scope);
		}
		for (auto &column : pivot.pivots) {
			for (auto &expr : column.pivot_expressions) {
				Expr(*expr, scope);
			}
			// The IN list: a typed literal ('a'::lvl) names a type.
			for (auto &entry : column.entries) {
				if (entry.expr) {
					Expr(*entry.expr, scope);
				}
			}
		}
		break;
	}
	case TableReferenceType::EMPTY_FROM:
	case TableReferenceType::COLUMN_DATA:
	case TableReferenceType::DELIM_GET:
		break;
	default:
		// SHOW_REF (DESCRIBE, SUMMARIZE) and anything a DuckDB upgrade adds.
		Unsupported("a secure view whose definition holds a " + EnumUtil::ToString(ref.type) + " reference");
	}
}

void Definition::Expr(const ParsedExpression &expr, const std::set<std::string> &scope) {
	if (expr.GetExpressionClass() == ExpressionClass::SUBQUERY) {
		auto &subquery = expr.Cast<SubqueryExpression>();
		if (subquery.Subquery() && subquery.Subquery()->node) {
			Query(*subquery.Subquery()->node, scope);
		}
		if (subquery.GetChild()) {
			Expr(*subquery.GetChild(), scope);
		}
		return;
	}
	switch (expr.GetExpressionClass()) {
	case ExpressionClass::FUNCTION: {
		auto &call = expr.Cast<FunctionExpression>();
		Function(call.GetQualifiedName(), CatalogType::SCALAR_FUNCTION_ENTRY, "function");
		Sequence(call);
		break;
	}
	case ExpressionClass::WINDOW: {
		// The window binder looks the name up directly; a macro found there expands (BindWindowExpression).
		auto &name = expr.Cast<WindowExpression>().GetQualifiedName();
		optional_ptr<CatalogEntry> entry;
		if (name.Path().size() <= 1) {
			entry = Unqualified(CatalogType::SCALAR_FUNCTION_ENTRY, name, "function");
		} else {
			entry = Lookup(CatalogType::SCALAR_FUNCTION_ENTRY, Resolved(name, "function"));
		}
		if (entry) {
			Macro(*entry);
		}
		break;
	}
	case ExpressionClass::CAST:
		Expr(expr.Cast<CastExpression>().TargetType(), scope);
		break;
	case ExpressionClass::TYPE:
		Type(expr.Cast<TypeExpression>());
		for (auto &child : expr.Cast<TypeExpression>().GetChildren()) {
			Expr(*child, scope);
		}
		break;
	default:
		break;
	}
	ParsedExpressionIterator::EnumerateChildren(expr, [&](const ParsedExpression &child) { Expr(child, scope); });
}

void Definition::Table(const BaseTableRef &table, const std::set<std::string> &scope) {
	auto &name = table.GetQualifiedName();
	if (name.Schema().empty()) {
		if (name.Catalog().empty() && scope.count(StringUtil::Lower(Text(name.Name()))) != 0) {
			return;
		}
		Unsupported("a secure view whose definition names the table " + Text(name.Name()) + " without its schema");
	}
	auto bound = Resolved(name, "table");
	if (!bound.Catalog().empty()) {
		catalogs.insert(StringUtil::Lower(Text(bound.Catalog())));
	}
	// Looked up as the body binds it (tables and views share a catalog set). Item 18: a definition reads
	// base tables and secure views (creating or replacing those is superuser-only; a nested one's own
	// boundary checks its definition), never a plain view, which whoever holds DDL on it can redefine to
	// read anything with the owner's rights. DuckDB's own views (information_schema, pg_catalog: the
	// system catalog's) are built in, admitted without a walk (review M-h).
	auto entry = Lookup(CatalogType::TABLE_ENTRY, bound);
	if (!entry || entry->type != CatalogType::VIEW_ENTRY) {
		return;
	}
	if (entry->Cast<ViewCatalogEntry>().security_type == ViewSecurityType::SECURE_VIEW || BuiltIn(*entry)) {
		return;
	}
	Unsupported("a secure view whose definition reads the plain view " + bound.ToString());
}

//! CONTRACT Addendum 4 item 32 (B): the call names a statement reaches as written, resolved as DuckDB
//! resolves them on the client's own context and transaction (its search path, the catalog snapshot its
//! transaction holds). DuckDB folds a foldable argument to its value while binding
//! (FunctionBinder::BindScalarFunction, HasNullConstantArgument), in the statement, in a view's body and
//! in a macro's, so the plan the describer reads may hold no trace of a call: the names are judged here.
//! A non-superuser may call a name that resolves to DuckDB's own entry (BuiltIn) and that the
//! classification lists; outside a secure view's boundary, not a superuser-class one. Every view the
//! statement reads by name, plain or secure, is walked transitively with the search path DuckDB binds
//! its body with (Binder::GetSearchPath(view catalog, view schema, true), bind_basetableref.cpp:333-335,
//! the client's set paths behind it); a macro that is not DuckDB's own is refused by name, so no macro
//! body is walked (a built-in one calls built-ins only). The statement's own names come from the parse
//! screen's walk (ReachedNames); a view's from the definition walk below, which follows the shapes a
//! view's query takes (a SELECT, a set operation, CTEs).
class CallNames {
public:
	explicit CallNames(ClientContext &context) : context_(context) {
	}

	//! Every statement of `sql`.
	void Sql(const std::string &sql) {
		Parser parser(context_);
		parser.ParseQuery(sql);
		for (auto &statement : parser.statements) {
			Statement(*statement);
		}
	}

	//! The statement's own names. The parse screen refused its unclassified and superuser-class names
	//! before binding (items 9 and 30); what it cannot see is the entry a classified name resolves to on
	//! the client's context, judged here, and the definitions of the views it reads, walked in full.
	void Statement(SQLStatement &statement) {
		CatalogEntryRetriever session(context_);
		for (auto &reached : ReachedNames(context_, statement)) {
			switch (reached.kind) {
			case ReachedName::Kind::RELATION:
				Relation(reached.name, session, false);
				break;
			case ReachedName::Kind::TABLE_CALL:
				Resolution(reached.name, CatalogType::TABLE_FUNCTION_ENTRY, session);
				break;
			default:
				Resolution(reached.name, CatalogType::SCALAR_FUNCTION_ENTRY, session);
				break;
			}
		}
	}

private:
	//! Views nested deeper than this are refused (fail closed).
	static constexpr idx_t kMaxViewDepth = 16;

	optional_ptr<CatalogEntry> Lookup(CatalogEntryRetriever &retriever, CatalogType type, const QualifiedName &name) {
		return retriever.GetEntry(EntryLookupInfo(type, Binder::BindTableName(retriever, name)),
		                          OnEntryNotFound::RETURN_NULL);
	}

	//! The entry a call binds to, as the binder looks it up: the name as written, then (a qualified name
	//! that finds nothing is a dot call on a column, x.lower() = lower(x)) the bare name.
	optional_ptr<CatalogEntry> Resolved(const QualifiedName &name, CatalogType type, CatalogEntryRetriever &retriever) {
		optional_ptr<CatalogEntry> entry;
		try {
			entry = Lookup(retriever, type, name);
		} catch (std::exception &) {
			entry = nullptr;
		}
		if (!entry && name.Path().size() > 1) {
			entry = Lookup(retriever, type, QualifiedName(name.Name()));
		}
		return entry;
	}

	[[noreturn]] static void NotOwn(const std::string &lower, CatalogEntry &entry) {
		throw PermissionException("QoD: function " + lower + " is not classified: it resolves to " +
		                          Text(entry.ParentCatalog().GetName()) + "." + Text(entry.ParentSchema().name) +
		                          "." + Text(entry.name) + ", which is not DuckDB's own");
	}

	//! A classified name of the statement's own text that resolves to an entry that is not DuckDB's own
	//! (a macro shadowing a built-in) is refused.
	void Resolution(const QualifiedName &name, CatalogType type, CatalogEntryRetriever &retriever) {
		auto lower = StringUtil::Lower(Text(name.Name()));
		if (!IsClassifiedFunction(lower)) {
			return;
		}
		auto entry = Resolved(name, type, retriever);
		if (entry && !BuiltIn(*entry)) {
			NotOwn(lower, *entry);
		}
	}

	//! A call inside a view's or a macro's body, which no screen saw: the entry it resolves to, its
	//! classification, and outside a secure view's boundary its class.
	void Call(const QualifiedName &name, CatalogType type, CatalogEntryRetriever &retriever, bool secure) {
		auto lower = StringUtil::Lower(Text(name.Name()));
		auto entry = Resolved(name, type, retriever);
		if (entry && !BuiltIn(*entry) && secure &&
		    (entry->type == CatalogType::MACRO_ENTRY || entry->type == CatalogType::TABLE_MACRO_ENTRY)) {
			// Inside a secure view's boundary a superuser's macro runs with the owner's rights (item 20):
			// its body's names are judged as the boundary's.
			Macro(*entry, retriever);
			return;
		}
		if (entry && !BuiltIn(*entry)) {
			NotOwn(lower, *entry);
		}
		if (!IsClassifiedFunction(lower) && lower != "unlist") {
			throw PermissionException("QoD: function " + lower + " is not classified");
		}
		if (!secure && IsSuperuserFunction(lower)) {
			throw PermissionException("QoD: function " + lower + " is superuser-only");
		}
	}

	void Macro(CatalogEntry &entry, CatalogEntryRetriever &retriever) {
		if (path_.count(&entry) != 0) {
			Unsupported("a macro that calls itself");
		}
		if (path_.size() >= kMaxViewDepth) {
			Unsupported("views and macros nested deeper than " + std::to_string(kMaxViewDepth));
		}
		path_.insert(&entry);
		for (auto &macro : entry.Cast<MacroCatalogEntry>().macros) {
			for (auto &parameter : macro->default_parameters) {
				if (parameter.second) {
					Expr(*parameter.second, retriever, true);
				}
			}
			if (macro->type == MacroType::SCALAR_MACRO) {
				auto &body = macro->Cast<ScalarMacroFunction>().expression;
				if (body) {
					Expr(*body, retriever, true);
				}
			} else if (macro->type == MacroType::TABLE_MACRO) {
				auto &body = macro->Cast<TableMacroFunction>().query_node;
				if (body) {
					Query(*body, retriever, true);
				}
			} else {
				Unsupported("a macro of a kind QoD does not know");
			}
		}
		path_.erase(&entry);
	}

	void Relation(const QualifiedName &name, CatalogEntryRetriever &retriever, bool secure) {
		auto entry = Lookup(retriever, CatalogType::TABLE_ENTRY, name);
		if (!entry || entry->type != CatalogType::VIEW_ENTRY || BuiltIn(*entry)) {
			return;
		}
		auto &view = entry->Cast<ViewCatalogEntry>();
		if (path_.count(entry.get()) != 0) {
			Unsupported("a view that reads itself");
		}
		if (path_.size() >= kMaxViewDepth) {
			Unsupported("views and macros nested deeper than " + std::to_string(kMaxViewDepth));
		}
		path_.insert(entry.get());
		CatalogEntryRetriever body(context_);
		vector<CatalogSearchEntry> search;
		search.emplace_back(view.ParentCatalog().GetName(), view.ParentSchema().name);
		search.emplace_back(view.ParentCatalog().GetName(), Identifier::InvalidSchema(), true);
		body.SetSearchPath(std::move(search));
		auto inside = secure || view.security_type == ViewSecurityType::SECURE_VIEW;
		Query(*view.GetQuery().node, body, inside);
		path_.erase(entry.get());
	}

	// ---- a view's definition

	void Query(const QueryNode &node, CatalogEntryRetriever &retriever, bool secure) {
		for (auto &entry : node.cte_map.map) {
			if (entry.second) {
				if (entry.second->query_node) {
					Query(*entry.second->query_node, retriever, secure);
				}
				for (auto &expr : entry.second->key_targets) {
					Expr(*expr, retriever, secure);
				}
				for (auto &expr : entry.second->payload_aggregates) {
					Expr(*expr, retriever, secure);
				}
			}
		}
		switch (node.type) {
		case QueryNodeType::SELECT_NODE: {
			auto &select = node.Cast<SelectNode>();
			if (select.from_table) {
				Ref(*select.from_table, retriever, secure);
			}
			for (auto &expr : select.select_list) {
				Expr(*expr, retriever, secure);
			}
			for (auto &expr : select.groups.group_expressions) {
				Expr(*expr, retriever, secure);
			}
			for (auto *clause : {&select.where_clause, &select.having, &select.qualify}) {
				if (*clause) {
					Expr(**clause, retriever, secure);
				}
			}
			break;
		}
		case QueryNodeType::SET_OPERATION_NODE:
			for (auto &child : node.Cast<SetOperationNode>().children) {
				Query(*child, retriever, secure);
			}
			break;
		case QueryNodeType::RECURSIVE_CTE_NODE: {
			auto &recursive = node.Cast<RecursiveCTENode>();
			Query(*recursive.left, retriever, secure);
			Query(*recursive.right, retriever, secure);
			for (auto &expr : recursive.key_targets) {
				Expr(*expr, retriever, secure);
			}
			break;
		}
		case QueryNodeType::CTE_NODE: {
			auto &cte = node.Cast<CTENode>();
			if (cte.query) {
				Query(*cte.query, retriever, secure);
			}
			if (cte.child) {
				Query(*cte.child, retriever, secure);
			}
			break;
		}
		default:
			Unsupported("a view whose definition is a " + EnumUtil::ToString(node.type));
		}
		ParsedExpressionIterator::EnumerateQueryNodeModifiers(const_cast<QueryNode &>(node),
		                                                      [&](unique_ptr<ParsedExpression> &child) {
			                                                      if (child) {
				                                                      Expr(*child, retriever, secure);
			                                                      }
		                                                      });
	}

	void Ref(const TableRef &ref, CatalogEntryRetriever &retriever, bool secure) {
		switch (ref.type) {
		case TableReferenceType::BASE_TABLE: {
			auto &base = ref.Cast<BaseTableRef>();
			if (base.at_clause && base.at_clause->ExpressionMutable()) {
				Expr(*base.at_clause->ExpressionMutable(), retriever, secure);
			}
			Relation(base.GetQualifiedName(), retriever, secure);
			break;
		}
		case TableReferenceType::JOIN: {
			auto &join = ref.Cast<JoinRef>();
			Ref(*join.left, retriever, secure);
			Ref(*join.right, retriever, secure);
			if (join.condition) {
				Expr(*join.condition, retriever, secure);
			}
			for (auto &expr : join.duplicate_eliminated_columns) {
				Expr(*expr, retriever, secure);
			}
			if (join.ranking_expression) {
				Expr(*join.ranking_expression, retriever, secure);
			}
			break;
		}
		case TableReferenceType::SUBQUERY:
			Query(*ref.Cast<SubqueryRef>().subquery->node, retriever, secure);
			break;
		case TableReferenceType::TABLE_FUNCTION: {
			auto &function = ref.Cast<TableFunctionRef>();
			if (function.function && function.function->GetExpressionClass() == ExpressionClass::FUNCTION) {
				auto &call = function.function->Cast<FunctionExpression>();
				Call(call.GetQualifiedName(), CatalogType::TABLE_FUNCTION_ENTRY, retriever, secure);
				ParsedExpressionIterator::EnumerateChildren(
				    call, [&](const ParsedExpression &child) { Expr(child, retriever, secure); });
			} else if (function.function) {
				Unsupported("a view whose definition calls a table function DuckDB does not name");
			}
			if (function.subquery) {
				Query(*function.subquery->node, retriever, secure);
			}
			break;
		}
		case TableReferenceType::EXPRESSION_LIST:
			for (auto &row : ref.Cast<ExpressionListRef>().values) {
				for (auto &expr : row) {
					Expr(*expr, retriever, secure);
				}
			}
			break;
		case TableReferenceType::PIVOT: {
			auto &pivot = ref.Cast<PivotRef>();
			if (pivot.source) {
				Ref(*pivot.source, retriever, secure);
			}
			for (auto &expr : pivot.aggregates) {
				Expr(*expr, retriever, secure);
			}
			for (auto &column : pivot.pivots) {
				for (auto &expr : column.pivot_expressions) {
					Expr(*expr, retriever, secure);
				}
				for (auto &entry : column.entries) {
					if (entry.expr) {
						Expr(*entry.expr, retriever, secure);
					}
				}
				if (column.subquery) {
					Query(*column.subquery, retriever, secure);
				}
			}
			break;
		}
		case TableReferenceType::EMPTY_FROM:
		case TableReferenceType::COLUMN_DATA:
		case TableReferenceType::DELIM_GET:
			break;
		default:
			Unsupported("a view whose definition holds a " + EnumUtil::ToString(ref.type) + " reference");
		}
	}

	void Expr(const ParsedExpression &expr, CatalogEntryRetriever &retriever, bool secure) {
		switch (expr.GetExpressionClass()) {
		case ExpressionClass::SUBQUERY: {
			auto &subquery = expr.Cast<SubqueryExpression>();
			if (subquery.Subquery() && subquery.Subquery()->node) {
				Query(*subquery.Subquery()->node, retriever, secure);
			}
			break;
		}
		case ExpressionClass::FUNCTION:
			Call(expr.Cast<FunctionExpression>().GetQualifiedName(), CatalogType::SCALAR_FUNCTION_ENTRY, retriever,
			     secure);
			break;
		case ExpressionClass::WINDOW:
			Call(expr.Cast<WindowExpression>().GetQualifiedName(), CatalogType::SCALAR_FUNCTION_ENTRY, retriever,
			     secure);
			break;
		default:
			break;
		}
		ParsedExpressionIterator::EnumerateChildren(expr,
		                                            [&](const ParsedExpression &child) { Expr(child, retriever, secure); });
	}

	ClientContext &context_;
	//! The views and macros on the walk's current path, for cycles.
	std::set<const CatalogEntry *> path_;
};

//! The client statement's own statements: every statement of its text, and an EXECUTE's prepared one
//! (the statement it re-binds now, under the catalog as it is now).
void JudgeCallNames(ClientContext &context, LogicalOperator &plan, const std::string &statement_text) {
	CallNames names(context);
	names.Sql(statement_text);
	std::function<void(LogicalOperator &)> executes = [&](LogicalOperator &op) {
		if (op.type == LogicalOperatorType::LOGICAL_EXECUTE) {
			auto &prepared = op.Cast<LogicalExecute>().prepared;
			if (!prepared || !prepared->unbound_statement) {
				Unsupported("an EXECUTE whose prepared statement cannot be read");
			}
			names.Statement(*prepared->unbound_statement);
		}
		for (auto &child : op.children) {
			executes(*child);
		}
	};
	executes(plan);
}

class Walker {
public:
	Walker(ClientContext &context, const Principal &principal, const std::string *statement_text,
	       LogicalOperator &plan)
	    : context_(context), principal_(principal), statement_text_(statement_text), core_(&plan) {
		// The statement itself under the wrappers that only carry it (decision 19 looks for its root).
		while ((core_->type == LogicalOperatorType::LOGICAL_PREPARE ||
		        core_->type == LogicalOperatorType::LOGICAL_EXPLAIN ||
		        core_->type == LogicalOperatorType::LOGICAL_EXECUTE) &&
		       core_->children.size() == 1) {
			core_ = core_->children[0].get();
		}
	}

	void Operator(LogicalOperator &op, const std::string &via);

	//! The session checks a secure view needs, after the walk (so a temporary object inside a boundary
	//! is reported as such first).
	void CheckSession();

	std::string Canonical(const std::string &statement) const {
		return CanonicalJson(JsonObject({{"control", ToArray(control_)},
		                                 {"functions", ToArray(functions_)},
		                                 {"objects", ToArray(objects_)},
		                                 {"settings", ToArray(settings_)},
		                                 {"statement", JsonString(statement)},
		                                 {"v", JsonInt(1)}}));
	}

private:
	bool Trusted() const {
		return principal_.superuser || principal_.system;
	}

	// ---- objects
	void AddObject(const std::string &kind, const std::string &access, const std::string &catalog,
	               const std::string &catalog_type, bool temporary, const std::string &schema,
	               const std::string &name, const std::set<std::string> &columns, const std::string &via);
	void InSchema(const std::string &kind, const std::string &access, SchemaCatalogEntry &schema,
	              const std::string &name, const std::set<std::string> &columns, const std::string &via);
	void AddEntry(const std::string &kind, const std::string &access, InCatalogEntry &entry,
	              const std::set<std::string> &columns, const std::string &via);
	void AddTable(TableCatalogEntry &table, const std::string &access, const std::string &via);
	void Returning(TableCatalogEntry &table, const std::string &via);
	void Named(const std::string &kind, const std::string &access, const QualifiedName &name, const std::string &via);
	void SchemaPath(const std::string &access, const QualifiedName &name, const std::string &via);
	void DefaultQualification(Identifier &catalog, Identifier &schema);
	Identifier DefaultDatabase();
	void InView(const std::string &catalog, const std::string &via);
	std::string ChangedSetting();
	bool SessionDefaultPaths(const vector<CatalogSearchEntry> &paths);
	bool ExpectedPaths(const vector<CatalogSearchEntry> &paths, const std::string &expected);
	std::string ViewKind(const QualifiedName &name);

	// ---- per operator
	void Get(LogicalGet &get, const std::string &via);
	std::string SecureView(LogicalSecureView &view, const std::string &via);
	void Create(LogicalCreate &create, const std::string &via);
	void CreateTable(LogicalCreateTable &create, const std::string &via);
	void Alter(AlterInfo &info, const std::string &via);
	void Drop(DropInfo &info, const std::string &via);
	void ExtensionOperator(LogicalExtensionOperator &op, const std::string &via);
	void UnnamedStep(LogicalExtensionOperator &op, const std::string &via);
	void ExtraExpressions(LogicalOperator &op, const std::string &via);
	void Constraints(vector<unique_ptr<BoundConstraint>> &constraints, const std::string &via);

	// ---- expressions, functions, settings, control
	void Expr(duckdb::Expression &expr, const std::string &via);
	void AddFunction(const std::string &type, const QualifiedName &qualified, const std::vector<std::string> &args,
	                 const std::string &via);
	std::vector<std::string> TableArgs(LogicalGet &get, const std::string &name, const std::string &via);
	std::vector<std::string> LocationStrings(const Value &value, const std::string &via);
	std::string Location(const std::string &given, const std::string &via);
	SQLStatement *Statement();
	std::vector<std::string> CopyFromLocations(const std::string &via);
	void AddSetting(const Identifier &raw, SetScope scope, const Value *value);
	void AddControl(const std::string &op, const std::string &target, const std::string &type) {
		Add(control_,
		    JsonObject({{"op", JsonString(op)}, {"target", StringOrNull(target)}, {"type", StringOrNull(type)}}));
	}

	ClientContext &context_;
	const Principal &principal_;
	const std::string *statement_text_;
	LogicalOperator *core_;
	bool boundary_ = false;
	//! Per secure view (its `via`), the catalogs its body may use: the view's own and those its
	//! definition names (CONTRACT Addendum 4 item 11 b).
	std::map<std::string, std::set<std::string>> view_catalogs_;
	bool parsed_ = false;
	vector<unique_ptr<SQLStatement>> statements_;
	Elements objects_;
	Elements functions_;
	Elements settings_;
	Elements control_;
};

// ---- objects ---------------------------------------------------------------------------------

void Walker::AddObject(const std::string &kind, const std::string &access, const std::string &catalog,
                       const std::string &catalog_type, bool temporary, const std::string &schema,
                       const std::string &name, const std::set<std::string> &columns, const std::string &via) {
	if (!via.empty() && temporary) {
		Unsupported("a temporary object inside a secure view");
	}
	InView(catalog, via);
	Add(objects_, JsonObject({{"access", JsonString(access)},
	                          {"catalog", JsonString(catalog)},
	                          {"catalogType", JsonString(catalog_type)},
	                          {"columns", Strings(std::vector<std::string>(columns.begin(), columns.end()))},
	                          {"kind", JsonString(kind)},
	                          {"name", JsonString(name)},
	                          {"schema", JsonString(schema)},
	                          {"via", StringOrNull(via)}}));
}

void Walker::InSchema(const std::string &kind, const std::string &access, SchemaCatalogEntry &schema,
                      const std::string &name, const std::set<std::string> &columns, const std::string &via) {
	if (schema.GetParentSchema()) {
		Unsupported("a nested schema");
	}
	auto &catalog = schema.ParentCatalog();
	AddObject(kind, access, Text(catalog.GetName()), catalog.GetCatalogType(), catalog.IsTemporaryCatalog(),
	          Text(schema.name), name, columns, via);
}

void Walker::AddEntry(const std::string &kind, const std::string &access, InCatalogEntry &entry,
                      const std::set<std::string> &columns, const std::string &via) {
	InSchema(kind, access, entry.ParentSchema(), Text(entry.name), columns, via);
}

void Walker::AddTable(TableCatalogEntry &table, const std::string &access, const std::string &via) {
	AddEntry("table", access, table, {}, via);
}

//! RETURNING hands back rows of the target: a read of every column.
void Walker::Returning(TableCatalogEntry &table, const std::string &via) {
	auto names = table.GetColumns().GetColumnNames();
	AddEntry("table", "read", table, std::set<std::string>(names.begin(), names.end()), via);
}

//! An entry DuckDB did not find (DROP / ALTER ... IF EXISTS) keeps the session's defaults, the
//! catalog and schema it would have been created in.
void Walker::DefaultQualification(Identifier &catalog, Identifier &schema) {
	auto &fallback = ClientData::Get(context_).catalog_search_path->GetDefault();
	if (catalog.empty() || IsInvalidCatalog(catalog)) {
		catalog = fallback.GetCatalog().empty() || IsInvalidCatalog(fallback.GetCatalog())
		              ? DefaultDatabase()
		              : fallback.GetCatalog();
	}
	if (schema.empty() || IsInvalidSchema(schema)) {
		schema = fallback.GetSchema().empty() || IsInvalidSchema(fallback.GetSchema()) ? Identifier::DefaultSchema()
		                                                                                : fallback.GetSchema();
	}
}

//! The session's default database (DatabaseManager::TryGetDefaultDatabase, which an invalid catalog
//! name resolves to): database_manager.hpp needs headers the release include tree does not carry.
Identifier Walker::DefaultDatabase() {
	auto catalog = Catalog::GetCatalogEntry(context_, Identifier());
	return catalog ? catalog->GetName() : Identifier();
}

//! Inside the secure view `via`, `catalog` must be the view's own or one its definition names: a body
//! binds unqualified names with the view's catalog first and then the session's search path, so an
//! entry of another catalog the definition does not name came from the session (CONTRACT Addendum 4
//! item 11 b). The system catalog holds DuckDB's own functions and no session can write to it.
void Walker::InView(const std::string &catalog, const std::string &via) {
	if (via.empty() || catalog == "system") {
		return;
	}
	auto allowed = view_catalogs_.find(via);
	if (allowed == view_catalogs_.end() || allowed->second.count(StringUtil::Lower(catalog)) == 0) {
		Unsupported("an entry of catalog " + catalog + " inside the secure view " + via +
		            ", whose definition does not name that catalog");
	}
}

void Walker::Named(const std::string &kind, const std::string &access, const QualifiedName &name,
                   const std::string &via) {
	if (name.Path().size() > 3) {
		Unsupported("a nested schema");
	}
	auto catalog = name.Catalog();
	auto schema = name.Schema();
	DefaultQualification(catalog, schema);
	auto found = Catalog::GetCatalogEntry(context_, catalog);
	AddObject(kind, access, Text(catalog), found ? found->GetCatalogType() : "",
	          found && found->IsTemporaryCatalog(), Text(schema), Text(name.Name()), {}, via);
}

//! CREATE / DROP / ALTER SCHEMA: `name` is the schema's path, [catalog, schema] once DuckDB resolved it
//! (Binder::ResolveCatalog); a longer one is a nested schema.
void Walker::SchemaPath(const std::string &access, const QualifiedName &name, const std::string &via) {
	auto &path = name.Path();
	if (path.size() > 2) {
		Unsupported("a nested schema");
	}
	Identifier catalog = path.size() == 2 ? path[0] : Identifier();
	Identifier schema = path.empty() ? Identifier() : path.back();
	Identifier unused = schema;
	DefaultQualification(catalog, unused);
	auto found = Catalog::GetCatalogEntry(context_, catalog);
	AddObject("schema", access, Text(catalog), found ? found->GetCatalogType() : "",
	          found && found->IsTemporaryCatalog(), Text(schema), "", {}, via);
}

//! "secure_view" for a view whose catalog entry is secure, else "view".
std::string Walker::ViewKind(const QualifiedName &name) {
	auto entry =
	    Catalog::GetEntry(context_, EntryLookupInfo(CatalogType::VIEW_ENTRY, name), OnEntryNotFound::RETURN_NULL);
	if (entry && entry->type == CatalogType::VIEW_ENTRY &&
	    entry->Cast<ViewCatalogEntry>().security_type == ViewSecurityType::SECURE_VIEW) {
		return "secure_view";
	}
	return "view";
}

// ---- operators -------------------------------------------------------------------------------

void Walker::Get(LogicalGet &get, const std::string &via) {
	auto table = get.GetTable();
	if (table) {
		std::set<std::string> columns;
		for (auto &index : get.GetColumnIds()) {
			if (!index.IsVirtualColumn()) {
				columns.insert(Text(get.GetColumnName(index)));
			}
		}
		AddEntry("table", "read", *table, columns, via);
		return;
	}
	auto &qualified = get.function.GetQualifiedName();
	auto name = Text(qualified.Name());
	if (name == "qod_session") {
		Unsupported("qod_session outside the prelude");
	}
	AddFunction("table", qualified, TableArgs(get, name, via), via);
}

//! The boundary is a read of the secure view; everything under it runs with the view owner's
//! rights, so it carries the view's qualified name as `via`.
std::string Walker::SecureView(LogicalSecureView &view, const std::string &via) {
	if (!view.has_source) {
		Unsupported("a secure view without its qualified name");
	}
	auto &path = view.source_name.Path();
	if (path.size() != 3) {
		Unsupported("a nested schema");
	}
	for (auto &part : path) {
		if (Text(part).find('.') != std::string::npos) {
			Unsupported("a secure view whose name holds '.'");
		}
	}
	if (view.has_at_clause) {
		// The body would read its tables as of another snapshot with the owner's rights (Addendum 4 item 12).
		Unsupported("a time-travel (AT) read of a secure view");
	}
	auto catalog = Catalog::GetCatalogEntry(context_, path[0]);
	if (!catalog) {
		Unsupported("a secure view of an unknown catalog");
	}
	AddObject("secure_view", "read", Text(path[0]), catalog->GetCatalogType(), catalog->IsTemporaryCatalog(),
	          Text(path[1]), Text(path[2]), {}, via);
	boundary_ = true;
	auto inner = Text(path[0]) + "." + Text(path[1]) + "." + Text(path[2]);
	auto entry = Catalog::GetEntry(context_, EntryLookupInfo(CatalogType::VIEW_ENTRY, view.source_name),
	                               OnEntryNotFound::RETURN_NULL);
	if (!entry || entry->type != CatalogType::VIEW_ENTRY) {
		Unsupported("a secure view without its catalog entry");
	}
	auto &query = entry->Cast<ViewCatalogEntry>().GetQuery();
	Definition definition(context_, Text(path[0]), Text(path[1]));
	definition.Query(*query.node, {});
	auto catalogs = QualifiersIn(query.ToString());
	catalogs.insert(definition.catalogs.begin(), definition.catalogs.end());
	catalogs.insert(StringUtil::Lower(Text(path[0])));
	view_catalogs_[inner] = std::move(catalogs);
	return inner;
}

void Walker::Create(LogicalCreate &create, const std::string &via) {
	auto &info = *create.info;
	std::string kind;
	switch (create.type) {
	case LogicalOperatorType::LOGICAL_CREATE_SCHEMA:
		// [catalog, schema, <empty name>] (CreateSchemaInfo): the schema's own path is its parent.
		SchemaPath("create", info.GetQualifiedName().Parent(), via);
		return;
	case LogicalOperatorType::LOGICAL_CREATE_VIEW:
		kind = info.Cast<CreateViewInfo>().security_type == ViewSecurityType::SECURE_VIEW ? "secure_view" : "view";
		if (info.on_conflict == OnCreateConflict::REPLACE_ON_CONFLICT && create.schema) {
			// CREATE OR REPLACE drops the view it replaces: replacing a secure view is a secure-view change,
			// whatever kind of view takes its name.
			QualifiedName replaced(create.schema->ParentCatalog().GetName(), create.schema->name,
			                       info.GetQualifiedName().Name());
			if (ViewKind(replaced) == "secure_view") {
				InSchema("secure_view", "drop", *create.schema, Text(info.GetQualifiedName().Name()), {}, via);
				kind = "secure_view";
			}
		}
		break;
	case LogicalOperatorType::LOGICAL_CREATE_SEQUENCE:
		kind = "sequence";
		break;
	case LogicalOperatorType::LOGICAL_CREATE_MACRO:
		kind = "macro";
		break;
	case LogicalOperatorType::LOGICAL_CREATE_TYPE:
		kind = "type";
		break;
	case LogicalOperatorType::LOGICAL_CREATE_TRIGGER:
		kind = "trigger";
		break;
	default:
		Unsupported(LogicalOperatorToString(create.type) + " without its schema");
	}
	if (!create.schema) {
		Unsupported(LogicalOperatorToString(create.type) + " without its schema");
	}
	InSchema(kind, "create", *create.schema, Text(info.GetQualifiedName().Name()), {}, via);
	if (create.type == LogicalOperatorType::LOGICAL_CREATE_TRIGGER) {
		// A trigger changes what every write to its table does.
		auto &trigger = info.Cast<CreateTriggerInfo>();
		if (!trigger.base_table) {
			Unsupported("a trigger without its table");
		}
		auto &base = trigger.base_table->Cast<BaseTableRef>();
		InSchema("table", "alter", *create.schema, Text(base.GetQualifiedName().Name()), {}, via);
	}
}

void Walker::CreateTable(LogicalCreateTable &create, const std::string &via) {
	auto &base = create.info->Base();
	InSchema("table", "create", create.schema, Text(base.GetTableName()), {}, via);
	// DuckDB records a foreign key on the table it references (same schema, BindCreateTableConstraints).
	for (auto &constraint : base.constraints) {
		if (constraint->type != ConstraintType::FOREIGN_KEY) {
			continue;
		}
		auto &foreign_key = constraint->Cast<ForeignKeyConstraint>();
		if (foreign_key.info.type == ForeignKeyType::FK_TYPE_FOREIGN_KEY_TABLE) {
			InSchema("table", "alter", create.schema, Text(foreign_key.info.table), {}, via);
		}
	}
	if (create.info->query) {
		Operator(*create.info->query, via);
	}
}

void Walker::Alter(AlterInfo &info, const std::string &via) {
	if (info.type == AlterType::ALTER_DATABASE) {
		Unsupported("ALTER DATABASE");
	}
	if (info.type == AlterType::ALTER_SCHEMA) {
		// The same layout as CREATE SCHEMA's (AlterSchemaInfo).
		SchemaPath("alter", info.GetQualifiedName().Parent(), via);
		return;
	}
	auto kind = KindOf(info.GetCatalogType());
	if (kind.empty()) {
		Unsupported("ALTER of a " + CatalogTypeToString(info.GetCatalogType()));
	}
	if (kind == "view") {
		kind = ViewKind(info.GetQualifiedName());
	}
	Named(kind, "alter", info.GetQualifiedName(), via);
}

void Walker::Drop(DropInfo &info, const std::string &via) {
	switch (info.type) {
	case CatalogType::SECRET_ENTRY:
		AddControl("drop_secret", "", "");
		return;
	case CatalogType::PREPARED_STATEMENT:
		AddControl("deallocate", Text(info.GetQualifiedName().Name()), "");
		return;
	case CatalogType::SCHEMA_ENTRY:
		SchemaPath("drop", info.GetQualifiedName(), via);
		return;
	case CatalogType::INDEX_ENTRY: {
		// Like CREATE INDEX: an index changes its table (a UNIQUE index constrains every write to it).
		Named("index", "drop", info.GetQualifiedName(), via);
		auto entry = Catalog::GetEntry(context_, EntryLookupInfo(CatalogType::INDEX_ENTRY, info.GetQualifiedName()),
		                               OnEntryNotFound::RETURN_NULL);
		if (entry && entry->type == CatalogType::INDEX_ENTRY) {
			auto &index = entry->Cast<IndexCatalogEntry>();
			InSchema("table", "alter", index.ParentSchema(), Text(index.GetTableName()), {}, via);
		}
		return;
	}
	case CatalogType::TRIGGER_ENTRY:
		Named("trigger", "drop", info.GetQualifiedName(), via);
		if (info.extra_drop_info) {
			auto &extra = info.extra_drop_info->Cast<ExtraDropTriggerInfo>();
			if (extra.base_table) {
				Named("table", "alter", extra.base_table->Cast<BaseTableRef>().GetQualifiedName(), via);
			}
		}
		return;
	default:
		break;
	}
	auto kind = KindOf(info.type);
	if (kind.empty() || kind == "schema") {
		Unsupported("DROP of a " + CatalogTypeToString(info.type));
	}
	if (kind == "view") {
		kind = ViewKind(info.GetQualifiedName());
	}
	Named(kind, "drop", info.GetQualifiedName(), via);
}

//! The extension operators the spike met (DuckLake, Iceberg, Postgres at their pinned commits).
void Walker::ExtensionOperator(LogicalExtensionOperator &op, const std::string &via) {
	auto name = op.GetName();
	std::string extension;
	try {
		extension = op.GetExtensionName();
	} catch (std::exception &) {
		// Only operators that serialize implement it.
		extension.clear();
	}
	if ((name == "DUCKLAKE_FLUSH_DATA" || name == "DUCKLAKE_COMPACTION") && extension == "ducklake") {
		AddControl("extension_operator", name, extension);
	} else if (name == "ICEBERG_REWRITE_DATA_FILES" && extension.empty()) {
		AddControl("extension_operator", name, "");
	} else if (name == "CUSTOM_OP" && extension.empty()) {
		UnnamedStep(op, via);
	} else {
		Unsupported("the extension operator " + name);
	}
}

//! The statement DuckDB parsed for this plan, re-parsed from its text, under the EXPLAIN and PREPARE
//! that only carry it; null when no text is reachable or it is not exactly one statement.
SQLStatement *Walker::Statement() {
	if (!parsed_) {
		parsed_ = true;
		if (statement_text_) {
			Parser parser(context_);
			try {
				parser.ParseQuery(*statement_text_);
			} catch (std::exception &) {
				parser.statements.clear();
			}
			if (parser.statements.size() == 1) {
				statements_ = std::move(parser.statements);
			}
		}
	}
	return Unwrapped(statements_.empty() ? nullptr : statements_[0].get());
}

//! A COPY's file as a string literal, or empty (an expression, a list).
std::string LiteralPath(const CopyInfo &info) {
	if (!info.file_path.empty()) {
		return info.file_path;
	}
	if (info.file_path_expression && info.file_path_expression->GetExpressionClass() == ExpressionClass::CONSTANT) {
		auto &literal = info.file_path_expression->Cast<ConstantExpression>().GetLiteral();
		if (literal.kind == LiteralKind::STRING) {
			return literal.text;
		}
	}
	return "";
}

//! The format the binder gives a parsed COPY writing `path` (Binder::BindCopyFormat): a FORMAT option
//! the transformer kept as an expression (a string literal or a bare name; anything else is unknown,
//! empty), else the FORMAT the transformer read (CopyInfo::format), else the path's extension when
//! the format is auto-detected (ExtractFormat: a .gz or .zst suffix dropped). Lower case.
std::string CopyFormat(const CopyInfo &info, const std::string &path) {
	auto option = info.parsed_options.find("format");
	if (option != info.parsed_options.end()) {
		if (!option->second) {
			return "";
		}
		auto &format = *option->second;
		if (format.GetExpressionClass() == ExpressionClass::CONSTANT) {
			auto &literal = format.Cast<ConstantExpression>().GetLiteral();
			return literal.kind == LiteralKind::STRING ? StringUtil::Lower(literal.text) : "";
		}
		if (format.GetExpressionClass() == ExpressionClass::COLUMN_REF) {
			auto &column = format.Cast<ColumnRefExpression>();
			return column.IsQualified() ? "" : StringUtil::Lower(Text(column.GetColumnName()));
		}
		return "";
	}
	if (!info.format.empty()) {
		return StringUtil::Lower(info.format);
	}
	if (!info.is_format_auto_detected) {
		return "";
	}
	auto format = StringUtil::Lower(path);
	for (auto suffix : {".gz", ".zst"}) {
		if (StringUtil::EndsWith(format, suffix)) {
			format = format.substr(0, format.size() - std::string(suffix).size());
			break;
		}
	}
	auto dot = format.rfind('.');
	return dot == std::string::npos || dot + 1 == format.size() ? "" : format.substr(dot + 1);
}

//! COPY ... FROM's reader has no parameters (the binder keeps the path in its opaque bind data,
//! Binder::BindCopyFrom): its location is the one the parsed COPY FROM names. None when the text is
//! unreachable or names no literal path, which the manager refuses for a File function.
std::vector<std::string> Walker::CopyFromLocations(const std::string &via) {
	auto path = statement_text_ ? CopyFromLiteralPath(context_, *statement_text_) : "";
	if (path.empty()) {
		return {};
	}
	return {Location(path, via)};
}

//! Decision 19: a step naming itself neither by name nor by extension is judged from the statement
//! DuckDB parsed. Only two exist (spike item 3), each recognized by its statement and shape.
void Walker::UnnamedStep(LogicalExtensionOperator &op, const std::string &via) {
	auto statement = &op == core_ ? Statement() : nullptr;
	if (statement && statement->type == StatementType::COPY_STATEMENT && op.children.size() == 1) {
		// IcebergLogicalCopy (duckdb-iceberg src/function/copy/iceberg_copy_function.cpp:13-31): the
		// COPY's location side, decision 18; the plan beneath it is walked by the caller.
		auto &info = *statement->Cast<CopyStatement>().info;
		auto target = LiteralPath(info);
		if (info.is_from || target.empty() || CopyFormat(info, target) != "iceberg") {
			Unsupported("an extension operator that names itself neither by name nor by extension");
		}
		AddControl("copy_to", Location(target, via), "iceberg");
		return;
	}
	if (statement && statement->type == StatementType::CREATE_STATEMENT && op.children.empty()) {
		// LogicalPostgresCreateIndex (duckdb-postgres src/storage/postgres_index.cpp:62-82): an alter of
		// its table, resolved as DuckDB resolves it, with the session's search path.
		auto &create = statement->Cast<CreateStatement>();
		if (create.info->type == CatalogType::INDEX_ENTRY) {
			auto &index = create.info->Cast<CreateIndexInfo>();
			CatalogEntryRetriever retriever(context_);
			auto table_name = Binder::BindTableName(retriever, index.GetQualifiedName().WithName(index.table));
			auto table = Catalog::GetEntry<TableCatalogEntry>(context_, table_name, OnEntryNotFound::RETURN_NULL);
			if (table && table->ParentCatalog().GetCatalogType() == "postgres") {
				AddTable(*table, "alter", via);
				InSchema("index", "create", table->ParentSchema(), Text(index.GetIndexName()), {}, via);
				return;
			}
		}
	}
	Unsupported("an extension operator that names itself neither by name nor by extension");
}

void Walker::Constraints(vector<unique_ptr<BoundConstraint>> &constraints, const std::string &via) {
	for (auto &constraint : constraints) {
		if (constraint && constraint->type == ConstraintType::CHECK) {
			auto &check = constraint->Cast<BoundCheckConstraint>();
			if (check.expression) {
				Expr(*check.expression, via);
			}
		}
	}
}

//! The expressions LogicalOperatorVisitor::EnumerateExpressions does not reach.
void Walker::ExtraExpressions(LogicalOperator &op, const std::string &via) {
	auto each = [&](vector<unique_ptr<duckdb::Expression>> &expressions) {
		for (auto &expr : expressions) {
			if (expr) {
				Expr(*expr, via);
			}
		}
	};
	switch (op.type) {
	case LogicalOperatorType::LOGICAL_INSERT: {
		auto &insert = op.Cast<LogicalInsert>();
		for (auto &row : insert.insert_values) {
			each(row);
		}
		each(insert.bound_defaults);
		Constraints(insert.bound_constraints, via);
		break;
	}
	case LogicalOperatorType::LOGICAL_UPDATE: {
		auto &update = op.Cast<LogicalUpdate>();
		each(update.bound_defaults);
		Constraints(update.bound_constraints, via);
		break;
	}
	case LogicalOperatorType::LOGICAL_DELETE:
		Constraints(op.Cast<LogicalDelete>().bound_constraints, via);
		break;
	case LogicalOperatorType::LOGICAL_MERGE_INTO: {
		auto &merge = op.Cast<LogicalMergeInto>();
		each(merge.bound_defaults);
		Constraints(merge.bound_constraints, via);
		break;
	}
	case LogicalOperatorType::LOGICAL_COPY_TO_FILE: {
		auto &copy = op.Cast<LogicalCopyToFile>();
		if (copy.partition_path_expression) {
			Expr(*copy.partition_path_expression, via);
		}
		for (auto &order : copy.order_columns) {
			if (order.expression) {
				Expr(*order.expression, via);
			}
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_CREATE_INDEX:
		each(op.Cast<LogicalCreateIndex>().unbound_expressions);
		break;
	case LogicalOperatorType::LOGICAL_SECURE_VIEW: {
		auto &view = op.Cast<LogicalSecureView>();
		each(view.output_expressions);
		each(view.source_filters);
		break;
	}
	case LogicalOperatorType::LOGICAL_PIVOT:
		each(op.Cast<LogicalPivot>().bound_pivot.aggregates);
		break;
	default:
		break;
	}
}

void Walker::Operator(LogicalOperator &op, const std::string &via) {
	switch (OperatorHandling(op.type)) {
	case Handling::DESCRIBED:
		break;
	case Handling::REFUSED:
		Unsupported(LogicalOperatorToString(op.type));
	case Handling::UNKNOWN:
		Unsupported("the operator type " + std::to_string(static_cast<int>(op.type)));
	}
	auto child_via = via;
	switch (op.type) {
	case LogicalOperatorType::LOGICAL_GET:
		Get(op.Cast<LogicalGet>(), via);
		break;
	case LogicalOperatorType::LOGICAL_SECURE_VIEW:
		child_via = SecureView(op.Cast<LogicalSecureView>(), via);
		break;
	case LogicalOperatorType::LOGICAL_INSERT: {
		auto &insert = op.Cast<LogicalInsert>();
		AddTable(insert.table, "insert", via);
		auto action = insert.on_conflict_info.action_type;
		if (action == OnConflictAction::UPDATE || action == OnConflictAction::REPLACE) {
			AddTable(insert.table, "update", via);
		}
		if (insert.return_chunk) {
			Returning(insert.table, via);
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_UPDATE: {
		auto &update = op.Cast<LogicalUpdate>();
		AddTable(update.table, "update", via);
		if (update.return_chunk) {
			Returning(update.table, via);
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_DELETE: {
		auto &del = op.Cast<LogicalDelete>();
		AddTable(del.table, "delete", via);
		if (del.return_chunk) {
			Returning(del.table, via);
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_MERGE_INTO: {
		auto &merge = op.Cast<LogicalMergeInto>();
		for (auto &entry : merge.actions) {
			for (auto &action : entry.second) {
				switch (action->action_type) {
				case MergeActionType::MERGE_UPDATE:
					AddTable(merge.table, "update", via);
					break;
				case MergeActionType::MERGE_DELETE:
					AddTable(merge.table, "delete", via);
					break;
				case MergeActionType::MERGE_INSERT:
					AddTable(merge.table, "insert", via);
					break;
				default:
					break;
				}
			}
		}
		if (merge.return_chunk) {
			Returning(merge.table, via);
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_CREATE_TABLE:
		CreateTable(op.Cast<LogicalCreateTable>(), via);
		break;
	case LogicalOperatorType::LOGICAL_CREATE_INDEX: {
		auto &index = op.Cast<LogicalCreateIndex>();
		AddTable(index.table, "alter", via);
		InSchema("index", "create", index.table.ParentSchema(), Text(index.info->GetIndexName()), {}, via);
		break;
	}
	case LogicalOperatorType::LOGICAL_CREATE_VIEW:
	case LogicalOperatorType::LOGICAL_CREATE_SEQUENCE:
	case LogicalOperatorType::LOGICAL_CREATE_SCHEMA:
	case LogicalOperatorType::LOGICAL_CREATE_MACRO:
	case LogicalOperatorType::LOGICAL_CREATE_TYPE:
	case LogicalOperatorType::LOGICAL_CREATE_TRIGGER:
		Create(op.Cast<LogicalCreate>(), via);
		break;
	case LogicalOperatorType::LOGICAL_ALTER:
		Alter(*op.Cast<LogicalAlter>().info, via);
		break;
	case LogicalOperatorType::LOGICAL_DROP:
		Drop(*op.Cast<LogicalDrop>().info, via);
		break;
	case LogicalOperatorType::LOGICAL_PRAGMA: {
		auto &info = *op.Cast<LogicalPragma>().info;
		AddControl("pragma", Text(info.function.name), "");
		// No arguments (CONTRACT Addendum 4 item 6): only a File function's locations leave the node.
		AddFunction("pragma", info.function.GetQualifiedName(), {}, via);
		break;
	}
	case LogicalOperatorType::LOGICAL_TRANSACTION:
		switch (op.Cast<LogicalTransaction>().info->type) {
		case TransactionType::BEGIN_TRANSACTION:
			AddControl("transaction", "begin", "");
			break;
		case TransactionType::COMMIT:
			AddControl("transaction", "commit", "");
			break;
		case TransactionType::ROLLBACK:
			AddControl("transaction", "rollback", "");
			break;
		default:
			Unsupported("an invalid transaction statement");
		}
		break;
	case LogicalOperatorType::LOGICAL_ATTACH:
		// Never its path or options: they can carry credentials.
		AddControl("attach", "", "");
		break;
	case LogicalOperatorType::LOGICAL_DETACH: {
		auto attached = Catalog::GetCatalogEntry(context_, op.Cast<LogicalDetach>().info->name);
		AddControl("detach", "", attached ? attached->GetCatalogType() : "");
		break;
	}
	case LogicalOperatorType::LOGICAL_LOAD: {
		auto &info = *op.Cast<LogicalLoad>().info;
		auto load = info.load_type == LoadType::LOAD || info.load_type == LoadType::LOAD_AS;
		AddControl(load ? "load" : "install", RedactedUrl(info.filename), "");
		break;
	}
	case LogicalOperatorType::LOGICAL_EXPLAIN:
		if (op.Cast<LogicalExplain>().explain_type == ExplainType::EXPLAIN_ANALYZE) {
			AddControl("explain_analyze", "", "");
		}
		break;
	case LogicalOperatorType::LOGICAL_PREPARE:
		AddControl("prepare", Text(op.Cast<LogicalPrepare>().name), "");
		break;
	case LogicalOperatorType::LOGICAL_EXECUTE:
		AddControl("execute", "", "");
		break;
	case LogicalOperatorType::LOGICAL_EXPORT: {
		auto &exported = op.Cast<LogicalExport>();
		AddControl("export", Location(exported.copy_info->file_path, via), Text(exported.function.name));
		if (exported.exported_tables) {
			for (auto &table : exported.exported_tables->data) {
				Returning(table.entry, via);
			}
		}
		break;
	}
	case LogicalOperatorType::LOGICAL_VACUUM:
		AddControl("vacuum", "", "");
		break;
	case LogicalOperatorType::LOGICAL_SET: {
		auto &set = op.Cast<LogicalSet>();
		AddSetting(set.name, set.scope, &set.value);
		break;
	}
	case LogicalOperatorType::LOGICAL_RESET: {
		auto &reset = op.Cast<LogicalReset>();
		AddSetting(reset.name, reset.scope, nullptr);
		break;
	}
	case LogicalOperatorType::LOGICAL_UPDATE_EXTENSIONS:
		AddControl("update_extensions", "", "");
		break;
	case LogicalOperatorType::LOGICAL_CREATE_SECRET:
		// Never its options: they are the credentials.
		AddControl("create_secret", "", Text(op.Cast<LogicalCreateSecret>().secret_input.type));
		break;
	case LogicalOperatorType::LOGICAL_COPY_TO_FILE: {
		auto &copy = op.Cast<LogicalCopyToFile>();
		AddControl("copy_to", Location(copy.file_path, via), Text(copy.function.name));
		break;
	}
	case LogicalOperatorType::LOGICAL_COPY_DATABASE:
		AddControl("copy_database", "", "");
		break;
	case LogicalOperatorType::LOGICAL_EXTENSION_OPERATOR:
		ExtensionOperator(op.Cast<LogicalExtensionOperator>(), via);
		break;
	default:
		break;
	}
	LogicalOperatorVisitor::EnumerateExpressions(op, [&](unique_ptr<duckdb::Expression> *child) {
		if (*child) {
			Expr(**child, via);
		}
	});
	ExtraExpressions(op, via);
	for (auto &child : op.children) {
		if (child) {
			Operator(*child, child_via);
		}
	}
}

void Walker::CheckSession() {
	if (!boundary_) {
		return;
	}
	if (!ClientConfig::GetConfig(context_).user_variables.empty()) {
		Unsupported("a secure view while the session holds variables");
	}
	// A body binds unqualified names in temp.main first: a temporary view, macro or type (and anything
	// else but a table or a sequence, which the walk sees where they are used) can shadow what the body
	// names and leave no trace in the plan (CONTRACT Addendum 4 item 10). Every set of every schema of
	// the temporary catalog (DuckSchemaEntry::GetCatalogSet; views share the tables' set, table macros
	// the table functions', macros the functions').
	bool shadowing = false;
	Catalog::GetCatalog(context_, Identifier::TempCatalog()).ScanSchemas(context_, [&](SchemaCatalogEntry &schema) {
		if (!schema.internal && Text(schema.name) != Text(Identifier::DefaultSchema())) {
			shadowing = true;
		}
		for (auto set : {CatalogType::TABLE_ENTRY, CatalogType::INDEX_ENTRY, CatalogType::TABLE_FUNCTION_ENTRY,
		                 CatalogType::COPY_FUNCTION_ENTRY, CatalogType::PRAGMA_FUNCTION_ENTRY,
		                 CatalogType::SCALAR_FUNCTION_ENTRY, CatalogType::SEQUENCE_ENTRY,
		                 CatalogType::COLLATION_ENTRY, CatalogType::COORDINATE_SYSTEM_ENTRY, CatalogType::TYPE_ENTRY,
		                 CatalogType::SCHEMA_ENTRY}) {
			schema.Scan(context_, set, [&](CatalogEntry &entry) {
				// Item 21: an index changes no rows a query returns and no query names one.
				if (!entry.internal && entry.type != CatalogType::TABLE_ENTRY &&
				    entry.type != CatalogType::SEQUENCE_ENTRY && entry.type != CatalogType::INDEX_ENTRY &&
				    !IsPivotEnum(entry)) {
					shadowing = true;
				}
			});
		}
	});
	if (shadowing) {
		Unsupported("a secure view while the session holds temporary entries other than tables and sequences");
	}
	// The session's settings steer a body too (default_order, default_collation, ...): only the schema
	// and search path the manager sets are admitted (CONTRACT Addendum 4 item 11 a).
	auto changed = ChangedSetting();
	if (!changed.empty()) {
		Unsupported("a secure view while the session has changed the setting " + changed);
	}
	auto &paths = ClientData::Get(context_).catalog_search_path->GetSetPaths();
	auto expected = context_.registered_state->Get<SessionPaths>(SessionPaths::kKey);
	if (expected && !expected->expected.empty() ? !ExpectedPaths(paths, expected->expected)
	                                            : !SessionDefaultPaths(paths)) {
		Unsupported("a secure view while the session's search path differs from the one it was given");
	}
}

//! Exactly one set path, naming the catalog and schema of `expected` (`<catalog>.<schema>`, as the
//! manager's USE prelude names them), case aside as DuckDB resolves names. A malformed `expected`
//! matches nothing.
bool Walker::ExpectedPaths(const vector<CatalogSearchEntry> &paths, const std::string &expected) {
	if (paths.size() != 1) {
		return false;
	}
	auto lower = [](const Identifier &name) {
		return StringUtil::Lower(Text(name));
	};
	try {
		auto want = CatalogSearchEntry::Parse(expected);
		return !IsInvalidCatalog(want.GetCatalog()) && lower(paths[0].GetCatalog()) == lower(want.GetCatalog()) &&
		       lower(paths[0].GetSchema()) == lower(want.GetSchema());
	} catch (std::exception &) {
		return false;
	}
}

//! The first setting (core or extension) the session changed for itself, other than schema and
//! search_path, or empty. A changed setting is one set in ClientConfig::user_settings; the few that
//! keep their own client state (profiling, progress bar, dialect and grammar, streaming buffer,
//! operator memory limit, schema, search_path) change nothing a body binds or returns.
std::string Walker::ChangedSetting() {
	auto &user_settings = ClientConfig::GetConfig(context_).user_settings;
	auto admitted = [](const std::string &name) {
		return name == "schema" || name == "search_path";
	};
	for (idx_t i = 0; i < DBConfig::GetOptionCount(); i++) {
		auto option = DBConfig::GetOptionByIndex(i);
		if (option && option->setting_idx.IsValid() && user_settings.IsSet(option->setting_idx.GetIndex()) &&
		    !admitted(option->name)) {
			return option->name;
		}
	}
	for (auto &entry : DBConfig::GetConfig(context_).GetExtensionSettings()) {
		auto name = Text(entry.first);
		if (entry.second.setting_index.IsValid() && user_settings.IsSet(entry.second.setting_index.GetIndex()) &&
		    !admitted(name)) {
			return name;
		}
	}
	return "";
}

//! No set path, or the database the connection connected to (ClientData::default_database) with its
//! default schema: the resolution a fresh connection has.
bool Walker::SessionDefaultPaths(const vector<CatalogSearchEntry> &paths) {
	if (paths.empty()) {
		return true;
	}
	if (paths.size() != 1) {
		return false;
	}
	auto &catalog = paths[0].GetCatalog();
	auto &connected = ClientData::Get(context_).default_database;
	return (IsInvalidCatalog(catalog) || Text(catalog) == Text(connected)) &&
	       Text(paths[0].GetSchema()) == Text(Identifier::DefaultSchema());
}

// ---- expressions, functions, settings ----------------------------------------------------------

void Walker::Expr(duckdb::Expression &expr, const std::string &via) {
	auto expression_class = expr.GetExpressionClass();
	switch (ExpressionHandling(expression_class)) {
	case Handling::DESCRIBED:
		break;
	case Handling::REFUSED:
		Unsupported("the expression class " + ExpressionClassToString(expression_class));
	case Handling::UNKNOWN:
		Unsupported("the expression class " + std::to_string(static_cast<int>(expression_class)));
	}
	switch (expression_class) {
	case ExpressionClass::BOUND_FUNCTION: {
		auto &function = expr.Cast<BoundFunctionExpression>();
		auto &qualified = function.Function().GetQualifiedName();
		AddFunction("scalar", qualified, {}, via);
		// The sequence a constant argument names, resolved at bind (NextValBind): a write for nextval and
		// setval, a read for currval. A NULL argument binds no sequence and the call returns NULL.
		auto name = Text(qualified.Name());
		if (Text(qualified.Catalog()) == "system" &&
		    (name == "nextval" || name == "setval" || name == "currval") && function.BindInfo()) {
			AddEntry("sequence", name == "currval" ? "read" : "update",
			         function.BindInfo()->Cast<NextvalBindData>().sequence, {}, via);
		}
		// A lambda function also keeps its body in its bind data (ListLambdaBindData), which the
		// executor runs: walk it there too, read only.
		if (function.Function().HasBindLambdaCallback() && function.BindInfo()) {
			auto body = function.BindInfo()->Cast<LambdaFunctionData>().GetLambdaExpression();
			if (body) {
				Expr(const_cast<duckdb::Expression &>(*body), via);
			}
		}
		break;
	}
	case ExpressionClass::BOUND_AGGREGATE:
		AddFunction("aggregate", expr.Cast<BoundAggregateExpression>().Function().GetQualifiedName(), {}, via);
		break;
	case ExpressionClass::BOUND_WINDOW: {
		auto &window = expr.Cast<BoundWindowExpression>();
		if (window.AggregateFunction()) {
			AddFunction("aggregate", window.AggregateFunction()->GetQualifiedName(), {}, via);
		}
		if (window.WindowFunction()) {
			AddFunction("window", window.WindowFunction()->GetQualifiedName(), {}, via);
		}
		break;
	}
	case ExpressionClass::BOUND_SUBQUERY: {
		auto &subquery = expr.Cast<BoundSubqueryExpression>().SubqueryMutable();
		if (subquery.plan) {
			Operator(*subquery.plan, via);
		}
		break;
	}
	case ExpressionClass::BOUND_LAMBDA: {
		// ExpressionIterator::EnumerateChildren does not enter a lambda.
		auto &lambda = expr.Cast<BoundLambdaExpression>();
		if (lambda.LambdaExprMutable()) {
			Expr(*lambda.LambdaExprMutable(), via);
		}
		for (auto &capture : lambda.CapturesMutable()) {
			if (capture) {
				Expr(*capture, via);
			}
		}
		break;
	}
	default:
		break;
	}
	ExpressionIterator::EnumerateChildren(expr, [&](duckdb::Expression &child) { Expr(child, via); });
}

void Walker::AddFunction(const std::string &type, const QualifiedName &qualified, const std::vector<std::string> &args,
                         const std::string &via) {
	auto name = Text(qualified.Name());
	if (!via.empty() && (name == "current_setting" || name == "getvariable")) {
		Unsupported(name + "() inside a secure view");
	}
	auto &path = qualified.Path();
	std::string catalog;
	std::string schema;
	if (path.size() > 3) {
		Unsupported("a nested schema");
	}
	if (path.size() == 3) {
		catalog = Text(path[0]);
		schema = Text(path[1]);
	} else if (type == "scalar" || type == "aggregate" || type == "window") {
		// Bound by DuckDB for SQL syntax (comparisons, casts), never by name resolution. Also absent: every
		// function a slot DuckDB evaluates while binding called (SET values, table-function arguments,
		// EXECUTE parameters, AT clauses, foldable LIMIT and OFFSET, PIVOT IN lists, COLUMNS(), type
		// expressions, unnest depth, CREATE VIEW bodies, ALTER ADD COLUMN DEFAULT and ALTER COLUMN TYPE
		// USING): the plan holds their result, and the parse screen owns them (CONTRACT Addendum 4 item 9).
		return;
	} else if (path.size() == 2) {
		schema = Text(path[0]);
	}
	if (!catalog.empty()) {
		InView(catalog, via);
	}
	Add(functions_, JsonObject({{"args", Strings(args)},
	                            {"catalog", JsonString(catalog)},
	                            {"name", JsonString(name)},
	                            {"schema", JsonString(schema)},
	                            {"type", JsonString(type)},
	                            {"via", StringOrNull(via)}}));
}

//! A File function's resolved locations; every other table function carries none (CONTRACT Addendum 4
//! item 6), so a credential a statement names (a postgres_scan connection string) never leaves the node.
std::vector<std::string> Walker::TableArgs(LogicalGet &get, const std::string &name, const std::string &via) {
	if (!IsFileFunction(name)) {
		return {};
	}
	if (get.parameters.empty()) {
		return CopyFromLocations(via);
	}
	// A file function's locations (the first positional argument), each resolved; nothing else.
	std::vector<std::string> args;
	for (auto &given : LocationStrings(get.parameters[0], via)) {
		args.push_back(Location(given, via));
	}
	return args;
}

//! The strings a file argument names: a string, or every element of a list of strings.
std::vector<std::string> Walker::LocationStrings(const Value &value, const std::string &via) {
	auto is_string = [](const Value &v) {
		return !v.IsNull() &&
		       (v.type().id() == LogicalTypeId::VARCHAR || v.type().id() == LogicalTypeId::STRING_LITERAL);
	};
	std::vector<std::string> strings;
	if (is_string(value)) {
		strings.push_back(value.GetValue<string>());
		return strings;
	}
	if (!value.IsNull() && value.type().id() == LogicalTypeId::LIST) {
		bool all = true;
		for (auto &child : ListValue::GetChildren(value)) {
			all = all && is_string(child);
			if (all) {
				strings.push_back(child.GetValue<string>());
			}
		}
		if (all) {
			return strings;
		}
	}
	if (Trusted() || !via.empty()) {
		return {value.ToString()};
	}
	throw PermissionException("QoD: " + value.ToString() + " is outside the tenant's authorized locations");
}

//! `given` as a description carries it, as the parse screen renders it (DescribedLocation). A
//! principal that is neither superuser nor system is refused a location the screen would refuse,
//! outside a secure view (a body a superuser wrote runs with its
//! owner's rights, and the manager judges it so); the parse screen refused such a path before bind
//! already, so this fires only on a path a statement reached some other way.
std::string Walker::Location(const std::string &given, const std::string &via) {
	return DescribedLocation(given, !Trusted() && via.empty());
}

void Walker::AddSetting(const Identifier &raw, SetScope scope, const Value *value) {
	if (scope == SetScope::VARIABLE) {
		Add(settings_,
		    JsonObject({{"name", JsonString(Text(raw))}, {"scope", JsonString("variable")}, {"value", JsonNull()}}));
		return;
	}
	auto name = StringUtil::Lower(Text(raw));
	if (IsLockedSetting(name)) {
		Locked(name);
	}
	bool global;
	ExtensionOption extension;
	auto option = DBConfig::GetOptionByName(raw);
	if (option) {
		name = option->name;
		global = PhysicalSet::GetSettingScope(*option, scope) == SetScope::GLOBAL;
	} else if (DBConfig::GetConfig(context_).TryGetExtensionOption(raw, extension)) {
		global = extension.ResolveScope(raw, scope) == SetScope::GLOBAL;
	} else {
		// Unknown until DuckDB autoloads its extension: judged at the widest scope it could take.
		global = scope != SetScope::SESSION && scope != SetScope::LOCAL;
	}
	if (IsLockedSetting(name)) {
		Locked(name);
	}
	auto resolved = global ? "global" : scope == SetScope::LOCAL ? "local" : "session";
	// Only the values the manager reads leave the node: a setting can hold a credential
	// (http_proxy_password, an azure_* key).
	auto sent = value && (name == "schema" || name == "search_path");
	Add(settings_, JsonObject({{"name", JsonString(name)},
	                           {"scope", JsonString(resolved)},
	                           {"value", sent ? JsonString(value->ToString()) : JsonNull()}}));
}

} // namespace

Handling OperatorHandling(LogicalOperatorType type) {
	switch (type) {
	case LogicalOperatorType::LOGICAL_PROJECTION:
	case LogicalOperatorType::LOGICAL_FILTER:
	case LogicalOperatorType::LOGICAL_AGGREGATE_AND_GROUP_BY:
	case LogicalOperatorType::LOGICAL_WINDOW:
	case LogicalOperatorType::LOGICAL_UNNEST:
	case LogicalOperatorType::LOGICAL_LIMIT:
	case LogicalOperatorType::LOGICAL_ORDER_BY:
	case LogicalOperatorType::LOGICAL_TOP_N:
	case LogicalOperatorType::LOGICAL_COPY_TO_FILE:
	case LogicalOperatorType::LOGICAL_DISTINCT:
	case LogicalOperatorType::LOGICAL_SAMPLE:
	case LogicalOperatorType::LOGICAL_PIVOT:
	case LogicalOperatorType::LOGICAL_COPY_DATABASE:
	case LogicalOperatorType::LOGICAL_SECURE_VIEW:
	case LogicalOperatorType::LOGICAL_GET:
	// DESCRIBE (query) and SHOW <setting> bind to a projection over a chunk DuckDB filled while binding,
	// with nothing of what they read in the plan: the parse screen refuses both (screen_rules.cpp), and
	// must keep doing so.
	case LogicalOperatorType::LOGICAL_CHUNK_GET:
	case LogicalOperatorType::LOGICAL_DELIM_GET:
	case LogicalOperatorType::LOGICAL_EXPRESSION_GET:
	case LogicalOperatorType::LOGICAL_DUMMY_SCAN:
	case LogicalOperatorType::LOGICAL_EMPTY_RESULT:
	case LogicalOperatorType::LOGICAL_CTE_REF:
	case LogicalOperatorType::LOGICAL_JOIN:
	case LogicalOperatorType::LOGICAL_DELIM_JOIN:
	case LogicalOperatorType::LOGICAL_COMPARISON_JOIN:
	case LogicalOperatorType::LOGICAL_ANY_JOIN:
	case LogicalOperatorType::LOGICAL_CROSS_PRODUCT:
	case LogicalOperatorType::LOGICAL_POSITIONAL_JOIN:
	case LogicalOperatorType::LOGICAL_ASOF_JOIN:
	case LogicalOperatorType::LOGICAL_DEPENDENT_JOIN:
	case LogicalOperatorType::LOGICAL_UNION:
	case LogicalOperatorType::LOGICAL_EXCEPT:
	case LogicalOperatorType::LOGICAL_INTERSECT:
	case LogicalOperatorType::LOGICAL_RECURSIVE_CTE:
	case LogicalOperatorType::LOGICAL_MATERIALIZED_CTE:
	case LogicalOperatorType::LOGICAL_INSERT:
	case LogicalOperatorType::LOGICAL_DELETE:
	case LogicalOperatorType::LOGICAL_UPDATE:
	case LogicalOperatorType::LOGICAL_MERGE_INTO:
	case LogicalOperatorType::LOGICAL_TRIGGER:
	case LogicalOperatorType::LOGICAL_ALTER:
	case LogicalOperatorType::LOGICAL_CREATE_TABLE:
	case LogicalOperatorType::LOGICAL_CREATE_INDEX:
	case LogicalOperatorType::LOGICAL_CREATE_SEQUENCE:
	case LogicalOperatorType::LOGICAL_CREATE_VIEW:
	case LogicalOperatorType::LOGICAL_CREATE_SCHEMA:
	case LogicalOperatorType::LOGICAL_CREATE_MACRO:
	case LogicalOperatorType::LOGICAL_DROP:
	case LogicalOperatorType::LOGICAL_PRAGMA:
	case LogicalOperatorType::LOGICAL_TRANSACTION:
	case LogicalOperatorType::LOGICAL_CREATE_TYPE:
	case LogicalOperatorType::LOGICAL_ATTACH:
	case LogicalOperatorType::LOGICAL_DETACH:
	case LogicalOperatorType::LOGICAL_CREATE_TRIGGER:
	case LogicalOperatorType::LOGICAL_EXPLAIN:
	case LogicalOperatorType::LOGICAL_PREPARE:
	case LogicalOperatorType::LOGICAL_EXECUTE:
	case LogicalOperatorType::LOGICAL_EXPORT:
	case LogicalOperatorType::LOGICAL_VACUUM:
	case LogicalOperatorType::LOGICAL_SET:
	case LogicalOperatorType::LOGICAL_LOAD:
	case LogicalOperatorType::LOGICAL_RESET:
	case LogicalOperatorType::LOGICAL_UPDATE_EXTENSIONS:
	case LogicalOperatorType::LOGICAL_CREATE_SECRET:
	case LogicalOperatorType::LOGICAL_EXTENSION_OPERATOR:
		return Handling::DESCRIBED;
	case LogicalOperatorType::LOGICAL_INVALID:
	case LogicalOperatorType::LOGICAL_CONNECT:
	case LogicalOperatorType::LOGICAL_DISCONNECT:
	case LogicalOperatorType::LOGICAL_EXTERNAL_RESOURCE:
		return Handling::REFUSED;
	default:
		return Handling::UNKNOWN;
	}
}

Handling ExpressionHandling(ExpressionClass expression_class) {
	switch (expression_class) {
	case ExpressionClass::BOUND_AGGREGATE:
	case ExpressionClass::BOUND_CASE:
	case ExpressionClass::BOUND_COLUMN_REF:
	case ExpressionClass::BOUND_CONJUNCTION:
	case ExpressionClass::BOUND_CONSTANT:
	case ExpressionClass::BOUND_DEFAULT:
	case ExpressionClass::BOUND_FUNCTION:
	case ExpressionClass::BOUND_OPERATOR:
	case ExpressionClass::BOUND_PARAMETER:
	case ExpressionClass::BOUND_REF:
	case ExpressionClass::BOUND_SUBQUERY:
	case ExpressionClass::BOUND_WINDOW:
	case ExpressionClass::BOUND_UNNEST:
	case ExpressionClass::BOUND_LAMBDA:
	case ExpressionClass::BOUND_LAMBDA_REF:
		return Handling::DESCRIBED;
	// Parsed expressions never reach a bound plan; the legacy classes and BOUND_EXPANDED are not produced
	// by binding at v2.0.0. Any of them in a plan means something QoD has not read.
	case ExpressionClass::INVALID:
	case ExpressionClass::AGGREGATE:
	case ExpressionClass::CASE:
	case ExpressionClass::CAST:
	case ExpressionClass::COLUMN_REF:
	case ExpressionClass::COMPARISON:
	case ExpressionClass::CONJUNCTION:
	case ExpressionClass::CONSTANT:
	case ExpressionClass::DEFAULT:
	case ExpressionClass::FUNCTION:
	case ExpressionClass::OPERATOR:
	case ExpressionClass::STAR:
	case ExpressionClass::SUBQUERY:
	case ExpressionClass::WINDOW:
	case ExpressionClass::PARAMETER:
	case ExpressionClass::COLLATE:
	case ExpressionClass::LAMBDA:
	case ExpressionClass::POSITIONAL_REFERENCE:
	case ExpressionClass::BETWEEN:
	case ExpressionClass::LAMBDA_REF:
	case ExpressionClass::TYPE:
	case ExpressionClass::LEGACY_BOUND_CAST:
	case ExpressionClass::LEGACY_BOUND_COMPARISON:
	case ExpressionClass::LEGACY_BOUND_BETWEEN:
	case ExpressionClass::BOUND_EXPANDED:
		return Handling::REFUSED;
	default:
		return Handling::UNKNOWN;
	}
}

bool IsLockedSetting(const std::string &name) {
	static const std::set<std::string> locked = {"debug_disable_optimizer", "disabled_optimizers", "enable_optimizer",
	                                             "quack_authentication_function", "quack_authorization_function"};
	return locked.count(duckdb::StringUtil::Lower(name)) != 0;
}

Described Describe(duckdb::ClientContext &context, duckdb::LogicalOperator &plan, const Principal &principal,
                   const std::string *statement_text) {
	std::string canonical;
	try {
		Walker walker(context, principal, statement_text, plan);
		walker.Operator(plan, "");
		walker.CheckSession();
		if (!principal.superuser && !principal.system && statement_text) {
			// Item 32 B: every call name the statement reaches, as written, which the plan may not show:
			// DuckDB folded calls on constants away while binding. Without a text the caller refuses the
			// statement (node_hooks, Enforce).
			JudgeCallNames(context, plan, *statement_text);
		}
		canonical = walker.Canonical(duckdb::LogicalOperatorToString(plan.type));
	} catch (duckdb::PermissionException &) {
		throw;
	} catch (std::invalid_argument &) {
		// CanonicalJson refuses a string that is not UTF-8 (Task 1). DuckDB validates query text and
		// identifiers, so only a value read from elsewhere could carry one.
		throw duckdb::PermissionException(
		    "QoD: statement uses text that is not valid UTF-8, which QoD does not support");
	} catch (std::exception &) {
		// A lookup or a cast that failed on a plan the walk did not expect. Its message may quote catalog
		// names, and its type could invalidate the client's transaction (any but a binder, catalog,
		// parser, permission... error does): a refusal, plain.
		throw duckdb::PermissionException("QoD: statement could not be described");
	}
	return Described {canonical, Hex(Sha256(canonical))};
}

void JudgeFailedStatement(duckdb::ClientContext &context, duckdb::SQLStatement &statement) {
	try {
		CallNames(context).Statement(statement);
	} catch (duckdb::PermissionException &) {
		throw;
	} catch (std::exception &) {
		// The walk cannot resolve what the binder could not bind either (a missing catalog): the binder's
		// own error stands, as it quotes no call's value.
	}
}

void ExpectSessionPath(duckdb::ClientContext &context, const std::string &path) {
	context.registered_state->GetOrCreate<SessionPaths>(SessionPaths::kKey)->expected = path;
}

std::string RedactedUrl(const std::string &text) {
	auto scheme_end = text.find("://");
	if (scheme_end == std::string::npos || scheme_end == 0 ||
	    !std::all_of(text.begin(), text.begin() + static_cast<std::ptrdiff_t>(scheme_end), IsSchemeCharacter) ||
	    duckdb::StringUtil::Lower(text.substr(0, scheme_end)) == "s3") {
		return text;
	}
	auto start = scheme_end + 3;
	auto rest = text.substr(start);
	rest = rest.substr(0, rest.find_first_of("?#"));
	auto authority_end = rest.find('/');
	auto authority = rest.substr(0, authority_end);
	auto at = authority.rfind('@');
	if (at != std::string::npos) {
		rest = rest.substr(at + 1);
	}
	return text.substr(0, start) + rest;
}

std::string DescribedLocation(const std::string &given, bool refuse_unresolved) {
	// The parse screen's rendering (Task 8), so a location reads the same in both descriptions.
	std::string why;
	auto location = MakeLocation(given, "read", "", why);
	if (location) {
		return location->resolved;
	}
	if (!refuse_unresolved) {
		return RedactedUrl(given);
	}
	throw duckdb::PermissionException("QoD: " + given + " is outside the tenant's authorized locations");
}

std::string CopyFromLiteralPath(duckdb::ClientContext &context, const std::string &statement_text) {
	duckdb::Parser parser(context);
	try {
		parser.ParseQuery(statement_text);
	} catch (std::exception &) {
		return "";
	}
	if (parser.statements.size() != 1) {
		return "";
	}
	auto statement = Unwrapped(parser.statements[0].get());
	if (!statement || statement->type != duckdb::StatementType::COPY_STATEMENT) {
		return "";
	}
	auto &info = *statement->Cast<duckdb::CopyStatement>().info;
	return info.is_from ? LiteralPath(info) : "";
}

} // namespace qod
