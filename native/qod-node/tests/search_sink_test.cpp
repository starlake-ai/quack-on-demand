// Search Phase 0 part 2, Task 7a Steps 3 to 5 (spec 7.6): the custom sink operator, its swap and the
// drain probe executed by it (qod_search_drain_sink).
//
// Native DuckDB tables, no extension. Fixture: t(i BIGINT) of 2,000,000 rows on an untagged
// connection, SET threads = 16, RegisterSearchFunctions and qod-node's hooks with a recording decider
// that allows (or refuses, for S6), the measured connection tagged as a quack session with a tenant
// principal installed. A test-only qod_test_sink_sum(TABLE) -> (s HUGEINT), registered through
// RegisterSinkFunction, sums the rowids it set, each bit once.
//
//   search_sink_test         the light cases S1, S2, S4 to S8, S11 to S15 and S15b (every CI platform)
//   search_sink_test --slow  S3 (240 runs under the Q3 load) and S9 (cancellation over 100M rows)
//
// S8 and S15 as restated by the owner's ruling of 2026-11-15 (P7a): DuckDB 2.0 rebinds a prepared
// statement that reads a table at every EXECUTE, so a swap happens once per bind and a Finalize once
// per EXECUTE, and every EXECUTE takes a fresh verdict.

#include "authz.hpp"
#include "decide_client.hpp"
#include "node_hooks.hpp"
#include "search_functions.hpp"
#include "search_sink.hpp"
#include "test_handles.hpp"

#include "duckdb.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/vector/unified_vector_format.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

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
using qod::search::SinkCountersForTest;
using qod::search::ResetSinkCountersForTest;

namespace {

int64_t now_ms = 1761200000000;
int g_failures = 0;

void Check(bool ok, const std::string &what) {
	std::cout << (ok ? "ok   " : "FAIL ") << what << std::endl;
	if (!ok) {
		g_failures++;
	}
}

std::string FirstLine(const std::string &s) {
	auto nl = s.find('\n');
	return nl == std::string::npos ? s : s.substr(0, nl);
}

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
	return static_cast<uint64_t>(usage.ru_maxrss);
#else
	return static_cast<uint64_t>(usage.ru_maxrss) * 1024;
#endif
#endif
}

// ---- the recording decider ---------------------------------------------------------------------

std::mutex g_decider_mutex;
std::vector<std::string> g_descriptions;
std::atomic<int> g_plan_decisions {0};
std::atomic<bool> g_refuse {false};
//! The sink's swap count at each plan decision, in call order (S6: every verdict precedes the swap).
std::vector<idx_t> g_swaps_at_plan_decision;

qod::DecideResult Recorder(const qod::DecideCall &call) {
	if (call.kind == "plan") {
		g_plan_decisions++;
	}
	{
		std::lock_guard<std::mutex> guard(g_decider_mutex);
		g_descriptions.push_back(call.kind + "\t" + call.description);
		if (call.kind == "plan") {
			g_swaps_at_plan_decision.push_back(SinkCountersForTest().swaps);
		}
	}
	qod::DecideResult result;
	result.status = g_refuse.load() ? qod::DecideResult::Status::Refuse : qod::DecideResult::Status::Allow;
	result.has_verdict = !g_refuse.load();
	if (g_refuse.load()) {
		result.reason = "refused by the test decider";
	}
	result.verdict.expires_at = now_ms + 60000;
	return result;
}

std::vector<std::string> TakeDescriptions() {
	std::lock_guard<std::mutex> guard(g_decider_mutex);
	auto out = std::move(g_descriptions);
	g_descriptions.clear();
	return out;
}

std::vector<idx_t> TakeSwapsAtPlanDecisions() {
	std::lock_guard<std::mutex> guard(g_decider_mutex);
	auto out = std::move(g_swaps_at_plan_decision);
	g_swaps_at_plan_decision.clear();
	return out;
}

struct FakeQuackSession : public ClientContextState {};

