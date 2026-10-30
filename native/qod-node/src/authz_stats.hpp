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
};

//! The columns of qod_authz_stats(), in order: the manager reads them in this order
//! (NodeAuthzStats.Counters, which a spec pins against this list).
const std::vector<std::string> &AuthzStatsColumns();

//! Every counter of `stats`, in AuthzStatsColumns' order. Each is loaded on its own: the row is not one
//! instant, which an increase does not need.
std::vector<int64_t> AuthzStatsValues(const AuthzStats &stats);

} // namespace qod
