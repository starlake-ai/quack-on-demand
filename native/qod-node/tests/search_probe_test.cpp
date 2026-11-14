// Search Phase 0 probe (spec Q3, Q11). Prints, does not assert, except that the statements run.
// 1. What a post_bind hook finds in the bind data of a DuckLake scan: the file list DuckLake resolved
//    (paths and every extended-info option), for a plain read, an AT read, and a read inside a
//    transaction that another connection commits to after the transaction's first read. An encrypted
//    lake shows whether an encryption key option is present (its value is never printed).
// 2. The join types of the tree post_bind sees for the correlated subquery forms, over plain DuckDB
//    tables (EXPLAIN does not print a dependent join's type).
// 3. How DuckDB 2.0 runs an in-out table function whose TABLE input is a 2M-row DuckLake scan:
//    local states, rows per thread, and the order of in-out calls, local inits and finals.
// The probe data goes under $QOD_SEARCH_PROBE_DIR when set (kept), else under the temp folder (removed).
// Exits 77 (CTest skip) when ducklake cannot be installed or loaded.
//
// Not part of the default test run: it installs ducklake and httpfs from the network, builds a 20M-row
// table and asserts nothing. Unix only. Build and run it by hand:
//   cmake -S native/qod-node -B <build> -DQOD_NODE_TESTS=ON -DQOD_NODE_PROBES=ON -DDUCKDB_HOME=<.duckdb/2.0.0>
//   cmake --build <build> --target search_probe_test
//   QOD_SEARCH_PROBE_DIR=<data dir> ctest --test-dir <build> -L probe -V

#include "duckdb.hpp"
#include "duckdb/common/enums/join_type.hpp"
#include "duckdb/common/enums/logical_operator_type.hpp"
#include "duckdb/common/multi_file/multi_file_list.hpp"
#include "duckdb/common/multi_file/multi_file_states.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/query_result.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/planner/operator/logical_dependent_join.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator/logical_join.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdlib>
#ifndef _WIN32
#include <cxxabi.h>
#endif
#include <filesystem>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <typeinfo>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

using namespace duckdb;

