#pragma once

#include "json.hpp"

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace qod {

//! Builders for the values qod-node writes (descriptions, listings, decide requests).
JsonValue JsonString(std::string value);
JsonValue JsonInt(int64_t value);
JsonValue JsonBool(bool value);
JsonValue JsonNull();
JsonValue JsonArray(std::vector<JsonValue> items);
JsonValue JsonObject(std::map<std::string, JsonValue> members);

//! `value` as canonical JSON, the form both sides of the authorization wire share: no insignificant
//! whitespace, object keys in byte order (JsonValue::object's order), strings escaped per RFC 8259
//! with \" \\ \b \f \n \r \t and every other control character as \u00xx in lowercase hex, everything
//! else literal UTF-8, integers only, arrays in the order given. Throws std::invalid_argument on a
//! string (value or key) that is not valid UTF-8, so no two outputs differ only in bytes a reader
//! would decode alike.
std::string CanonicalJson(const JsonValue &value);

} // namespace qod
