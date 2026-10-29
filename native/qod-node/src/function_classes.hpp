#pragma once

#include <optional>
#include <string>

namespace qod {

//! What a non-superuser may do with a function (spec Section 6), as function-classes.tsv classes it.
enum class FunctionClass { Allowed, Listing, File, Superuser };

//! The class of the built-in or extension function `name` (lower case) of type `type` ("scalar",
//! "aggregate", "window", "table", "pragma", or "macro" / "table_macro" for a built-in macro, which
//! its body classes), from function_classes.inc (generated from
//! src/main/resources/authz/function-classes.tsv by scripts/dev/gen-qod-node-function-classes.py;
//! every classified function lives in the system catalog, its main schema but for the pg_catalog
//! macros); nullopt when nobody classified it (a macro whose body calls a function not classed allowed
//! or reads a table, a function loaded at runtime).
std::optional<FunctionClass> FunctionClassOf(const std::string &name, const std::string &type);

//! Whether a classification of `name` (lower case), of any type, is superuser: no name the table
//! lists is classed superuser under one type and something else under another.
bool IsSuperuserFunction(const std::string &name);

//! Whether function-classes.tsv classifies `name` (lower case) under any type, or it names a built-in
//! macro (builtin_macros.inc, from macros.json: one the table does not class is judged by its
//! expansion). A name neither lists (a function an extension loaded at runtime, a macro a superuser
//! created) is refused to users wherever they call it (CONTRACT Addendum 4 item 30): DuckDB may fold
//! such a call away before the plan reaches the describer.
bool IsClassifiedFunction(const std::string &name);

} // namespace qod
