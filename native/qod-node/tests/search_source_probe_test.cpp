// Search Phase 0 part 2, Task 1: option (b)'s open checks (spec 7.1 step 2). Prints, per mechanism
// M1 to M4 and per read, whether it ran, its error, and the values it read, on a DuckLake with a
// DuckDB-file catalog and on one with a Postgres catalog (QOD_P2_PG_DSN_FILE: a file holding the
// libpq connection string; never printed). Unix only, label probe.
//
// Scenario per catalog (DATA_INLINING_ROW_LIMIT 10, table pages of 20,000 rows in two files of 10,000,
// two inlined inserts, an inlined delete, a 2,001-row delete that writes a delete file):
//   client: BEGIN; SELECT count(*) FROM lake.main.pages;          -- fixes S
//   other connection: INSERT 5,000 rows; DELETE 1,000 rows; DELETE one inlined row (autocommit each):
//                     S+1, S+2 and S+3
//   other connection: CALL ducklake_merge_adjacent_files('lake'); CALL ducklake_expire_snapshots(
//                       'lake', older_than => now()); CALL ducklake_cleanup_old_files('lake', cleanup_all => true)
//   client: SELECT * FROM qod_probe_source((SELECT rowid FROM lake.main.pages), 'lake');   -- M1, M4
//   client: SELECT rowid FROM lake.main.pages;                                              -- M2
// Expected of a read "in the client's transaction": S (not S+2) from ducklake_current_snapshot and
// from max(snapshot_id); the delete files, inlined deletes and inlined rows visible at S, equal to a
// superuser's answer at S taken before the other connection's commits; and the data file still
// readable (a cleanup may have removed a merged-away file: reported).
//
// Mechanisms: M1 context.Query in the bind of the in-out function qod_probe_source(TABLE, VARCHAR);
// M2 the same call in a post_bind hook registered after qod-node's, on a plain read; M4 no statement:
// catalog table scans (TableCatalogEntry::GetScanFunction) and table functions bound and run from C++
// on the client's context, among them (fix round 1) M4a, DuckLake's ducklake_list_files, and M4b, the
// DuckLake table's own scan with its virtual columns. A second variant (<kind>+keep, M4 only) leaves
// out expiry and cleanup, so the delete file visible at S stays on disk. Each runs with three principals: bare (no qod-node hooks at all),
// superuser and tenant (qod-node's real hooks on a quack-tagged connection, the decider a recorder
// that allows everything). Tenant M1, M2 and M4 are the M3 rows. Every (catalog, principal,
// mechanism) runs in a forked child that builds the scenario from scratch; the mechanism has a 20 s
// watchdog (alarm), so a deadlock prints HUNG instead of hanging the probe.
//
// The tenant runs also execute each read as its own top-level statement (autocommit, after the
// transaction), to capture what qod-node describes and refuses for that text ("direct" lines).
// Captured plan descriptions: nested statements to <dir>/../nested-descriptions.jsonl, direct ones to
// <dir>/../direct-descriptions.jsonl (<dir> = QOD_SEARCH_PROBE_DIR); they hold no secret.
// Exits 77 when ducklake or postgres cannot load.
//   cmake --build <build> --target search_source_probe_test
//   QOD_P2_PG_DSN_FILE=<dsn file> QOD_SEARCH_PROBE_DIR=<data dir> ctest --test-dir <build> -R search_source_probe -V

#include "authz.hpp"
#include "decide_client.hpp"
#include "node_hooks.hpp"
#include "test_handles.hpp"

#include "duckdb.hpp"
#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_function_catalog_entry.hpp"
#include "duckdb/catalog/entry_lookup_info.hpp"
#include "duckdb/common/opener_file_system.hpp"
#include "duckdb/execution/execution_context.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/main/query_parameters.hpp"
#include "duckdb/parallel/thread_context.hpp"
#include "duckdb/parser/tableref/table_function_ref.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <algorithm>
#include <atomic>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

using namespace duckdb;

namespace {

// ---- output hygiene: the catalog password never reaches stdout -----------------------------------

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

std::string FirstLine(const std::string &text) {
	auto line = text.substr(0, text.find('\n'));
	return Redact(line.size() > 300 ? line.substr(0, 300) + "..." : line);
}

void Say(const std::string &line) {
	std::cout << Redact(line) << std::endl;
}

// ---- the 20 s watchdog -------------------------------------------------------------------------

char g_hung_line[512];

void OnAlarm(int) {
	auto n = std::strlen(g_hung_line);
	ssize_t ignored = ::write(1, g_hung_line, n);
	(void)ignored;
	_exit(3);
}

void ArmWatchdog(const std::string &label) {
	auto text = "== " + label + ": HUNG (no answer within 20 s; the child is stopped)\n";
	std::snprintf(g_hung_line, sizeof(g_hung_line), "%s", text.c_str());
	std::cout.flush();
	std::signal(SIGALRM, OnAlarm);
	alarm(20);
}

//! The outer statement's watchdog label while one runs: a nested read's watchdog replaces it (alarm is
//! process-wide), so DisarmWatchdog puts it back.
std::string g_outer_label;

void DisarmWatchdog() {
	alarm(0);
	if (!g_outer_label.empty()) {
		ArmWatchdog(g_outer_label);
	}
}

void ArmOuterWatchdog(const std::string &label) {
	g_outer_label = label;
	ArmWatchdog(label);
}

void DisarmOuterWatchdog() {
	g_outer_label.clear();
	alarm(0);
}

// ---- what a child runs -----------------------------------------------------------------------

enum class Principal { Bare, Superuser, Tenant };
enum class Mech { M1, M2, M4 };

const char *PrincipalName(Principal p) {
	return p == Principal::Bare ? "bare" : p == Principal::Superuser ? "superuser" : "tenant";
}

std::string MechLabel(Mech m, Principal p) {
	std::string base = m == Mech::M1 ? "M1" : m == Mech::M2 ? "M2" : "M4";
	return p == Principal::Tenant ? "M3/" + base : base;
}

//! One read the mechanisms run: its id, its form, the SQL text M1 / M2 send, the expected value.
struct Read {
	std::string id;
	std::string form;
	std::string sql;
	std::string expected;
};

struct ScenarioFacts {
	std::string kind;        // duckdb | postgres
	std::string alias = "lake";
	std::string meta;        // __ducklake_metadata_lake
	int64_t table_id = -1;
	int64_t s = -1;
	std::string inlined_data_table;
	std::string inlined_delete_table;
	std::string delete_file_path;
	std::string data_file_path;
	std::vector<Read> reads;
};

// Child globals (each child is its own process).
ScenarioFacts g_facts;
Principal g_principal = Principal::Bare;
Mech g_mech = Mech::M1;
ClientContext *g_client = nullptr;
bool g_m2_armed = false;
bool g_in_probe = false;
std::string g_phase = "setup";
std::string g_desc_dir;

// ---- the recorder decider (M3): records every plan description, answers allow ------------------

int64_t now_ms = 1761200000000;

struct Recorded {
	std::string phase;
	std::string kind;
	std::string description;
};
std::vector<Recorded> g_recorded;

void AppendLine(const std::string &file, const std::string &line) {
	int fd = ::open(file.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0600);
	if (fd < 0) {
		return;
	}
	auto text = line + "\n";
	ssize_t ignored = ::write(fd, text.data(), text.size());
	(void)ignored;
	::close(fd);
}

qod::DecideResult Recorder(const qod::DecideCall &call) {
	g_recorded.push_back(Recorded {g_phase, call.kind, call.description});
	if (call.kind == "plan" && !g_desc_dir.empty()) {
		if (g_phase.rfind("nested:", 0) == 0) {
			AppendLine(g_desc_dir + "/nested-descriptions.jsonl", call.description);
			AppendLine(g_desc_dir + "/nested-descriptions.index",
			           g_facts.kind + "\t" + MechLabel(g_mech, g_principal) + "\t" + g_phase);
		} else if (g_phase.rfind("direct:", 0) == 0) {
			AppendLine(g_desc_dir + "/direct-descriptions.jsonl", call.description);
			AppendLine(g_desc_dir + "/direct-descriptions.index", g_facts.kind + "\t" + g_phase);
		} else if (g_phase == "outer") {
			AppendLine(g_desc_dir + "/outer-descriptions.jsonl", call.description);
			AppendLine(g_desc_dir + "/outer-descriptions.index",
			           g_facts.kind + "\t" + MechLabel(g_mech, g_principal) + "\touter");
		}
	}
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	return result;
}

struct FakeQuackSession : public ClientContextState {};

void TagAsQuackSession(Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
}

// ---- a counting client FileSystem: the client's own, wrapped, so M4's Parquet opens are counted --

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

// ---- small helpers -----------------------------------------------------------------------------

std::string Str(const Identifier &id) {
	return id.GetIdentifierName();
}

std::string Str(const std::string &text) {
	return text;
}

template <class F>
std::string NameOf(const F &function) {
	return Str(function.GetName());
}

//! The first value of the first row, as a string; sets error on failure.
std::string FirstValue(QueryResult &result, std::string &error) {
	if (result.HasError()) {
		error = result.GetError();
		return "";
	}
	auto chunk = result.Fetch();
	if (!chunk || chunk->size() == 0) {
		return "<no row>";
	}
	auto value = chunk->GetValue(0, 0);
	return value.IsNull() ? "<null>" : value.ToString();
}

std::string RunValue(Connection &con, const std::string &sql, std::string &error) {
	auto result = con.Query(sql);
	return FirstValue(*result, error);
}

bool Exec(Connection &con, const std::string &sql, const std::string &what) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		Say("   setup " + what + ": ERROR " + FirstLine(result->GetError()));
		return false;
	}
	return true;
}

