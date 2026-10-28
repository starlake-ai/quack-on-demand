#include "screen_rules.hpp"

#include "duckdb/common/error_data.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/parser/expression/list.hpp"
#include "duckdb/parser/parsed_data/copy_info.hpp"
#include "duckdb/parser/parsed_data/create_table_info.hpp"
#include "duckdb/parser/parsed_data/create_type_info.hpp"
#include "duckdb/parser/parsed_data/create_view_info.hpp"
#include "duckdb/parser/parsed_data/drop_info.hpp"
#include "duckdb/parser/parsed_data/load_info.hpp"
#include "duckdb/parser/parsed_data/pragma_info.hpp"
#include "duckdb/parser/parsed_expression_iterator.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/query_node/list.hpp"
#include "duckdb/parser/statement/list.hpp"
#include "duckdb/parser/statement/multi_statement.hpp"
#include "duckdb/parser/tableref/list.hpp"
#include "file_functions.hpp"

#include <map>
#include <stdexcept>
#include <vector>

namespace qod {
namespace {

using namespace duckdb;

//! A refusal of the screen; what() is the whole message.
class ScreenRefusal : public std::runtime_error {
public:
	explicit ScreenRefusal(const std::string &reason) : std::runtime_error("QoD: " + reason) {
	}
};

[[noreturn]] void Refuse(const std::string &reason) {
	throw ScreenRefusal(reason);
}

//! Functions that run SQL on a fresh internal connection, which no screen sees (Section 3).
const char *const kRefusedCalls[] = {"json_execute_serialized_sql", "register_external_resource_type",
                                     "create_external_resource"};

//! The PRAGMAs a non-superuser may run: read-only listings (Section 3).
const char *const kReadOnlyPragmas[] = {"table_info",     "show_tables", "show_tables_expanded", "show_databases",
                                        "database_list", "functions",   "version"};

//! COPY options that hold text, never a path, whatever it spells ('%d/%m/%Y').
const char *const kCopyTextOptions[] = {"dateformat", "date_format", "timestampformat", "timestamp_format",
                                        "delim",      "delimiter",   "sep",             "quote",
                                        "escape",     "null",        "nullstr",         "new_line",
                                        "comment"};

//! Settings a session may not change (decision 23), besides every s3_* and external_file_cache_*
//! one: what makes httpfs sign with the node's environment or the AWS chain, the proxies a signed
//! request passes, the secret defaults, and the caches that would hand one user what another read.
const char *const kReservedSettings[] = {"aws_default_credentials",
                                         "cache_local_files",
                                         "default_secret_storage",
                                         "enable_external_file_cache",
                                         "enable_global_s3_configuration",
                                         "enable_http_metadata_cache",
                                         "enable_object_cache",
                                         "http_proxy",
                                         "http_proxy_password",
                                         "http_proxy_username",
                                         "merge_http_secret_into_s3_request",
                                         "parquet_metadata_cache",
                                         "validate_external_file_cache"};

//! How deep the walk may nest (nodes, references and expressions together).
constexpr idx_t kMaxDepth = 1000;

template <class LIST>
bool Contains(const LIST &list, const std::string &name) {
	for (auto &entry : list) {
		if (name == entry) {
			return true;
		}
	}
	return false;
}

bool IsRefusedCall(const std::string &name) {
	return Contains(kRefusedCalls, name);
}

//! Whether a session may not SET or RESET `name` (lower case): decision 23.
bool IsReservedSetting(const std::string &name) {
	return StringUtil::StartsWith(name, "s3_") || StringUtil::StartsWith(name, "external_file_cache_") ||
	       Contains(kReservedSettings, name);
}

//! The calls the screen judges by their arguments.
bool IsJudgedCall(const std::string &name) {
	return name == "query" || name == "query_table" || Contains(kFileFunctions, name);
}

std::string TokenText(const std::string &sql, const vector<SimplifiedToken> &tokens, idx_t i) {
	auto end = i + 1 < tokens.size() ? tokens[i + 1].start : sql.size();
	auto text = sql.substr(tokens[i].start, end - tokens[i].start);
	auto last = text.find_last_not_of(" \t\r\n");
	return last == std::string::npos ? "" : text.substr(0, last + 1);
}

//! A name token as the catalog compares it: unquoted, lower case.
std::string CallName(const std::string &text) {
	if (text.size() < 2 || text.front() != '"' || text.back() != '"') {
		return StringUtil::Lower(text);
	}
	std::string unquoted;
	for (idx_t i = 1; i + 1 < text.size(); i++) {
		unquoted += text[i];
		if (text[i] == '"' && text[i + 1] == '"') {
			i++;
		}
	}
	return StringUtil::Lower(unquoted);
}

//! How many times `sql`'s tokens call each judged or refused name: an identifier (quoted or not) or a
//! keyword right before '(', comments skipped.
std::map<std::string, idx_t> CallsInTokens(const std::string &sql) {
	auto tokens = Parser::Tokenize(sql);
	std::map<std::string, idx_t> calls;
	for (idx_t i = 0; i < tokens.size(); i++) {
		if (tokens[i].type != SimplifiedTokenType::SIMPLIFIED_TOKEN_IDENTIFIER &&
		    tokens[i].type != SimplifiedTokenType::SIMPLIFIED_TOKEN_KEYWORD) {
			continue;
		}
		auto name = CallName(TokenText(sql, tokens, i));
		if (!IsJudgedCall(name) && !IsRefusedCall(name)) {
			continue;
		}
		auto next = i + 1;
		while (next < tokens.size() && tokens[next].type == SimplifiedTokenType::SIMPLIFIED_TOKEN_COMMENT) {
			next++;
		}
		if (next < tokens.size() && tokens[next].type == SimplifiedTokenType::SIMPLIFIED_TOKEN_OPERATOR &&
		    TokenText(sql, tokens, next).compare(0, 1, "(") == 0) {
			calls[name]++;
		}
	}
	return calls;
}

bool IsStringLiteral(const ParsedExpression &expression) {
	return expression.GetExpressionClass() == ExpressionClass::CONSTANT &&
	       expression.Cast<ConstantExpression>().GetLiteral().kind == LiteralKind::STRING;
}

bool IsBooleanLiteral(const ParsedExpression &expression) {
	return expression.GetExpressionClass() == ExpressionClass::CONSTANT &&
	       expression.Cast<ConstantExpression>().GetLiteral().kind == LiteralKind::BOOLEAN;
}

//! `[a, b]` / list_value(a, b), as the parser writes a list (unqualified or system.main).
bool IsListValue(const ParsedExpression &expression) {
	if (expression.GetExpressionClass() != ExpressionClass::FUNCTION) {
		return false;
	}
	auto &list = expression.Cast<FunctionExpression>();
	auto &name = list.GetQualifiedName();
	return list.FunctionName() == "list_value" && (name.Catalog().empty() || name.Catalog() == "system") &&
	       (name.Schema().empty() || name.Schema() == "main");
}

//! A non-empty list of string literals.
bool IsStringList(const ParsedExpression &expression) {
	if (!IsListValue(expression)) {
		return false;
	}
	auto &arguments = expression.Cast<FunctionExpression>().GetArguments();
	if (arguments.empty()) {
		return false;
	}
	for (auto &argument : arguments) {
		if (argument.HasName() || !IsStringLiteral(argument.GetExpression())) {
			return false;
		}
	}
	return true;
}

//! The arguments DuckDB binds as positional (bind_table_function.cpp:110-125): a `name := v` or
//! `name => v` argument, an aliased one and an unqualified `name = v` comparison are named parameters.
std::vector<const ParsedExpression *> Positional(const FunctionExpression &call) {
	std::vector<const ParsedExpression *> result;
	for (auto &argument : call.GetArguments()) {
		auto &expression = argument.GetExpression();
		if (argument.HasName() || !expression.GetAlias().empty()) {
			continue;
		}
		if (expression.GetExpressionType() == ExpressionType::COMPARE_EQUAL) {
			auto &left = expression.Cast<ComparisonExpression>().Left();
			if (left.GetExpressionType() == ExpressionType::COLUMN_REF &&
			    !left.Cast<ColumnRefExpression>().IsQualified()) {
				continue;
			}
		}
		result.push_back(&expression);
	}
	return result;
}

bool IsExtensionName(const std::string &name) {
	if (name.empty()) {
		return false;
	}
	for (auto c : name) {
		if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
			return false;
		}
	}
	return true;
}

class Screener {
public:
	explicit Screener(ClientContext &context) : context_(context) {
	}

