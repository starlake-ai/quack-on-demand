// Kept apart from the DuckDB sources so windows.h and its macros never meet DuckDB's headers.
#include "lifetime.hpp"

#include <chrono>
#include <condition_variable>
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

std::mutex stop_lock;
std::condition_variable stop_requested;
std::string stop_reason;

void RequestStop(const std::string &reason) {
	{
		std::lock_guard<std::mutex> guard(stop_lock);
		if (stop_reason.empty()) {
			stop_reason = reason;
		}
	}
	stop_requested.notify_all();
}

#ifdef _WIN32

BOOL WINAPI OnConsoleControl(DWORD) {
	RequestStop("console control event");
	return TRUE;
}

void WatchParent(long long parent_pid) {
	HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(parent_pid));
	if (!parent) {
		RequestStop("parent process " + std::to_string(parent_pid) + " is gone");
		return;
	}
	// A handle names this process, not its pid: a reused pid cannot keep the node alive.
	std::thread([parent, parent_pid] {
		WaitForSingleObject(parent, INFINITE);
		CloseHandle(parent);
		RequestStop("parent process " + std::to_string(parent_pid) + " ended");
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
		RequestStop("parent process " + std::to_string(parent_pid) + " ended");
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
			RequestStop("parent process " + std::to_string(parent_pid) + " ended");
		}).detach();
		return;
	}
	if (queue >= 0) {
		close(queue);
	}
	if (!ParentAlive(parent_pid)) {
		RequestStop("parent process " + std::to_string(parent_pid) + " is gone");
		return;
	}
#elif defined(__linux__)
	// The kernel signals this process the moment its parent dies; SIGTERM is one of the stop signals.
	if (getppid() == static_cast<pid_t>(parent_pid)) {
		prctl(PR_SET_PDEATHSIG, SIGTERM);
		// The parent may have died before prctl took effect.
		if (getppid() != static_cast<pid_t>(parent_pid)) {
			RequestStop("parent process " + std::to_string(parent_pid) + " is gone");
			return;
		}
	}
#endif
	PollParent(parent_pid);
}

#endif

} // namespace

void PrepareStopSignals() {
#ifdef _WIN32
	SetConsoleCtrlHandler(OnConsoleControl, TRUE);
#else
	sigemptyset(&stop_signals);
	sigaddset(&stop_signals, SIGINT);
	sigaddset(&stop_signals, SIGTERM);
	pthread_sigmask(SIG_BLOCK, &stop_signals, nullptr);
#endif
}

std::string WaitForStop(long long parent_pid) {
#ifndef _WIN32
	std::thread([] {
		int received = 0;
		sigwait(&stop_signals, &received);
		RequestStop("signal " + std::to_string(received));
	}).detach();
#endif
	if (parent_pid > 0) {
		WatchParent(parent_pid);
	}
	std::unique_lock<std::mutex> guard(stop_lock);
	stop_requested.wait(guard, [] { return !stop_reason.empty(); });
	return stop_reason;
}

} // namespace qod
