#include "authz_stats.hpp"

namespace qod {

const std::vector<std::string> &AuthzStatsColumns() {
	static const std::vector<std::string> columns = {"plan_cache_hits",    "plan_cache_misses", "decide_calls",
	                                                 "decide_unavailable", "decide_refused",    "local_refusals",
	                                                 "screen_calls"};
	return columns;
}

std::vector<int64_t> AuthzStatsValues(const AuthzStats &stats) {
	return {stats.plan_cache_hits.load(),    stats.plan_cache_misses.load(), stats.decide_calls.load(),
	        stats.decide_unavailable.load(), stats.decide_refused.load(),    stats.local_refusals.load(),
	        stats.screen_calls.load()};
}

} // namespace qod
