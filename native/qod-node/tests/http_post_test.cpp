// Unit tests for http_post: URL parsing, chunked decoding, and round trips against the test server
// (Content-Length, chunked, a dropped connection, a timeout, nothing listening). Built with
// -DQOD_NODE_TESTS=ON and run by CTest.

#include "http_post.hpp"
#include "test_http_server.hpp"

#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

void ExpectBadUrl(const std::string &url, const std::string &needle) {
	try {
		qod::ParseHttpUrl(url);
		std::cerr << "FAIL: accepted URL " << url << std::endl;
		failures++;
	} catch (std::invalid_argument &error) {
		if (std::string(error.what()).find(needle) == std::string::npos) {
			std::cerr << "FAIL: " << url << ": \"" << error.what() << "\" does not mention \"" << needle << "\""
			          << std::endl;
			failures++;
		}
	}
}

//! The message HttpPost throws, or "" when it returned.
std::string PostFailure(const std::string &url, int64_t timeout_ms) {
	try {
		qod::HttpPost(qod::ParseHttpUrl(url), "/x", {}, "{}", timeout_ms);
		return "";
	} catch (std::runtime_error &error) {
		return error.what();
	}
}

} // namespace

int main() {
	// ---- URLs: plain http only
	auto local = qod::ParseHttpUrl("http://127.0.0.1:20900");
	Expect(local.host == "127.0.0.1" && local.port == "20900" && local.path.empty() &&
	           local.authority == "127.0.0.1:20900",
	       "an address and a port");
	auto prefixed = qod::ParseHttpUrl("http://qod-manager.qod.svc:8080/qod/");
	Expect(prefixed.host == "qod-manager.qod.svc" && prefixed.path == "/qod", "a name and a path prefix");
	Expect(qod::ParseHttpUrl("http://[::1]:20900").host == "::1", "an IPv6 literal");
	Expect(qod::ParseHttpUrl("http://manager").port == "80", "the default port");
	ExpectBadUrl("https://manager:20900", "not a plain http");
	ExpectBadUrl("ftp://manager", "not a plain http");
	ExpectBadUrl("http://", "no host");
	ExpectBadUrl("http://user:pass@manager", "user information");
	ExpectBadUrl("http://manager:0", "port");
	ExpectBadUrl("http://manager:65536", "port");
	ExpectBadUrl("http://manager:x", "port");
	ExpectBadUrl("http://manager/a?b=c", "query");
	ExpectBadUrl("http://[::1", "IPv6");
	ExpectBadUrl("http://manager/a b", "space or control character");
	ExpectBadUrl("http://manager/a\tb", "space or control character");
	ExpectBadUrl(std::string("http://manager/a") + '\x7f', "space or control character");

	// ---- chunked bodies
	std::string body;
	Expect(qod::DecodeChunked("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n", body) && body == "hello world",
	       "two chunks");
	Expect(qod::DecodeChunked("5;ext=1\r\nhello\r\n0\r\nX-Trailer: y\r\n\r\n", body) && body == "hello",
	       "a chunk extension and a trailer");
	Expect(!qod::DecodeChunked("5\r\nhel", body), "an incomplete chunk");
	Expect(!qod::DecodeChunked("5\r\nhello\r\n0\r\n", body), "no end of the trailer section yet");
	try {
		qod::DecodeChunked("zz\r\nhello\r\n", body);
		Expect(false, "a malformed chunk size is refused");
	} catch (std::runtime_error &) {
	}
	try {
		qod::DecodeChunked("5;" + std::string(5000, 'x'), body);
		Expect(false, "a chunk size line with no end in sight is refused");
	} catch (std::runtime_error &error) {
		Expect(std::string(error.what()) == "a malformed chunk size", "a chunk size line too long");
	}
	try {
		qod::DecodeChunked("0\r\n" + std::string(70 * 1024, 'x'), body);
		Expect(false, "a trailer section with no end in sight is refused");
	} catch (std::runtime_error &error) {
		Expect(std::string(error.what()) == "the trailer section is too long", "a trailer section too long");
	}

	// ---- round trips
	qod_test::HttpAnswer answer;
	qod_test::TestHttpServer server([&answer](const qod_test::HttpRequest &) { return answer; });
	answer.body = "the verdict";
	auto reply = qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/api/internal/authz/decide",
	                           {{"Content-Type", "application/json"}, {"X-QoD-Node", "n-1"}}, "{\"a\":1}", 2000);
	Expect(reply.status == 200 && reply.body == "the verdict", "a Content-Length response");
	auto last = server.Last();
	Expect(last.method == "POST" && last.path == "/api/internal/authz/decide" && last.body == "{\"a\":1}" &&
	           last.headers["content-type"] == "application/json" && last.headers["x-qod-node"] == "n-1" &&
	           last.headers["connection"] == "close" && last.headers["content-length"] == "7",
	       "the request as sent");
	answer.chunked = true;
	answer.status = 503;
	reply = qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {}, "{}", 2000);
	Expect(reply.status == 503 && reply.body == "the verdict", "a chunked response");
	// Many small chunks and a trailer, arriving a few bytes at a time: the decoder resumes across
	// every boundary (inside a size line, a chunk, its line break and the trailer).
	answer.status = 200;
	answer.body = std::string(300, 'v') + "the end";
	answer.chunk_bytes = 7;
	answer.trailer = "X-Trailer: y\r\n";
	answer.segment_bytes = 3;
	reply = qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {}, "{}", 5000);
	Expect(reply.status == 200 && reply.body == answer.body, "a chunked body trickled in 3-byte pieces");
	answer.chunk_bytes = 0;
	answer.trailer.clear();
	answer.segment_bytes = 0;
	// A chunked body over kMaxHttpReply, in chunks under it.
	answer.body = std::string(qod::kMaxHttpReply + 1, 'v');
	answer.chunk_bytes = 64 * 1024;
	Expect(PostFailure(server.Url(), 5000) == "the response is too large", "a chunked body over kMaxHttpReply");
	answer.chunk_bytes = 0;
	answer.chunked = false;
	// Close-delimited bodies: no Content-Length, the end of the connection ends the body.
	answer.close_delimited = true;
	answer.body = "until the close";
	reply = qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {}, "{}", 2000);
	Expect(reply.status == 200 && reply.body == "until the close", "a close-delimited response");
	answer.body = std::string(qod::kMaxHttpReply, 'v');
	reply = qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {}, "{}", 5000);
	Expect(reply.body.size() == qod::kMaxHttpReply, "a close-delimited body of exactly kMaxHttpReply");
	answer.body = std::string(qod::kMaxHttpReply + 1, 'v');
	Expect(PostFailure(server.Url(), 5000) == "the response is too large", "a close-delimited body over kMaxHttpReply");
	answer.close_delimited = false;
	answer.body = "the verdict";
	answer.drop = true;
	Expect(PostFailure(server.Url(), 2000).find("closed before the response") != std::string::npos,
	       "a dropped connection");
	answer.drop = false;
	answer.delay_ms = 1000;
	auto started = std::chrono::steady_clock::now();
	Expect(PostFailure(server.Url(), 200).find("timed out") != std::string::npos, "a slow server times out");
	Expect(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(900),
	       "within the deadline, not the server's delay");
	answer.delay_ms = 0;
	Expect(PostFailure("http://127.0.0.1:" + std::to_string(qod_test::UnusedPort()), 2000)
	               .find("cannot connect") != std::string::npos,
	       "nothing listening");
	try {
		qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {{"X-QoD-Node", "n\r\nX-Injected: 1"}}, "{}", 2000);
		Expect(false, "a header holding a line break is refused");
	} catch (std::invalid_argument &) {
	}

	if (failures == 0) {
		std::cout << "http_post_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