namespace {

std::atomic<bool> capture_on {false};
std::mutex dump_mutex;
std::vector<std::string> dumps;
//! The client contexts whose statements are captured (DuckLake's own metadata queries are not).
std::set<ClientContext *> captured_contexts;

std::string Demangle(const char *name) {
#ifdef _WIN32
	return name;
#else
	int status = 0;
	char *out = abi::__cxa_demangle(name, nullptr, nullptr, &status);
	std::string result = status == 0 && out ? out : name;
	std::free(out);
	return result;
#endif
}

bool IsSecretOption(const std::string &name) {
	std::string lower = name;
	std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
	return lower.find("key") != std::string::npos || lower.find("encrypt") != std::string::npos ||
	       lower.find("secret") != std::string::npos;
}

void DumpGet(LogicalGet &get, std::ostringstream &out, const std::string &pad) {
	auto table = get.GetTable();
	out << pad << "  get: function=" << get.function.GetName().GetIdentifierName()
	    << " table=" << (table ? table->name.GetIdentifierName() : std::string("<none>"))
	    << " bind_data=" << (get.bind_data ? Demangle(typeid(*get.bind_data).name()) : std::string("<null>"))
	    << "\n";
	out << pad << "  function_info="
	    << (get.function.function_info ? Demangle(typeid(*get.function.function_info).name()) : std::string("<none>"))
	    << "\n";
	if (get.at_clause) {
		out << pad << "  at_clause: unit=" << get.at_clause->Unit().GetIdentifierName()
		    << " value=" << get.at_clause->GetValue().ToString() << " ("
		    << get.at_clause->GetValue().type().ToString() << ")\n";
	} else {
		out << pad << "  at_clause: <none>\n";
	}
	for (auto &param : get.ParamsToString()) {
		out << pad << "  param " << param.first << " = " << param.second << "\n";
	}
	auto files = dynamic_cast<MultiFileBindData *>(get.bind_data.get());
	if (!files) {
		out << pad << "  (no MultiFileBindData)\n";
		return;
	}
	out << pad << "  inner bind_data="
	    << (files->bind_data ? Demangle(typeid(*files->bind_data).name()) : std::string("<null>"))
	    << " file_list=" << (files->file_list ? Demangle(typeid(*files->file_list).name()) : std::string("<null>"))
	    << "\n";
	if (!files->file_list) {
		return;
	}
	auto all = files->file_list->GetAllFiles();
	out << pad << "  files=" << all.size() << "\n";
	for (auto &file : all) {
		out << pad << "    " << file.path << "\n";
		if (!file.extended_info) {
			out << pad << "      (no extended_info)\n";
			continue;
		}
		// Sorted, so two dumps compare line by line.
		std::map<std::string, std::string> options;
		for (auto &option : file.extended_info->options) {
			if (IsSecretOption(option.first)) {
				options[option.first] =
				    option.second.IsNull() ? "<present, NULL>" : "<present, non-NULL, value not printed>";
			} else {
				options[option.first] = option.second.ToString() + " (" + option.second.type().ToString() + ")";
			}
		}
		for (auto &option : options) {
			out << pad << "      " << option.first << " = " << option.second << "\n";
		}
	}
}

void Walk(LogicalOperator &op, std::ostringstream &out, int depth) {
	std::string pad(depth * 2, ' ');
	out << pad << LogicalOperatorToString(op.type);
	switch (op.type) {
	case LogicalOperatorType::LOGICAL_COMPARISON_JOIN:
	case LogicalOperatorType::LOGICAL_ANY_JOIN:
	case LogicalOperatorType::LOGICAL_DELIM_JOIN:
	case LogicalOperatorType::LOGICAL_ASOF_JOIN:
	case LogicalOperatorType::LOGICAL_DEPENDENT_JOIN:
		out << " join_type=" << JoinTypeToString(op.Cast<LogicalJoin>().join_type);
		break;
	default:
		break;
	}
	if (op.type == LogicalOperatorType::LOGICAL_DEPENDENT_JOIN) {
		auto &dep = op.Cast<LogicalDependentJoin>();
		out << " perform_delim=" << dep.perform_delim << " any_join=" << dep.any_join
		    << " correlated_columns=" << dep.correlated_columns.size()
		    << " condition=" << (dep.condition ? dep.condition->ToString() : std::string("<none>"));
	}
	if (op.type == LogicalOperatorType::LOGICAL_FILTER || op.type == LogicalOperatorType::LOGICAL_PROJECTION) {
		out << " [";
		for (idx_t i = 0; i < op.expressions.size(); i++) {
			out << (i ? ", " : "") << op.expressions[i]->ToString();
		}
		out << "]";
	}
	out << "\n";
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		DumpGet(op.Cast<LogicalGet>(), out, pad);
	}
	for (auto &child : op.children) {
		Walk(*child, out, depth + 1);
	}
}

void ProbePostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!capture_on || !statement.plan) {
		return;
	}
	{
		std::lock_guard<std::mutex> lock(dump_mutex);
		if (captured_contexts.find(&input.context) == captured_contexts.end()) {
			return;
		}
	}
	std::ostringstream out;
	Walk(*statement.plan, out, 0);
	std::lock_guard<std::mutex> lock(dump_mutex);
	dumps.push_back(out.str());
}

// ---- in-out probe -----------------------------------------------------------------------------

struct ProbeGlobal : public GlobalTableFunctionState {
	std::atomic<idx_t> locals {0};
	std::atomic<idx_t> finals {0};
	std::atomic<idx_t> rows {0};
	std::atomic<idx_t> inout_calls {0};
	//! In-out calls that ran after some local state's final had started (on another local state).
	std::atomic<idx_t> inout_after_a_final {0};
	//! Local states created after some local state's final had started.
	std::atomic<idx_t> locals_after_a_final {0};
	//! Finals that saw finals_before + 1 == locals at their start (the last-finisher test of spec 7.6).
	std::atomic<idx_t> looked_last {0};
	//! In-out calls that ran after a final had looked last (the pattern would then have fired early).
	std::atomic<idx_t> inout_after_looked_last {0};
	std::mutex mutex;
	std::set<std::thread::id> threads;
	//! What MaxThreads answers: MAX_THREADS, or 1 for qod_probe_inout_one.
	idx_t max_threads = GlobalTableFunctionState::MAX_THREADS;
	idx_t MaxThreads() const override {
		return max_threads;
	}
};

