#pragma once

#include "duckdb.hpp"

#include <functional>
#include <string>

namespace qod {

//! Runs every statement of sql on con, one at a time. A failing statement is reported on stderr with
//! its position and DuckDB's error (ErrorWithoutStatement), and the next one runs: the node has always
//! carried on past an unreachable federated source or an offline INSTALL. Returns the number of
//! statements that failed. `before`, when set, is called with each statement's text before it runs;
//! what it throws stops the run.
size_t RunBestEffort(duckdb::Connection &con, const std::string &sql, const std::string &label,
                     const std::function<void(const std::string &)> &before = nullptr);

//! Whether `statement` is a SET of a setting that restricts what the node may read or reach
//! (disabled_filesystems, enable_external_access, lock_configuration, allowed_directories,
//! allowed_paths), as a superuser's initSql may set: whatever needs the local file system
//! (loading quack) must run before it. False for anything that does not parse.
bool RestrictsNodeAccess(duckdb::ClientContext &context, const std::string &statement);

//! Runs one statement and throws std::runtime_error with label and DuckDB's error
//! (ErrorWithoutStatement) if it fails.
duckdb::unique_ptr<duckdb::QueryResult> RunStrict(duckdb::Connection &con, const std::string &statement,
                                                              const std::string &label);

//! A failed result's error as DuckDB words it, without the excerpt of the statement (`LINE 1: ...`)
//! DuckDB appends to a parser or binder error: init SQL and the serve statement carry credentials, and
//! the node's output reaches the manager's log.
std::string ErrorWithoutStatement(const duckdb::QueryResult &result);

//! The whole content of path (a file), or of stdin when path is "-" (how the spawn scripts hand over
//! the init SQL: a pipe, never on disk), one leading UTF-8 byte order mark dropped. Throws if it cannot
//! be read.
std::string ReadAll(const std::string &path);

} // namespace qod
