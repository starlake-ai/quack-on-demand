// Tests for decide_client against a real HTTP replica on 127.0.0.1 (the test server): the request as
// the manager reads it, every answer the node must refuse or retry, the verdict checks and the cache.
// Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "crypto.hpp"
#include "decide_client.hpp"
#include "json.hpp"
#include "test_http_server.hpp"
#include "test_verdicts.hpp"

#include <atomic>
#include <chrono>
#include <iostream>
#include <mutex>
#include <string>

namespace {

using qod::DecideResult;
using qod_test::kKey;
using qod_test::kNode;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

std::atomic<int64_t> now_ms {1761200000000};
const std::string kCtx(64, 'c');

//! How the replica answers the next requests.
struct Replica {
	std::mutex lock;
	std::string mode = "allow";
};
Replica replica;

void Mode(const std::string &mode) {
	std::lock_guard<std::mutex> guard(replica.lock);
	replica.mode = mode;
}

//! The test replica: reads the request like the manager does (description decoded, fingerprint over its
//! bytes) and answers as the current mode says.
qod_test::HttpAnswer Answer(const qod_test::HttpRequest &request) {
	std::string mode;
	{
		std::lock_guard<std::mutex> guard(replica.lock);
		mode = replica.mode;
	}
	qod_test::HttpAnswer answer;
	// The manager's 401 bodies (DecideRoute).
	if (mode == "401") {
		answer.status = 401;
		answer.body = "signature does not verify";
		return answer;
	}
	if (mode == "401-handle") {
		answer.status = 401;
		answer.body = "handle refused: handle does not verify";
		return answer;
	}
	if (mode == "503") {
		answer.status = 503;
		answer.body = "authorization context expired; retry";
		return answer;
	}
	if (mode == "500") {
		answer.status = 500;
		answer.body = "boom";
		return answer;
	}
	if (mode == "drop") {
		answer.drop = true;
		return answer;
	}
	if (mode == "garbage") {
		answer.body = "not a verdict";
		return answer;
	}
	auto body = qod::ParseJson(request.body);
	auto description = qod::FromBase64Url(body.Get("description", qod::JsonValue::Kind::STRING).string);
	qod_test::VerdictFields fields;
	fields.kind = body.Get("kind", qod::JsonValue::Kind::STRING).string;
	fields.fingerprint = qod::Hex(qod::Sha256(description));
	fields.expires_at = fields.kind == "screen" ? now_ms.load() : now_ms.load() + 60000;
	if (mode == "refuse" || mode == "refuse-retry") {
		fields.decision = "refuse";
		fields.reason = "table lake.main.t is not granted";
		fields.retryable = mode == "refuse-retry";
	}
	if (mode == "slow") {
		answer.delay_ms = 1000;
	}
	if (mode == "chunked") {
		answer.chunked = true;
	}
	if (mode == "other-fingerprint") {
		fields.fingerprint = qod::Hex(qod::Sha256("another description"));
	}
	if (mode == "other-principal") {
		fields.principal = "u-mallory";
	}
	if (mode == "other-version") {
		fields.policy_version = 2;
	}
	if (mode == "other-ctx") {
		fields.ctx = std::string(64, 'd');
	}
	if (mode == "expired") {
		fields.expires_at = now_ms.load();
	}
	if (mode == "row-filters") {
		fields.row_filters = "[\"x\"]";
	}
	if (mode == "masks") {
		fields.masks = "[\"x\"]";
	}
	answer.body = qod_test::SignVerdict(qod_test::VerdictJson(fields), mode == "bad-mac" ? std::string(32, 'x') : kKey);
	return answer;
}

int cases = 0;

//! A plan call for alice with a description no earlier call used (so no cache hit).
qod::DecideCall Call(const std::string &url, const std::string &kind = "plan") {
	qod::DecideCall call;
	call.replica = url;
	call.handle = "the-handle";
	call.kind = kind;
	call.description = "{\"case\":" + std::to_string(++cases) + ",\"v\":1}";
	call.principal = {"u-alice", "alice", "acme", false, false};
	call.policy_version = 1;
	call.ctx = kCtx;
	return call;
}

void ExpectRefusal(const DecideResult &result, DecideResult::Status status, bool retryable, const std::string &needle,
                   const std::string &what) {
	if (result.status != status || result.retryable != retryable ||
	    result.Message().find("QoD: " + needle) != 0) {
		std::cerr << "FAIL: " << what << ": status " << static_cast<int>(result.status) << ", retryable "
		          << result.retryable << ", \"" << result.Message() << "\"" << std::endl;
		failures++;
	}
}

} // namespace