struct ProbeLocal : public LocalTableFunctionState {
	idx_t index = 0;
	idx_t rows = 0;
	idx_t calls = 0;
	idx_t final_calls = 0;
	bool done = false;
	std::thread::id init_thread;
};

unique_ptr<FunctionData> ProbeBind(ClientContext &, TableFunctionBindInput &, vector<LogicalType> &types,
                                   vector<Identifier> &names) {
	types = {LogicalType::UBIGINT, LogicalType::UBIGINT, LogicalType::UBIGINT, LogicalType::UBIGINT};
	names.emplace_back("local_index");
	names.emplace_back("locals");
	names.emplace_back("finals_before_me");
	names.emplace_back("local_rows");
	return make_uniq<TableFunctionData>();
}

//! Per-run summary, read after the query (stress runs print only this). Every counter only grows during a
//! run, so each final merges its view under `summary_mutex` keeping the maximum, and the result does not
//! depend on the order in which concurrent finals write.
struct RunSummary {
	idx_t locals = 0;
	idx_t finals = 0;
	idx_t rows = 0;
	idx_t locals_after_a_final = 0;
	idx_t looked_last = 0;
	idx_t inout_after_looked_last = 0;
};
std::mutex summary_mutex;
RunSummary summary;

void MergeSummary(const RunSummary &seen) {
	std::lock_guard<std::mutex> lock(summary_mutex);
	summary.locals = std::max(summary.locals, seen.locals);
	summary.finals = std::max(summary.finals, seen.finals);
	summary.rows = std::max(summary.rows, seen.rows);
	summary.locals_after_a_final = std::max(summary.locals_after_a_final, seen.locals_after_a_final);
	summary.looked_last = std::max(summary.looked_last, seen.looked_last);
	summary.inout_after_looked_last = std::max(summary.inout_after_looked_last, seen.inout_after_looked_last);
}

RunSummary ReadSummary() {
	std::lock_guard<std::mutex> lock(summary_mutex);
	return summary;
}
bool inout_quiet = false;
//! The order of local inits and finals of the latest run, for the stress runs that show an anomaly.
std::mutex events_mutex;
std::vector<std::string> run_events;

void Event(const std::string &event) {
	std::lock_guard<std::mutex> lock(events_mutex);
	run_events.push_back(event);
}

unique_ptr<GlobalTableFunctionState> ProbeInitGlobal(ClientContext &, TableFunctionInitInput &) {
	{
		std::lock_guard<std::mutex> lock(events_mutex);
		run_events.clear();
	}
	{
		std::lock_guard<std::mutex> lock(summary_mutex);
		summary = RunSummary();
	}
	return make_uniq<ProbeGlobal>();
}

unique_ptr<GlobalTableFunctionState> ProbeInitGlobalOne(ClientContext &context, TableFunctionInitInput &input) {
	auto global = ProbeInitGlobal(context, input);
	global->Cast<ProbeGlobal>().max_threads = 1;
	return global;
}

unique_ptr<LocalTableFunctionState> ProbeInitLocal(ExecutionContext &, TableFunctionInitInput &,
                                                   GlobalTableFunctionState *global_p) {
	auto &global = global_p->Cast<ProbeGlobal>();
	auto local = make_uniq<ProbeLocal>();
	local->index = global.locals++;
	local->init_thread = std::this_thread::get_id();
	auto finals_now = global.finals.load();
	if (finals_now > 0) {
		global.locals_after_a_final++;
	}
	Event("init local=" + std::to_string(local->index) + " finals_so_far=" + std::to_string(finals_now));
	return std::move(local);
}

