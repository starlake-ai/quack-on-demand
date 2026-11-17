// Search Phase 0 part 2, Task 6 (spec 7.1 step 2, section 13): qod::search::ResolveSnapshotView and
// ReadDeletePositions, called from a post_bind hook on a read's target scan, against what a superuser
// connection reads at the same snapshot before any later commit: __ducklake_metadata_lake for the
// files, and the table's own SQL read for the liveness. On a DuckLake with a DuckDB-file catalog, and
// with a Postgres catalog when QOD_P2_PG_DSN_FILE names a file holding a libpq key=value connection
// string (never printed).
//
// The reads: 1 plain; 2 AT (VERSION => 3); 3 AT (TIMESTAMP => snapshot 3's time), refused for Phase 0;
// 4 a transaction whose first lake read came before another connection's commits, 4b the same read
// after them, 4c AT (VERSION => the old S) after them; 5 after a 2,001-row delete; 6 after an inlined
// delete; 7 after ducklake_flush_inlined_data, 7b and 7c at versions before it; 8 after
// ducklake_merge_adjacent_files on a second table; 9 after a merge of the target's own files, 9b at a
// version before it. Every file the client's FileSystem opens during a call is checked to be the
// target's. Then the named exception's conditions (spec 13): the calls name and open only the target
// table, take no name from client text, run no statement a hook or decider sees, and a client
// statement naming the same calls is described to the decider as the superuser-only functions it
// calls; and the client's own writes resolve into the view (Task 14, read-your-writes).
// Case E (Task 16 Step 2): an ENCRYPTED lake through the scan-only route (RunEncrypted): the view's
// liveness, a fallback read and qod_search's top 10, with no parquet_scan of a data or delete file.
// QOD_P2_T6_BENCH_ROWS=<n> also times ResolveSnapshotView and the target scan on a table of n rows.
// Exits 77 when ducklake (or, for the Postgres half, postgres) cannot load.

#include "authz.hpp"
#include "decide_client.hpp"
#include "crypto.hpp"
#include "function_classes.hpp"
#include "json.hpp"
#include "node_hooks.hpp"
#include "search_fallback.hpp"
#include "search_functions.hpp"
#include "search_lineage.hpp"
#include "search_snapshot.hpp"
#include "search_visibility.hpp"
#include "test_handles.hpp"
#include "test_search_records.hpp"
#include "search_segments.hpp"

#include "qod_search.h"

#include "duckdb.hpp"
#include "duckdb/common/opener_file_system.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

using namespace duckdb;
using View = qod::search::SnapshotView;

namespace {

// ---- output hygiene and checks ------------------------------------------------------------------

std::string g_secret_password;

std::string Redact(std::string text) {
	if (!g_secret_password.empty()) {
		size_t at;
		while ((at = text.find(g_secret_password)) != std::string::npos) {
			text.replace(at, g_secret_password.size(), "<redacted>");
		}
	}
	return text;
}

void Say(const std::string &line) {
	std::cout << Redact(line) << std::endl;
}

int g_failures = 0;
int g_checks = 0;
std::string g_kind;

void Check(bool ok, const std::string &what, const std::string &detail = "") {
	g_checks++;
	if (!ok) {
		g_failures++;
	}
	Say(std::string(ok ? "ok   " : "FAIL ") + "[" + g_kind + "] " + what + (detail.empty() ? "" : ": " + detail));
}

std::string FirstLine(const std::string &text) {
	auto line = text.substr(0, text.find('\n'));
	return Redact(line.size() > 300 ? line.substr(0, 300) + "..." : line);
}

std::string Base(const std::string &path) {
	return path.substr(path.find_last_of('/') + 1);
}

std::string Quote(const std::string &text) {
	return "'" + text + "'";
}

template <class C>
std::string Join(const C &items) {
	std::string out;
	for (auto &i : items) {
		out += (out.empty() ? "" : ", ") + i;
	}
	return "[" + out + "]";
}

bool Exec(Connection &con, const std::string &sql, const std::string &what) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		Say("   setup " + what + ": ERROR " + FirstLine(result->GetError()));
		return false;
	}
	return true;
}

//! A query's rows, fetched whole.
struct Table {
	std::vector<std::vector<Value>> rows;
	idx_t RowCount() const {
		return rows.size();
	}
	Value GetValue(idx_t col, idx_t row) const {
		return rows[row][col];
	}
};

//! Runs `sql`; false (and `error`) when it fails.
bool Try(Connection &con, const std::string &sql, Table &out, std::string &error) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		error = result->GetError();
		return false;
	}
	while (auto chunk = result->Fetch()) {
		if (chunk->size() == 0) {
			break;
		}
		for (idx_t r = 0; r < chunk->size(); r++) {
			std::vector<Value> row;
			for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
				row.push_back(chunk->GetValue(c, r));
			}
			out.rows.push_back(std::move(row));
		}
	}
	return true;
}

std::unique_ptr<Table> Must(Connection &con, const std::string &sql) {
	auto out = std::make_unique<Table>();
	std::string error;
	if (!Try(con, sql, *out, error)) {
		throw std::runtime_error("reference query failed: " + FirstLine(error));
	}
	return out;
}

int64_t Int(Connection &con, const std::string &sql) {
	auto r = Must(con, sql);
	if (r->RowCount() == 0 || r->GetValue(0, 0).IsNull()) {
		return -1;
	}
	return r->GetValue(0, 0).GetValue<int64_t>();
}

// ---- a counting client FileSystem (Task 1's), so the scans' opens are counted -------------------

std::mutex g_fs_mutex;
bool g_fs_counting = false;
std::map<std::string, idx_t> g_fs_opens;

class CountingClientFileSystem : public OpenerFileSystem {
public:
	explicit CountingClientFileSystem(unique_ptr<FileSystem> original_p) : original(std::move(original_p)) {
		inner = dynamic_cast<OpenerFileSystem *>(original.get());
	}
	FileSystem &GetFileSystem() const override {
		return inner->GetFileSystem();
	}
	optional_ptr<FileOpener> GetOpener() const override {
		return inner->GetOpener();
	}
	unique_ptr<FileHandle> OpenFileExtended(const OpenFileInfo &file, FileOpenFlags flags,
	                                        optional_ptr<FileOpener> opener = nullptr) override {
		{
			std::lock_guard<std::mutex> lock(g_fs_mutex);
			if (g_fs_counting) {
				g_fs_opens[file.path]++;
			}
		}
		return OpenerFileSystem::OpenFileExtended(file, flags, opener);
	}
	std::string GetName() const override {
		return "CountingClientFileSystem - " + inner->GetName();
	}

private:
	unique_ptr<FileSystem> original;
	OpenerFileSystem *inner;
};

bool WrapClientFileSystem(Connection &con) {
	auto &data = ClientData::Get(*con.context);
	if (!dynamic_cast<OpenerFileSystem *>(data.client_file_system.get())) {
		return false;
	}
	data.client_file_system = make_uniq<CountingClientFileSystem>(std::move(data.client_file_system));
	return true;
}

void StartCounting() {
	std::lock_guard<std::mutex> lock(g_fs_mutex);
	g_fs_opens.clear();
	g_fs_counting = true;
}

std::map<std::string, idx_t> StopCounting() {
	std::lock_guard<std::mutex> lock(g_fs_mutex);
	g_fs_counting = false;
	return g_fs_opens;
}

// ---- the post_bind hook: ResolveSnapshotView on the read's target scan ---------------------------

//! Liveness / Hit (Task 10's bench): CachedSnapshotView after clearing the cache (a miss), or with the
//! cache as it is (a hit), each followed by LiveDocs for every file (num_docs from g_record_counts).
//! ResolveOnly (Task 16 case E): ResolveSnapshotView alone, no ReadDeletePositions (it refuses an encrypted
//! delete file); ScanColumn (case E): ScanTargetRowsWithColumn of `body` limited to `only_file`.
enum class HookMode { Resolve, ScanAll, ScanOne, Liveness, Hit, ResolveOnly, ScanColumn };

//! Task 10's bench: each data file's record count, by base name.
std::map<std::string, int64_t> g_record_counts;

struct HookState {
	bool armed = false;
	ClientContext *client = nullptr;
	std::string table = "pages";
	HookMode mode = HookMode::Resolve;
	std::string only_file;
	std::optional<View> view;
	std::map<std::string, std::vector<std::pair<int64_t, int64_t>>> positions; // by delete file path
	std::vector<qod::search::TargetRow> rows;
	std::vector<qod::search::TargetRowText> text_rows;
	std::string error;
	double ms = 0;                         // ResolveSnapshotView alone
	std::map<std::string, idx_t> opens;    // files the client's FileSystem opened during it
	// Task 10's bench (Liveness, Hit): CachedSnapshotView, then LiveDocs for every file.
	double view_ms = 0;
	double live_docs_ms = 0;
	double target_scan_ms = 0;
	bool hit = false;
	size_t files = 0;
	int64_t live_docs_bits = 0;
};

HookState g_hook;

LogicalGet *FindTarget(LogicalOperator &op, const std::string &table) {
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		auto &get = op.Cast<LogicalGet>();
		auto entry = get.GetTable();
		if (entry && StringUtil::CIEquals(entry->name.GetIdentifierName(), table)) {
			return &get;
		}
	}
	for (auto &child : op.children) {
		if (auto found = FindTarget(*child, table)) {
			return found;
		}
	}
	return nullptr;
}

void SnapshotPostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!g_hook.armed || &input.context != g_hook.client || !statement.plan) {
		return;
	}
	auto get = FindTarget(*statement.plan, g_hook.table);
	if (!get) {
		return;
	}
	g_hook.armed = false;
	auto start = std::chrono::steady_clock::now();
	try {
		if (g_hook.mode == HookMode::Liveness || g_hook.mode == HookMode::Hit) {
			if (g_hook.mode == HookMode::Liveness) {
				qod::search::ClearLivenessCacheForTest();
			}
			auto t0 = std::chrono::steady_clock::now();
			bool hit = false;
			auto view = qod::search::CachedSnapshotView(input.context, *get, hit);
			auto t1 = std::chrono::steady_clock::now();
			int64_t bits = 0;
			for (size_t i = 0; i < view->files.size(); i++) {
				auto count = g_record_counts.find(Base(view->files[i].path));
				if (count == g_record_counts.end()) {
					throw InvalidInputException("bench: no record count for a listed file");
				}
				auto docs = qod::search::LiveDocs(view->liveness[i], static_cast<uint32_t>(count->second));
				bits += static_cast<int64_t>(docs.size()) + static_cast<int64_t>(docs.empty() ? 0 : docs.back() & 1);
			}
			auto t2 = std::chrono::steady_clock::now();
			g_hook.view_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
			g_hook.live_docs_ms = std::chrono::duration<double, std::milli>(t2 - t1).count();
			g_hook.target_scan_ms = view->target_scan_ms;
			g_hook.hit = hit;
			g_hook.files = view->files.size();
			g_hook.live_docs_bits = bits;
		} else if (g_hook.mode == HookMode::ResolveOnly) {
			StartCounting();
			std::optional<View> resolved;
			try {
				resolved = qod::search::ResolveSnapshotView(input.context, *get);
			} catch (...) {
				g_hook.opens = StopCounting();
				throw;
			}
			g_hook.opens = StopCounting();
			g_hook.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
			g_hook.view = std::move(*resolved);
		} else if (g_hook.mode == HookMode::ScanColumn) {
			StartCounting();
			try {
				g_hook.text_rows = qod::search::ScanTargetRowsWithColumn(input.context, *get, "body", &g_hook.only_file);
			} catch (...) {
				g_hook.opens = StopCounting();
				throw;
			}
			g_hook.opens = StopCounting();
		} else if (g_hook.mode == HookMode::Resolve) {
			StartCounting();
			std::optional<View> resolved;
			try {
				resolved = qod::search::ResolveSnapshotView(input.context, *get);
			} catch (...) {
				g_hook.opens = StopCounting();
				throw;
			}
			g_hook.opens = StopCounting();
			g_hook.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
			auto view = std::move(*resolved);
			for (auto &d : view.delete_files) {
				g_hook.positions[d.path] = qod::search::ReadDeletePositions(input.context, view, d, &view.reads);
			}
			g_hook.view = std::move(view);
		} else {
			g_hook.rows = qod::search::ScanTargetRows(input.context, *get,
			                                          g_hook.mode == HookMode::ScanOne ? &g_hook.only_file : nullptr);
		}
	} catch (std::exception &e) {
		ErrorData data(e);
		g_hook.error = data.Message();
	}
	if (g_hook.mode == HookMode::ScanAll || g_hook.mode == HookMode::ScanOne || g_hook.mode == HookMode::ScanColumn) {
		g_hook.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
	}
}

