#pragma once

#include "duckdb.hpp"

#include <string>
#include <utility>
#include <vector>

namespace qod {

//! The lines the spawn scripts write around a tenant admin's initSql (spec Section 8, Task 20). The
//! begin line may carry a label after one space (`database`, `pool`), naming the section in the log.
//! The scripts drop every line of the tenant's text that holds `qod:untrusted-`, in any case, before
//! they write them, so a tenant's text can neither close its own section nor open one.
constexpr const char *kUntrustedBegin = "-- qod:untrusted-begin";
constexpr const char *kUntrustedEnd = "-- qod:untrusted-end";

//! One stretch of an init script: SQL a superuser or the manager wrote (trusted, run as written), or a
//! tenant admin's (untrusted, run under the tenant initSql allowlist).
struct InitSection {
	bool trusted;
	//! The begin line's label, empty for a trusted section or a begin line without one.
	std::string label;
	std::string sql;
};

//! Splits `sql` on its marker lines (a line equal to a marker once spaces, tabs and a trailing CR are
//! trimmed), before any tokenizing, so nothing inside one section (an unterminated string or comment
//! included) can reach into the next. Fails closed: a section opened and never closed runs to the end
//! untrusted, a begin line inside an untrusted section keeps it untrusted, and an end line outside one
//! is dropped. Sections holding nothing but blanks are dropped.
std::vector<InitSection> SplitInitSections(const std::string &sql);

//! The tenant initSql allowlist's verdict on one statement text: empty to run it, else why not (never
//! naming any part of the text but a setting's name). `parsed` gets the statement to run, the one
//! judged, or stays null when the text holds no statement (a comment). Runs only:
//! - SET / RESET, at the default or GLOBAL scope, of a setting in kInitSettings that the parse screen
//!   does not reserve, to a single literal value;
//! - a PRAGMA of the parse screen's read-only list, with literal arguments only;
//! - INSTALL / FORCE INSTALL / LOAD of a bare extension name that DuckDB's aliases resolve into
//!   kInitExtensions, with no FROM, no VERSION, no path and no URL.
//! Everything else is refused, as is a text that does not parse or holds more than one statement.
std::string JudgeInitStatement(duckdb::ClientContext &context, const std::string &statement,
                               duckdb::unique_ptr<duckdb::SQLStatement> &parsed);

//! What the allowlist makes of a whole untrusted text: how many statements it holds (SplitStatements)
//! and the 1-based position and reason of each one refused.
struct InitVerdict {
	size_t statements = 0;
	std::vector<std::pair<size_t, std::string>> refused;
};

//! The verdict on every statement of `sql`, judged on `context` without running any.
InitVerdict CheckInit(duckdb::ClientContext &context, const std::string &sql);

//! `verdict` as canonical JSON: {"refused":[{"reason":..,"statement":n}],"statements":n}. What
//! `qod-node --check-init` prints, and the manager's write-time check reads.
std::string InitVerdictJson(const InitVerdict &verdict);

//! Runs the untrusted `sql` on `con` statement by statement under the allowlist: a refused statement
//! is skipped and reported on stderr with its position and the reason, a failing one with DuckDB's
//! error (ErrorWithoutStatement), and the rest still runs. Returns how many were refused or failed.
size_t RunUntrusted(duckdb::Connection &con, const std::string &sql, const std::string &label);

//! Runs an init script: its trusted sections as RunBestEffort does, its untrusted ones through
//! RunUntrusted. Returns how many statements were refused or failed.
size_t RunInit(duckdb::Connection &con, const std::string &sql);

} // namespace qod
