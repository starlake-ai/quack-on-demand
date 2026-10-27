// Tests for credentials: the checks of a replica's answer (VerifyCredentials) and the client against a
// real HTTP replica on 127.0.0.1 (the test server): the request as the manager reads it, the cache by
// principal and digest, and every answer the node must refuse or retry. Built with -DQOD_NODE_TESTS=ON
// and run by CTest.

#include "credentials.hpp"
#include "crypto.hpp"
#include "test_credentials.hpp"
#include "test_http_server.hpp"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <iostream>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

using qod::CredentialFetch;
using qod_test::CredentialEntry;
using qod_test::CredentialsJson;
using qod_test::kKey;
using qod_test::kNode;
using qod_test::SignCredentials;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

std::atomic<int64_t> now_ms {1761200000000};

//! How the replica answers the next requests.
struct Replica {
	std::mutex lock;
	qod_test::HttpAnswer answer;
};
Replica replica;

void Answering(int status, const std::string &body, int delay_ms = 0, bool drop = false) {
	std::lock_guard<std::mutex> guard(replica.lock);
	replica.answer = qod_test::HttpAnswer();
	replica.answer.status = status;
	replica.answer.body = body;
	replica.answer.delay_ms = delay_ms;
	replica.answer.drop = drop;
}

qod_test::HttpAnswer Answer(const qod_test::HttpRequest &) {
	std::lock_guard<std::mutex> guard(replica.lock);
	return replica.answer;
}

//! A credential whose id starts with eight `digit`s, under `scope`.
CredentialEntry Entry(char digit, const std::string &scope) {
	CredentialEntry entry;
	entry.id = std::string(8, digit) + std::string(24, '0');
	entry.scope = scope;
	return entry;
}

//! VerifyCredentials's refusal of `text` for the digest `creds`, or "accepted".
std::string Refusal(const std::string &text, const std::string &creds) {
	try {
		qod::VerifyCredentials(text, kKey, creds);
		return "accepted";
	} catch (std::invalid_argument &error) {
		return error.what();
	}
}

void ExpectFetch(const CredentialFetch &fetch, CredentialFetch::Status status, const std::string &message,
                 const std::string &what) {
	if (fetch.status != status || fetch.Message() != message) {
		std::cerr << "FAIL: " << what << ": status " << static_cast<int>(fetch.status) << ", \"" << fetch.Message()
		          << "\"" << std::endl;
		failures++;
	}
}

} // namespace

