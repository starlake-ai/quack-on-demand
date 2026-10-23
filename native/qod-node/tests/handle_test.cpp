// Unit tests for json and handle: the strict JSON reader, handle verification (format, MAC, shape,
// node, expiry) and the nonce ledger. Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "crypto.hpp"
#include "handle.hpp"
#include "json.hpp"

#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

template <class F>
void ExpectRefused(F f, const std::string &needle, const std::string &what) {
	try {
		f();
		std::cerr << "FAIL: accepted: " << what << std::endl;
		failures++;
	} catch (std::invalid_argument &error) {
		if (std::string(error.what()).find(needle) == std::string::npos) {
			std::cerr << "FAIL: " << what << ": \"" << error.what() << "\" does not mention \"" << needle << "\""
			          << std::endl;
			failures++;
		}
	}
}

const std::string kKey(32, '\x42');
const std::string kNode = "quack-acme-db-bi-1";
const int64_t kNow = 1761200000000;
const std::string kNonce = "0123456789abcdef0123456789abcdef";

std::string Payload(const std::string &node = kNode, int64_t exp = kNow + 60000,
                    const std::string &tenant = "\"acme\"") {
	return std::string("{\"v\":1,\"node\":\"") + node +
	       "\",\"replica\":\"http://10.0.0.1:20900\",\"principal\":{\"id\":\"u-1\",\"name\":\"alice\",\"tenant\":" +
	       tenant + ",\"superuser\":false,\"system\":false},\"pool\":\"acme/acme_db/bi\",\"policyVersion\":42," +
	       "\"nonce\":\"" + kNonce + "\",\"exp\":" + std::to_string(exp) + "}";
}

std::string Sign(const std::string &payload, const std::string &key = kKey) {
	return qod::ToBase64Url(payload) + "." + qod::Hex(qod::HmacSha256(key, payload));
}

// The golden handle NodeHandlesSpec (Scala) mints from fixed inputs: both sides agree on the format.
void VerifyGolden(const std::string &path) {
	std::ifstream in(path);
	if (!in) {
		std::cerr << "FAIL: cannot read " << path << std::endl;
		failures++;
		return;
	}
	std::string line, key_hex, text;
	while (std::getline(in, line)) {
		if (line.rfind("key=", 0) == 0) {
			key_hex = line.substr(4);
		} else if (line.rfind("handle=", 0) == 0) {
			text = line.substr(7);
		}
	}
	try {
		auto handle = qod::VerifyHandle(text, qod::FromHex(key_hex), "quack-acme-db-bi-1", 1761200000000);
		Expect(handle.principal.id == "u-1" && handle.principal.name == "alice" && handle.principal.tenant == "acme",
		       "golden principal");
		Expect(!handle.principal.superuser && !handle.principal.system, "golden flags");
		Expect(handle.replica == "http://10.0.0.1:20900" && handle.pool == "acme/acme_db/bi", "golden replica, pool");
		Expect(handle.policy_version == 42 && handle.nonce == "0123456789abcdef0123456789abcdef" &&
		           handle.exp == 1761200060000,
		       "golden version, nonce, exp");
	} catch (std::invalid_argument &error) {
		std::cerr << "FAIL: the golden handle does not verify: " << error.what() << std::endl;
		failures++;
	}
}

} // namespace

