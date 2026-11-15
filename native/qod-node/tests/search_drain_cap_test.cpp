// Search Phase 0 part 2, Task 7a Step 1: the drain probes refuse a rowid at or above 2^27 before any
// allocation for it, so no input a tenant may write makes the node allocate by its value.
//
// On an in-memory DuckDB with the search functions registered:
//   - qod_search_drain of 2^27 - 1 returns one row;
//   - qod_search_drain of 2^27, qod_search_drain_parallel of 2^40 (and the sink form, once registered,
//     in search_sink_test) fail with "rowid out of range";
//   - the process's peak resident set grows by less than 64 MiB across the refused calls;
//   - under SET memory_limit = '32MB', a parallel drain whose bitsets would need 16 MiB per local
//     state fails with an out-of-memory error (the bitsets are taken from DuckDB's buffer manager), and
//     the database answers the next statement (the std::vector form of the test hook is not bounded).

#include "search_functions.hpp"
#include "search_sink.hpp"

#include "duckdb.hpp"

#include <cstdint>
#include <iostream>
#include <string>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#define PSAPI_VERSION 2
#include <windows.h>
#include <psapi.h>
#else
#include <sys/resource.h>
#endif

using namespace duckdb;

namespace {

int g_failures = 0;

void Check(bool ok, const std::string &what) {
	std::cout << (ok ? "ok   " : "FAIL ") << what << std::endl;
	if (!ok) {
		g_failures++;
	}
}

//! The process's peak resident set, in bytes.
uint64_t PeakRss() {
#ifdef _WIN32
	PROCESS_MEMORY_COUNTERS counters;
	if (!GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof(counters))) {
		return 0;
	}
	return static_cast<uint64_t>(counters.PeakWorkingSetSize);
#else
	struct rusage usage;
	getrusage(RUSAGE_SELF, &usage);
#ifdef __APPLE__
	return static_cast<uint64_t>(usage.ru_maxrss); // bytes on macOS
#else
	return static_cast<uint64_t>(usage.ru_maxrss) * 1024; // kilobytes on Linux
#endif
#endif
}

std::string FirstLine(const std::string &s) {
	auto nl = s.find('\n');
	return nl == std::string::npos ? s : s.substr(0, nl);
}

//! Runs `sql`, expecting an error holding `needle`; false when it did not fail so.
bool ExpectError(Connection &con, const std::string &sql, const std::string &needle) {
	auto result = con.Query(sql);
	if (!result->HasError()) {
		Check(false, sql + ": expected an error holding '" + needle + "', got a result");
		return false;
	}
	auto error = result->GetError();
	bool ok = error.find(needle) != std::string::npos;
	Check(ok, sql + ": " + FirstLine(error));
	return ok;
}

//! Runs `sql`, expecting one row whose first column is `expected`.
void ExpectValue(Connection &con, const std::string &sql, int64_t expected) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		Check(false, sql + ": " + FirstLine(result->GetError()));
		return;
	}
	auto chunk = result->Fetch();
	bool ok = chunk && chunk->size() == 1 && chunk->GetValue(0, 0).GetValue<int64_t>() == expected;
	Check(ok, sql + " = " + std::to_string(expected) +
	              (chunk && chunk->size() == 1 ? " (got " + chunk->GetValue(0, 0).ToString() + ")" : ""));
}

} // namespace

