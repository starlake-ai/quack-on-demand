// Unit tests for http_post: URL parsing, chunked decoding, and round trips against the test server
// (Content-Length, chunked, a dropped connection, a timeout, nothing listening). Built with
// -DQOD_NODE_TESTS=ON and run by CTest.

#include "http_post.hpp"
#include "socket_compat.hpp"
#include "test_http_server.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

int failures = 0;

#ifdef _WIN32
constexpr bool kWindows = true;
#else
constexpr bool kWindows = false;
#endif

//! 127.0.0.1:`port`, as a resolver lists it.
qod::ResolvedAddress Loopback(uint16_t port) {
	sockaddr_in address {};
	address.sin_family = AF_INET;
	address.sin_port = htons(port);
	address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	return qod::ResolvedAddress {AF_INET, std::string(reinterpret_cast<const char *>(&address), sizeof(address))};
}

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
	// 10 s: on Windows a refused connect is retried for about 2 s before it fails, so a 2 s deadline
	// ran out first and the call failed "timed out connecting" instead.
	auto unanswered = PostFailure("http://127.0.0.1:" + std::to_string(qod_test::UnusedPort()), 10000);
	Expect(unanswered.find("cannot connect") != std::string::npos, "nothing listening: " + unanswered);
	try {
		qod::HttpPost(qod::ParseHttpUrl(server.Url()), "/x", {{"X-QoD-Node", "n\r\nX-Injected: 1"}}, "{}", 2000);
		Expect(false, "a header holding a line break is refused");
	} catch (std::invalid_argument &) {
	}

	// ---- host names: resolved once, reused for the lifetime, again once forgotten (hand-off T7-M5)
	int64_t now = 1000;
	int resolved = 0;
	auto fail_next = false;
	qod::ResolvedAddresses known(
	    [&](const std::string &host, const std::string &port) {
		    resolved++;
		    if (fail_next) {
			    throw std::runtime_error("cannot resolve " + host);
		    }
		    return std::vector<qod::ResolvedAddress> {{2, host + "/" + port + "/" + std::to_string(resolved)}};
	    },
	    [&] { return now; }, 30000, 1000);
	auto fresh = false;
	auto first = known.Get("manager", "20900", fresh);
	Expect(fresh && resolved == 1, "a name is resolved the first time");
	now += 29999;
	Expect(known.Get("manager", "20900", fresh) == first && !fresh && resolved == 1,
	       "and reused within its lifetime");
	known.Get("manager", "20901", fresh);
	Expect(fresh && resolved == 2, "another port is another entry");
	now += 1;
	auto second = known.Get("manager", "20900", fresh);
	Expect(!(second == first) && fresh && resolved == 3, "a name past its lifetime is resolved again");
	known.Forget("manager", "20900");
	known.Get("manager", "20900", fresh);
	Expect(fresh && resolved == 4, "a forgotten name is resolved again");
	known.Forget("other", "1");
	fail_next = true;
	try {
		known.Get("missing", "1", fresh);
		Expect(false, "a name that resolves to nothing throws");
	} catch (std::runtime_error &error) {
		Expect(std::string(error.what()) == "cannot resolve missing", "naming the host");
	}
	try {
		known.Get("missing", "1", fresh);
		Expect(false, "a failure within the retry window throws again");
	} catch (std::runtime_error &error) {
		Expect(resolved == 5 && std::string(error.what()) == "cannot resolve missing",
		       "without resolving the name again");
	}
	fail_next = false;
	now += 1000;
	known.Get("missing", "1", fresh);
	Expect(fresh && resolved == 6, "a failed resolution is retried after the window");
	// A refresh that fails serves the last addresses, for the retry window, then resolves again.
	auto kept = known.Get("manager", "20900", fresh);
	now += 30000;
	fail_next = true;
	Expect(known.Get("manager", "20900", fresh) == kept && !fresh && resolved == 7,
	       "a failed refresh serves the stale addresses");
	Expect(known.Get("manager", "20900", fresh) == kept && !fresh && resolved == 7,
	       "and keeps serving them within the retry window, without resolving");
	fail_next = false;
	now += 1000;
	Expect(!(known.Get("manager", "20900", fresh) == kept) && fresh && resolved == 8,
	       "then resolves the name again");
	// The stale addresses go with Forget, as fresh ones do.
	now += 30000;
	fail_next = true;
	known.Get("manager", "20900", fresh);
	known.Forget("manager", "20900");
	now += 1000;
	try {
		known.Get("manager", "20900", fresh);
		Expect(false, "a forgotten name whose refresh fails throws");
	} catch (std::runtime_error &) {
	}
	fail_next = false;
	qod::ResolvedAddresses empty([](const std::string &, const std::string &) { return std::vector<qod::ResolvedAddress> {}; },
	                             [] { return int64_t(0); }, 30000);
	try {
		empty.Get("nothing", "1", fresh);
		Expect(false, "a name with no address throws");
	} catch (std::runtime_error &error) {
		Expect(std::string(error.what()) == "cannot resolve nothing", "a name with no address names the host");
	}
	// Prefer moves the address that connected to the front, the others keeping their order.
	qod::ResolvedAddresses ordered(
	    [](const std::string &, const std::string &) {
		    return std::vector<qod::ResolvedAddress> {{2, "a"}, {2, "b"}, {2, "c"}};
	    },
	    [] { return int64_t(0); }, 30000);
	ordered.Get("n", "1", fresh);
	ordered.Prefer("n", "1", qod::ResolvedAddress {2, "c"});
	auto reordered = ordered.Get("n", "1", fresh);
	Expect(reordered.size() == 3 && reordered[0].sockaddr == "c" && reordered[1].sockaddr == "a" &&
	           reordered[2].sockaddr == "b" && !fresh,
	       "the preferred address first");
	ordered.Prefer("n", "1", qod::ResolvedAddress {2, "z"});
	Expect(ordered.Get("n", "1", fresh).size() == 3, "an address the name does not list changes nothing");

	// One caller resolves a name at a time: the others wait for a first resolution, and are served the
	// last addresses while a refresh is in flight.
	{
		std::mutex gate_lock;
		std::condition_variable gate;
		auto open = false;
		std::atomic<int> calls {0};
		std::atomic<int64_t> clock {0};
		qod::ResolvedAddresses single(
		    [&](const std::string &, const std::string &) {
			    calls++;
			    std::unique_lock<std::mutex> guard(gate_lock);
			    gate.wait(guard, [&] { return open; });
			    return std::vector<qod::ResolvedAddress> {{2, "r" + std::to_string(calls.load())}};
		    },
		    [&] { return clock.load(); }, 30000);
		std::vector<std::thread> waiters;
		std::atomic<int> got {0};
		for (int i = 0; i < 4; i++) {
			waiters.emplace_back([&] {
				auto unused = false;
				if (single.Get("n", "1", unused)[0].sockaddr == "r1") {
					got++;
				}
			});
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(200));
		Expect(calls == 1, "concurrent first resolutions of one name resolve it once");
		{
			std::lock_guard<std::mutex> guard(gate_lock);
			open = true;
		}
		gate.notify_all();
		for (auto &waiter : waiters) {
			waiter.join();
		}
		Expect(got == 4, "and every caller gets its addresses");
		// A refresh in flight: the other callers get the stale addresses at once.
		{
			std::lock_guard<std::mutex> guard(gate_lock);
			open = false;
		}
		clock = 30000;
		std::thread refresher([&] {
			auto unused = false;
			single.Get("n", "1", unused);
		});
		while (calls < 2) {
			std::this_thread::sleep_for(std::chrono::milliseconds(5));
		}
		Expect(single.Get("n", "1", fresh)[0].sockaddr == "r1" && !fresh && calls == 2,
		       "a refresh in flight serves the stale addresses to the others");
		{
			std::lock_guard<std::mutex> guard(gate_lock);
			open = true;
		}
		gate.notify_all();
		refresher.join();
		Expect(single.Get("n", "1", fresh)[0].sockaddr == "r2" && !fresh, "then the refreshed ones");
	}

	// The in-call re-resolution: kept addresses that refuse are forgotten and the name is resolved again
	// within the same call; a replica that moved costs no failed call. Platform-free (127.0.0.1 only).
	{
		auto live = static_cast<uint16_t>(std::stoi(qod::ParseHttpUrl(server.Url()).port));
		auto dead = static_cast<uint16_t>(qod_test::UnusedPort());
		std::vector<uint16_t> answers;
		int lookups = 0;
		qod::ResolvedAddresses moving(
		    [&](const std::string &, const std::string &) {
			    auto port = answers[std::min<size_t>(lookups, answers.size() - 1)];
			    lookups++;
			    return std::vector<qod::ResolvedAddress> {Loopback(port)};
		    },
		    [] { return int64_t(0); }, 30000);
		answers = {dead, live};
		answer = qod_test::HttpAnswer {};
		answer.body = "moved";
		auto replica = qod::ParseHttpUrl("http://replica:20900");
		moving.Get(replica.host, replica.port, fresh);
		// 10 s: on Windows a refused connect is retried for about 2 s before it fails.
		Expect(qod::HttpPost(replica, "/x", {}, "{}", 10000, moving).body == "moved" && lookups == 2,
		       "a replica that moved is reached in the same call, the name resolved again");
		moving.Get(replica.host, replica.port, fresh);
		Expect(!fresh && lookups == 2, "and its new address is kept");
		// The name still resolves to the address that refuses: tried once, the call fails.
		answers = {dead};
		lookups = 0;
		auto stuck = qod::ParseHttpUrl("http://stuck:20900");
		moving.Get(stuck.host, stuck.port, fresh);
		try {
			qod::HttpPost(stuck, "/x", {}, "{}", 10000, moving);
			Expect(false, "a replica whose name resolves to the same refusing address fails");
		} catch (std::runtime_error &error) {
			auto message = std::string(error.what());
			Expect(lookups == 2 && (message.find("cannot connect") != std::string::npos ||
			                        message.find("timed out connecting") != std::string::npos),
			       "after one more resolution, not a second connect to the same address: " + message);
		}
		moving.Get(stuck.host, stuck.port, fresh);
		Expect(fresh, "its addresses are forgotten");
	}

	// HttpPost reaches a server by name twice: the second call takes the kept addresses. 10 s and either
	// failure message: on Windows `localhost` lists ::1 first and a refused connect is retried for about
	// 2 s, which a 2 s deadline would spend on ::1 alone.
	answer = qod_test::HttpAnswer {};
	answer.body = "by name";
	auto by_name = qod::ParseHttpUrl("http://localhost:" + qod::ParseHttpUrl(server.Url()).port);
	Expect(qod::HttpPost(by_name, "/x", {}, "{}", 10000).body == "by name", "a replica named localhost");
	qod::HostAddresses().Get(by_name.host, by_name.port, fresh);
	Expect(!fresh, "its addresses are kept");
	Expect(qod::HttpPost(by_name, "/x", {}, "{}", 10000).body == "by name", "and used again");
	Expect(qod::HostAddresses().Get(by_name.host, by_name.port, fresh).front().family == AF_INET,
	       "the address that connected (the listener's IPv4 one) first");
	// Kept addresses that stop answering are forgotten, so the next call resolves the name again.
	auto gone = qod::ParseHttpUrl("http://localhost:" + std::to_string(qod_test::UnusedPort()));
	auto refused = PostFailure("http://" + gone.authority, 10000);
	Expect(refused.find("cannot connect") != std::string::npos ||
	           (kWindows && refused.find("timed out connecting") != std::string::npos),
	       "nothing listening on a named replica: " + refused);
	qod::HostAddresses().Get(gone.host, gone.port, fresh);
	Expect(fresh, "a named replica that did not answer is resolved again");

	if (failures == 0) {
		std::cout << "http_post_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