int main(int argc, char **argv) {
	// ---- json
	auto doc = qod::ParseJson(" {\"a\": [1, -2, true, null, \"x\\n\\u00e9\\ud83e\\udd86\"], \"b\": {\"c\": false}} ");
	Expect(doc.Get("a", qod::JsonValue::Kind::ARRAY).array.size() == 5, "json array");
	Expect(doc.Get("a", qod::JsonValue::Kind::ARRAY).array[1].integer == -2, "json negative");
	Expect(doc.Get("a", qod::JsonValue::Kind::ARRAY).array[4].string == "x\n\xc3\xa9\xf0\x9f\xa6\x86", "json escapes");
	Expect(!doc.Get("b", qod::JsonValue::Kind::OBJECT).Get("c", qod::JsonValue::Kind::BOOLEAN).boolean, "json nested");
	ExpectRefused([] { qod::ParseJson("{\"a\":1,\"a\":2}"); }, "duplicate key", "json duplicate key");
	ExpectRefused([] { qod::ParseJson("{\"a\":1.5}"); }, "not an integer", "json fraction");
	ExpectRefused([] { qod::ParseJson("{\"a\":1e3}"); }, "not an integer", "json exponent");
	ExpectRefused([] { qod::ParseJson("{\"a\":01}"); }, "leading zero", "json leading zero");
	ExpectRefused([] { qod::ParseJson("{\"a\":1} x"); }, "trailing", "json trailing");
	ExpectRefused([] { qod::ParseJson("{\"a\":\"\\ud83e\"}"); }, "surrogate", "json lone surrogate");
	ExpectRefused([] { qod::ParseJson("\"a\nb\""); }, "control character", "json raw newline");
	ExpectRefused([] { qod::ParseJson(std::string(40, '[') + std::string(40, ']')); }, "too deep", "json depth");
	ExpectRefused([] { qod::ParseJson("{\"a\":99999999999999999999}"); }, "out of range", "json range");
	ExpectRefused([] { qod::ParseJson("{\"a\":1}").Get("a", qod::JsonValue::Kind::STRING); }, "not a string",
	              "json type");

	// ---- handles
	auto handle = qod::VerifyHandle(Sign(Payload()), kKey, kNode, kNow);
	Expect(handle.principal.id == "u-1" && handle.principal.name == "alice", "handle principal");
	Expect(handle.principal.tenant == "acme" && !handle.principal.superuser && !handle.principal.system,
	       "handle flags");
	Expect(handle.pool == "acme/acme_db/bi" && handle.policy_version == 42 && handle.nonce == kNonce,
	       "handle fields");
	Expect(handle.replica == "http://10.0.0.1:20900" && handle.exp == kNow + 60000, "handle replica, exp");
	Expect(qod::VerifyHandle(Sign(Payload(kNode, kNow + 60000, "null")), kKey, kNode, kNow).principal.tenant.empty(),
	       "handle null tenant");

	auto good = Sign(Payload());
	auto tampered = qod::ToBase64Url(Payload().replace(Payload().find("alice"), 5, "admin")) + good.substr(good.find('.'));
	ExpectRefused([&] { qod::VerifyHandle(tampered, kKey, kNode, kNow); }, "does not verify", "tampered payload");
	ExpectRefused([&] { qod::VerifyHandle(Sign(Payload(), std::string(32, 'x')), kKey, kNode, kNow); },
	              "does not verify", "other key");
	ExpectRefused([&] { qod::VerifyHandle(Sign(Payload("quack-acme-db-bi-2")), kKey, kNode, kNow); }, "another node",
	              "other node");
	ExpectRefused([&] { qod::VerifyHandle(Sign(Payload(kNode, kNow)), kKey, kNode, kNow); }, "expired", "expired");
	ExpectRefused([&] { qod::VerifyHandle(Sign(Payload(kNode, kNow + qod::kMaxHandleLifetimeMs + 1)), kKey, kNode, kNow); },
	              "too long", "too long");
	ExpectRefused([&] { qod::VerifyHandle("no-dot", kKey, kNode, kNow); }, "malformed handle", "no dot");
	ExpectRefused([&] { qod::VerifyHandle(good + ".x", kKey, kNode, kNow); }, "malformed handle", "two dots");
	ExpectRefused([&] { qod::VerifyHandle(good.substr(0, good.size() - 1) + "G", kKey, kNode, kNow); }, "MAC",
	              "bad hex");
	ExpectRefused([&] { qod::VerifyHandle("!!" + good.substr(good.find('.')), kKey, kNode, kNow); }, "payload",
	              "bad base64");
	auto no_principal = std::string("{\"v\":1,\"node\":\"") + kNode +
	                    "\",\"replica\":\"r\",\"pool\":\"p\",\"policyVersion\":1,\"nonce\":\"" + kNonce +
	                    "\",\"exp\":" + std::to_string(kNow + 1000) + "}";
	ExpectRefused([&] { qod::VerifyHandle(Sign(no_principal), kKey, kNode, kNow); }, "principal", "no principal");
	auto v2 = Payload();
	v2.replace(v2.find("\"v\":1"), 5, "\"v\":2");
	ExpectRefused([&] { qod::VerifyHandle(Sign(v2), kKey, kNode, kNow); }, "version", "version 2");

	// ---- nonce ledger
	qod::NonceLedger ledger;
	Expect(ledger.Consume(kNonce, kNow + 1000, kNow), "nonce first use");
	Expect(!ledger.Consume(kNonce, kNow + 1000, kNow + 500), "nonce replay refused");
	Expect(ledger.Consume(kNonce, kNow + 30000, kNow + 20000), "nonce reusable once its handle expired");

	qod::NonceLedger peeked;
	Expect(!peeked.Used(kNonce, kNow), "an unused nonce");
	Expect(!peeked.Used(kNonce, kNow), "Used records nothing");
	Expect(peeked.Consume(kNonce, kNow + 1000, kNow), "consumed after a peek");
	Expect(peeked.Used(kNonce, kNow + 500), "a consumed nonce is used until its handle expires");
	Expect(!peeked.Used(kNonce, kNow + 1000), "and no longer once it expired");

	// ---- principal equality: every field counts
	qod::Principal alice {"u-1", "alice", "acme", false, false};
	auto promoted = alice;
	promoted.superuser = true;
	Expect(alice == qod::Principal({"u-1", "alice", "acme", false, false}), "same principal");
	Expect(alice != promoted, "another flag is another principal");

	if (argc > 1) {
		VerifyGolden(argv[1]);
	}

	if (failures == 0) {
		std::cout << "handle_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