int main() {
	auto d1 = qod_test::Digest('1');
	auto alice = Entry('a', "s3://lake/alice/");

	// ---- an answer verifies; absent members take their defaults
	auto shared = Entry('b', "s3://lake/shared/");
	shared.session_token = "alice-token";
	shared.region = "eu-west-3";
	shared.endpoint = "minio.internal:9000";
	shared.url_style = "vhost";
	shared.use_ssl = false;
	auto set = qod::VerifyCredentials(SignCredentials(CredentialsJson(d1, {alice, shared})), kKey, d1);
	Expect(set.creds == d1 && set.credentials.size() == 2, "a set of two verifies");
	if (set.credentials.size() == 2) {
		auto &first = set.credentials[0];
		Expect(first.id == alice.id && first.scope == "s3://lake/alice/" && first.key_id == "AKIAALICE" &&
		           first.secret == "alice-secret" && first.session_token.empty() && first.region == "us-east-1" &&
		           first.endpoint.empty() && first.url_style == "vhost" && first.use_ssl,
		       "absent members: no session token, us-east-1, AWS's own endpoint and its vhost style");
		auto &second = set.credentials[1];
		Expect(second.session_token == "alice-token" && second.region == "eu-west-3" &&
		           second.endpoint == "minio.internal:9000" && second.url_style == "vhost" && !second.use_ssl,
		       "every member as sent");
	}
	auto minio = Entry('c', "s3://lake/c/");
	minio.endpoint = "127.0.0.1:9000";
	auto path_style = qod::VerifyCredentials(SignCredentials(CredentialsJson(d1, {minio})), kKey, d1);
	Expect(path_style.credentials.size() == 1 && path_style.credentials[0].url_style == "path",
	       "path style by default for an endpoint of its own");
	Expect(qod::VerifyCredentials(SignCredentials(CredentialsJson(d1, {})), kKey, d1).credentials.empty(),
	       "an empty set verifies");
	Expect(qod::UserSecretName(alice.id) == "qod_uc_aaaaaaaa", "the secret name is qod_uc_ and the id's first 8");

	// ---- answers that do not verify
	auto good = CredentialsJson(d1, {alice});
	Expect(Refusal(SignCredentials(good, std::string(32, 'x')), d1) == "answer MAC does not verify", "another key");
	Expect(Refusal("not an answer", d1) == "malformed answer", "garbage");
	Expect(Refusal(qod::ToBase64Url(good) + "." + std::string(63, 'a'), d1) == "malformed answer MAC", "a short MAC");
	Expect(Refusal(SignCredentials("{\"credentials\":[],\"creds\":\"" + d1 + "\",\"v\":2}"), d1) ==
	           "unsupported answer version",
	       "version 2");
	Expect(Refusal(SignCredentials(good), qod_test::Digest('2')) == "answer is for another credential set",
	       "an answer for another digest");
	Expect(Refusal(SignCredentials(CredentialsJson(qod::kUnavailableCreds, {alice})), qod::kUnavailableCreds) ==
	           "answer is for no credential set",
	       "an answer for the unavailable digest, which the replica never signs");
	auto no_key = good;
	auto key_member = std::string("\"keyId\":\"AKIAALICE\",");
	no_key.replace(no_key.find(key_member), key_member.size(), "");
	Expect(Refusal(SignCredentials(no_key), d1) == "malformed answer: \"keyId\" is missing or null",
	       "a credential without keyId");
	std::vector<CredentialEntry> many;
	for (int i = 0; i < 33; i++) {
		char id[33];
		std::snprintf(id, sizeof(id), "%08x%024x", i, 0);
		auto entry = Entry('0', "s3://lake/" + std::to_string(i) + "/");
		entry.id = id;
		many.push_back(entry);
	}
	Expect(Refusal(SignCredentials(CredentialsJson(d1, many)), d1) == "answer holds more than 32 credentials",
	       "33 credentials");
	std::vector<std::pair<std::function<void(CredentialEntry &)>, std::string>> bad_entries {
	    {[](CredentialEntry &e) { e.id = "AAAAAAAA" + std::string(24, '0'); }, "malformed credential id"},
	    {[](CredentialEntry &e) { e.id = "aaaa"; }, "malformed credential id"},
	    {[](CredentialEntry &e) { e.scope = "s3://lake/alice"; }, "credential scope is not an s3:// prefix"},
	    {[](CredentialEntry &e) { e.scope = "gs://lake/alice/"; }, "credential scope is not an s3:// prefix"},
	    {[](CredentialEntry &e) { e.scope = "s3:///"; }, "credential scope is not an s3:// prefix"},
	    {[](CredentialEntry &e) { e.scope = "s3://lake/al ice/"; }, "credential scope is not an s3:// prefix"},
	    {[](CredentialEntry &e) { e.key_id = ""; }, "credential has no key id"},
	    {[](CredentialEntry &e) { e.secret = ""; }, "credential has no secret"},
	    {[](CredentialEntry &e) { e.url_style = "virtual"; }, "credential URL style is not path or vhost"}};
	for (auto &bad : bad_entries) {
		auto entry = alice;
		bad.first(entry);
		Expect(Refusal(SignCredentials(CredentialsJson(d1, {entry})), d1) == bad.second, bad.second);
	}
	auto twin = Entry('a', "s3://lake/other/");
	twin.id = "aaaaaaaa" + std::string(23, '0') + "1";
	Expect(Refusal(SignCredentials(CredentialsJson(d1, {alice, twin})), d1) == "two credentials share a secret name",
	       "two ids with the same first 8 digits");

	// ---- the client: the request as the manager reads it
	qod_test::TestHttpServer server(Answer);
	auto url = server.Url();
	qod::CredentialClient client(kNode, kKey, qod::AuthzLimits {300, 100}, [] { return now_ms.load(); });
	Answering(200, SignCredentials(good));
	auto fetched = client.Fetch(url, "the-handle", "u-alice", d1);
	Expect(fetched.status == CredentialFetch::Status::Ok && !fetched.cached && fetched.set.credentials.size() == 1,
	       "a fetch");
	auto request = server.Last();
	Expect(request.method == "POST" && request.path == "/api/internal/authz/credentials",
	       "POST to the credentials path");
	Expect(request.body == "{\"handle\":\"the-handle\"}", "the body is canonical JSON with the handle");
	Expect(request.headers["content-type"] == "application/json" && request.headers["x-qod-node"] == kNode,
	       "Content-Type and X-QoD-Node");
	Expect(request.headers["x-qod-signature"] == qod::Hex(qod::HmacSha256(kKey, request.body)),
	       "X-QoD-Signature is the node key's HMAC of the body bytes");

	// ---- the cache: by principal and digest, for kMaxCachedCredentialsMs
	auto requests = server.Requests();
	auto again = client.Fetch(url, "another-handle", "u-alice", d1);
	Expect(again.status == CredentialFetch::Status::Ok && again.cached && server.Requests() == requests,
	       "the same principal and digest are served from the cache");
	client.Fetch(url, "a-handle", "u-bob", d1);
	Expect(server.Requests() == requests + 1, "another principal goes to the replica");
	Answering(200, SignCredentials(CredentialsJson(qod_test::Digest('2'), {alice})));
	Expect(client.Fetch(url, "a-handle", "u-alice", qod_test::Digest('2')).status == CredentialFetch::Status::Ok &&
	           server.Requests() == requests + 2,
	       "another digest goes to the replica");
	now_ms += qod::kMaxCachedCredentialsMs;
	Answering(200, SignCredentials(good));
	Expect(!client.Fetch(url, "a-handle", "u-alice", d1).cached && server.Requests() == requests + 3,
	       "a set is fetched again once its time is up");
	Expect(client.CachedSets() == 3, "three sets kept: alice's two digests and bob's");

	// ---- refusals and retries; none is cached
	Answering(401, "handle refused: handle MAC does not verify");
	ExpectFetch(client.Fetch(url, "h", "u-erin", d1), CredentialFetch::Status::Refuse,
	            "qod-node: S3 credentials refused: handle refused: handle MAC does not verify", "a 401");
	Answering(503, "authorization context expired; retry");
	ExpectFetch(client.Fetch(url, "h", "u-erin", d1), CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: authorization context expired; retry", "a 503");
	Answering(500, "boom");
	ExpectFetch(client.Fetch(url, "h", "u-erin", d1), CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: the replica could not answer", "a 500");
	Answering(200, "", 0, true);
	ExpectFetch(client.Fetch(url, "h", "u-erin", d1), CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: the replica could not be reached", "a dropped connection");
	ExpectFetch(client.Fetch("http://127.0.0.1:" + std::to_string(qod_test::UnusedPort()), "h", "u-erin", d1),
	            CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: the replica could not be reached", "an unreachable replica");
	requests = server.Requests();
	ExpectFetch(client.Fetch("https://127.0.0.1:20900", "h", "u-erin", d1), CredentialFetch::Status::Refuse,
	            "qod-node: S3 credentials refused: the handle's replica cannot be called (not a plain http:// URL)",
	            "an https replica");
	Expect(server.Requests() == requests, "and no request was made");
	Answering(200, SignCredentials(CredentialsJson(qod_test::Digest('9'), {alice})));
	ExpectFetch(client.Fetch(url, "h", "u-erin", d1), CredentialFetch::Status::Refuse,
	            "qod-node: S3 credentials refused: the answer does not verify: answer is for another credential set",
	            "an answer for another digest");
	Answering(503, "S3 credentials unavailable; retry");
	ExpectFetch(client.Fetch(url, "h", "u-erin", qod::kUnavailableCreds), CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: S3 credentials unavailable; retry",
	            "the unavailable digest: the replica answers 503");
	Answering(200, SignCredentials(CredentialsJson(qod::kUnavailableCreds, {alice})));
	ExpectFetch(client.Fetch(url, "h", "u-erin", qod::kUnavailableCreds), CredentialFetch::Status::Refuse,
	            "qod-node: S3 credentials refused: the answer does not verify: answer is for no credential set",
	            "the unavailable digest signed: refused all the same");
	Answering(200, SignCredentials(good));
	auto healed = client.Fetch(url, "h", "u-erin", d1);
	Expect(healed.status == CredentialFetch::Status::Ok && !healed.cached, "no failure was cached");
	Expect(client.CachedSets() == 4, "only the sets that verified are kept: alice's two, bob's, erin's");

	// ---- last: a slow replica (the test server stays busy with it after the client gave up)
	Answering(200, SignCredentials(good), 1000);
	auto started = std::chrono::steady_clock::now();
	ExpectFetch(client.Fetch(url, "h", "u-frank", d1), CredentialFetch::Status::Unavailable,
	            "qod-node: S3 credentials unavailable, retry: the replica could not be reached",
	            "a replica slower than QOD_AUTHZ_DECIDE_TIMEOUT_MS");
	Expect(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(900), "it gives up at the timeout");

	if (failures == 0) {
		std::cout << "credentials_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