std::string Quote(const std::string &text) {
	std::string out = "'";
	for (auto c : text) {
		out += c == '\'' ? std::string("''") : std::string(1, c);
	}
	return out + "'";
}

// ---- the reads -----------------------------------------------------------------------------

//! The SQL text of every read for snapshot `s`: fixed text built from the facts the probe resolved.
std::vector<Read> BuildReads(const ScenarioFacts &f, int64_t s) {
	auto S = std::to_string(s);
	auto tid = std::to_string(f.table_id);
	auto md = f.meta;
	std::vector<Read> reads;
	reads.push_back({"current_snapshot", "sql", "SELECT * FROM ducklake_current_snapshot(" + Quote(f.alias) + ")", ""});
	reads.push_back({"max_snapshot", "sql", "SELECT CAST(max(snapshot_id) AS VARCHAR) FROM " + md + ".ducklake_snapshot", ""});
	reads.push_back({"delete_files", "sql",
	                 "SELECT coalesce(string_agg(delete_file_id || ':' || data_file_id || ':' || begin_snapshot, ',' "
	                 "ORDER BY delete_file_id), '') FROM " + md + ".ducklake_delete_file WHERE table_id = " + tid +
	                     " AND begin_snapshot <= " + S + " AND (end_snapshot IS NULL OR end_snapshot > " + S + ")",
	                 ""});
	auto idl_select = "SELECT coalesce(string_agg(file_id || ':' || row_id, ',' ORDER BY file_id, row_id), '') FROM ";
	auto idl_where = " WHERE begin_snapshot <= " + S;
	reads.push_back({"inlined_delete", "sql", idl_select + md + "." + f.inlined_delete_table + idl_where, ""});
	reads.push_back({"inlined_delete", "query_table",
	                 idl_select + std::string("query_table(") + Quote(md + "." + f.inlined_delete_table) + ")" + idl_where,
	                 ""});
	auto idt_select = "SELECT coalesce(string_agg(CAST(_ducklake_row_id AS VARCHAR), ',' ORDER BY _ducklake_row_id), "
	                  "'') FROM ";
	auto idt_where = " WHERE _ducklake_begin_snapshot <= " + S + " AND (_ducklake_end_snapshot IS NULL OR "
	                 "_ducklake_end_snapshot > " + S + ")";
	reads.push_back({"inlined_data", "sql", idt_select + md + "." + f.inlined_data_table + idt_where, ""});
	reads.push_back({"inlined_data", "query_table",
	                 idt_select + std::string("query_table(") + Quote(md + "." + f.inlined_data_table) + ")" + idt_where,
	                 ""});
	reads.push_back({"delete_positions", "sql",
	                 "SELECT 'count=' || count(*) || ' min=' || min(pos) || ' max=' || max(pos) FROM parquet_scan(" +
	                     Quote(f.delete_file_path) + ")",
	                 ""});
	reads.push_back({"data_file_rows", "sql",
	                 "SELECT 'count=' || count(*) || ' min=' || min(file_row_number) || ' max=' || "
	                 "max(file_row_number) FROM parquet_scan(" + Quote(f.data_file_path) + ", file_row_number => true)",
	                 ""});
	// Fix round 1 (review C1): DuckLake's own transaction, through its public table function (M4a) and
	// through the DuckLake table's own scan (M4b). The expected values are the same SQL, run by the
	// reference at S. Paths print by file name only.
	auto list_select = "SELECT coalesce(string_agg(regexp_extract(data_file, '[^/]*$') || '|' || "
	                   "coalesce(regexp_extract(delete_file, '[^/]*$'), '-'), ',' ORDER BY data_file), '') FROM ";
	reads.push_back({"list_files", "default", list_select + std::string("ducklake_list_files(") + Quote(f.alias) +
	                                              ", 'pages')",
	                 ""});
	reads.push_back({"list_files", "at_s",
	                 list_select + std::string("ducklake_list_files(") + Quote(f.alias) +
	                     ", 'pages', snapshot_version => " + S + ")",
	                 ""});
	reads.push_back({"table_scan", "rowid",
	                 "SELECT count(*) || ' rows; rowids >= 20000: ' || coalesce(string_agg(CAST(rowid AS VARCHAR), ',' "
	                 "ORDER BY rowid) FILTER (WHERE rowid >= 20000), '') FROM " + f.alias + ".main.pages",
	                 ""});
	return reads;
}

