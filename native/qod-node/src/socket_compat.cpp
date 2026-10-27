#include "socket_compat.hpp"

#include <chrono>
#include <mutex>
#include <stdexcept>
#include <string>

#ifndef _WIN32
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#endif

namespace qod {

void StartSockets() {
#ifdef _WIN32
	static std::once_flag once;
	static int started = -1;
	std::call_once(once, [] {
		WSADATA data;
		started = WSAStartup(MAKEWORD(2, 2), &data);
	});
	if (started != 0) {
		throw std::runtime_error("cannot start Winsock (error " + std::to_string(started) + ")");
	}
#endif
}

void CloseSocket(SocketHandle socket) {
	if (socket == kNoSocket) {
		return;
	}
#ifdef _WIN32
	closesocket(socket);
#else
	close(socket);
#endif
}

bool MakeNonBlocking(SocketHandle socket) {
#ifdef _WIN32
	u_long on = 1;
	return ioctlsocket(socket, FIONBIO, &on) == 0;
#else
	auto flags = fcntl(socket, F_GETFL, 0);
	return flags >= 0 && fcntl(socket, F_SETFL, flags | O_NONBLOCK) == 0;
#endif
}

SocketHandle OpenStreamSocket(int family) {
	auto handle = ::socket(family, SOCK_STREAM, IPPROTO_TCP);
	if (handle == kNoSocket) {
		return kNoSocket;
	}
	if (!MakeNonBlocking(handle)) {
		CloseSocket(handle);
		return kNoSocket;
	}
#ifndef _WIN32
	fcntl(handle, F_SETFD, FD_CLOEXEC);
#ifdef SO_NOSIGPIPE
	int one = 1;
	setsockopt(handle, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
#endif
	return handle;
}

int WaitSocket(SocketHandle socket, bool for_write, int64_t deadline_ms) {
	while (true) {
		auto remaining = deadline_ms - MonotonicMs();
		if (remaining < 0) {
			remaining = 0;
		}
#ifdef _WIN32
		fd_set ready;
		fd_set failed;
		FD_ZERO(&ready);
		FD_ZERO(&failed);
		FD_SET(socket, &ready);
		FD_SET(socket, &failed);
		timeval timeout {static_cast<long>(remaining / 1000), static_cast<long>((remaining % 1000) * 1000)};
		auto result = select(0, for_write ? nullptr : &ready, for_write ? &ready : nullptr, &failed, &timeout);
		if (result == SOCKET_ERROR) {
			return -1;
		}
		return result == 0 ? 0 : 1;
#else
		pollfd entry {socket, static_cast<short>(for_write ? POLLOUT : POLLIN), 0};
		auto result = poll(&entry, 1, static_cast<int>(remaining));
		if (result < 0 && errno == EINTR) {
			continue;
		}
		if (result < 0) {
			return -1;
		}
		return result == 0 ? 0 : 1;
#endif
	}
}

bool WouldBlock() {
#ifdef _WIN32
	auto error = WSAGetLastError();
	return error == WSAEWOULDBLOCK || error == WSAEINPROGRESS || error == WSAEINTR;
#else
	return errno == EWOULDBLOCK || errno == EAGAIN || errno == EINPROGRESS || errno == EINTR;
#endif
}

int ConnectResult(SocketHandle socket) {
	int error = 0;
	socklen_t length = sizeof(error);
	if (getsockopt(socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char *>(&error), &length) != 0) {
		return -1;
	}
	return error;
}

long long SendSome(SocketHandle socket, const char *data, size_t length) {
#ifdef _WIN32
	return send(socket, data, static_cast<int>(length), 0);
#elif defined(MSG_NOSIGNAL)
	return send(socket, data, length, MSG_NOSIGNAL);
#else
	return send(socket, data, length, 0);
#endif
}

long long ReceiveSome(SocketHandle socket, char *data, size_t length) {
#ifdef _WIN32
	return recv(socket, data, static_cast<int>(length), 0);
#else
	return recv(socket, data, length, 0);
#endif
}

int64_t MonotonicMs() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch())
	    .count();
}

} // namespace qod