void RegisterHook(DatabaseInstance &db) {
	PlannerExtension planner;
	planner.post_bind_function = SnapshotPostBind;
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

//! Runs `sql` on `client` with the hook armed; the outer statement's error (if any) in `outer_error`.
bool RunHooked(Connection &client, const std::string &sql, std::string &outer_error, HookMode mode = HookMode::Resolve,
               const std::string &table = "pages", const std::string &only_file = "") {
	g_hook = HookState();
	g_hook.client = client.context.get();
	g_hook.table = table;
	g_hook.mode = mode;
	g_hook.only_file = only_file;
	g_hook.armed = true;
	auto result = client.Query(sql);
	g_hook.armed = false;
	outer_error = result->HasError() ? FirstLine(result->GetError()) : "";
	return g_hook.view.has_value() || (mode != HookMode::Resolve && mode != HookMode::ResolveOnly);
}

// ---- the catalog's truth at S ------------------------------------------------------------------

struct Truth {
	int64_t s = -1;
	std::set<std::string> files;          // base|row_id_start|begin|size|footer
	std::map<std::string, int64_t> record_count; // base -> record_count
	std::map<std::string, std::set<int64_t>> live; // base -> positions the table's own SQL read returns
	std::map<int64_t, std::string> file_base_by_id;
	std::set<std::string> delete_files;   // data base|delete base|size|footer
	std::set<std::string> inlined_deletes; // data base|position
	std::set<std::string> inlined_tables; // name|schema_version
	std::map<std::string, std::set<std::string>> positions; // delete base -> pos|snapshot
	std::string data_path;
};

std::string g_meta = "__ducklake_metadata_lake";

int64_t TableId(Connection &ref, const std::string &table) {
	return Int(ref, "SELECT table_id FROM " + g_meta + ".ducklake_table WHERE table_name = " + Quote(table) +
	                    " AND end_snapshot IS NULL");
}

bool MetaTableExists(Connection &ref, const std::string &name) {
	// duckdb_tables() does not list the metadata catalog's tables: ask the table itself.
	Table r;
	std::string error;
	return Try(ref, "SELECT 1 FROM " + g_meta + "." + name + " LIMIT 0", r, error);
}

Truth ReadTruth(Connection &ref, int64_t s, const std::string &table = "pages") {
	Truth t;
	t.s = s;
	auto tid = TableId(ref, table);
	auto S = std::to_string(s);
	auto dp = Must(ref, "SELECT value FROM " + g_meta + ".ducklake_metadata WHERE key = 'data_path'");
	t.data_path = dp->RowCount() ? dp->GetValue(0, 0).ToString() : "";
	auto files = Must(ref, "SELECT data_file_id, path, row_id_start, begin_snapshot, file_size_bytes, footer_size, "
	                       "record_count FROM " + g_meta + ".ducklake_data_file WHERE table_id = " +
	                           std::to_string(tid) + " AND begin_snapshot <= " + S +
	                           " AND (end_snapshot IS NULL OR end_snapshot > " + S + ")");
	for (idx_t r = 0; r < files->RowCount(); r++) {
		auto base = Base(files->GetValue(1, r).ToString());
		t.file_base_by_id[files->GetValue(0, r).GetValue<int64_t>()] = base;
		auto v = [&](idx_t c) { return files->GetValue(c, r).IsNull() ? std::string("-1") : files->GetValue(c, r).ToString(); };
		t.files.insert(base + "|" + v(2) + "|" + v(3) + "|" + v(4) + "|" + v(5));
		t.record_count[base] = files->GetValue(6, r).GetValue<int64_t>();
	}
	auto dels = Must(ref, "SELECT data_file_id, path, file_size_bytes, footer_size FROM " + g_meta +
	                          ".ducklake_delete_file WHERE table_id = " + std::to_string(tid) + " AND begin_snapshot <= " +
	                          S + " AND (end_snapshot IS NULL OR end_snapshot > " + S + ")");
	for (idx_t r = 0; r < dels->RowCount(); r++) {
		auto data_base = t.file_base_by_id[dels->GetValue(0, r).GetValue<int64_t>()];
		auto path = dels->GetValue(1, r).ToString();
		auto footer = dels->GetValue(3, r).IsNull() ? std::string("0") : dels->GetValue(3, r).ToString();
		t.delete_files.insert(data_base + "|" + Base(path) + "|" + dels->GetValue(2, r).ToString() + "|" + footer);
		// The delete file's positions and snapshot column, as the file holds them.
		auto full = path;
		if (!full.empty() && full[0] != '/') {
			// a relative path: look the full one up beside the data file (same directory)
			for (auto &entry : std::filesystem::recursive_directory_iterator(t.data_path)) {
				if (entry.path().filename().string() == Base(path)) {
					full = entry.path().string();
				}
			}
		}
		auto pos = std::make_unique<Table>();
		std::string error;
		if (!Try(ref, "SELECT pos, _ducklake_internal_snapshot_id FROM read_parquet(" + Quote(full) + ")", *pos, error)) {
			pos = Must(ref, "SELECT pos, -1 FROM read_parquet(" + Quote(full) + ")");
		}
		auto &set = t.positions[Base(path)];
		for (idx_t i = 0; i < pos->RowCount(); i++) {
			set.insert(pos->GetValue(0, i).ToString() + "|" +
			           (pos->GetValue(1, i).IsNull() ? std::string("-1") : pos->GetValue(1, i).ToString()));
		}
	}
	auto inlined_delete_table = "ducklake_inlined_delete_" + std::to_string(tid);
	if (MetaTableExists(ref, inlined_delete_table)) {
		auto inl = Must(ref, "SELECT file_id, row_id FROM " + g_meta + "." + inlined_delete_table +
		                         " WHERE begin_snapshot <= " + S);
		for (idx_t r = 0; r < inl->RowCount(); r++) {
			auto id = inl->GetValue(0, r).GetValue<int64_t>();
			if (t.file_base_by_id.count(id)) {
				t.inlined_deletes.insert(t.file_base_by_id[id] + "|" + inl->GetValue(1, r).ToString());
			}
		}
	}
	auto tables = Must(ref, "SELECT table_name, schema_version FROM " + g_meta +
	                            ".ducklake_inlined_data_tables WHERE table_id = " + std::to_string(tid));
	for (idx_t r = 0; r < tables->RowCount(); r++) {
		t.inlined_tables.insert(tables->GetValue(0, r).ToString() + "|" + tables->GetValue(1, r).ToString());
	}
	return t;
}

//! The positions a superuser's own read of the table returns, per data file (the definition of live:
//! what the client's read returns), at `version` (-1: the connection's snapshot).
void ReadLiveTruth(Connection &ref, Truth &t, const std::string &table = "pages", int64_t version = -1) {
	auto rows = Must(ref, "SELECT filename, file_row_number FROM lake.main." + table +
	                          (version >= 0 ? " AT (VERSION => " + std::to_string(version) + ")" : ""));
	t.live.clear();
	for (idx_t r = 0; r < rows->RowCount(); r++) {
		auto name = rows->GetValue(0, r).ToString();
		if (name.find(".parquet") != std::string::npos) {
			t.live[Base(name)].insert(rows->GetValue(1, r).GetValue<int64_t>());
		}
	}
}

Truth TruthAt(Connection &ref, int64_t s, bool at_clause, const std::string &table = "pages") {
	auto t = ReadTruth(ref, s, table);
	ReadLiveTruth(ref, t, table, at_clause ? s : -1);
	return t;
}

// ---- comparing a view with the truth -------------------------------------------------------------

struct Got {
	std::set<std::string> files, delete_files, inlined_tables;
	std::map<std::string, std::set<std::string>> positions;
	std::map<std::string, std::set<int64_t>> live;
};

Got Summarize(const View &v) {
	Got g;
	for (auto &f : v.files) {
		g.files.insert(Base(f.path) + "|" + std::to_string(f.row_id_start) + "|" + std::to_string(f.begin_snapshot) + "|" +
		               std::to_string(f.file_size) + "|" + std::to_string(f.footer_size));
	}
	for (size_t i = 0; i < v.files.size() && i < v.liveness.size(); i++) {
		auto &set = g.live[Base(v.files[i].path)];
		for (int64_t p = 0; p < v.liveness[i].end; p++) {
			if (v.liveness[i].IsLive(p)) {
				set.insert(p);
			}
		}
		if (set.empty()) {
			g.live.erase(Base(v.files[i].path));
		}
	}
	for (auto &d : v.delete_files) {
		g.delete_files.insert(Base(d.data_file_path) + "|" + Base(d.path) + "|" + std::to_string(d.file_size) + "|" +
		                      std::to_string(d.footer_size));
	}
	for (auto &t : v.inlined_tables) {
		g.inlined_tables.insert(t.table_name + "|" + std::to_string(t.schema_version));
	}
	for (auto &entry : g_hook.positions) {
		auto &set = g.positions[Base(entry.first)];
		for (auto &p : entry.second) {
			set.insert(std::to_string(p.first) + "|" + std::to_string(p.second));
		}
	}
	return g;
}

//! Positions hidden at S (HiddenPositions with the catalog's record count) that no delete file
//! position at or before S explains: the catalog's inlined deletes, plus rows of a backdated file
//! stamped after S (P6).
std::set<std::string> HiddenBeyondDeleteFiles(const View &v, const Truth &truth) {
	std::set<std::string> out;
	for (size_t i = 0; i < v.files.size(); i++) {
		auto base = Base(v.files[i].path);
		auto rc = truth.record_count.count(base) ? truth.record_count.at(base) : 0;
		std::set<int64_t> deleted;
		for (auto &d : v.delete_files) {
			if (d.file_index == i) {
				for (auto &p : g_hook.positions[d.path]) {
					if (p.second < 0 || p.second <= v.snapshot_id) {
						deleted.insert(p.first);
					}
				}
			}
		}
		for (auto &run : qod::search::HiddenPositions(v.liveness[i], rc)) {
			for (auto p = run.first; p < run.second; p++) {
				if (!deleted.count(p)) {
					out.insert(base + "|" + std::to_string(p));
				}
			}
		}
	}
	return out;
}

//! Every file the client's FileSystem opened while ResolveSnapshotView ran is a listed data or delete
//! file of the target (independent of the code's own log).
bool OpensOnlyTarget(const View &v, std::string &why) {
	std::set<std::string> paths;
	for (auto &f : v.files) {
		paths.insert(f.path);
	}
	for (auto &d : v.delete_files) {
		paths.insert(d.path);
	}
	for (auto &o : g_hook.opens) {
		if (!paths.count(o.first)) {
			why = "opened " + o.first;
			return false;
		}
	}
	return true;
}

//! Every ruled call names only the target (alias lake, schema main, table `table`), and every path read
//! is one the view lists for that table.
bool ReadsOnlyTarget(const View &v, const std::string &table, std::string &why) {
	std::set<std::string> paths;
	for (auto &f : v.files) {
		paths.insert(f.path);
	}
	for (auto &d : v.delete_files) {
		paths.insert(d.path);
	}
	for (auto &r : v.reads) {
		bool ok = false;
		if (r == "ducklake_current_snapshot('lake')") {
			ok = true;
		} else if (r.rfind("ducklake_list_files('lake', '" + table + "', schema => 'main', snapshot_version => ", 0) == 0) {
			ok = true;
		} else if (r.rfind("ducklake_scan('lake'.'main'.'" + table + "'", 0) == 0) {
			ok = true;
		} else if (r.rfind("parquet_scan('", 0) == 0) {
			auto end = r.find("')");
			ok = end != std::string::npos && paths.count(r.substr(14, end - 14));
		}
		if (!ok) {
			why = "unexpected read: " + r;
			return false;
		}
	}
	return true;
}

void CompareView(const std::string &label, const Truth &truth, const std::string &outer_error,
                 bool hidden_are_inlined_deletes = true) {
	if (!g_hook.view) {
		Check(false, label + ": ResolveSnapshotView ran", g_hook.error.empty() ? "hook did not fire" : FirstLine(g_hook.error));
		return;
	}
	auto &v = *g_hook.view;
	Check(outer_error.empty(), label + ": the read itself ran", outer_error);
	Check(v.snapshot_id == truth.s, label + ": snapshot_id",
	      std::to_string(v.snapshot_id) + " vs " + std::to_string(truth.s));
	auto g = Summarize(v);
	Check(g.files == truth.files, label + ": data files (path, row_id_start, begin, size, footer)",
	      Join(g.files) + " vs " + Join(truth.files));
	bool ids_unknown = true;
	for (auto &f : v.files) {
		ids_unknown = ids_unknown && f.data_file_id == -1 && !f.flags_known && f.record_count == -1 &&
		              f.bundle_key == qod::Hex(qod::Sha256(f.path)) && f.bundle_key.size() == 64;
		Check(f.path.rfind(truth.data_path, 0) == 0, label + ": data file path is absolute under data_path", Base(f.path));
	}
	Check(ids_unknown, label + ": every file: data_file_id and record_count -1, flags_known false, bundle_key = "
	                           "hex SHA-256 of its path");
	Check(g.live == truth.live, label + ": liveness per file equals the positions the table's own read returns",
	      std::to_string(g.live.size()) + " files vs " + std::to_string(truth.live.size()));
	Check(g.delete_files == truth.delete_files, label + ": delete files (data file, path, size, footer)",
	      Join(g.delete_files) + " vs " + Join(truth.delete_files));
	auto beyond = HiddenBeyondDeleteFiles(v, truth);
	if (hidden_are_inlined_deletes) {
		Check(beyond == truth.inlined_deletes,
		      label + ": hidden positions beyond the delete files equal the catalog's inlined deletes",
		      Join(beyond) + " vs " + Join(truth.inlined_deletes));
	}
	Check(g.inlined_tables == truth.inlined_tables, label + ": inlined tables",
	      Join(g.inlined_tables) + " vs " + Join(truth.inlined_tables));
	Check(g.positions == truth.positions, label + ": ReadDeletePositions equals read_parquet(pos, snapshot column)");
	std::string why;
	Check(ReadsOnlyTarget(v, "pages", why), label + ": every ruled call names only the target table", why);
	Check(!g_hook.opens.empty() || v.files.empty(), label + ": the client's FileSystem counted the opens");
	Check(OpensOnlyTarget(v, why), label + ": every file opened is a listed file of the target", why);
	std::string flags;
	for (auto &f : v.files) {
		flags += (flags.empty() ? "" : ",") + std::string(f.has_deletes ? "D" : "-");
	}
	Say("   " + label + ": " + std::to_string(v.reads.size()) + " ruled calls, " + std::to_string(g_hook.ms) +
	    " ms; has_deletes per file: " + flags + "; hidden beyond delete files " + std::to_string(beyond.size()) +
	    "; files opened " + std::to_string(g_hook.opens.size()));
}

// ---- the scenario ------------------------------------------------------------------------------

std::map<std::string, std::string> ParseDsn(const std::string &dsn) {
	std::map<std::string, std::string> out;
	std::istringstream in(dsn);
	std::string token;
	while (in >> token) {
		auto eq = token.find('=');
		if (eq != std::string::npos) {
			out[token.substr(0, eq)] = token.substr(eq + 1);
		}
	}
	return out;
}

//! Loads ducklake (and postgres), attaches the lake. 77 when an extension cannot load.
int AttachLake(Connection &setup, const std::string &kind, const std::filesystem::path &dir, const std::string &dsn_file,
               const std::string &db_name) {
	for (std::string ext : {"ducklake", "parquet"}) {
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("== " + kind + ": " + ext + " unavailable, skipped");
			return 77;
		}
	}
	std::string attach;
	if (kind == "postgres") {
		if (setup.Query("INSTALL postgres")->HasError() || setup.Query("LOAD postgres")->HasError()) {
			Say("== postgres unavailable, skipped");
			return 77;
		}
		std::ifstream in(dsn_file);
		std::string dsn((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		auto parts = ParseDsn(dsn);
		g_secret_password = parts["password"];
		auto secret = "CREATE TEMPORARY SECRET (TYPE postgres, HOST " + Quote(parts["host"]) + ", PORT " + parts["port"] +
		              ", USER " + Quote(parts["user"]) + ", PASSWORD " + Quote(parts["password"]) + ")";
		if (setup.Query(secret)->HasError()) {
			Say("== postgres: the secret could not be created (statement not printed)");
			return 1;
		}
		if (!Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach admin")) {
			return 1;
		}
		for (auto stmt : {"DROP DATABASE IF EXISTS " + db_name + " WITH (FORCE)", "CREATE DATABASE " + db_name}) {
			if (!Exec(setup, "CALL postgres_execute('pgadmin', " + Quote(stmt) + ", use_transaction => false)", stmt)) {
				return 1;
			}
		}
		Exec(setup, "DETACH pgadmin", "detach admin");
		attach = "ATTACH 'ducklake:postgres:dbname=" + db_name + "' AS lake (DATA_PATH " +
		         Quote((dir / "data").string() + "/") + ", DATA_INLINING_ROW_LIMIT 10)";
	} else {
		attach = "ATTACH " + Quote("ducklake:" + (dir / "meta.ducklake").string()) + " AS lake (DATA_PATH " +
		         Quote((dir / "data").string() + "/") + ", DATA_INLINING_ROW_LIMIT 10)";
	}
	return Exec(setup, attach, "attach lake") ? 0 : 1;
}

int64_t Head(Connection &con) {
	return Int(con, "SELECT id FROM ducklake_current_snapshot('lake')");
}

const char *kRead = "SELECT rowid FROM lake.main.pages";

//! True when the hook refused with `prefix`.
bool RefusedWith(const std::string &prefix) {
	return !g_hook.view && g_hook.error.find(prefix) != std::string::npos;
}

int64_t LiveCount(const View &v) {
	int64_t n = 0;
	for (auto &l : v.liveness) {
		n += l.live_count;
	}
	return n;
}

void RunScenario(const std::string &kind, const std::filesystem::path &dir, const std::string &dsn_file, int &code) {
	g_kind = kind;
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir / "data");
	std::filesystem::create_directories(dir / "data2");
	DuckDB db(nullptr);
	RegisterHook(*db.instance);
	Connection setup(db);
	code = AttachLake(setup, kind, dir, dsn_file, "qod_t6_snapshot");
	if (code != 0) {
		return;
	}
	g_meta = "__ducklake_metadata_lake";
	// a second lake (DuckDB-file catalog), for a transaction that writes another lake
	bool ok = Exec(setup, "ATTACH " + Quote("ducklake:" + (dir / "meta2.ducklake").string()) + " AS lake2 (DATA_PATH " +
	                          Quote((dir / "data2").string() + "/") + ")",
	               "attach lake2");
	ok = ok && Exec(setup, "CREATE TABLE lake2.main.t (id BIGINT)", "create lake2.t");
	ok = ok && Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR)", "create");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages SELECT i, 'title ' || i, 'body ' || i FROM range(0, 10000) r(i)", "A");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages SELECT i, 'title ' || i, 'body ' || i FROM range(10000, 20000) r(i)", "B");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages VALUES (90001, 'a', 'x'), (90002, 'b', 'x'), (90003, 'c', 'x')", "inl 1");
	auto s_inl1 = Head(setup);
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages VALUES (90004, 'd', 'x'), (90005, 'e', 'x')", "inl 2");
	ok = ok && Exec(setup, "CREATE TABLE lake.main.other (id BIGINT, v VARCHAR)", "create other");
	for (int i = 0; i < 3 && ok; i++) {
		ok = Exec(setup, "INSERT INTO lake.main.other SELECT i, 'v' FROM range(" + std::to_string(i * 100) + ", " +
		                     std::to_string(i * 100 + 100) + ") r(i)",
		          "other file");
	}
	if (!ok) {
		code = 1;
		return;
	}
	Connection client(db);
	Connection ref(db);
	Say("== " + kind + ": client FileSystem wrapped for counting: " + (WrapClientFileSystem(client) ? "yes" : "no"));
	std::string err;

	// 1. plain read
	auto truth = TruthAt(ref, Head(ref), false);
	RunHooked(client, kRead, err);
	CompareView("1 plain read", truth, err);

	// 2. AT (VERSION => 3)
	auto t3 = TruthAt(ref, 3, true);
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (VERSION => 3)", err);
	CompareView("2 AT (VERSION => 3)", t3, err);

	// 3. AT (TIMESTAMP => snapshot 3's time): refused for Phase 0 (OWNER RULING 2026-11-14).
	auto time3 = Must(ref, "SELECT snapshot_time::VARCHAR FROM " + g_meta + ".ducklake_snapshot WHERE snapshot_id = 3")
	                 ->GetValue(0, 0)
	                 .ToString();
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (TIMESTAMP => TIMESTAMPTZ " + Quote(time3) + ")", err);
	Check(RefusedWith("QoD: search_at_timestamp_unsupported: AT (TIMESTAMP => ...) is not supported by search in Phase 0"),
	      "3 AT (TIMESTAMP => t): refused, naming AT (VERSION => n)", FirstLine(g_hook.error));
	Check(err.empty(), "3 AT (TIMESTAMP => t): the read itself ran (the refusal is the search's only)", err);

	// 5. after a 2,001-row delete (one delete file)
	ok = Exec(setup, "DELETE FROM lake.main.pages WHERE id BETWEEN 12000 AND 14000", "2001-row delete");
	truth = TruthAt(ref, Head(ref), false);
	Check(truth.delete_files.size() == 1, "5 the scenario has one delete file", Join(truth.delete_files));
	RunHooked(client, kRead, err);
	CompareView("5 after a 2,001-row delete", truth, err);

	// 6. after an inlined delete (file A, id 5)
	ok = ok && Exec(setup, "DELETE FROM lake.main.pages WHERE id = 5", "inlined delete");
	truth = TruthAt(ref, Head(ref), false);
	Check(truth.inlined_deletes.size() == 1, "6 the scenario has one inlined delete", Join(truth.inlined_deletes));
	RunHooked(client, kRead, err);
	CompareView("6 after an inlined delete", truth, err);

	// 4. a transaction whose first lake read came before another connection's commits
	Exec(client, "BEGIN TRANSACTION", "begin");
	Must(client, "SELECT count(*) FROM lake.main.pages");
	auto before = TruthAt(ref, Head(ref), false);
	{
		Connection other(db);
		Exec(other, "INSERT INTO lake.main.pages SELECT i, 't', 'b' FROM range(30000, 35000) r(i)", "other insert");
		Exec(other, "DELETE FROM lake.main.pages WHERE id BETWEEN 15000 AND 15999", "other delete (replaces B's delete file)");
		Exec(other, "DELETE FROM lake.main.pages WHERE id = 30010", "other inlined delete (position 10, rowid 20015)");
		Exec(other, "DELETE FROM lake.main.pages WHERE id = 90001", "other delete of an inlined row");
		Exec(other, "CALL ducklake_merge_adjacent_files('lake')", "other merge");
	}
	RunHooked(client, kRead, err);
	CompareView("4 a transaction that read before another connection's commits", before, err);
	auto after = TruthAt(ref, Head(ref), false);
	Check(after.s > before.s && after.delete_files != before.delete_files,
	      "4 the other connection did move the catalog on", std::to_string(after.s));
	Exec(client, "ROLLBACK", "rollback");

	// 4b. the same state, read now
	RunHooked(client, kRead, err);
	CompareView("4b the same read after the commits", after, err);
	auto live_4b = g_hook.view ? LiveCount(*g_hook.view) : -1;

	// 4c. AT (VERSION => the old S) after those commits: B's delete file is now the backdated
	// replacement, whose positions stamped after S must not count; the view must equal the truth taken
	// before the commits.
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(before.s) + ")", err);
	if (g_hook.view) {
		auto g = Summarize(*g_hook.view);
		Check(g.live == before.live, "4c AT (VERSION => " + std::to_string(before.s) +
		                                 ") after the commits: liveness equals the truth taken before them");
		Check(g.files == before.files, "4c the same data files as before the commits", Join(g.files));
		Check(OpensOnlyTarget(*g_hook.view, err), "4c every file opened is a listed file of the target", err);
		Say("   4c delete files now listed at the old S: " + Join(g.delete_files) + " (before: " +
		    Join(before.delete_files) + ")");
	} else {
		Check(false, "4c resolves", FirstLine(g_hook.error));
	}

	// 7. after ducklake_flush_inlined_data
	ok = ok && Exec(setup, "CALL ducklake_flush_inlined_data('lake')", "flush");
	truth = TruthAt(ref, Head(ref), false);
	RunHooked(client, kRead, err);
	CompareView("7 after ducklake_flush_inlined_data", truth, err);

	// 7b. AT (VERSION => the pre-flush S) after the flush: the flushed file (backdated) now holds that
	// state's inlined rows; the same rows, another representation.
	auto pre_flush = after.s;
	auto t_pre = TruthAt(ref, pre_flush, true);
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(pre_flush) + ")", err);
	CompareView("7b AT (VERSION => " + std::to_string(pre_flush) + ") after the flush", t_pre, err, false);
	if (g_hook.view) {
		int64_t inlined_rows = 0;
		for (auto &t : g_hook.view->inlined_tables) {
			inlined_rows += t.live_rows;
		}
		Check(LiveCount(*g_hook.view) + inlined_rows == live_4b + 4,
		      "7b the live rows at that S are the same rows as before the flush (now in files)",
		      std::to_string(LiveCount(*g_hook.view)) + " in files + " + std::to_string(inlined_rows) +
		          " inlined vs " + std::to_string(live_4b) + " in files + 4 inlined before the flush");
	}

	// 7c. AT (VERSION => the first inlined insert) after the flush: the flushed file begins there but
	// holds rows stamped later, which the scan hides: hidden positions that are not inlined deletes.
	auto t_inl = TruthAt(ref, s_inl1, true);
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(s_inl1) + ")", err);
	CompareView("7c AT (VERSION => " + std::to_string(s_inl1) + ") after the flush", t_inl, err, false);
	if (g_hook.view) {
		auto beyond = HiddenBeyondDeleteFiles(*g_hook.view, t_inl);
		Check(!beyond.empty() && t_inl.inlined_deletes.empty(),
		      "7c hidden positions beyond the delete files are not the catalog's inlined deletes (rows stamped after S)",
		      Join(beyond));
	}

	// 8. after ducklake_merge_adjacent_files on a second table
	for (int i = 3; i < 5 && ok; i++) {
		ok = Exec(setup, "INSERT INTO lake.main.other SELECT i, 'v' FROM range(" + std::to_string(i * 100) + ", " +
		                     std::to_string(i * 100 + 100) + ") r(i)",
		          "other file");
	}
	auto other_before = ReadTruth(ref, Head(ref), "other");
	auto pages_before = Head(ref);
	ok = ok && Exec(setup, "CALL ducklake_merge_adjacent_files('lake', 'other')", "merge other");
	auto other_after = ReadTruth(ref, Head(ref), "other");
	Check(other_after.files.size() < other_before.files.size() && Head(ref) > pages_before,
	      "8 the merge of other did merge its files",
	      std::to_string(other_before.files.size()) + " -> " + std::to_string(other_after.files.size()) + " files");
	truth = TruthAt(ref, Head(ref), false);
	RunHooked(client, kRead, err);
	CompareView("8 after merge_adjacent_files on a second table", truth, err);
	auto other_truth = TruthAt(ref, Head(ref), false, "other");
	RunHooked(client, "SELECT rowid FROM lake.main.other", err, HookMode::Resolve, "other");
	if (g_hook.view) {
		auto g = Summarize(*g_hook.view);
		Check(g.files == other_truth.files && g.live == other_truth.live, "8 the second table's files and liveness",
		      Join(g.files) + " vs " + Join(other_truth.files));
		std::string why;
		Check(ReadsOnlyTarget(*g_hook.view, "other", why) && OpensOnlyTarget(*g_hook.view, why),
		      "8 the second table's calls and opens name only it", why);
	} else {
		Check(false, "8 the second table resolves", FirstLine(g_hook.error));
	}

	// 9. a merge of pages' own files with per-row snapshots, then reads before, at and after it
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages SELECT i, 't', 'b' FROM range(40000, 40020) r(i)", "small file 1");
	auto s_small1 = Head(setup);
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages SELECT i, 't', 'b' FROM range(40020, 40040) r(i)", "small file 2");
	auto files_before_merge = ReadTruth(ref, Head(ref)).files.size();
	ok = ok && Exec(setup, "CALL ducklake_merge_adjacent_files('lake', 'pages')", "merge pages");
	auto files_after_merge = ReadTruth(ref, Head(ref)).files.size();
	Check(files_after_merge < files_before_merge, "9 the merge of pages did merge files",
	      std::to_string(files_before_merge) + " -> " + std::to_string(files_after_merge));
	truth = TruthAt(ref, Head(ref), false);
	RunHooked(client, kRead, err);
	CompareView("9 after merge_adjacent_files on pages", truth, err);
	auto t_small = TruthAt(ref, s_small1, true);
	RunHooked(client, "SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(s_small1) + ")", err);
	CompareView("9b AT (VERSION => " + std::to_string(s_small1) + ") after the merge", t_small, err, false);
	if (g_hook.view) {
		auto beyond = HiddenBeyondDeleteFiles(*g_hook.view, t_small);
		Check(!beyond.empty() && t_small.inlined_deletes.empty(),
		      "9b hidden positions beyond the delete files are not the catalog's inlined deletes (rows stamped after S)",
		      Join(beyond));
	}

	// ---- the exception's conditions (spec 13) ----
	// a join: the target is pages; nothing names or opens other
	RunHooked(client, "SELECT p.rowid FROM lake.main.pages p JOIN lake.main.other o ON p.id = o.id", err);
	if (g_hook.view) {
		bool names_other = false;
		for (auto &r : g_hook.view->reads) {
			names_other = names_other || r.find("other") != std::string::npos;
		}
		bool opens_other = false;
		for (auto &o : g_hook.opens) {
			for (auto &f : other_truth.files) {
				opens_other = opens_other || f.rfind(Base(o.first) + "|", 0) == 0;
			}
		}
		std::string why;
		Check(!names_other && !opens_other && OpensOnlyTarget(*g_hook.view, why),
		      "a join: no ruled call names the other table and no file of it is opened",
		      Join(g_hook.view->reads) + " " + why);
	} else {
		Check(false, "a join resolves", FirstLine(g_hook.error));
	}
	// names come from the resolved entry, not the text: another spelling, and no catalog in the text
	RunHooked(client, kRead, err);
	auto canonical = g_hook.view ? g_hook.view->reads : std::vector<std::string>();
	auto canonical_opens = g_hook.opens;
	RunHooked(client, "SELECT rowid FROM LaKe.MaIn.\"PAGES\"", err);
	Check(g_hook.view && g_hook.view->reads == canonical && g_hook.opens == canonical_opens &&
	          g_hook.view->catalog_alias == "lake" && g_hook.view->table_name == "pages",
	      "another spelling: the calls and opens are the resolved entry's (lake, main, pages)",
	      g_hook.view ? Join(g_hook.view->reads) : FirstLine(g_hook.error + err));
	Exec(client, "USE lake", "use");
	RunHooked(client, "SELECT rowid FROM pages", err);
	Check(g_hook.view && g_hook.view->reads == canonical && g_hook.opens == canonical_opens,
	      "no catalog in the text: the calls and opens are the resolved entry's",
	      g_hook.view ? Join(g_hook.view->reads) : FirstLine(g_hook.error + err));
	Exec(client, "USE memory", "use memory");
	// a session's own macros of the same names cannot shadow the calls (system.main lookups)
	Exec(client, "CREATE TEMP MACRO ducklake_current_snapshot(a) AS TABLE SELECT 999::UBIGINT AS id", "shadow 1");
	Exec(client, "CREATE TEMP MACRO ducklake_list_files(a, b, schema := NULL, snapshot_version := NULL) AS TABLE "
	             "SELECT 'x' AS data_file",
	     "shadow 2");
	Exec(client, "CREATE TEMP MACRO parquet_scan(a) AS TABLE SELECT -1::BIGINT AS pos", "shadow 3");
	Exec(client, "CREATE TEMP MACRO read_parquet(a) AS TABLE SELECT -1::BIGINT AS pos", "shadow 4");
	auto head = Head(ref);
	RunHooked(client, kRead, err);
	Check(g_hook.view && g_hook.view->snapshot_id == head && g_hook.view->reads == canonical &&
	          Summarize(*g_hook.view).files == truth.files && Summarize(*g_hook.view).positions == truth.positions,
	      "session macros named ducklake_current_snapshot, ducklake_list_files, parquet_scan, read_parquet do not "
	      "shadow the calls",
	      g_hook.view ? std::to_string(g_hook.view->snapshot_id) : FirstLine(g_hook.error + err));
	for (auto m : {"ducklake_current_snapshot", "ducklake_list_files", "parquet_scan", "read_parquet"}) {
		Exec(client, std::string("DROP MACRO TABLE temp.main.") + m, "drop shadow");
	}
	// a scanned file name the listing does not name fails closed
	RunHooked(client, kRead, err);
	if (g_hook.view && !g_hook.view->files.empty()) {
		auto &v = *g_hook.view;
		bool refused = false;
		try {
			qod::search::FileIndexForScanName(v, v.files[0].path + "x");
		} catch (std::exception &e) {
			refused = ErrorData(e).Message().find("QoD: search_snapshot_unavailable") != std::string::npos;
		}
		Check(refused && qod::search::FileIndexForScanName(v, v.files[0].path) == 0 &&
		          (v.inlined_tables.empty() || qod::search::FileIndexForScanName(v, v.inlined_tables[0].table_name) == -1),
		      "a scanned file name the listing does not name is refused (no silent miss)");
	}

	// the inlined rows through the target's own scan, limited to one inlined table by filename
	Exec(setup, "INSERT INTO lake.main.pages VALUES (90011, 'f', 'x'), (90012, 'g', 'x')", "inlined insert after flush");
	RunHooked(client, kRead, err);
	if (g_hook.view && !g_hook.view->inlined_tables.empty()) {
		auto name = g_hook.view->inlined_tables.back().table_name;
		Check(g_hook.view->inlined_tables.back().live_rows == 2, "the inlined table's live rows are counted",
		      std::to_string(g_hook.view->inlined_tables.back().live_rows));
		StartCounting();
		RunHooked(client, kRead, err, HookMode::ScanOne, "pages", name);
		auto opens = StopCounting();
		std::set<std::string> names;
		for (auto &r : g_hook.rows) {
			names.insert(r.filename);
		}
		idx_t parquet_opens = 0;
		for (auto &o : opens) {
			parquet_opens += o.first.find(".parquet") != std::string::npos ? o.second : 0;
		}
		Check(g_hook.error.empty() && g_hook.rows.size() == 2 && names.size() == 1 && *names.begin() == name,
		      "ScanTargetRows with a filename filter returns only that inlined table's rows",
		      std::to_string(g_hook.rows.size()) + " rows " + Join(names) + " " + FirstLine(g_hook.error));
		// Measured, not asserted (P6): DuckLake's reader applies the filter to the rows, not before it
		// opens a file.
		Say("   ScanTargetRows with a filename filter on " + name + ": " + std::to_string(parquet_opens) +
		    " Parquet opens, " + std::to_string(g_hook.ms) + " ms");
	} else {
		Check(false, "an inlined table after the flush and a new insert", FirstLine(g_hook.error));
	}

	// the client's own writes (Task 14, read-your-writes, lifting the OWNER RULING 2026-11-14 refusal): the view
	// resolves in the transaction, its deletes applied by the scan, its own rows named as transaction-local sources
	// (an inlined entry, a data file with no snapshot), none of them listed by ducklake_list_files
	struct Write {
		std::string what, sql;
		int64_t live_delta;      // live rows of pages the view gains (or loses) against the committed view
		size_t local_files;      // transaction-local data files in the view
		bool local_inlined;      // a transaction-local inlined entry in the view
		int64_t local_rows;      // live rows in the transaction-local sources
	};
	RunHooked(client, kRead, err);
	auto committed_live = g_hook.view ? LiveCount(*g_hook.view) : -1;
	int64_t committed_inlined = 0;
	if (g_hook.view) {
		for (auto &t : g_hook.view->inlined_tables) {
			committed_inlined += t.live_rows;
		}
	}
	for (auto &w : std::vector<Write> {{"deleted a row", "DELETE FROM lake.main.pages WHERE id = 1", -1, 0, false, 0},
	                                   {"inserted an inlined row", "INSERT INTO lake.main.pages VALUES (99999, 'l', 'x')", 1,
	                                    0, true, 1},
	                                   {"inserted a file of rows",
	                                    "INSERT INTO lake.main.pages SELECT i, 'l', 'x' FROM range(50000, 50100) r(i)", 100,
	                                    1, false, 100},
	                                   {"wrote another table of the lake", "INSERT INTO lake.main.other VALUES (7, 'w')", 0,
	                                    0, false, 0}}) {
		// The write must itself succeed, or the case would test nothing.
		bool wrote = Exec(client, "BEGIN TRANSACTION", "begin") && Exec(client, w.sql, w.what);
		RunHooked(client, kRead, err);
		bool ok = wrote && g_hook.view.has_value();
		std::string detail = wrote ? FirstLine(g_hook.error) : "the write itself failed";
		if (ok) {
			auto &v = *g_hook.view;
			size_t local_files = 0;
			int64_t local_rows = 0, inlined = 0;
			bool local_inlined = false;
			for (size_t i = 0; i < v.files.size(); i++) {
				if (v.files[i].transaction_local) {
					local_files++;
					local_rows += v.liveness[i].live_count;
					ok = ok && v.files[i].begin_snapshot == -1;
				}
			}
			for (auto &t : v.inlined_tables) {
				inlined += t.live_rows;
				if (t.transaction_local) {
					local_inlined = true;
					local_rows += t.live_rows;
				}
			}
			auto delta = LiveCount(v) + inlined - committed_live - committed_inlined;
			ok = ok && delta == w.live_delta && local_files == w.local_files && local_inlined == w.local_inlined &&
			     local_rows == w.local_rows &&
			     v.transaction_local_names.size() == w.local_files + (w.local_inlined ? 1 : 0);
			detail = "live " + std::to_string(delta) + ", " + std::to_string(local_files) + " local file(s), inlined " +
			         (local_inlined ? "yes" : "no") + ", " + std::to_string(local_rows) + " local rows, " +
			         std::to_string(v.transaction_local_names.size()) + " names";
		}
		Check(ok, "a transaction that " + w.what + ": the view resolves with the transaction's own writes", detail);
		Exec(client, "ROLLBACK", "rollback");
	}
	bool wrote_lake2 = Exec(client, "BEGIN TRANSACTION", "begin") &&
	                   Exec(client, "INSERT INTO lake2.main.t VALUES (1)", "write lake2");
	RunHooked(client, kRead, err);
	Check(wrote_lake2 && g_hook.view.has_value(), "a transaction that wrote another lake (lake2): resolves",
	      wrote_lake2 ? FirstLine(g_hook.error) : "the write to lake2 itself failed");
	Exec(client, "ROLLBACK", "rollback");
	RunHooked(client, kRead, err);
	Check(g_hook.view.has_value(), "auto-commit after those transactions: resolves", FirstLine(g_hook.error));

	// the reads ran in the client's transaction: the client's own read still works afterwards
	auto count = client.Query("SELECT count(*) FROM lake.main.pages");
	Check(!count->HasError(), "the client's own read after the calls", count->HasError() ? FirstLine(count->GetError()) : "");

	// a rename between S and now (review of Task 6, Minor 8): ducklake_list_files resolves the table by
	// name at S, so a name that meant another table at S must still give either exactly what the
	// client's own read of the same statement returns, or search_snapshot_unavailable; never another
	// table's files.
	auto s_before_rename = Head(ref);
	ok = Exec(setup, "ALTER TABLE lake.main.other RENAME TO other_old", "rename other") &&
	     Exec(setup, "CREATE TABLE lake.main.other (id BIGINT, v VARCHAR)", "a new other") &&
	     Exec(setup, "INSERT INTO lake.main.other SELECT i, 'n' FROM range(900, 1000) r(i)", "the new other's rows");
	Check(ok, "rename: other renamed to other_old, a new other created with rows of its own");
	for (std::string name : {"other_old", "other"}) {
		auto at = "lake.main." + name + " AT (VERSION => " + std::to_string(s_before_rename) + ")";
		Table own_rows;
		std::string own_error;
		bool own_ok = Try(ref, "SELECT filename, file_row_number FROM " + at, own_rows, own_error);
		std::map<std::string, std::set<int64_t>> own_live;
		for (idx_t r = 0; own_ok && r < own_rows.RowCount(); r++) {
			auto file = own_rows.GetValue(0, r).ToString();
			if (file.find(".parquet") != std::string::npos) {
				own_live[Base(file)].insert(own_rows.GetValue(1, r).GetValue<int64_t>());
			}
		}
		RunHooked(client, "SELECT rowid FROM " + at, err, HookMode::ResolveOnly, name);
		auto label = "rename: " + name + " AT the version before the rename";
		if (g_hook.view) {
			auto got = Summarize(*g_hook.view).live;
			Check(own_ok && got == own_live, label + ": resolves to exactly the client's own read of it",
			      std::to_string(got.size()) + " files vs " + std::to_string(own_live.size()) +
			          (own_ok ? "" : ", own read: " + FirstLine(own_error)));
		} else if (!g_hook.error.empty()) {
			Check(g_hook.error.find("QoD: search_snapshot_unavailable") != std::string::npos,
			      label + ": refused with search_snapshot_unavailable", FirstLine(g_hook.error));
		} else {
			Check(!own_ok && !err.empty(), label + ": the read itself does not bind, so nothing resolves",
			      err + (own_ok ? ", but the superuser's read binds" : ""));
		}
	}

	if (kind == "postgres") {
		// leave nothing on the part 2 Postgres
		Exec(setup, "DETACH lake", "detach lake");
		Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach admin");
		Exec(setup, "CALL postgres_execute('pgadmin', 'DROP DATABASE IF EXISTS qod_t6_snapshot WITH (FORCE)', "
		            "use_transaction => false)",
		     "drop the scenario database");
	}
}

