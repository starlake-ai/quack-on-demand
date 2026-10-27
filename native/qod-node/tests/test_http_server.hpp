// A one-thread HTTP/1.1 server on 127.0.0.1 for the decide client's tests, over POSIX sockets and
// Winsock, so ctest runs them on every platform qod-node builds for. One request per connection; the
// handler's answer, after its delay, is the whole response. No socket type appears here: tests that
// include DuckDB's headers include this one too.

#pragma once

#include <functional>
#include <map>
#include <memory>
#include <string>

namespace qod_test {

struct HttpRequest {
	std::string method;
	std::string path;
	//! Header names lowercased.
	std::map<std::string, std::string> headers;
	std::string body;
};

struct HttpAnswer {
	int status = 200;
	std::string body;
	//! Wait this long before answering: a slow replica.
	int delay_ms = 0;
	//! Send the body chunked instead of with a Content-Length.
	bool chunked = false;
	//! Close the connection without answering.
	bool drop = false;
};

class TestHttpServer {
public:
	explicit TestHttpServer(std::function<HttpAnswer(const HttpRequest &)> handler);
	~TestHttpServer();

	//! http://127.0.0.1:<port>
	std::string Url() const;
	//! The requests read so far.
	int Requests() const;
	//! The last request read.
	HttpRequest Last() const;

	struct State;

private:
	std::unique_ptr<State> state_;
};

//! A loopback port nothing listens on (bound, then closed).
int UnusedPort();

} // namespace qod_test
