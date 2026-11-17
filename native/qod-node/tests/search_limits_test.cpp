// QoD Search limits and cancellation (spec 9, invariant 8; spec 12 Phase 1 items 21 and 23), for qod_search and
// qod_search_matches alike where the limit applies to both.
//
// One case per error: k 0 and k 10,001 (qod_search's), a 4,097-byte query, parentheses 33 deep, 65 terms, a boost on
// a group the collector cannot score in clause order, the unindexed rows cap, the unindexed sources cap (refused
// before any scan: the fallback's text scan counter does not move), search_memory_exceeded, and a cancel in the
// drain, in the statistics (the cancel flag set before the library's first pass), in the fallback's indexing, while
// the library searches (with the test hooks, qs_search held until its cancel flag is set and the interrupt sent once
// it is held; without them, or for qod_search_matches, an interrupt 5 ms in that counts only when the library itself
// answered search_cancelled) and in the matches iterator. Each asserts the exact error
// and that the client received no row: the statement read as a stream, the rows that reached the client before its
// error counted, 0. Beside each limit, its bound itself is admitted (k 1 and the knob's bound, 4,096 bytes, 32 deep,
// 64 terms, the cap at the backlog). Then the k default (Task 16, carried from its review): with QOD_SEARCH_MAX_K=5,
// k := NULL answers 5 rows (min(10, maxK)), and so does a call naming no k. Exits 77 when ducklake cannot load.

#include "search_fixtures.hpp"

#include <chrono>
#include <thread>

