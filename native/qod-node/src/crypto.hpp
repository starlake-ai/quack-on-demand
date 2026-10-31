#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace qod {

using Digest = std::array<uint8_t, 32>;

//! SHA-256 (FIPS 180-4) of data.
Digest Sha256(const std::string &data);

//! HMAC-SHA256 (RFC 2104) of data under key.
Digest HmacSha256(const std::string &key, const std::string &data);

//! Lowercase hex of bytes.
std::string Hex(const uint8_t *bytes, size_t length);
std::string Hex(const Digest &digest);

//! The bytes a lowercase or uppercase hex string spells; throws std::invalid_argument on anything
//! else (an odd length, a non-hex character).
std::string FromHex(const std::string &hex);

//! True when a and b are equal, in time that does not depend on where they differ.
bool ConstantTimeEquals(const std::string &a, const std::string &b);

//! The bytes a base64url string (RFC 4648 section 5, padding optional) spells; throws
//! std::invalid_argument on anything else.
std::string FromBase64Url(const std::string &text);

//! The base64url encoding of `bytes`, unpadded.
std::string ToBase64Url(const std::string &bytes);

} // namespace qod
