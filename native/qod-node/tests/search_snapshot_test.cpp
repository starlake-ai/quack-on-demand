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
// calls; and the client's own writes refuse the search (search_uncommitted_writes).
// QOD_P2_T6_BENCH_ROWS=<n> also times ResolveSnapshotView and the target scan on a table of n rows.
// Exits 77 when ducklake (or, for the Postgres half, postgres) cannot load.

#include "authz.hpp"
#include "decide_client.hpp"
#include "crypto.hpp"
#include "function_classes.hpp"
#include "node_hooks.hpp"
#include "search_functions.hpp"
#include "search_snapshot.hpp"
#include "test_handles.hpp"

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
#include <chrono>
#include <csignal>
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
#include <unistd.h>
#include <vector>

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

enum class HookMode { Resolve, ScanAll, ScanOne };

struct HookState {
	bool armed = false;
	ClientContext *client = nullptr;
	std::string table = "pages";
	HookMode mode = HookMode::Resolve;
	std::string only_file;
	std::optional<View> view;
	std::map<std::string, std::vector<std::pair<int64_t, int64_t>>> positions; // by delete file path
	std::vector<qod::search::TargetRow> rows;
	std::string error;
	double ms = 0;                         // ResolveSnapshotView alone
	std::map<std::string, idx_t> opens;    // files the client's FileSystem opened during it
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
		if (g_hook.mode == HookMode::Resolve) {
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
	if (g_hook.mode != HookMode::Resolve) {
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
	return g_hook.view.has_value() || mode != HookMode::Resolve;
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
	Check(RefusedWith("search_snapshot_unavailable: AT (TIMESTAMP => ...) is not supported by search in Phase 0"),
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
			refused = ErrorData(e).Message().find("search_snapshot_unavailable") != std::string::npos;
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

	// the client's own writes (OWNER RULING 2026-11-14): any write to the lake refuses the search
	struct Write {
		std::string what, sql;
	};
	for (auto &w : std::vector<Write> {{"deleted a row", "DELETE FROM lake.main.pages WHERE id = 1"},
	                                   {"inserted an inlined row", "INSERT INTO lake.main.pages VALUES (99999, 'l', 'x')"},
	                                   {"inserted a file of rows",
	                                    "INSERT INTO lake.main.pages SELECT i, 'l', 'x' FROM range(50000, 50100) r(i)"},
	                                   {"wrote another table of the lake",
	                                    "INSERT INTO lake.main.other VALUES (7, 'w')"}}) {
		Exec(client, "BEGIN TRANSACTION", "begin");
		Exec(client, w.sql, w.what);
		RunHooked(client, kRead, err);
		Check(RefusedWith("search_uncommitted_writes: "), "a transaction that " + w.what + ": refused",
		      FirstLine(g_hook.error));
		Exec(client, "ROLLBACK", "rollback");
	}
	Exec(client, "BEGIN TRANSACTION", "begin");
	Exec(client, "INSERT INTO lake2.main.t VALUES (1)", "write lake2");
	RunHooked(client, kRead, err);
	Check(g_hook.view.has_value(), "a transaction that wrote another lake (lake2): resolves", FirstLine(g_hook.error));
	Exec(client, "ROLLBACK", "rollback");
	RunHooked(client, kRead, err);
	Check(g_hook.view.has_value(), "auto-commit after those transactions: resolves", FirstLine(g_hook.error));

	// the reads ran in the client's transaction: the client's own read still works afterwards
	auto count = client.Query("SELECT count(*) FROM lake.main.pages");
	Check(!count->HasError(), "the client's own read after the calls", count->HasError() ? FirstLine(count->GetError()) : "");

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

	// The functions the search module registers are exactly the drain probes: nothing exposes the
	// snapshot source.
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
	Check(fok && search_functions == std::set<std::string> {"qod_search_drain", "qod_search_drain_parallel",
	                                                                 "qod_search_drain_sink"},
	      "the search module registers exactly the drain probes: no SQL function exposes the snapshot source",
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

// ---- cost: ResolveSnapshotView and the target scan on a larger table -----------------------------

void RunBench(const std::filesystem::path &dir, int64_t rows) {
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
	Exec(setup, "DELETE FROM lake.main.pages WHERE id % 100 = 7", "1% deletes");
	Exec(setup, "DELETE FROM lake.main.pages WHERE id = 3", "inlined delete");
	Exec(setup, "INSERT INTO lake.main.pages VALUES (-1, 'i'), (-2, 'j')", "inlined rows");
	Connection client(db);
	std::string err;
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
	auto med = [](std::vector<double> v) {
		v.erase(v.begin()); // the first run is cold
		std::sort(v.begin(), v.end());
		return v.empty() ? 0.0 : v[v.size() / 2];
	};
	Say("== bench rows=" + std::to_string(rows) + " files=" + std::to_string(files) +
	    ": ResolveSnapshotView median " + std::to_string(med(resolve)) + " ms (cold " + std::to_string(resolve[0]) +
	    ", of which its target scan for the liveness " + std::to_string(med(inner_scan)) +
	    " ms); ScanTargetRows unfiltered (a TargetRow per row) " + std::to_string(med(scan_all)) + " ms; filename-filtered (inlined only) " +
	    std::to_string(scan_one.empty() ? 0.0 : med(scan_one)) + " ms");
}

void OnAlarm(int) {
	const char msg[] = "FAIL search_snapshot_test: no answer within 600 s\n";
	ssize_t ignored = ::write(1, msg, sizeof(msg) - 1);
	(void)ignored;
	_exit(1);
}

} // namespace

int main() {
	std::signal(SIGALRM, OnAlarm);
	alarm(600);
	auto base = std::filesystem::temp_directory_path() / ("qod-search-snapshot-" + std::to_string(::getpid()));
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