struct ReadResult {
	std::string read;
	std::string form;
	bool ran = false;
	bool hung = false;
	std::string error;
	std::string values;
	bool matches = false;
	bool hooks_saw_nested = false;
	std::string seen;
	std::string local_refusal;
};

void PrintRead(const std::string &label, const ReadResult &r, const std::string &expected) {
	std::ostringstream out;
	out << "== " << label << " " << g_facts.kind << " " << r.read << " " << r.form
	    << " principal=" << PrincipalName(g_principal) << ": ran=" << (r.ran ? 1 : 0) << " value=[" << r.values
	    << "] expected=[" << expected << "] match=" << (r.ran && r.matches ? "yes" : "no");
	if (g_principal != Principal::Bare) {
		out << " hooks_saw_nested=" << (r.hooks_saw_nested ? 1 : 0);
		if (!r.seen.empty()) {
			out << " seen=[" << r.seen << "]";
		}
		if (!r.local_refusal.empty()) {
			out << " local_refusal=[" << r.local_refusal << "]";
		}
	}
	if (!r.error.empty()) {
		out << " error=[" << FirstLine(r.error) << "]";
	}
	Say(out.str());
}

//! What the recorder saw since `since`: the names that matter in the descriptions recorded then.
std::string SeenSince(size_t since, bool &any) {
	std::set<std::string> names;
	any = false;
	for (auto i = since; i < g_recorded.size(); i++) {
		if (g_recorded[i].kind != "plan") {
			continue;
		}
		any = true;
		auto &d = g_recorded[i].description;
		for (auto needle : {"__ducklake_metadata_", "ducklake_current_snapshot", "query_table", "parquet_scan",
		                    "read_parquet", "ducklake_inlined_"}) {
			if (d.find(needle) != std::string::npos) {
				names.insert(needle);
			}
		}
	}
	std::string out;
	for (auto &n : names) {
		out += (out.empty() ? "" : ",") + n;
	}
	return out.empty() && any ? "a description without those names" : out;
}

// ---- M1 and M2: context.Query on the client's context ----------------------------------------

void RunSqlReads(ClientContext &context, const std::string &label) {
	int64_t s = g_facts.s;
	bool s_known = false;
	auto reads = g_facts.reads;
	for (size_t i = 0; i < reads.size(); i++) {
		auto &rd = reads[i];
		if (i == 1 && s_known) {
			// Rebuild the remaining texts at the S this mechanism read itself.
			auto again = BuildReads(g_facts, s);
			for (size_t j = 1; j < reads.size(); j++) {
				reads[j].sql = again[j].sql;
			}
		}
		ReadResult r;
		r.read = rd.id;
		r.form = rd.form;
		g_phase = "nested:" + rd.id + ":" + rd.form;
		auto before = g_recorded.size();
		ArmWatchdog(label + " " + g_facts.kind + " " + rd.id + " " + rd.form + " principal=" +
		            PrincipalName(g_principal));
		try {
			auto result = context.Query(rd.sql, QueryParameters());
			DisarmWatchdog();
			std::string error;
			r.values = FirstValue(*result, error);
			r.error = error;
			r.ran = error.empty();
		} catch (std::exception &e) {
			DisarmWatchdog();
			r.error = std::string("exception: ") + e.what();
		}
		g_phase = "probe";
		bool any = false;
		r.seen = SeenSince(before, any);
		if (r.error.find("QoD: ") != std::string::npos) {
			r.local_refusal = FirstLine(r.error);
		}
		r.hooks_saw_nested = any || !r.local_refusal.empty();
		r.matches = r.ran && r.values == rd.expected;
		if (i == 0 && r.ran) {
			try {
				s = std::stoll(r.values);
				s_known = true;
			} catch (std::exception &) {
			}
		}
		PrintRead(label, r, rd.expected);
	}
}

// ---- M4: no statement: scans and table functions driven from C++ -------------------------------

struct Rows {
	std::vector<std::string> names;
	std::vector<std::vector<Value>> rows;
	int Col(const std::string &name) const {
		for (size_t i = 0; i < names.size(); i++) {
			if (StringUtil::CIEquals(names[i], name)) {
				return static_cast<int>(i);
			}
		}
		return -1;
	}
};

template <class F>
Rows Drive(ClientContext &context, F &function, FunctionData *bind_data, const vector<LogicalType> &types,
           const std::vector<std::string> &names, vector<column_t> column_ids = {}) {
	Rows out;
	out.names = names;
	if (!function.function) {
		throw std::runtime_error("the function has no scan callback");
	}
	if (column_ids.empty()) {
		for (idx_t i = 0; i < types.size(); i++) {
			column_ids.push_back(i);
		}
	}
	vector<idx_t> projection_ids;
	TableFunctionInitInput init_input(bind_data, column_ids, projection_ids, nullptr);
	unique_ptr<GlobalTableFunctionState> global;
	if (function.init_global) {
		global = function.init_global(context, init_input);
	}
	ThreadContext thread(context);
	ExecutionContext exec(context, thread, nullptr);
	unique_ptr<LocalTableFunctionState> local;
	if (function.init_local) {
		local = function.init_local(exec, init_input, global.get());
	}
	DataChunk chunk;
	chunk.Initialize(Allocator::Get(context), types);
	TableFunctionInput input(bind_data, local.get(), global.get());
	for (idx_t guard = 0; guard < 100000; guard++) {
		chunk.Reset();
		function.function(context, input, chunk);
		if (chunk.size() == 0) {
			break;
		}
		for (idx_t row = 0; row < chunk.size(); row++) {
			std::vector<Value> values;
			for (idx_t col = 0; col < chunk.ColumnCount(); col++) {
				values.push_back(chunk.GetValue(col, row));
			}
			out.rows.push_back(std::move(values));
		}
	}
	return out;
}

LogicalGet *FindGet(LogicalOperator &op) {
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		return &op.Cast<LogicalGet>();
	}
	for (auto &child : op.children) {
		if (auto found = FindGet(*child)) {
			return found;
		}
	}
	return nullptr;
}

Rows DriveGet(ClientContext &context, LogicalGet &get) {
	std::vector<std::string> names;
	for (auto &n : get.names) {
		names.push_back(Str(n));
	}
	return Drive(context, get.function, get.bind_data.get(), get.returned_types, names);
}

