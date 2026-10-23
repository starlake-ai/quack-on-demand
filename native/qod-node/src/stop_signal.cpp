// Kept apart from the DuckDB sources so windows.h and its macros never meet DuckDB's headers.
#include "stop_signal.hpp"

#ifdef _WIN32

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <condition_variable>
#include <mutex>

namespace qod {
namespace {

std::mutex stop_lock;
std::condition_variable stop_requested_signal;
bool stop_requested = false;

BOOL WINAPI OnConsoleControl(DWORD) {
	{
		std::lock_guard<std::mutex> guard(stop_lock);
		stop_requested = true;
	}
	stop_requested_signal.notify_all();
	return TRUE;
}

} // namespace

void PrepareStopSignal() {
	SetConsoleCtrlHandler(OnConsoleControl, TRUE);
}

void WaitForStopSignal() {
	std::unique_lock<std::mutex> guard(stop_lock);
	stop_requested_signal.wait(guard, [] { return stop_requested; });
}

} // namespace qod

#else

#include <csignal>
#include <pthread.h>

namespace qod {
namespace {

sigset_t stop_signals;

} // namespace

void PrepareStopSignal() {
	sigemptyset(&stop_signals);
	sigaddset(&stop_signals, SIGINT);
	sigaddset(&stop_signals, SIGTERM);
	// Blocked before any thread starts: every thread inherits the mask, so sigwait is the only receiver.
	pthread_sigmask(SIG_BLOCK, &stop_signals, nullptr);
}

void WaitForStopSignal() {
	int received = 0;
	sigwait(&stop_signals, &received);
}

} // namespace qod

#endif