	//! Screens every statement of `sql`: the outer SQL (nesting 0) or a query() literal.
	void Text(const std::string &sql, int nesting) {
		if (nesting > kMaxQueryNesting) {
			Refuse("query() nests deeper than " + std::to_string(kMaxQueryNesting) + " levels");
		}
		auto normalized = Parser::NormalizeSQLString(sql);
		auto calls = CallsInTokens(normalized);
		for (auto &call : calls) {
			if (IsRefusedCall(call.first)) {
				Refuse(call.first + " is reserved to superusers: it runs SQL no screen sees");
			}
		}
		Parser parser(context_);
		try {
			parser.ParseQuery(normalized);
		} catch (std::exception &error) {
			Refuse("the statement does not parse: " + ErrorData(error).RawMessage());
		}
		auto outer = nesting_;
		nesting_ = nesting;
		judged_.emplace_back();
		for (auto &statement : parser.statements) {
			Statement(*statement);
		}
		for (auto &call : calls) {
			if (judged_.back()[call.first] < call.second) {
				Refuse(call.first + " is called where QoD cannot check its arguments");
			}
		}
		judged_.pop_back();
		nesting_ = outer;
	}

	std::vector<Location> locations;

private:
	//! Counts one level of the walk's nesting, refusing past kMaxDepth.
	class Deeper {
	public:
		explicit Deeper(idx_t &depth) : depth_(depth) {
			if (++depth_ > kMaxDepth) {
				depth_--;
				Refuse("the statement nests too deeply to be screened");
			}
		}
		~Deeper() {
			depth_--;
		}

