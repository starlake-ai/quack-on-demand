// Unit tests for json and handle: the strict JSON reader, handle verification (format, MAC, shape,
// node, expiry) and the nonce ledger, then the golden handle and prelude NodeHandlesSpec (Scala) pins
// too (the golden file's path is the one argument). Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "authz.hpp"
#include "crypto.hpp"
#include "handle.hpp"
#include "json.hpp"

#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

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

const std::string kCtx(64, 'c');

//! A handle payload; `members` goes right before `read_only` (raw JSON members, none: ""), which goes
//! right before "nonce". Node protocol 5: every handle carries ctx, creds, path and readOnly.
std::string Payload(const std::string &node = kNode, int64_t exp = kNow + 60000,
                    const std::string &tenant = "\"acme\"",
                    const std::string &members = ",\"ctx\":\"" + kCtx + "\",\"creds\":\"\",\"path\":\"\"",
                    const std::string &read_only = ",\"readOnly\":[]") {
	return std::string("{\"v\":3,\"node\":\"") + node +
	       "\",\"replica\":\"http://10.0.0.1:20900\",\"principal\":{\"id\":\"u-1\",\"name\":\"alice\",\"tenant\":" +
	       tenant + ",\"superuser\":false,\"system\":false},\"pool\":\"acme/acme_db/bi\",\"policyVersion\":42" +
	       members + read_only + ",\"nonce\":\"" + kNonce + "\",\"exp\":" + std::to_string(exp) + "}";
}

//! A handle with a valid ctx, an empty creds and path, and the given readOnly members (raw JSON).
std::string PayloadWithReadOnly(const std::string &read_only) {
	return Payload(kNode, kNow + 60000, "\"acme\"", ",\"ctx\":\"" + kCtx + "\",\"creds\":\"\",\"path\":\"\"",
	               read_only);
}

std::string Sign(const std::string &payload, const std::string &key = kKey) {
	return qod::ToBase64Url(payload) + "." + qod::Hex(qod::HmacSha256(key, payload));
}

//! A handle with the given ctx member (raw JSON), an empty creds and an empty path.
std::string PayloadWithCtx(const std::string &ctx_json) {
	return Payload(kNode, kNow + 60000, "\"acme\"", ",\"ctx\":" + ctx_json + ",\"creds\":\"\",\"path\":\"\"");
}

//! A handle with a valid ctx, the given creds member (raw JSON) and an empty path.
std::string PayloadWithCreds(const std::string &creds_json) {
	return Payload(kNode, kNow + 60000, "\"acme\"",
	               ",\"ctx\":\"" + kCtx + "\",\"creds\":" + creds_json + ",\"path\":\"\"");
}

//! A handle with a valid ctx, an empty creds and the given path members (raw JSON, none: "").
std::string PayloadWithPath(const std::string &path_members) {
	return Payload(kNode, kNow + 60000, "\"acme\"", ",\"ctx\":\"" + kCtx + "\",\"creds\":\"\"" + path_members);
}

