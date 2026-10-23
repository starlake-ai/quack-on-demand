#pragma once

namespace qod {

//! Prepares the process to receive a stop request: SIGINT/SIGTERM on POSIX, console control events on
//! Windows. Call before any thread starts.
void PrepareStopSignal();

//! Blocks until a stop request arrives.
void WaitForStopSignal();

} // namespace qod
