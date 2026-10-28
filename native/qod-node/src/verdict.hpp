#pragma once

#include <cstddef>
#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>

namespace qod {

//! A verified verdict: the manager's decision on one description, sent like a handle (decision 14) as
//! `<base64url(verdict JSON)>.<lowercase hex HMAC-SHA256(node key, verdict bytes)>`, the JSON
//! {"v":1,"kind","decision","reason","retryable","principal","policyVersion","ctx","fingerprint",
//!  "expiresAt","rowFilters":[],"masks":[]}. Other keys are ignored.
struct Verdict {
	//! "plan" or "screen".
	std::string kind;
	//! decision == "allow"; "refuse" otherwise.
	bool allow = false;
	std::string reason;
	bool retryable = false;
	//! The principal id the verdict was decided for.
	std::string principal;
	int64_t policy_version = 0;
	//! The session context digest (decision 22).
	std::string ctx;
	//! Lowercase hex SHA-256 of the description's canonical bytes.
	std::string fingerprint;
	//! Epoch milliseconds.
	int64_t expires_at = 0;
};

//! What a verdict must answer: the request's kind and fingerprint, and the connection's installed
//! principal id, policy version and session context digest.
struct VerdictExpectation {
	std::string kind;
	std::string fingerprint;
	std::string principal;
	int64_t policy_version = 0;
	std::string ctx;
};

//! How long after its expiresAt a screen verdict is still accepted (CONTRACT.md Addendum 4 item 5): the
//! manager stamps expiresAt = now, and the clocks of the manager and the node may differ by this much.
constexpr int64_t kMaxScreenVerdictAgeMs = 60 * 1000;

//! Verifies `text` with this node's 32-byte `node_key` against `expected` at `now_ms` and returns what
//! it carries. Throws std::invalid_argument naming the first failed check: the format, the MAC, the
//! shape, the version, kind, fingerprint, principal, policy version, ctx, expiry (a plan verdict while
//! expiresAt > now; a screen verdict, which carries expiresAt = now and is used once, at once, while
//! expiresAt >= now - kMaxScreenVerdictAgeMs), or a non-empty rowFilters or masks (reserved for part 2:
//! refused until a node enforces them).
Verdict VerifyVerdict(const std::string &text, const std::string &node_key, const VerdictExpectation &expected,
                      int64_t now_ms);

//! How long the cache keeps a verdict at most, whatever its expiresAt (an OPA tenant's cache TTL can be
//! longer than a minute; a far-future expiry must not pin a verdict).
constexpr int64_t kMaxCachedVerdictMs = 5 * 60 * 1000;

//! Plan verdicts by principal id, policy version, session context digest and fingerprint (decision
//! 22), the least recently used one evicted first. Thread safe: quack serves its connections on several
//! threads.
class VerdictCache {
public:
	//! At most `capacity` entries; 0 caches nothing.
	explicit VerdictCache(size_t capacity);

	//! True, with `found`, when a verdict is cached for the key and has not expired at `now_ms`; an
	//! expired entry is dropped. A hit makes the entry the most recently used.
	bool Find(const std::string &principal, int64_t policy_version, const std::string &ctx,
	          const std::string &fingerprint, int64_t now_ms, Verdict &found);

	//! Caches `verdict` until min(expiresAt, now_ms + kMaxCachedVerdictMs), evicting the least recently
	//! used entry past capacity. Does not cache a screen verdict, a retryable refusal or a verdict
	//! already expired at `now_ms`.
	void Put(const Verdict &verdict, int64_t now_ms);

	size_t Size();

private:
	struct Entry {
		std::string key;
		Verdict verdict;
		int64_t until = 0;
	};

	static std::string Key(const std::string &principal, int64_t policy_version, const std::string &ctx,
	                       const std::string &fingerprint);

	std::mutex lock_;
	size_t capacity_;
	//! Most recently used first.
	std::list<Entry> entries_;
	std::unordered_map<std::string, std::list<Entry>::iterator> index_;
};

} // namespace qod
