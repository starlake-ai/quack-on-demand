#pragma once

#include "handle.hpp"
#include "verdict.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>

namespace qod {

//! The node's decide limits, from its non-secret env.
struct AuthzLimits {
	//! QOD_AUTHZ_DECIDE_TIMEOUT_MS: one decide call, connect to last byte.
	int64_t decide_timeout_ms = 2000;
	//! QOD_AUTHZ_CACHE_ENTRIES: plan verdicts kept; 0 keeps none.
	size_t cache_entries = 10000;
};

//! AuthzLimits from `env` (a variable's value, empty when unset): QOD_AUTHZ_DECIDE_TIMEOUT_MS 1..60000,
//! QOD_AUTHZ_CACHE_ENTRIES 0..10000000. Throws std::invalid_argument naming a malformed variable (never
//! its value).
AuthzLimits ParseAuthzLimits(const std::function<std::string(const char *)> &env);

//! ParseAuthzLimits over this process's environment.
AuthzLimits AuthzLimitsFromEnv();

//! One decide call: a statement's description, for the principal installed on its connection.
struct DecideCall {
	//! The issuing replica, from the connection's current handle.
	std::string replica;
	//! The connection's current handle, as the manager minted it.
	std::string handle;
	//! "plan" or "screen".
	std::string kind;
	//! The description's canonical JSON bytes.
	std::string description;
	Principal principal;
	int64_t policy_version = 0;
	//! The session context digest the handle carried (decision 22).
	std::string ctx;
};

struct DecideResult {
	enum class Status { Allow, Refuse, Unavailable };

	Status status = Status::Unavailable;
	//! Why, when not Allow; Message() adds the "QoD: " every refusal starts with.
	std::string reason;
	bool retryable = false;
	//! The verified verdict, when the replica answered with one or the cache held one.
	bool has_verdict = false;
	Verdict verdict;
	//! The verdict came from the cache: no call was made.
	bool cached = false;

	std::string Message() const {
		return "QoD: " + reason;
	}
};

//! The path every replica serves decisions on.
constexpr const char *kDecidePath = "/api/internal/authz/decide";

//! Asks the issuing replica for verdicts (Section 5) and verifies them. Thread safe.
class DecideClient {
public:
	DecideClient(std::string node_id, std::string node_key, AuthzLimits limits, std::function<int64_t()> clock);

	//! The verdict on `call`. A plan: from the cache when it holds one for the principal id, policy
	//! version, ctx and fingerprint, else from the replica, then cached (an allow, or a refusal that is
	//! not retryable). A screen: from the replica every time, never cached. Never throws: a transport
	//! failure, a timeout or an unexpected status is Unavailable (retryable); a 401, a replica URL that
	//! is not plain http, or a verdict that does not verify is a Refuse.
	DecideResult Decide(const DecideCall &call);

	VerdictCache &Cache() {
		return cache_;
	}

private:
	DecideResult Ask(const DecideCall &call, const std::string &fingerprint);

	std::string node_id_;
	std::string node_key_;
	AuthzLimits limits_;
	std::function<int64_t()> clock_;
	VerdictCache cache_;
};

} // namespace qod
