#include "describer.hpp"

#include "crypto.hpp"
#include "file_functions.hpp"
#include "json.hpp"
#include "json_writer.hpp"
#include "locations.hpp"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/index_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/schema_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/view_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry_retriever.hpp"
#include "duckdb/catalog/catalog_search_path.hpp"
#include "duckdb/common/enum_util.hpp"
#include "duckdb/common/enums/view_security_type.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/execution/operator/helper/physical_set.hpp"
#include "duckdb/function/lambda_functions.hpp"
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
#include "duckdb/parser/tableref/basetableref.hpp"
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
	auto catalogs = QualifiersIn(entry->Cast<ViewCatalogEntry>().GetQuery().ToString());
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
				if (!entry.internal && entry.type != CatalogType::TABLE_ENTRY &&
				    entry.type != CatalogType::SEQUENCE_ENTRY) {
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
	auto recorded = context_.registered_state->Get<SessionPaths>(SessionPaths::kKey);
	if (recorded ? CatalogSearchEntry::ListToString(paths) != CatalogSearchEntry::ListToString(recorded->set_paths)
	             : !SessionDefaultPaths(paths)) {
		Unsupported("a secure view while the session's search path differs from the one it was given");
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

void RecordSessionPaths(duckdb::ClientContext &context) {
	auto paths = context.registered_state->GetOrCreate<SessionPaths>(SessionPaths::kKey);
	paths->set_paths = duckdb::ClientData::Get(context).catalog_search_path->GetSetPaths();
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
