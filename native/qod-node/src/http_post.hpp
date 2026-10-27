#pragma once

#include <cstddef>
#include <cstdint>
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

//! Splits `url`, which must be http://host[:port][/path] with no user information, query or fragment.
//! Throws std::invalid_argument naming what is wrong: qod-node speaks plain HTTP only.
HttpUrl ParseHttpUrl(const std::string &url);

struct HttpReply {
	int status = 0;
	std::string body;
};

//! The largest response body HttpPost reads.
constexpr size_t kMaxHttpReply = 1024 * 1024;

//! POSTs `body` to `path` on `url`'s host over one fresh connection (Connection: close), with `headers`
//! and a Content-Length, and returns the status and body (Content-Length, chunked or close-delimited).
//! Connecting, sending and receiving share one deadline `timeout_ms` from now; resolving the host name
//! does not count against it. Throws std::invalid_argument for a header that holds CR or LF, and
//! std::runtime_error on any transport failure, a timeout, a body over kMaxHttpReply or a response that
//! is not HTTP/1.x.
HttpReply HttpPost(const HttpUrl &url, const std::string &path,
                   const std::vector<std::pair<std::string, std::string>> &headers, const std::string &body,
                   int64_t timeout_ms);

//! Decodes the chunked body (RFC 9112 section 7.1) `raw` holds into `body`: false while `raw` does not
//! hold all of it yet. Throws std::runtime_error when it is malformed or over kMaxHttpReply. Chunk
//! extensions and trailer fields are dropped.
bool DecodeChunked(const std::string &raw, std::string &body);

} // namespace qod
