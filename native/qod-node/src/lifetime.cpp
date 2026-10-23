// Kept apart from the DuckDB sources so windows.h and its macros never meet DuckDB's headers.
#include "lifetime.hpp"

#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <cerrno>
#include <csignal>
#include <pthread.h>
#include <sys/types.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <sys/event.h>
#elif defined(__linux__)
#include <sys/prctl.h>
#endif
#endif

namespace qod {
namespace {

struct StopState {
	std::mutex lock;
	std::condition_variable requested;
	std::string reason;
	bool serving = false;
};

// Never destroyed: a watcher thread can still request a stop while main returns.
StopState &State() {
	static auto *state = new StopState();
	return *state;
}

// Before MarkServing there is nothing to close, and startup may be blocked where no flag reaches it,
// so the process ends at once. After it, the first request goes to WaitForStop and a second one (the
// wrapper killed while the database closes) ends the process at once.
void RequestStop(const std::string &reason, int status) {
	auto &state = State();
	{
		std::lock_guard<std::mutex> guard(state.lock);
		if (!state.serving || !state.reason.empty()) {
			std::cerr << "[qod-node] stopping " << (state.serving ? "at once" : "before ready") << ": " << reason
			          << std::endl;
			std::_Exit(status);
		}
		state.reason = reason;
	}
	state.requested.notify_all();
}

std::string ParentEnded(long long parent_pid, const char *how) {
	return "parent process " + std::to_string(parent_pid) + " " + how;
}

#ifdef _WIN32

BOOL WINAPI OnConsoleControl(DWORD) {
	RequestStop("console control event", 1);
	return TRUE;
}

void WatchParent(long long parent_pid) {
	HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(parent_pid));
	if (!parent) {
		RequestStop(ParentEnded(parent_pid, "is gone"), 1);
		return;
	}
	// A handle names this process, not its pid: a reused pid cannot keep the node alive.
	std::thread([parent, parent_pid] {
		WaitForSingleObject(parent, INFINITE);
		CloseHandle(parent);
		RequestStop(ParentEnded(parent_pid, "ended"), 1);
	}).detach();
}

#else

sigset_t stop_signals;

bool ParentAlive(long long parent_pid) {
	return kill(static_cast<pid_t>(parent_pid), 0) == 0 || errno == EPERM;
}

void PollParent(long long parent_pid) {
	std::thread([parent_pid] {
		while (ParentAlive(parent_pid)) {
			std::this_thread::sleep_for(std::chrono::seconds(1));
		}
		RequestStop(ParentEnded(parent_pid, "ended"), 1);
	}).detach();
}

void WatchParent(long long parent_pid) {
#if defined(__APPLE__)
	// kqueue reports the exit of exactly this process, without polling a pid that could be reused.
	int queue = kqueue();
	struct kevent change;
	EV_SET(&change, static_cast<uintptr_t>(parent_pid), EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, nullptr);
	if (queue >= 0 && kevent(queue, &change, 1, nullptr, 0, nullptr) == 0) {
		std::thread([queue, parent_pid] {
			struct kevent event;
			kevent(queue, nullptr, 0, &event, 1, nullptr);
			close(queue);
			RequestStop(ParentEnded(parent_pid, "ended"), 1);
		}).detach();
		return;
	}
	if (queue >= 0) {
		close(queue);
	}
	if (!ParentAlive(parent_pid)) {
		RequestStop(ParentEnded(parent_pid, "is gone"), 1);
		return;
	}
#elif defined(__linux__)
	// The kernel signals this process the moment its parent dies; SIGTERM is one of the stop signals.
	if (getppid() == static_cast<pid_t>(parent_pid)) {
		prctl(PR_SET_PDEATHSIG, SIGTERM);
		// The parent may have died before prctl took effect.
		if (getppid() != static_cast<pid_t>(parent_pid)) {
			RequestStop(ParentEnded(parent_pid, "is gone"), 1);
			return;
		}
	}
#endif
	PollParent(parent_pid);
}

#endif

} // namespace

void StartStopWatch(long long parent_pid) {
#ifdef _WIN32
	SetConsoleCtrlHandler(OnConsoleControl, TRUE);
#else
	sigemptyset(&stop_signals);
	sigaddset(&stop_signals, SIGINT);
	sigaddset(&stop_signals, SIGTERM);
	pthread_sigmask(SIG_BLOCK, &stop_signals, nullptr);
	std::thread([] {
		for (;;) {
			int received = 0;
			if (sigwait(&stop_signals, &received) == 0) {
				RequestStop("signal " + std::to_string(received), 128 + received);
			}
		}
	}).detach();
#endif
	if (parent_pid > 0) {
		WatchParent(parent_pid);
	}
}

void MarkServing() {
	auto &state = State();
	std::lock_guard<std::mutex> guard(state.lock);
	state.serving = true;
}

std::string WaitForStop() {
	auto &state = State();
	std::unique_lock<std::mutex> guard(state.lock);
	state.requested.wait(guard, [&state] { return !state.reason.empty(); });
	return state.reason;
}

} // namespace qod
