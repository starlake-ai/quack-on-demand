#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

namespace qod {

//! Who a statement runs for, as the manager vouches for it in a handle.
struct Principal {
	std::string id;
	std::string name;
	//! Empty for a principal of no tenant (a superuser, the manager itself).
	std::string tenant;
	bool superuser = false;
	//! The manager's own statements: health probes, maintenance, merges, metadata reads.
	bool system = false;

	bool operator==(const Principal &other) const {
		return id == other.id && name == other.name && tenant == other.tenant && superuser == other.superuser &&
		       system == other.system;
	}
	bool operator!=(const Principal &other) const {
		return !(*this == other);
	}
};

//! A verified per-statement handle. The manager mints one for every statement it sends a node, as
//! `<base64url(payload)>.<hex HMAC-SHA256(node key, payload bytes)>`, the payload a JSON object:
//! {"v":1,"node":..,"replica":..,"principal":{"id","name","tenant","superuser","system"},
//!  "pool":..,"policyVersion":..,"nonce":..,"exp":..}. The MAC covers the payload bytes as sent, so
//! no canonical form has to agree between the two sides.
struct Handle {
	std::string node;
	//! The manager replica that minted it, which decides this statement's verdicts.
	std::string replica;
	//! tenant/tenantDb/pool.
	std::string pool;
	Principal principal;
	int64_t policy_version = 0;
	std::string nonce;
	//! Epoch milliseconds.
	int64_t exp = 0;
};

//! How far ahead of this node's clock a handle may expire: the manager mints them for 60 s, and the
//! rest tolerates a manager clock up to 4 minutes ahead of the node's. That is the one direction it
//! covers: a node clock ahead of the manager's by the 60 s lifetime or more refuses every handle as
//! expired. A handle valid longer than this is refused as valid too long.
constexpr int64_t kMaxHandleLifetimeMs = 5 * 60 * 1000;

//! Verifies `text` for this node (`node_key`: its 32-byte key; `node_id`: its id) at `now_ms` and
//! returns what it carries. Throws std::invalid_argument naming the first failed check: the format,
//! the MAC, the payload's shape, another node, expired, or valid too long.
Handle VerifyHandle(const std::string &text, const std::string &node_key, const std::string &node_id,
                    int64_t now_ms);

//! The nonces of the handles already used, each kept until its handle expires: a handle installs a
//! principal once.
class NonceLedger {
public:
	//! False when `nonce` was used before and its handle has not expired; otherwise records it until
	//! `exp` and returns true.
	bool Consume(const std::string &nonce, int64_t exp, int64_t now_ms);
	//! True when `nonce` was used and its handle has not expired. Records nothing.
	bool Used(const std::string &nonce, int64_t now_ms);

private:
	//! Drops the nonces whose handles expired, at most every 10 s. Called with lock_ held.
	void Sweep(int64_t now_ms);

	std::mutex lock_;
	std::unordered_map<std::string, int64_t> used_;
	int64_t next_sweep_ms_ = 0;
};

} // namespace qod
