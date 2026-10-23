#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace qod {

//! A parsed JSON value: what handles and verdicts carry. Numbers are integers only (no fraction, no
//! exponent): nothing qod-node reads needs more, and a strict reader is easier to trust.
struct JsonValue {
	enum class Kind { NUL, BOOLEAN, INTEGER, STRING, ARRAY, OBJECT };

	Kind kind = Kind::NUL;
	bool boolean = false;
	int64_t integer = 0;
	std::string string;
	std::vector<JsonValue> array;
	std::map<std::string, JsonValue> object;

	//! The member `key` of an object; throws std::invalid_argument naming it when this is not an
	//! object, the member is absent, or it is not of `kind`.
	const JsonValue &Get(const std::string &key, Kind kind) const;
	//! Like Get, for a member that may be null.
	const JsonValue *GetOptional(const std::string &key, Kind kind) const;
};

//! Parses `text` as exactly one JSON value (whitespace around it allowed). Throws
//! std::invalid_argument on anything else: malformed text, a fraction or exponent, a duplicate
//! object key, nesting deeper than 32 levels, or trailing characters.
JsonValue ParseJson(const std::string &text);

} // namespace qod