void TagAsQuackSession(Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
}

bool Install(Connection &con, const std::string &principal, int64_t policy_version) {
	qod_test::MintSpec spec;
	spec.principal_id = principal;
	spec.exp = now_ms + 60000;
	spec.policy_version = policy_version;
	auto installed = con.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		std::cout << "   prelude refused: " << FirstLine(installed->GetError()) << std::endl;
		return false;
	}
	return true;
}

// ---- qod_test_sink_sum: the sum of the rowids set, each bit once ------------------------------

struct SumLocal : public qod::search::SinkLocal {
	std::vector<uint64_t> words;
};

class SumProgram : public qod::search::SinkProgram {
public:
	std::unique_ptr<qod::search::SinkLocal> NewLocal() override {
		return std::unique_ptr<qod::search::SinkLocal>(new SumLocal());
	}
	void Consume(ClientContext &, qod::search::SinkLocal &local_p, Vector &rowids, idx_t count) override {
		auto &local = static_cast<SumLocal &>(local_p);
		UnifiedVectorFormat format;
		rowids.ToUnifiedFormat(format);
		auto values = format.GetData<int64_t>();
		for (idx_t i = 0; i < count; i++) {
			auto index = format.sel->get_index(i);
			if (!format.validity.RowIsValid(index)) {
				continue;
			}
			auto rowid = values[index];
			if (rowid < 0 || static_cast<uint64_t>(rowid) >= qod::search::kMaxRowidExclusive) {
				throw InvalidInputException(qod::search::kRowidOutOfRange);
			}
			auto word = static_cast<size_t>(rowid >> 6);
			if (word >= local.words.size()) {
				local.words.resize(word + 1, 0);
			}
			local.words[word] |= 1ull << (rowid & 63);
		}
	}
	void Merge(qod::search::SinkLocal &local_p) override {
		auto &local = static_cast<SumLocal &>(local_p);
		if (local.words.size() > words.size()) {
			words.resize(local.words.size(), 0);
		}
		for (size_t i = 0; i < local.words.size(); i++) {
			words[i] |= local.words[i];
		}
	}
	void Finish(ClientContext &) override {
		sum = 0;
		for (size_t w = 0; w < words.size(); w++) {
			for (int b = 0; b < 64; b++) {
				if (words[w] & (1ull << b)) {
					sum += static_cast<int64_t>(w * 64 + b);
				}
			}
		}
	}
	idx_t OutputRows() const override {
		return 1;
	}
	Value OutputValue(idx_t, idx_t) const override {
		return Value::HUGEINT(hugeint_t(sum));
	}

private:
	std::vector<uint64_t> words;
	int64_t sum = 0;
};

unique_ptr<FunctionData> SumBind(ClientContext &, TableFunctionBindInput &input, vector<LogicalType> &types,
                                 vector<Identifier> &names) {
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		throw BinderException("qod_test_sink_sum takes one BIGINT input column");
	}
	types = {LogicalType::HUGEINT};
	names.emplace_back("s");
	return make_uniq<TableFunctionData>();
}

void RegisterSum(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_sink_test");
	TableFunction function("qod_test_sink_sum", {LogicalType::TABLE}, nullptr, SumBind);
	qod::search::RegisterSinkFunction(loader, function, [](ClientContext &, const FunctionData &) {
		return std::unique_ptr<qod::search::SinkProgram>(new SumProgram());
	});
}

// ---- query helpers -----------------------------------------------------------------------------

struct Rows {
	std::string error;
	std::vector<std::vector<std::string>> values;
	idx_t columns = 0;
};

Rows Run(Connection &con, const std::string &sql) {
	Rows out;
	auto result = con.Query(sql);
	if (result->HasError()) {
		out.error = result->GetError();
		return out;
	}
	out.columns = result->ColumnCount();
	while (auto chunk = result->Fetch()) {
		if (chunk->size() == 0) {
			break;
		}
		for (idx_t r = 0; r < chunk->size(); r++) {
			std::vector<std::string> row;
			for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
				row.push_back(chunk->GetValue(c, r).ToString());
			}
			out.values.push_back(std::move(row));
		}
	}
	return out;
}

