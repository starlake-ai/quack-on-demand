#include "screen_rules.hpp"

#include "duckdb/common/error_data.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/parser/constraints/list.hpp"
#include "duckdb/parser/expression/list.hpp"
#include "duckdb/parser/parsed_data/alter_schema_info.hpp"
#include "duckdb/parser/parsed_data/alter_table_info.hpp"
#include "duckdb/parser/parsed_data/copy_info.hpp"
#include "duckdb/parser/parsed_data/create_index_info.hpp"
#include "duckdb/parser/parsed_data/create_schema_info.hpp"
#include "duckdb/parser/parsed_data/create_table_info.hpp"
#include "duckdb/parser/parsed_data/create_trigger_info.hpp"
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
#include "function_classes.hpp"

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

//! COPY options whose value DuckDB v2.0.0 never reads as a location, so the screen admits any value,
//! computed or not (its calls are still walked): the column and ordering lists bound against the
//! COPY's columns (partition_by, force_quote, force_not_null, force_null, order_by, and the json
//! writer's geometry_column / id_column); partition_path, an expression over the partition columns
//! that DuckDB itself refuses when it evaluates to an absolute or remote path or holds '..'
//! (PartitionFileRequestBuilder::BuildDirectory); booleans; numbers and sizes; text the writer or
//! reader formats with ('%d/%m/%Y', a delimiter, a codec name, a prefix written into the file); and
//! file metadata (parquet's kv_metadata, field_ids, shredding, encryption_config, which names keys
//! added by PRAGMA add_parquet_key). The names come from bind_copy.cpp (GetFullCopyOptionsList,
//! BindCopyTo), copy_csv.cpp (CSVListCopyOptions), csv_reader_options.cpp (SetReadOption,
//! SetBaseOption), copy_json.cpp (CopyToJSONPlanInternal) and parquet_extension.cpp
//! (ParquetListCopyOptions). Every other option (filename_pattern, file_extension, rejects_table,
//! rejects_scan, format, the multi-file and JSON reader options of COPY FROM, any option an extension
//! adds) must be literal, and each text it holds must not look like a path (CopyOptionText). Sorted.
const char *const kCopyNonPathOptions[] = {"allow_quoted_nulls",
                                           "append",
                                           "array",
                                           "auto_detect",
                                           "batch_size",
                                           "batch_size_bytes",
                                           "batches_per_file",
                                           "bbox",
                                           "binary_as_string",
                                           "bloom_filter_false_positive_ratio",
                                           "buffer_size",
                                           "can_have_nan",
                                           "chunk_size",
                                           "codec",
                                           "comment",
                                           "compression",
                                           "compression_level",
                                           "data_page_size_limit",
                                           "date_format",
                                           "dateformat",
                                           "debug_use_openssl",
                                           "decimal_separator",
                                           "delim",
                                           "delimiter",
                                           "dictionary_compression_ratio_threshold",
                                           "dictionary_size_limit",
                                           "encoding",
                                           "encryption_config",
                                           "escape",
                                           "field_ids",
                                           "file_row_number",
                                           "file_size_bytes",
                                           "force_not_null",
                                           "force_null",
                                           "force_quote",
                                           "geometry_column",
                                           "geoparquet_version",
                                           "header",
                                           "hive_file_pattern",
                                           "id_column",
                                           "ignore_errors",
                                           "int96_as",
                                           "kv_metadata",
                                           "max_line_size",
                                           "maximum_line_size",
                                           "new_line",
                                           "null",
                                           "null_padding",
                                           "nullstr",
                                           "order_by",
                                           "overwrite",
                                           "overwrite_or_ignore",
                                           "parallel",
                                           "parquet_version",
                                           "partition_by",
                                           "partition_path",
                                           "per_thread_output",
                                           "prefix",
                                           "preserve_order",
                                           "quote",
                                           "rejects_limit",
                                           "rejects_line_size_limit",
                                           "return_files",
                                           "return_stats",
                                           "row_group_size",
                                           "row_group_size_bytes",
                                           "row_groups_per_file",
                                           "sample_size",
                                           "sep",
                                           "separator",
                                           "shredding",
                                           "skip",
                                           "store_rejects",
                                           "strict_mode",
                                           "string_dictionary_page_size_limit",
                                           "suffix",
                                           "thousands",
                                           "timestamp_format",
                                           "timestamp_is_adjusted_to_utc",
                                           "timestampformat",
                                           "use_tmp_file",
                                           "write_bloom_filter",
                                           "write_empty_file",
                                           "write_partition_columns",
                                           "write_timestamp_as_int96"};

