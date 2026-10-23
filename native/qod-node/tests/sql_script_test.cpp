// Unit tests for sql_script: statement splitting, literal quoting and the serve statement. Built with -DQOD_NODE_TESTS=ON
// and run by CTest (ctest --test-dir <build>).

#include "sql_script.hpp"

#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

void ExpectStatements(const std::string &sql, const std::vector<std::string> &expected) {
	auto actual = qod::SplitStatements(sql);
	bool same = actual.size() == expected.size();
	for (size_t i = 0; same && i < actual.size(); i++) {
		same = actual[i] == expected[i];
	}
	if (!same) {
		std::cerr << "FAIL: SplitStatements(" << sql << ") gave " << actual.size() << " statement(s):" << std::endl;
		for (auto &statement : actual) {
			std::cerr << "  [" << statement << "]" << std::endl;
		}
		failures++;
	}
}

} // namespace

int main() {
	ExpectStatements("", {});
	ExpectStatements("  \n ;; \n", {});
	ExpectStatements("SELECT 1", {"SELECT 1"});
	ExpectStatements("SELECT 1;\nSELECT 2;", {"SELECT 1", "SELECT 2"});
	// Separators inside a string, a quoted identifier or a comment do not split.
	ExpectStatements("SELECT 'a;b'; SELECT 2", {"SELECT 'a;b'", "SELECT 2"});
	ExpectStatements("SELECT \"x;y\" FROM t; SELECT 3", {"SELECT \"x;y\" FROM t", "SELECT 3"});
	ExpectStatements("SELECT 1 -- note; not a separator\n; SELECT 4",
	                 {"SELECT 1 -- note; not a separator", "SELECT 4"});
	ExpectStatements("SELECT 1 /* a; b */; SELECT 5", {"SELECT 1 /* a; b */", "SELECT 5"});
	// A statement spanning lines stays whole.
	ExpectStatements("ATTACH 'x'\n  (READ_ONLY);\nUSE x;", {"ATTACH 'x'\n  (READ_ONLY)", "USE x"});
	// A statement that does not parse is still split out on its own, so it can fail alone.
	ExpectStatements("SELEC 1; SELECT 6", {"SELEC 1", "SELECT 6"});

	Expect(qod::QuoteLiteral("abc") == "'abc'", "QuoteLiteral plain");
	Expect(qod::QuoteLiteral("o'neil") == "'o''neil'", "QuoteLiteral doubles a single quote");

	// The node serves plain HTTP to the manager, on any hostname, and reads back only listen_url.
	auto serve = qod::ServeStatement("0.0.0.0", 21901, "t'ok");
	Expect(serve.rfind("SELECT listen_url FROM quack_serve(", 0) == 0, "ServeStatement reads back listen_url only");
	Expect(serve.find("'quack:0.0.0.0:21901'") != std::string::npos, "ServeStatement listen address");
	Expect(serve.find("token := 't''ok'") != std::string::npos, "ServeStatement quotes the token");
	Expect(serve.find("disable_ssl := true") != std::string::npos, "ServeStatement serves plain HTTP");
	Expect(serve.find("allow_other_hostname := true") != std::string::npos, "ServeStatement allows other hostnames");

	if (failures == 0) {
		std::cout << "sql_script_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
