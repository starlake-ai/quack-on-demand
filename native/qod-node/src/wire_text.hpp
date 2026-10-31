#pragma once

#include <string>

namespace qod {

//! The text checks and quoting the node's signed formats and its two manager clients share: one place,
//! so the decide and credential clients quote a replica's answers alike.

//! True when `text` is not empty and holds only 0-9 and a-f.
bool IsLowerHex(const std::string &text);

//! `text` without leading and trailing blanks (space, tab, CR, LF).
std::string TrimBlanks(const std::string &text);

//! A response body as a refusal may quote it: printable ASCII (anything else becomes a space), at most
//! 200 characters then "...", trimmed of spaces.
std::string QuotedBody(const std::string &body);

} // namespace qod
