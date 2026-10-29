// What each step of a user's statement costs on the node, in process (Task 14, spike item 8): the
// describer, the call-name walk (CONTRACT Addendum 4 item 32 B), the parse screen's content rules, the
// verdict cache lookup, verifying a verdict, and the decide call's transport with the replica named by
// IP and by `localhost` (hand-off T7-M5: the name is resolved outside the call's deadline). Not a ctest:
// timings belong on a quiet machine, run by hand. Not built on Windows (socket_compat.hpp must not meet
// DuckDB's headers there). Prints one line per step, p50 and p99 over
// kRounds after kWarmup; exits 1 only when a step fails outright.
//
//   native/qod-node/build/authz_bench

#include "crypto.hpp"
#include "decide_client.hpp"
#include "describe_harness.hpp"
#include "describer.hpp"
#include "http_post.hpp"
#include "screen_rules.hpp"
#include "socket_compat.hpp"
#include "test_http_server.hpp"
#include "test_verdicts.hpp"
#include "verdict.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <functional>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr int kWarmup = 200;
constexpr int kRounds = 2000;

int failures = 0;

int64_t Nanos() {
	return std::chrono::duration_cast<std::chrono::nanoseconds>(
	           std::chrono::steady_clock::now().time_since_epoch())
	    .count();
}

double Percentile(std::vector<int64_t> samples, double p) {
	std::sort(samples.begin(), samples.end());
	auto rank = std::max<size_t>(1, static_cast<size_t>(std::ceil(p / 100.0 * samples.size())));
	return samples[rank - 1] / 1e6;
}

//! `step` kWarmup times, then kRounds timed: its p50 and p99 in milliseconds, printed under `label`.
void Time(const std::string &label, const std::function<void()> &step) {
	std::vector<int64_t> samples;
	samples.reserve(kRounds);
	try {
		for (int i = 0; i < kWarmup + kRounds; i++) {
			auto start = Nanos();
			step();
			if (i >= kWarmup) {
				samples.push_back(Nanos() - start);
			}
		}
	} catch (std::exception &error) {
		std::cerr << "FAIL: " << label << ": " << error.what() << std::endl;
		failures++;
		return;
	}
	std::printf("[authz-bench] %-58s p50=%.4f ms p99=%.4f ms n=%d\n", label.c_str(), Percentile(samples, 50),
	            Percentile(samples, 99), kRounds);
}

//! The statement the hook times, and what it times it as.
struct Bench {
	bool on = false;
	std::string label;
};

Bench &TheBench() {
	static Bench bench;
	return bench;
}

//! On the bound plan of the statement under bench: the describer alone (a principal's local refusals,
//! which every principal pays), then with the call-name walk over the text, and the parse screen's
//! content rules over the same text (which qod_screen runs before binding).
void BenchPostBind(duckdb::PlannerExtensionInput &input, duckdb::BoundStatement &statement) {
	auto &bench = TheBench();
	if (!bench.on || !statement.plan) {
		return;
	}
	bench.on = false;
	auto &context = input.context;
	auto text = context.GetCurrentQuery();
	auto alice = qod_test::Alice();
	auto &plan = *statement.plan;
	Time(bench.label + ": describe (no walk)", [&] { qod::Describe(context, plan, alice, nullptr); });
	Time(bench.label + ": describe + call-name walk", [&] { qod::Describe(context, plan, alice, &text); });
	Time(bench.label + ": parse screen content rules", [&] {
		auto outcome = qod::ScreenStatement(context, text);
		if (!outcome.refusal.empty()) {
			throw std::runtime_error(outcome.refusal);
		}
	});
}

void Run(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		throw std::runtime_error(sql + ": " + result->GetError());
	}
}

void Statement(duckdb::Connection &con, const std::string &label, const std::string &sql) {
	TheBench().on = true;
	TheBench().label = label;
	try {
		Run(con, sql);
	} catch (std::exception &error) {
		std::cerr << "FAIL: " << label << ": " << error.what() << std::endl;
		failures++;
	}
	TheBench().on = false;
}

std::string Columns(int first) {
	std::string text;
	for (int c = first; c < first + 6; c++) {
		text += (text.empty() ? "" : ", ") + std::string("count(c") + std::to_string(c) + ")";
	}
	return text;
}

