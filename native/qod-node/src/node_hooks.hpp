#pragma once

#include "authz.hpp"
#include "duckdb.hpp"

#include <memory>

namespace qod {

//! quack tags every client connection it creates with a ClientContextState under this key, before
//! the connection's first statement. A connection without it (the init connection, quack's callback
//! connections, DuckLake's metadata connection) is internal to the node.
constexpr const char *kQuackSessionKey = "quack_session";

//! quack_authorization_function's value: qod_screen fully qualified, so nothing a session or the
//! init SQL creates in the default search path can shadow it.
constexpr const char *kScreenFunction = "system.main.qod_screen";

//! Registers on `db`, before any SQL runs:
//!  - qod_session(handle), the table function the manager's prelude calls: verifies the handle and
//!    installs its principal on the connection (single use, refresh rules: RefreshRefusal);
//!  - qod_screen(connection_id, sql), the parse screen quack calls for every PREPARE (installed as
//!    quack_authorization_function once quack is loaded);
//!  - a post_bind hook: no statement on a quack client connection binds without a current principal,
//!    and none changes quack_authorization_function or quack_authentication_function.
void RegisterNodeAuthz(duckdb::DatabaseInstance &db, std::shared_ptr<NodeAuthz> authz);

//! The principal installed on the connection of `context` and the handle that last installed or
//! refreshed it (with its replica), into `out`, while that handle is current at `now_ms` and the
//! connection's last prelude succeeded whole; false otherwise (no prelude yet, expired, or the last
//! prelude refused). For the plan verdicts' decide calls (Task 10).
bool SessionSnapshot(duckdb::ClientContext &context, int64_t now_ms, Installed &out);

} // namespace qod