//! A catalog table of the metadata catalog, by GetEntry and GetScanFunction.
Rows ScanCatalogTable(ClientContext &context, const std::string &catalog, const std::string &table,
                      std::string &how) {
	auto &cat = Catalog::GetCatalog(context, Identifier(catalog));
	auto schema = cat.GetDefaultSchema();
	Identifier schema_name = schema ? *schema : Identifier("main");
	auto &entry =
	    Catalog::GetEntry<TableCatalogEntry>(context, QualifiedName(Identifier(catalog), schema_name, Identifier(table)));
	unique_ptr<FunctionData> bind_data;
	auto function = entry.GetScanFunction(context, bind_data);
	vector<LogicalType> types;
	std::vector<std::string> names;
	for (auto &col : entry.GetColumns().Logical()) {
		types.push_back(col.Type());
		names.push_back(Str(col.Name()));
	}
	how = "GetScanFunction " + NameOf(function) + " on " + Str(schema_name) + "." + table;
	return Drive(context, function, bind_data.get(), types, names);
}

//! The DuckLake table's own scan (the route a plain SELECT of the table takes), bound from C++ with no
//! statement: `id` plus every virtual column the scan offers (rowid, and filename / file_row_number when
//! DuckLake offers them). `offered` lists the virtual columns by name.
Rows ScanLakeTable(ClientContext &context, const std::string &catalog, const std::string &table, std::string &how,
                   std::string &offered) {
	QualifiedName qualified(Identifier(catalog), Identifier("main"), Identifier(table));
	auto &entry = Catalog::GetEntry<TableCatalogEntry>(context, qualified);
	unique_ptr<FunctionData> bind_data;
	// DuckLake's entry refuses the two-argument overload ("called without entry lookup info"): the
	// binder passes the lookup it resolved the table with (and its AT clause, none here).
	EntryLookupInfo lookup(CatalogType::TABLE_ENTRY, qualified);
	auto function = entry.GetScanFunction(context, bind_data, lookup);
	vector<LogicalType> types;
	std::vector<std::string> names;
	vector<column_t> column_ids;
	idx_t index = 0;
	for (auto &col : entry.GetColumns().Logical()) {
		if (Str(col.Name()) == "id") {
			column_ids.push_back(index);
			types.push_back(col.Type());
			names.push_back("id");
		}
		index++;
	}
	offered.clear();
	if (function.get_virtual_columns) {
		auto virtuals = function.get_virtual_columns(context, bind_data.get());
		for (auto &entry_v : virtuals) {
			auto name = Str(entry_v.second.name);
			offered += (offered.empty() ? "" : ",") + name;
			if (name == "rowid" || name == "filename" || name == "file_row_number" || name == "file_index") {
				column_ids.push_back(entry_v.first);
				types.push_back(entry_v.second.type);
				names.push_back(name);
			}
		}
	}
	how = "GetScanFunction " + NameOf(function) + " on main." + table + " (DuckLake entry), columns:";
	for (auto &n : names) {
		how += " " + n;
	}
	return Drive(context, function, bind_data.get(), types, names, column_ids);
}

//! A table function of the system catalog, bound as Binder::BindTableFunctionInternal binds one.
Rows CallTableFunction(ClientContext &context, optional_ptr<Binder> outer, const std::string &name,
                       vector<Value> args, named_argument_map_t named, std::string &how) {
	auto &entry = Catalog::GetEntry<TableFunctionCatalogEntry>(
	    context, QualifiedName(Identifier("system"), Identifier("main"), Identifier(name)));
	vector<LogicalType> arg_types;
	for (auto &a : args) {
		arg_types.push_back(a.type());
	}
	auto definition = entry.functions.GetFunctionByArguments(context, arg_types);
	BoundTableFunction bound(definition);
	bound.GetSignature().FillNamedDefaults(context, named);
	bound.SetCallArguments(args, named);
	vector<LogicalType> input_types;
	vector<Identifier> input_names;
	TableFunctionRef ref;
	ref.alias = Identifier(name);
	auto binder = Binder::CreateBinder(context, outer);
	TableFunctionBindInput bind_input(args, named, input_types, input_names, bound.function_info.get(), binder.get(),
	                                  bound, ref);
	if (bound.bind_replace) {
		auto table_ref = bound.bind_replace(context, bind_input);
		if (table_ref) {
			auto bound_ref = binder->Bind(*table_ref);
			auto get = bound_ref.plan ? FindGet(*bound_ref.plan) : nullptr;
			if (!get) {
				throw std::runtime_error("bind_replace gave a plan with no LogicalGet");
			}
			how = name + ": bind_replace, then Binder::Bind of the TableRef, then " + NameOf(get->function);
			return DriveGet(context, *get);
		}
	}
	if (!bound.bind) {
		throw std::runtime_error("no bind callback (bind_operator only?)");
	}
	vector<LogicalType> return_types;
	vector<Identifier> return_names;
	auto bind_data = bound.bind(context, bind_input, return_types, return_names);
	std::vector<std::string> names;
	for (auto &n : return_names) {
		names.push_back(Str(n));
	}
	how = name + ": bind, then its scan callback";
	return Drive(context, bound, bind_data.get(), return_types, names);
}

int64_t AsInt(const Value &v) {
	return v.IsNull() ? -1 : v.GetValue<int64_t>();
}

std::string CountMinMax(const Rows &rows, const std::string &column) {
	auto c = rows.Col(column);
	if (c < 0) {
		throw std::runtime_error("no column " + column);
	}
	int64_t mn = 0, mx = 0;
	for (size_t i = 0; i < rows.rows.size(); i++) {
		auto v = AsInt(rows.rows[i][c]);
		mn = i == 0 ? v : std::min(mn, v);
		mx = i == 0 ? v : std::max(mx, v);
	}
	if (rows.rows.empty()) {
		return "<null>";
	}
	return "count=" + std::to_string(rows.rows.size()) + " min=" + std::to_string(mn) + " max=" + std::to_string(mx);
}

std::string InlinedDelete(const Rows &rows, int64_t s) {
	auto f = rows.Col("file_id"), r = rows.Col("row_id"), b = rows.Col("begin_snapshot");
	std::vector<std::pair<int64_t, int64_t>> keys;
	for (auto &row : rows.rows) {
		if (AsInt(row[b]) <= s) {
			keys.emplace_back(AsInt(row[f]), AsInt(row[r]));
		}
	}
	std::sort(keys.begin(), keys.end());
	std::string out;
	for (auto &k : keys) {
		out += (out.empty() ? "" : ",") + std::to_string(k.first) + ":" + std::to_string(k.second);
	}
	return out;
}

std::string InlinedData(const Rows &rows, int64_t s) {
	auto id = rows.Col("_ducklake_row_id"), b = rows.Col("_ducklake_begin_snapshot"),
	     e = rows.Col("_ducklake_end_snapshot");
	std::vector<int64_t> ids;
	for (auto &row : rows.rows) {
		auto begin = AsInt(row[b]);
		auto end = row[e].IsNull() ? -1 : AsInt(row[e]);
		if (begin <= s && (end < 0 || end > s)) {
			ids.push_back(AsInt(row[id]));
		}
	}
	std::sort(ids.begin(), ids.end());
	std::string out;
	for (auto v : ids) {
		out += (out.empty() ? "" : ",") + std::to_string(v);
	}
	return out;
}

