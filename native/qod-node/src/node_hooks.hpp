#pragma once

#include "authz.hpp"
#include "decide_client.hpp"
#include "duckdb.hpp"

#include <functional>
#include <memory>

namespace qod {

//! quack tags every client connection it creates with a ClientContextState under this key, before
//! the connection's first statement. A connection without it (the init connection, quack's callback
//! connections, DuckLake's metadata connection) is internal to the node.
constexpr const char *kQuackSessionKey = "quack_session";

//! quack_authorization_function's value: qod_screen fully qualified, so nothing a session or the
//! init SQL creates in the default search path can shadow it.
constexpr const char *kScreenFunction = "system.main.qod_screen";

//! Decides one plan description for a connection's installed principal. In production this node's
//! DecideClient (NodeAuthz::Decider, which keeps the verdict cache); tests pass a stand-in.
using PlanDecider = std::function<DecideResult(const DecideCall &call)>;

//! Registers on `db`, before any SQL runs:
//!  - qod_session(handle), the table function the manager's prelude calls: verifies the handle and
//!    installs its principal on the connection (single use, refresh rules: RefreshRefusal);
//!  - qod_screen(connection_id, sql), the parse screen quack calls for every PREPARE (installed as
//!    quack_authorization_function once quack is loaded);
//!  - a post_bind hook: no statement on a quack client connection binds without a current principal;
//!    every other plan bound on such a connection is described (Describe: its local refusals hold for
//!    every principal) and runs only under a verdict, a local allow for superuser and system
//!    principals, else `decider`'s. Plans bound on any other connection (the init connection, quack's
//!    callback connections, DuckLake's metadata queries) are the node's own and pass;
//!  - a pre_optimize hook: the plan is described again and decided again when it changed since
//!    post_bind (an extension's post_bind registered later may change it);
//!  - the connection state's backstops: OnFinalizePrepare refuses a statement that reached prepare
//!    finalization without a verdict, OnRebindPreparedStatement rebinds a prepared statement whose
//!    verdict was taken for another principal, policy version or context, or expired.
//! A null `decider` is this node's DecideClient.
void RegisterNodeAuthz(duckdb::DatabaseInstance &db, std::shared_ptr<NodeAuthz> authz,
                       PlanDecider decider = nullptr);

//! The principal installed on the connection of `context` and the handle that last installed or
//! refreshed it (with its replica), into `out`, while that handle is current at `now_ms` and the
//! connection's last prelude succeeded whole; false otherwise (no prelude yet, expired, or the last
//! prelude refused). What the plan verdicts' decide calls carry.
bool SessionSnapshot(duckdb::ClientContext &context, int64_t now_ms, Installed &out);

} // namespace qod
