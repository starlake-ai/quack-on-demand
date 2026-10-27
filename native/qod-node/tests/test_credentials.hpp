// Session handles carrying the replica the tests serve and a credentials digest, and the replica's
// signed credential answers, laid out as the manager mints and signs them (NodeHandles.scala and
// CredentialsRoute.scala, Task 18): the one place the tests' credential shapes live.

#pragma once

#include "crypto.hpp"
#include "test_handles.hpp"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace qod_test {

//! A handle for `principal_id` on kNode, minted by `replica`, carrying `creds` ("" or 64 lowercase
//! hex digits) and ctx, expiring at `exp`, with a fresh nonce (none of test_handles.hpp's Mint).
inline std::string MintFrom(const std::string &replica, const std::string &principal_id, const std::string &creds,
                            int64_t exp, bool superuser = false, bool system = false) {
	static int counter = 0x100000;
	char nonce[33];
	std::snprintf(nonce, sizeof(nonce), "%032x", ++counter);
	auto tenant = superuser ? std::string("null") : std::string("\"acme\"");
	auto payload = std::string("{\"v\":1,\"node\":\"") + kNode + "\",\"replica\":\"" + replica +
	               "\",\"principal\":{\"id\":\"" + principal_id + "\",\"name\":\"" + principal_id +
	               "\",\"tenant\":" + tenant + ",\"superuser\":" + (superuser ? "true" : "false") +
	               ",\"system\":" + (system ? "true" : "false") +
	               "},\"pool\":\"acme/acme_db/bi\",\"policyVersion\":1,\"ctx\":\"" + std::string(64, 'c') +
	               "\",\"creds\":\"" + creds + "\",\"nonce\":\"" + nonce + "\",\"exp\":" + std::to_string(exp) + "}";
	return qod::ToBase64Url(payload) + "." + qod::Hex(qod::HmacSha256(kKey, payload));
}

//! A handle's `creds` for the tests: 64 copies of one hex digit (the node only compares digests).
inline std::string Digest(char digit) {
	return std::string(64, digit);
}

//! One credential as the replica serves it; an empty optional member is sent as null.
struct CredentialEntry {
	std::string id;
	std::string scope;
	std::string key_id = "AKIAALICE";
	std::string secret = "alice-secret";
	std::string session_token;
	std::string region;
	std::string endpoint;
	std::string url_style;
	bool use_ssl = true;
};

//! A JSON string of `text` (the tests' values need no escaping).
inline std::string JsonText(const std::string &text) {
	return "\"" + text + "\"";
}

inline std::string JsonTextOrNull(const std::string &text) {
	return text.empty() ? std::string("null") : JsonText(text);
}

//! The answer JSON for `entries`, in the order given, and `creds`: keys in byte order, like the
//! manager's canonical JSON.
inline std::string CredentialsJson(const std::string &creds, const std::vector<CredentialEntry> &entries) {
	std::string list;
	for (auto &e : entries) {
		if (!list.empty()) {
			list += ",";
		}
		list += "{\"endpoint\":" + JsonTextOrNull(e.endpoint) + ",\"id\":" + JsonText(e.id) +
		        ",\"keyId\":" + JsonText(e.key_id) + ",\"region\":" + JsonTextOrNull(e.region) +
		        ",\"scope\":" + JsonText(e.scope) + ",\"secret\":" + JsonText(e.secret) +
		        ",\"sessionToken\":" + JsonTextOrNull(e.session_token) + ",\"urlStyle\":" + JsonTextOrNull(e.url_style) +
		        ",\"useSsl\":" + (e.use_ssl ? "true" : "false") + "}";
	}
	return "{\"credentials\":[" + list + "],\"creds\":" + JsonText(creds) + ",\"v\":1}";
}

//! `json` signed with `key`, as the answer travels.
inline std::string SignCredentials(const std::string &json, const std::string &key = kKey) {
	return qod::ToBase64Url(json) + "." + qod::Hex(qod::HmacSha256(key, json));
}

} // namespace qod_test
