// Session handles for the unit tests, laid out and signed as the manager mints them
// (NodeHandles.scala): the one place the tests' payload shape lives.

#pragma once

#include "authz.hpp"
#include "crypto.hpp"

#include <cstdint>
#include <cstdio>
#include <string>

namespace qod_test {

//! The tests' node key and node id.
const std::string kKey(32, '\x42');
const std::string kNode = "quack-acme-db-bi-1";

//! A handle for `principal_id` (tenant acme) on `node`, expiring at `exp`, with a fresh nonce.
inline std::string Mint(const std::string &principal_id, int64_t exp, const std::string &node = kNode,
                        int64_t policy_version = 1) {
	static int counter = 0;
	char nonce[33];
	std::snprintf(nonce, sizeof(nonce), "%032x", ++counter);
	auto payload = std::string("{\"v\":1,\"node\":\"") + node +
	               "\",\"replica\":\"http://127.0.0.1:20900\",\"principal\":{\"id\":\"" + principal_id +
	               "\",\"name\":\"" + principal_id + "\",\"tenant\":\"acme\",\"superuser\":false,\"system\":false}," +
	               "\"pool\":\"acme/acme_db/bi\",\"policyVersion\":" + std::to_string(policy_version) + ",\"nonce\":\"" +
	               nonce + "\",\"exp\":" + std::to_string(exp) + "}";
	return qod::ToBase64Url(payload) + "." + qod::Hex(qod::HmacSha256(kKey, payload));
}

//! The manager's prelude carrying `handle`.
inline std::string Prelude(const std::string &handle) {
	return std::string(qod::kPreludePrefix) + handle + qod::kPreludeSuffix;
}

} // namespace qod_test