	private:
		idx_t &depth_;
	};

	void Walk(const unique_ptr<ParsedExpression> &expression) {
		if (expression) {
			Expression(*expression);
		}
	}

	void Statement(SQLStatement &statement) {
		Deeper deeper(depth_);
		switch (statement.type) {
		case StatementType::SELECT_STATEMENT:
			Node(*statement.Cast<SelectStatement>().node);
			break;
		case StatementType::INSERT_STATEMENT:
			Node(*statement.Cast<InsertStatement>().node);
			break;
		case StatementType::UPDATE_STATEMENT:
			Node(*statement.Cast<UpdateStatement>().node);
			break;
		case StatementType::DELETE_STATEMENT:
			Node(*statement.Cast<DeleteStatement>().node);
			break;
		case StatementType::MERGE_INTO_STATEMENT:
			Node(*statement.Cast<MergeIntoStatement>().node);
			break;
		case StatementType::COPY_STATEMENT:
			Copy(*statement.Cast<CopyStatement>().info);
			break;
		case StatementType::CREATE_STATEMENT:
			Create(*statement.Cast<CreateStatement>().info);
			break;
		case StatementType::CALL_STATEMENT:
			TableFunction(statement.Cast<CallStatement>().function);
			break;
		case StatementType::PRAGMA_STATEMENT:
			Pragma(*statement.Cast<PragmaStatement>().info);
			break;
		case StatementType::EXPLAIN_STATEMENT:
			Statement(*statement.Cast<ExplainStatement>().stmt);
			break;
		case StatementType::PREPARE_STATEMENT:
			Statement(*statement.Cast<PrepareStatement>().statement);
			break;
		case StatementType::EXECUTE_STATEMENT:
			for (auto &value : statement.Cast<ExecuteStatement>().named_values) {
				Walk(value.second);
			}
			break;
		case StatementType::MULTI_STATEMENT:
			for (auto &child : statement.Cast<MultiStatement>().statements) {
				Statement(*child);
			}
			break;
		case StatementType::SET_STATEMENT: {
			auto &set = statement.Cast<SetStatement>();
			auto name = StringUtil::Lower(set.name.GetIdentifierName());
			if (set.scope != SetScope::VARIABLE && IsReservedSetting(name)) {
				Refuse("SET and RESET of " + name + " are reserved to superusers");
			}
			if (set.set_type == SetType::SET) {
				Walk(statement.Cast<SetVariableStatement>().value);
			}
			break;
		}
		case StatementType::LOAD_STATEMENT:
			Load(*statement.Cast<LoadStatement>().info);
			break;
		case StatementType::ATTACH_STATEMENT:
			Refuse("ATTACH is reserved to superusers");
		case StatementType::EXPORT_STATEMENT:
			Refuse("EXPORT DATABASE is reserved to superusers");
		case StatementType::CONNECT_STATEMENT:
		case StatementType::DISCONNECT_STATEMENT:
			Refuse("CONNECT and DISCONNECT are reserved to superusers");
		case StatementType::EXTERNAL_RESOURCE_STATEMENT:
			Refuse("external resources are reserved to superusers: they run SQL no screen sees");
		case StatementType::DROP_STATEMENT:
			if (statement.Cast<DropStatement>().info->type == CatalogType::SECRET_ENTRY) {
				Refuse("secrets are reserved to superusers");
			}
			break;
		case StatementType::ALTER_STATEMENT:
		case StatementType::TRANSACTION_STATEMENT:
		case StatementType::VACUUM_STATEMENT:
		case StatementType::ANALYZE_STATEMENT:
		case StatementType::DETACH_STATEMENT:
		case StatementType::COPY_DATABASE_STATEMENT:
		case StatementType::UPDATE_EXTENSIONS_STATEMENT:
			// Nothing they bind opens a file; a judged call hidden in one fails the token count.
			break;
		default:
			Refuse("statement uses " + StatementTypeToString(statement.type) + ", which QoD does not support");
		}
	}