OperatorResultType ProbeInOut(ExecutionContext &, TableFunctionInput &data, DataChunk &input, DataChunk &output) {
	auto &global = data.global_state->Cast<ProbeGlobal>();
	auto &local = data.local_state->Cast<ProbeLocal>();
	local.rows += input.size();
	local.calls++;
	global.rows += input.size();
	global.inout_calls++;
	if (global.finals.load() > 0) {
		global.inout_after_a_final++;
	}
	if (global.looked_last.load() > 0) {
		global.inout_after_looked_last++;
	}
	{
		std::lock_guard<std::mutex> lock(global.mutex);
		global.threads.insert(std::this_thread::get_id());
	}
	output.SetChildCardinality(0);
	return OperatorResultType::NEED_MORE_INPUT;
}

OperatorFinalizeResultType ProbeFinal(ExecutionContext &, TableFunctionInput &data, DataChunk &output) {
	auto &global = data.global_state->Cast<ProbeGlobal>();
	auto &local = data.local_state->Cast<ProbeLocal>();
	local.final_calls++;
	if (local.done) {
		std::lock_guard<std::mutex> lock(global.mutex);
		std::cout << "  final again: local=" << local.index << " final_calls=" << local.final_calls << std::endl;
		output.SetChildCardinality(0);
		return OperatorFinalizeResultType::FINISHED;
	}
	local.done = true;
	auto before = global.finals++;
	auto locals_now = global.locals.load();
	if (before + 1 == locals_now) {
		global.looked_last++;
	}
	Event("final local=" + std::to_string(local.index) + " finals_before=" + std::to_string(before) +
	      " locals_now=" + std::to_string(locals_now) + " local_rows=" + std::to_string(local.rows) +
	      " local_inout_calls=" + std::to_string(local.calls) + (before + 1 == locals_now ? " LOOKS_LAST" : ""));
	RunSummary seen;
	seen.locals = global.locals.load();
	seen.finals = global.finals.load();
	seen.rows = global.rows.load();
	seen.locals_after_a_final = global.locals_after_a_final.load();
	seen.looked_last = global.looked_last.load();
	seen.inout_after_looked_last = global.inout_after_looked_last.load();
	MergeSummary(seen);
	if (!inout_quiet) {
		std::lock_guard<std::mutex> lock(global.mutex);
		std::cout << "  final: local=" << local.index << " same_thread_as_init="
		          << (local.init_thread == std::this_thread::get_id()) << " local_rows=" << local.rows
		          << " local_inout_calls=" << local.calls << " finals_before=" << before
		          << " locals=" << global.locals.load() << " global_rows=" << global.rows.load()
		          << " global_inout_calls=" << global.inout_calls.load()
		          << " inout_after_a_final=" << global.inout_after_a_final.load()
		          << " locals_after_a_final=" << global.locals_after_a_final.load()
		          << " distinct_inout_threads=" << global.threads.size() << " looked_last=" << (before + 1 == locals_now)
		          << std::endl;
	}
	output.SetValue(0, 0, Value::UBIGINT(local.index));
	output.SetValue(1, 0, Value::UBIGINT(global.locals.load()));
	output.SetValue(2, 0, Value::UBIGINT(before));
	output.SetValue(3, 0, Value::UBIGINT(local.rows));
	output.SetChildCardinality(1);
	return OperatorFinalizeResultType::FINISHED;
}