std::string DeleteFiles(const Rows &rows, int64_t table_id, int64_t s) {
	auto d = rows.Col("delete_file_id"), df = rows.Col("data_file_id"), t = rows.Col("table_id"),
	     b = rows.Col("begin_snapshot"), e = rows.Col("end_snapshot");
	std::vector<std::vector<int64_t>> keys;
	for (auto &row : rows.rows) {
		auto end = row[e].IsNull() ? -1 : AsInt(row[e]);
		if (AsInt(row[t]) == table_id && AsInt(row[b]) <= s && (end < 0 || end > s)) {
			keys.push_back({AsInt(row[d]), AsInt(row[df]), AsInt(row[b])});
		}
	}
	std::sort(keys.begin(), keys.end());
	std::string out;
	for (auto &k : keys) {
		out += (out.empty() ? "" : ",") + std::to_string(k[0]) + ":" + std::to_string(k[1]) + ":" + std::to_string(k[2]);
	}
	return out;
}

void RunScanReads(ClientContext &context, optional_ptr<Binder> binder, const std::string &label) {
	int64_t s = g_facts.s;
	auto &reads = g_facts.reads;
	for (size_t i = 0; i < reads.size(); i++) {
		auto &rd = reads[i];
		ReadResult r;
		r.read = rd.id;
		std::string how;
		g_phase = "nested:" + rd.id + ":m4";
		auto before = g_recorded.size();
		{
			std::lock_guard<std::mutex> lock(g_fs_mutex);
			g_fs_opens.clear();
			g_fs_counting = true;
		}
		ArmWatchdog(label + " " + g_facts.kind + " " + rd.id + " " + rd.form + " principal=" +
		            PrincipalName(g_principal));
		try {
			if (rd.id == "current_snapshot") {
				r.form = "tablefn";
				auto rows = CallTableFunction(context, binder, "ducklake_current_snapshot", {Value(g_facts.alias)}, {}, how);
				r.values = rows.rows.empty() ? "<no row>" : rows.rows[0][0].ToString();
				try {
					s = std::stoll(r.values);
				} catch (std::exception &) {
				}
			} else if (rd.id == "max_snapshot") {
				r.form = "scan";
				auto rows = ScanCatalogTable(context, g_facts.meta, "ducklake_snapshot", how);
				auto c = rows.Col("snapshot_id");
				int64_t mx = -1;
				for (auto &row : rows.rows) {
					mx = std::max(mx, AsInt(row[c]));
				}
				r.values = std::to_string(mx);
			} else if (rd.id == "delete_files") {
				r.form = "scan";
				auto rows = ScanCatalogTable(context, g_facts.meta, "ducklake_delete_file", how);
				r.values = DeleteFiles(rows, g_facts.table_id, s);
			} else if (rd.id == "inlined_delete" || rd.id == "inlined_data") {
				auto table = rd.id == "inlined_delete" ? g_facts.inlined_delete_table : g_facts.inlined_data_table;
				Rows rows;
				if (rd.form == "sql") {
					r.form = "scan";
					rows = ScanCatalogTable(context, g_facts.meta, table, how);
				} else {
					r.form = "query_table";
					rows = CallTableFunction(context, binder, "query_table", {Value(g_facts.meta + "." + table)}, {}, how);
				}
				r.values = rd.id == "inlined_delete" ? InlinedDelete(rows, s) : InlinedData(rows, s);
			} else if (rd.id == "delete_positions") {
				r.form = "tablefn";
				auto rows = CallTableFunction(context, binder, "parquet_scan", {Value(g_facts.delete_file_path)}, {}, how);
				r.values = CountMinMax(rows, "pos");
			} else if (rd.id == "data_file_rows") {
				r.form = "tablefn";
				named_argument_map_t named;
				named[Identifier("file_row_number")] = Value::BOOLEAN(true);
				auto rows = CallTableFunction(context, binder, "parquet_scan", {Value(g_facts.data_file_path)},
				                              std::move(named), how);
				r.values = CountMinMax(rows, "file_row_number");
			} else if (rd.id == "list_files") {
				// M4a: DuckLake's own table function, bound from C++ in the client's transaction.
				r.form = rd.form == "at_s" ? "tablefn_at_s" : "tablefn";
				named_argument_map_t named;
				if (rd.form == "at_s") {
					named[Identifier("snapshot_version")] = Value::BIGINT(s);
				}
				auto rows = CallTableFunction(context, binder, "ducklake_list_files",
				                              {Value(g_facts.alias), Value("pages")}, std::move(named), how);
				auto df = rows.Col("data_file"), dl = rows.Col("delete_file");
				std::vector<std::pair<std::string, std::string>> files;
				for (auto &row : rows.rows) {
					auto base = [](const Value &v) {
						if (v.IsNull()) {
							return std::string("-");
						}
						auto p = v.ToString();
						return p.substr(p.find_last_of('/') + 1);
					};
					files.emplace_back(row[df].ToString(), base(row[df]) + "|" + base(row[dl]));
				}
				std::sort(files.begin(), files.end());
				for (auto &f : files) {
					r.values += (r.values.empty() ? "" : ",") + f.second;
				}
				std::string columns;
				for (auto &n : rows.names) {
					columns += (columns.empty() ? "" : ",") + n;
				}
				how += "; columns: " + columns;
			} else if (rd.id == "table_scan") {
				// M4b: the DuckLake table's own scan, bound from C++ in the client's transaction.
				r.form = "lake_scan";
				std::string offered;
				auto rows = ScanLakeTable(context, g_facts.alias, "pages", how, offered);
				auto rid = rows.Col("rowid"), fname = rows.Col("filename"), frn = rows.Col("file_row_number");
				std::vector<int64_t> high;
				std::set<std::string> filenames;
				idx_t null_filename = 0, null_frn = 0, inlined_with_filename = 0;
				for (auto &row : rows.rows) {
					auto id = rid >= 0 ? AsInt(row[rid]) : -1;
					if (id >= 20000) {
						high.push_back(id);
					}
					if (fname >= 0) {
						if (row[fname].IsNull()) {
							null_filename++;
						} else {
							auto p = row[fname].ToString();
							filenames.insert(p.substr(p.find_last_of('/') + 1));
							if (id >= 20000) {
								inlined_with_filename++;
							}
						}
					}
					if (frn >= 0 && row[frn].IsNull()) {
						null_frn++;
					}
				}
				std::sort(high.begin(), high.end());
				r.values = std::to_string(rows.rows.size()) + " rows; rowids >= 20000: ";
				for (size_t k = 0; k < high.size(); k++) {
					r.values += (k == 0 ? "" : ",") + std::to_string(high[k]);
				}
				std::string files;
				for (auto &f : filenames) {
					files += (files.empty() ? "" : ",") + f;
				}
				how += "; virtual columns offered: " + (offered.empty() ? std::string("none") : offered) +
				       "; rowid read: " + (rid >= 0 ? "yes" : "no") + "; filename read: " +
				       (fname >= 0 ? "yes (" + std::to_string(filenames.size()) + " distinct: " + files +
				                         "; NULL on " + std::to_string(null_filename) + " rows; rows with rowid >= 20000 "
				                         "carrying one: " + std::to_string(inlined_with_filename) + ")"
				                   : std::string("no")) +
				       "; file_row_number read: " +
				       (frn >= 0 ? "yes (NULL on " + std::to_string(null_frn) + " rows)" : std::string("no"));
			}
			DisarmWatchdog();
			r.ran = true;
		} catch (std::exception &e) {
			DisarmWatchdog();
			ErrorData data(e);
			r.error = data.Message();
		}
		idx_t opens = 0;
		std::string paths;
		{
			std::lock_guard<std::mutex> lock(g_fs_mutex);
			g_fs_counting = false;
			for (auto &entry : g_fs_opens) {
				opens += entry.second;
				auto base = entry.first.substr(entry.first.find_last_of('/') + 1);
				paths += (paths.empty() ? "" : ",") + base + "x" + std::to_string(entry.second);
			}
		}
		g_phase = "probe";
		bool any = false;
		r.seen = SeenSince(before, any);
		r.hooks_saw_nested = any;
		r.matches = r.ran && r.values == rd.expected;
		PrintRead(label, r, rd.expected);
		Say("   " + label + " " + g_facts.kind + " " + rd.id + " " + rd.form + " how: " + (how.empty() ? "-" : how) +
		    "; client FileSystem opens: " + std::to_string(opens) + (paths.empty() ? "" : " (" + paths + ")"));
	}
}

