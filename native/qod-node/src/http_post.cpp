#include "http_post.hpp"

#include "socket_compat.hpp"

#include <algorithm>
#include <stdexcept>

namespace qod {
namespace {

std::string Lower(std::string text) {
	for (auto &c : text) {
		if (c >= 'A' && c <= 'Z') {
			c = static_cast<char>(c - 'A' + 'a');
		}
	}
	return text;
}

std::string Trim(const std::string &text) {
	auto first = text.find_first_not_of(" \t");
	if (first == std::string::npos) {
		return "";
	}
	return text.substr(first, text.find_last_not_of(" \t") - first + 1);
}

bool AllDigits(const std::string &text) {
	return !text.empty() && std::all_of(text.begin(), text.end(), [](char c) { return c >= '0' && c <= '9'; });
}

bool AllHex(const std::string &text) {
	return !text.empty() && std::all_of(text.begin(), text.end(), [](char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	});
}

//! Closes the socket on every way out.
struct SocketGuard {
	SocketHandle handle = kNoSocket;
	~SocketGuard() {
		CloseSocket(handle);
	}
};

SocketHandle Connect(const HttpUrl &url, int64_t deadline) {
	addrinfo hints {};
	hints.ai_family = AF_UNSPEC;
	hints.ai_socktype = SOCK_STREAM;
	hints.ai_flags = AI_NUMERICSERV;
	addrinfo *found = nullptr;
	if (getaddrinfo(url.host.c_str(), url.port.c_str(), &hints, &found) != 0 || !found) {
		throw std::runtime_error("cannot resolve " + url.host);
	}
	std::string failure = "cannot connect to " + url.authority;
	SocketHandle connected = kNoSocket;
	for (auto *address = found; address && connected == kNoSocket; address = address->ai_next) {
		auto handle = OpenStreamSocket(address->ai_family);
		if (handle == kNoSocket) {
			continue;
		}
		auto ok = connect(handle, address->ai_addr, static_cast<socklen_t>(address->ai_addrlen)) == 0;
		if (!ok && WouldBlock()) {
			auto ready = WaitSocket(handle, true, deadline);
			if (ready == 0) {
				failure = "timed out connecting to " + url.authority;
			}
			ok = ready == 1 && ConnectResult(handle) == 0;
		}
		if (ok) {
			connected = handle;
		} else {
			CloseSocket(handle);
		}
	}
	freeaddrinfo(found);
	if (connected == kNoSocket) {
		throw std::runtime_error(failure);
	}
	return connected;
}

void SendAll(SocketHandle handle, const std::string &data, int64_t deadline) {
	size_t sent = 0;
	while (sent < data.size()) {
		auto count = SendSome(handle, data.data() + sent, std::min<size_t>(data.size() - sent, 64 * 1024));
		if (count > 0) {
			sent += static_cast<size_t>(count);
		} else if (count < 0 && WouldBlock()) {
			auto ready = WaitSocket(handle, true, deadline);
			if (ready == 0) {
				throw std::runtime_error("timed out sending the request");
			}
			if (ready < 0) {
				throw std::runtime_error("the connection failed while sending");
			}
		} else {
			throw std::runtime_error("the connection failed while sending");
		}
	}
}

//! The head of a response, once the blank line that ends it arrived.
struct Head {
	int status = 0;
	size_t body_start = 0;
	bool chunked = false;
	bool has_length = false;
	size_t length = 0;
};

bool ParseHead(const std::string &raw, Head &head) {
	auto end = raw.find("\r\n\r\n");
	if (end == std::string::npos) {
		if (raw.size() > 64 * 1024) {
			throw std::runtime_error("the response head is too long");
		}
		return false;
	}
	auto line_end = raw.find("\r\n");
	auto status_line = raw.substr(0, line_end);
	// "HTTP/1.1 200 OK", "HTTP/1.0 503", ...
	if (status_line.size() < 12 || status_line.compare(0, 7, "HTTP/1.") != 0 || status_line[8] != ' ' ||
	    !AllDigits(status_line.substr(9, 3)) || (status_line.size() > 12 && status_line[12] != ' ')) {
		throw std::runtime_error("the answer is not an HTTP/1.x response");
	}
	head.status = std::stoi(status_line.substr(9, 3));
	auto pos = line_end + 2;
	while (pos < end) {
		auto next = raw.find("\r\n", pos);
		auto line = raw.substr(pos, next - pos);
		pos = next + 2;
		auto colon = line.find(':');
		if (colon == std::string::npos) {
			throw std::runtime_error("a malformed response header");
		}
		auto name = Lower(Trim(line.substr(0, colon)));
		auto value = Trim(line.substr(colon + 1));
		if (name == "content-length") {
			if (!AllDigits(value) || value.size() > 9) {
				throw std::runtime_error("a malformed Content-Length");
			}
			head.has_length = true;
			head.length = static_cast<size_t>(std::stoul(value));
			if (head.length > kMaxHttpReply) {
				throw std::runtime_error("the response is too large");
			}
		} else if (name == "transfer-encoding") {
			head.chunked = Lower(value).find("chunked") != std::string::npos;
		}
	}
	head.body_start = end + 4;
	return true;
}

HttpReply ReadReply(SocketHandle handle, int64_t deadline) {
	std::string raw;
	Head head;
	auto have_head = false;
	char buffer[16 * 1024];
	while (true) {
		if (!have_head) {
			have_head = ParseHead(raw, head);
		}
		if (have_head && head.chunked) {
			std::string body;
			if (DecodeChunked(raw.substr(head.body_start), body)) {
				return HttpReply {head.status, body};
			}
		} else if (have_head && head.has_length && raw.size() - head.body_start >= head.length) {
			return HttpReply {head.status, raw.substr(head.body_start, head.length)};
		}
		if (raw.size() > kMaxHttpReply + 64 * 1024) {
			throw std::runtime_error("the response is too large");
		}
		auto count = ReceiveSome(handle, buffer, sizeof(buffer));
		if (count > 0) {
			raw.append(buffer, static_cast<size_t>(count));
		} else if (count == 0) {
			if (have_head && !head.chunked && !head.has_length) {
				return HttpReply {head.status, raw.substr(head.body_start)};
			}
			throw std::runtime_error("the connection closed before the response was complete");
		} else if (WouldBlock()) {
			auto ready = WaitSocket(handle, false, deadline);
			if (ready == 0) {
				throw std::runtime_error("timed out waiting for the response");
			}
			if (ready < 0) {
				throw std::runtime_error("the connection failed while receiving");
			}
		} else {
			throw std::runtime_error("the connection failed while receiving");
		}
	}
}

bool HasLineBreak(const std::string &text) {
	return text.find_first_of("\r\n") != std::string::npos;
}

} // namespace

HttpUrl ParseHttpUrl(const std::string &url) {
	const std::string scheme = "http://";
	if (url.compare(0, scheme.size(), scheme) != 0) {
		throw std::invalid_argument("not a plain http:// URL");
	}
	auto rest = url.substr(scheme.size());
	auto slash = rest.find('/');
	HttpUrl result;
	result.authority = rest.substr(0, slash);
	result.path = slash == std::string::npos ? "" : rest.substr(slash);
	if (result.authority.empty() || result.authority.find('@') != std::string::npos) {
		throw std::invalid_argument("no host, or user information in the URL");
	}
	if (result.path.find_first_of("?#") != std::string::npos) {
		throw std::invalid_argument("a query or fragment in the URL");
	}
	std::string port = "80";
	if (result.authority[0] == '[') {
		auto close = result.authority.find(']');
		if (close == std::string::npos) {
			throw std::invalid_argument("an unclosed IPv6 literal");
		}
		result.host = result.authority.substr(1, close - 1);
		auto after = result.authority.substr(close + 1);
		if (!after.empty()) {
			if (after[0] != ':') {
				throw std::invalid_argument("characters after an IPv6 literal");
			}
			port = after.substr(1);
		}
	} else {
		auto colon = result.authority.rfind(':');
		result.host = result.authority.substr(0, colon);
		if (colon != std::string::npos) {
			port = result.authority.substr(colon + 1);
		}
	}
	if (result.host.empty() || result.host.find_first_of(" \t\r\n/\\") != std::string::npos) {
		throw std::invalid_argument("a malformed host");
	}
	if (!AllDigits(port) || port.size() > 5 || std::stoi(port) < 1 || std::stoi(port) > 65535) {
		throw std::invalid_argument("a malformed port");
	}
	result.port = port;
	while (!result.path.empty() && result.path.back() == '/') {
		result.path.pop_back();
	}
	return result;
}

bool DecodeChunked(const std::string &raw, std::string &body) {
	body.clear();
	size_t pos = 0;
	while (true) {
		auto line_end = raw.find("\r\n", pos);
		if (line_end == std::string::npos) {
			return false;
		}
		auto size_text = raw.substr(pos, line_end - pos);
		size_text = Trim(size_text.substr(0, size_text.find(';')));
		if (!AllHex(size_text) || size_text.size() > 7) {
			throw std::runtime_error("a malformed chunk size");
		}
		auto size = static_cast<size_t>(std::stoul(size_text, nullptr, 16));
		pos = line_end + 2;
		if (size == 0) {
			// The trailer section ends with an empty line.
			return raw.compare(pos, 2, "\r\n") == 0 || raw.find("\r\n\r\n", pos) != std::string::npos;
		}
		if (raw.size() < pos + size + 2) {
			return false;
		}
		if (raw.compare(pos + size, 2, "\r\n") != 0) {
			throw std::runtime_error("a chunk without its line break");
		}
		body.append(raw, pos, size);
		if (body.size() > kMaxHttpReply) {
			throw std::runtime_error("the response is too large");
		}
		pos += size + 2;
	}
}

HttpReply HttpPost(const HttpUrl &url, const std::string &path,
                   const std::vector<std::pair<std::string, std::string>> &headers, const std::string &body,
                   int64_t timeout_ms) {
	if (HasLineBreak(path)) {
		throw std::invalid_argument("a line break in the request path");
	}
	std::string request = "POST " + path + " HTTP/1.1\r\nHost: " + url.authority + "\r\n";
	for (auto &header : headers) {
		if (HasLineBreak(header.first) || HasLineBreak(header.second)) {
			throw std::invalid_argument("a line break in header " + header.first);
		}
		request += header.first + ": " + header.second + "\r\n";
	}
	request += "Content-Length: " + std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n";
	request += body;

	StartSockets();
	auto deadline = MonotonicMs() + timeout_ms;
	SocketGuard socket;
	socket.handle = Connect(url, deadline);
	SendAll(socket.handle, request, deadline);
	return ReadReply(socket.handle, deadline);
}

} // namespace qod