	void Node(QueryNode &node) {
		Deeper deeper(depth_);
		switch (node.type) {
		case QueryNodeType::SELECT_NODE: {
			auto &select = node.Cast<SelectNode>();
			for (auto &expression : select.select_list) {
				Walk(expression);
			}
			for (auto &expression : select.groups.group_expressions) {
				Walk(expression);
			}
			Walk(select.where_clause);
			Walk(select.having);
			Walk(select.qualify);
			if (select.from_table) {
				Ref(*select.from_table);
			}
			break;
		}
		case QueryNodeType::SET_OPERATION_NODE:
			for (auto &child : node.Cast<SetOperationNode>().children) {
				Node(*child);
			}
			break;
		case QueryNodeType::RECURSIVE_CTE_NODE: {
			auto &cte = node.Cast<RecursiveCTENode>();
			Node(*cte.left);
			Node(*cte.right);
			for (auto &expression : cte.key_targets) {
				Walk(expression);
			}
			break;
		}
		case QueryNodeType::INSERT_QUERY_NODE: {
			auto &insert = node.Cast<InsertQueryNode>();
			if (insert.select_statement) {
				Node(*insert.select_statement->node);
			}
			if (insert.table_ref) {
				Ref(*insert.table_ref);
			}
			for (auto &expression : insert.returning_list) {
				Walk(expression);
			}
			if (insert.on_conflict_info) {
				auto &conflict = *insert.on_conflict_info;
				if (conflict.set_info) {
					for (auto &expression : conflict.set_info->expressions) {
						Walk(expression);
					}
					Walk(conflict.set_info->condition);
				}
				Walk(conflict.condition);
			}
			break;
		}
		case QueryNodeType::UPDATE_QUERY_NODE: {
			auto &update = node.Cast<UpdateQueryNode>();
			if (update.table) {
				Ref(*update.table);
			}
			if (update.from_table) {
				Ref(*update.from_table);
			}
			if (update.set_info) {
				for (auto &expression : update.set_info->expressions) {
					Walk(expression);
				}
				Walk(update.set_info->condition);
			}
			for (auto &expression : update.returning_list) {
				Walk(expression);
			}
			break;
		}
		case QueryNodeType::DELETE_QUERY_NODE: {
			auto &del = node.Cast<DeleteQueryNode>();
			if (del.table) {
				Ref(*del.table);
			}
			for (auto &clause : del.using_clauses) {
				Ref(*clause);
			}
			Walk(del.condition);
			for (auto &expression : del.returning_list) {
				Walk(expression);
			}
			break;
		}
		case QueryNodeType::MERGE_QUERY_NODE: {
			auto &merge = node.Cast<MergeQueryNode>();
			if (merge.target) {
				Ref(*merge.target);
			}
			if (merge.source) {
				Ref(*merge.source);
			}
			Walk(merge.join_condition);
			for (auto &entry : merge.actions) {
				for (auto &action : entry.second) {
					Walk(action->condition);
					if (action->update_info) {
						for (auto &expression : action->update_info->expressions) {
							Walk(expression);
						}
						Walk(action->update_info->condition);
					}
					for (auto &expression : action->expressions) {
						Walk(expression);
					}
				}
			}
			for (auto &expression : merge.returning_list) {
				Walk(expression);
			}
			break;
		}
		case QueryNodeType::COPY_QUERY_NODE:
			// COPY ... TO as a CTE body (transform_select.cpp:1113-1139).
			Copy(*node.Cast<CopyQueryNode>().info);
			break;
		default:
			Refuse("statement uses query node type " + std::to_string(static_cast<int>(node.type)) +
			       ", which QoD does not support");
		}
		ParsedExpressionIterator::EnumerateQueryNodeModifiers(
		    node, [&](unique_ptr<ParsedExpression> &child) { Walk(child); });
		for (auto &entry : node.cte_map.map) {
			if (entry.second->query_node) {
				Node(*entry.second->query_node);
			}
		}
	}