// ---- tenant: no statement reaches a hook or the decider -----------------------------------------

struct FakeQuackSession : public ClientContextState {};
int64_t now_ms = 1761200000000;
std::vector<std::string> g_descriptions;

qod::DecideResult Recorder(const qod::DecideCall &call) {
	if (call.kind == "plan") {
		g_descriptions.push_back(call.description);
	}
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	return result;
}

void RunTenant(const std::filesystem::path &dir, int &code) {
	g_kind = "duckdb tenant";
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir / "data");
	DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, Recorder);
	RegisterHook(*db.instance); // after qod-node's: it runs once the verdict admitted the statement
	qod::RegisterSearchFunctions(*db.instance);
	Connection setup(db);
	code = AttachLake(setup, "duckdb", dir, "", "");
	if (code != 0) {
		return;
	}
	Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR)", "create");
	Exec(setup, "INSERT INTO lake.main.pages SELECT i, 't' FROM range(0, 1000) r(i)", "file");
	Exec(setup, "DELETE FROM lake.main.pages WHERE id BETWEEN 100 AND 200", "delete file");
	Exec(setup, "INSERT INTO lake.main.pages VALUES (5000, 'i')", "inlined");

	// The functions the search module registers are exactly the two searches and the bundle builder: nothing
	// exposes the snapshot source, and no drain probe is left (ruling 14).
	Table functions;
	std::string ferr;
	bool fok = Try(setup, "SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_name ILIKE 'qod%' ORDER BY 1",
	               functions, ferr);
	std::set<std::string> qod_functions;
	for (idx_t r = 0; fok && r < functions.RowCount(); r++) {
		qod_functions.insert(functions.GetValue(0, r).ToString());
	}
	std::set<std::string> search_functions;
	for (auto &f : qod_functions) {
		if (f.rfind("qod_search", 0) == 0) {
			search_functions.insert(f);
		}
	}
	Check(fok && search_functions == std::set<std::string> {"qod_search", "qod_search_build_segment",
	                                                                 "qod_search_matches"},
	      "the search module registers exactly qod_search, qod_search_matches and the builder: no SQL function "
	      "exposes the snapshot source",
	      Join(search_functions));

	Connection client(db);
	WrapClientFileSystem(client);
	client.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
	qod_test::MintSpec spec;
	spec.principal_id = "u-1";
	spec.exp = now_ms + 60000;
	spec.superuser = false;
	auto installed = client.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		Check(false, "tenant prelude", FirstLine(installed->GetError()));
		return;
	}
	g_descriptions.clear();
	std::string err;
	RunHooked(client, kRead, err);
	Check(g_hook.view.has_value() && err.empty(), "a tenant's read: the snapshot source resolves in its post_bind",
	      FirstLine(g_hook.error + err));
	bool any_call = false;
	for (auto &d : g_descriptions) {
		for (auto needle : {"ducklake_current_snapshot", "ducklake_list_files", "parquet_scan", "ducklake_scan"}) {
			any_call = any_call || d.find(needle) != std::string::npos;
		}
	}
	Check(!g_descriptions.empty() && !any_call,
	      "a tenant's read: the decider saw the outer statement only, none of the ruled calls",
	      std::to_string(g_descriptions.size()) + " plan descriptions");
	if (g_hook.view) {
		std::string why;
		Check(ReadsOnlyTarget(*g_hook.view, "pages", why), "a tenant's read: the calls name only the target", why);
		Check(!g_hook.opens.empty() && OpensOnlyTarget(*g_hook.view, why),
		      "a tenant's read: every file opened is a listed file of the target", why);
		Check(g_hook.view->delete_files.size() == 1 && g_hook.positions.size() == 1,
		      "a tenant's read: the delete file and its positions are read", std::to_string(g_hook.positions.size()));
	}
	// A client statement naming the same calls is an ordinary statement: described to the decider with
	// the functions it calls, which the classification makes superuser-only (the real decider refuses
	// them, NestedMetadataDecisionSpec).
	for (auto &text : std::vector<std::string> {"SELECT * FROM ducklake_current_snapshot('lake')",
	                                            "SELECT * FROM ducklake_list_files('lake', 'pages', schema => 'main', "
	                                            "snapshot_version => 1)"}) {
		auto fn = text.find("ducklake_current_snapshot") != std::string::npos ? "ducklake_current_snapshot"
		                                                                      : "ducklake_list_files";
		Exec(client, qod_test::Prelude(qod_test::MintWith(spec)), "prelude");
		g_descriptions.clear();
		client.Query(text);
		bool described = false;
		for (auto &d : g_descriptions) {
			described = described || d.find(fn) != std::string::npos;
		}
		Check(described && qod::IsSuperuserFunction(fn),
		      std::string("a client statement calling ") + fn + " is described with it, a superuser-only function");
	}
}

