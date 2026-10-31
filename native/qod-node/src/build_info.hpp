#pragma once

namespace qod {

//! The build marker, "qod-node-build-info:protocol=<n>;duckdb=<version>;duckdb_source=<source id>":
//! what this binary was built against. The literal is embedded verbatim, so scripts can check a
//! binary without running it.
const char *BuildInfo();

//! Shared builds: throws std::runtime_error if the libduckdb the process loaded is not the one this
//! binary was built against (a mismatch links cleanly and fails at the first call through a changed
//! internal API). Static builds carry their DuckDB and never mismatch.
void CheckLinkedDuckDB();

} // namespace qod