std::string One(Connection &con, const std::string &sql) {
	auto rows = Run(con, sql);
	if (!rows.error.empty()) {
		return "ERROR " + FirstLine(rows.error);
	}
	if (rows.values.size() != 1) {
		return "ROWS " + std::to_string(rows.values.size());
	}
	std::string out;
	for (size_t c = 0; c < rows.values[0].size(); c++) {
		out += (c == 0 ? "" : "|") + rows.values[0][c];
	}
	return out;
}

std::string AllText(const Rows &rows) {
	std::string out = rows.error;
	for (auto &row : rows.values) {
		for (auto &v : row) {
			out += v + "\t";
		}
		out += "\n";
	}
	return out;
}

bool Holds(const std::string &text, const std::string &needle) {
	return text.find(needle) != std::string::npos;
}

// The plan's three inputs, and an input empty at run time (i::VARCHAR is never 'x', which no optimizer
// can prove). `WHERE false` is S1's fourth input, handled apart: DuckDB's optimizer folds an in-out call
// over a provably empty input into an empty result, so neither the drain nor the sink returns a row.
const std::vector<std::string> kInputs = {"(SELECT rowid FROM t)", "(SELECT rowid FROM t WHERE i % 10 = 0)",
                                          "(SELECT rowid FROM t WHERE i % 100 = 0)",
                                          "(SELECT rowid FROM t WHERE i::VARCHAR = 'x')"};

// ---- the light cases ---------------------------------------------------------------------------

void S1(Connection &client) {
	for (auto &input : kInputs) {
		auto sink = One(client, "SELECT rows, max_rowid FROM qod_search_drain_sink(" + input + ")");
		auto drain = One(client, "SELECT rows, max_rowid FROM qod_search_drain(" + input + ")");
		auto truth = One(client, "SELECT count(*), coalesce(max(rowid), -1) FROM " + input + " x");
		Check(sink == drain && sink == truth, "S1 result " + input + ": sink " + sink + ", drain " + drain +
		                                          ", truth " + truth);
		auto sum = One(client, "SELECT s FROM qod_test_sink_sum(" + input + ")");
		auto sum_truth = One(client, "SELECT coalesce(sum(rowid), 0)::HUGEINT FROM " + input + " x");
		Check(sum == sum_truth, "S1 sum " + input + ": " + sum + " (truth " + sum_truth + ")");
	}
	ResetSinkCountersForTest();
	auto empty = One(client, "SELECT rows, max_rowid FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i::VARCHAR = 'x'))");
	auto c = SinkCountersForTest();
	Check(empty == "0|-1" && c.finalizes == 1, "S1 input empty at run time: " + empty + ", finalizes " +
	                                               std::to_string(c.finalizes) + " (Finish still runs once)");
	ResetSinkCountersForTest();
	auto folded = One(client, "SELECT rows, max_rowid FROM qod_search_drain_sink((SELECT rowid FROM t WHERE false))");
	auto folded_drain = One(client, "SELECT rows, max_rowid FROM qod_search_drain((SELECT rowid FROM t WHERE false))");
	c = SinkCountersForTest();
	Check(folded == folded_drain && folded == "ROWS 0" && c.swaps == 0 && c.finalizes == 0,
	      "S1 WHERE false (folded by DuckDB's optimizer before the swap): sink " + folded + ", drain " + folded_drain +
	          ", swaps " + std::to_string(c.swaps));
}

void S2(Connection &client) {
	int more_than_one = 0;
	for (int r = 0; r < 40; r++) {
		auto locals = One(client, "SELECT locals FROM qod_search_drain_sink((SELECT rowid FROM t))");
		if (locals.rfind("ERROR", 0) != 0 && locals.rfind("ROWS", 0) != 0 && std::stoll(locals) > 1) {
			more_than_one++;
		}
	}
	Check(more_than_one >= 39, "S2 parallel: locals > 1 in " + std::to_string(more_than_one) + " of 40 runs");
}