// ---- E: an encrypted lake through the scan-only route (Task 16 Step 2, owner ruling 2026-11-14) -------
// A DuckLake attached with ENCRYPTED (DuckDB-file catalog, httpfs for OpenSSL): pages of 20,000 rows in 2
// files, one bundle per file built on the system principal's connection from the rows of the target's own
// scan before any delete (never read_parquet, which has no key), then a 2,001-row delete, an inlined delete,
// a third (unindexed) file and three inlined rows. No parquet_scan of a data or delete file anywhere: the
// liveness comes from the target's own DuckLake scan (DuckLake decrypts with its own per-file keys), the
// fallback rows from the same scan limited by filename, and PRAGMA add_parquet_key is never used. The bundles
// themselves are plaintext here: sealing a bundle under the HMAC subkey (spec 6.2) is Phase 2, and a
// plaintext bundle of an encrypted lake must never be written outside a test.

std::string g_e_root;

//! The case's decider: allows every plan and answers each search call as the manager's DecideRoute does (the
//! table population). The index record comes from the stand-in source (g_e_indexes).
qod_test::TestIndexes g_e_indexes;

qod::DecideResult ERecorder(const qod::DecideCall &call) {
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	if (call.kind == "plan") {
		auto described = qod::ParseJson(call.description);
		if (auto searches = described.GetOptional("searches", qod::JsonValue::Kind::ARRAY)) {
			for (auto &s : searches->array) {
				qod::SearchEntry entry;
				entry.call = static_cast<uint64_t>(s.Get("call", qod::JsonValue::Kind::INTEGER).integer);
				entry.population = "table";
				result.verdict.search.push_back(entry);
			}
		}
	}
	return result;
}

