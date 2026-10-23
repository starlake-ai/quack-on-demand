#pragma once

#include "duckdb.hpp"

#include <string>

namespace qod {

//! Runs every statement of sql on con, one at a time. A failing statement is reported on stderr with
//! its position and DuckDB's error, never its text (init SQL carries credentials), and the next one
//! runs: the node has always carried on past an unreachable federated source or an offline INSTALL.
//! Returns the number of statements that failed.
size_t RunBestEffort(duckdb::Connection &con, const std::string &sql, const std::string &label);

//! Runs one statement and throws std::runtime_error with label and DuckDB's error if it fails.
duckdb::unique_ptr<duckdb::QueryResult> RunStrict(duckdb::Connection &con, const std::string &statement,
                                                              const std::string &label);

//! The whole content of path (a FIFO from the spawn scripts, or a file). Throws if it cannot be read.
std::string ReadAll(const std::string &path);

} // namespace qod
