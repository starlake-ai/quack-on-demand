#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

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
//! {"v":3,"node":..,"replica":..,"principal":{"id","name","tenant","superuser","system"},
//!  "pool":..,"policyVersion":..,"ctx":..,"creds":..,"path":..,"readOnly":[..],"nonce":..,"exp":..},
//! every member required ("ctx", "creds" and "path" since node protocol 3, "readOnly" since 5). The MAC
//! covers the payload bytes as sent, so no canonical form has to agree between the two sides.
struct Handle {
	std::string node;
	//! The manager replica that minted it, which decides this statement's verdicts.
	std::string replica;
	//! tenant/tenantDb/pool.
	std::string pool;
	Principal principal;
	int64_t policy_version = 0;
	//! The session's authorization context digest, which verdicts are cached by: 64 lowercase hex
	//! digits, required (node protocol 3).
	std::string ctx;
	//! The digest of the principal's own S3 credentials (64 lowercase hex digits), empty when it has
	//! none; required, as a string, since node protocol 3. The node fetches the credentials when it
	//! differs from the one its connection secrets were made from (Task 7).
	std::string creds;
	//! The session default path the manager expects on the connection, `<catalog>.<schema>`: the
	//! target of the USE prelude the router sends before the pool's statements, empty when it sends
	//! none. Required, as a string, since node protocol 3 (CONTRACT Addendum 4 item 22).
	std::string path;
	//! The pool's read-only federated catalogs (decision 24): lowercased aliases of 1 to 128 characters
	//! of [a-z0-9_], sorted ascending, none repeated, empty for none. Every principal's write, DDL,
	//! ATTACH and DETACH into one is refused, the system's included. Required since node protocol 5.
	std::vector<std::string> read_only;
	std::string nonce;
	//! Epoch milliseconds.
	int64_t exp = 0;
};

//! The handle version this node reads, and the only one. A node reads exactly one version and refuses
//! every other, the probe's handle included: version 1 is node protocol 2's (no enforcement in the
//! engine), version 2 is protocols 3 and 4's (no readOnly, so a superuser could write into a
//! read-only catalog there), version 3 is protocol 5's (a DuckLake read at an earlier snapshot described
//! under the name it had then, so another table's grant could cover it: review M1). Bumping it keeps
//! such a node unhealthy and never routed to on every backend. Bump it with the manager's
//! NodeHandles.Version whenever a node that ignores a new member, or describes differently, must not
//! serve.
constexpr int64_t kHandleVersion = 4;

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