// The golden handle NodeHandlesSpec (Scala) mints from fixed inputs, and its prelude: both sides agree
// on the handle's format and on the prelude's spelling.
void VerifyGolden(const std::string &path) {
	std::ifstream in(path);
	if (!in) {
		std::cerr << "FAIL: cannot read " << path << std::endl;
		failures++;
		return;
	}
	std::string line, key_hex, text, prelude, ctx, creds, use_path, read_only;
	while (std::getline(in, line)) {
		if (line.rfind("readOnly=", 0) == 0) {
			read_only = line.substr(9);
		} else if (line.rfind("key=", 0) == 0) {
			key_hex = line.substr(4);
		} else if (line.rfind("handle=", 0) == 0) {
			text = line.substr(7);
		} else if (line.rfind("ctx=", 0) == 0) {
			ctx = line.substr(4);
		} else if (line.rfind("creds=", 0) == 0) {
			creds = line.substr(6);
		} else if (line.rfind("path=", 0) == 0) {
			use_path = line.substr(5);
		} else if (line.rfind("prelude=", 0) == 0) {
			prelude = line.substr(8);
		}
	}
	// The manager's spelling of the prelude is the one the node reads a handle out of.
	Expect(!text.empty() && qod::PreludeHandle(prelude) == text, "golden prelude carries the golden handle");
	try {
		auto handle = qod::VerifyHandle(text, qod::FromHex(key_hex), "quack-acme-db-bi-1", 1761200000000);
		Expect(handle.principal.id == "u-1" && handle.principal.name == "alice" && handle.principal.tenant == "acme",
		       "golden principal");
		Expect(!handle.principal.superuser && !handle.principal.system, "golden flags");
		Expect(handle.replica == "http://10.0.0.1:20900" && handle.pool == "acme/acme_db/bi", "golden replica, pool");
		Expect(handle.policy_version == 42 && handle.nonce == "0123456789abcdef0123456789abcdef" &&
		           handle.exp == 1761200060000,
		       "golden version, nonce, exp");
		Expect(!ctx.empty() && handle.ctx == ctx, "golden ctx");
		Expect(!creds.empty() && handle.creds == creds, "golden creds");
		Expect(!use_path.empty() && handle.path == use_path, "golden path");
		std::string joined;
		for (auto &alias : handle.read_only) {
			joined += (joined.empty() ? "" : ",") + alias;
		}
		Expect(!read_only.empty() && joined == read_only, "golden readOnly");
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
	auto no_principal = std::string("{\"v\":3,\"node\":\"") + kNode +
	                    "\",\"replica\":\"r\",\"pool\":\"p\",\"policyVersion\":1,\"ctx\":\"" + kCtx +
	                    "\",\"creds\":\"\",\"path\":\"\",\"readOnly\":[],\"nonce\":\"" + kNonce +
	                    "\",\"exp\":" + std::to_string(kNow + 1000) + "}";
	ExpectRefused([&] { qod::VerifyHandle(Sign(no_principal), kKey, kNode, kNow); }, "principal", "no principal");
	auto v4 = Payload();
	v4.replace(v4.find("\"v\":3"), 5, "\"v\":4");
	ExpectRefused([&] { qod::VerifyHandle(Sign(v4), kKey, kNode, kNow); }, "version", "version 4");
	// Version 2 is the handle of node protocols 3 and 4, which do not read readOnly: a node of this build
	// refuses it, and a node of those protocols refuses a version 3 handle, the probe's included, so it
	// stays unroutable. Version 1 is node protocol 2's, whose nodes enforce nothing in the engine.
	auto v2 = Payload();
	v2.replace(v2.find("\"v\":3"), 5, "\"v\":2");
	ExpectRefused([&] { qod::VerifyHandle(Sign(v2), kKey, kNode, kNow); }, "version", "version 2");
	auto v1 = Payload();
	v1.replace(v1.find("\"v\":3"), 5, "\"v\":1");
	ExpectRefused([&] { qod::VerifyHandle(Sign(v1), kKey, kNode, kNow); }, "version", "version 1");

	// ---- the session context digest
	Expect(qod::VerifyHandle(Sign(PayloadWithCtx("\"" + kCtx + "\"")), kKey, kNode, kNow).ctx == kCtx,
	       "the handle's ctx");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCtx("\"" + kCtx.substr(1) + "\"")), kKey, kNode, kNow); },
	              "bad ctx", "a short ctx");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCtx("\"" + std::string(64, 'C') + "\"")), kKey, kNode, kNow); },
	              "bad ctx", "an uppercase ctx");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCtx("42")), kKey, kNode, kNow); }, "malformed handle",
	              "a ctx that is no string");

	// ---- the user's S3 credential digest
	Expect(qod::VerifyHandle(Sign(PayloadWithCreds("\"\"")), kKey, kNode, kNow).creds.empty(),
	       "an empty creds: no credential");
	Expect(qod::VerifyHandle(Sign(PayloadWithCreds("\"" + std::string(64, 'd') + "\"")), kKey, kNode, kNow).creds ==
	           std::string(64, 'd'),
	       "the handle's creds");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCreds("\"" + std::string(63, 'd') + "\"")), kKey, kNode, kNow); },
	              "bad creds", "a short creds");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCreds("\"" + std::string(64, 'D') + "\"")), kKey, kNode, kNow); },
	              "bad creds", "an uppercase creds");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCreds("7")), kKey, kNode, kNow); }, "malformed handle",
	              "a creds that is no string");

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

	// Run without the golden file, the cross-language checks would be skipped and the run still pass.
	if (argc < 2) {
		std::cerr << "FAIL: no golden file: run handle_test with the path of tests/golden/handle-v3.txt" << std::endl;
		failures++;
	} else {
		VerifyGolden(argv[1]);
	}

	// ---- node protocol 3: every handle carries the session's authorization context digest
	auto without_ctx = std::string("{\"v\":3,\"node\":\"") + kNode +
	                   "\",\"replica\":\"http://10.0.0.1:20900\",\"principal\":{\"id\":\"u-1\",\"name\":\"alice\","
	                   "\"tenant\":\"acme\",\"superuser\":false,\"system\":false},\"pool\":\"acme/acme_db/bi\","
	                   "\"policyVersion\":42,\"creds\":\"\",\"path\":\"\",\"readOnly\":[],\"nonce\":\"" +
	                   kNonce + "\",\"exp\":" + std::to_string(kNow + 60000) + "}";
	ExpectRefused([&] { qod::VerifyHandle(Sign(without_ctx), kKey, kNode, kNow); }, "ctx", "a handle without ctx");
	auto null_ctx = without_ctx;
	null_ctx.insert(null_ctx.find(",\"nonce\""), ",\"ctx\":null");
	ExpectRefused([&] { qod::VerifyHandle(Sign(null_ctx), kKey, kNode, kNow); }, "ctx", "a null ctx");
	auto short_ctx = without_ctx;
	short_ctx.insert(short_ctx.find(",\"nonce\""), ",\"ctx\":\"abc\"");
	ExpectRefused([&] { qod::VerifyHandle(Sign(short_ctx), kKey, kNode, kNow); }, "ctx",
	              "a ctx that is not 64 lowercase hex digits");
	auto upper_ctx = without_ctx;
	upper_ctx.insert(upper_ctx.find(",\"nonce\""), ",\"ctx\":\"" + std::string(64, 'A') + "\"");
	ExpectRefused([&] { qod::VerifyHandle(Sign(upper_ctx), kKey, kNode, kNow); }, "ctx", "an uppercase ctx");
	auto good_ctx = without_ctx;
	good_ctx.insert(good_ctx.find(",\"nonce\""), ",\"ctx\":\"" + std::string(64, 'c') + "\"");
	Expect(qod::VerifyHandle(Sign(good_ctx), kKey, kNode, kNow).ctx == std::string(64, 'c'), "a valid ctx is read");
	// ---- node protocol 3: and the digest of the principal's S3 credentials (decision 23)
	ExpectRefused(
	    [&] {
		    qod::VerifyHandle(Sign(Payload(kNode, kNow + 60000, "\"acme\"", ",\"ctx\":\"" + kCtx + "\",\"path\":\"\"")),
		                      kKey, kNode, kNow);
	    },
	    "creds", "a handle without creds");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithCreds("null")), kKey, kNode, kNow); }, "creds", "a null creds");
	Expect(qod::VerifyHandle(Sign(Payload()), kKey, kNode, kNow).creds.empty(), "an empty creds is no credential");
	// ---- node protocol 3: and the session default path the manager expects (CONTRACT Addendum 4 item 22)
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithPath("")), kKey, kNode, kNow); }, "path",
	              "a handle without path");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithPath(",\"path\":null")), kKey, kNode, kNow); }, "path",
	              "a null path");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithPath(",\"path\":7")), kKey, kNode, kNow); }, "path",
	              "a path that is no string");
	Expect(qod::VerifyHandle(Sign(PayloadWithPath(",\"path\":\"acme_db.main\"")), kKey, kNode, kNow).path ==
	           "acme_db.main",
	       "the handle's path");
	Expect(qod::VerifyHandle(Sign(Payload()), kKey, kNode, kNow).path.empty(), "an empty path: no USE prelude");

	// ---- node protocol 5: and the pool's read-only catalogs (decision 24)
	Expect(qod::VerifyHandle(Sign(Payload()), kKey, kNode, kNow).read_only.empty(), "an empty readOnly: none");
	Expect(qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":[\"crm\",\"erp_pg\",\"x9\"]")), kKey, kNode,
	                         kNow)
	               .read_only == std::vector<std::string> {"crm", "erp_pg", "x9"},
	       "the handle's readOnly");
	auto longest = std::string(128, 'a');
	Expect(qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":[\"" + longest + "\"]")), kKey, kNode, kNow)
	               .read_only == std::vector<std::string> {longest},
	       "a 128-character alias");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithReadOnly("")), kKey, kNode, kNow); }, "readOnly",
	              "a handle without readOnly");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":null")), kKey, kNode, kNow); },
	              "readOnly", "a null readOnly");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":\"crm\"")), kKey, kNode, kNow); },
	              "readOnly", "a readOnly that is no array");
	ExpectRefused([&] { qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":[7]")), kKey, kNode, kNow); },
	              "readOnly", "a readOnly entry that is no string");
	for (auto bad : {"[\"CRM\"]", "[\"\"]", "[\"crm-x\"]", "[\"cr\\u0131m\"]", "[\"erp\",\"crm\"]",
	                 "[\"crm\",\"crm\"]"}) {
		ExpectRefused(
		    [&] { qod::VerifyHandle(Sign(PayloadWithReadOnly(std::string(",\"readOnly\":") + bad)), kKey, kNode, kNow); },
		    "bad readOnly", std::string("readOnly ") + bad);
	}
	ExpectRefused(
	    [&] {
		    qod::VerifyHandle(Sign(PayloadWithReadOnly(",\"readOnly\":[\"" + std::string(129, 'a') + "\"]")), kKey, kNode,
		                      kNow);
	    },
	    "bad readOnly", "an alias longer than 128 characters");

	if (failures == 0) {
		std::cout << "handle_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
