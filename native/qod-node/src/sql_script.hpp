#pragma once

#include <string>
#include <vector>

namespace qod {

//! Splits a SQL script into statements on the `;` separators DuckDB's own tokenizer reports, so a
//! separator inside a string, a quoted identifier or a comment never splits. Statements are trimmed;
//! empty ones are dropped. Each statement can then run (and fail) on its own, as the DuckDB CLI ran an
//! init script.
std::vector<std::string> SplitStatements(const std::string &sql);

//! A SQL string literal for value: single-quoted, embedded single quotes doubled.
std::string QuoteLiteral(const std::string &value);

} // namespace qod
