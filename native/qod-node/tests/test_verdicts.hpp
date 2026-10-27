// Verdicts for the unit tests, laid out and signed as the manager signs them (Verdicts.sign, Task 6):
// the one place the tests' verdict shape lives.

#pragma once

#include "crypto.hpp"
#include "test_handles.hpp"

#include <cstdint>
#include <string>

namespace qod_test {

//! A verdict's fields, valid by default except fingerprint and expires_at, which each test sets.
struct VerdictFields {
	std::string kind = "plan";
	std::string decision = "allow";
	std::string reason;
	bool retryable = false;
	std::string principal = "u-alice";
	int64_t policy_version = 1;
	std::string ctx = std::string(64, 'c');
	std::string fingerprint;
	int64_t expires_at = 0;
	//! JSON arrays, as sent.
	std::string row_filters = "[]";
	std::string masks = "[]";
};

//! The verdict JSON of `f`, keys in byte order like the manager's canonical JSON.
inline std::string VerdictJson(const VerdictFields &f) {
	return std::string("{\"ctx\":\"") + f.ctx + "\",\"decision\":\"" + f.decision + "\",\"expiresAt\":" +
	       std::to_string(f.expires_at) + ",\"fingerprint\":\"" + f.fingerprint + "\",\"kind\":\"" + f.kind +
	       "\",\"masks\":" + f.masks + ",\"policyVersion\":" + std::to_string(f.policy_version) +
	       ",\"principal\":\"" + f.principal + "\",\"reason\":\"" + f.reason +
	       "\",\"retryable\":" + (f.retryable ? "true" : "false") + ",\"rowFilters\":" + f.row_filters + ",\"v\":1}";
}

//! `json` signed with `key`, as a verdict travels.
inline std::string SignVerdict(const std::string &json, const std::string &key = kKey) {
	return qod::ToBase64Url(json) + "." + qod::Hex(qod::HmacSha256(key, json));
}

} // namespace qod_test
