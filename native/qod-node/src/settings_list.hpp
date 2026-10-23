#pragma once

#include "duckdb/main/config.hpp"

#include <ostream>

namespace qod {

//! Writes every setting the instance knows, core and extension, one per line:
//!   core      <name> <scope target> <setters: g/l>
//!   extension <name> <default scope>
//! A core setting without a scope target (custom setters) has exactly one setter, which gives its
//! scope. The input the settings classification is generated from.
void ListSettings(duckdb::DBConfig &config, std::ostream &out);

} // namespace qod
