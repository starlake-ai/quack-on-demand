#include "decide_client.hpp"

#include "crypto.hpp"
#include "http_post.hpp"
#include "json_writer.hpp"
#include "wire_text.hpp"

#include <cstdlib>
#include <iostream>
#include <stdexcept>

namespace qod {
namespace {

long long LimitValue(const char *name, const std::string &text, long long min, long long max) {
	size_t used = 0;
	long long value = 0;
	try {
		value = std::stoll(text, &used);
	} catch (std::exception &) {
		used = 0;
	}
	if (used != text.size() || value < min || value > max) {
		throw std::invalid_argument(std::string(name) + " must be a number between " + std::to_string(min) + " and " +
		                            std::to_string(max));
	}
	return value;
}

DecideResult Refused(std::string reason) {
	DecideResult result;
	result.status = DecideResult::Status::Refuse;
	result.reason = std::move(reason);
	return result;
}

DecideResult Unavailable(std::string reason) {
	DecideResult result;
	result.status = DecideResult::Status::Unavailable;
	result.reason = std::move(reason);
	result.retryable = true;
	return result;
}

DecideResult FromVerdict(const Verdict &verdict, bool cached) {
	DecideResult result;
	result.has_verdict = true;
	result.verdict = verdict;
	result.cached = cached;
	if (verdict.allow) {
		result.status = DecideResult::Status::Allow;
	} else {
		result.status = DecideResult::Status::Refuse;
		result.reason = verdict.reason;
		result.retryable = verdict.retryable;
	}
	return result;
}

std::string EnvValue(const char *name) {
#ifdef _WIN32
	char *value = nullptr;
	size_t length = 0;
	std::string result;
	if (_dupenv_s(&value, &length, name) == 0 && value) {
		result = value;
	}
	free(value);
	return result;
#else
	auto value = std::getenv(name);
	return value ? value : "";
#endif
}

} // namespace

AuthzLimits ParseAuthzLimits(const std::function<std::string(const char *)> &env) {
	AuthzLimits limits;
	auto timeout = env("QOD_AUTHZ_DECIDE_TIMEOUT_MS");
	if (!timeout.empty()) {
		limits.decide_timeout_ms = LimitValue("QOD_AUTHZ_DECIDE_TIMEOUT_MS", timeout, 1, 60000);
	}
	auto entries = env("QOD_AUTHZ_CACHE_ENTRIES");
	if (!entries.empty()) {
		limits.cache_entries = static_cast<size_t>(LimitValue("QOD_AUTHZ_CACHE_ENTRIES", entries, 0, 10000000));
	}
	return limits;
}

AuthzLimits AuthzLimitsFromEnv() {
	return ParseAuthzLimits(EnvValue);
}

DecideClient::DecideClient(std::string node_id, std::string node_key, AuthzLimits limits,
                           std::function<int64_t()> clock, AuthzStats &stats)
    : node_id_(std::move(node_id)), node_key_(std::move(node_key)), limits_(limits), clock_(std::move(clock)),
      stats_(stats), cache_(limits.cache_entries) {
}

DecideResult DecideClient::Decide(const DecideCall &call) {
	auto fingerprint = Hex(Sha256(call.description));
	if (call.kind == "plan") {
		Verdict cached;
		if (cache_.Find(call.principal.id, call.policy_version, call.ctx, fingerprint, clock_(), cached)) {
			stats_.plan_cache_hits++;
			return FromVerdict(cached, true);
		}
		stats_.plan_cache_misses++;
	}
	stats_.decide_calls++;
	auto result = Ask(call, fingerprint);
	if (result.status == DecideResult::Status::Unavailable) {
		stats_.decide_unavailable++;
	} else if (result.status == DecideResult::Status::Refuse) {
		stats_.decide_refused++;
	}
	if (result.has_verdict) {
		cache_.Put(result.verdict, clock_());
	}
	return result;
}

DecideResult DecideClient::Ask(const DecideCall &call, const std::string &fingerprint) {
	HttpUrl url;
	try {
		url = ParseHttpUrl(call.replica);
	} catch (std::invalid_argument &error) {
		return Refused(std::string("authorization refused: the handle's replica cannot be called (") + error.what() +
		               ")");
	}
	auto body = CanonicalJson(JsonObject({{"description", JsonString(ToBase64Url(call.description))},
	                                      {"handle", JsonString(call.handle)},
	                                      {"kind", JsonString(call.kind)}}));
	HttpReply reply;
	try {
		reply = HttpPost(url, url.path + kDecidePath,
		                 {{"Content-Type", "application/json"},
		                  {"X-QoD-Node", node_id_},
		                  {"X-QoD-Signature", Hex(HmacSha256(node_key_, body))}},
		                 body, limits_.decide_timeout_ms);
	} catch (std::exception &error) {
		std::cerr << "[qod-node] decide call to " << url.authority << " failed: " << error.what() << std::endl;
		return Unavailable("authorization unavailable");
	}
	if (reply.status == 401) {
		return Refused("authorization refused: " + QuotedBody(reply.body));
	}
	if (reply.status == 503) {
		auto said = QuotedBody(reply.body);
		return Unavailable(said.empty() ? "authorization unavailable" : "authorization unavailable: " + said);
	}
	if (reply.status != 200) {
		std::cerr << "[qod-node] decide call to " << url.authority << " answered HTTP " << reply.status << std::endl;
		return Unavailable("authorization unavailable");
	}
	VerdictExpectation expected {call.kind, fingerprint, call.principal.id, call.policy_version, call.ctx};
	try {
		return FromVerdict(VerifyVerdict(TrimBlanks(reply.body), node_key_, expected, clock_()), false);
	} catch (std::invalid_argument &error) {
		return Refused(std::string("authorization refused: the verdict does not verify: ") + error.what());
	}
}

} // namespace qod