int main() {
	qod_test::TestHttpServer server(Answer);
	qod::DecideClient client(kNode, kKey, qod::AuthzLimits {300, 100}, [] { return now_ms.load(); });

	// ---- allow, and the request as the manager reads it
	auto call = Call(server.Url());
	auto allowed = client.Decide(call);
	Expect(allowed.status == DecideResult::Status::Allow && allowed.has_verdict && !allowed.cached, "an allow");
	auto request = server.Last();
	auto expected_body = "{\"description\":\"" + qod::ToBase64Url(call.description) +
	                     "\",\"handle\":\"the-handle\",\"kind\":\"plan\"}";
	Expect(request.method == "POST" && request.path == "/api/internal/authz/decide", "POST to the decide path");
	Expect(request.body == expected_body, "the body is canonical JSON: description base64url, handle, kind");
	Expect(request.headers["content-type"] == "application/json" && request.headers["x-qod-node"] == kNode,
	       "Content-Type and X-QoD-Node");
	Expect(request.headers["x-qod-signature"] == qod::Hex(qod::HmacSha256(kKey, request.body)),
	       "X-QoD-Signature is the node key's HMAC of the body bytes");

	// ---- the cache: plan verdicts by principal, policy version, ctx and fingerprint
	auto requests = server.Requests();
	auto again = client.Decide(call);
	Expect(again.status == DecideResult::Status::Allow && again.cached && server.Requests() == requests,
	       "the same plan again is answered from the cache");
	// The same statement under another session context (a restricted PAT of alice): the cache must not
	// serve it alice's allow. This replica always signs kCtx, so the answer it gets does not match.
	auto other_ctx = call;
	other_ctx.ctx = std::string(64, 'e');
	ExpectRefusal(client.Decide(other_ctx), DecideResult::Status::Refuse, false,
	              "authorization refused: the verdict does not verify: verdict is for another session context",
	              "another ctx is never served alice's cached verdict, and a mismatched verdict is refused");
	Expect(server.Requests() == requests + 1, "another ctx went to the replica");
	auto other_version = call;
	other_version.policy_version = 2;
	client.Decide(other_version);
	Expect(server.Requests() == requests + 2, "another policy version went to the replica");

	// ---- refusals
	Mode("refuse");
	auto refuse_call = Call(server.Url());
	auto refused = client.Decide(refuse_call);
	ExpectRefusal(refused, DecideResult::Status::Refuse, false, "table lake.main.t is not granted",
	              "a refuse verdict carries the manager's reason");
	requests = server.Requests();
	Expect(client.Decide(refuse_call).cached && server.Requests() == requests, "a refusal for good is cached");
	Mode("refuse-retry");
	auto retry_call = Call(server.Url());
	ExpectRefusal(client.Decide(retry_call), DecideResult::Status::Refuse, true, "table lake.main.t is not granted",
	              "a retryable refusal");
	requests = server.Requests();
	client.Decide(retry_call);
	Expect(server.Requests() == requests + 1, "a retryable refusal is not cached");

	// ---- status codes and transport
	Mode("401");
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Refuse, false,
	              "authorization refused: signature does not verify", "a 401 for the request signature");
	// A handle from another node fails its MAC first: "handle does not verify" (CONTRACT.md Addendum 4
	// item 3).
	Mode("401-handle");
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Refuse, false,
	              "authorization refused: handle refused: handle does not verify",
	              "a 401 for a handle that does not verify (another node's, say)");
	Mode("503");
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Unavailable, true,
	              "authorization unavailable: authorization context expired; retry", "a 503");
	Mode("500");
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Unavailable, true,
	              "authorization unavailable", "a 500");
	Mode("drop");
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Unavailable, true,
	              "authorization unavailable", "a dropped connection");
	ExpectRefusal(client.Decide(Call("http://127.0.0.1:" + std::to_string(qod_test::UnusedPort()))),
	              DecideResult::Status::Unavailable, true, "authorization unavailable", "an unreachable replica");
	requests = server.Requests();
	ExpectRefusal(client.Decide(Call("https://127.0.0.1:20900")), DecideResult::Status::Refuse, false,
	              "authorization refused: the handle's replica cannot be called (not a plain http:// URL)",
	              "an https replica");
	Expect(server.Requests() == requests, "and no request was made");

	// ---- verdicts that do not verify
	struct Bad {
		const char *mode;
		const char *needle;
	};
	for (auto &bad : {Bad {"bad-mac", "verdict MAC does not verify"},
	                  Bad {"garbage", "malformed verdict"},
	                  Bad {"other-fingerprint", "verdict is for another description"},
	                  Bad {"other-principal", "verdict is for another principal"},
	                  Bad {"other-version", "verdict is for another policy version"},
	                  Bad {"other-ctx", "verdict is for another session context"},
	                  Bad {"expired", "verdict expired"},
	                  Bad {"row-filters", "verdict carries row filters or masks"},
	                  Bad {"masks", "verdict carries row filters or masks"}}) {
		Mode(bad.mode);
		ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Refuse, false,
		              std::string("authorization refused: the verdict does not verify: ") + bad.needle, bad.mode);
	}

	// ---- screen verdicts are fetched every time
	Mode("allow");
	auto screen = Call(server.Url(), "screen");
	Expect(client.Decide(screen).status == DecideResult::Status::Allow, "a screen allow");
	requests = server.Requests();
	Expect(client.Decide(screen).status == DecideResult::Status::Allow && server.Requests() == requests + 1,
	       "a screen verdict is never cached");

	// ---- chunked answers and a replica URL with a path prefix
	Mode("chunked");
	Expect(client.Decide(Call(server.Url())).status == DecideResult::Status::Allow, "a chunked verdict");
	Mode("allow");
	Expect(client.Decide(Call(server.Url() + "/qod/")).status == DecideResult::Status::Allow &&
	           server.Last().path == "/qod/api/internal/authz/decide",
	       "the decide path goes under the replica's own path");

	// ---- limits from the env
	auto limits = qod::ParseAuthzLimits([](const char *) { return std::string(); });
	Expect(limits.decide_timeout_ms == 2000 && limits.cache_entries == 10000, "the defaults");
	limits = qod::ParseAuthzLimits([](const char *name) {
		return std::string(name) == "QOD_AUTHZ_DECIDE_TIMEOUT_MS" ? std::string("500") : std::string("0");
	});
	Expect(limits.decide_timeout_ms == 500 && limits.cache_entries == 0, "set values, 0 entries allowed");
	for (auto bad : {"0", "60001", "abc", "-1", "5ms"}) {
		try {
			qod::ParseAuthzLimits([bad](const char *name) {
				return std::string(name) == "QOD_AUTHZ_DECIDE_TIMEOUT_MS" ? std::string(bad) : std::string();
			});
			Expect(false, std::string("timeout '") + bad + "' is refused");
		} catch (std::invalid_argument &error) {
			Expect(std::string(error.what()).find("QOD_AUTHZ_DECIDE_TIMEOUT_MS must be a number") == 0,
			       "the error names the variable");
		}
	}
	try {
		qod::ParseAuthzLimits([](const char *name) {
			return std::string(name) == "QOD_AUTHZ_CACHE_ENTRIES" ? std::string("-1") : std::string();
		});
		Expect(false, "a negative cache size is refused");
	} catch (std::invalid_argument &) {
	}

	// ---- last: a slow replica (the test server stays busy with it after the client gave up)
	Mode("slow");
	auto started = std::chrono::steady_clock::now();
	ExpectRefusal(client.Decide(Call(server.Url())), DecideResult::Status::Unavailable, true,
	              "authorization unavailable", "a replica slower than QOD_AUTHZ_DECIDE_TIMEOUT_MS");
	Expect(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(900), "it gives up at the timeout");

	if (failures == 0) {
		std::cout << "decide_client_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