struct EHit {
	int64_t rowid;
	float score;
};

std::string ShowHits(const std::vector<EHit> &hits) {
	std::ostringstream out;
	for (size_t i = 0; i < hits.size() && i < 4; i++) {
		out << (i ? " " : "") << hits[i].rowid << "@" << hits[i].score;
	}
	return "[" + out.str() + (hits.size() > 4 ? " ..." : "") + "] (" + std::to_string(hits.size()) + ")";
}

std::string TakeError(char *err) {
	std::string text = err ? err : "";
	qs_string_free(err);
	return text;
}

//! The engine test's reference (search_engine_test.cpp Reference): a fresh single segment of the live rows
//! the superuser's `ref` reads, searched with the TABLE population and every row allowed.
std::vector<EHit> EReference(Connection &ref, const std::string &query, uint32_t k, std::string &error) {
	Table rows;
	if (!Try(ref, "SELECT rowid, body FROM lake.main.pages ORDER BY rowid", rows, error)) {
		return {};
	}
	qod_test::ReferenceSource ref_source("/qod-reference/lake.main.pages", {"body"});
	auto &source = ref_source.source;
	char *err = nullptr;
	auto builder = qs_builder_new(&source, nullptr, UINT64_MAX, &err);
	if (!builder) {
		error = TakeError(err);
		return {};
	}
	for (idx_t i = 0; i < rows.RowCount(); i++) {
		auto text = rows.GetValue(1, i).IsNull() ? std::string() : rows.GetValue(1, i).GetValue<std::string>();
		QsText cell = text.empty() ? QsText {nullptr, 0} : QsText {text.data(), text.size()};
		if (qs_builder_add(builder, i, rows.GetValue(0, i).GetValue<int64_t>(), -1, &cell, 1, &err) != 0) {
			error = TakeError(err);
			qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
			return {};
		}
	}
	uint8_t *bytes = nullptr;
	size_t len = 0;
	uint64_t docs = 0;
	if (qs_builder_finish(builder, &bytes, &len, &docs, &err) != 0) {
		error = TakeError(err);
		return {};
	}
	auto segment = qs_segment_open(bytes, len, &source, &err);
	qs_bytes_free(bytes, len);
	if (!segment) {
		error = TakeError(err);
		return {};
	}
	size_t words = (rows.RowCount() + 63) / 64;
	std::vector<uint64_t> live(words, ~uint64_t(0));
	std::vector<uint64_t> allowed(words, ~uint64_t(0));
	const QsSegment *segments[] = {segment};
	const uint64_t *lives[] = {live.data()};
	const uint64_t *alloweds[] = {allowed.data()};
	size_t lens[] = {words};
	std::vector<QsHit> out(k);
	size_t n = 0;
	QsSearchArgs args {};
	args.segments = segments;
	args.n_segments = 1;
	args.live = lives;
	args.live_words = lens;
	args.allowed = alloweds;
	args.allowed_words = lens;
	args.population = QS_POPULATION_TABLE;
	args.query = query.c_str();
	args.fields = ref_source.fields.data();
	args.n_fields = ref_source.fields.size();
	args.k1 = 1.2f;
	args.b = 0.75f;
	args.k = k;
	args.path = QS_PATH_EXHAUSTIVE;
	args.memory_budget = UINT64_MAX;
	auto rc = qs_search(&args, out.data(), out.size(), &n, &err);
	qs_segment_free(segment);
	if (rc != 0) {
		error = TakeError(err);
		return {};
	}
	std::vector<EHit> hits;
	for (size_t i = 0; i < n; i++) {
		hits.push_back(EHit {out[i].rowid, out[i].score});
	}
	return hits;
}

