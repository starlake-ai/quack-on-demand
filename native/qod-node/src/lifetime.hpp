#pragma once

#include <string>

namespace qod {

//! Call before any thread starts. On POSIX it blocks SIGINT and SIGTERM in every thread, so the one
//! waiter in WaitForStop receives them; on Windows it installs the console control handler.
void PrepareStopSignals();

//! Blocks until a stop request arrives (SIGINT/SIGTERM, or a Windows console control event) or, when
//! parent_pid is not 0, until that process has ended: the node never outlives its spawn wrapper.
//! Returns why it stopped.
std::string WaitForStop(long long parent_pid);

} // namespace qod
