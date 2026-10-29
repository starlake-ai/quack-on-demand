#pragma once

#include <cstddef>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace qod {

//! A plain http:// URL, split for one request.
struct HttpUrl {
	//! A host name or address, without the brackets of an IPv6 literal.
	std::string host;
	std::string port;
	//! host[:port] as written, for the Host header.
	std::string authority;
	//! The path prefix, without a trailing '/' ("" for none).
	std::string path;
};

//! Splits `url`, which must be http://host[:port][/path] with no user information, query or fragment,
//! and no space or control character in the path.
//! Throws std::invalid_argument naming what is wrong: qod-node speaks plain HTTP only.
HttpUrl ParseHttpUrl(const std::string &url);

struct HttpReply {
	int status = 0;
	std::string body;
};

//! The largest response body HttpPost reads.
constexpr size_t kMaxHttpReply = 1024 * 1024;

//! One address a host name resolved to: its family (AF_INET, AF_INET6) and its sockaddr's bytes, so
//! this header names no socket type (it is included next to DuckDB's headers).
struct ResolvedAddress {
	int family = 0;
	std::string sockaddr;

	bool operator==(const ResolvedAddress &other) const {
		return family == other.family && sockaddr == other.sockaddr;
	}
};

//! How long a resolved host name is reused before it is resolved again.
constexpr int64_t kResolvedLifetimeMs = 30 * 1000;

//! How long after a failed resolution the name is not resolved again: its last addresses are served
//! meanwhile, or, without any, the failure.
constexpr int64_t kFailedResolutionRetryMs = 1000;

//! The addresses of each host and port the node calls, resolved once and reused for `lifetime_ms`, so
//! a decide call does not resolve its replica's name every time (Task 14, hand-off T7-M5: resolving is
//! not bounded by the call's deadline). Thread safe; the lock is never held while resolving. One caller
//! at a time resolves a name: while it does, the others are served the name's last addresses, or wait
//! for it when there are none yet. A failed resolution serves the last addresses (or the failure, when
//! there are none) for `retry_ms`, so a resolver outage costs one resolution per name and `retry_ms`,
//! not one per call.
class ResolvedAddresses {
public:
	//! The addresses `host`:`port` resolves to, in the resolver's order; throws std::runtime_error when
	//! it resolves to none.
	using Resolver = std::function<std::vector<ResolvedAddress>(const std::string &host, const std::string &port)>;

	ResolvedAddresses(Resolver resolver, std::function<int64_t()> clock, int64_t lifetime_ms,
	                  int64_t retry_ms = kFailedResolutionRetryMs);

	//! The addresses of `host`:`port`: the kept ones while younger than the lifetime, else resolved now
	//! and kept, the address that last connected first (Prefer). `fresh` is true only for the caller
	//! that just resolved them. Past the lifetime, the kept ones while another caller resolves the name
	//! or within `retry_ms` of a failed resolution, or when the resolution this call made failed. Throws
	//! std::runtime_error ("cannot resolve <host>") when the name resolves to nothing and none are kept.
	std::vector<ResolvedAddress> Get(const std::string &host, const std::string &port, bool &fresh);

	//! Drops what is kept for `host`:`port`: none of its addresses answered.
	void Forget(const std::string &host, const std::string &port);

	//! Moves `address` to the front of `host`:`port`'s kept addresses: the one that connected, tried
	//! first next time (a name listing an address that refuses first, `localhost`'s `::1` against an
	//! IPv4 listener, then pays the refusal once).
	void Prefer(const std::string &host, const std::string &port, const ResolvedAddress &address);

private:
	struct Entry {
		std::vector<ResolvedAddress> addresses;
		int64_t resolved_at = 0;
		//! A caller is resolving the name.
		bool resolving = false;
		//! Until when a failed resolution is not retried, and its message.
		int64_t retry_at = 0;
		std::string failure;
	};

	Resolver resolver_;
	std::function<int64_t()> clock_;
	int64_t lifetime_ms_;
	int64_t retry_ms_;
	std::mutex lock_;
	std::condition_variable resolved_;
	std::map<std::string, Entry> entries_;
};

//! What HttpPost resolves host names with: getaddrinfo, kept kResolvedLifetimeMs.
ResolvedAddresses &HostAddresses();

//! POSTs `body` to `path` on `url`'s host over one fresh connection (Connection: close), with `headers`
//! and a Content-Length, and returns the status and body (Content-Length, chunked or close-delimited).
//! Connecting, sending and receiving share one deadline `timeout_ms` from now. The host name is
//! resolved through `known` (HostAddresses() by default: once per kResolvedLifetimeMs, and again within
//! the call when none of the kept addresses answers while the deadline has not passed); resolving
//! takes from that deadline but is not bounded by it. Throws std::invalid_argument for a header that
//! holds CR or LF, and std::runtime_error on any transport failure, a timeout, a body over
//! kMaxHttpReply or a response that is not HTTP/1.x.
HttpReply HttpPost(const HttpUrl &url, const std::string &path,
                   const std::vector<std::pair<std::string, std::string>> &headers, const std::string &body,
                   int64_t timeout_ms);
HttpReply HttpPost(const HttpUrl &url, const std::string &path,
                   const std::vector<std::pair<std::string, std::string>> &headers, const std::string &body,
                   int64_t timeout_ms, ResolvedAddresses &known);

//! Decodes the chunked body (RFC 9112 section 7.1) `raw` holds into `body`: false while `raw` does not
//! hold all of it yet. Throws std::runtime_error when it is malformed or over kMaxHttpReply. Chunk
//! extensions and trailer fields are dropped.
bool DecodeChunked(const std::string &raw, std::string &body);

} // namespace qod