namespace {

const std::vector<std::string> kFunctions = {"qod_search", "qod_search_matches"};

std::string Call(const std::string &fn, const std::string &input, const std::string &query, const std::string &table,
                 const std::string &extra = "") {
	if (fn == "qod_search") {
		return Search(input, query, table, kCols, extra);
	}
	return Matches(input, query, table, kCols, extra);
}

//! `sql` read as a stream fails with `expected` in its error (then `then` after it, when given) and no row reaches
//! the client before it.
void Fails(Connection &con, const std::string &name, const std::string &sql, const std::string &expected,
           const std::string &then = "") {
	auto got = Stream(con, sql);
	auto at = got.error.find(expected);
	Check(got.failed && got.rows == 0 && at != std::string::npos &&
	          (then.empty() || got.error.find(then, at + expected.size()) != std::string::npos),
	      name + ": " + expected + (then.empty() ? "" : " ... " + then) + ", no row reached the client",
	      got.failed ? std::to_string(got.rows) + " rows before: " + FirstLine(got.error)
	                 : "it ended as a success with " + std::to_string(got.rows) + " rows");
}

//! `sql` read as a stream succeeds with `rows` rows (any count when rows < 0).
void Answers(Connection &con, const std::string &name, const std::string &sql, int64_t rows = -1) {
	auto got = Stream(con, sql);
	Check(!got.failed && (rows < 0 || got.rows == static_cast<idx_t>(rows)),
	      name + (rows < 0 ? ": admitted" : ": " + std::to_string(rows) + " rows"),
	      got.failed ? FirstLine(got.error) : std::to_string(got.rows) + " rows");
}

std::string Terms(int n) {
	std::string out;
	for (int i = 0; i < n; i++) {
		out += (i ? " w" : "w") + std::to_string(i % 40);
	}
	return out;
}

int Run() {
	Node node;
	OpenNode(node, "qod-search-limits");
	if (node.status != 0) {
		return node.status;
	}
	auto &setup = *node.setup;
	auto &sysc = *node.sysc;
	auto &client = *node.client;
	auto &data = node.data;
	// lt: 200,000 rows in 2 bundled files (the long search and the matches stream); lf: 3,000 rows in one unbundled
	// file and 5 inlined (the fallback); ls: 6 small unbundled files (the sources cap).
	const std::string text = "array_to_string(list_transform(range(30), lambda j: 'w' || ((i * 7 + j * 13) % 40)), ' ')";
	bool ok = Exec(setup, "CREATE TABLE lake.main.lt (id BIGINT, body VARCHAR)", "lt") &&
	          Exec(setup, "INSERT INTO lake.main.lt SELECT i, " + text + " FROM range(0, 100000) r(i) ORDER BY i", "lt 1") &&
	          Exec(setup, "INSERT INTO lake.main.lt SELECT i, " + text + " FROM range(100000, 200000) r(i) ORDER BY i",
	               "lt 2") &&
	          Exec(setup, "CREATE TABLE lake.main.lf (id BIGINT, body VARCHAR)", "lf") &&
	          Exec(setup, "INSERT INTO lake.main.lf SELECT i, " + text + " FROM range(0, 3000) r(i) ORDER BY i", "lf file") &&
	          Exec(setup, "INSERT INTO lake.main.lf SELECT i, 'w1 w2' FROM range(3000, 3005) r(i)", "lf inlined") &&
	          Exec(setup, "CREATE TABLE lake.main.ls (id BIGINT, body VARCHAR)", "ls");
	for (int f = 0; ok && f < 6; f++) {
		ok = Exec(setup,
		          "INSERT INTO lake.main.ls SELECT i, 'w1 w' || (i % 5) FROM range(" + std::to_string(f * 11) + ", " +
		              std::to_string(f * 11 + 11) + ") r(i)",
		          "ls file " + std::to_string(f));
	}
	int64_t lt_files = 0;
	ok = ok && IndexTable(setup, "lt", {"body"}, data) && IndexTable(setup, "lf", {"body"}, data) &&
	     IndexTable(setup, "ls", {"body"}, data) && BuildSegments(setup, sysc, "lt", data, lt_files);
	Check(ok && lt_files == 2, "the fixture: lt in 2 bundled files, lf and ls unbundled", std::to_string(lt_files));
	if (!ok) {
		CloseNode(node);
		return 1;
	}
	const std::string lt = "SELECT rowid FROM lake.main.lt";
	const std::string lf = "SELECT rowid FROM lake.main.lf";
	const std::string ls = "SELECT rowid FROM lake.main.ls";
	const std::string ltt = "'lake.main.lt'";

	// ---- k (qod_search) -----------------------------------------------------------------------------
	Fails(client, "qod_search k := 0", Search(lt, "'w1'", ltt, kCols, ", k := 0"),
	      "QoD: search_invalid_argument: k must be between 1 and 10000");
	Fails(client, "qod_search k := 10001", Search(lt, "'w1'", ltt, kCols, ", k := 10001"),
	      "QoD: search_invalid_argument: k must be between 1 and 10000");
	Answers(client, "qod_search k := 1", Search(lt, "'w1'", ltt, kCols, ", k := 1"), 1);
	Answers(client, "qod_search k := 10000", Search(lt, "'w1'", ltt, kCols, ", k := 10000"), 10000);
	Answers(client, "qod_search k := NULL (the default, min(10, 10000))", Search(lt, "'w1'", ltt, kCols, ", k := NULL"), 10);

	// ---- the query's limits (spec 9), both functions ---------------------------------------------------
	for (auto &fn : kFunctions) {
		std::string at_limit(4096, ' ');
		at_limit.replace(0, 2, "w1");
		std::string over = at_limit + " ";
		Fails(client, fn + " a 4,097-byte query", Call(fn, lt, Quote(over), ltt),
		      "QoD: search_query_too_large: the query is longer than 4096 bytes");
		Answers(client, fn + " a 4,096-byte query", Call(fn, lt, Quote(at_limit), ltt, fn == "qod_search" ? ", k := 1" : ""));
		Fails(client, fn + " parentheses 33 deep",
		      Call(fn, lt, Quote(std::string(33, '(') + "w1" + std::string(33, ')')), ltt),
		      "QoD: search_query_too_large: the query nests parentheses deeper than 32");
		Answers(client, fn + " parentheses 32 deep",
		        Call(fn, lt, Quote(std::string(32, '(') + "w1" + std::string(32, ')')), ltt, fn == "qod_search" ? ", k := 1" : ""));
		Fails(client, fn + " 65 terms", Call(fn, lt, Quote(Terms(65)), ltt),
		      "QoD: search_query_too_large: the query holds more than 64 terms");
		Answers(client, fn + " 64 terms", Call(fn, lt, Quote(Terms(64)), ltt, fn == "qod_search" ? ", k := 1" : ""));
		Fails(client, fn + " a boost on a group of three scored clauses with a required one",
		      Call(fn, lt, Quote("(+w1 w2 w3)^2"), ltt),
		      "QoD: search_query_unsupported: a boost on a group of three or more scored clauses with a required one, or "
		      "with *");
		Answers(client, fn + " a boost on a group of two clauses", Call(fn, lt, Quote("(+w1 w2)^2"), ltt));
	}

	// ---- the fallback's caps, both functions -------------------------------------------------------------
	for (auto &fn : kFunctions) {
		qod::search::SetMaxUnindexedRowsForTest(3004);
		Fails(client, fn + " the rows cap (3,005 unindexed rows, cap 3,004)", Call(fn, lf, "'w1'", "'lake.main.lf'"),
		      "QoD: search_index_behind: lake.main.lf has 3005 unindexed live rows (cap 3004); retry once the indexer "
		      "catches up");
		qod::search::SetMaxUnindexedRowsForTest(3005);
		Answers(client, fn + " the rows cap at the backlog (3,005)", Call(fn, lf, "'w1'", "'lake.main.lf'"));
		qod::search::SetMaxUnindexedRowsForTest(200000);
		qod::search::SetMaxUnindexedSourcesForTest(5);
		auto scans = qod::search::TextScansForTest();
		auto fallback = qod::search::FallbackCountersForTest().rows;
		Fails(client, fn + " the sources cap (6 unindexed files, cap 5)", Call(fn, ls, "'w1'", "'lake.main.ls'"),
		      "QoD: search_index_behind: lake.main.ls has 6 unindexed sources (cap 5); retry once the indexer catches up");
		Check(qod::search::TextScansForTest() == scans && qod::search::FallbackCountersForTest().rows == fallback,
		      fn + " the sources cap: refused before any scan (the text scan counter 0, no row indexed)",
		      std::to_string(qod::search::TextScansForTest() - scans) + " scans");
		qod::search::SetMaxUnindexedSourcesForTest(6);
		Answers(client, fn + " the sources cap at the backlog (6)", Call(fn, ls, "'w1'", "'lake.main.ls'"));
		qod::search::SetMaxUnindexedSourcesForTest(64);
	}

	// ---- memory, both functions -----------------------------------------------------------------------------
	for (auto &fn : kFunctions) {
		qod::SetSearchMemoryForTest(1024);
		Fails(client, fn + " a query memory of 1 KiB", Call(fn, lt, "'w1'", ltt),
		      "QoD: search_memory_exceeded: the search's live bitsets need ", " bytes more, past its budget of 1024 bytes");
		Fails(client, fn + " a query memory of 1 KiB, through the fallback", Call(fn, lf, "'w1'", "'lake.main.lf'"),
		      "QoD: search_memory_exceeded: the search's fallback builders need ", " bytes more, past its budget of 1024 bytes");
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
	}

	// ---- cancellation, both functions ----------------------------------------------------------------------
	for (auto &fn : kFunctions) {
		qod::search::InterruptAfterRowsForTest(50000);
		Fails(client, fn + " a cancel in the drain (after 50,000 input rows)", Call(fn, lt, "'w1'", ltt), "Interrupted");
		qod::search::InterruptAfterRowsForTest(0);

		qod::ResetLastSearchCallForTest();
		qod::CancelInFinishForTest(true);
		Fails(client, fn + " a cancel in the statistics (the flag set before the library's first pass)",
		      Call(fn, lt, "'w1'", ltt), "Interrupted");
		qod::CancelInFinishForTest(false);
		auto record = qod::LastSearchCallForTest();
		Check(record.called && record.library_cancelled,
		      fn + " the statistics' cancel: the library itself answered search_cancelled");
		// The same in the allowed population, whose statistics pass reads the live AND allowed docs (spec 9, 7.5).
		g_population = "allowed";
		qod::ResetLastSearchCallForTest();
		qod::CancelInFinishForTest(true);
		Fails(client, fn + " a cancel in the allowed population's statistics", Call(fn, lt + " WHERE id % 3 = 0", "'w1'", ltt),
		      "Interrupted");
		qod::CancelInFinishForTest(false);
		g_population = "table";
		record = qod::LastSearchCallForTest();
		Check(record.called && record.library_cancelled && record.population == QS_POPULATION_ALLOWED,
		      fn + " the allowed statistics' cancel: the library itself answered search_cancelled");

		qod::search::InterruptFallbackAfterRowsForTest(1000);
		Fails(client, fn + " a cancel in the fallback's indexing (after 1,000 rows)", Call(fn, lf, "'w1'", "'lake.main.lf'"),
		      "Interrupted");
		qod::search::InterruptFallbackAfterRowsForTest(0);

		// While the library searches: the client interrupts the statement, the node's watcher copies the interrupt into
		// the call's cancel flag, and the library answers search_cancelled.
		Exec(client, "SET threads = 1", "threads 1");
		const std::string searches = fn + " a cancel while the library searches (40 terms over 200,000 docs)";
		std::thread interrupter;
		qod::ResetLastSearchCallForTest();
#ifdef QOD_SEARCH_TEST_HOOKS
		if (fn == "qod_search") {
			// Deterministic (the library's test-hooks hold): qs_search stops past its statistics and before it scores
			// until its cancel flag is set, and the client interrupts only once the call is held, so the interrupt
			// lands inside qs_search on any host, however fast.
			auto holds = qs_test_search_holds();
			qs_test_hold_next_search();
			qod::SearchCallObserverForTest([&](ClientContext &context) {
				interrupter = std::thread([&context, holds] {
					auto until = std::chrono::steady_clock::now() + std::chrono::seconds(10);
					while (qs_test_search_holds() == holds && std::chrono::steady_clock::now() < until) {
						std::this_thread::sleep_for(std::chrono::milliseconds(1));
					}
					context.Interrupt();
				});
			});
			Fails(client, searches + ", held in qs_search", Call(fn, lt, Quote(Terms(40)), ltt), "Interrupted");
			qod::SearchCallObserverForTest(nullptr);
			if (interrupter.joinable()) {
				interrupter.join();
			}
			Check(qs_test_search_holds() == holds + 1, fn + " the held search: qs_search reached the hold",
			      std::to_string(qs_test_search_holds() - holds) + " holds");
			Check(qod::LastSearchCallForTest().library_cancelled,
			      fn + " the search's cancel: the library itself answered search_cancelled");
			Exec(client, "SET threads = 16", "threads 16");
			continue;
		}
#endif
		// Timed (no hold: a build without the test hooks, and qod_search_matches, whose iterator cancel has its own
		// deterministic case below): the client interrupts 5 ms into the call. The interrupt can land before the library
		// runs (in the drain) or after it (a fast host); such a run tells nothing of a cancel inside the library, so it
		// counts only when the library itself answered search_cancelled, and never as a pass of a cancel that did not
		// happen.
		qod::SearchCallObserverForTest([&](ClientContext &context) {
			interrupter = std::thread([&context] {
				std::this_thread::sleep_for(std::chrono::milliseconds(5));
				context.Interrupt();
			});
		});
		auto got = Stream(client, Call(fn, lt, Quote(Terms(40)), ltt));
		qod::SearchCallObserverForTest(nullptr);
		if (interrupter.joinable()) {
			interrupter.join();
		}
		if (qod::LastSearchCallForTest().library_cancelled) {
			Check(got.failed && got.rows == 0 && got.error.find("Interrupted") != std::string::npos,
			      searches + ": Interrupted, no row reached the client (the library itself answered search_cancelled)",
			      got.failed ? std::to_string(got.rows) + " rows before: " + FirstLine(got.error)
			                 : "it ended as a success with " + std::to_string(got.rows) + " rows");
		} else {
			Say("note " + searches + ": not reached on this host (the interrupt landed outside the library, " +
			    (got.failed ? "the statement failed: " + FirstLine(got.error) : "the statement answered " +
			                                                                    std::to_string(got.rows) + " rows") +
			    "); no cancel inside the library to check");
			// An interrupt that landed outside the library (in the drain before it, or after it) still never delivers
			// part of an answer.
			if (got.failed) {
				Check(got.rows == 0 && got.error.find("Interrupted") != std::string::npos,
				      searches + ", the interrupt outside the library: Interrupted, no row reached the client",
				      std::to_string(got.rows) + " rows before: " + FirstLine(got.error));
			}
		}
		Exec(client, "SET threads = 16", "threads 16");
	}
	qod::ResetLastSearchCallForTest();
	qod::CancelMatchesAfterChunksForTest(0);
	Fails(client, "qod_search_matches a cancel in the matches iterator (before its first chunk)",
	      Matches(lt, "'w1'", ltt), "Interrupted");
	qod::CancelMatchesAfterChunksForTest(-1);
	Check(qod::LastSearchCallForTest().library_cancelled,
	      "qod_search_matches the iterator's cancel: the library itself answered search_cancelled");
	// After one chunk went out: rows may have reached the client, and the stream still ends in the named error, never
	// as a complete answer (invariant 8 at the statement's level, Task 2's ruling).
	{
		qod::ResetLastSearchCallForTest();
		qod::CancelMatchesAfterChunksForTest(1);
		auto got = Stream(client, Matches(lt, "'w1'", ltt));
		qod::CancelMatchesAfterChunksForTest(-1);
		Check(got.failed && got.error.find("Interrupted") != std::string::npos &&
		          qod::LastSearchCallForTest().library_cancelled,
		      "qod_search_matches a cancel in the matches iterator after its first chunk: the stream ends in Interrupted (" +
		          std::to_string(got.rows) + " rows reached the client first, none read as a complete answer)",
		      got.failed ? FirstLine(got.error) : "it ended as a success with " + std::to_string(got.rows) + " rows");
	}
	Answers(client, "the connection after the cancels", Search(lt, "'w1'", ltt), 10);

	// ---- the k default under QOD_SEARCH_MAX_K=5 (Task 16, carried) ----------------------------------------
	SetEnvForTest("QOD_SEARCH_MAX_K", "5");
	RegisterKnobs();
	Answers(client, "QOD_SEARCH_MAX_K=5: k := NULL answers the default, min(10, 5)", Search(lt, "'w1'", ltt, kCols, ", k := NULL"),
	        5);
	Answers(client, "QOD_SEARCH_MAX_K=5: a call naming no k answers the default, min(10, 5)", Search(lt, "'w1'", ltt), 5);
	Fails(client, "QOD_SEARCH_MAX_K=5: k := 6", Search(lt, "'w1'", ltt, kCols, ", k := 6"),
	      "QoD: search_invalid_argument: k must be between 1 and 5");
	SetEnvForTest("QOD_SEARCH_MAX_K", nullptr);
	RegisterKnobs();
	Answers(client, "QOD_SEARCH_MAX_K unset again: a call naming no k answers 10", Search(lt, "'w1'", ltt), 10);

	CloseNode(node);
	return Finish("search_limits_test");
}

} // namespace

int main() {
	return Run();
}
