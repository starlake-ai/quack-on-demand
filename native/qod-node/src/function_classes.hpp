#pragma once

#include <optional>
#include <string>

namespace qod {

//! What a non-superuser may do with a function (spec Section 6), as function-classes.tsv classes it.
enum class FunctionClass { Allowed, Listing, File, Superuser };

//! The class of the built-in or extension function `name` (lower case) of type `type` ("scalar",
//! "aggregate", "window", "table" or "pragma"), from function_classes.inc (generated from
//! src/main/resources/authz/function-classes.tsv by scripts/dev/gen-qod-node-function-classes.py;
//! every classified function lives in the system catalog's main schema); nullopt when nobody
//! classified it (a macro, a function loaded at runtime).
std::optional<FunctionClass> FunctionClassOf(const std::string &name, const std::string &type);

//! Whether a classification of `name` (lower case), of any type, is superuser: no name the table
//! lists is classed superuser under one type and something else under another.
bool IsSuperuserFunction(const std::string &name);

} // namespace qod
