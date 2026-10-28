#pragma once

#include "locations.hpp"

#include <string>
#include <vector>

namespace duckdb {
class ClientContext;
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
//! path, a computed query() string, unknown statement forms;
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
