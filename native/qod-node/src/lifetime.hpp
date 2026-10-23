#pragma once

#include <string>

namespace qod {

//! Call first, before any thread starts. Watches for a stop request from then on: SIGINT or SIGTERM
//! (on POSIX blocked in every thread, so only the watcher receives them), a Windows console control
//! event, or, when parent_pid is not 0, the end of that process: the node never outlives its spawn
//! wrapper. A stop that arrives before MarkServing ends the process at once (status 128 + the signal,
//! else 1), wherever startup is: blocked opening an init FIFO no one writes, waiting on a catalog lock,
//! or downloading an extension.
void StartStopWatch(long long parent_pid);

//! The node serves: from now on a stop request goes to WaitForStop, so main can close the database.
void MarkServing();

//! Blocks until a stop request arrives after MarkServing. Returns why it stopped.
std::string WaitForStop();

} // namespace qod