// ---- the probe function and the M2 hook --------------------------------------------------------

struct ProbeGlobal : public GlobalTableFunctionState {};

unique_ptr<FunctionData> ProbeBind(ClientContext &context, TableFunctionBindInput &input, vector<LogicalType> &types,
                                   vector<Identifier> &names) {
	types = {LogicalType::BIGINT};
	names.emplace_back("rows");
	if (g_in_probe || &context != g_client) {
		return make_uniq<TableFunctionData>();
	}
	g_in_probe = true;
	auto label = MechLabel(g_mech, g_principal);
	Say("   " + label + " runs in the bind of qod_probe_source (alias " + input.inputs.back().ToString() + ")");
	if (g_mech == Mech::M1) {
		RunSqlReads(context, label);
	} else if (g_mech == Mech::M4) {
		RunScanReads(context, input.binder, label);
	}
	g_in_probe = false;
	// The outer statement goes on binding: what qod-node describes for it from here is the outer one.
	g_phase = "outer";
	return make_uniq<TableFunctionData>();
}

unique_ptr<GlobalTableFunctionState> ProbeInitGlobal(ClientContext &, TableFunctionInitInput &) {
	return make_uniq<ProbeGlobal>();
}

OperatorResultType ProbeInOut(ExecutionContext &, TableFunctionInput &, DataChunk &, DataChunk &output) {
	output.SetChildCardinality(0);
	return OperatorResultType::NEED_MORE_INPUT;
}

bool ReadsPages(LogicalOperator &op) {
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		auto &get = op.Cast<LogicalGet>();
		auto table = get.GetTable();
		if (table && Str(table->name) == "pages") {
			return true;
		}
	}
	for (auto &child : op.children) {
		if (ReadsPages(*child)) {
			return true;
		}
	}
	return false;
}

void ProbePostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!g_m2_armed || &input.context != g_client || !statement.plan || !ReadsPages(*statement.plan)) {
		return;
	}
	g_m2_armed = false;
	auto label = MechLabel(Mech::M2, g_principal);
	Say("   " + label + " runs in a post_bind hook registered after qod-node's");
	RunSqlReads(input.context, label);
	g_phase = "outer";
}

