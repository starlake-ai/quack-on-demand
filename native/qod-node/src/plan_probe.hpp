#pragma once

#include "duckdb/main/config.hpp"

namespace qod {

//! Spike instrumentation. Registers a post_bind and a pre_optimize hook that print, per statement,
//! which kind of connection it runs on and what the bound plan resolves to (tables and columns
//! read or written, table and scalar functions, secure-view boundaries), plus a per-connection
//! state that reports prepare finalization. It decides nothing.
void RegisterProbes(duckdb::DBConfig &config);

} // namespace qod