//! Check, plus a "== E" line (what the brief's grep shows).
//! `what` starts with the step's number ("2 ...").
void ECheck(bool ok, const std::string &what, const std::string &detail = "") {
	Check(ok, what, detail);
	Say("== E" + what.substr(0, what.find(' ')) + (ok ? " ok   " : " FAIL ") + what.substr(what.find(' ') + 1) +
	    (ok || detail.empty() ? "" : ": " + FirstLine(detail)));
}

void RunEncrypted(const std::filesystem::path &dir_p, int &code) {
	g_kind = "E encrypted";
	std::filesystem::remove_all(dir_p);
	std::filesystem::create_directories(dir_p / "data");
	std::filesystem::create_directories(dir_p / "bundles");
	auto dir = std::filesystem::canonical(dir_p);
	auto data = (dir / "data").string() + "/";
	g_e_root = (dir / "bundles").string() + "/";

	DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, ERecorder);
	RegisterHook(*db.instance);
	qod::RegisterSearchFunctions(*db.instance);
	Connection setup(db);
	// httpfs: DuckDB's OpenSSL module; without it an encrypted database refuses writes (mbedtls is read-only).
	for (std::string ext : {"ducklake", "parquet", "httpfs"}) {
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("== E: " + ext + " unavailable, skipped");
			code = 77;
			return;
		}
	}
	if (!Exec(setup, "ATTACH " + Quote("ducklake:" + (dir / "enc.ducklake").string()) + " AS lake (DATA_PATH " +
	                     Quote(data) + ", DATA_INLINING_ROW_LIMIT 10, ENCRYPTED)",
	          "attach the encrypted lake")) {
		code = 1;
		return;
	}
	g_meta = "__ducklake_metadata_lake";
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	auto insert = [&](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.pages SELECT i, 't' || i, " + body + " FROM range(" + std::to_string(from) + ", " +
		       std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR)", "create") &&
	          Exec(setup, insert(0, 10000), "file 1") && Exec(setup, insert(10000, 20000), "file 2");
	if (!ok) {
		code = 1;
		return;
	}
	Connection ref(db); // untagged: reads as the superuser does
	Connection sysc(db);
	{
		sysc.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
		qod_test::MintSpec spec;
		spec.principal_id = "system";
		spec.exp = now_ms + 60000;
		spec.system = true;
		auto installed = sysc.Query(qod_test::Prelude(qod_test::MintWith(spec)));
		if (installed->HasError()) {
			ECheck(false, "0 the system principal's prelude", installed->GetError());
			code = 1;
			return;
		}
	}

	// E0: the lake is encrypted, and a data file cannot be read without its key.
	auto encrypted = Must(ref, "SELECT value FROM " + g_meta + ".ducklake_metadata WHERE key = 'encrypted'");
	auto listed = Must(ref, "SELECT data_file, data_file_size_bytes, data_file_footer_size, "
	                        "data_file_encryption_key IS NOT NULL FROM ducklake_list_files('lake', 'pages')");
	bool keyed = listed->RowCount() == 2;
	for (idx_t r = 0; r < listed->RowCount(); r++) {
		keyed = keyed && listed->GetValue(3, r).GetValue<bool>();
	}
	ECheck(encrypted->RowCount() == 1 && encrypted->GetValue(0, 0).ToString() == "true" && keyed,
	       "0 the lake is ENCRYPTED: ducklake_metadata.encrypted = true, 2 data files, each with its own key");
	{
		Table t;
		std::string error;
		auto read = Try(ref, "SELECT count(*) FROM read_parquet(" + Quote(listed->GetValue(0, 0).ToString()) + ")", t, error);
		ECheck(!read, "0 read_parquet of a data file without its key fails (the route below never uses it)",
		       read ? "it read " + t.GetValue(0, 0).ToString() : "");
	}

	// The stand-in record of pages (body), its bundles under g_e_root.
	{
		std::string error;
		g_e_indexes.Clear();
		g_e_indexes.Install();
		ECheck(g_e_indexes.Add(ref, "lake", "pages", {"body"}, g_e_root, error), "0 the index record of pages", error);
	}
	// M8 (change 64): qod_search_build_segment refuses a table of an encrypted lake, whoever calls (here the system
	// principal), before any row is read: no file is opened or written under the bundle root.
	{
		WrapClientFileSystem(sysc);
		auto path = listed->GetValue(0, 0).ToString();
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT, body FROM "
		           "lake.main.pages WHERE filename = " +
		           Quote(path) + " ORDER BY file_row_number), 'lake.main.pages', " + Quote(path) + ", " +
		           listed->GetValue(1, 0).ToString() + "::UBIGINT, " + listed->GetValue(2, 0).ToString() + "::UBIGINT)";
		StartCounting();
		Table out;
		std::string error;
		auto ran = Try(sysc, sql, out, error);
		auto opens = StopCounting();
		bool under_root = false;
		for (auto &o : opens) {
			under_root = under_root || o.first.rfind(g_e_root, 0) == 0;
		}
		bool empty = std::filesystem::is_empty(g_e_root);
		ECheck(!ran && error.find("QoD: search_encrypted_unsupported: lake.main.pages is in an encrypted DuckLake") !=
		                   std::string::npos &&
		           !under_root && empty,
		       "1 M8 qod_search_build_segment on an ENCRYPTED lake, as the system principal: search_encrypted_unsupported, "
		       "nothing opened or written under the bundle root",
		       ran ? "it ran" : FirstLine(error) + (under_root ? " (opened under the root)" : "") +
		                            (empty ? "" : " (files under the root)"));
	}
	// The bundles: one per file, from the target's own scan, before any delete (every position live), built by
	// the test through the library itself, since no node function writes a plaintext bundle of an encrypted lake
	// (sealing is Phase 2): the reads below need bundles to exist.
	std::set<std::string> built;
	auto record = *g_e_indexes.Find("lake", "pages");
	for (idx_t r = 0; r < listed->RowCount(); r++) {
		qod::search::DataFileRef file;
		file.path = listed->GetValue(0, r).ToString();
		file.bundle_key = qod::Hex(qod::Sha256(file.path));
		file.file_size = listed->GetValue(1, r).GetValue<uint64_t>();
		file.footer_size = listed->GetValue(2, r).GetValue<uint64_t>();
		auto rows = Must(ref, "SELECT rowid, body FROM lake.main.pages WHERE filename = " + Quote(file.path) +
		                          " ORDER BY file_row_number");
		std::vector<QsField> fields;
		auto source = qod::search::BundleSource(record, file, fields);
		source.row_id_start = -1;
		char *err = nullptr;
		auto builder = qs_builder_new(&source, nullptr, UINT64_MAX, &err);
		bool ok_build = builder != nullptr;
		for (idx_t i = 0; ok_build && i < rows->RowCount(); i++) {
			auto text = rows->GetValue(1, i).IsNull() ? std::string() : rows->GetValue(1, i).GetValue<std::string>();
			QsText cell = text.empty() ? QsText {nullptr, 0} : QsText {text.data(), text.size()};
			ok_build = qs_builder_add(builder, i, rows->GetValue(0, i).GetValue<int64_t>(), -1, &cell, 1, &err) == 0;
		}
		uint8_t *bytes = nullptr;
		size_t len = 0;
		uint64_t docs = 0;
		if (builder) {
			ok_build = qs_builder_finish(builder, &bytes, &len, &docs, &err) == 0 && ok_build;
		}
		if (ok_build) {
			qod::search::WriteBundle(*ref.context,
			                         qod::search::SegmentPath(record.bundle_root, record.index_id, file.bundle_key), bytes,
			                         len);
			qs_bytes_free(bytes, len);
		}
		ECheck(ok_build && docs == 10000,
		       "1 bundle of data file " + std::to_string(r + 1) + " built by the test from the target's own scan "
		       "(10,000 docs)",
		       ok_build ? std::to_string(docs) + " docs" : TakeError(err));
		if (ok_build) {
			built.insert(file.path);
			Say("   E bundle " + std::to_string(r + 1) + ": " + std::to_string(docs) + " docs, " + std::to_string(len) +
			    " bytes, plaintext, written by the test (sealing is Phase 2)");
		}
	}

	// The changes after the build.
	ok = Exec(setup, "DELETE FROM lake.main.pages WHERE id BETWEEN 12000 AND 14000", "2,001-row delete") &&
	     Exec(setup, "DELETE FROM lake.main.pages WHERE id = 5", "inlined delete") &&
	     Exec(setup, insert(20000, 21000), "file 3 (unindexed)") &&
	     Exec(setup, "INSERT INTO lake.main.pages VALUES (90001, 'a', 'w1 w10 w1'), (90002, 'b', 'w10'), (90003, 'c', 'w0')",
	          "three inlined rows");
	if (!ok) {
		code = 1;
		return;
	}
	// Not ReadTruth: it reads the delete file with read_parquet, which an encrypted file refuses.
	Truth live_truth;
	live_truth.s = Head(ref);
	ReadLiveTruth(ref, live_truth);
	int64_t superuser_live = Int(ref, "SELECT count(*) FROM lake.main.pages");
	auto files_now = Must(ref, "SELECT data_file, delete_file, delete_file_encryption_key IS NOT NULL FROM "
	                           "ducklake_list_files('lake', 'pages')");
	std::set<std::string> listed_paths;
	std::string unindexed;
	bool delete_keyed = false;
	idx_t delete_files = 0;
	for (idx_t r = 0; r < files_now->RowCount(); r++) {
		auto path = files_now->GetValue(0, r).ToString();
		listed_paths.insert(path);
		if (!built.count(path)) {
			unindexed = path;
		}
		if (!files_now->GetValue(1, r).IsNull()) {
			listed_paths.insert(files_now->GetValue(1, r).ToString());
			delete_files++;
			delete_keyed = delete_keyed || files_now->GetValue(2, r).GetValue<bool>();
		}
	}
	ECheck(files_now->RowCount() == 3 && !unindexed.empty() && delete_files == 1 && delete_keyed,
	       "0 after the changes: 3 data files (one unindexed) and one encrypted delete file",
	       std::to_string(files_now->RowCount()) + " files, " + std::to_string(delete_files) + " delete files");

	Connection client(db);
	WrapClientFileSystem(client);
	client.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
	qod_test::MintSpec spec;
	spec.principal_id = "u-1";
	spec.exp = now_ms + 60000;
	auto installed = client.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		ECheck(false, "0 the tenant prelude", installed->GetError());
		code = 1;
		return;
	}

	// E2: ResolveSnapshotView succeeds and its liveness is the superuser's live rows at S.
	std::string err;
	RunHooked(client, kRead, err, HookMode::ResolveOnly);
	if (g_hook.view) {
		auto &v = *g_hook.view;
		auto g = Summarize(v);
		bool keys_known = !v.files.empty();
		for (auto &f : v.files) {
			keys_known = keys_known && !f.encryption_key.empty();
		}
		bool parquet_scan = false;
		for (auto &r : v.reads) {
			parquet_scan = parquet_scan || r.rfind("parquet_scan(", 0) == 0;
		}
		std::string why;
		ECheck(err.empty() && v.snapshot_id == live_truth.s, "2 ResolveSnapshotView succeeds at S",
		       err + " S " + std::to_string(v.snapshot_id) + " vs " + std::to_string(live_truth.s));
		ECheck(g.live == live_truth.live, "2 its liveness equals the superuser's live rows at S, per file",
		       std::to_string(LiveCount(v)) + " live in files vs " + std::to_string(live_truth.live.size()) + " files");
		ECheck(keys_known, "2 every listed data file carries its DuckLake key from the scan's bind data");
		ECheck(!parquet_scan && ReadsOnlyTarget(v, "pages", why), "2 no parquet_scan among its reads; every read names "
		       "the target", why + " " + Join(v.reads));
		ECheck(OpensOnlyTarget(v, why), "2 every file it opened is a listed file of the target", why);
		int64_t inlined = 0;
		for (auto &t : v.inlined_tables) {
			inlined += t.live_rows;
		}
		ECheck(LiveCount(v) + inlined == superuser_live, "2 live rows in files plus inlined rows equal the superuser's count",
		       std::to_string(LiveCount(v)) + " + " + std::to_string(inlined) + " vs " + std::to_string(superuser_live));
	} else {
		ECheck(false, "2 ResolveSnapshotView succeeds", FirstLine(g_hook.error + " " + err));
	}

	// E3: the unindexed file's live rows through ScanTargetRowsWithColumn (Task 11's route), filename-limited.
	RunHooked(client, kRead, err, HookMode::ScanColumn, "pages", unindexed);
	{
		auto want = Must(ref, "SELECT file_row_number, rowid, body FROM lake.main.pages WHERE filename = " + Quote(unindexed) +
		                          " ORDER BY file_row_number");
		std::set<std::string> got_rows, want_rows;
		bool one_file = true;
		for (auto &row : g_hook.text_rows) {
			one_file = one_file && row.filename == unindexed;
			got_rows.insert(std::to_string(row.file_row_number) + "|" + std::to_string(row.rowid) + "|" + row.text);
		}
		for (idx_t r = 0; r < want->RowCount(); r++) {
			want_rows.insert(want->GetValue(0, r).ToString() + "|" + want->GetValue(1, r).ToString() + "|" +
			                 want->GetValue(2, r).ToString());
		}
		bool opens_listed = true;
		for (auto &o : g_hook.opens) {
			opens_listed = opens_listed && listed_paths.count(o.first);
		}
		ECheck(g_hook.error.empty() && one_file && got_rows == want_rows && want_rows.size() == 1000,
		       "3 ScanTargetRowsWithColumn of the unindexed file returns its 1,000 live rows (position, rowid, body)",
		       FirstLine(g_hook.error) + " got " + std::to_string(got_rows.size()) + " want " +
		           std::to_string(want_rows.size()));
		ECheck(opens_listed, "3 every file it opened is a listed data or delete file", Join([&] {
			       std::vector<std::string> v;
			       for (auto &o : g_hook.opens) {
				       v.push_back(Base(o.first));
			       }
			       return v;
		       }()));
	}

	// E4: qod_search over the table equals the reference's top 10; every open is a bundle or a listed file.
	// The fallback's own window: the counts at its start and end (the statement's counting runs on).
	std::map<std::string, idx_t> fallback_start, fallback_opens;
	qod::search::FallbackScanObserverForTest([&](bool on) {
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		if (on) {
			fallback_start = g_fs_opens;
		} else {
			fallback_opens.clear();
			for (auto &o : g_fs_opens) {
				auto was = fallback_start.count(o.first) ? fallback_start.at(o.first) : 0;
				if (o.second > was) {
					fallback_opens[o.first] = o.second - was;
				}
			}
		}
	});
	for (std::string query : {"w1 w10", "w0"}) {
		qod::search::ClearLivenessCacheForTest();
		fallback_opens.clear();
		StartCounting();
		Table rows;
		std::string error;
		auto ran = Try(client,
		               "SELECT * FROM qod_search((SELECT rowid FROM lake.main.pages), 'lake.main.pages', ['body'], " +
		                   Quote(query) + ")",
		               rows, error);
		auto all_opens = StopCounting();
		std::string rerr;
		auto want = EReference(ref, query, 10, rerr);
		std::vector<EHit> got;
		for (idx_t r = 0; ran && r < rows.RowCount(); r++) {
			got.push_back(EHit {rows.GetValue(0, r).GetValue<int64_t>(), rows.GetValue(1, r).GetValue<float>()});
		}
		bool same = rerr.empty() && got.size() == want.size() && got.size() == 10;
		for (size_t i = 0; same && i < got.size(); i++) {
			same = got[i].rowid == want[i].rowid && got[i].score == want[i].score;
		}
		ECheck(ran && same, "4 qod_search '" + query + "' returns the reference's top 10 (rowids, order, bit-identical "
		                    "scores)",
		       ran ? (rerr.empty() ? "got " + ShowHits(got) + ", want " + ShowHits(want) : rerr) : error);
		auto &opens = all_opens;
		idx_t bundle_opens = 0, data_opens = 0;
		std::vector<std::string> stray;
		for (auto &o : opens) {
			if (o.first.rfind(g_e_root, 0) == 0) {
				bundle_opens += o.second;
			} else if (listed_paths.count(o.first)) {
				data_opens += o.second;
			} else {
				stray.push_back(o.first);
			}
		}
		bool unindexed_in_fallback = fallback_opens.count(unindexed) > 0;
		// The first search opens the bundles and probes the unindexed file's; the second finds the bundles in the
		// open-segment cache and the probe in the missing-bundle cache (Task 12, D8), so it opens no bundle path.
		ECheck(stray.empty() && (query == "w1 w10" ? bundle_opens > 0 : bundle_opens == 0),
		       "4 every file the search opened is a bundle or a listed data or delete file (no other read path); the "
		       "second search, every bundle cached and the missing one remembered, opens no bundle path",
		       Join(stray) + " bundle opens " + std::to_string(bundle_opens));
		ECheck(unindexed_in_fallback, "4 the unindexed file was opened by the fallback's DuckLake scan");
		Say("   E4 '" + query + "': opens " + std::to_string(bundle_opens) + " of bundles, " + std::to_string(data_opens) +
		    " of data or delete files (" + std::to_string([&] {
			    idx_t n = 0;
			    for (auto &o : fallback_opens) {
				    n += listed_paths.count(o.first) ? o.second : 0;
			    }
			    return n;
		    }()) +
		    " inside the fallback's scan, the rest in the liveness scan and the input's own scan, both DuckLake scans of the target)");
	}
	qod::search::FallbackScanObserverForTest(nullptr);
	Say("== E note: the bundles are plaintext in this probe; sealing under the HMAC subkey is Phase 2, and a "
	    "plaintext bundle of an encrypted lake must never be written outside a test");
	code = 0;
}

