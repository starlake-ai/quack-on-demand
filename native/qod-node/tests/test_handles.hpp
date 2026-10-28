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

//! What a test handle says. A superuser has no tenant.
struct MintSpec {
	std::string principal_id;
	int64_t exp = 0;
	std::string node = kNode;
	int64_t policy_version = 1;
	bool superuser = false;
	//! The manager's own statements.
	bool system = false;
	//! Where the node asks for verdicts on this connection's statements.
	std::string replica = "http://127.0.0.1:20900";
	std::string ctx = std::string(64, 'c');
	//! The digest of the principal's S3 credentials (decision 23); empty: none.
	std::string creds;
};

//! A handle as `spec` says, with a fresh nonce.
inline std::string MintWith(const MintSpec &spec) {
	static int counter = 0;
	char nonce[33];
	std::snprintf(nonce, sizeof(nonce), "%032x", ++counter);
	auto payload = std::string("{\"v\":1,\"node\":\"") + spec.node + "\",\"replica\":\"" + spec.replica +
	               "\",\"principal\":{\"id\":\"" + spec.principal_id + "\",\"name\":\"" + spec.principal_id +
	               "\",\"tenant\":" + (spec.superuser ? "null" : "\"acme\"") +
	               ",\"superuser\":" + (spec.superuser ? "true" : "false") +
	               ",\"system\":" + (spec.system ? "true" : "false") + "}," +
	               "\"pool\":\"acme/acme_db/bi\",\"policyVersion\":" + std::to_string(spec.policy_version) +
	               ",\"ctx\":\"" + spec.ctx + "\",\"creds\":\"" + spec.creds + "\",\"nonce\":\"" + nonce +
	               "\",\"exp\":" + std::to_string(spec.exp) + "}";
	return qod::ToBase64Url(payload) + "." + qod::Hex(qod::HmacSha256(kKey, payload));
}

//! A handle for `principal_id` (tenant acme) on `node`, expiring at `exp`, with a fresh nonce.
inline std::string Mint(const std::string &principal_id, int64_t exp, const std::string &node = kNode,
                        int64_t policy_version = 1) {
	MintSpec spec;
	spec.principal_id = principal_id;
	spec.exp = exp;
	spec.node = node;
	spec.policy_version = policy_version;
	return MintWith(spec);
}

//! The manager's prelude carrying `handle`.
inline std::string Prelude(const std::string &handle) {
	return std::string(qod::kPreludePrefix) + handle + qod::kPreludeSuffix;
}

} // namespace qod_test
