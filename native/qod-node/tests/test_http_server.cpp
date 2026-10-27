#include "test_http_server.hpp"

#include "socket_compat.hpp"

#include <atomic>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <thread>

namespace qod_test {

struct TestHttpServer::State {
	std::function<HttpAnswer(const HttpRequest &)> handler;
	qod::SocketHandle listener = qod::kNoSocket;
	int port = 0;
	std::atomic<bool> stop {false};
	mutable std::mutex lock;
	int requests = 0;
	HttpRequest last;
	std::thread thread;
};

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
	return first == std::string::npos ? "" : text.substr(first, text.find_last_not_of(" \t") - first + 1);
}

qod::SocketHandle Listen(int &port) {
	qod::StartSockets();
	auto listener = qod::OpenStreamSocket(AF_INET);
	if (listener == qod::kNoSocket) {
		throw std::runtime_error("test server: no socket");
	}
	sockaddr_in address {};
	address.sin_family = AF_INET;
	address.sin_port = 0;
	address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	socklen_t length = sizeof(address);
	if (bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0 || listen(listener, 16) != 0 ||
	    getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) != 0) {
		qod::CloseSocket(listener);
		throw std::runtime_error("test server: cannot listen on 127.0.0.1");
	}
	port = ntohs(address.sin_port);
	return listener;
}

//! Reads one request (head, then Content-Length bytes) within 5 s; false when it cannot.
bool ReadRequest(qod::SocketHandle connection, HttpRequest &request) {
	auto deadline = qod::MonotonicMs() + 5000;
	std::string raw;
	size_t body_start = std::string::npos;
	size_t length = 0;
	char buffer[4096];
	while (body_start == std::string::npos || raw.size() < body_start + length) {
		auto count = qod::ReceiveSome(connection, buffer, sizeof(buffer));
		if (count > 0) {
			raw.append(buffer, static_cast<size_t>(count));
		} else if (count < 0 && qod::WouldBlock()) {
			if (qod::WaitSocket(connection, false, deadline) != 1) {
				return false;
			}
		} else {
			return false;
		}
		if (body_start == std::string::npos) {
			auto end = raw.find("\r\n\r\n");
			if (end == std::string::npos) {
				continue;
			}
			body_start = end + 4;
			auto line_end = raw.find("\r\n");
			auto request_line = raw.substr(0, line_end);
			auto space = request_line.find(' ');
			request.method = request_line.substr(0, space);
			request.path = request_line.substr(space + 1, request_line.rfind(' ') - space - 1);
			auto pos = line_end + 2;
			while (pos < end) {
				auto next = raw.find("\r\n", pos);
				auto line = raw.substr(pos, next - pos);
				pos = next + 2;
				auto colon = line.find(':');
				request.headers[Lower(Trim(line.substr(0, colon)))] = Trim(line.substr(colon + 1));
			}
			auto declared = request.headers.find("content-length");
			length = declared == request.headers.end() ? 0 : std::stoul(declared->second);
		}
	}
	request.body = raw.substr(body_start, length);
	return true;
}

void SendAll(qod::SocketHandle connection, const std::string &data) {
	auto deadline = qod::MonotonicMs() + 5000;
	size_t sent = 0;
	while (sent < data.size()) {
		auto count = qod::SendSome(connection, data.data() + sent, data.size() - sent);
		if (count > 0) {
			sent += static_cast<size_t>(count);
		} else if (count < 0 && qod::WouldBlock() && qod::WaitSocket(connection, true, deadline) == 1) {
			continue;
		} else {
			return;
		}
	}
}

std::string ToHex(size_t value) {
	const char *digits = "0123456789abcdef";
	std::string text;
	do {
		text.insert(text.begin(), digits[value % 16]);
		value /= 16;
	} while (value > 0);
	return text;
}

void Answer(qod::SocketHandle connection, const HttpAnswer &answer) {
	std::string response = "HTTP/1.1 " + std::to_string(answer.status) + " Test\r\nContent-Type: text/plain\r\n" +
	                       "Connection: close\r\n";
	if (answer.chunked) {
		// Two chunks and an empty trailer section: the split lands inside the body.
		auto half = answer.body.size() / 2;
		response += "Transfer-Encoding: chunked\r\n\r\n";
		for (auto &chunk : {answer.body.substr(0, half), answer.body.substr(half)}) {
			if (!chunk.empty()) {
				response += ToHex(chunk.size()) + "\r\n" + chunk + "\r\n";
			}
		}
		response += "0\r\n\r\n";
	} else {
		response += "Content-Length: " + std::to_string(answer.body.size()) + "\r\n\r\n" + answer.body;
	}
	SendAll(connection, response);
}

void Serve(TestHttpServer::State &state) {
	while (!state.stop) {
		if (qod::WaitSocket(state.listener, false, qod::MonotonicMs() + 50) != 1) {
			continue;
		}
		auto connection = accept(state.listener, nullptr, nullptr);
		if (connection == qod::kNoSocket) {
			continue;
		}
		// Accepted sockets inherit non-blocking mode on some platforms only: make it uniform. A client
		// that gave up (the timeout tests) must not kill the test with SIGPIPE when the answer goes out.
		qod::MakeNonBlocking(connection);
#ifdef SO_NOSIGPIPE
		int one = 1;
		setsockopt(connection, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
		HttpRequest request;
		if (ReadRequest(connection, request)) {
			{
				std::lock_guard<std::mutex> guard(state.lock);
				state.requests++;
				state.last = request;
			}
			auto answer = state.handler(request);
			if (answer.delay_ms > 0) {
				std::this_thread::sleep_for(std::chrono::milliseconds(answer.delay_ms));
			}
			if (!answer.drop) {
				Answer(connection, answer);
			}
		}
		qod::CloseSocket(connection);
	}
}

} // namespace

TestHttpServer::TestHttpServer(std::function<HttpAnswer(const HttpRequest &)> handler) : state_(new State()) {
	state_->handler = std::move(handler);
	state_->listener = Listen(state_->port);
	auto &state = *state_;
	state_->thread = std::thread([&state] { Serve(state); });
}

TestHttpServer::~TestHttpServer() {
	state_->stop = true;
	state_->thread.join();
	qod::CloseSocket(state_->listener);
}

std::string TestHttpServer::Url() const {
	return "http://127.0.0.1:" + std::to_string(state_->port);
}

int TestHttpServer::Requests() const {
	std::lock_guard<std::mutex> guard(state_->lock);
	return state_->requests;
}

HttpRequest TestHttpServer::Last() const {
	std::lock_guard<std::mutex> guard(state_->lock);
	return state_->last;
}

int UnusedPort() {
	int port = 0;
	qod::CloseSocket(Listen(port));
	return port;
}

} // namespace qod_test