	void Ref(TableRef &ref) {
		Deeper deeper(depth_);
		switch (ref.type) {
		case TableReferenceType::BASE_TABLE: {
			auto &base = ref.Cast<BaseTableRef>();
			if (base.at_clause) {
				Walk(base.at_clause->ExpressionMutable());
			}
			break;
		}
		case TableReferenceType::SUBQUERY:
			Node(*ref.Cast<SubqueryRef>().subquery->node);
			break;
		case TableReferenceType::JOIN: {
			auto &join = ref.Cast<JoinRef>();
			Ref(*join.left);
			Ref(*join.right);
			Walk(join.condition);
			for (auto &expression : join.duplicate_eliminated_columns) {
				Walk(expression);
			}
			Walk(join.ranking_expression);
			break;
		}
		case TableReferenceType::TABLE_FUNCTION: {
			auto &function = ref.Cast<TableFunctionRef>();
			TableFunction(function.function);
			if (function.subquery) {
				Node(*function.subquery->node);
			}
			break;
		}
		case TableReferenceType::EXPRESSION_LIST:
			for (auto &row : ref.Cast<ExpressionListRef>().values) {
				for (auto &expression : row) {
					Walk(expression);
				}
			}
			break;
		case TableReferenceType::PIVOT: {
			auto &pivot = ref.Cast<PivotRef>();
			if (pivot.source) {
				Ref(*pivot.source);
			}
			for (auto &expression : pivot.aggregates) {
				Walk(expression);
			}
			for (auto &column : pivot.pivots) {
				for (auto &expression : column.pivot_expressions) {
					Walk(expression);
				}
				for (auto &entry : column.entries) {
					Walk(entry.expr);
				}
				if (column.subquery) {
					Node(*column.subquery);
				}
			}
			break;
		}
		case TableReferenceType::SHOW_REF: {
			// DESCRIBE / SUMMARIZE / SHOW <name> bind their target and discard it (bind_showref.cpp): no
			// plan would show what they read. The listing forms stay.
			auto type = ref.Cast<ShowRef>().show_type;
			if (type != ShowType::SHOW_SPECIAL && type != ShowType::SHOW_FROM) {
				Refuse("DESCRIBE, SHOW and SUMMARIZE of a table or query are reserved to superusers (duckdb_tables() "
				       "and duckdb_columns() list the same)");
			}
			break;
		}
		case TableReferenceType::EMPTY_FROM:
			break;
		default:
			Refuse("statement uses table reference type " + std::to_string(static_cast<int>(ref.type)) +
			       ", which QoD does not support");
		}
	}