// ---- cost: ResolveSnapshotView and the target scan on a larger table -----------------------------
// Task 6's bench, extended by Task 10 (owner ruling 2026-11-14, point 3c): 10 files, 1% and 10% of the
// rows deleted (plus one inlined delete and two inlined rows, as Task 6's), median of 21 warm runs after
// one cold one, of
//   - miss: CachedSnapshotView with the cache cleared (ResolveSnapshotView, its target scan beside it);
//   - LiveDocs for every file (the wiring's own cost; num_docs = the file's record count);
//   - hit: CachedSnapshotView in the next statement, at the same S;
// each in an auto-commit statement of its own (SELECT rowid FROM pages LIMIT 0: the hook runs at
// post_bind, and nothing of the table is returned). Task 6's ScanTargetRows figures follow for 1% up to
// 10M rows.

double Median(std::vector<double> v) {
	v.erase(v.begin()); // the first run is cold
	std::sort(v.begin(), v.end());
	return v.empty() ? 0.0 : v[v.size() / 2];
}

std::string Ms(double ms) {
	std::ostringstream out;
	out.setf(std::ios::fixed);
	out.precision(ms < 1 ? 3 : 1);
	out << ms;
	return out.str();
}

void RunBenchAt(const std::filesystem::path &dir, int64_t rows, int percent) {
	g_kind = "bench";
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir / "data");
	DuckDB db(nullptr);
	RegisterHook(*db.instance);
	Connection setup(db);
	if (AttachLake(setup, "duckdb", dir, "", "") != 0) {
		return;
	}
	Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR)", "create");
	int files = 10;
	for (int i = 0; i < files; i++) {
		auto lo = rows / files * i, hi = rows / files * (i + 1);
		Exec(setup, "INSERT INTO lake.main.pages SELECT i, 'title ' || i FROM range(" + std::to_string(lo) + ", " +
		                std::to_string(hi) + ") r(i)",
		     "file");
	}
	Exec(setup, percent == 1 ? "DELETE FROM lake.main.pages WHERE id % 100 = 7" : "DELETE FROM lake.main.pages WHERE id % 10 = 7",
	     std::to_string(percent) + "% deletes");
	Exec(setup, "DELETE FROM lake.main.pages WHERE id = 3", "inlined delete");
	Exec(setup, "INSERT INTO lake.main.pages VALUES (-1, 'i'), (-2, 'j')", "inlined rows");
	g_record_counts.clear();
	auto counts = Must(setup, "SELECT path, record_count FROM " + g_meta + ".ducklake_data_file WHERE end_snapshot IS NULL");
	for (idx_t r = 0; r < counts->RowCount(); r++) {
		g_record_counts[Base(counts->GetValue(0, r).ToString())] = counts->GetValue(1, r).GetValue<int64_t>();
	}
	auto live = Int(setup, "SELECT count(*) FROM lake.main.pages");
	Connection client(db);
	std::string err;
	const std::string probe = "SELECT rowid FROM lake.main.pages LIMIT 0";
	std::vector<double> miss, scan, live_docs, miss_live, hit, hit_live;
	size_t listed = 0;
	bool all_hit = true, all_miss = true;
	for (int run = 0; run < 22; run++) {
		RunHooked(client, probe, err, HookMode::Liveness);
		if (!g_hook.error.empty()) {
			Say("   bench: " + FirstLine(g_hook.error));
			return;
		}
		all_miss = all_miss && !g_hook.hit;
		miss.push_back(g_hook.view_ms);
		scan.push_back(g_hook.target_scan_ms);
		live_docs.push_back(g_hook.live_docs_ms);
		miss_live.push_back(g_hook.view_ms + g_hook.live_docs_ms);
		listed = g_hook.files;
		RunHooked(client, probe, err, HookMode::Hit);
		if (!g_hook.error.empty()) {
			Say("   bench: " + FirstLine(g_hook.error));
			return;
		}
		all_hit = all_hit && g_hook.hit;
		hit.push_back(g_hook.view_ms);
		hit_live.push_back(g_hook.view_ms + g_hook.live_docs_ms);
	}
	Check(all_miss && all_hit, "bench: every cleared lookup missed and every following one hit");
	Say("== t10 bench rows=" + std::to_string(rows) + " deleted=" + std::to_string(percent) + "% files=" +
	    std::to_string(listed) + " live=" + std::to_string(live) + " (median of 21 warm, ms): miss " + Ms(Median(miss)) +
	    " (target scan " + Ms(Median(scan)) + "), LiveDocs " + Ms(Median(live_docs)) + ", miss+LiveDocs " +
	    Ms(Median(miss_live)) + ", hit " + Ms(Median(hit)) + ", hit+LiveDocs " + Ms(Median(hit_live)) + "; cold miss " +
	    Ms(miss[0]) + " (target scan " + Ms(scan[0]) + ")");
	if (percent != 1 || rows > 10000000) {
		return;
	}
	// Task 6's figures, as before.
	std::vector<double> resolve, scan_all, scan_one, inner_scan;
	std::string inlined;
	for (int run = 0; run < 4; run++) {
		RunHooked(client, kRead, err);
		if (!g_hook.view) {
			Say("   bench: " + FirstLine(g_hook.error));
			return;
		}
		resolve.push_back(g_hook.ms);
		inner_scan.push_back(g_hook.view->target_scan_ms);
		inlined = g_hook.view->inlined_tables.empty() ? "" : g_hook.view->inlined_tables.back().table_name;
		RunHooked(client, kRead, err, HookMode::ScanAll);
		scan_all.push_back(g_hook.ms);
		if (!inlined.empty()) {
			RunHooked(client, kRead, err, HookMode::ScanOne, "pages", inlined);
			scan_one.push_back(g_hook.ms);
		}
	}
	Say("== bench rows=" + std::to_string(rows) + " files=" + std::to_string(files) +
	    ": ResolveSnapshotView median " + std::to_string(Median(resolve)) + " ms (cold " + std::to_string(resolve[0]) +
	    ", of which its target scan for the liveness " + std::to_string(Median(inner_scan)) +
	    " ms); ScanTargetRows unfiltered (a TargetRow per row) " + std::to_string(Median(scan_all)) +
	    " ms; filename-filtered (inlined only) " + std::to_string(scan_one.empty() ? 0.0 : Median(scan_one)) + " ms");
}