void S4(Connection &client) {
	// The text form renders operator names ("Qod Search Sink"); the JSON form prints GetName() as is.
	auto plan = AllText(Run(client, "EXPLAIN (FORMAT json) SELECT * FROM qod_search_drain_sink((SELECT rowid FROM t))"));
	Check(Holds(plan, "QOD_SEARCH_SINK") && !Holds(plan, "TABLE_INOUT_FUNCTION") && !Holds(plan, "INOUT"),
	      "S4 plan: QOD_SEARCH_SINK in the physical plan, no in-out operator");
}

void S5(Connection &client) {
	qod::search::SkipSwapForTest(true);
	auto rows = Run(client, "SELECT * FROM qod_search_drain_sink((SELECT rowid FROM t))");
	qod::search::SkipSwapForTest(false);
	Check(Holds(rows.error, "search_internal: not planned through the search sink") && rows.values.empty(),
	      "S5 fail closed: " + FirstLine(rows.error));
	auto after = One(client, "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
	Check(after == "200000", "S5 the database answers afterwards: " + after);
}

void S6(Connection &client) {
	const std::string sql = "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))";
	TakeDescriptions();
	TakeSwapsAtPlanDecisions();
	auto swaps_before = SinkCountersForTest().swaps;
	Run(client, sql);
	auto swaps_after = SinkCountersForTest().swaps;
	auto with_swap = TakeDescriptions();
	auto seen = TakeSwapsAtPlanDecisions();
	// The evidence that the swap cannot change what is judged: every plan decision of the statement saw
	// the swap count of before the statement, and the statement then swapped once.
	bool before_swap = !seen.empty() && swaps_after == swaps_before + 1;
	for (auto n : seen) {
		before_swap = before_swap && n == swaps_before;
	}
	std::string seen_text;
	for (auto n : seen) {
		seen_text += (seen_text.empty() ? "" : ",") + std::to_string(n);
	}
	Check(before_swap, "S6 every plan verdict precedes the swap: swaps at the decisions [" + seen_text +
	                       "], before " + std::to_string(swaps_before) + ", after " + std::to_string(swaps_after));
	qod::search::SkipSwapForTest(true);
	Run(client, sql);
	qod::search::SkipSwapForTest(false);
	auto without_swap = TakeDescriptions();
	TakeSwapsAtPlanDecisions();
	// Follows from the order above (the swap runs after every describe), so this only pins that the
	// skip hook leaves the describes alone; the order check is the evidence.
	Check(!with_swap.empty() && with_swap == without_swap,
	      "S6 descriptions equal with the swap and with the swap skipped (" + std::to_string(with_swap.size()) +
	          " calls)");
	g_refuse = true;
	ResetSinkCountersForTest();
	auto refused = Run(client, sql);
	auto c = SinkCountersForTest();
	g_refuse = false;
	Check(!refused.error.empty() && c.plans == 0,
	      "S6 a refused statement plans no sink: plans " + std::to_string(c.plans) + ", " + FirstLine(refused.error));
}

void S7(Connection &client) {
	auto truth = One(client, "SELECT coalesce(max(rowid), -1) FROM (SELECT rowid FROM t WHERE i % 10 = 0) x");
	auto projected = One(client, "SELECT max_rowid FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
	auto joined = One(client, "SELECT d.rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0)) d "
	                          "JOIN (SELECT 1 AS one) o ON true");
	Check(projected == truth, "S7 projection: " + projected + " (truth " + truth + ")");
	Check(joined == "200000", "S7 join: " + joined);
}

void S8(Connection &client) {
	const std::string body = "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))";
	// SQL PREPARE / EXECUTE
	ResetSinkCountersForTest();
	bool ok = Run(client, "PREPARE p AS " + body).error.empty();
	auto e1 = One(client, "EXECUTE p");
	auto e2 = One(client, "EXECUTE p");
	auto c = SinkCountersForTest();
	Check(ok && e1 == "200000" && e2 == "200000" && c.swaps == 3 && c.finalizes == 2,
	      "S8 SQL prepared: " + e1 + ", " + e2 + ", swaps " + std::to_string(c.swaps) + " (3: one per bind), finalizes " +
	          std::to_string(c.finalizes) + " (2)");
	Install(client, "u-1", 2);
	ResetSinkCountersForTest();
	auto e3 = One(client, "EXECUTE p");
	c = SinkCountersForTest();
	Check(e3 == "200000" && c.swaps == 1 && c.finalizes == 1,
	      "S8 SQL prepared after the policy version moved: " + e3 + ", swaps " + std::to_string(c.swaps) +
	          ", finalizes " + std::to_string(c.finalizes));
	Run(client, "DEALLOCATE p");
	// the client API (the path quack's PREPARE and EXECUTE take)
	ResetSinkCountersForTest();
	auto prepared = client.Prepare(body);
	std::string results;
	for (int i = 0; i < 2 && !prepared->HasError(); i++) {
		auto result = prepared->Execute();
		results += result->HasError() ? "ERROR " + FirstLine(result->GetError()) + ";" : result->Fetch()->GetValue(0, 0).ToString() + ";";
	}
	c = SinkCountersForTest();
	Check(!prepared->HasError() && results == "200000;200000;" && c.swaps == 3 && c.finalizes == 2,
	      "S8 API prepared: " + results + " swaps " + std::to_string(c.swaps) + ", finalizes " +
	          std::to_string(c.finalizes));
	Install(client, "u-1", 3);
	ResetSinkCountersForTest();
	auto result = prepared->Execute();
	c = SinkCountersForTest();
	Check(!result->HasError() && c.swaps == 1 && c.finalizes == 1,
	      "S8 API prepared after the policy version moved: swaps " + std::to_string(c.swaps) + ", finalizes " +
	          std::to_string(c.finalizes));
}

void S11(Connection &client) {
	ResetSinkCountersForTest();
	auto rows = Run(client, "SELECT * FROM (SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t))) AS _qod_probe "
	                        "LIMIT 0");
	auto c = SinkCountersForTest();
	Check(rows.error.empty() && rows.columns == 1 && rows.values.empty() && c.swaps == 0 && c.plans == 0 &&
	          c.finalizes == 0 && c.locals == 0,
	      "S11 schema probe: columns " + std::to_string(rows.columns) + ", rows " + std::to_string(rows.values.size()) +
	          ", swaps " + std::to_string(c.swaps) + ", plans " + std::to_string(c.plans) + ", finalizes " +
	          std::to_string(c.finalizes) + ", locals " + std::to_string(c.locals) + " " + FirstLine(rows.error));
}

void S12(Connection &client) {
	ResetSinkCountersForTest();
	// The profiler names an operator by its PhysicalOperatorType, EXTENSION for the sink, with its
	// ParamsToString ("Function": the call); the JSON form prints both as they are.
	auto text = AllText(
	    Run(client, "EXPLAIN (ANALYZE, FORMAT json) SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t))"));
	auto c = SinkCountersForTest();
	bool shown = Holds(text, "\"EXTENSION\"") && Holds(text, "qod_search_drain_sink") && !Holds(text, "INOUT");
	Check(shown && c.finalizes == 1, "S12 EXPLAIN ANALYZE: the sink (EXTENSION, Function qod_search_drain_sink) shown " +
	                                     std::to_string(shown) + ", finalizes " + std::to_string(c.finalizes));
}

void S13(Connection &client) {
	ResetSinkCountersForTest();
	auto lateral = Run(client, "SELECT * FROM range(3) r, LATERAL qod_search_drain_sink((SELECT rowid FROM t WHERE i = "
	                           "r.range))");
	auto c = SinkCountersForTest();
	// A user's query: an ordinary error naming the shape, never DuckDB's internal-error form.
	auto ordinary = [](const std::string &error) {
		return !Holds(error, "INTERNAL") && !Holds(error, "Stack Trace") && !Holds(error, "assertion failure");
	};
	bool lateral_refused =
	    (Holds(lateral.error, "QoD: search_unsupported_plan: qod_search_drain_sink with a LATERAL or correlated input") ||
	     Holds(lateral.error, "Binder Error")) &&
	    ordinary(lateral.error);
	Check(lateral_refused && c.plans == 0 && !Holds(lateral.error, "not planned through"),
	      "S13 LATERAL refused, never unswapped: " + FirstLine(lateral.error));
	ResetSinkCountersForTest();
	auto ordinality = Run(client, "SELECT * FROM qod_search_drain_sink((SELECT rowid FROM t)) WITH ORDINALITY");
	c = SinkCountersForTest();
	bool ordinality_refused =
	    (Holds(ordinality.error, "QoD: search_unsupported_plan: qod_search_drain_sink with WITH ORDINALITY") ||
	     Holds(ordinality.error, "Binder Error")) &&
	    ordinary(ordinality.error);
	Check(ordinality_refused && c.plans == 0 && !Holds(ordinality.error, "not planned through"),
	      "S13 WITH ORDINALITY refused, never unswapped: " + FirstLine(ordinality.error));
	auto count = One(client, "SELECT count(*) FROM qod_search_drain_sink((SELECT rowid FROM t))");
	Check(count == "1", "S13 count(*) with no output column read: " + count);
	auto healthy = One(client, "SELECT 42");
	Check(healthy == "42", "S13 the database answers afterwards: " + healthy);
}

void S14(Connection &client) {
	auto accepted = One(client, "SELECT rows FROM qod_search_drain_sink((SELECT 134217727::BIGINT))");
	Check(accepted == "1", "S14 2^27 - 1 accepted: " + accepted);
	auto before = PeakRss();
	auto at_cap = Run(client, "SELECT * FROM qod_search_drain_sink((SELECT 134217728::BIGINT))");
	Check(Holds(at_cap.error, "rowid out of range"), "S14 2^27 refused: " + FirstLine(at_cap.error));
	if (!Holds(at_cap.error, "rowid out of range")) {
		return; // never ask the allocator for 2^40 bits
	}
	auto beyond = Run(client, "SELECT * FROM qod_search_drain_sink((SELECT (1::BIGINT << 40)))");
	Check(Holds(beyond.error, "rowid out of range"), "S14 2^40 refused: " + FirstLine(beyond.error));
	auto grown = PeakRss() > before ? PeakRss() - before : 0;
	Check(grown < (uint64_t(64) << 20), "S14 peak RSS grew by " + std::to_string(grown >> 20) + " MiB (limit 64)");
}

//! S15 (as restated): the prepared statement is rebound at every EXECUTE, so every EXECUTE takes a fresh
//! verdict and a fresh swap; meanwhile another connection prepares and executes another sink statement.
//! Run under AddressSanitizer in Step 5.
void S15(DuckDB &db, Connection &client) {
	auto prepared = client.Prepare("SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
	std::atomic<bool> stop {false};
	std::atomic<int> other_ok {0}, other_bad {0};
	std::thread other([&] {
		Connection second(db);
		TagAsQuackSession(second);
		if (!Install(second, "u-2", 1)) {
			other_bad++;
			return;
		}
		while (!stop.load()) {
			auto p2 = second.Prepare("SELECT max_rowid FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 100 = 0))");
			if (p2->HasError()) {
				other_bad++;
				continue;
			}
			auto r = p2->Execute();
			if (r->HasError()) {
				other_bad++;
				continue;
			}
			auto chunk = r->Fetch();
			(chunk && chunk->size() == 1 && chunk->GetValue(0, 0).ToString() == "1999900") ? other_ok++ : other_bad++;
		}
	});
	int fresh = 0, executes = 0;
	// At least 6 executions here, and on until the other connection has run 20 of its own (review M4), so
	// that AddressSanitizer sees the two overlap; at most 200.
	for (int i = 0; (i < 6 || other_ok.load() + other_bad.load() < 20) && i < 200 && !prepared->HasError(); i++) {
		executes++;
		auto decisions = g_plan_decisions.load();
		auto swaps = SinkCountersForTest().swaps;
		auto result = prepared->Execute();
		bool right = !result->HasError() && result->Fetch()->GetValue(0, 0).ToString() == "200000";
		if (right && g_plan_decisions.load() > decisions && SinkCountersForTest().swaps > swaps) {
			fresh++;
		}
	}
	stop = true;
	other.join();
	Check(!prepared->HasError() && fresh == executes,
	      "S15 every EXECUTE takes a fresh verdict and a fresh swap: " + std::to_string(fresh) + " of " +
	          std::to_string(executes));
	Check(other_ok >= 20 && other_bad == 0, "S15 the other connection's prepared sink statements: " +
	                                          std::to_string(other_ok.load()) + " right, " +
	                                          std::to_string(other_bad.load()) + " wrong");
}

//! S15b (review I2): a prepared sink statement whose input reads no table is not rebound (DuckDB sets
//! always_require_rebind only for a plan reading a database), so its physical plan, and the bind data
//! the operator owns, run again at every EXECUTE: one swap in all, a fresh global sink and source state
//! and one Finalize per EXECUTE, the right values each time. Run under AddressSanitizer in Step 5.
void S15b(Connection &client) {
	ResetSinkCountersForTest();
	auto decisions = g_plan_decisions.load();
	auto prepared = client.Prepare(
	    "SELECT rows, max_rowid FROM qod_search_drain_sink((SELECT 5::BIGINT UNION ALL SELECT 7::BIGINT))");
	std::string results;
	const int executes = 3;
	for (int i = 0; i < executes && !prepared->HasError(); i++) {
		auto result = prepared->Execute();
		if (result->HasError()) {
			results += "ERROR " + FirstLine(result->GetError()) + ";";
			continue;
		}
		auto chunk = result->Fetch();
		results += chunk && chunk->size() == 1
		               ? chunk->GetValue(0, 0).ToString() + "|" + chunk->GetValue(1, 0).ToString() + ";"
		               : "ROWS;";
	}
	auto c = SinkCountersForTest();
	Check(!prepared->HasError() && results == "2|7;2|7;2|7;" && c.swaps == 1 && c.plans == 1 && c.finalizes == 3,
	      "S15b a prepared sink statement that reads no table reuses its plan: " + results + " swaps " +
	          std::to_string(c.swaps) + ", plans " + std::to_string(c.plans) + ", finalizes " +
	          std::to_string(c.finalizes) + ", plan decisions " + std::to_string(g_plan_decisions.load() - decisions));
}

//! Not in the plan's table: DuckDB's own serializer check tolerates a NotImplementedException from
//! Serialize (Planner::VerifyPlan), so a superuser's debug_verify_serializer does not fail the sink.
void SerializerCheck(DuckDB &db) {
	Connection su(db);
	bool set = Run(su, "SET debug_verify_serializer = true").error.empty();
	auto value = One(su, "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
	Run(su, "SET debug_verify_serializer = false");
	Check(set && value == "200000", "debug_verify_serializer on an untagged connection: " + value);
}

// ---- the slow cases ----------------------------------------------------------------------------

void S3(DuckDB &db, Connection &setup, Connection &client) {
	Run(setup, "CREATE TABLE big AS SELECT i::BIGINT AS i, (i * 7)::BIGINT AS j FROM range(20000000) r(i)");
	std::atomic<bool> stop {false};
	std::atomic<int> load_queries {0};
	std::thread load([&] {
		Connection loader(db);
		while (!stop.load()) {
			if (loader.Query("SELECT sum(hash(i, j)) FROM big")->HasError()) {
				break;
			}
			load_queries++;
		}
	});
	int once = 0, matched = 0, right = 0, runs = 0;
	for (int threads : {8, 16}) {
		Run(setup, "SET threads = " + std::to_string(threads));
		for (int r = 0; r < 120; r++, runs++) {
			ResetSinkCountersForTest();
			auto rows = One(client, "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
			auto c = SinkCountersForTest();
			right += rows == "200000" ? 1 : 0;
			once += c.finalizes == 1 ? 1 : 0;
			matched += c.combines == c.locals ? 1 : 0;
		}
	}
	stop = true;
	load.join();
	Run(setup, "SET threads = 16");
	Check(right == runs && once == runs && matched == runs && load_queries > 0,
	      "S3 once: " + std::to_string(runs) + " runs, right " + std::to_string(right) + ", one finalize " +
	          std::to_string(once) + ", combines == locals " + std::to_string(matched) + ", load statements " +
	          std::to_string(load_queries.load()));
}

void S9(Connection &setup, Connection &client) {
	Run(setup, "CREATE TABLE h AS SELECT i::BIGINT AS i FROM range(100000000) r(i)");
	ResetSinkCountersForTest();
	qod::search::InterruptAfterRowsForTest(30000000);
	auto rows = Run(client, "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM h))");
	qod::search::InterruptAfterRowsForTest(0);
	auto c = SinkCountersForTest();
	// interrupts_answered counts the sink's own checks that threw: the hook interrupts and the sink checks
	// in the same Sink call, before DuckDB's executor checks again at its next chunk, so at least one
	// does whatever the other threads do (each of them answers at its next check, the sink's or the
	// executor's).
	Check(Holds(rows.error, "Interrupt") && rows.values.empty() && c.interrupt_checks > 0 &&
	          c.interrupts_answered >= 1 && c.max_rows_between_checks <= 65536,
	      "S9 cancellation: " + FirstLine(rows.error) + ", interrupt checks " + std::to_string(c.interrupt_checks) +
	          ", answered by the sink's own check " + std::to_string(c.interrupts_answered) +
	          ", max rows between checks " + std::to_string(c.max_rows_between_checks));
	auto after = One(client, "SELECT rows FROM qod_search_drain_sink((SELECT rowid FROM t WHERE i % 10 = 0))");
	Check(after == "200000", "S9 the connection answers afterwards: " + after);
	Run(setup, "DROP TABLE h");
}

} // namespace

int main(int argc, char **argv) {
	bool slow = argc > 1 && std::strcmp(argv[1], "--slow") == 0;
	DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, Recorder);
	qod::RegisterSearchFunctions(*db.instance);
	RegisterSum(*db.instance);

	Connection setup(db);
	if (!Run(setup, "CREATE TABLE t AS SELECT i::BIGINT AS i FROM range(2000000) r(i)").error.empty() ||
	    !Run(setup, "SET threads = 16").error.empty()) {
		std::cout << "FAIL fixture" << std::endl;
		return 1;
	}
	Connection client(db);
	TagAsQuackSession(client);
	if (!Install(client, "u-1", 1)) {
		return 1;
	}
	if (slow) {
		S3(db, setup, client);
		S9(setup, client);
	} else {
		S1(client);
		S2(client);
		S4(client);
		S5(client);
		S6(client);
		S7(client);
		S11(client);
		S12(client);
		S13(client);
		S14(client);
		S8(client);
		S15(db, client);
		S15b(client);
		SerializerCheck(db);
	}
	std::cout << (g_failures == 0 ? "search_sink: all checks passed" : "search_sink: failures") << std::endl;
	return g_failures == 0 ? 0 : 1;
}
