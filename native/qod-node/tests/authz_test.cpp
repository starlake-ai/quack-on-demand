// Unit tests for authz: the prelude's exact form, the refresh rules and the parse screen's
// per-connection book, without DuckDB. Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "authz.hpp"
#include "test_handles.hpp"

#include <iostream>
#include <string>
#include <vector>

namespace {

using qod_test::kKey;
using qod_test::kNode;
using qod_test::Mint;
using qod_test::Prelude;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

void ExpectMentions(const std::string &text, const std::string &needle, const std::string &what) {
	if (text.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << text << "\" does not mention \"" << needle << "\"" << std::endl;
		failures++;
	}
}

int64_t now_ms = 1761200000000;

} // namespace

int main() {
	// ---- the prelude: exactly the manager's spelling
	Expect(qod::PreludeHandle("CALL system.main.qod_session('abc.0f')") == "abc.0f", "the prelude");
	Expect(qod::PreludeHandle("CALL qod_session('abc.0f')").empty(), "unqualified, a macro could shadow it");
	Expect(qod::PreludeHandle("call system.main.qod_session('abc.0f')").empty(), "another spelling is no prelude");
	Expect(qod::PreludeHandle(" CALL system.main.qod_session('abc.0f')").empty(), "a leading blank");
	Expect(qod::PreludeHandle("CALL system.main.qod_session('a'); SELECT 1").empty(), "a second statement");
	Expect(qod::PreludeHandle("CALL system.main.qod_session('a''b')").empty(), "a quote inside the handle");
	Expect(qod::PreludeHandle("CALL system.main.qod_session('')").empty(), "no handle");

	// ---- refresh rules
	qod::Handle next;
	next.principal = {"u-1", "alice", "acme", false, false};
	next.policy_version = 3;
	Expect(qod::RefreshRefusal(nullptr, next).empty(), "the first handle installs");
	qod::Installed current {next.principal, 3, now_ms + 60000, "n-1"};
	Expect(qod::RefreshRefusal(&current, next).empty(), "the same principal refreshes");
	auto other = next;
	other.principal.id = "u-2";
	ExpectMentions(qod::RefreshRefusal(&current, other), "another principal", "another principal");
	auto promoted = next;
	promoted.principal.superuser = true;
	ExpectMentions(qod::RefreshRefusal(&current, promoted), "another principal", "a changed flag");
	auto older = next;
	older.policy_version = 2;
	ExpectMentions(qod::RefreshRefusal(&current, older), "older policy version", "a lower policy version");
	auto rescoped = next;
	rescoped.ctx = std::string(64, 'b');
	Expect(qod::RefreshRefusal(&current, rescoped).empty(), "a refresh may change the session context");

	// ---- the parse screen
	qod::NodeAuthz authz(kNode, kKey, [] { return now_ms; });
	Expect(authz.Screen("c-1", "SELECT 1") == qod::kNoPrincipal, "nothing recorded yet");
	auto first = Mint("u-1", now_ms + 60000);
	Expect(authz.Screen("c-1", Prelude(first)).empty(), "the prelude is admitted");
	Expect(authz.Screen("c-1", "SELECT 1").empty(), "then the connection's statements");
	Expect(authz.Screen("c-2", "SELECT 1") == qod::kNoPrincipal, "a principal is recorded per connection");
	ExpectMentions(authz.Screen("c-1", Prelude(Mint("u-2", now_ms + 60000))), "another principal",
	               "another principal on c-1");
	ExpectMentions(authz.Screen("c-3", Prelude("not-a-handle")), "session handle refused", "a malformed handle");
	// The screen only peeks at the nonce: qod_session consumes it on the session.
	Expect(authz.Consume(authz.Verify(first)), "the session consumes the nonce");
	ExpectMentions(authz.Screen("c-4", Prelude(first)), "already used", "a consumed handle elsewhere");

	// ---- the book keeps the handle that installed: the screen's decide calls carry it
	auto fifth = Mint("u-5", now_ms + 60000);
	Expect(authz.Screen("c-5", Prelude(fifth)).empty(), "u-5 installs");
	qod::Installed recorded;
	Expect(authz.Screen("c-5", "SELECT 1", &recorded).empty(), "u-5's statement is admitted");
	Expect(recorded.handle == fifth && recorded.replica == "http://127.0.0.1:20900" && recorded.principal.id == "u-5" &&
	           recorded.ctx == std::string(64, 'c'),
	       "with the connection's record: handle, replica, principal, ctx");
	qod::Installed untouched;
	Expect(authz.Screen("c-5", Prelude(Mint("u-5", now_ms + 60000)), &untouched).empty() && untouched.handle.empty(),
	       "a prelude fills no record: it has no content to screen");

	// ---- decision 23: the S3 credential scopes qod_session installed, by the handle's nonce
	authz.RecordCredentialScopes("n-scopes-1", {"s3://acme-etl/"}, now_ms + 60000);
	Expect(authz.CredentialScopes("n-scopes-1") == std::vector<std::string> {"s3://acme-etl/"},
	       "recorded scopes are read back");
	Expect(authz.CredentialScopes("n-scopes-2").empty(), "an unknown nonce has none");
	authz.RecordCredentialScopes("n-scopes-3", {"s3://b/"}, now_ms);
	Expect(authz.CredentialScopes("n-scopes-3").empty(), "expired scopes are none");
	now_ms += 61000;
	Expect(authz.Screen("c-1", "SELECT 1") == qod::kNoPrincipal, "an expired record");
	// The book is advisory: once a record expired it admits another principal's prelude; the
	// session refuses it (authz_engine_test).
	Expect(authz.Screen("c-1", Prelude(Mint("u-9", now_ms + 60000))).empty(),
	       "after expiry the book admits another principal");

	if (failures == 0) {
		std::cout << "authz_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