void RunBench(const std::filesystem::path &dir, int64_t rows) {
	for (int percent : {1, 10}) {
		RunBenchAt(dir / ("deleted-" + std::to_string(percent)), rows, percent);
		std::error_code ignored;
		std::filesystem::remove_all(dir / ("deleted-" + std::to_string(percent)), ignored);
	}
}

//! The run's watchdog, on every platform (it was alarm(2) and SIGALRM, which MSVC lacks): a detached thread that
//! ends the process after `seconds` unless a later call replaced it.
std::atomic<int> g_watchdog {0};

void Watchdog(int seconds) {
	auto generation = ++g_watchdog;
	std::thread([generation, seconds] {
		std::this_thread::sleep_for(std::chrono::seconds(seconds));
		if (g_watchdog.load() == generation) {
			std::fprintf(stdout, "FAIL search_snapshot_test: no answer within %d s\n", seconds);
			std::fflush(stdout);
			std::_Exit(1);
		}
	}).detach();
}

} // namespace

//! HiddenPositions (word by word) against a bit-by-bit reading of the same bitset, and SetRange's bound
//! (review of Task 6, Minor C and E).
void RunBitsetUnits() {
	auto reference = [](const qod::search::FileLiveness &live, int64_t record_count) {
		std::vector<std::pair<int64_t, int64_t>> runs;
		int64_t start = -1;
		for (int64_t p = 0; p < record_count; p++) {
			bool hidden = !live.IsLive(p);
			if (hidden && start < 0) {
				start = p;
			} else if (!hidden && start >= 0) {
				runs.emplace_back(start, p);
				start = -1;
			}
		}
		if (start >= 0) {
			runs.emplace_back(start, record_count);
		}
		return runs;
	};
	qod::search::FileLiveness live;
	live.words = {~0ull, 0ull, 0x00000000ffffffffull, ~0ull, 0x8000000000000001ull, ~0ull, 0ull};
	bool same = true;
	std::string detail;
	for (int64_t rc : {0, 1, 63, 64, 65, 128, 150, 191, 192, 255, 256, 300, 320, 448, 449, 600}) {
		auto got = qod::search::HiddenPositions(live, rc);
		if (got != reference(live, rc)) {
			same = false;
			detail += " record_count " + std::to_string(rc);
		}
	}
	uint64_t x = 0x9e3779b97f4a7c15ull;
	for (int round = 0; round < 200; round++) {
		qod::search::FileLiveness r;
		r.words.resize(1 + round % 9);
		for (auto &w : r.words) {
			x ^= x << 13;
			x ^= x >> 7;
			x ^= x << 17;
			w = (x % 3 == 0) ? 0 : (x % 3 == 1) ? ~0ull : x;
		}
		auto rc = static_cast<int64_t>(x % (64 * r.words.size() + 100));
		if (qod::search::HiddenPositions(r, rc) != reference(r, rc)) {
			same = false;
			detail += " random round " + std::to_string(round);
		}
	}
	Check(same, "HiddenPositions word by word equals the bit-by-bit reading (full, empty, mixed and partial words, "
	            "record counts past the bitset)",
	      detail);
	auto refused = [](int64_t lo, int64_t hi) {
		qod::search::FileLiveness l;
		try {
			qod::search::SetLiveRangeForTest(l, lo, hi);
		} catch (std::exception &e) {
			return ErrorData(e).Message().find("QoD: search_snapshot_unavailable") != std::string::npos &&
			       l.words.empty();
		}
		return false;
	};
	qod::search::FileLiveness small;
	qod::search::SetLiveRangeForTest(small, 3, 70);
	Check(refused(qod::search::kMaxFilePosition, qod::search::kMaxFilePosition + 5) &&
	          refused(5, qod::search::kMaxFilePosition + 1) && refused(-1, 4) && small.IsLive(3) &&
	          small.IsLive(69) && !small.IsLive(70) && !small.IsLive(2),
	      "SetRange refuses a position outside [0, 2^40) before the bitset grows, and marks [3, 70) otherwise");
}

int main(int argc, char **argv) {
	auto base = std::filesystem::temp_directory_path() / ("qod-search-snapshot-" + std::to_string(::getpid()));
	if (argc > 1 && std::string(argv[1]) == "--bench") {
		// Task 10's liveness cost only (CTest search_snapshot_bench, label probe): skipped without a size.
		auto rows = std::getenv("QOD_P2_T6_BENCH_ROWS");
		if (!rows || !*rows) {
			Say("search_snapshot_test --bench: QOD_P2_T6_BENCH_ROWS is not set, skipped");
			return 77;
		}
		Watchdog(3500);
		RunBench(base / "bench", std::stoll(rows));
		std::error_code ignored;
		std::filesystem::remove_all(base, ignored);
		Say("checks: " + std::to_string(g_checks) + ", failed: " + std::to_string(g_failures));
		return g_failures == 0 ? 0 : 1;
	}
	Watchdog(600);
	RunBitsetUnits();
	auto dsn_env = std::getenv("QOD_P2_PG_DSN_FILE");
	std::string dsn_file = dsn_env ? dsn_env : "";
	int code = 0, skipped = 0;
	RunScenario("duckdb", base / "duckdb", "", code);
	skipped += code == 77;
	if (code == 77) {
		std::filesystem::remove_all(base);
		return 77;
	}
	if (!dsn_file.empty() && std::filesystem::exists(dsn_file)) {
		RunScenario("postgres", base / "postgres", dsn_file, code);
		skipped += code == 77;
	} else {
		Say("== postgres: QOD_P2_PG_DSN_FILE not set or missing: the Postgres catalog is skipped");
	}
	RunTenant(base / "tenant", code);
	RunEncrypted(base / "encrypted", code);
	if (code == 77) {
		Say("== E: skipped (httpfs or ducklake unavailable)");
	}
	auto bench = std::getenv("QOD_P2_T6_BENCH_ROWS");
	if (bench && *bench) {
		RunBench(base / "bench", std::stoll(bench));
	}
	std::error_code ignored;
	std::filesystem::remove_all(base, ignored);
	Say("checks: " + std::to_string(g_checks) + ", failed: " + std::to_string(g_failures) +
	    ", skipped kinds: " + std::to_string(skipped));
	return g_failures == 0 ? 0 : 1;
}
