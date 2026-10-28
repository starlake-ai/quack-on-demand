#pragma once

#include "credentials.hpp"
#include "decide_client.hpp"
#include "handle.hpp"

#include <cstdint>
#include <functional>
#include <limits>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

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
	//! The installing (or last refreshing) handle's ctx: a refresh may change it.
	std::string ctx;
	//! That handle, as the manager minted it: the connection's decide calls carry it.
	std::string handle;
	//! The replica that minted it, which decides the connection's statements.
	std::string replica;
};

//! What one verdict admitted: the plan it was taken for and the session it holds for.
struct Admitted {
	std::string fingerprint;
	std::string principal_id;
	int64_t policy_version = 0;
	std::string ctx;
	//! Epoch milliseconds; kNeverExpires for a local allow (superuser, system, the prelude).
	int64_t expires_at = 0;
};

constexpr int64_t kNeverExpires = std::numeric_limits<int64_t>::max();

//! The refusal of a statement that reached prepare finalization without a verdict.
constexpr const char *kNoVerdict = "QoD: statement reached prepare finalization without a verdict";

//! OnFinalizePrepare's rule: empty to admit, else the refusal. A connection without a principal runs
//! nothing; a statement runs only under a verdict, unless the principal is privileged (a superuser or
//! the manager's own), whose plans are admitted on the node.
std::string FinalizeRefusal(bool installed, bool privileged, bool has_verdict);

//! True when a prepared statement must be bound (described and decided) again before it executes:
//! no verdict is stored with it (`stored` null), or the stored one was taken for another principal,
//! policy version or session context than `current`, or has expired at `now_ms`.
bool RebindRequired(const Admitted *stored, const Installed &current, int64_t now_ms);

//! Why `next` may not install on a connection that holds `current` (null: none yet); empty when it
//! may. The first handle installs its principal; a later one only refreshes it: the same principal,
//! and a policy version no older.
std::string RefreshRefusal(const Installed *current, const Handle &next);

//! This node's identity and handle checks, shared by the parse screen, qod_session and the
//! post_bind backstop.
class NodeAuthz {
public:
	NodeAuthz(std::string node_id, std::string node_key, Clock clock = SystemNowMs,
	          AuthzLimits limits = AuthzLimits());

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
	//!
	//! When it admits a statement that is not the prelude, `recorded` (if given) receives the
	//! connection's record, for the content rules and their decide call (node_hooks).
	std::string Screen(const std::string &connection_id, const std::string &sql, Installed *recorded = nullptr);

	//! Records, until `exp`, the scopes of the S3 credentials qod_session installed with the handle
	//! `nonce` (decision 23): the screen judges that connection's s3:// locations against them.
	void RecordCredentialScopes(const std::string &nonce, std::vector<std::string> scopes, int64_t exp);

	//! The scopes recorded for `nonce`; none when nothing is (any longer).
	std::vector<std::string> CredentialScopes(const std::string &nonce);

	//! The decide client of this node (its key, its clock), shared by every connection: the parse
	//! screen's location decisions and the plan verdicts.
	DecideClient &Decider() {
		return decider_;
	}

	//! The S3 credential client of this node (its key, its clock, the decide timeout), shared by every
	//! connection: qod_session installs each principal's own credentials with it (decision 23).
	CredentialClient &Credentials() {
		return credentials_;
	}

private:
	//! Drops the records whose handles expired, at most every 10 s. Called with lock_ held.
	void Sweep(int64_t now_ms);

	std::string node_id_;
	std::string node_key_;
	Clock clock_;
	//! After the three above: initialized from them.
	DecideClient decider_;
	CredentialClient credentials_;
	NonceLedger ledger_;
	std::mutex lock_;
	std::unordered_map<std::string, Installed> connections_;
	struct RecordedScopes {
		std::vector<std::string> scopes;
		int64_t exp = 0;
	};
	//! By handle nonce, under lock_; swept with connections_.
	std::unordered_map<std::string, RecordedScopes> credential_scopes_;
	int64_t next_sweep_ms_ = 0;
};

} // namespace qod
