#pragma once

// The few socket calls the decide client and its tests' server need, over POSIX sockets and Winsock.
// Only .cpp files that use sockets include this header, never one that includes DuckDB's: winsock2.h
// must not meet a windows.h included first.

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#endif

#include <cstddef>
#include <cstdint>

namespace qod {

#ifdef _WIN32
using SocketHandle = SOCKET;
const SocketHandle kNoSocket = INVALID_SOCKET;
#else
using SocketHandle = int;
const SocketHandle kNoSocket = -1;
#endif

//! Starts Winsock once per process; nothing elsewhere. Throws std::runtime_error when it cannot.
void StartSockets();

//! Closes `socket`; nothing for kNoSocket.
void CloseSocket(SocketHandle socket);

//! Puts `socket` in non-blocking mode; false when it cannot.
bool MakeNonBlocking(SocketHandle socket);

//! A non-blocking TCP socket of `family` (AF_INET or AF_INET6) that never raises SIGPIPE and is not
//! inherited across exec; kNoSocket when it cannot be made.
SocketHandle OpenStreamSocket(int family);

//! Waits until `socket` is writable (`for_write`) or readable, until the monotonic `deadline_ms`
//! (MonotonicMs): 1 ready (a failed connect counts as ready: ConnectResult says how it ended), 0 the
//! deadline passed, -1 the wait failed.
int WaitSocket(SocketHandle socket, bool for_write, int64_t deadline_ms);

//! True when the last socket call failed only because it would block, was interrupted, or a connect is
//! still in progress.
bool WouldBlock();

//! The error a non-blocking connect ended with: 0 connected.
int ConnectResult(SocketHandle socket);

//! One send or receive of at most `length` bytes: the count, or -1 (WouldBlock() tells a retry from a
//! failure). A receive returns 0 at the peer's end of stream.
long long SendSome(SocketHandle socket, const char *data, size_t length);
long long ReceiveSome(SocketHandle socket, char *data, size_t length);

//! Milliseconds of a monotonic clock, for deadlines.
int64_t MonotonicMs();

} // namespace qod