void RegisterProbe(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_search_probe");
	TableFunction probe("qod_probe_inout", {LogicalType::TABLE}, nullptr, ProbeBind, ProbeInitGlobal, ProbeInitLocal);
	probe.in_out_function = ProbeInOut;
	probe.in_out_function_final = ProbeFinal;
	loader.RegisterFunction(probe);
	// The same function, whose global state answers MaxThreads() = 1: does that bound the pipeline?
	TableFunction one("qod_probe_inout_one", {LogicalType::TABLE}, nullptr, ProbeBind, ProbeInitGlobalOne,
	                  ProbeInitLocal);
	one.in_out_function = ProbeInOut;
	one.in_out_function_final = ProbeFinal;
	loader.RegisterFunction(one);
	PlannerExtension planner;
	planner.post_bind_function = ProbePostBind;
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

bool Ok(unique_ptr<QueryResult> result, const std::string &what) {
	if (result->HasError()) {
		std::cerr << "ERROR " << what << ": " << result->GetError() << std::endl;
		std::cout << "ERROR " << what << ": " << result->GetError() << std::endl;
		return false;
	}
	std::cout << "== " << what << "\n" << result->ToString() << std::endl;
	return true;
}

void PrintDumpsSince(size_t from, const std::string &what) {
	std::lock_guard<std::mutex> lock(dump_mutex);
	std::cout << "== post_bind of: " << what << " (" << (dumps.size() - from) << " plan(s))" << std::endl;
	for (auto i = from; i < dumps.size(); i++) {
		std::cout << dumps[i];
	}
}

size_t DumpCount() {
	std::lock_guard<std::mutex> lock(dump_mutex);
	return dumps.size();
}

//! Runs `sql` with the capture on and prints its post_bind dumps.
bool Captured(Connection &con, const std::string &sql, const std::string &what) {
	auto from = DumpCount();
	capture_on = true;
	auto ok = Ok(con.Query(sql), what);
	capture_on = false;
	PrintDumpsSince(from, what);
	return ok;
}

} // namespace

