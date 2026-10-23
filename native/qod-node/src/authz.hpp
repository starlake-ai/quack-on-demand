#pragma once

#include "handle.hpp"

#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>

namespace qod {

//! The refusal of every statement on a quack connection that carries no current principal, from the
//! parse screen and again from post_bind. The manager's health probe expects it (the canary).
constexpr const char *kNoPrincipal = "qod-node: this connection has no verified principal";

//! The manager's prelude, CALL system.main.qod_session('<handle>'), sent as its own PREPARE before
//! every statement on a node connection. Qualified, like the screen: a macro a session creates in
//! the default search path or in its temp schema cannot shadow it.
constexpr const char *kPreludePrefix = "CALL system.main.qod_session('";
constexpr const char *kPreludeSuffix = "')";

//! How every refusal of a session handle starts, from the parse screen and from qod_session.
constexpr const char *kHandleRefused = "qod-node: session handle refused: ";

//! Milliseconds since the epoch.
using Clock = std::function<int64_t()>;
int64_t SystemNowMs();

//! The handle `sql` installs when it is exactly the manager's prelude, else empty. Only that exact
//! spelling: the manager writes it, and a handle holds only base64url characters, '.' and hex
//! digits, so it needs no quoting.
std::string PreludeHandle(const std::string &sql);

//! A principal installed on a connection, from the handle that installed or last refreshed it.
struct Installed {
	Principal principal;
	int64_t policy_version = 0;
	int64_t exp = 0;
	std::string nonce;
};

//! Why `next` may not install on a connection that holds `current` (null: none yet); empty when it
//! may. The first handle installs its principal; a later one only refreshes it: the same principal,
//! and a policy version no older.
std::string RefreshRefusal(const Installed *current, const Handle &next);

//! This node's identity and handle checks, shared by the parse screen, qod_session and the
//! post_bind backstop.
class NodeAuthz {
public:
	NodeAuthz(std::string node_id, std::string node_key, Clock clock = SystemNowMs);

	const std::string &NodeId() const {
		return node_id_;
	}
	int64_t Now() const {
		return clock_();
	}

	//! VerifyHandle for this node, now.
	Handle Verify(const std::string &text) const;

	//! Single use: records the handle's nonce until it expires; false when it was used already.
	bool Consume(const Handle &handle);

	//! The parse screen. quack calls it for every PREPARE with the client connection id and the full
	//! SQL, before the statement is parsed or bound on the session. Admits the manager's prelude when
	//! its handle verifies, is unused and may install on the connection (RefreshRefusal), and records
	//! the handle's principal for the connection; admits any other statement only on a connection
	//! whose recorded principal is still current. Returns empty to admit, else the refusal.
	//!
	//! The book is advisory: it cannot learn that a connection closed, so a record lasts as long as
	//! its handle, and once it expired another principal's prelude is admitted here. The session
	//! decides (qod_session keeps a connection's principal for its life; post_bind refuses a
	//! statement without a current one); the screen only stops what it can before bind.
	std::string Screen(const std::string &connection_id, const std::string &sql);

private:
	//! Drops the records whose handles expired, at most every 10 s. Called with lock_ held.
	void Sweep(int64_t now_ms);

	std::string node_id_;
	std::string node_key_;
	Clock clock_;
	NonceLedger ledger_;
	std::mutex lock_;
	std::unordered_map<std::string, Installed> connections_;
	int64_t next_sweep_ms_ = 0;
};

} // namespace qod