	void Expression(const ParsedExpression &expression) {
		Deeper deeper(depth_);
		if (expression.GetExpressionClass() == ExpressionClass::FUNCTION) {
			Call(expression.Cast<FunctionExpression>());
		} else if (expression.GetExpressionClass() == ExpressionClass::SUBQUERY) {
			auto &subquery = expression.Cast<SubqueryExpression>().Subquery();
			if (subquery) {
				Node(*subquery->node);
			}
		}
		ParsedExpressionIterator::EnumerateChildren(expression,
		                                            [&](const ParsedExpression &child) { Expression(child); });
	}

	void Call(const FunctionExpression &call) {
		auto name = StringUtil::Lower(call.FunctionName().GetIdentifierName());
		if (IsRefusedCall(name)) {
			Refuse(name + " is reserved to superusers: it runs SQL no screen sees");
		}
		if (!IsJudgedCall(name)) {
			return;
		}
		judged_.back()[name]++;
		auto positional = Positional(call);
		if (name == "query") {
			if (positional.size() != 1 || !IsStringLiteral(*positional[0])) {
				Refuse("query() needs a literal SQL string");
			}
			Text(positional[0]->Cast<ConstantExpression>().GetLiteral().text, nesting_ + 1);
		} else if (name == "query_table") {
			// DuckDB turns each name into `FROM <name>`: a quoted string in table position when it holds a
			// path separator (a replacement scan, off for these sessions), else a name QualifiedName::TryParse
			// reads, which holds no call (query_function.cpp:37-61). Nothing more to screen.
			auto names = !positional.empty() && (IsStringLiteral(*positional[0]) || IsStringList(*positional[0]));
			auto flag = positional.size() == 1 || (positional.size() == 2 && IsBooleanLiteral(*positional[1]));
			if (!names || !flag) {
				Refuse("query_table() needs literal table names");
			}
		} else {
			// Every DuckDB file reader takes its file, list or pattern as its first positional argument.
			if (positional.empty()) {
				Refuse(name + " needs a literal file name");
			}
			auto &location = *positional[0];
			if (IsStringLiteral(location)) {
				Add(location.Cast<ConstantExpression>().GetLiteral().text, "read", name);
			} else if (IsStringList(location)) {
				for (auto &element : location.Cast<FunctionExpression>().GetArguments()) {
					Add(element.GetExpression().Cast<ConstantExpression>().GetLiteral().text, "read", name);
				}
			} else {
				Refuse(name + " needs a literal file name");
			}
		}
	}

	//! A table function in table position (FROM, JOIN, PIVOT source, CALL): DuckDB binds it before any plan
	//! exists, so only the judged readers and query functions and the classified Allowed / Listing table
	//! functions may bind (Task 2's kAdmittedTableFunctions); a superuser function, a table macro or an
	//! unclassified one is refused.
	void TableFunction(const unique_ptr<ParsedExpression> &function) {
		if (!function || function->GetExpressionClass() != ExpressionClass::FUNCTION) {
			Refuse("statement uses a table function the screen cannot read, which QoD does not support");
		}
		auto name = StringUtil::Lower(function->Cast<FunctionExpression>().FunctionName().GetIdentifierName());
		if (!IsJudgedCall(name) && !IsRefusedCall(name) && !Contains(kAdmittedTableFunctions, name)) {
			Refuse("table function " + name + " is not available to non-superusers");
		}
		Expression(*function);
	}

	//! A COPY option's string value that could name a path of its own (FILENAME_PATTERN '../x_{i}').
	static void CopyOption(const std::string &name, const std::string &text) {
		if (Contains(kCopyTextOptions, name)) {
			return;
		}
		if (text.find('/') != std::string::npos || text.find('\\') != std::string::npos ||
		    text.find("..") != std::string::npos) {
			Refuse("COPY option " + name + " names a path");
		}
	}

