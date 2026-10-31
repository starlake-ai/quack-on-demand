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

//! The statement that starts the node's quack listener on bind:port with token, reading back only
//! listen_url so the token never reaches any output. Plain HTTP (disable_ssl): DuckDB 2.0 serves HTTPS
//! by default off localhost, and the manager speaks plain HTTP to its nodes. allow_other_hostname: the
//! manager reaches a node by an address other than the one it binds.
std::string ServeStatement(const std::string &bind, int port, const std::string &token);

} // namespace qod