void RegisterProbe(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_source_probe");
	TableFunction probe("qod_probe_source", {LogicalType::TABLE, LogicalType::VARCHAR}, nullptr, ProbeBind,
	                    ProbeInitGlobal);
	probe.in_out_function = ProbeInOut;
	loader.RegisterFunction(probe);
	PlannerExtension planner;
	planner.post_bind_function = ProbePostBind;
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

// ---- the scenario ------------------------------------------------------------------------------

//! key=value pairs split on whitespace: enough for the generated DSN (no value holds a space or a quote).
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

std::string g_dsn_file;
std::string g_base_dir;

int RunChild(const std::string &base_kind, bool keep, Principal principal, Mech mech) {
	// keep: the variant without expiry and cleanup (review I1), printed as <kind>+keep.
	const std::string kind = base_kind + (keep ? "+keep" : "");
	g_facts = ScenarioFacts();
	g_facts.kind = kind;
	g_facts.meta = "__ducklake_metadata_" + g_facts.alias;
	g_principal = principal;
	g_mech = mech;
	auto name = kind + "-" + PrincipalName(principal) + "-" + MechLabel(mech, Principal::Bare);
	auto dir = std::filesystem::path(g_base_dir) / name;
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir / "data");

	DuckDB db(nullptr);
	if (principal != Principal::Bare) {
		auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
		qod::RegisterNodeAuthz(*db.instance, authz, Recorder);
	}
	RegisterProbe(*db.instance);

	Connection setup(db);
	for (std::string ext : {"ducklake", "parquet"}) {
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("== scenario " + kind + ": " + ext + " unavailable, skipped");
			return 77;
		}
	}
	std::string attach;
	if (base_kind == "postgres") {
		if (setup.Query("INSTALL postgres")->HasError() || setup.Query("LOAD postgres")->HasError()) {
			Say("== scenario " + kind + ": postgres unavailable, skipped");
			return 77;
		}
		std::ifstream in(g_dsn_file);
		std::string dsn((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		auto parts = ParseDsn(dsn);
		g_secret_password = parts["password"];
		auto secret = "CREATE TEMPORARY SECRET (TYPE postgres, HOST " + Quote(parts["host"]) + ", PORT " +
		              parts["port"] + ", USER " + Quote(parts["user"]) + ", PASSWORD " + Quote(parts["password"]) + ")";
		if (setup.Query(secret)->HasError()) {
			Say("== scenario " + kind + ": the postgres secret could not be created (statement not printed)");
			return 1;
		}
		auto db_name = "qod_t1_" + std::string(PrincipalName(principal)) + "_" + MechLabel(mech, Principal::Bare) + (keep ? "_keep" : "");
		std::transform(db_name.begin(), db_name.end(), db_name.begin(), ::tolower);
		if (!Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach admin")) {
			return 1;
		}
		for (auto stmt : {"DROP DATABASE IF EXISTS " + db_name + " WITH (FORCE)", "CREATE DATABASE " + db_name}) {
			auto result = setup.Query("CALL postgres_execute('pgadmin', " + Quote(stmt) + ", use_transaction => false)");
			if (result->HasError()) {
				Say("   setup " + stmt + ": ERROR " + FirstLine(result->GetError()));
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
	bool ok = Exec(setup, attach, "attach lake");
	ok = ok && Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR)", "create");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages SELECT i, 'title ' || i, 'body ' || i FROM range(0, 10000) r(i)",
	                "file A");
	ok = ok && Exec(setup,
	                "INSERT INTO lake.main.pages SELECT i, 'title ' || i, 'body ' || i FROM range(10000, 20000) r(i)",
	                "file B");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages VALUES (90001, 'inl a', 'x'), (90002, 'inl b', 'x'), "
	                       "(90003, 'inl c', 'x')",
	                "inlined insert 1");
	ok = ok && Exec(setup, "INSERT INTO lake.main.pages VALUES (90004, 'inl d', 'x'), (90005, 'inl e', 'x')",
	                "inlined insert 2");
	ok = ok && Exec(setup, "DELETE FROM lake.main.pages WHERE id = 5", "inlined delete");
	ok = ok && Exec(setup, "DELETE FROM lake.main.pages WHERE id BETWEEN 12000 AND 14000", "2001-row delete");
	if (!ok) {
		return 1;
	}

	Connection client(db);
	g_client = client.context.get();
	bool wrapped = WrapClientFileSystem(client);
	Say("== scenario " + kind + " principal=" + PrincipalName(principal) + " mech=" + MechLabel(mech, principal) +
	    ": client FileSystem wrapped for counting: " + (wrapped ? "yes" : "no"));
	if (principal != Principal::Bare) {
		TagAsQuackSession(client);
		qod_test::MintSpec spec;
		spec.principal_id = principal == Principal::Tenant ? "u-1" : "su-1";
		spec.exp = now_ms + 60000;
		spec.superuser = principal == Principal::Superuser;
		g_phase = "prelude";
		auto installed = client.Query(qod_test::Prelude(qod_test::MintWith(spec)));
		if (installed->HasError()) {
			Say("== scenario " + kind + ": prelude refused: " + FirstLine(installed->GetError()));
			return 1;
		}
	}
	g_phase = "client";
	std::string error;
	Exec(client, "BEGIN TRANSACTION", "begin");
	auto first_count = RunValue(client, "SELECT count(*) FROM lake.main.pages", error);
	Say("== scenario " + kind + ": client BEGIN; first read count=" + first_count +
	    (error.empty() ? "" : " error=" + FirstLine(error)));

	// The reference: the node's own connection, at S, before the other connection commits.
	Connection ref(db);
	auto md = g_facts.meta;
	g_facts.s = std::stoll(RunValue(ref, "SELECT * FROM ducklake_current_snapshot('lake')", error));
	g_facts.table_id = std::stoll(RunValue(
	    ref, "SELECT table_id FROM " + md + ".ducklake_table WHERE table_name = 'pages' AND end_snapshot IS NULL", error));
	g_facts.inlined_data_table = RunValue(
	    ref, "SELECT table_name FROM " + md + ".ducklake_inlined_data_tables WHERE table_id = " +
	             std::to_string(g_facts.table_id) + " ORDER BY schema_version DESC LIMIT 1",
	    error);
	g_facts.inlined_delete_table = "ducklake_inlined_delete_" + std::to_string(g_facts.table_id);
	{
		auto files = ref.Query("SELECT data_file, delete_file FROM ducklake_list_files('lake', 'pages') WHERE "
		                       "delete_file IS NOT NULL ORDER BY data_file LIMIT 1");
		if (files->HasError()) {
			Say("   reference list files: ERROR " + FirstLine(files->GetError()));
			return 1;
		}
		auto chunk = files->Fetch();
		if (!chunk || chunk->size() == 0) {
			Say("   reference: no data file with a delete file at S");
			return 1;
		}
		g_facts.data_file_path = chunk->GetValue(0, 0).ToString();
		g_facts.delete_file_path = chunk->GetValue(1, 0).ToString();
	}
	g_facts.reads = BuildReads(g_facts, g_facts.s);
	for (auto &rd : g_facts.reads) {
		std::string ref_error;
		rd.expected = RunValue(ref, rd.sql, ref_error);
		if (!ref_error.empty()) {
			rd.expected = "<reference failed: " + FirstLine(ref_error) + ">";
		}
	}
	auto at_count = RunValue(ref, "SELECT count(*) FROM lake.main.pages AT (VERSION => " + std::to_string(g_facts.s) + ")",
	                         error);
	Say("== scenario " + kind + ": S=" + std::to_string(g_facts.s) + " table_id=" + std::to_string(g_facts.table_id) +
	    " count AT S=" + at_count + " inlined data table=" + g_facts.inlined_data_table +
	    " delete file=" + g_facts.delete_file_path.substr(g_facts.delete_file_path.find_last_of('/') + 1) +
	    " data file=" + g_facts.data_file_path.substr(g_facts.data_file_path.find_last_of('/') + 1));
	for (auto &rd : g_facts.reads) {
		Say("== scenario " + kind + " expected " + rd.id + " " + rd.form + ": [" + rd.expected + "]");
	}

	// The other connection moves the lake on and cleans up.
	Connection other(db);
	Exec(other, "INSERT INTO lake.main.pages SELECT i, 'title ' || i, 'body ' || i FROM range(30000, 35000) r(i)",
	     "other insert");
	Exec(other, "DELETE FROM lake.main.pages WHERE id BETWEEN 15000 AND 15999", "other delete");
	// An inlined row deleted after S: once its snapshots expire, a fresh read at S no longer finds it.
	Exec(other, "DELETE FROM lake.main.pages WHERE id = 90001", "other inlined-row delete");
	std::vector<std::string> maintenance = {"CALL ducklake_merge_adjacent_files('lake')"};
	if (!keep) {
		maintenance.push_back("CALL ducklake_expire_snapshots('lake', older_than => now())");
		maintenance.push_back("CALL ducklake_cleanup_old_files('lake', cleanup_all => true)");
	}
	for (auto &stmt : maintenance) {
		auto result = other.Query(stmt);
		Say("== scenario " + kind + " other: " + std::string(stmt) + ": " +
		    (result->HasError() ? "ERROR " + FirstLine(result->GetError()) : std::string("ok")));
	}
	auto head = RunValue(other, "SELECT * FROM ducklake_current_snapshot('lake')", error);
	Say("== scenario " + kind + " other: head snapshot now " + head + "; delete file at S still on disk: " +
	    (std::filesystem::exists(g_facts.delete_file_path) ? "yes" : "no") +
	    "; data file still on disk: " + (std::filesystem::exists(g_facts.data_file_path) ? "yes" : "no"));

	// The mechanism, on the client's context, in its transaction.
	auto label = MechLabel(mech, principal);
	g_phase = "outer";
	if (mech == Mech::M2) {
		g_m2_armed = true;
		ArmOuterWatchdog("outer " + label + " " + kind + " principal=" + PrincipalName(principal));
		auto result = client.Query("SELECT rowid FROM lake.main.pages");
		DisarmOuterWatchdog();
		g_m2_armed = false;
		std::string outer_error = result->HasError() ? result->GetError() : "";
		Say("== outer " + label + " " + kind + " principal=" + PrincipalName(principal) +
		    ": SELECT rowid FROM lake.main.pages: " +
		    (outer_error.empty() ? std::string("ran") : "refused or failed: " + FirstLine(outer_error)));
	} else {
		ArmOuterWatchdog("outer " + label + " " + kind + " principal=" + PrincipalName(principal));
		auto result = client.Query("SELECT * FROM qod_probe_source((SELECT rowid FROM lake.main.pages), 'lake')");
		DisarmOuterWatchdog();
		std::string outer_error = result->HasError() ? result->GetError() : "";
		Say("== outer " + label + " " + kind + " principal=" + PrincipalName(principal) + ": qod_probe_source: " +
		    (outer_error.empty() ? std::string("ran") : "refused or failed: " + FirstLine(outer_error)));
	}
	g_phase = "client";
	auto later = RunValue(client, "SELECT count(*) FROM lake.main.pages", error);
	Say("== scenario " + kind + ": client's own read after the mechanism, same transaction: count=" + later +
	    (error.empty() ? "" : " error=" + FirstLine(error)));
	client.Query("ROLLBACK");

	// Each read as its own top-level statement on the tenant's connection: what the node describes,
	// refuses locally, and what the real decider gets asked (direct-descriptions.jsonl).
	if (principal == Principal::Tenant && mech == Mech::M4) {
		for (auto &rd : g_facts.reads) {
			g_phase = "direct:" + rd.id + ":" + rd.form;
			auto before = g_recorded.size();
			auto result = client.Query(rd.sql);
			std::string direct_error;
			auto value = FirstValue(*result, direct_error);
			g_phase = "probe";
			bool any = false;
			auto seen = SeenSince(before, any);
			Say("== direct " + kind + " " + rd.id + " " + rd.form + " principal=tenant (autocommit): ran=" +
			    (direct_error.empty() ? "1" : "0") + " described=" + (any ? "1" : "0") +
			    (seen.empty() ? "" : " seen=[" + seen + "]") +
			    (direct_error.empty() ? " value=[" + (value.size() > 200 ? value.substr(0, 200) + "..." : value) + "]"
			                          : " error=[" + FirstLine(direct_error) + "]"));
		}
	}
	std::cout.flush();
	return 0;
}

} // namespace