	void Copy(CopyInfo &info) {
		if (info.select_statement) {
			Node(*info.select_statement);
		}
		for (auto &option : info.parsed_options) {
			if (option.second && IsStringLiteral(*option.second)) {
				CopyOption(StringUtil::Lower(option.first.GetIdentifierName()),
				           option.second->Cast<ConstantExpression>().GetLiteral().text);
			}
			Walk(option.second);
		}
		for (auto &option : info.options) {
			for (auto &value : option.second) {
				if (!value.IsNull() && value.type().id() == LogicalTypeId::VARCHAR) {
					CopyOption(StringUtil::Lower(option.first.GetIdentifierName()), value.ToString());
				}
			}
		}
		if (info.file_path_expression) {
			Refuse("COPY needs a literal file name");
		}
		Add(info.file_path, info.is_from ? "read" : "write", info.is_from ? "copy_from" : "copy_to");
	}

	void Create(CreateInfo &info) {
		switch (info.type) {
		case CatalogType::TABLE_ENTRY: {
			auto &table = info.Cast<CreateTableInfo>();
			if (table.query) {
				Node(*table.query->node);
			}
			break;
		}
		case CatalogType::VIEW_ENTRY: {
			auto &view = info.Cast<CreateViewInfo>();
			if (view.query) {
				Node(*view.query->node);
			}
			break;
		}
		case CatalogType::TYPE_ENTRY: {
			auto &type = info.Cast<CreateTypeInfo>();
			if (type.query) {
				Statement(*type.query);
			}
			break;
		}
		case CatalogType::MACRO_ENTRY:
		case CatalogType::TABLE_MACRO_ENTRY:
			// Section 6: macros are superuser-only; their bodies are no place for the walk to miss a call.
			Refuse("CREATE MACRO is reserved to superusers");
		case CatalogType::SECRET_ENTRY:
			// Decision 23: a user's secret could scope inside another's, or shadow the connection's own.
			Refuse("secrets are reserved to superusers");
		default:
			// Indexes, sequences, schemas, triggers, tables with constraints: the walk admits no
			// file in them; a judged call in one fails the token count.
			break;
		}
	}

	void Pragma(PragmaInfo &info) {
		auto name = StringUtil::Lower(info.name.GetIdentifierName());
		if (!Contains(kReadOnlyPragmas, name)) {
			Refuse("PRAGMA " + name + " is reserved to superusers");
		}
		for (auto &parameter : info.parameters) {
			Walk(parameter);
		}
		for (auto &parameter : info.named_parameters) {
			Walk(parameter.second);
		}
	}

	void Load(LoadInfo &info) {
		auto plain = (info.load_type == LoadType::LOAD || info.load_type == LoadType::INSTALL ||
		              info.load_type == LoadType::FORCE_INSTALL) &&
		             info.repository.empty() && IsExtensionName(info.filename);
		if (!plain) {
			Refuse("LOAD or INSTALL of a file, a URL or a repository, and extension repositories, are reserved to "
			       "superusers");
		}
	}

	void Add(const std::string &given, const std::string &access, const std::string &via) {
		if (given.empty()) {
			Refuse(via + " names no file");
		}
		std::string why;
		auto location = MakeLocation(given, access, via, why);
		if (!location) {
			Refuse((given.find('\0') == std::string::npos ? given : via + "'s file name") + " " + why);
		}
		locations.push_back(*location);
	}

	ClientContext &context_;
	int nesting_ = 0;
	idx_t depth_ = 0;
	//! The judged calls of each Text level, innermost last.
	std::vector<std::map<std::string, idx_t>> judged_;
};

} // namespace

ScreenOutcome ScreenStatement(ClientContext &context, const std::string &sql) {
	ScreenOutcome outcome;
	try {
		Screener screener(context);
		screener.Text(sql, 0);
		outcome.facts.locations = std::move(screener.locations);
	} catch (ScreenRefusal &refusal) {
		outcome.refusal = refusal.what();
	} catch (std::exception &error) {
		outcome.refusal = "QoD: the statement cannot be screened: " + ErrorData(error).RawMessage();
	}
	return outcome;
}

} // namespace qod