int main() {
	auto env_dir = std::getenv("QOD_SEARCH_PROBE_DIR");
	auto keep = env_dir && *env_dir;
	auto dir = keep ? std::filesystem::path(env_dir) / ("run-" + std::to_string(::getpid()))
	                : std::filesystem::temp_directory_path() / ("qod-search-probe-" + std::to_string(::getpid()));
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir);
	std::cout << "probe dir: " << dir.string() << std::endl;
	DuckDB db(nullptr);
	RegisterProbe(*db.instance);
	Connection con(db);
	Connection other(db);
	{
		std::lock_guard<std::mutex> lock(dump_mutex);
		captured_contexts.insert(con.context.get());
		captured_contexts.insert(other.context.get());
	}
	if (con.Query("INSTALL ducklake")->HasError() || con.Query("LOAD ducklake")->HasError()) {
		std::cerr << "ducklake unavailable: skipped" << std::endl;
		return 77;
	}
	// Encryption needs OpenSSL, which httpfs brings (under mbedtls an encrypted lake refuses writes).
	auto have_httpfs = !con.Query("INSTALL httpfs")->HasError() && !con.Query("LOAD httpfs")->HasError();
	auto meta = (dir / "probe.ducklake").string();
	auto data = (dir / "data").string() + "/";
	bool ok = true;
	ok &= Ok(con.Query("ATTACH 'ducklake:" + meta + "' AS lake (DATA_PATH '" + data + "', DATA_INLINING_ROW_LIMIT 10)"),
	         "attach (DATA_INLINING_ROW_LIMIT 10)");
	ok &= Ok(con.Query("CREATE TABLE lake.pages AS SELECT i AS id, 'doc ' || i AS body FROM range(2000000) r(i)"),
	         "create 2M rows");
	ok &= Ok(con.Query("INSERT INTO lake.pages VALUES (9000000, 'inlined a'), (9000001, 'inlined b')"), "inlined insert");
	ok &= Ok(con.Query("UPDATE lake.pages SET body = 'updated' WHERE id = 5"), "one-row update");
	ok &= Ok(con.Query("DELETE FROM lake.pages WHERE id = 6"), "one-row delete");
	ok &= Ok(con.Query("DELETE FROM lake.pages WHERE id = 9000000"), "delete of an inlined row");
	ok &= Ok(con.Query("SELECT snapshot_id, changes FROM lake.snapshots() ORDER BY snapshot_id"), "snapshots");
	ok &= Ok(con.Query("SELECT data_file_id, path, record_count, row_id_start, begin_snapshot, end_snapshot, "
	                   "encryption_key IS NOT NULL AS has_key FROM __ducklake_metadata_lake.ducklake_data_file "
	                   "ORDER BY data_file_id"),
	         "catalog: data files");
	ok &= Ok(con.Query("SELECT delete_file_id, data_file_id, path, delete_count, begin_snapshot, end_snapshot "
	                   "FROM __ducklake_metadata_lake.ducklake_delete_file ORDER BY delete_file_id"),
	         "catalog: delete files");

	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages WHERE id < 10)", "plain read");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages AT (VERSION => 2))", "AT (VERSION => 2) read");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages AT (VERSION => 3))", "AT (VERSION => 3) read");

	// Snapshot consistency: a transaction's later statement must see its own snapshot, not the commit
	// another connection made after its first read.
	ok &= Ok(con.Query("BEGIN"), "begin");
	ok &= Captured(con, "SELECT count(*) FROM lake.pages", "first read in the transaction");
	ok &= Ok(other.Query("INSERT INTO lake.pages SELECT i, 'late' FROM range(3000000, 3100000) r(i)"),
	         "other connection commits 100k rows (a new data file)");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)",
	               "second read in the transaction (must not list the late file)");
	ok &= Ok(con.Query("FROM ducklake_current_snapshot('lake')"),
	         "ducklake_current_snapshot in the transaction");
	ok &= Ok(con.Query("FROM ducklake_last_committed_snapshot('lake')"),
	         "ducklake_last_committed_snapshot in the transaction");
	ok &= Ok(con.Query("COMMIT"), "commit");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)", "read after the commit (lists the late file)");

	// A transaction that has not read the lake before the other commit: which snapshot does it bind?
	ok &= Ok(con.Query("BEGIN"), "begin (no lake read yet)");
	ok &= Ok(con.Query("SELECT 42"), "a statement that does not touch the lake");
	ok &= Ok(other.Query("INSERT INTO lake.pages SELECT i, 'later' FROM range(4000000, 4000100) r(i)"),
	         "other connection commits 100 more rows");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)",
	               "first lake read in a transaction begun before the other commit");
	ok &= Ok(con.Query("FROM ducklake_current_snapshot('lake')"),
	         "ducklake_current_snapshot in that transaction");
	ok &= Ok(con.Query("COMMIT"), "commit");

	{
		// The AT clause takes no subquery: read snapshot 5's time first, then pass it as a literal.
		auto time = con.Query("SELECT snapshot_time::VARCHAR FROM ducklake_snapshots('lake') WHERE snapshot_id = 5");
		if (time->HasError()) {
			std::cout << "ERROR snapshot 5 time: " << time->GetError() << std::endl;
			ok = false;
		} else {
			auto chunk = time->Fetch();
			auto literal = chunk->GetValue(0, 0).ToString();
			ok &= Captured(con,
			               "SELECT count(*) FROM (SELECT rowid FROM lake.pages AT (TIMESTAMP => TIMESTAMPTZ '" +
			                   literal + "'))",
			               "AT (TIMESTAMP => snapshot 5's time) read");
		}
	}
	// An AT read at a snapshot before the late files: does the list drop them?
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages AT (VERSION => 5))",
	               "AT (VERSION => 5) read, before both late files");
	// A delete large enough for delete files (past the inlining limit), on two data files.
	ok &= Ok(con.Query("DELETE FROM lake.pages WHERE id % 1000 = 7"), "delete 2100 rows (delete files)");
	ok &= Ok(con.Query("SELECT max(snapshot_id) FROM lake.snapshots()"), "head snapshot after the delete");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)", "read after the delete");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages AT (VERSION => 7))",
	               "AT (VERSION => 7) read, before the delete");
	ok &= Ok(con.Query("CALL ducklake_flush_inlined_data('lake')"), "flush inlined data");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)", "read after the flush");
	ok &= Ok(con.Query("CALL ducklake_merge_adjacent_files('lake')"), "merge adjacent files");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.pages)", "read after the merge");
	// A merge needs files without deletes: three small files of a table of their own.
	ok &= Ok(con.Query("CREATE TABLE lake.m AS SELECT i AS id FROM range(100) r(i)"), "table m, file 1");
	ok &= Ok(con.Query("INSERT INTO lake.m SELECT i FROM range(100, 200) r(i)"), "table m, file 2");
	ok &= Ok(con.Query("INSERT INTO lake.m SELECT i FROM range(200, 300) r(i)"), "table m, file 3");
	ok &= Ok(con.Query("CALL ducklake_merge_adjacent_files('lake')"), "merge adjacent files of m");
	ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM lake.m)", "read of m after the merge");
	ok &= Ok(con.Query("SELECT data_file_id, path, record_count, row_id_start, begin_snapshot, end_snapshot, "
	                   "encryption_key IS NOT NULL AS has_key FROM __ducklake_metadata_lake.ducklake_data_file "
	                   "ORDER BY data_file_id"),
	         "catalog: data files at the end");
	ok &= Ok(con.Query("SELECT delete_file_id, data_file_id, path, delete_count, begin_snapshot, end_snapshot "
	                   "FROM __ducklake_metadata_lake.ducklake_delete_file ORDER BY delete_file_id"),
	         "catalog: delete files at the end");

	// Encryption: an encrypted lake's file list, the key option's presence only.
	if (have_httpfs) {
		auto emeta = (dir / "enc.ducklake").string();
		auto edata = (dir / "enc_data").string() + "/";
		ok &= Ok(con.Query("ATTACH 'ducklake:" + emeta + "' AS elake (DATA_PATH '" + edata + "', ENCRYPTED)"),
		         "attach encrypted lake");
		ok &= Ok(con.Query("CREATE TABLE elake.t AS SELECT i AS id FROM range(1000) r(i)"), "create encrypted table");
		ok &= Captured(con, "SELECT count(*) FROM (SELECT rowid FROM elake.t)", "encrypted lake read");
	} else {
		std::cout << "== httpfs unavailable: encrypted lake not probed" << std::endl;
	}

	// Join types of the tree post_bind sees, over plain DuckDB tables.
	ok &= Ok(con.Query("CREATE TABLE docs AS SELECT i AS id, i % 7 AS cat FROM range(100) r(i)"), "plain table docs");
	ok &= Ok(con.Query("CREATE TABLE other AS SELECT i AS id, i % 7 AS cat FROM range(50) r(i)"), "plain table other");
	ok &= Captured(con, "SELECT rowid FROM docs WHERE id IN (SELECT id FROM other)", "J1 IN uncorrelated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE EXISTS (SELECT 1 FROM other o WHERE o.cat = d.cat)",
	               "J2 EXISTS correlated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE NOT EXISTS (SELECT 1 FROM other o WHERE o.id = d.id)",
	               "J3 NOT EXISTS correlated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE d.id > (SELECT max(o.id) FROM other o WHERE o.cat = d.cat)",
	               "J4 scalar subquery correlated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE d.id IN (SELECT o.id FROM other o WHERE o.cat = d.cat)",
	               "J5 IN correlated");
	ok &= Captured(con,
	               "SELECT rowid, (SELECT count(*) FROM other o WHERE o.cat = d.cat) AS n FROM docs d",
	               "J6 subquery in the select list");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE d.id NOT IN (SELECT o.id FROM other o WHERE o.cat = d.cat)",
	               "J7 NOT IN correlated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE d.id > ANY (SELECT o.id FROM other o WHERE o.cat = d.cat)",
	               "J8 > ANY correlated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE d.id > (SELECT max(o.id) FROM other o)",
	               "J9 scalar subquery uncorrelated");
	ok &= Captured(con, "SELECT rowid FROM docs d WHERE EXISTS (SELECT 1 FROM other o WHERE o.id > 10)",
	               "J10 EXISTS uncorrelated");

	// In-out threading.
	ok &= Ok(con.Query("SET threads = 8"), "threads 8");
	ok &= Ok(con.Query("SELECT current_setting('threads') AS threads"), "threads now");
	std::cout << "== in-out events over 2.1M rows" << std::endl;
	ok &= Ok(con.Query("SELECT * FROM qod_probe_inout((SELECT rowid FROM lake.pages)) ORDER BY local_index"),
	         "in-out over 2.1M rows");
	std::cout << "== in-out events over a 10% filter" << std::endl;
	ok &= Ok(con.Query("SELECT * FROM qod_probe_inout((SELECT rowid FROM lake.pages WHERE id % 10 = 0)) "
	                   "ORDER BY local_index"),
	         "in-out over a 10% filter");
	std::cout << "== in-out events, global MaxThreads() = 1" << std::endl;
	ok &= Ok(con.Query("SELECT * FROM qod_probe_inout_one((SELECT rowid FROM lake.pages)) ORDER BY local_index"),
	         "in-out with MaxThreads() = 1 over 2.1M rows");
	std::cout << "== in-out events over a 2M-row plain DuckDB range" << std::endl;
	ok &= Ok(con.Query("SELECT * FROM qod_probe_inout((SELECT i FROM range(2000000) r(i))) ORDER BY local_index"),
	         "in-out over range(2000000)");
	ok &= Captured(con, "EXPLAIN SELECT * FROM qod_probe_inout((SELECT rowid FROM lake.pages))", "explain in-out");

	// Stress: the in-out query repeated while another connection runs a parallel query, so tasks wait
	// for threads. Does a local state ever start after a final, or a final look last too early?
	ok &= Ok(con.Query("CREATE TABLE big AS SELECT i, i * 7 AS j FROM range(20000000) r(i)"), "plain table big (20M)");
	inout_quiet = true;
	for (std::string function : {"qod_probe_inout", "qod_probe_inout_one"})
	for (int threads : {8, 16}) {
		ok &= Ok(con.Query("SET threads = " + std::to_string(threads)), "threads " + std::to_string(threads));
		std::atomic<bool> stop {false};
		Connection load(db);
		std::thread loader([&] {
			while (!stop.load()) {
				load.Query("SELECT sum(hash(i, j)) FROM big");
			}
		});
		int printed = 0;
		idx_t runs = 0, bad_locals = 0, early_last = 0, multi_last = 0, wrong_rows = 0, one_local = 0;
		std::map<idx_t, idx_t> locals_hist;
		for (int run = 0; run < 40; run++) {
			auto result = con.Query("SELECT count(*) FROM " + function + "((SELECT rowid FROM lake.pages))");
			if (result->HasError()) {
				std::cout << "ERROR stress: " << result->GetError() << std::endl;
				ok = false;
				break;
			}
			runs++;
			auto seen = ReadSummary();
			locals_hist[seen.locals]++;
			one_local += seen.locals == 1;
			bad_locals += seen.locals_after_a_final > 0;
			early_last += seen.inout_after_looked_last > 0;
			multi_last += seen.looked_last != 1;
			if ((seen.locals_after_a_final > 0 || seen.looked_last != 1) && printed < 3) {
				printed++;
				std::lock_guard<std::mutex> lock(events_mutex);
				std::cout << "-- stress " << function << " threads=" << threads << " run " << run << " events:"
				          << std::endl;
				for (auto &event : run_events) {
					std::cout << "   " << event << std::endl;
				}
			}
			wrong_rows += seen.finals != seen.locals;
		}
		stop = true;
		loader.join();
		std::cout << "== stress " << function << " threads=" << threads << " runs=" << runs
		          << " runs_with_exactly_one_local=" << one_local
		          << " runs_with_a_local_created_after_a_final=" << bad_locals
		          << " runs_with_inout_after_a_final_looked_last=" << early_last
		          << " runs_where_looked_last_count_is_not_1=" << multi_last
		          << " runs_with_finals_ne_locals=" << wrong_rows << " locals_histogram=";
		for (auto &entry : locals_hist) {
			std::cout << entry.first << ":" << entry.second << " ";
		}
		std::cout << std::endl;
	}
	inout_quiet = false;
	if (!keep) {
		std::filesystem::remove_all(dir);
	}
	return ok ? 0 : 1;
}