int main() {
	auto env_dir = std::getenv("QOD_SEARCH_PROBE_DIR");
	auto keep = env_dir && *env_dir;
	auto base = keep ? std::filesystem::path(env_dir)
	                 : std::filesystem::temp_directory_path() / ("qod-search-source-" + std::to_string(::getpid()));
	std::filesystem::create_directories(base);
	g_base_dir = base.string();
	g_desc_dir = keep ? base.parent_path().string() : base.string();
	// QOD_P2_T1_ONLY=<kind>-<principal>-<mech> (for instance duckdb-bare-M1) runs one child only and keeps
	// the captured descriptions of the full run.
	auto only_env = std::getenv("QOD_P2_T1_ONLY");
	std::string only = only_env ? only_env : "";
	// QOD_P2_T1_MECHS=M4 (a comma list of M1, M2, M4) runs those mechanisms only; the captured
	// descriptions then go to <desc dir>/fix1/, so the full run's stay as they are.
	auto mechs_env = std::getenv("QOD_P2_T1_MECHS");
	std::string mechs = mechs_env ? mechs_env : "";
	if (!mechs.empty() && only.empty()) {
		g_desc_dir += "/fix1";
		std::filesystem::create_directories(g_desc_dir);
	}
	if (only.empty()) {
		for (auto file : {"nested-descriptions.jsonl", "nested-descriptions.index", "direct-descriptions.jsonl",
		                  "direct-descriptions.index", "outer-descriptions.jsonl", "outer-descriptions.index"}) {
			std::filesystem::remove(std::filesystem::path(g_desc_dir) / file);
		}
	} else {
		g_desc_dir.clear();
	}
	auto dsn = std::getenv("QOD_P2_PG_DSN_FILE");
	g_dsn_file = dsn ? dsn : "";
	std::vector<std::string> kinds = {"duckdb"};
	if (!g_dsn_file.empty() && std::filesystem::exists(g_dsn_file)) {
		kinds.push_back("postgres");
	} else {
		std::cout << "== scenario postgres: QOD_P2_PG_DSN_FILE not set or missing: the Postgres catalog is skipped"
		          << std::endl;
	}
	int skipped = 0, failed = 0, ran = 0, hung = 0;
	// The keep variant (no expiry, no cleanup: review I1) runs M4 only: M1 and M2 deadlock whatever
	// the catalog holds.
	for (bool keep_variant : {false, true}) {
	for (auto &kind : kinds) {
		for (auto principal : {Principal::Bare, Principal::Superuser, Principal::Tenant}) {
			for (auto mech : {Mech::M1, Mech::M2, Mech::M4}) {
				auto mech_name = MechLabel(mech, Principal::Bare);
				auto id = kind + (keep_variant ? "+keep" : "") + "-" + PrincipalName(principal) + "-" + mech_name;
				if (!only.empty() && only != id) {
					continue;
				}
				if (keep_variant && mech != Mech::M4) {
					continue;
				}
				if (!mechs.empty() && ("," + mechs + ",").find("," + mech_name + ",") == std::string::npos) {
					continue;
				}
				std::cout.flush();
				auto pid = fork();
				if (pid == 0) {
					std::cout << "== child " << id << " pid " << ::getpid() << std::endl;
					int code = 1;
					try {
						code = RunChild(kind, keep_variant, principal, mech);
					} catch (std::exception &e) {
						std::cout << "== scenario " << kind << ": child failed: " << Redact(e.what()) << std::endl;
					}
					std::cout.flush();
					_exit(code);
				}
				int status = 0;
				// The mechanism has its own 20 s watchdog; this bounds the scenario build too.
				int waited = 0;
				while (waitpid(pid, &status, WNOHANG) == 0) {
					if (++waited > 1800) {
						kill(pid, SIGKILL);
						waitpid(pid, &status, 0);
						std::cout << "== scenario " << kind << " " << PrincipalName(principal)
						          << ": child killed after 180 s" << std::endl;
						break;
					}
					usleep(100000);
				}
				auto code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
				std::cout << "== child " << kind << (keep_variant ? "+keep" : "") << " " << PrincipalName(principal)
				          << " " << MechLabel(mech, principal) << " exit=" << code << std::endl;
				if (code == 77) {
					skipped++;
				} else if (code == 0) {
					ran++;
				} else if (code == 3) {
					hung++;
				} else {
					failed++;
				}
			}
		}
	}
	}
	if (!keep) {
		std::error_code ignored;
		std::filesystem::remove_all(base, ignored);
	}
	std::cout << "children ran: " << ran << ", hung (watchdog): " << hung << ", skipped: " << skipped
	          << ", failed: " << failed << std::endl;
	if (ran == 0 && hung == 0 && skipped > 0) {
		return 77;
	}
	return failed == 0 ? 0 : 1;
}
