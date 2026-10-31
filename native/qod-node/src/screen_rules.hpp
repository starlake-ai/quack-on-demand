#pragma once

#include "locations.hpp"

#include "duckdb/parser/qualified_name.hpp"

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace duckdb {
class ClientContext;
class FunctionExpression;
class SQLStatement;
}

namespace qod {

//! What the parse screen found in a statement it admits: the locations binding it would open.
struct ScreenFacts {
	std::vector<Location> locations;
};

//! The content rules' outcome for one PREPARE: `refusal` empty to admit (with the facts), else the
//! whole refusal message ("QoD: ...").
struct ScreenOutcome {
	std::string refusal;
	ScreenFacts facts;
};

//! The call-shaped tokens of one text, the screen's backstop to its walk: per judged (reader, query()),
//! refused or superuser-class name, how many times an identifier (quoted or not) or a keyword spelled so
//! stands right before '(' (`calls`), comments skipped; and how many of those stand, past any `<name> .`
//! qualifiers, right after INTO, TABLE, EXISTS, ON or REFERENCES (`after_target`): where an INSERT target,
//! a CREATE TABLE name, a CREATE INDEX table or a referenced table is written with its column list.
struct CallShapes {
	std::map<std::string, uint64_t> calls;
	std::map<std::string, uint64_t> after_target;
};

//! The CallShapes of `sql` (normalized as the screen normalizes it).
CallShapes CallShapesOf(const std::string &sql);

//! Whether a session may not SET or RESET the setting `name` (lower case): every s3_* and
//! external_file_cache_* setting and the others decision 23 reserves.
bool IsReservedSessionSetting(const std::string &name);

//! Whether `name` (lower case) is one of the read-only listing PRAGMAs a non-superuser may run.
bool IsReadOnlyPragma(const std::string &name);

//! Whether `name` is a plain extension name (letters, digits, underscores): no path, no URL.
bool IsPlainExtensionName(const std::string &name);

//! A name the screen's walk reaches in a statement, qualified as written: a call (a scalar or aggregate
//! function or macro, a table function in table position, a window function), a relation it reads by
//! name (a table or view in FROM or JOIN, the table of COPY ... TO, query_table's names), or a table it
//! writes (the target of an INSERT or COPY ... FROM, an UPDATE, a DELETE, a MERGE). A name an UPDATE's
//! SET reaches carries the UPDATE's table as its scope: DuckDB binds the SET of the UPDATE that DuckDB 2.0
//! rewrites ALTER TABLE ... ADD COLUMN ... DEFAULT into on that table's own search path
//! (UpdateQueryNode::prioritize_table_when_binding, bind_update.cpp:31-36), and that UPDATE runs as a
//! query of its own whose text is its ToString, which no longer says so.
struct ReachedName {
	enum class Kind { CALL, TABLE_CALL, WINDOW_CALL, RELATION, INSERT_TARGET, UPDATE_TARGET, DELETE_TARGET,
		              MERGE_TARGET };
	Kind kind;
	duckdb::QualifiedName name;
	//! Whether `scope` is set: a table on whose search path DuckDB may bind this name too.
	bool scoped = false;
	duckdb::QualifiedName scope;
	//! Whether the name comes from a string rather than from the statement's own text: a query() literal
	//! (at any depth) or a query_table() name, which the gateway's metadata filter never rewrites.
	bool literal = false;
};

//! Every name the screen's walk reaches in `statement` and the query() literals it holds, for the
//! describer's call-name walk (CONTRACT Addendum 4 item 32). It collects, it does not judge: where the
//! screen would refuse the statement, the names reached up to that point (in a session the screen
//! itself refused or admitted the same text before binding).
std::vector<ReachedName> ReachedNames(duckdb::ClientContext &context, duckdb::SQLStatement &statement);

//! query(sql)'s literal SQL, as the screen reads it (one positional string literal), or nullopt.
std::optional<std::string> QueryLiteral(const duckdb::FunctionExpression &call);

//! query_table(names[, flag])'s literal table names, as the screen reads them (a string literal or a
//! list of them, an optional boolean literal), or nullopt.
std::optional<std::vector<std::string>> QueryTableLiterals(const duckdb::FunctionExpression &call);

//! How deep query('...') may nest query('...').
constexpr int kMaxQueryNesting = 8;

//! The parse screen's content rules (spec Section 3, decisions 15, 16, 18) over `sql`, for a principal
//! that is neither superuser nor system: parsed with DuckDB's parser under `context`'s parser settings
//! and extensions, every statement of a multi-statement request and behind EXPLAIN and PREPARE.
//! Refuses ATTACH, EXPORT DATABASE, CONNECT, DISCONNECT, external resources, PRAGMAs off the read-only
//! list (IMPORT DATABASE included), DESCRIBE / SHOW / SUMMARIZE of a table or query, LOAD and INSTALL of
//! anything but a plain extension name, CREATE MACRO, CREATE and DROP SECRET, SET and RESET of the
//! settings decision 23 reserves, json_execute_serialized_sql and the external
//! resource functions, a table function in table position that is neither a judged reader or query
//! function nor in kAdmittedTableFunctions, computed reader or COPY file names, a COPY option naming a
//! path, a computed query() string, a DuckLake change feed call (ducklake_table_changes, _insertions,
//! _deletions) that is not DuckDB's own or whose catalog, schema and table are not string literals,
//! unknown statement forms;
//! a call to a function function-classes.tsv classes superuser, anywhere (function_classes.hpp), and in
//! the slots DuckDB evaluates while binding (SET values, table-function arguments, EXECUTE parameters,
//! AT clauses, LIMIT and OFFSET, PIVOT IN lists, COLUMNS(), type expressions, unnest's parameters,
//! ALTER TABLE ADD COLUMN, ALTER COLUMN TYPE USING, COPY options, PRAGMA arguments, table and schema
//! options; a CREATE VIEW body is walked like a SELECT, its own slots included) any function not classed
//! allowed and nextval, currval and setval (CONTRACT Addendum 4 items 9, 13 and 15). Column defaults,
//! generated columns, CHECK constraints, SET DEFAULT, index expressions and trigger bodies are walked
//! too;
//! screens a literal query() string like the outer SQL; collects every reader and COPY location
//! (MakeLocation refusals are refusals here). A call-shaped token of a reader, query() or superuser-class
//! name that the walk saw neither as a call nor as a name before a parenthesis (a table with its column
//! list, a CTE or an alias with column names) is refused, so a statement form the walk does not follow
//! fails closed. Never throws.
ScreenOutcome ScreenStatement(duckdb::ClientContext &context, const std::string &sql);

} // namespace qod
