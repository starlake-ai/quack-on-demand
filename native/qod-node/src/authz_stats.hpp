#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace qod {

//! This node's authorization counters since it started (decision 26), read by
//! system.main.qod_authz_stats() for the manager's health probe, which exports their increases. Thread
//! safe: every connection's hooks and the shared decide client count into them.
struct AuthzStats {
	//! Plan decisions the verdict cache answered: no decide call. A cached refusal counts here only.
	std::atomic<int64_t> plan_cache_hits {0};
	//! Plan decisions the cache did not hold, each followed by a decide call.
	std::atomic<int64_t> plan_cache_misses {0};
	//! Decisions asked of the issuing replica, plan and screen; one whose replica URL cannot be called is
	//! refused without a request, and counts too.
	std::atomic<int64_t> decide_calls {0};
	//! Decide calls answered unavailable: a transport failure, a timeout, a 503 or any other status.
	std::atomic<int64_t> decide_unavailable {0};
	//! Decide calls answered with a refusal: a refuse verdict, a 401, a verdict that does not verify, a
	//! replica URL that cannot be called.
	std::atomic<int64_t> decide_refused {0};
	//! Statements the node refused alone, without a decide call, on a connection whose principal is
	//! current: the parse screen's content rules, the describer, the call-name walk of a statement that
	//! failed to bind, a write to a read-only catalog. Never a refusal for want of a principal or of a
	//! valid session handle: the health probe's canary is one, on every tick.
	std::atomic<int64_t> local_refusals {0};
	//! Statements quack submitted to the parse screen (qod_screen): client statements, the manager's
	//! preludes and its probe's statements.
	std::atomic<int64_t> screen_calls {0};
	//! Plan verdicts the verdict cache evicted for room (least recently used first), never one it replaced
	//! or one that expired: a count that keeps rising means QOD_AUTHZ_CACHE_ENTRIES is too small.
	std::atomic<int64_t> verdict_cache_evictions {0};
};

//! What qod_authz_stats() reports beside the counters: sizes as of the read (Task 11 addendum 6), which go
//! up and down.
struct AuthzStatsSizes {
	//! Plan verdicts the verdict cache holds, expired ones not looked up since included.
	int64_t verdict_cache_size = 0;
	//! S3 credential sets the node keeps (decision 23).
	int64_t credential_sets_size = 0;
};

//! The columns of qod_authz_stats(), in order: the manager reads them in this order
//! (NodeAuthzStats.Columns, which a spec pins against this list). Task 8's seven counters first, then
//! verdict_cache_size, verdict_cache_evictions and credential_sets_size: a new column is appended, never
//! inserted.
const std::vector<std::string> &AuthzStatsColumns();

//! Every counter of `stats` and every size of `sizes`, in AuthzStatsColumns' order. Each is loaded on its
//! own: the row is not one instant, which an increase does not need.
std::vector<int64_t> AuthzStatsValues(const AuthzStats &stats, const AuthzStatsSizes &sizes);

} // namespace qod