//! The COPY functions kCopyNonPathOptions was read from (copy_csv.cpp, copy_blob.cpp,
//! parquet_extension.cpp, json_extension.cpp at v2.0.0). Under any other format (an extension's, or
//! a file extension no core function claims) every option must be literal and hold no path.
const char *const kCoreCopyFormats[] = {"blob",    "csv",   "geojson", "geojsonl",
                                        "json",    "jsonl", "ndjson",  "parquet"};

//! The COPY options that spell the names of the files written (bind_copy.cpp, CreateFilename): one
//! string, under every format.
const char *const kCopyFileNameOptions[] = {"file_extension", "filename_pattern"};

//! Settings a session may not change (decision 23), besides every s3_* and external_file_cache_*
//! one: what makes httpfs sign with the node's environment or the AWS chain, refresh credentials,
//! add headers to a signed request or log it unredacted; how the server's certificate is verified;
//! which HTTP client sends a request, the proxies it passes and the connections it reuses; the
//! project a GCS request is billed to; the consistency check between reads of one file; the secret
//! defaults; and the caches that would hand one user what another read. From httpfs's settings at
//! DuckDB 2.0.0 (duckdb_settings() with httpfs loaded, and DuckDbSettingScopes, generated from the
//! httpfs sources, for the ones registered only later, such as the curl client's) and the core
//! secret and cache settings.
const char *const kReservedSettings[] = {"allow_persistent_secrets",
                                         "allow_unredacted_secrets",
                                         "aws_default_credentials",
                                         "ca_cert_file",
                                         "cache_local_files",
                                         "default_secret_storage",
                                         "enable_curl_server_cert_verification",
                                         "enable_external_file_cache",
                                         "enable_global_s3_configuration",
                                         "enable_http_metadata_cache",
                                         "enable_object_cache",
                                         "enable_server_cert_verification",
                                         "extra_http_headers",
                                         "gcs_user_project",
                                         "http_keep_alive",
                                         "http_proxy",
                                         "http_proxy_password",
                                         "http_proxy_username",
                                         "httpfs_client_implementation",
                                         "httpfs_connection_caching",
                                         "httpfs_enable_credential_refresh",
                                         "merge_http_secret_into_s3_request",
                                         "parquet_metadata_cache",
                                         "redact_http_logs",
                                         "secret_directory",
                                         "unsafe_disable_etag_checks",
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

//! How many times `sql`'s tokens call each judged, refused or superuser-class name: an identifier
//! (quoted or not) or a keyword right before '(', comments skipped.
std::map<std::string, idx_t> CallsInTokens(const std::string &sql) {
	auto tokens = Parser::Tokenize(sql);
	std::map<std::string, idx_t> calls;
	for (idx_t i = 0; i < tokens.size(); i++) {
		if (tokens[i].type != SimplifiedTokenType::SIMPLIFIED_TOKEN_IDENTIFIER &&
		    tokens[i].type != SimplifiedTokenType::SIMPLIFIED_TOKEN_KEYWORD) {
			continue;
		}
		auto name = CallName(TokenText(sql, tokens, i));
		if (!IsJudgedCall(name) && !IsRefusedCall(name) && !IsSuperuserFunction(name)) {
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
		named_.emplace_back();
		for (auto &statement : parser.statements) {
			Statement(*statement);
		}
		// A call-shaped token the walk did not see as a call (which it judged or refused) nor as a name
		// before a parenthesis (a table with its column list, a CTE or an alias with its column names) is a
		// call where the walk does not go: refused (Addendum 4 item 15).
		for (auto &call : calls) {
			auto seen = judged_.back()[call.first] + named_.back()[call.first];
			if (IsSuperuserFunction(call.first) && seen < call.second) {
				Refuse(call.first + " is reserved to superusers");
			}
			if (IsJudgedCall(call.first) && seen < call.second) {
				Refuse(call.first + " is called where QoD cannot check its arguments");
			}
		}
		named_.pop_back();
		judged_.pop_back();
		nesting_ = outer;
	}

	std::vector<Location> locations;

private:
	//! Marks the walk as inside a slot DuckDB evaluates while binding (CONTRACT Addendum 4 item 9), for
	//! its scope: only functions classed allowed may be called there, and no sequence function. The
	//! outermost slot names the refusal.
	class InSlot {
	public:
		InSlot(Screener &screener, const char *slot) : screener_(screener), outer_(screener.slot_) {
			if (outer_.empty()) {
				screener_.slot_ = slot;
			}
		}
		~InSlot() {
			screener_.slot_ = outer_;
		}

	private:
		Screener &screener_;
		std::string outer_;
	};

	//! A call in a bind-time slot: refused unless it is a built-in function classed allowed under one of
	//! `types`, and is no sequence function (nextval, currval and setval run, or read, a sequence while
	//! binding: what the plan, and so the manager, never sees). A name the screen cannot resolve to a
	//! class (a macro, a function of another catalog or schema, one loaded at runtime) is not allowed.
	void SlotCall(const QualifiedName &qualified, const std::string &name, std::initializer_list<const char *> types) {
		if (slot_.empty()) {
			return;
		}
		auto why = " is not allowed in " + slot_ + ": DuckDB evaluates it while binding, before QoD judges the plan";
		if (name == "nextval" || name == "currval" || name == "setval") {
			Refuse(name + why);
		}
		auto system = (qualified.Catalog().empty() || qualified.Catalog() == "system") &&
		              (qualified.Schema().empty() || qualified.Schema() == "main");
		auto allowed = false;
		for (auto type : types) {
			auto cls = FunctionClassOf(name, type);
			allowed = allowed || (cls && *cls == FunctionClass::Allowed);
		}
		if (!system || !allowed) {
			Refuse(name + why);
		}
	}

	//! A function name the walk reaches, wherever it is: a superuser-class one is refused (rule (i) of
	//! Addendum 4 item 9), whatever catalog or schema qualifies it.
	static void SuperuserCall(const std::string &name) {
		if (IsSuperuserFunction(name)) {
			Refuse(name + " is reserved to superusers");
		}
	}

	//! A type expression (a CAST target, a column's type): its parameters are folded while binding
	//! (Binder::BindLogicalType, bind_type_expression.cpp:76-104).
	void TypeSlot(const LogicalType &type) {
		if (!type.IsUnbound()) {
			return;
		}
		auto &expression = UnboundType::GetTypeExpression(type);
		if (expression) {
			InSlot slot(*this, "a type expression");
			Expression(*expression);
		}
	}

	//! A column an ALTER TABLE adds: its type, and its default and generated expressions, which DuckDB
	//! binds and runs for the existing rows when the ALTER executes, in no plan the manager sees.
	void AddedColumn(const ColumnDefinition &column, const char *slot) {
		TypeSlot(column.Type());
		InSlot in(*this, slot);
		if (column.HasDefaultValue()) {
			Expression(column.DefaultValue());
		}
		if (column.Generated()) {
			Expression(column.GeneratedExpression());
		}
	}

	static bool IsAddColumn(const AlterInfo &info) {
		return info.type == AlterType::ALTER_TABLE &&
		       info.Cast<AlterTableInfo>().alter_table_type == AlterTableType::ADD_COLUMN;
	}

	//! ALTER TABLE: the expressions an ALTER runs at execution (ADD COLUMN ... DEFAULT, ALTER COLUMN ...
	//! TYPE ... USING), as bind-time slots. The others (SET DEFAULT, constraints) run in later plans; a
	//! judged call in one fails the token count, and a superuser-class one is refused by it.
	void Alter(AlterInfo &info) {
		if (info.type == AlterType::ALTER_SCHEMA) {
			auto &schema = info.Cast<AlterSchemaInfo>();
			if (schema.alter_schema_type == AlterSchemaType::SET_SCHEMA_OPTIONS) {
				Options(schema.Cast<SetSchemaOptionsInfo>().options);
			}
			return;
		}
		if (info.type != AlterType::ALTER_TABLE) {
			return;
		}
		auto &table = info.Cast<AlterTableInfo>();
		switch (table.alter_table_type) {
		case AlterTableType::ADD_COLUMN:
			AddedColumn(table.Cast<AddColumnInfo>().new_column, "ALTER TABLE ADD COLUMN");
			break;
		case AlterTableType::ADD_FIELD:
			AddedColumn(table.Cast<AddFieldInfo>().new_field, "ALTER TABLE ADD COLUMN");
			break;
		case AlterTableType::ALTER_COLUMN_TYPE: {
			auto &change = table.Cast<ChangeColumnTypeInfo>();
			TypeSlot(change.target_type);
			InSlot slot(*this, "ALTER COLUMN TYPE USING");
			Walk(change.expression);
			break;
		}
		case AlterTableType::SET_DEFAULT:
			// Stored, and run in later INSERT plans: walked like any expression (Addendum 4 item 15).
			Walk(table.Cast<SetDefaultInfo>().expression);
			break;
		case AlterTableType::ADD_CONSTRAINT:
			Constraint(*table.Cast<AddConstraintInfo>().constraint);
			break;
		case AlterTableType::SET_PARTITIONED_BY:
			for (auto &key : table.Cast<SetPartitionedByInfo>().partition_keys) {
				Walk(key);
			}
			break;
		case AlterTableType::SET_SORTED_BY:
			for (auto &order : table.Cast<SetSortedByInfo>().orders) {
				Walk(order.expression);
			}
			break;
		case AlterTableType::SET_TABLE_OPTIONS:
			Options(table.Cast<SetTableOptionsInfo>().table_options);
			break;
		default:
			break;
		}
	}

	//! WITH (...) / SET (...) options of a table or schema: no plan holds them, the catalog (DuckLake,
	//! Iceberg) takes their values when the CREATE or ALTER binds or runs, so they are a bind-time slot.
	void Options(const case_insensitive_map_t<unique_ptr<ParsedExpression>> &options) {
		InSlot slot(*this, "table or schema options");
		for (auto &option : options) {
			Walk(option.second);
		}
	}

	//! A table constraint: a CHECK's expression is walked; a foreign key names its referenced table before
	//! a parenthesis (REFERENCES t (a)).
	void Constraint(const duckdb::Constraint &constraint) {
		switch (constraint.type) {
		case ConstraintType::CHECK:
			Walk(constraint.Cast<CheckConstraint>().expression);
			break;
		case ConstraintType::FOREIGN_KEY:
			Named(constraint.Cast<ForeignKeyConstraint>().info.table);
			break;
		default:
			break;
		}
	}

	//! The slot the walk is in, empty outside every one.
	std::string slot_;
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
		case StatementType::EXECUTE_STATEMENT: {
			// bind_execute.cpp:63: every parameter is evaluated while binding, EXPLAIN EXECUTE included.
			InSlot slot(*this, "EXECUTE parameters");
			for (auto &value : statement.Cast<ExecuteStatement>().named_values) {
				Walk(value.second);
			}
			break;
		}
		case StatementType::MULTI_STATEMENT: {
			// Only the parser writes one (a request's statements arrive one by one). DuckDB 2.0 rewrites ALTER
			// TABLE ... ADD COLUMN ... DEFAULT <expression> into one: the column added with DEFAULT NULL, an
			// UPDATE that sets it to the expression for the existing rows, then SET DEFAULT
			// (transform_alter.cpp:32-90, 236-266). The whole rewrite is the ADD COLUMN's slot.
			auto &multi = statement.Cast<MultiStatement>();
			auto adds_column = !multi.statements.empty() &&
			                   multi.statements[0]->type == StatementType::ALTER_STATEMENT &&
			                   IsAddColumn(*multi.statements[0]->Cast<AlterStatement>().info);
			InSlot slot(*this, adds_column ? "ALTER TABLE ADD COLUMN" : "");
			for (auto &child : multi.statements) {
				Statement(*child);
			}
			break;
		}
		case StatementType::SET_STATEMENT: {
			auto &set = statement.Cast<SetStatement>();
			auto name = StringUtil::Lower(set.name.GetIdentifierName());
			if (set.scope != SetScope::VARIABLE && IsReservedSetting(name)) {
				Refuse("SET and RESET of " + name + " are reserved to superusers");
			}
			if (set.set_type == SetType::SET) {
				// bind_set.cpp:20-27: a setting's value is evaluated while binding; a variable's is a query.
				InSlot slot(*this, set.scope == SetScope::VARIABLE ? "" : "a SET value");
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
			Alter(*statement.Cast<AlterStatement>().info);
			break;
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
			// INSERT INTO t (a) / INSERT INTO t (SELECT ...): the target before a parenthesis.
			Named(insert.qualified_name.Name());
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
		for (auto &modifier : node.modifiers) {
			switch (modifier->type) {
			case ResultModifierType::LIMIT_MODIFIER: {
				// bind_select_node.cpp:60-78: a foldable LIMIT or OFFSET is evaluated while binding.
				auto &limit = modifier->Cast<LimitModifier>();
				InSlot slot(*this, "LIMIT and OFFSET");
				Walk(limit.limit);
				Walk(limit.offset);
				break;
			}
			case ResultModifierType::LEGACY_LIMIT_PERCENT_MODIFIER: {
				auto &limit = modifier->Cast<LegacyLimitPercentModifier>();
				InSlot slot(*this, "LIMIT and OFFSET");
				Walk(limit.limit);
				Walk(limit.offset);
				break;
			}
			case ResultModifierType::ORDER_MODIFIER:
				for (auto &order : modifier->Cast<OrderModifier>().orders) {
					Walk(order.expression);
				}
				break;
			case ResultModifierType::DISTINCT_MODIFIER:
				for (auto &target : modifier->Cast<DistinctModifier>().distinct_on_targets) {
					Walk(target);
				}
				break;
			default:
				Refuse("statement uses result modifier type " + std::to_string(static_cast<int>(modifier->type)) +
				       ", which QoD does not support");
			}
		}
		for (auto &entry : node.cte_map.map) {
			if (!entry.second->aliases.empty()) {
				Named(entry.first);
			}
			if (entry.second->query_node) {
				Node(*entry.second->query_node);
			}
		}
	}

	void Ref(TableRef &ref) {
		Deeper deeper(depth_);
		if (!ref.alias.empty() && !ref.column_name_alias.empty()) {
			Named(ref.alias);
		}
		switch (ref.type) {
		case TableReferenceType::BASE_TABLE: {
			auto &base = ref.Cast<BaseTableRef>();
			if (base.at_clause) {
				// bind_basetableref.cpp:111-117 (BindAtClause).
				InSlot slot(*this, "an AT clause");
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
					// bind_pivot.cpp:542-608 (BindPivotInList): folded while binding.
					InSlot slot(*this, "a PIVOT IN list");
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
		switch (expression.GetExpressionClass()) {
		case ExpressionClass::FUNCTION: {
			auto &call = expression.Cast<FunctionExpression>();
			Call(call, false);
			auto name = StringUtil::Lower(call.FunctionName().GetIdentifierName());
			if (name == "unnest" || name == "unlist") {
				// bind_unnest_expression.cpp:155-170: every parameter after the list is folded while binding.
				auto first = true;
				for (auto &argument : call.GetArguments()) {
					if (first) {
						first = false;
						Expression(argument.GetExpression());
					} else {
						InSlot slot(*this, "unnest's parameters");
						Expression(argument.GetExpression());
					}
				}
				if (call.Filter()) {
					Expression(*call.Filter());
				}
				if (call.OrderBy()) {
					for (auto &order : call.OrderBy()->orders) {
						Expression(*order.expression);
					}
				}
				return;
			}
			break;
		}
		case ExpressionClass::WINDOW: {
			auto &window = expression.Cast<WindowExpression>();
			auto name = StringUtil::Lower(window.GetQualifiedName().Name().GetIdentifierName());
			SuperuserCall(name);
			SlotCall(window.GetQualifiedName(), name, {"window", "aggregate"});
			break;
		}
		case ExpressionClass::SUBQUERY: {
			auto &subquery = expression.Cast<SubqueryExpression>().Subquery();
			if (subquery) {
				Node(*subquery->node);
			}
			break;
		}
		case ExpressionClass::CAST: {
			// The target type is no child of the cast's (util_parsed_expression.cpp:62-66): its parameters.
			InSlot slot(*this, "a type expression");
			Expression(expression.Cast<CastExpression>().TargetType());
			break;
		}
		case ExpressionClass::STAR: {
			// bind_star_expression.cpp:440-453: COLUMNS(<expression>) is folded while binding.
			auto &star = expression.Cast<StarExpression>();
			for (auto &item : star.ReplaceList()) {
				Expression(*item.second);
			}
			if (star.Expression()) {
				InSlot slot(*this, "COLUMNS()");
				Expression(*star.Expression());
			}
			return;
		}
		default:
			break;
		}
		ParsedExpressionIterator::EnumerateChildren(expression,
		                                            [&](const ParsedExpression &child) { Expression(child); });
	}

	//! A function call: a scalar or aggregate one, or (`table_position`) the table function of a FROM,
	//! JOIN or CALL, whose own name TableFunction already checked.
	void Call(const FunctionExpression &call, bool table_position) {
		auto name = StringUtil::Lower(call.FunctionName().GetIdentifierName());
		if (IsRefusedCall(name)) {
			Refuse(name + " is reserved to superusers: it runs SQL no screen sees");
		}
		SuperuserCall(name);
		if (table_position) {
			SlotCall(call.GetQualifiedName(), name, {"table"});
		} else {
			SlotCall(call.GetQualifiedName(), name, {"scalar", "aggregate"});
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
		auto &call = function->Cast<FunctionExpression>();
		auto name = StringUtil::Lower(call.FunctionName().GetIdentifierName());
		if (!IsJudgedCall(name) && !IsRefusedCall(name) && !Contains(kAdmittedTableFunctions, name)) {
			Refuse("table function " + name + " is not available to non-superusers");
		}
		Deeper deeper(depth_);
		Call(call, true);
		// bind_table_function.cpp:151 and function_binder.cpp:503-508 (FoldArgument): the arguments are
		// folded while binding, before any plan exists.
		InSlot slot(*this, "a table function's arguments");
		ParsedExpressionIterator::EnumerateChildren(call, [&](const ParsedExpression &child) { Expression(child); });
	}

	//! A text a COPY option holds that could name a path of its own (FILENAME_PATTERN '../x_{i}').
	static void CopyOptionText(const std::string &name, const std::string &text) {
		auto path = text.find('/') != std::string::npos || text.find('\\') != std::string::npos ||
		            text.find("..") != std::string::npos;
#ifdef _WIN32
		// A drive (C:x) or an alternate stream.
		path = path || text.find(':') != std::string::npos;
#endif
		if (path) {
			Refuse("COPY option " + name + " names a path");
		}
	}

	//! The texts of a COPY option value the binder evaluates without computing anything, into `texts`:
	//! no value (a flag), a constant, an unqualified name (the binder takes it as its own text, so
	//! FILENAME_PATTERN "../x" is the text ../x), or a list, struct or row of those. False for anything
	//! computed: a call (concat, ||), a cast ('x'::VARCHAR), a parameter, a qualified name.
	static bool LiteralOption(const ParsedExpression *expression, std::vector<std::string> &texts, idx_t depth = 0) {
		if (!expression) {
			return true;
		}
		if (depth > kMaxDepth) {
			Refuse("the statement nests too deeply to be screened");
		}
		switch (expression->GetExpressionClass()) {
		case ExpressionClass::CONSTANT: {
			// As spelled and as the binder takes it: a hex or bit literal's text is its digits, its value
			// the bytes they spell (X'2F2E2E' is /..).
			auto &literal = expression->Cast<ConstantExpression>().GetLiteral();
			texts.push_back(literal.text);
			try {
				auto value = literal.ToValue();
				if (!value.IsNull()) {
					texts.push_back(value.ToString());
				}
			} catch (std::exception &) {
				// A literal the screen cannot decode is no literal it can check.
				return false;
			}
			return true;
		}
		case ExpressionClass::COLUMN_REF: {
			auto &name = expression->Cast<ColumnRefExpression>();
			if (name.IsQualified()) {
				return false;
			}
			texts.push_back(name.GetColumnName().GetIdentifierName());
			return true;
		}
		case ExpressionClass::FUNCTION: {
			auto &call = expression->Cast<FunctionExpression>();
			auto &qualified = call.GetQualifiedName();
			auto function = StringUtil::Lower(call.FunctionName().GetIdentifierName());
			auto system = (qualified.Catalog().empty() || qualified.Catalog() == "system") &&
			              (qualified.Schema().empty() || qualified.Schema() == "main");
			if (!system || (function != "list_value" && function != "struct_pack" && function != "row")) {
				return false;
			}
			for (auto &argument : call.GetArguments()) {
				if (argument.HasName()) {
					texts.push_back(argument.GetName().GetIdentifierName());
				}
				if (!LiteralOption(&argument.GetExpression(), texts, depth + 1)) {
					return false;
				}
			}
			return true;
		}
		default:
			return false;
		}
	}

	//! The COPY format DuckDB will look up (Binder::BindCopyFormat at v2.0.0): FORMAT as given, else the
	//! file name's extension past a .gz or .zst suffix (ExtractFormat), lower case. A file name with no
	//! extension is csv, as DuckDB finds no function and no extension for an empty format and falls back
	//! to csv. A FORMAT value that is computed stays in parsed_options and is refused there.
	static std::string CopyFormat(const CopyInfo &info) {
		if (!info.format.empty()) {
			return StringUtil::Lower(info.format);
		}
		auto format = StringUtil::Lower(info.file_path);
		if (StringUtil::EndsWith(format, ".gz")) {
			format = format.substr(0, format.size() - 3);
		} else if (StringUtil::EndsWith(format, ".zst")) {
			format = format.substr(0, format.size() - 4);
		}
		auto dot = format.rfind('.');
		return dot == std::string::npos || dot + 1 == format.size() ? "csv" : format.substr(dot + 1);
	}

	//! COPY options, both directions (C1): an option DuckDB never reads as a location may hold any
	//! value (kCopyNonPathOptions), for the core formats only (M5: an extension's COPY function may read
	//! any name as a path); any other one must be literal, and no text it holds may look like a path.
	//! Its value is computed at bind (BindCopyOption evaluates it), so a computed one could spell any
	//! path: FILENAME_PATTERN concat('..', '/x_{i}') writes beside the checked target. FILENAME_PATTERN
	//! and FILE_EXTENSION, which spell the files written, take one string and nothing else (I2).
	void CopyOptions(CopyInfo &info) {
		auto core = Contains(kCoreCopyFormats, CopyFormat(info));
		auto free_value = [core](const std::string &name) {
			return core && Contains(kCopyNonPathOptions, name);
		};
		for (auto &option : info.parsed_options) {
			{
				// bind_copy.cpp:729-750 (BindCopyOption): every option is evaluated while binding.
				InSlot slot(*this, "a COPY option");
				Walk(option.second);
			}
			auto name = StringUtil::Lower(option.first.GetIdentifierName());
			if (Contains(kCopyFileNameOptions, name)) {
				// The parser keeps a single string (or name) in CopyInfo.options; anything here is a list,
				// a struct, a row, a cast or a call.
				Refuse("COPY option " + name + " needs one literal string");
			}
			if (free_value(name)) {
				continue;
			}
			std::vector<std::string> texts;
			if (!LiteralOption(option.second.get(), texts)) {
				Refuse("COPY option " + name + " is computed, so QoD cannot check that it names no path");
			}
			for (auto &text : texts) {
				CopyOptionText(name, text);
			}
		}
		for (auto &option : info.options) {
			auto name = StringUtil::Lower(option.first.GetIdentifierName());
			if (Contains(kCopyFileNameOptions, name) &&
			    (option.second.size() != 1 || option.second[0].type().id() != LogicalTypeId::VARCHAR)) {
				// A BLOB or BIT literal (X'2F2E2E'), several values, or none.
				Refuse("COPY option " + name + " needs one literal string");
			}
			if (free_value(name)) {
				continue;
			}
			for (auto &value : option.second) {
				if (!value.IsNull()) {
					CopyOptionText(name, value.ToString());
				}
			}
		}
	}

	void Copy(CopyInfo &info) {
		if (info.select_statement) {
			Node(*info.select_statement);
		}
		CopyOptions(info);
		if (!info.select_list.empty()) {
			Named(info.GetQualifiedName().Name());
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
			Named(table.GetQualifiedName().Name());
			if (table.query) {
				Node(*table.query->node);
			}
			// The column types are bound at creation (Binder::BindLogicalType, bind_create.cpp:615-636).
			// The defaults, generated columns, constraints and keys run in later plans: walked like any
			// expression (Addendum 4 item 15), so a superuser-class call in one is refused here.
			for (auto &column : table.columns.Logical()) {
				TypeSlot(column.Type());
				if (column.HasDefaultValue()) {
					Expression(column.DefaultValue());
				}
				if (column.Generated()) {
					Expression(column.GeneratedExpression());
				}
			}
			for (auto &constraint : table.constraints) {
				Constraint(*constraint);
			}
			for (auto &key : table.partition_keys) {
				Walk(key);
			}
			for (auto &key : table.sort_keys) {
				Walk(key);
			}
			Options(table.options);
			break;
		}
		case CatalogType::SCHEMA_ENTRY: {
			Options(info.Cast<CreateSchemaInfo>().options);
			break;
		}
		case CatalogType::VIEW_ENTRY: {
			auto &view = info.Cast<CreateViewInfo>();
			if (!view.aliases.empty()) {
				Named(view.GetQualifiedName().Name());
			}
			if (view.query) {
				// Bound at creation, which evaluates its bind-time slots: walked like a SELECT, so those slots
				// get the slot rule and its readers the location checks (CONTRACT Addendum 4 item 13).
				Node(*view.query->node);
			}
			break;
		}
		case CatalogType::INDEX_ENTRY: {
			auto &index = info.Cast<CreateIndexInfo>();
			Named(index.table);
			for (auto &expression : index.parsed_expressions) {
				Walk(expression);
			}
			for (auto &expression : index.expressions) {
				Walk(expression);
			}
			break;
		}
		case CatalogType::TRIGGER_ENTRY: {
			// The trigger body runs in the plans of the statements that fire it.
			auto &trigger = info.Cast<CreateTriggerInfo>();
			if (trigger.base_table) {
				Ref(*trigger.base_table);
			}
			if (trigger.trigger_action) {
				Node(*trigger.trigger_action);
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
		// Binder::BindPragma folds every argument through PlaceArguments (bind_pragma.cpp,
		// function_binder.cpp:668-673), with no foldability check (Addendum 4 item 15).
		InSlot slot(*this, "PRAGMA arguments");
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
	//! The names of each Text level the walk saw right before a parenthesis that opens no call: a table
	//! with its column list (INSERT INTO t (a), CREATE TABLE t (a INT), CREATE INDEX ... ON t (a),
	//! REFERENCES t (a), COPY t (a)), a CTE or a table alias with its column names, a view with its column
	//! names. The token count does not take them for calls.
	std::vector<std::map<std::string, idx_t>> named_;

	void Named(const Identifier &name) {
		named_.back()[StringUtil::Lower(name.GetIdentifierName())]++;
	}
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