int main() {
	static_assert(qod::search::kMaxRowidExclusive == 134217728ull, "the cap is 2^27");
	DuckDB db(nullptr);
	qod::RegisterSearchFunctions(*db.instance);
	Connection con(db);
	Check(!con.Query("SET threads = 16")->HasError(), "SET threads = 16");

	ExpectValue(con, "SELECT rows FROM qod_search_drain((SELECT 134217727::BIGINT))", 1);
	auto before = PeakRss();
	// Stop at the first refusal that does not hold: without the cap the next calls would ask the
	// allocator for 128 GiB and more.
	if (!ExpectError(con, "SELECT rows FROM qod_search_drain((SELECT 134217728::BIGINT))", "rowid out of range") ||
	    !ExpectError(con, "SELECT * FROM qod_search_drain_parallel((SELECT (1::BIGINT << 40)))",
	                 "rowid out of range") ||
	    !ExpectError(con, "SELECT rows FROM qod_search_drain((SELECT (1::BIGINT << 62)))", "rowid out of range")) {
		std::cout << "search_drain_cap: a refusal did not hold, stopped before the larger inputs" << std::endl;
		return 1;
	}
	auto after = PeakRss();
	auto grown = after > before ? after - before : 0;
	Check(grown < (uint64_t(64) << 20), "peak RSS grew by " + std::to_string(grown >> 20) +
	                                        " MiB across the refused calls (limit 64 MiB)");

	// The bitsets count against memory_limit: 2,000,000 rows (17 row groups, so several local states at
	// threads = 16), each value just below the cap, so every local state's bitset needs 16 MiB.
	Check(!con.Query("CREATE TABLE near_cap AS SELECT (134217727 - (i % 1024))::BIGINT AS r FROM range(2000000) t(i)")
	           ->HasError(),
	      "create near_cap");
	Check(!con.Query("SET memory_limit = '32MB'")->HasError(), "SET memory_limit = '32MB'");
	ExpectError(con, "SELECT sum(rows) FROM qod_search_drain_parallel((SELECT r FROM near_cap))", "Out of Memory");
	// The same drain with the bitsets in a std::vector is not bounded by memory_limit: this is what the
	// buffer manager form changes.
	qod::UseVectorBitsetsForTest(true);
	ExpectValue(con, "SELECT sum(rows) FROM qod_search_drain_parallel((SELECT r FROM near_cap))", 2000000);
	qod::UseVectorBitsetsForTest(false);
	Check(!con.Query("RESET memory_limit")->HasError(), "RESET memory_limit");
	ExpectValue(con, "SELECT sum(rows) FROM qod_search_drain_parallel((SELECT r FROM near_cap))", 2000000);
	ExpectValue(con, "SELECT max_rowid FROM qod_search_drain((SELECT r FROM near_cap))", 134217727);

	// A UNION input runs the call in two pipelines, so qod_search_drain gets a second local state despite
	// MaxThreads() = 1 (review C1). It refuses that with an ordinary error, never one that invalidates
	// the database: every connection answers afterwards.
	{
		auto result = con.Query("SELECT rows FROM qod_search_drain((SELECT rowid FROM near_cap WHERE rowid < 10 UNION ALL "
		                        "SELECT rowid FROM near_cap WHERE rowid >= 10 AND rowid < 20))");
		auto error = result->HasError() ? result->GetError() : std::string();
		Check(result->HasError() && error.find("qod_search_drain_sink") != std::string::npos &&
		          error.find("INTERNAL") == std::string::npos,
		      "qod_search_drain of a UNION input refused as an ordinary error: " + FirstLine(error));
		Connection other(db);
		ExpectValue(con, "SELECT 42", 42);
		ExpectValue(other, "SELECT 42", 42);
		ExpectValue(other, "SELECT sum(rows) FROM qod_search_drain_parallel((SELECT rowid FROM near_cap WHERE rowid < 10 "
		                   "UNION ALL SELECT rowid FROM near_cap WHERE rowid >= 10 AND rowid < 20))",
		            20);
	}

	// The bitset never grows past the cap's 2^21 words (16 MiB): two rowids just under the cap would
	// otherwise double it to 32 MiB (review M1).
	Check(!con.Query("SET memory_limit = '40MB'")->HasError(), "SET memory_limit = '40MB'");
	ExpectValue(con, "SELECT rows FROM qod_search_drain((SELECT 134217600::BIGINT UNION ALL SELECT 134217727::BIGINT "
	                 "ORDER BY 1))",
	            2);
	Check(!con.Query("RESET memory_limit")->HasError(), "RESET memory_limit");

	std::cout << (g_failures == 0 ? "search_drain_cap: all checks passed" : "search_drain_cap: failures")
	          << std::endl;
	return g_failures == 0 ? 0 : 1;
}
