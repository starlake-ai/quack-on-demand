// Unit tests for verdict: VerifyVerdict's checks, the golden verdicts VerdictsSpec (Scala) signs
// (tests/golden/verdict-v1.txt, the one argument), and the verdict cache (keys, expiry, LRU, threads).
// Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "crypto.hpp"
#include "test_verdicts.hpp"
#include "verdict.hpp"

#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

using qod_test::kKey;
using qod_test::SignVerdict;
using qod_test::VerdictFields;
using qod_test::VerdictJson;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

const int64_t kNow = 1761200000000;
const std::string kCtx(64, 'c');

std::string Fingerprint(const std::string &description) {
	return qod::Hex(qod::Sha256(description));
}

qod::VerdictExpectation Expected(const std::string &kind = "plan") {
	return qod::VerdictExpectation {kind, Fingerprint("{\"v\":1}"), "u-alice", 1, kCtx};
}

VerdictFields Good() {
	VerdictFields fields;
	fields.fingerprint = Fingerprint("{\"v\":1}");
	fields.expires_at = kNow + 60000;
	return fields;
}

void ExpectRefused(const std::string &text, const qod::VerdictExpectation &expected, const std::string &needle,
                   const std::string &what) {
	try {
		qod::VerifyVerdict(text, kKey, expected, kNow);
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

qod::Verdict Plan(const std::string &principal, int64_t version, const std::string &ctx,
                  const std::string &fingerprint, int64_t expires_at) {
	qod::Verdict verdict;
	verdict.kind = "plan";
	verdict.allow = true;
	verdict.principal = principal;
	verdict.policy_version = version;
	verdict.ctx = ctx;
	verdict.fingerprint = fingerprint;
	verdict.expires_at = expires_at;
	return verdict;
}

// The golden verdicts VerdictsSpec (Scala) signs from fixed inputs: both sides agree on the verdict
// format, its canonical bytes and its MAC byte for byte (Task 6 writes the file).
void VerifyGolden(const std::string &path) {
	std::ifstream in(path);
	if (!in) {
		std::cerr << "FAIL: cannot read " << path << std::endl;
		failures++;
		return;
	}
	std::string line, key_hex, description, fingerprint, ctx, allow, refuse;
	while (std::getline(in, line)) {
		if (line.rfind("key=", 0) == 0) {
			key_hex = line.substr(4);
		} else if (line.rfind("description=", 0) == 0) {
			description = line.substr(12);
		} else if (line.rfind("fingerprint=", 0) == 0) {
			fingerprint = line.substr(12);
		} else if (line.rfind("ctx=", 0) == 0) {
			ctx = line.substr(4);
		} else if (line.rfind("allow=", 0) == 0) {
			allow = line.substr(6);
		} else if (line.rfind("refuse=", 0) == 0) {
			refuse = line.substr(7);
		}
	}
	Expect(qod::Hex(qod::Sha256(description)) == fingerprint, "the golden fingerprint is the description's SHA-256");
	Expect(qod::Hex(qod::Sha256("{}")) == ctx, "the golden ctx is the SHA-256 of {}");
	auto key = qod::FromHex(key_hex);
	qod::VerdictExpectation expected {"plan", fingerprint, "u-1", 42, ctx};
	try {
		auto verdict = qod::VerifyVerdict(allow, key, expected, 1761200000000);
		Expect(verdict.allow && verdict.reason.empty() && !verdict.retryable && verdict.expires_at == 1761200060000,
		       "the golden allow");
		auto refused = qod::VerifyVerdict(refuse, key, expected, 1761200000000);
		Expect(!refused.allow && refused.reason == "function lower is not classified" && !refused.retryable,
		       "the golden refusal");
	} catch (std::invalid_argument &error) {
		std::cerr << "FAIL: a golden verdict does not verify: " << error.what() << std::endl;
		failures++;
	}
	try {
		qod::VerifyVerdict(allow, std::string(32, '\x42'), expected, 1761200000000);
		std::cerr << "FAIL: the golden allow verifies under another key" << std::endl;
		failures++;
	} catch (std::invalid_argument &) {
	}
}

} // namespace

int main(int argc, char **argv) {
	// Run without the golden file, the cross-language checks would be skipped and the run still pass.
	if (argc < 2) {
		std::cerr << "FAIL: no golden file: run verdict_test with the path of tests/golden/verdict-v1.txt" << std::endl;
		return 1;
	}
	VerifyGolden(argv[1]);

	// ---- a verdict that answers the request
	auto allowed = qod::VerifyVerdict(SignVerdict(VerdictJson(Good())), kKey, Expected(), kNow);
	Expect(allowed.allow && allowed.expires_at == kNow + 60000 && allowed.ctx == kCtx, "an allow verdict");
	auto refusal = Good();
	refusal.decision = "refuse";
	refusal.reason = "table lake.main.t is not granted";
	auto refused = qod::VerifyVerdict(SignVerdict(VerdictJson(refusal)), kKey, Expected(), kNow);
	Expect(!refused.allow && refused.reason == "table lake.main.t is not granted" && !refused.retryable,
	       "a refuse verdict carries its reason");
	auto screen = Good();
	screen.kind = "screen";
	screen.expires_at = kNow - 5;
	Expect(qod::VerifyVerdict(SignVerdict(VerdictJson(screen)), kKey, Expected("screen"), kNow).allow,
	       "a screen verdict stamped a moment ago (expiresAt = the manager's now) is used at once");
	screen.expires_at = kNow - qod::kMaxScreenVerdictAgeMs;
	Expect(qod::VerifyVerdict(SignVerdict(VerdictJson(screen)), kKey, Expected("screen"), kNow).allow,
	       "a screen verdict is accepted until kMaxScreenVerdictAgeMs after its expiresAt");
	screen.expires_at = kNow - qod::kMaxScreenVerdictAgeMs - 1;
	ExpectRefused(SignVerdict(VerdictJson(screen)), Expected("screen"), "verdict expired",
	              "a screen verdict older than kMaxScreenVerdictAgeMs (a captured one replayed)");

	// ---- every check
	auto text = SignVerdict(VerdictJson(Good()));
	ExpectRefused(SignVerdict(VerdictJson(Good()), std::string(32, '\x43')), Expected(), "MAC does not verify",
	              "signed with another node's key");
	auto tampered = text;
	tampered[3] = tampered[3] == 'A' ? 'B' : 'A';
	ExpectRefused(tampered, Expected(), "", "a tampered payload");
	auto upper = text.substr(0, text.find('.') + 1);
	for (auto c : text.substr(text.find('.') + 1)) {
		upper += static_cast<char>(c >= 'a' && c <= 'f' ? c - 'a' + 'A' : c);
	}
	ExpectRefused(upper, Expected(), "malformed verdict MAC", "an uppercase MAC");
	ExpectRefused(text + ".x", Expected(), "malformed verdict", "a third part");
	ExpectRefused("garbage", Expected(), "malformed verdict", "no MAC at all");
	auto v2 = VerdictJson(Good());
	v2.replace(v2.find("\"v\":1"), 5, "\"v\":2");
	ExpectRefused(SignVerdict(v2), Expected(), "unsupported verdict version", "another verdict version");
	auto other_kind = Good();
	other_kind.kind = "screen";
	ExpectRefused(SignVerdict(VerdictJson(other_kind)), Expected(), "another kind", "a screen verdict for a plan");
	auto other_print = Good();
	other_print.fingerprint = Fingerprint("{\"v\":2}");
	ExpectRefused(SignVerdict(VerdictJson(other_print)), Expected(), "another description", "another fingerprint");
	auto other_principal = Good();
	other_principal.principal = "u-mallory";
	ExpectRefused(SignVerdict(VerdictJson(other_principal)), Expected(), "another principal", "another principal");
	auto other_version = Good();
	other_version.policy_version = 2;
	ExpectRefused(SignVerdict(VerdictJson(other_version)), Expected(), "another policy version",
	              "another policy version");
	auto other_ctx = Good();
	other_ctx.ctx = std::string(64, 'd');
	ExpectRefused(SignVerdict(VerdictJson(other_ctx)), Expected(), "another session context",
	              "a verdict for another session context (a restricted PAT reusing a full session's allow)");
	auto expired = Good();
	expired.expires_at = kNow;
	ExpectRefused(SignVerdict(VerdictJson(expired)), Expected(), "verdict expired", "an expired plan verdict");
	auto filters = Good();
	filters.row_filters = "[{\"table\":\"t\",\"filter\":\"x\"}]";
	ExpectRefused(SignVerdict(VerdictJson(filters)), Expected(), "row filters or masks", "a row filter");
	auto masks = Good();
	masks.masks = "[\"c\"]";
	ExpectRefused(SignVerdict(VerdictJson(masks)), Expected(), "row filters or masks", "a mask");
	auto maybe = Good();
	maybe.decision = "maybe";
	ExpectRefused(SignVerdict(VerdictJson(maybe)), Expected(), "unknown decision", "an unknown decision");
	auto no_reason = VerdictJson(Good());
	no_reason.replace(no_reason.find(",\"reason\":\"\""), 12, "");
	ExpectRefused(SignVerdict(no_reason), Expected(), "malformed verdict", "a verdict without its reason");

	// ---- the cache: key, LRU, expiry
	auto a = Fingerprint("a");
	auto b = Fingerprint("b");
	auto c = Fingerprint("c");
	qod::Verdict found;
	qod::VerdictCache lru(2);
	lru.Put(Plan("u-1", 1, kCtx, a, kNow + 60000), kNow);
	lru.Put(Plan("u-1", 1, kCtx, b, kNow + 60000), kNow);
	Expect(lru.Find("u-1", 1, kCtx, a, kNow, found), "a cached verdict");
	lru.Put(Plan("u-1", 1, kCtx, c, kNow + 60000), kNow);
	Expect(lru.Size() == 2, "the cache stays at its capacity");
	Expect(!lru.Find("u-1", 1, kCtx, b, kNow, found), "the least recently used entry was evicted");
	Expect(lru.Find("u-1", 1, kCtx, a, kNow, found) && lru.Find("u-1", 1, kCtx, c, kNow, found),
	       "the recently used ones stay");
	Expect(!lru.Find("u-2", 1, kCtx, a, kNow, found), "another principal misses");
	Expect(!lru.Find("u-1", 2, kCtx, a, kNow, found), "another policy version misses");
	Expect(!lru.Find("u-1", 1, std::string(64, 'd'), a, kNow, found), "another session context misses");
	Expect(!lru.Find("u-1", 1, kCtx, b, kNow, found), "another fingerprint misses");
	Expect(!lru.Find("u-11", 1, kCtx, a, kNow, found) && !lru.Find("u", 11, kCtx, a, kNow, found),
	       "an id cannot run into the policy version");

	// Evictions: an entry pushed out for room (LRU), never one replaced or one that expired.
	qod::VerdictCache counted(2);
	Expect(counted.Put(Plan("u-1", 1, kCtx, a, kNow + 60000), kNow) == 0 &&
	           counted.Put(Plan("u-1", 1, kCtx, b, kNow + 60000), kNow) == 0,
	       "no eviction below capacity");
	Expect(counted.Put(Plan("u-1", 1, kCtx, a, kNow + 60000), kNow) == 0, "replacing an entry evicts none");
	Expect(counted.Put(Plan("u-1", 1, kCtx, c, kNow + 60000), kNow) == 1, "one entry evicted past capacity");
	Expect(counted.Size() == 2, "and the cache stays at its capacity");
	auto uncached = Plan("u-1", 1, kCtx, a, kNow + 60000);
	uncached.kind = "screen";
	Expect(counted.Put(uncached, kNow) == 0, "a verdict not cached evicts none");
	qod::VerdictCache lapsing(2);
	lapsing.Put(Plan("u-1", 1, kCtx, a, kNow + 1000), kNow);
	Expect(!lapsing.Find("u-1", 1, kCtx, a, kNow + 1000, found) && lapsing.Size() == 0 &&
	           lapsing.Put(Plan("u-1", 1, kCtx, b, kNow + 60000), kNow + 1000) == 0,
	       "an expired entry dropped on lookup is no eviction");

	qod::VerdictCache timed(10);
	timed.Put(Plan("u-1", 1, kCtx, a, kNow + 1000), kNow);
	Expect(timed.Find("u-1", 1, kCtx, a, kNow + 999, found), "valid until its expiresAt");
	Expect(!timed.Find("u-1", 1, kCtx, a, kNow + 1000, found), "dropped at its expiresAt");
	Expect(timed.Size() == 0, "an expired entry leaves the cache on lookup");
	timed.Put(Plan("u-1", 1, kCtx, b, kNow + 60 * 60 * 1000), kNow);
	Expect(timed.Find("u-1", 1, kCtx, b, kNow + qod::kMaxCachedVerdictMs - 1, found), "a long verdict is cached");
	Expect(!timed.Find("u-1", 1, kCtx, b, kNow + qod::kMaxCachedVerdictMs, found),
	       "but for kMaxCachedVerdictMs at most");

	qod::VerdictCache picky(10);
	auto screen_verdict = Plan("u-1", 1, kCtx, a, kNow + 60000);
	screen_verdict.kind = "screen";
	picky.Put(screen_verdict, kNow);
	auto retry = Plan("u-1", 1, kCtx, b, kNow + 60000);
	retry.allow = false;
	retry.retryable = true;
	picky.Put(retry, kNow);
	picky.Put(Plan("u-1", 1, kCtx, c, kNow), kNow);
	Expect(picky.Size() == 0, "no screen verdict, retryable refusal or expired verdict is cached");
	auto final_refusal = retry;
	final_refusal.retryable = false;
	picky.Put(final_refusal, kNow);
	Expect(picky.Find("u-1", 1, kCtx, b, kNow, found) && !found.allow, "a refusal for good is cached");
	qod::VerdictCache none(0);
	none.Put(Plan("u-1", 1, kCtx, a, kNow + 60000), kNow);
	Expect(none.Size() == 0, "capacity 0 caches nothing");

	// ---- many threads at once: no crash, the bound holds
	qod::VerdictCache shared(64);
	std::vector<std::thread> threads;
	for (int t = 0; t < 8; t++) {
		threads.emplace_back([&shared, t] {
			qod::Verdict seen;
			for (int i = 0; i < 2000; i++) {
				auto print = qod::Hex(qod::Sha256(std::to_string((t * 7919 + i) % 300)));
				if (i % 3 == 0) {
					shared.Put(Plan("u-1", 1, kCtx, print, kNow + 60000), kNow);
				} else {
					shared.Find("u-1", 1, kCtx, print, kNow, seen);
				}
			}
		});
	}
	for (auto &thread : threads) {
		thread.join();
	}
	Expect(shared.Size() <= 64, "the bound holds under concurrent use");

	if (failures == 0) {
		std::cout << "verdict_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