void PlanSteps() {
	duckdb::DuckDB db(nullptr);
	duckdb::PlannerExtension planner;
	planner.post_bind_function = BenchPostBind;
	duckdb::PlannerExtension::Register(duckdb::DBConfig::GetConfig(*db.instance), planner);
	duckdb::Connection con(db);
	std::string cols;
	for (int c = 0; c < 18; c++) {
		cols += (cols.empty() ? "" : ", ") + std::string("i AS c") + std::to_string(c);
	}
	Run(con, "CREATE TABLE w AS SELECT " + cols + " FROM range(1000) t(i)");
	Run(con, "CREATE VIEW v1 AS SELECT * FROM w");
	Run(con, "CREATE VIEW v2 AS SELECT c0, fdiv(c1, 2) AS h FROM v1");
	auto plain = "SELECT " + Columns(12) + " FROM w";
	auto view = std::string("SELECT count(h), count(c0) FROM v2");
	Statement(con, "plain", plain);
	Statement(con, "view over view with fdiv()", view);
	// The router's USE prelude makes a managed pool's every text two statements. Each statement's text
	// is its own (ClientContext::GetCurrentQuery), so the hook, which times the first, times the USE's
	// steps alone: what the prelude adds per statement, the SELECT's being the lines above.
	Statement(con, "USE prelude (its own statement)", "USE memory.main; " + plain);
}

void CacheSteps() {
	qod::VerdictCache cache(10000);
	auto now = Nanos() / 1000000;
	qod::Verdict verdict;
	verdict.kind = "plan";
	verdict.allow = true;
	verdict.principal = "u-alice";
	verdict.policy_version = 1;
	verdict.ctx = std::string(64, 'c');
	verdict.expires_at = now + 600000;
	std::string wanted;
	for (int i = 0; i < 10000; i++) {
		verdict.fingerprint = qod::Hex(qod::Sha256("plan " + std::to_string(i)));
		cache.Put(verdict, now);
		if (i == 5000) {
			wanted = verdict.fingerprint;
		}
	}
	qod::Verdict found;
	Time("verdict cache lookup (10,000 entries, hit)", [&] {
		if (!cache.Find("u-alice", 1, verdict.ctx, wanted, now, found)) {
			throw std::runtime_error("the cached verdict was not found");
		}
	});
	auto description = std::string(600, 'd');
	Time("fingerprint (SHA-256 of a 600-byte description)", [&] { qod::Sha256(description); });
}

void DecideSteps() {
	auto now = Nanos() / 1000000;
	qod_test::VerdictFields fields;
	fields.fingerprint = std::string(64, 'f');
	fields.expires_at = now + 600000;
	auto signed_verdict = qod_test::SignVerdict(qod_test::VerdictJson(fields));
	qod::VerdictExpectation expected {"plan", fields.fingerprint, fields.principal, fields.policy_version, fields.ctx};
	Time("verify a verdict (MAC, fields)",
	     [&] { qod::VerifyVerdict(signed_verdict, qod_test::kKey, expected, now); });

	qod_test::TestHttpServer server([&](const qod_test::HttpRequest &) {
		qod_test::HttpAnswer answer;
		answer.body = signed_verdict;
		return answer;
	});
	auto by_ip = qod::ParseHttpUrl(server.Url());
	auto by_name = qod::ParseHttpUrl("http://localhost:" + by_ip.port);
	auto body = std::string(900, 'b');
	qod::StartSockets();
	for (auto *url : {&by_ip, &by_name}) {
		auto label = std::string("name resolution, getaddrinfo(") + url->host + ")";
		Time(label, [&] {
			addrinfo hints {};
			hints.ai_family = AF_UNSPEC;
			hints.ai_socktype = SOCK_STREAM;
			hints.ai_flags = AI_NUMERICSERV;
			addrinfo *found = nullptr;
			if (getaddrinfo(url->host.c_str(), url->port.c_str(), &hints, &found) != 0 || !found) {
				throw std::runtime_error("cannot resolve " + url->host);
			}
			freeaddrinfo(found);
		});
		Time("decide transport, HttpPost to " + url->authority + " (in-process server)", [&] {
			auto reply = qod::HttpPost(*url, std::string(qod::kDecidePath), {{"Content-Type", "application/json"}},
			                           body, 2000);
			if (reply.status != 200) {
				throw std::runtime_error("HTTP " + std::to_string(reply.status));
			}
		});
	}
}

} // namespace

int main() {
	PlanSteps();
	CacheSteps();
	DecideSteps();
	return failures == 0 ? 0 : 1;
}
