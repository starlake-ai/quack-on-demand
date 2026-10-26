#pragma once

#include "duckdb.hpp"

#include <ostream>

namespace qod {

//! Writes every function the connection's instance resolves in the system catalog, macros left out
//! (DuckDB expands a macro while binding, so what its body calls is judged instead), as a JSON array
//! of {"aliasOf","name","schema","type"} elements (SortedJsonLines; aliasOf is null unless the row is
//! an alias). The input the function classification is generated from (native/qod-node/
//! functions.json). Throws std::runtime_error when DuckDB cannot list them.
void ListFunctions(duckdb::Connection &con, std::ostream &out);

} // namespace qod
