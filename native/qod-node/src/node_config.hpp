#pragma once

#include "duckdb/main/config.hpp"
#include "options.hpp"

namespace qod {

//! Writes allow_community_extensions, allow_unsigned_extensions and autoinstall_known_extensions
//! into `config` from `policy`, all three always (DuckDB's own defaults turn community extensions and
//! autoinstall on). Call it before the database opens: only then does DuckDB accept turning an
//! allow_* setting on.
void ApplyExtensionPolicy(duckdb::DBConfig &config, const ExtensionPolicy &policy);

} // namespace qod
