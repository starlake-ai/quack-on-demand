#pragma once

#include "duckdb/main/config.hpp"

#include <ostream>

namespace qod {

//! Writes every setting the instance knows, core and extension, and every core alias, as a JSON array
//! (SortedJsonLines) of:
//!   {"kind":"core","name":..,"scope":<scope target>,"setters":<g/l>}
//!   {"kind":"extension","name":..,"scope":<default scope>,"setters":<g/l: supports global/session>}
//!   {"kind":"alias","name":<alias>,"target":<setting>}
//! A core setting without a scope target ("none": custom setters) has exactly one setter, which gives
//! its scope. The input the setting classification is generated from (native/qod-node/settings.json).
void ListSettings(duckdb::DBConfig &config, std::ostream &out);

} // namespace qod
