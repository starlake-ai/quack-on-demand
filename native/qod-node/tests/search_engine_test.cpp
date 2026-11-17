// QoD Search (spec 4.1, 4.2 rules 1, 2, 4, 5, 7, 9, 6.1, 7.1, 7.6): qod_search and qod_search_matches end to
// end in qod-node, on a DuckLake with a DuckDB-file catalog.
//
// Fixture: lake.main.pages(id, title, body, lang, f10) of 20,000 rows (Task 3's rule: f10 = id % 10,
// lang 'fr' for even ids, a Zipf body) in 2 data files, lake.main.other(id, body), a plain view
// lake.main.v AS SELECT rowid AS rid, * FROM lake.main.pages and a secure view memory.main.sv over pages;
// a stand-in index record per table (test_search_records.hpp: pages on title and body, the others on body),
// a bundle per data file built with qod_search_build_segment; qod-node's hooks with a decider that allows
// everything and records the descriptions; the measured connection tagged as a quack session with a
// tenant principal (pool acme/acme_db/bi).
//
// The reference (also Tasks 10 and 11): a fresh single segment built through qs_builder_* from the live
// rows, searched with QS_POPULATION_TABLE, an all-ones live bitset and the allowed bitset marking the
// rows the case's input returns. Cases: A1 to A9 admitted (rowids, ranks and bit-identical scores against the
// reference); R1 to R27 refused (or recorded) before any segment is read and before any sink plan; D1 to
// D4 the descriptions; E0 to E5 the execution through the sink; G1 the GetAllFiles re-entrancy count (and on a
// fresh lake, with qod-node's own verdict cache: G1 the counts by phase, G2 a commit between the hook and
// execution, G3 a lock probe in a forked child; the Postgres catalog's run is search_engine_postgres);
// T9 the verdict's search entries and the record source; M1 to M6 Phase 1's (two columns, every match, the
// record checks, the build, branches, query_json) and F-MAC (every bundle refusal takes the missing bundle's
// path); V0 to V8 visibility at S, the liveness cache and the bundle removal (Task 10, on lake.main.vt:
// 30,000 rows in 3 files, a delete file, an update flushed, a merge); V9 the cache's key, its bounds and a
// catalog pinned at a snapshot (a SQLite-catalog lake). W1 the row-policy rewriter's actual output
// (golden/search-rewriter-outputs.sql) through the lineage check; CS1 the column-policy search separation's output
// (golden/search-cls-outputs.sql) through the lineage check; W2 the build endpoint's statements on the
// system principal's connection, then a search over those bundles. F1 to F11 and R the fallback for
// unindexed files and inlined rows and its cap (on lake.main.ft and lake.main.big), and a column rename swap.
// Exits 77 when ducklake cannot load.

#include "authz.hpp"
#include "crypto.hpp"
#include "decide_client.hpp"
#include "describer.hpp"
#include "json.hpp"
#include "node_hooks.hpp"
#include "search_fallback.hpp"
#include "search_functions.hpp"
#include "search_knobs.hpp"
#include "search_lineage.hpp"
#include "search_memory.hpp"
#include "search_segments.hpp"
#include "search_sink.hpp"
#include "search_visibility.hpp"
#include "test_handles.hpp"
#include "test_http_server.hpp"
#include "test_search_records.hpp"
#include "test_verdicts.hpp"

#include "qod_search.h"

#include "duckdb.hpp"
#include "duckdb/common/multi_file/multi_file_states.hpp"
#include "duckdb/common/opener_file_system.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/query_result_stream.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "duckdb/storage/buffer/temporary_file_information.hpp"
#include "duckdb/storage/buffer_manager.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <poll.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

#ifdef __APPLE__
#include <mach/mach.h>
#endif

// The shared fixtures (Task 17 moved them to search_fixtures.hpp, unchanged): checks, statements, hits and the
// reference, the stand-in decider and record source, Install, the refusal checks, the builds, the counting file
// system, the knob registration.
#include "search_fixtures.hpp"

namespace {

//! --only-y: run Y0 to Y8 only (Task 14).
bool g_only_y = false;
bool OnlyY() {
	return g_only_y;
}


// ---- the decider, the session, the G1 counter --------------------------------------------------


//! G1 (change 23): statements bound on contexts other than the client's while the client's statement runs, by
//! phase: before the search hook (the client's own bind), in the hook before its snapshot view, while the hook
//! resolves the view (GetAllFiles and the liveness scan), in the hook after it, and after the hook until the
//! statement ends (the optimizer and the execution). Task 13 adds the hook's bracket (SearchHookObserverForTest)
//! and, per statement, whether qod-node's verdict counters moved while its hooks ran on it.
struct G1 {
	std::mutex lock;
	const ClientContext *client = nullptr;
	bool on = false;
	enum Phase { BeforeHook = 0, HookBefore = 1, Resolving = 2, HookAfter = 3, AfterHook = 4 };
	int phase = BeforeHook;
	//! Per phase, per other context: the statements counted by the post_bind registered after the search's.
	std::map<const ClientContext *, int> counts[5];
	int untagged[5] = {0, 0, 0, 0, 0};
	//! The same counts from a post_bind registered BEFORE qod-node's (Task 16): equal counts mean DuckDB called
	//! every post_bind between the two, qod-node's included, on each of those statements (it calls them in
	//! registration order, and a hook that threw would have failed the statement).
	int pre[5] = {0, 0, 0, 0, 0};
	//! The node whose verdict counters are read around each of those statements (null: none read).
	qod::NodeAuthz *authz = nullptr;
	//! Per other context, the counters when the pre hook saw its statement.
	std::map<const ClientContext *, std::vector<int64_t>> open;
	//! Statements during which plan_cache_hits, plan_cache_misses, decide_calls or local_refusals moved.
	int counters_moved = 0;
	//! Statements the post hook saw with no pre hook record (never expected).
	int unpaired = 0;
};
G1 g_g1;

std::vector<int64_t> VerdictCounters(qod::NodeAuthz &authz) {
	auto &s = authz.Stats();
	return {s.plan_cache_hits.load(), s.plan_cache_misses.load(), s.decide_calls.load(), s.local_refusals.load()};
}

void CountingPreBind(PlannerExtensionInput &input, BoundStatement &) {
	std::lock_guard<std::mutex> guard(g_g1.lock);
	if (!g_g1.on || &input.context == g_g1.client) {
		return;
	}
	g_g1.pre[g_g1.phase]++;
	if (g_g1.authz) {
		g_g1.open[&input.context] = VerdictCounters(*g_g1.authz);
	}
}

void CountingPostBind(PlannerExtensionInput &input, BoundStatement &) {
	std::lock_guard<std::mutex> guard(g_g1.lock);
	if (!g_g1.on || &input.context == g_g1.client) {
		return;
	}
	auto untagged = input.context.registered_state->Get<ClientContextState>(qod::kQuackSessionKey) == nullptr;
	g_g1.counts[g_g1.phase][&input.context]++;
	g_g1.untagged[g_g1.phase] += untagged;
	if (g_g1.authz) {
		auto it = g_g1.open.find(&input.context);
		if (it == g_g1.open.end()) {
			g_g1.unpaired++;
		} else {
			g_g1.counters_moved += VerdictCounters(*g_g1.authz) != it->second;
			g_g1.open.erase(it);
		}
	}
}


// ---- V: visibility at S and statistics in both populations (Task 10, spec 6.3, 7.5, invariant 3) --

//! The largest relative score difference between `got` and `want` (same length assumed).
double MaxRel(const std::vector<Hit> &got, const std::vector<Hit> &want) {
	double worst = 0;
	for (size_t i = 0; i < got.size() && i < want.size(); i++) {
		worst = std::max(worst, std::fabs(double(got[i].score) - double(want[i].score)) /
		                            std::max(1e-12, std::fabs(double(want[i].score))));
	}
	return worst;
}

//! Builds the bundle of every data file of lake.main.vt not built yet from the file itself, on the system
//! principal's connection `builder`: positions as the file holds them (so a file holding rows dead at the
//! head builds too), its own rowids (_ducklake_internal_row_id, else row_id_start + position) and its
//! per-row snapshots when it carries _ducklake_internal_snapshot_id (a flushed or merged file, V5).
//! VBuildTable for `catalog`.`table` (listed and its metadata read through the writable `catalog`), the
//! bundles built under the index of `index_table` (another alias of the same lake for a pinned attach).
bool VBuildTable(Connection &setup, Connection &builder, const std::string &catalog, const std::string &table,
                 const std::string &index_table, const std::string &root, std::set<std::string> &built_paths,
                 int &built_now, std::set<std::string> &with_snapshots) {
	Rows listed;
	std::string error;
	if (!Try(setup,
	         "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files(" + Quote(catalog) +
	             ", " + Quote(table) + ")",
	         listed, error)) {
		Check(false, "V list " + table + "'s files", FirstLine(error));
		return false;
	}
	built_now = 0;
	for (auto &file : listed) {
		auto path = file[0].GetValue<std::string>();
		if (built_paths.count(path)) {
			continue;
		}
		Rows columns;
		if (!Try(setup, "SELECT name FROM parquet_schema(" + Quote(path) + ")", columns, error)) {
			Check(false, "V the columns of " + path, FirstLine(error));
			return false;
		}
		bool has_rowid = false, has_snapshot = false;
		for (auto &c : columns) {
			auto name = c[0].ToString();
			has_rowid = has_rowid || name == "_ducklake_internal_row_id";
			has_snapshot = has_snapshot || name == "_ducklake_internal_snapshot_id";
		}
		std::string rowid = "_ducklake_internal_row_id::BIGINT";
		if (!has_rowid) {
			Rows start;
			if (!Try(setup,
			         "SELECT row_id_start FROM __ducklake_metadata_" + catalog +
			             ".ducklake_data_file WHERE end_snapshot IS NULL AND ends_with(" +
			             Quote(path) + ", path)",
			         start, error) ||
			    start.size() != 1) {
				Check(false, "V the row_id_start of " + path, FirstLine(error));
				return false;
			}
			rowid = "(" + start[0][0].ToString() + " + file_row_number)::BIGINT";
		}
		auto snapshot = has_snapshot ? std::string("_ducklake_internal_snapshot_id::BIGINT") : std::string("NULL::BIGINT");
		Rows built;
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, " + rowid + ", " + snapshot +
		           ", body FROM read_parquet(" + Quote(path) +
		           ", file_row_number = true) ORDER BY file_row_number), " + Quote(index_table) + ", " +
		           Quote(path) + ", " + file[1].ToString() + "::UBIGINT, " + file[2].ToString() + "::UBIGINT)";
		auto saved_root = g_bundle_root;
		g_bundle_root = root;
		auto ran = Try(builder, sql, built, error);
		g_bundle_root = saved_root;
		if (!ran || built.size() != 1) {
			Check(false, "V build the bundle of " + path, FirstLine(error));
			return false;
		}
		built_paths.insert(path);
		if (has_snapshot) {
			with_snapshots.insert(path);
		}
		built_now++;
		Say("   V built " + fs::path(path).filename().string() + ": " + built[0][1].ToString() + " docs" +
		    (has_rowid ? ", explicit rowids" : "") + (has_snapshot ? ", per-row snapshots" : ""));
	}
	return true;
}

bool VBuild(Connection &setup, Connection &builder, const std::string &root, int &built_now,
            std::set<std::string> &with_snapshots) {
	return VBuildTable(setup, builder, "lake", "vt", "lake.main.vt", root, g_built, built_now, with_snapshots);
}

//! The DuckLake field id (ducklake_column.column_id) of `column` of lake.main.`table` now.
int64_t ColumnFieldId(Connection &setup, const std::string &table, const std::string &column) {
	return Int(setup, "SELECT c.column_id FROM __ducklake_metadata_lake.ducklake_column c JOIN "
	                  "__ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = " +
	                      Quote(table) + " AND t.end_snapshot IS NULL AND c.column_name = " + Quote(column) +
	                      " AND c.end_snapshot IS NULL");
}

//! The bundle of `file` (the index of lake.main.vt) under `root`, through the node's segment cache, opened as
//! the search opens it: under the record's index id, table id, field ids and MAC key.
std::shared_ptr<const qod::search::OpenSegment> VSegment(Connection &setup, const std::string &root,
                                                         const qod::search::DataFileRef &file) {
	auto record = RecordOf("vt");
	auto copy = file;
	qod::search::QueryMemory memory(uint64_t(1) << 40, false);
	return qod::search::LoadSegment(*setup.context, qod::search::SegmentPath(root, record.index_id, file.bundle_key),
	                                record, copy, memory);
}

//! The live rowids a view holds: every doc of every file that its liveness at S marks.
std::set<int64_t> ViewRowids(Connection &setup, const std::string &root, const qod::search::SnapshotView &view,
                             bool &ok) {
	std::set<int64_t> out;
	ok = true;
	for (size_t i = 0; i < view.files.size(); i++) {
		auto segment = VSegment(setup, root, view.files[i]);
		if (!segment) {
			ok = false;
			continue;
		}
		for (uint32_t d = 0; d < segment->num_docs; d++) {
			if (view.liveness[i].IsLive(d)) {
				out.insert(qs_segment_rowid(segment->segment, d));
			}
		}
	}
	return out;
}

std::set<int64_t> SuperuserRowids(Connection &setup, const std::string &at) {
	Rows rows;
	std::string error;
	std::set<int64_t> out;
	if (Try(setup, "SELECT rowid FROM lake.main.vt" + at, rows, error)) {
		for (auto &r : rows) {
			out.insert(r[0].GetValue<int64_t>());
		}
	}
	return out;
}

std::string At(int64_t version) {
	return version < 0 ? std::string() : " AT (VERSION => " + std::to_string(version) + ")";
}

const std::vector<std::string> kVQueries = {"w0", "w1 w10", "w49999", "w3 w7 w11"};

struct VState {
	Connection *setup;
	Connection *client;
	std::string root;
	double max_rel = 0;
	int compared = 0;
};

//! The view the last search on `client` searched at.
std::shared_ptr<const qod::search::SnapshotView> ViewOf(VState &v, int64_t version, bool &hit, std::string &error) {
	qod::ResetLastSearchCallForTest();
	Rows rows;
	auto ran = Try(*v.client, Search("SELECT rowid FROM lake.main.vt" + At(version), "'w0'", "'lake.main.vt'"), rows, error);
	auto record = qod::LastSearchCallForTest();
	hit = record.view_hit;
	return ran && record.called ? record.view : nullptr;
}

//! Every query of Task 7's list, at each version (-1 = the current S), in three cells: the table
//! population over the whole table and over WHERE f10 = 0 (statistics of every live row, the allowed
//! bitset the input's), and the allowed population over WHERE f10 = 0 (statistics of the allowed rows
//! only, through the stand-in decider answering 'allowed'). Each equals the reference: a fresh segment of
//! the superuser's live rows at that version (those passing f10 = 0 for the allowed population).
void VCompare(VState &v, const std::string &step, const std::vector<int64_t> &versions) {
	for (auto version : versions) {
		auto at = At(version);
		int pass = 0, total = 0;
		std::string first_failure;
		struct Cell {
			std::string name, population, input, reference_at;
		};
		std::vector<Cell> cells = {
		    {"table", "table", "SELECT rowid FROM lake.main.vt" + at, at},
		    {"table, f10 = 0", "table", "SELECT rowid FROM lake.main.vt" + at + " WHERE f10 = 0", at},
		    {"allowed, f10 = 0", "allowed", "SELECT rowid FROM lake.main.vt" + at + " WHERE f10 = 0", at + " WHERE f10 = 0"},
		};
		for (auto &query : kVQueries) {
			for (auto &cell : cells) {
				total++;
				g_population = cell.population;
				Rows rows;
				std::string error, rerr, why;
				auto ran = Try(*v.client, Search(cell.input, Quote(query), "'lake.main.vt'"), rows, error);
				g_population = "table";
				auto want = Reference(*v.setup, "lake.main.vt", cell.reference_at, cell.input, query, 10, rerr);
				auto got = Hits(rows);
				if (ran && rerr.empty() && SameHits(got, want, true, why)) {
					pass++;
					v.compared++;
					v.max_rel = std::max(v.max_rel, MaxRel(got, want));
				} else if (first_failure.empty()) {
					first_failure = "'" + query + "' " + cell.name + ": " + (ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
				}
			}
		}
		Check(pass == total,
		      step + " at " + (version < 0 ? std::string("the current S") : "VERSION " + std::to_string(version)) + ": " +
		          std::to_string(total) + " searches (4 queries, table and allowed populations) equal the reference",
		      first_failure);
	}
}

//! V8: each file's HiddenPositions at the current S against the positions the superuser's read of that
//! file does not return at S.
void VDeadDocs(VState &v, const std::string &step) {
	bool hit = false;
	std::string error;
	auto view = ViewOf(v, -1, hit, error);
	if (!view) {
		Check(false, step + " V8: the view at the current S", FirstLine(error));
		return;
	}
	for (size_t i = 0; i < view->files.size(); i++) {
		auto &file = view->files[i];
		auto segment = VSegment(*v.setup, v.root, file);
		Rows rows;
		Try(*v.setup, "SELECT file_row_number FROM lake.main.vt WHERE filename = " + Quote(file.path), rows, error);
		std::set<int64_t> returned;
		for (auto &r : rows) {
			returned.insert(r[0].GetValue<int64_t>());
		}
		std::vector<std::pair<int64_t, int64_t>> want;
		int64_t docs = segment ? segment->num_docs : 0;
		for (int64_t p = 0; p < docs; p++) {
			if (!returned.count(p)) {
				if (!want.empty() && want.back().second == p) {
					want.back().second = p + 1;
				} else {
					want.emplace_back(p, p + 1);
				}
			}
		}
		auto got = qod::search::HiddenPositions(view->liveness[i], docs);
		int64_t dead = 0;
		std::ostringstream runs;
		for (auto &r : got) {
			dead += r.second - r.first;
			if (runs.tellp() < 80) {
				runs << " [" << r.first << ", " << r.second << ")";
			}
		}
		Check(segment && got == want,
		      step + " V8 " + fs::path(file.path).filename().string() + ": HiddenPositions equals the superuser's deleted "
		                                                              "positions at S",
		      std::to_string(docs) + " docs, " + std::to_string(dead) + " dead:" + runs.str());
		Say("   " + step + " dead docs: file " + std::to_string(i) + " of " + std::to_string(view->files.size()) + ", " +
		    std::to_string(docs) + " docs, " + std::to_string(dead) + " dead");
	}
}

//! The live rowid set of each version's view, and whether each equals the superuser's read there.
std::map<int64_t, std::set<int64_t>> VLiveSets(VState &v, const std::string &step, const std::vector<int64_t> &versions) {
	std::map<int64_t, std::set<int64_t>> out;
	for (auto version : versions) {
		bool hit = false, ok = false;
		std::string error;
		auto view = ViewOf(v, version, hit, error);
		if (!view) {
			Check(false, step + " the view at VERSION " + std::to_string(version), FirstLine(error));
			continue;
		}
		auto set = ViewRowids(*v.setup, v.root, *view, ok);
		auto truth = SuperuserRowids(*v.setup, At(version));
		Check(ok && set == truth,
		      step + " at VERSION " + std::to_string(version) + ": the view's live rowids equal the superuser's read",
		      std::to_string(set.size()) + " live, superuser " + std::to_string(truth.size()));
		out[version] = std::move(set);
	}
	return out;
}

void RunVisibility(Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	VState v {&setup, &client, data};
	g_population = "table";
	g_bundle_root = data;
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	bool ok = Exec(setup, "CREATE TABLE lake.main.vt (id BIGINT, body VARCHAR, f10 INTEGER)", "vt") &&
	          IndexTable(setup, "vt");
	for (int f = 0; f < 3 && ok; f++) {
		ok = Exec(setup,
		          "INSERT INTO lake.main.vt SELECT i, " + body + ", (i % 10)::INTEGER FROM range(" + std::to_string(f * 10000) +
		              ", " + std::to_string((f + 1) * 10000) + ") r(i) ORDER BY i",
		          "vt file " + std::to_string(f + 1));
	}
	if (!ok) {
		return;
	}
	std::set<std::string> with_snapshots;
	int built = 0;
	if (!VBuild(setup, sysc, data, built, with_snapshots)) {
		return;
	}
	Check(built == 3 && Int(setup, "SELECT count(*) FROM lake.main.vt") == 30000, "V vt: 30,000 rows in 3 bundled files",
	      std::to_string(built) + " files");
	auto n0 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	VCompare(v, "V0", {-1});

	// V1: a 2,001-row DELETE writes a delete file.
	Exec(setup, "DELETE FROM lake.main.vt WHERE id BETWEEN 5000 AND 7000", "V1 delete 2,001 rows");
	auto n1 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	Check(Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'vt') WHERE delete_file IS NOT NULL") == 1,
	      "V1 the delete wrote a delete file");
	VCompare(v, "V1", {-1, n0});
	VDeadDocs(v, "V1");

	// V2: a one-row UPDATE of a file row: an inlined delete and an inlined row with the same rowid (6.3).
	auto old_rowid = Int(setup, "SELECT rowid FROM lake.main.vt WHERE id = 15000");
	Exec(setup, "UPDATE lake.main.vt SET body = 'w0 w1 w10 w3 w7 w11 updated', f10 = 0 WHERE id = 15000", "V2 update");
	auto n2 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	Check(Int(setup, "SELECT rowid FROM lake.main.vt WHERE id = 15000") == old_rowid &&
	          Int(setup, "SELECT count(*) FROM lake.main.vt WHERE id = 15000 AND filename LIKE 'ducklake_inlined_data_%'") == 1,
	      "V2 the update is an inlined row with the file row's rowid " + std::to_string(old_rowid));
	{
		// Task 11 (F3 on vt): before the flush the file doc is dead by the inlined delete and the new value is a
		// doc of the query's fallback segment, with the same rowid: one hit for it, and every cell equals the
		// reference.
		Rows rows;
		std::string error;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.vt WHERE id = 15000", "'updated'", "'lake.main.vt'"),
		               rows, error);
		auto record = qod::LastSearchCallForTest();
		Check(ran && rows.size() == 1 && rows[0][0].GetValue<int64_t>() == old_rowid && record.fallback_segments == 1 &&
		          record.fallback_docs == 1,
		      "V2 before the flush, the updated row is found through the fallback segment, under its rowid",
		      ran ? Show(Hits(rows)) + ", " + std::to_string(record.fallback_segments) + " fallback segments"
		          : FirstLine(error));
		VCompare(v, "V2 before the flush (an inlined row and an inlined delete, through the fallback)", {-1, n1});
	}
	auto before_flush = VLiveSets(v, "V2 before the flush", {n0, n1});

	// V3: ducklake_flush_inlined_data, then the new file's bundle.
	Exec(setup, "CALL ducklake_flush_inlined_data('lake')", "V3 flush");
	auto n3 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	VBuild(setup, sysc, data, built, with_snapshots);
	Check(built == 1, "V3 the flush wrote one data file, now bundled", std::to_string(built));
	{
		// The rowid repeats: a dead doc of file 2 and a live doc of the flushed file; Locate takes the live one.
		bool hit = false;
		std::string error;
		auto view = ViewOf(v, -1, hit, error);
		int holders = 0, live_holders = 0;
		if (view) {
			for (size_t i = 0; i < view->files.size(); i++) {
				auto segment = VSegment(setup, data, view->files[i]);
				for (uint32_t d = 0; segment && d < segment->num_docs; d++) {
					if (qs_segment_rowid(segment->segment, d) == old_rowid) {
						holders++;
						live_holders += view->liveness[i].IsLive(d);
					}
				}
			}
		}
		Check(holders == 2 && live_holders == 1,
		      "V3 rowid " + std::to_string(old_rowid) + " is held by 2 docs, 1 of them live at S",
		      std::to_string(holders) + " docs, " + std::to_string(live_holders) + " live" + (view ? "" : ": " + FirstLine(error)));
		Rows rows;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.vt WHERE id = 15000", "'updated'", "'lake.main.vt'"), rows,
		               error);
		Check(ran && rows.size() == 1 && rows[0][0].GetValue<int64_t>() == old_rowid,
		      "V3 the repeated rowid resolves to its one live doc (the updated text is found)",
		      ran ? Show(Hits(rows)) : FirstLine(error));
	}
	VCompare(v, "V3", {-1, n2, n1, n0});
	VDeadDocs(v, "V3 (after V1 and V2)");
	auto after_flush = VLiveSets(v, "V3 after the flush", {n0, n1, n2});
	Check(after_flush[n0] == before_flush[n0] && after_flush[n1] == before_flush[n1],
	      "V3 liveness at old S is identical across the flush (VERSION " + std::to_string(n0) + " and " +
	          std::to_string(n1) + ")");

	// V4: ducklake_merge_adjacent_files, then the merged file's bundle.
	auto before_merge = VLiveSets(v, "V4 before the merge", {n0, n1, n2, n3});
	Exec(setup, "CALL ducklake_merge_adjacent_files('lake')", "V4 merge");
	auto n4 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	VBuild(setup, sysc, data, built, with_snapshots);
	Check(built >= 1, "V4 the merge wrote a data file of vt, now bundled", std::to_string(built));
	VCompare(v, "V4", {-1, n3, n2, n1, n0});
	auto after_merge = VLiveSets(v, "V4 after the merge", {n0, n1, n2, n3});
	bool same = true;
	for (auto version : {n0, n1, n2, n3}) {
		same = same && after_merge[version] == before_merge[version];
	}
	Check(same, "V4 liveness at old S is identical across the merge (VERSION " + std::to_string(n0) + " to " +
	                std::to_string(n3) + ")");

	// V5: a file carrying _ducklake_internal_snapshot_id, read at an old S: its rows stamped after S are
	// hidden by the target's own scan (6.3 rule 2), the bundle's snapshot column unused.
	{
		bool hit = false;
		std::string error;
		auto view = ViewOf(v, n1, hit, error);
		int carrying = 0, hidden_stamped = 0, live_stamped = 0;
		if (view) {
			for (size_t i = 0; i < view->files.size(); i++) {
				if (!with_snapshots.count(view->files[i].path)) {
					continue;
				}
				carrying++;
				auto segment = VSegment(setup, data, view->files[i]);
				for (uint32_t d = 0; segment && d < segment->num_docs; d++) {
					if (qs_segment_snapshot(segment->segment, d) > n1) {
						if (view->liveness[i].IsLive(d)) {
							live_stamped++;
						} else {
							hidden_stamped++;
						}
					}
				}
			}
		}
		Check(view && carrying >= 1 && hidden_stamped >= 1 && live_stamped == 0,
		      "V5 at VERSION " + std::to_string(n1) + ": a listed file carries _ducklake_internal_snapshot_id and every "
		      "row of it stamped after S is hidden",
		      std::to_string(carrying) + " such files, " + std::to_string(hidden_stamped) + " rows stamped after S hidden, " +
		          std::to_string(live_stamped) + " live" +
		          (view ? "" : ": " + FirstLine(error)));
	}
	(void)n4;

	// V6: the cache (3c).
	{
		qod::search::ClearLivenessCacheForTest();
		const std::string sql = Search("SELECT rowid FROM lake.main.vt WHERE f10 = 0", "'w1 w10'", "'lake.main.vt'");
		auto run = [&](Connection &con, Rows &rows, qod::SearchCallRecord &record, std::string &error) {
			qod::ResetLastSearchCallForTest();
			auto ran = Try(con, sql, rows, error);
			record = qod::LastSearchCallForTest();
			return ran;
		};
		Rows r1, r2, r3, r4;
		qod::SearchCallRecord c1, c2, c3, c4;
		std::string e1, e2, e3, e4;
		auto k0 = qod::search::LivenessCacheCountersForTest();
		auto ok1 = run(client, r1, c1, e1);
		auto ok2 = run(client, r2, c2, e2);
		auto k1 = qod::search::LivenessCacheCountersForTest();
		bool identical = r1.size() == r2.size() && !r1.empty();
		for (size_t i = 0; identical && i < r1.size(); i++) {
			identical = r1[i][0] == r2[i][0] && r1[i][1].GetValue<float>() == r2[i][1].GetValue<float>();
		}
		Check(ok1 && ok2 && !c1.view_hit && c2.view_hit && c1.view_source == c2.view_source && k1.misses == k0.misses + 1 &&
		          k1.hits == k0.hits + 1,
		      "V6 a repeated auto-commit search hits (the same view at the same S)",
		      ok1 && ok2 ? "" : FirstLine(e1 + e2));
		Check(identical, "V6 the results are identical, miss then hit", Show(Hits(r1)) + " / " + Show(Hits(r2)));
		// A commit from another connection: the next search misses, at a new S, and sees the commit.
		auto top = r1.empty() ? -1 : r1[0][0].GetValue<int64_t>();
		Exec(setup, "DELETE FROM lake.main.vt WHERE rowid = " + std::to_string(top), "V6 another connection's delete");
		{
			// Review M3: that one-row delete is an inlined delete (ducklake_inlined_delete_<table id>), and the
			// whole grid at the current S searches over it.
			auto table_id = Int(setup, "SELECT table_id FROM __ducklake_metadata_lake.ducklake_table WHERE table_name = 'vt' "
			                           "AND end_snapshot IS NULL");
			Check(Int(setup, "SELECT count(*) FROM __ducklake_metadata_lake.ducklake_inlined_delete_" +
			                     std::to_string(table_id)) >= 1,
			      "V6 the one-row delete is an inlined delete");
			VCompare(v, "V6 over an inlined delete", {-1});
			qod::search::ClearLivenessCacheForTest();
		}
		auto ok3 = run(client, r3, c3, e3);
		auto ok4 = run(client, r4, c4, e4);
		std::string rerr, why, why2;
		auto want = Reference(setup, "lake.main.vt", "", "SELECT rowid FROM lake.main.vt WHERE f10 = 0", "w1 w10", 10, rerr);
		bool gone = true;
		for (auto &row : r3) {
			gone = gone && row[0].GetValue<int64_t>() != top;
		}
		Check(ok3 && !c3.view_hit && c3.view && c1.view && c3.view->snapshot_id > c1.view->snapshot_id && gone &&
		          SameHits(Hits(r3), want, true, why),
		      "V6 a commit from another connection: the next search misses at a new S and equals the fresh reference",
		      ok3 ? why : FirstLine(e3));
		Check(ok4 && c4.view_hit && SameHits(Hits(r4), Hits(r3), true, why2),
		      "V6 and the search after it hits, with identical results", ok4 ? why2 : FirstLine(e4));
		// AT (VERSION => n) and an explicit transaction never touch the cache.
		auto k2 = qod::search::LivenessCacheCountersForTest();
		Rows ra, rt;
		std::string ea, et;
		qod::ResetLastSearchCallForTest();
		auto ok_at = Try(client, Search("SELECT rowid FROM lake.main.vt AT (VERSION => " + std::to_string(n3) + ")", "'w0'",
		                                "'lake.main.vt'"),
		                 ra, ea);
		auto at_record = qod::LastSearchCallForTest();
		auto k3 = qod::search::LivenessCacheCountersForTest();
		Check(ok_at && !at_record.view_hit && k3.hits == k2.hits && k3.misses == k2.misses && k3.entries == k2.entries &&
		          k3.bypassed == k2.bypassed + 1,
		      "V6 an AT (VERSION => n) search never touches the cache", ok_at ? "" : FirstLine(ea));
		Exec(client, "BEGIN TRANSACTION", "begin");
		qod::ResetLastSearchCallForTest();
		auto ok_tx = Try(client, sql, rt, et);
		auto tx_record = qod::LastSearchCallForTest();
		Exec(client, "COMMIT", "commit");
		auto k4 = qod::search::LivenessCacheCountersForTest();
		Check(ok_tx && !tx_record.view_hit && k4.hits == k3.hits && k4.misses == k3.misses && k4.entries == k3.entries &&
		          k4.bypassed == k3.bypassed + 1 && SameHits(Hits(rt), Hits(r3), true, why),
		      "V6 a search inside an explicit transaction never touches the cache, with identical results",
		      ok_tx ? why : FirstLine(et));
		// The allowed population, miss then hit.
		g_population = "allowed";
		qod::search::ClearLivenessCacheForTest();
		Rows a1, a2;
		qod::SearchCallRecord ac1, ac2;
		std::string ae1, ae2;
		auto okA1 = run(client, a1, ac1, ae1);
		auto okA2 = run(client, a2, ac2, ae2);
		g_population = "table";
		auto wantA = Reference(setup, "lake.main.vt", " WHERE f10 = 0", "SELECT rowid FROM lake.main.vt WHERE f10 = 0",
		                       "w1 w10", 10, rerr);
		std::string whyA, whyB;
		Check(okA1 && okA2 && !ac1.view_hit && ac2.view_hit && SameHits(Hits(a1), wantA, true, whyA) &&
		          SameHits(Hits(a2), Hits(a1), true, whyB),
		      "V6 the allowed population: miss then hit, identical, equal to the reference",
		      okA1 && okA2 ? whyA + whyB : FirstLine(ae1 + ae2));
		Say("   V6 cache: capacity " + std::to_string(k4.capacity) + ", entries " + std::to_string(k4.entries) + ", hits " +
		    std::to_string(k4.hits) + ", misses " + std::to_string(k4.misses) + ", bypassed " + std::to_string(k4.bypassed));
	}

	// V7: LiveDocs against the bundle's row count.
	{
		qod::search::FileLiveness live;
		live.words.assign(16, 0);
		for (int64_t p = 0; p < 1001; p += 3) {
			live.words[p >> 6] |= uint64_t(1) << (p & 63);
		}
		live.end = 1001;
		bool threw = false;
		std::string message;
		try {
			qod::search::LiveDocs(live, 1000);
		} catch (std::exception &e) {
			threw = true;
			message = ErrorData(e).Message();
		}
		Check(threw && message.find("QoD: search_snapshot_unavailable: liveness past the bundle's rows") != std::string::npos,
		      "V7 LiveDocs of end = 1001 against num_docs = 1000 throws liveness past the bundle's rows", message);
		live.words[1000 >> 6] &= ~(uint64_t(1) << (1000 & 63));
		live.end = 1000;
		auto docs = qod::search::LiveDocs(live, 1000);
		bool equal = docs.size() == 16;
		for (int64_t p = 0; equal && p < 1024; p++) {
			bool bit = (docs[p >> 6] >> (p & 63)) & 1;
			equal = bit == (p < 1000 && live.IsLive(p));
		}
		Check(equal, "V7 LiveDocs of end = 1000: 16 words whose bits equal IsLive for every position",
		      std::to_string(docs.size()) + " words");
	}

	// The bundle removal test (invariant 3, Task 11 F5): every .seg of vt deleted. With the fallback cap above
	// the table size (the default 200,000 against vt's 30,000 rows), every query equals the reference at the
	// current S and at old ones; with a cap below the live rows, search_index_behind naming the table.
	{
		auto folder = fs::path(data) / ".qod_search" / qod_test::TestIndexId("lake.main.vt");
		int removed = 0;
		std::error_code ec;
		for (auto &entry : fs::directory_iterator(folder, ec)) {
			if (entry.path().extension() == ".seg") {
				removed += fs::remove(entry.path(), ec) ? 1 : 0;
			}
		}
		qod::search::ClearSegmentCacheForTest();
		Check(removed >= 3, "V bundle removal: every .seg of vt deleted (" + std::to_string(removed) + ")");
		auto before = v.compared;
		VCompare(v, "V bundle removal, every row through the fallback (cap 200,000)", {-1, n3, n1, n0});
		Rows rows;
		std::string error;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.vt", "'w0'", "'lake.main.vt'"), rows, error);
		auto record = qod::LastSearchCallForTest();
		auto live = Int(setup, "SELECT count(*) FROM lake.main.vt");
		Check(ran && record.fallback_docs == static_cast<uint64_t>(live) && record.fallback_segments >= 1,
		      "V bundle removal: the fallback indexed every live row (" + std::to_string(live) + ")",
		      ran ? std::to_string(record.fallback_docs) + " docs in " + std::to_string(record.fallback_segments) +
		                " segments, " + std::to_string(record.fallback_ms) + " ms"
		          : FirstLine(error));
		Say("   V bundle removal: " + std::to_string(v.compared - before) + " searches through the fallback; fallback of " +
		    std::to_string(record.fallback_docs) + " rows in " + std::to_string(record.fallback_segments) + " segments: " +
		    std::to_string(record.fallback_ms) + " ms, " + std::to_string(record.fallback_bytes) + " bytes");
		qod::search::SetMaxUnindexedRowsForTest(static_cast<uint64_t>(live) - 1);
		RefusedAtRun(client, "V bundle removal with a cap of the live rows less one",
		             Search("SELECT rowid FROM lake.main.vt", "'w0'", "'lake.main.vt'"),
		             "QoD: search_index_behind: lake.main.vt has " + std::to_string(live) + " unindexed live rows (cap " +
		                 std::to_string(live - 1) + "); retry once the indexer catches up");
		qod::search::SetMaxUnindexedRowsForTest(200000);
	}
	std::ostringstream rel;
	rel << std::scientific << v.max_rel;
	Say("   V compared " + std::to_string(v.compared) + " searches with the reference, max relative score error " +
	    rel.str());
	Check(v.max_rel == 0 && v.compared > 0, "V every compared score is bit-identical to the reference");
	g_population = "table";
}

//! One search on `client`, its rows and record.
bool VSearch(Connection &client, const std::string &sql, Rows &rows, qod::SearchCallRecord &record, std::string &error) {
	qod::ResetLastSearchCallForTest();
	auto ran = Try(client, sql, rows, error);
	record = qod::LastSearchCallForTest();
	return ran && record.called;
}

//! V9 (Task 10 fix round 1): the cache's key and bounds, and a catalog pinned at a snapshot, on a second
//! lake with a SQLite catalog (a DuckDB-file catalog cannot be attached twice in one instance):
//! plake.main.pt (2 files) and plake.main.pt2 (1 file), and plake_pin, the same catalog attached
//! READ_ONLY at SNAPSHOT_VERSION S_p.
void RunCacheKeysAndPins(Connection &setup, Connection &sysc, Connection &client, const fs::path &base,
                         const std::string &root) {
	if (setup.Query("INSTALL sqlite")->HasError() || setup.Query("LOAD sqlite")->HasError()) {
		Say("   V9 skipped: the sqlite extension is not available");
		return;
	}
	auto meta = (base / "pmeta.sqlite").string();
	fs::create_directories(base / "pdata");
	auto pdata = fs::canonical(base / "pdata").string() + "/";
	auto attach = [&](const std::string &alias, const std::string &options) {
		return Exec(setup, "ATTACH " + Quote("ducklake:sqlite:" + meta) + " AS " + alias + " (DATA_PATH " + Quote(pdata) +
		                       options + ")",
		            "attach " + alias);
	};
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 200)::BIGINT), ' ')";
	auto insert = [&](const std::string &table, int64_t from, int64_t to) {
		return Exec(setup,
		            "INSERT INTO plake.main." + table + " SELECT i, " + body + ", (i % 10)::INTEGER FROM range(" +
		                std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i",
		            table + " rows");
	};
	bool ok = attach("plake", ", DATA_INLINING_ROW_LIMIT 10") &&
	          Exec(setup, "CREATE TABLE plake.main.pt (id BIGINT, body VARCHAR, f10 INTEGER)", "pt") &&
	          Exec(setup, "CREATE TABLE plake.main.pt2 (id BIGINT, body VARCHAR, f10 INTEGER)", "pt2") &&
	          insert("pt", 0, 500) && insert("pt", 500, 1000) && insert("pt2", 0, 700) &&
	          IndexTable(setup, "pt", {"body"}, root, "plake") && IndexTable(setup, "pt2", {"body"}, root, "plake");
	if (!ok) {
		return;
	}
	std::set<std::string> built_pt, built_pt2, built_pin, snapshots;
	int built = 0;
	ok = VBuildTable(setup, sysc, "plake", "pt", "plake.main.pt", root, built_pt, built, snapshots) &&
	     VBuildTable(setup, sysc, "plake", "pt2", "plake.main.pt2", root, built_pt2, built, snapshots);
	if (!ok) {
		return;
	}
	auto search = [](const std::string &table, const std::string &query) {
		return Search("SELECT rowid FROM " + table, Quote(query), Quote(table));
	};
	auto equals_reference = [&](const Rows &rows, const std::string &table, const std::string &at, const std::string &query,
	                            std::string &why) {
		std::string rerr;
		auto want = Reference(setup, table, at, "SELECT rowid FROM " + table + at, query, 10, rerr);
		return rerr.empty() && SameHits(Hits(rows), want, true, why);
	};
	using qod::search::LivenessCacheCountersForTest;

	// Two tables of one lake searched alternately at one S: each its own entry, each its own answer.
	{
		qod::search::ClearLivenessCacheForTest();
		auto k0 = LivenessCacheCountersForTest();
		int good = 0, hits = 0;
		std::string first;
		for (int round = 0; round < 2; round++) {
			for (auto table : {std::string("plake.main.pt"), std::string("plake.main.pt2")}) {
				Rows rows;
				qod::SearchCallRecord record;
				std::string error, why;
				auto ran = VSearch(client, search(table, "w1 w10"), rows, record, error);
				if (ran && !rows.empty() && equals_reference(rows, table, "", "w1 w10", why)) {
					good++;
				} else if (first.empty()) {
					first = table + ": " + (ran ? why : FirstLine(error));
				}
				hits += round == 1 && record.view_hit;
			}
		}
		auto k1 = LivenessCacheCountersForTest();
		Check(good == 4 && hits == 2 && k1.misses == k0.misses + 2 && k1.hits == k0.hits + 2 && k1.entries == 2,
		      "V9 two tables at one S, alternately: two entries, each search equal to its own table's reference, "
		      "the second round hits",
		      first + " (hits " + std::to_string(hits) + ", entries " + std::to_string(k1.entries) + ")");
	}
	// A newer S replaces the table's entry: one entry per table, never one per S.
	{
		auto k0 = LivenessCacheCountersForTest();
		insert("pt2", 700, 720); // a commit on another table: a new S for every table of the lake
		VBuildTable(setup, sysc, "plake", "pt2", "plake.main.pt2", root, built_pt2, built, snapshots);
		Rows rows;
		qod::SearchCallRecord record;
		std::string error, why;
		auto ran = VSearch(client, search("plake.main.pt", "w1 w10"), rows, record, error);
		auto k1 = LivenessCacheCountersForTest();
		Check(ran && !record.view_hit && k1.replaced == k0.replaced + 1 && k1.entries == k0.entries &&
		          equals_reference(rows, "plake.main.pt", "", "w1 w10", why),
		      "V9 a new S replaces pt's entry (replaced +1, entries unchanged), equal to the reference",
		      ran ? why + " (entries " + std::to_string(k0.entries) + " to " + std::to_string(k1.entries) + ")"
		          : FirstLine(error));
	}
	// The byte bound: evicts by bytes, never stores an entry larger than it, and 0 turns the cache off.
	{
		qod::search::ClearLivenessCacheForTest();
		Rows rows;
		qod::SearchCallRecord pt, pt2;
		std::string error;
		VSearch(client, search("plake.main.pt", "w0"), rows, pt, error);
		VSearch(client, search("plake.main.pt2", "w0"), rows, pt2, error);
		auto pt_bytes = pt.view ? qod::search::LivenessViewBytes(*pt.view) : 0;
		auto pt2_bytes = pt2.view ? qod::search::LivenessViewBytes(*pt2.view) : 0;
		Check(pt_bytes > 0 && pt2_bytes > 0, "V9 entry sizes",
		      "pt " + std::to_string(pt_bytes) + " bytes, pt2 " + std::to_string(pt2_bytes) + " bytes");
		// Review M9: each bitset is shrunk to fit, so the counted bytes are the allocated ones (capacity); a stored
		// view's words are in its buffer-manager block (Task 12), exactly as many as resolved.
		for (auto record : {&pt, &pt2}) {
			bool fitted = record->view != nullptr;
			uint64_t allocated = 0;
			if (record->view) {
				for (auto &live : record->view->liveness) {
					fitted = fitted && (live.block_words ? live.words.empty() && live.block_count * 64 >= uint64_t(live.end) &&
					                                            live.block_count == size_t((live.end + 63) >> 6)
					                                      : live.words.capacity() == live.words.size());
					allocated += (live.block_words ? live.block_count : live.words.capacity()) * sizeof(uint64_t);
				}
				for (auto &file : record->view->files) {
					allocated += file.path.size() + file.bundle_key.size();
				}
				for (auto &file : record->view->delete_files) {
					allocated += file.path.size() + file.data_file_path.size();
				}
			}
			Check(fitted && record->view && qod::search::LivenessViewBytes(*record->view) == allocated,
			      "V9 every bitset is shrunk to fit and the counted bytes equal the capacity-based bytes",
			      std::to_string(allocated) + " bytes");
		}
		// Room for the larger alone: the second table's entry evicts the first's.
		qod::search::SetLivenessCacheBoundsForTest(64, std::max(pt_bytes, pt2_bytes));
		qod::search::ClearLivenessCacheForTest();
		auto k0 = LivenessCacheCountersForTest();
		qod::SearchCallRecord r1, r2, r3;
		VSearch(client, search("plake.main.pt", "w0"), rows, r1, error);
		VSearch(client, search("plake.main.pt2", "w0"), rows, r2, error);
		VSearch(client, search("plake.main.pt", "w0"), rows, r3, error);
		auto k1 = LivenessCacheCountersForTest();
		Check(!r1.view_hit && !r2.view_hit && !r3.view_hit && k1.evictions >= k0.evictions + 2 && k1.entries == 1 &&
		          k1.bytes <= k1.byte_bound,
		      "V9 a byte bound holding one entry: each table's entry evicts the other's, by bytes",
		      "evictions " + std::to_string(k1.evictions - k0.evictions) + ", entries " + std::to_string(k1.entries) +
		          ", bytes " + std::to_string(k1.bytes) + " of " + std::to_string(k1.byte_bound));
		qod::search::SetLivenessCacheBoundsForTest(64, pt_bytes - 1);
		qod::search::ClearLivenessCacheForTest();
		k0 = LivenessCacheCountersForTest();
		VSearch(client, search("plake.main.pt", "w0"), rows, r1, error);
		VSearch(client, search("plake.main.pt", "w0"), rows, r2, error);
		k1 = LivenessCacheCountersForTest();
		Check(!r1.view_hit && !r2.view_hit && k1.too_large == k0.too_large + 2 && k1.entries == 0,
		      "V9 an entry larger than the byte bound is never stored", "too large " +
		                                                                std::to_string(k1.too_large - k0.too_large));
		qod::search::SetLivenessCacheBoundsForTest(64, 0);
		k0 = LivenessCacheCountersForTest();
		std::string why;
		auto ran = VSearch(client, search("plake.main.pt", "w0"), rows, r1, error);
		VSearch(client, search("plake.main.pt", "w0"), rows, r2, error);
		k1 = LivenessCacheCountersForTest();
		Check(ran && !r1.view_hit && !r2.view_hit && k1.bypassed == k0.bypassed + 2 && k1.misses == k0.misses &&
		          k1.entries == 0 && equals_reference(rows, "plake.main.pt", "", "w0", why),
		      "V9 a byte bound of 0 turns the cache off, the search unchanged", ran ? why : FirstLine(error));
		qod::search::SetLivenessCacheBoundsForTest(64, uint64_t(256) << 20);
	}
	// DETACH and ATTACH again under the same alias: a new catalog oid, so a miss.
	{
		Rows rows;
		qod::SearchCallRecord r1, r2;
		std::string error, why;
		VSearch(client, search("plake.main.pt", "w1 w10"), rows, r1, error);
		Exec(setup, "DETACH plake", "detach plake");
		attach("plake", ", DATA_INLINING_ROW_LIMIT 10");
		auto ran = VSearch(client, search("plake.main.pt", "w1 w10"), rows, r2, error);
		Check(ran && !r2.view_hit && r1.view && r2.view && r1.view->snapshot_id == r2.view->snapshot_id &&
		          equals_reference(rows, "plake.main.pt", "", "w1 w10", why),
		      "V9 DETACH and ATTACH again at the same S: a miss (a new catalog oid), equal to the reference",
		      ran ? why : FirstLine(error));
	}
	// I1: a catalog attached at a fixed snapshot is never served from the cache, so another writer's merge,
	// which changes what that S lists, is seen.
	{
		auto pinned_at = Int(setup, "SELECT id FROM ducklake_current_snapshot('plake')");
		if (!attach("plake_pin", ", SNAPSHOT_VERSION " + std::to_string(pinned_at) + ", READ_ONLY")) {
			return;
		}
		// The pinned alias's own record (its own index id), the table id read through the writable alias.
		IndexTable(setup, "pt", {"body"}, root, "plake_pin", "plake_pin.main.pt", "plake");
		VBuildTable(setup, sysc, "plake", "pt", "plake_pin.main.pt", root, built_pin, built, snapshots);
		auto listed_at = [&](int64_t version) {
			Rows rows;
			std::string error;
			std::set<std::string> out;
			Try(setup, "SELECT data_file FROM ducklake_list_files('plake', 'pt', snapshot_version => " +
			               std::to_string(version) + ")",
			    rows, error);
			for (auto &r : rows) {
				out.insert(r[0].ToString());
			}
			return out;
		};
		auto files_of = [](const qod::SearchCallRecord &record) {
			std::set<std::string> out;
			if (record.view) {
				for (auto &f : record.view->files) {
					out.insert(f.path);
				}
			}
			return out;
		};
		auto at = " AT (VERSION => " + std::to_string(pinned_at) + ")";
		Rows r1, r2, r3;
		qod::SearchCallRecord c1, c2, c3;
		std::string e1, e2, e3, why1, why2;
		auto k0 = LivenessCacheCountersForTest();
		auto ok1 = VSearch(client, search("plake_pin.main.pt", "w1 w10"), r1, c1, e1);
		auto ok2 = VSearch(client, search("plake_pin.main.pt", "w1 w10"), r2, c2, e2);
		auto k1 = LivenessCacheCountersForTest();
		Check(ok1 && ok2 && !c1.view_hit && !c2.view_hit && k1.bypassed == k0.bypassed + 2 && k1.misses == k0.misses &&
		          c1.view->snapshot_id == pinned_at && files_of(c1) == listed_at(pinned_at) &&
		          equals_reference(r1, "plake.main.pt", at, "w1 w10", why1),
		      "V9 a catalog attached READ_ONLY at SNAPSHOT_VERSION " + std::to_string(pinned_at) +
		          ": bypassed twice, at its S, equal to the reference there",
		      ok1 && ok2 ? why1 : FirstLine(e1 + e2));
		Exec(setup, "CALL ducklake_merge_adjacent_files('plake')", "V9 another writer's merge");
		VBuildTable(setup, sysc, "plake", "pt", "plake_pin.main.pt", root, built_pin, built, snapshots);
		auto ok3 = VSearch(client, search("plake_pin.main.pt", "w1 w10"), r3, c3, e3);
		auto now_listed = listed_at(pinned_at);
		Check(ok3 && !c3.view_hit && c3.view->snapshot_id == pinned_at && files_of(c3) == now_listed &&
		          files_of(c3) != files_of(c1) && equals_reference(r3, "plake.main.pt", at, "w1 w10", why2) &&
		          SameHits(Hits(r3), Hits(r1), true, why2),
		      "V9 after another writer's merge, the pinned search lists what S lists now (" +
		          std::to_string(files_of(c1).size()) + " files then " + std::to_string(files_of(c3).size()) +
		          "), never a cached view, with the same answer",
		      ok3 ? why2 : FirstLine(e3));
		Exec(setup, "DETACH plake_pin", "detach plake_pin");
	}
}

// ---- F: the fallback for unindexed files and inlined rows (Task 11, spec 9, 7.5, 6.3) --------------


//! The 4 queries of kVQueries in VCompare's 3 cells over `table` at `at` (the table population over the whole
//! table and over WHERE f10 = 0, the allowed population over WHERE f10 = 0), each against the reference.
void FCompare(Connection &setup, Connection &client, const std::string &table, const std::string &at,
              const std::string &step, double &max_rel, int &compared) {
	int pass = 0, total = 0;
	std::string first_failure;
	struct Cell {
		std::string name, population, input, reference_at;
	};
	std::vector<Cell> cells = {
	    {"table", "table", "SELECT rowid FROM " + table + at, at},
	    {"table, f10 = 0", "table", "SELECT rowid FROM " + table + at + " WHERE f10 = 0", at},
	    {"allowed, f10 = 0", "allowed", "SELECT rowid FROM " + table + at + " WHERE f10 = 0", at + " WHERE f10 = 0"},
	};
	for (auto &query : kVQueries) {
		for (auto &cell : cells) {
			total++;
			g_population = cell.population;
			Rows rows;
			std::string error, rerr, why;
			auto ran = Try(client, Search(cell.input, Quote(query), Quote(table)), rows, error);
			g_population = "table";
			auto want = Reference(setup, table, cell.reference_at, cell.input, query, 10, rerr);
			auto got = Hits(rows);
			if (ran && rerr.empty() && SameHits(got, want, true, why)) {
				pass++;
				compared++;
				max_rel = std::max(max_rel, MaxRel(got, want));
			} else if (first_failure.empty()) {
				first_failure = "'" + query + "' " + cell.name + ": " + (ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
			}
		}
	}
	Check(pass == total, step + ": " + std::to_string(total) + " searches (4 queries, table and allowed populations) "
	                                                          "equal the reference",
	      first_failure);
}


std::string Ms(double ms) {
	std::ostringstream out;
	out.precision(3);
	out << std::fixed << ms;
	return out.str();
}

//! F1 to F8 on lake.main.ft (id, body, f10): file A of 10,000 rows (bundled), file B of 1,500 (not yet), then
//! inlined rows, an update and the cap's edge on lake.main.big.
void RunFallback(DuckDB &db, Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	g_population = "table";
	g_bundle_root = data;
	qod::search::SetMaxUnindexedRowsForTest(200000);
	const std::string ft = "lake.main.ft";
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	auto insert = [&](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.ft SELECT i, " + body + ", (i % 10)::INTEGER FROM range(" + std::to_string(from) +
		       ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	int64_t files = 0;
	if (!Exec(setup, "CREATE TABLE lake.main.ft (id BIGINT, body VARCHAR, f10 INTEGER)", "ft") || !IndexTable(setup, "ft") ||
	    !Exec(setup, insert(0, 10000), "ft file A") || !BuildSegments(setup, sysc, "ft", data, files) ||
	    !Exec(setup, insert(10000, 11500), "ft file B (no bundle)")) {
		return;
	}
	auto file_b = Rows();
	std::string error;
	Try(setup, "SELECT data_file FROM ducklake_list_files('lake', 'ft') ORDER BY data_file", file_b, error);
	Rows unbundled;
	Try(setup, "SELECT DISTINCT filename FROM lake.main.ft WHERE id >= 10000", unbundled, error);
	auto path_b = unbundled.size() == 1 ? unbundled[0][0].ToString() : std::string();
	Check(files == 1 && file_b.size() == 2 && !path_b.empty() && !g_built.count(path_b),
	      "F the fixture: ft in 2 data files, A bundled, B (1,500 rows) not", std::to_string(file_b.size()) + " files");
	double max_rel = 0;
	int compared = 0;
	const std::string all = Search("SELECT rowid FROM lake.main.ft", "'w1 w10'", Quote(ft));

	// F4: a cap of 1,000 against B's 1,500 live rows: refused before any row is read, naming the table and the
	// backlog, never the file.
	{
		qod::search::SetMaxUnindexedRowsForTest(1000);
		auto scans = qod::search::TextScansForTest();
		auto counters = qod::search::FallbackCountersForTest();
		Rows rows;
		std::string err;
		auto ran = Try(client, all, rows, err);
		auto after = qod::search::FallbackCountersForTest();
		Check(!ran && rows.empty() &&
		          err.find("QoD: search_index_behind: lake.main.ft has 1500 unindexed live rows (cap 1000); retry once the indexer "
		                   "catches up") != std::string::npos,
		      "F4 a cap of 1,000 with 1,500 unindexed rows: search_index_behind naming the table and 1,500, no row",
		      ran ? "it ran" : FirstLine(err));
		Check(qod::search::TextScansForTest() == scans && after.rows == counters.rows &&
		          after.segments == counters.segments && after.refused == counters.refused + 1,
		      "F4 refused before any row is read: the fallback's scan is never driven",
		      std::to_string(qod::search::TextScansForTest() - scans) + " scans, " +
		          std::to_string(after.rows - counters.rows) + " rows");
		Check(err.find(path_b) == std::string::npos && err.find(".parquet") == std::string::npos &&
		          err.find(data) == std::string::npos,
		      "F4 the refusal names no file path");
		qod::search::SetMaxUnindexedRowsForTest(1500);
		qod::ResetLastSearchCallForTest();
		ran = Try(client, all, rows, err);
		Check(ran && rows.size() == 10 && qod::LastSearchCallForTest().fallback_docs == 1500,
		      "F4 a cap equal to the backlog (1,500) answers", ran ? "" : FirstLine(err));
		qod::search::SetMaxUnindexedRowsForTest(200000);
	}

	// X1, X2 (Task 10, ruling 7, OWNER RULING 2026-11-16 D5): the rows cap's search_index_behind is a Permission
	// Error, which no user SQL raises with a chosen text, so the manager can answer it UNAVAILABLE. In the table
	// population it names the backlog; in the allowed population no count of unindexed rows, which would count
	// hidden rows: the same text with the count removed, holding no digit but the cap's.
	{
		qod::search::SetMaxUnindexedRowsForTest(1000);
		const std::string kTableForm = "Permission Error: QoD: search_index_behind: lake.main.ft has 1500 unindexed "
		                               "live rows (cap 1000); retry once the indexer catches up";
		const std::string kCount = "1500 unindexed live rows";
		std::string allowed_form = kTableForm;
		allowed_form.replace(allowed_form.find(kCount), kCount.size(), "more unindexed rows than the search reads");
		const std::string filtered = Search("SELECT rowid FROM lake.main.ft WHERE f10 = 0", "'w1 w10'", Quote(ft));
		auto first = [&](const std::string &sql, size_t &scans_moved) {
			auto scans = qod::search::TextScansForTest();
			Rows rows;
			std::string err;
			auto ran = Try(client, sql, rows, err);
			scans_moved = qod::search::TextScansForTest() - scans;
			return ran ? std::string("it ran") : FirstLine(err);
		};
		size_t moved = 0;
		auto table_all = first(all, moved);
		Check(table_all == kTableForm && moved == 0,
		      "X1 the rows cap in the table population: a Permission Error naming the table, the backlog and the cap",
		      table_all);
		auto table_filtered = first(filtered, moved);
		Check(table_filtered == kTableForm && moved == 0, "X1 the same text for a filtered input (the table population)",
		      table_filtered);
		g_population = "allowed";
		size_t moved_all = 0;
		auto allowed_all = first(all, moved_all);
		auto allowed_filtered = first(filtered, moved);
		g_population = "table";
		auto digits = allowed_filtered;
		if (digits.find("(cap 1000)") != std::string::npos) {
			digits.erase(digits.find("(cap 1000)"), 10);
		}
		Check(allowed_filtered == allowed_form && allowed_all == allowed_form && moved == 0 && moved_all == 0 &&
		          digits.find_first_of("0123456789") == std::string::npos,
		      "X2 the rows cap in the allowed population: the table's text with the count removed, no digit but the cap's",
		      allowed_filtered + " | " + allowed_all);
		qod::search::SetMaxUnindexedRowsForTest(200000);
	}

	// X4 (Task 10 fix round 1, security m1): a fallback source whose scan returns another count of rows than its
	// liveness at S holds fails closed with search_snapshot_unavailable; the table population's text carries both
	// figures, the allowed population's neither, as they count rows the population hides. The test hook counts one
	// more live row for the first source than the view holds, so the scan returns one row short.
	{
		qod::search::InflateFallbackLivenessForTest(true);
		const std::string kTableForm = "Invalid Input Error: QoD: search_snapshot_unavailable: the fallback scan of "
		                               "lake.main.ft returned 1500 rows of a source where its liveness at S holds 1501";
		const std::string kAllowedForm = "Invalid Input Error: QoD: search_snapshot_unavailable: the fallback scan of "
		                                 "lake.main.ft returned another count of rows of a source than its liveness at "
		                                 "S holds";
		std::vector<std::string> got;
		for (auto population : {"table", "allowed"}) {
			g_population = population;
			Rows rows;
			std::string err;
			got.push_back(Try(client, all, rows, err) ? std::string("it ran") : FirstLine(err));
		}
		g_population = "table";
		qod::search::InflateFallbackLivenessForTest(false);
		Check(got[0] == kTableForm, "X4 a source's row count off its liveness, the table population: both figures",
		      got[0]);
		Check(got[1] == kAllowedForm && got[1].find_first_of("0123456789") == std::string::npos,
		      "X4 the same in the allowed population: no figure, no digit", got[1]);
		Rows rows;
		std::string err;
		auto ran = Try(client, all, rows, err);
		Check(ran && rows.size() == 10, "X4 the hook off: the search answers again", ran ? "" : FirstLine(err));
	}

	// F1: one indexed and one unindexed file: every cell equals the reference, B through one fallback segment.
	{
		qod::ResetLastSearchCallForTest();
		qod::search::RecordFallbackRowidsForTest(true);
		Rows rows;
		std::string err;
		auto ran = Try(client, all, rows, err);
		auto record = qod::LastSearchCallForTest();
		auto recorded = qod::search::FallbackRowidsForTest();
		qod::search::RecordFallbackRowidsForTest(false);
		Rows truth;
		Try(setup, "SELECT rowid FROM lake.main.ft WHERE filename = " + Quote(path_b) + " ORDER BY rowid", truth, err);
		std::vector<int64_t> want;
		for (auto &r : truth) {
			want.push_back(r[0].GetValue<int64_t>());
		}
		Check(ran && record.fallback_segments == 1 && record.fallback_docs == 1500 && recorded == want,
		      "F1 B's 1,500 live rows form one fallback segment, under their own rowids",
		      std::to_string(record.fallback_segments) + " segments, " + std::to_string(record.fallback_docs) + " docs");
		Say("   F1 fallback of B: 1,500 rows, " + Ms(record.fallback_ms) + " ms, " + std::to_string(record.fallback_bytes) +
		    " bytes");
		for (int i = 0; i < 3; i++) {
			auto before = qod::search::FallbackCountersForTest();
			qod::ResetLastSearchCallForTest();
			Try(client, all, rows, err);
			auto index_ms = double(qod::search::FallbackCountersForTest().index_ns - before.index_ns) / 1e6;
			Say("   F1 run " + std::to_string(i + 1) + ": fallback " + Ms(qod::LastSearchCallForTest().fallback_ms) +
			    " ms (indexing " + Ms(index_ms) + " ms, the target scan the rest)");
		}
		FCompare(setup, client, ft, "", "F1 one indexed and one unindexed file", max_rel, compared);
	}

	// F7: the fallback counts against QOD_SEARCH_QUERY_MEMORY; F7b: an interrupt between sources.
	{
		qod::SetSearchMemoryForTest(16384);
		Rows rows;
		std::string err;
		auto ran = Try(client, all, rows, err);
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Check(!ran && rows.empty() && err.find("QoD: search_memory_exceeded") != std::string::npos,
		      "F7 a 16 KiB query budget: the fallback fails with search_memory_exceeded, no row",
		      ran ? "it ran" : FirstLine(err));
		auto counters = qod::search::FallbackCountersForTest();
		qod::search::FallbackSourceObserverForTest([](ClientContext &context) { context.Interrupt(); });
		ran = Try(client, all, rows, err);
		qod::search::FallbackSourceObserverForTest(nullptr);
		Check(!ran && rows.empty() && err.find("INTERRUPT") != std::string::npos &&
		          qod::search::FallbackCountersForTest().segments == counters.segments,
		      "F7b an interrupt before the fallback's scan: an interrupt error, no row, no segment built",
		      ran ? "it ran" : FirstLine(err));
	}

	// F6: ROWS_M4. The fallback is one target scan (TextScansForTest + 1), and the files opened while it runs,
	// on a connection whose FileSystem counts every open, are the target's listed files only (B among them).
	{
		Connection counted(db);
		auto wrapped = WrapClientFileSystem(counted);
		if (wrapped && Install(counted, "acme/acme_db/bi")) {
			Rows listed;
			std::string err;
			Try(setup, "SELECT data_file, delete_file FROM ducklake_list_files('lake', 'ft')", listed, err);
			std::set<std::string> own;
			for (auto &r : listed) {
				own.insert(r[0].ToString());
				if (!r[1].IsNull()) {
					own.insert(r[1].ToString());
				}
			}
			qod::search::FallbackScanObserverForTest([](bool begin) {
				std::lock_guard<std::mutex> lock(g_fs_mutex);
				if (begin) {
					g_fs_opens.clear();
				}
				g_fs_counting = begin;
			});
			auto scans = qod::search::TextScansForTest();
			Rows rows;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(counted, all, rows, err);
			qod::search::FallbackScanObserverForTest(nullptr);
			std::map<std::string, idx_t> opens;
			{
				std::lock_guard<std::mutex> lock(g_fs_mutex);
				g_fs_counting = false;
				opens = g_fs_opens;
			}
			std::string stray;
			for (auto &o : opens) {
				if (!own.count(o.first) && stray.empty()) {
					stray = o.first;
				}
			}
			Check(ran && qod::LastSearchCallForTest().fallback_docs == 1500 && qod::search::TextScansForTest() == scans + 1 &&
			          opens.count(path_b) && stray.empty(),
			      "F6 the fallback is one target scan, which opens only the target's listed files (B included)",
			      ran ? std::to_string(qod::search::TextScansForTest() - scans) + " scans, " + std::to_string(opens.size()) +
			                " paths opened" + (stray.empty() ? "" : ", stray " + stray)
			          : FirstLine(err));
		} else {
			Check(false, "F6 the counting connection", wrapped ? "no prelude" : "the client FileSystem cannot be wrapped");
		}
	}

	// B bundled: no fallback, the same answers.
	BuildSegments(setup, sysc, "ft", data, files);
	{
		qod::ResetLastSearchCallForTest();
		Rows rows;
		std::string err;
		Try(client, all, rows, err);
		Check(qod::LastSearchCallForTest().fallback_segments == 0, "F with B bundled, no fallback segment");
		FCompare(setup, client, ft, "", "F with both files bundled", max_rel, compared);
	}

	// F2: 5 inlined rows (DATA_INLINING_ROW_LIMIT 10), found with no flush; live at S by their begin and end
	// snapshots; one of them deleted, at the current S and at the S before the delete.
	{
		Exec(setup,
		     "INSERT INTO lake.main.ft VALUES (20000, 'zqa w1', 0), (20001, 'zqa w10', 1), (20002, 'zqa', 0), (20003, "
		     "'zqa w1 w10', 3), (20004, 'zqa zqa', 4)",
		     "F2 5 inlined rows");
		auto s_insert = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		Check(Int(setup, "SELECT count(*) FROM lake.main.ft WHERE filename LIKE 'ducklake_inlined_data_%'") == 5,
		      "F2 the 5 rows are inlined");
		auto table_id = Int(setup, "SELECT table_id FROM __ducklake_metadata_lake.ducklake_table WHERE table_name = 'ft' "
		                           "AND end_snapshot IS NULL");
		Rows names;
		Try(setup,
		    "SELECT table_name FROM __ducklake_metadata_lake.ducklake_inlined_data_tables WHERE table_id = " +
		        std::to_string(table_id),
		    names, error);
		auto truth = [&](int64_t s) {
			std::vector<int64_t> out;
			for (auto &n : names) {
				Rows rows;
				std::string err;
				Try(setup,
				    "SELECT _ducklake_row_id FROM __ducklake_metadata_lake." + n[0].ToString() +
				        " WHERE _ducklake_begin_snapshot <= " + std::to_string(s) +
				        " AND (_ducklake_end_snapshot IS NULL OR _ducklake_end_snapshot > " + std::to_string(s) + ")",
				    rows, err);
				if (!err.empty()) {
					Check(false, "F2 read " + n[0].ToString(), FirstLine(err));
				}
				for (auto &r : rows) {
					out.push_back(r[0].GetValue<int64_t>());
				}
			}
			std::sort(out.begin(), out.end());
			return out;
		};
		auto found = [&](const std::string &at, const std::string &label, int64_t s, size_t expected) {
			qod::ResetLastSearchCallForTest();
			qod::search::RecordFallbackRowidsForTest(true);
			Rows rows;
			std::string err, rerr, why;
			auto sql = Search("SELECT rowid FROM lake.main.ft" + at, "'zqa'", Quote(ft));
			auto ran = Try(client, sql, rows, err);
			auto record = qod::LastSearchCallForTest();
			auto recorded = qod::search::FallbackRowidsForTest();
			qod::search::RecordFallbackRowidsForTest(false);
			auto want = Reference(setup, ft, at, "SELECT rowid FROM lake.main.ft" + at, "zqa", 10, rerr);
			auto live = truth(s);
			Check(ran && rows.size() == expected && rerr.empty() && SameHits(Hits(rows), want, true, why),
			      "F2 " + label + ": 'zqa' finds the " + std::to_string(expected) + " live inlined rows, equal to the "
			                                                                      "reference",
			      ran ? why + rerr : FirstLine(err));
			Check(record.fallback_segments == names.size() && recorded == live && live.size() == expected,
			      "F2 " + label + ": the fallback's docs are the inlined rows with begin_snapshot <= S and end_snapshot "
			                      "NULL or > S",
			      std::to_string(recorded.size()) + " docs, " + std::to_string(live.size()) + " in the catalog");
			Say("   F2 " + label + ": fallback of " + std::to_string(record.fallback_docs) + " inlined rows, " +
			    Ms(record.fallback_ms) + " ms");
		};
		Check(names.size() == 1, "F2 one inlined data table", std::to_string(names.size()));
		found("", "at the current S", s_insert, 5);
		FCompare(setup, client, ft, "", "F2 with 5 inlined rows", max_rel, compared);
		Exec(setup, "DELETE FROM lake.main.ft WHERE id = 20002", "F2 delete an inlined row");
		auto s_delete = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		found("", "after a delete of one", s_delete, 4);
		found(At(s_insert), "at VERSION " + std::to_string(s_insert) + ", before the delete", s_insert, 5);
		FCompare(setup, client, ft, At(s_insert), "F2 at the S before the delete", max_rel, compared);
	}

	// F3: the update path of 6.3 on a bundled file row: the file doc dead by an inlined delete, the new value a
	// fallback doc with the same rowid. Exactly one hit for that rowid.
	{
		auto rowid = Int(setup, "SELECT rowid FROM lake.main.ft WHERE id = 42");
		Rows old;
		Try(setup, "SELECT split_part(body, ' ', 1) FROM lake.main.ft WHERE id = 42", old, error);
		auto old_word = old.empty() ? std::string("w0") : old[0][0].ToString();
		Exec(setup, "UPDATE lake.main.ft SET body = 'zqb updated' WHERE id = 42", "F3 update a file row");
		auto table_id = Int(setup, "SELECT table_id FROM __ducklake_metadata_lake.ducklake_table WHERE table_name = 'ft' "
		                           "AND end_snapshot IS NULL");
		Check(Int(setup, "SELECT rowid FROM lake.main.ft WHERE id = 42") == rowid &&
		          Int(setup, "SELECT count(*) FROM __ducklake_metadata_lake.ducklake_inlined_delete_" +
		                         std::to_string(table_id)) >= 1,
		      "F3 the update is an inlined delete and an inlined row with the file row's rowid " + std::to_string(rowid));
		auto one = [&](const std::string &query, size_t expected, const std::string &label) {
			Rows rows;
			std::string err;
			auto ran = Try(client,
			               Search("SELECT rowid FROM lake.main.ft WHERE id = 42", Quote(query), Quote(ft), kCols, ", k := 10000"),
			               rows, err);
			size_t of_rowid = 0;
			for (auto &r : rows) {
				of_rowid += r[0].GetValue<int64_t>() == rowid;
			}
			Check(ran && rows.size() == expected && of_rowid == expected, "F3 " + label,
			      ran ? Show(Hits(rows)) : FirstLine(err));
		};
		one("zqb " + old_word, 1, "'zqb " + old_word + "': exactly one hit for rowid " + std::to_string(rowid) +
		                              ", the fallback doc");
		one(old_word, 0, "'" + old_word + "' alone: no hit (the file doc is dead at S)");
		FCompare(setup, client, ft, "", "F3 after the update", max_rel, compared);
	}

	// F8: the cap's edge on lake.main.big, 200,000 rows with its bundles removed: answered at the default cap of
	// 200,000 (the cost of a full-cap fallback), refused at 199,999.
	{
		auto removed = RemoveBundles(data, "lake.main.big");
		auto live = Int(setup, "SELECT count(*) FROM lake.main.big");
		const std::string input = "SELECT rowid FROM lake.main.big WHERE id % 100 = 0";
		auto sql = Search(input, "'w1 w10'", "'lake.main.big'");
		qod::ResetLastSearchCallForTest();
		Rows rows;
		std::string err, rerr, why;
		auto ran = Try(client, sql, rows, err);
		auto record = qod::LastSearchCallForTest();
		auto want = Reference(setup, "lake.main.big", "", input, "w1 w10", 10, rerr);
		Check(removed >= 1 && live == 200000 && ran && record.fallback_docs == 200000 && rerr.empty() &&
		          SameHits(Hits(rows), want, true, why),
		      "F8 big's 200,000 rows with no bundle, at the default cap of 200,000: through the fallback, equal to the "
		      "reference",
		      ran ? why + rerr : FirstLine(err));
		Say("   F8 fallback of big: " + std::to_string(record.fallback_docs) + " rows in " +
		    std::to_string(record.fallback_segments) + " segments, " + Ms(record.fallback_ms) + " ms, " +
		    std::to_string(record.fallback_bytes) + " bytes");
		for (int i = 0; i < 3; i++) {
			qod::ResetLastSearchCallForTest();
			auto before = qod::search::FallbackCountersForTest();
			auto t0 = std::chrono::steady_clock::now();
			Try(client, sql, rows, err);
			auto total = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
			auto again = qod::LastSearchCallForTest();
			auto index_ms = double(qod::search::FallbackCountersForTest().index_ns - before.index_ns) / 1e6;
			Say("   F8 run " + std::to_string(i + 1) + ": fallback " + Ms(again.fallback_ms) + " ms (indexing " +
			    Ms(index_ms) + " ms, the target scan the rest), statement " + Ms(total) + " ms");
		}
		qod::search::SetMaxUnindexedRowsForTest(199999);
		RefusedAtRun(client, "F8 the same at a cap of 199,999", sql,
		             "QoD: search_index_behind: lake.main.big has 200000 unindexed live rows (cap 199999)");
		qod::search::SetMaxUnindexedRowsForTest(200000);
	}

	// F7c: an interrupt in the middle of the fallback's scan (big's 200,000 rows, interrupted after 3,000):
	// an interrupt error, no row, no segment built.
	{
		auto counters = qod::search::FallbackCountersForTest();
		qod::search::InterruptFallbackAfterRowsForTest(3000);
		Rows rows;
		std::string err;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.big", "'w1 w10'", "'lake.main.big'"), rows, err);
		qod::search::InterruptFallbackAfterRowsForTest(0);
		auto after = qod::search::FallbackCountersForTest();
		Check(!ran && rows.empty() && err.find("INTERRUPT") != std::string::npos && after.segments == counters.segments &&
		          after.rows == counters.rows,
		      "F7c an interrupt after 3,000 of 200,000 fallback rows: an interrupt error, no row, no segment built",
		      ran ? "it ran" : FirstLine(err));
	}

	// F9 (review I1): 70 small unindexed files (11 rows each, past DATA_INLINING_ROW_LIMIT 10). Past the source cap
	// of 64, search_index_behind before any read; under a cap of 100, one target scan for all 70, equal to the
	// reference, with the files it opens counted.
	{
		bool made = Exec(setup, "CREATE TABLE lake.main.st (id BIGINT, body VARCHAR, f10 INTEGER)", "st") &&
		            IndexTable(setup, "st");
		for (int f = 0; f < 70 && made; f++) {
			made = Exec(setup,
			            "INSERT INTO lake.main.st SELECT i, " + body + ", (i % 10)::INTEGER FROM range(" +
			                std::to_string(f * 11) + ", " + std::to_string(f * 11 + 11) + ") r(i) ORDER BY i",
			            "st file " + std::to_string(f));
		}
		auto st_files = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'st')");
		Check(made && st_files == 70, "F9 st: 770 rows in 70 data files, none bundled", std::to_string(st_files) + " files");
		const std::string st = Search("SELECT rowid FROM lake.main.st", "'w1 w10'", "'lake.main.st'");
		auto scans = qod::search::TextScansForTest();
		Rows rows;
		std::string err;
		auto ran = Try(client, st, rows, err);
		Check(!ran && rows.empty() &&
		          err.find("QoD: search_index_behind: lake.main.st has 70 unindexed sources (cap 64); retry once the "
		                   "indexer catches up") != std::string::npos &&
		          qod::search::TextScansForTest() == scans,
		      "F9 70 unindexed files past the source cap of 64: search_index_behind, no row, no scan",
		      ran ? "it ran" : FirstLine(err));
		// X3 (Task 10): the sources cap in both populations, raised before any scan: a Permission Error naming the
		// table, the sources and the cap in the table population, no count of sources in the allowed one.
		{
			const std::string kTableForm = "Permission Error: QoD: search_index_behind: lake.main.st has 70 unindexed "
			                               "sources (cap 64); retry once the indexer catches up";
			const std::string kCount = "70 unindexed sources";
			std::string allowed_form = kTableForm;
			allowed_form.replace(allowed_form.find(kCount), kCount.size(), "more unindexed sources than the search reads");
			const std::string st_filtered =
			    Search("SELECT rowid FROM lake.main.st WHERE f10 = 0", "'w1 w10'", "'lake.main.st'");
			std::vector<std::string> got;
			auto scans_before = qod::search::TextScansForTest();
			for (auto population : {"table", "allowed"}) {
				g_population = population;
				for (auto &sql : {st, st_filtered}) {
					Rows none;
					std::string e;
					got.push_back(Try(client, sql, none, e) ? std::string("it ran") : FirstLine(e));
				}
			}
			g_population = "table";
			auto scans_moved = qod::search::TextScansForTest() - scans_before;
			auto digits = got[2];
			if (digits.find("(cap 64)") != std::string::npos) {
				digits.erase(digits.find("(cap 64)"), 8);
			}
			Check(got[0] == kTableForm && got[1] == kTableForm && scans_moved == 0,
			      "X3 the sources cap in the table population: a Permission Error naming the 70 sources, before any scan",
			      got[0] + " | " + got[1]);
			Check(got[2] == allowed_form && got[3] == allowed_form && scans_moved == 0 &&
			          digits.find_first_of("0123456789") == std::string::npos,
			      "X3 the sources cap in the allowed population: no count of sources, no digit but the cap's, before any "
			      "scan",
			      got[2] + " | " + got[3]);
		}
		qod::search::SetMaxUnindexedSourcesForTest(100);
		Connection counted(db);
		WrapClientFileSystem(counted);
		Install(counted, "acme/acme_db/bi");
		qod::search::FallbackScanObserverForTest([](bool begin) {
			std::lock_guard<std::mutex> lock(g_fs_mutex);
			if (begin) {
				g_fs_opens.clear();
			}
			g_fs_counting = begin;
		});
		for (int i = 0; i < 3; i++) {
			scans = qod::search::TextScansForTest();
			auto before = qod::search::FallbackCountersForTest();
			qod::ResetLastSearchCallForTest();
			ran = Try(counted, st, rows, err);
			auto record = qod::LastSearchCallForTest();
			auto index_ms = double(qod::search::FallbackCountersForTest().index_ns - before.index_ns) / 1e6;
			size_t opened = 0, opens = 0;
			{
				std::lock_guard<std::mutex> lock(g_fs_mutex);
				opened = g_fs_opens.size();
				for (auto &o : g_fs_opens) {
					opens += o.second;
				}
			}
			if (i == 0) {
				std::string rerr, why;
				auto want = Reference(setup, "lake.main.st", "", "SELECT rowid FROM lake.main.st", "w1 w10", 10, rerr);
				Check(ran && record.fallback_segments == 70 && record.fallback_docs == 770 &&
				          qod::search::TextScansForTest() == scans + 1 && rerr.empty() && SameHits(Hits(rows), want, true, why),
				      "F9 under a source cap of 100: 70 fallback segments from one target scan, equal to the reference",
				      ran ? std::to_string(record.fallback_segments) + " segments, " +
				                std::to_string(qod::search::TextScansForTest() - scans) + " scans" + why + rerr
				          : FirstLine(err));
			}
			Say("   F9 run " + std::to_string(i + 1) + ": 70 files of 11 rows in one scan: fallback " +
			    Ms(record.fallback_ms) + " ms (indexing " + Ms(index_ms) + " ms), " + std::to_string(opened) +
			    " paths opened " + std::to_string(opens) + " times during the scan");
		}
		qod::search::FallbackScanObserverForTest(nullptr);
		{
			std::lock_guard<std::mutex> lock(g_fs_mutex);
			g_fs_counting = false;
		}
		double ignored_rel = 0;
		int ignored_count = 0;
		FCompare(setup, client, "lake.main.st", "", "F9 the grid over 70 unindexed files", ignored_rel, ignored_count);
		max_rel = std::max(max_rel, ignored_rel);
		compared += ignored_count;
		qod::search::SetMaxUnindexedSourcesForTest(64);
	}

	// F10 (review minor 6, Task 14 fix round 1): a prepared search in a transaction that then writes to the lake:
	// DuckDB rebinds the second EXECUTE, which must answer with the write, equal to the transaction's reference.
	{
		Exec(client, "BEGIN TRANSACTION", "F10 begin");
		auto prepared = client.Prepare(all);
		std::string err, rerr, why;
		bool first = false, second_ok = false;
		if (!prepared->HasError()) {
			auto r1 = prepared->Execute();
			first = !r1->HasError();
			Exec(client, "INSERT INTO lake.main.ft VALUES (30000, 'w1 w10 w1', 0)", "F10 a write in the transaction");
			auto new_rowid = Int(client, "SELECT rowid FROM lake.main.ft WHERE id = 30000");
			auto r2 = prepared->Execute();
			if (r2->HasError()) {
				err = FirstLine(r2->GetError());
			} else {
				Rows rows;
				while (auto chunk = r2->Fetch()) {
					for (idx_t r = 0; r < chunk->size(); r++) {
						std::vector<Value> row;
						for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
							row.push_back(chunk->GetValue(c, r));
						}
						rows.push_back(std::move(row));
					}
				}
				auto got = Hits(rows);
				auto want = Reference(client, ft, "", "SELECT rowid FROM lake.main.ft", "w1 w10", 10, rerr);
				bool has_new = false;
				for (auto &h : got) {
					has_new = has_new || h.rowid == new_rowid;
				}
				second_ok = rerr.empty() && SameHits(got, want, true, why) && has_new;
				err = rerr.empty() ? (has_new ? why : "the new row is not among the hits " + Show(got)) : FirstLine(rerr);
			}
		} else {
			err = FirstLine(prepared->GetError());
		}
		Exec(client, "ROLLBACK", "F10 rollback");
		Check(first && second_ok,
		      "F10 BEGIN; EXECUTE p; INSERT ...; EXECUTE p: the second EXECUTE is rebound and answers with the write, equal "
		      "to the transaction's reference",
		      err);
	}

	// F11 (review minor 5): the caps' parse takes a plain decimal number that fits 64 bits, nothing else.
	{
		uint64_t value = 0;
		bool good = qod::search::ParseCap("200000", value) && value == 200000 &&
		            qod::search::ParseCap("18446744073709551615", value) && value == UINT64_MAX &&
		            qod::search::ParseCap("0", value) && value == 0;
		bool refused = true;
		for (auto text : {"", " 5", "5 ", "+5", "-1", "5x", "0x10", "18446744073709551616", "99999999999999999999"}) {
			refused = refused && !qod::search::ParseCap(text, value);
		}
		Check(good && refused,
		      "F11 the cap parse: plain decimals taken, a sign, a space, junk or an overflow refused (the default is "
		      "then used)");
	}

	// R (review C1, M9): a rename swap. rt(body, notes) indexed on body and bundled, then body renamed secret and
	// notes renamed body. The record pins body's field id: `secret` (that field) is searched through the bundle,
	// at the current S and at AT (VERSION => S0), and `body` (now notes' field) is not indexed. An index made on
	// today's body (another record, another index id) reads notes' field: through the fallback (no bundle under
	// its id), then through a bundle built for it. 'alpha' (the old body's word, now secret's) matches secret only.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.rt (id BIGINT, body VARCHAR, notes VARCHAR, f10 INTEGER)", "rt") &&
		          IndexTable(setup, "rt") &&
		          Exec(setup,
		               "INSERT INTO lake.main.rt SELECT i, 'alpha w' || (i % 7), 'omega w' || (i % 5), (i % 10)::INTEGER "
		               "FROM range(0, 2000) r(i) ORDER BY i",
		               "rt rows");
		int64_t rt_files = 0;
		ok = ok && BuildSegments(setup, sysc, "rt", data, rt_files);
		auto s0 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		auto body_field = ColumnFieldId(setup, "rt", "body");
		ok = ok && Exec(setup, "ALTER TABLE lake.main.rt RENAME COLUMN body TO secret", "rename body to secret") &&
		     Exec(setup, "ALTER TABLE lake.main.rt RENAME COLUMN notes TO body", "rename notes to body");
		auto now_field = ColumnFieldId(setup, "rt", "body");
		Check(ok && rt_files == 1 && body_field >= 0 && now_field >= 0 && now_field != body_field &&
		          ColumnFieldId(setup, "rt", "secret") == body_field,
		      "R the swap: today's body is another field (" + std::to_string(now_field) + ") than the bundled one (" +
		          std::to_string(body_field) + "), now secret");
		auto run = [&](const std::string &label, const std::string &at, const std::string &column,
		               const std::string &column_at, size_t fallback_segments, const std::string &unmatched) {
			auto input = "SELECT rowid FROM lake.main.rt" + at;
			for (auto query : {std::string("omega"), std::string("omega w3"), std::string("alpha"), std::string("alpha w3")}) {
				qod::ResetLastSearchCallForTest();
				Rows rows;
				std::string err, rerr, why;
				auto ran = Try(client, Search(input, Quote(query), "'lake.main.rt'", "['" + column + "']"), rows, err);
				auto record = qod::LastSearchCallForTest();
				auto want = Reference(setup, "lake.main.rt", at, input, query, 10, rerr, column_at);
				bool none = query == unmatched;
				Check(ran && rerr.empty() && record.fallback_segments == fallback_segments &&
				          (none ? rows.empty() : rows.size() == 10) && SameHits(Hits(rows), want, true, why),
				      "R " + label + ": '" + query + "' on " + column + " reads " + column_at + " at that S" +
				          (none ? ", no hit" : ", equal to the reference") + ", " + std::to_string(fallback_segments) +
				          " fallback segments",
				      ran ? why + rerr + " (" + std::to_string(record.fallback_segments) + " fallback segments)"
				          : FirstLine(err));
			}
		};
		run("current S, the record's field under its new name, through its bundle", "", "secret", "secret", 0, "omega");
		run("AT (VERSION => S0), the record's field under its new name", At(s0), "secret", "body", 0, "omega");
		Refused(client, "R the column now named body is another field than the record's",
		        Search("SELECT rowid FROM lake.main.rt", "'omega'", "'lake.main.rt'", "['body']"),
		        "Invalid Input Error: QoD: search_columns_unsupported: body of lake.main.rt is not indexed");
		// An index on today's body: another record and index id, so the bundle above is not its own.
		IndexTable(setup, "rt", {"body"}, data, "lake", "lake.main.rt#today");
		run("current S, an index on today's body: through the fallback", "", "body", "body", 1, "alpha");
		run("AT (VERSION => S0), an index on today's body: through the fallback", At(s0), "body", "notes", 1, "alpha");
		g_built.clear();
		BuildSegments(setup, sysc, "rt", data, rt_files);
		run("current S, a bundle built for today's body", "", "body", "body", 0, "alpha");
		run("AT (VERSION => S0), the bundle built for today's body", At(s0), "body", "notes", 0, "alpha");
		// An index field the snapshot read does not hold: refused before any segment is read.
		Exec(setup, "ALTER TABLE lake.main.rt ADD COLUMN fresh VARCHAR", "add fresh");
		IndexTable(setup, "rt", {"body", "fresh"}, data, "lake", "lake.main.rt#fresh");
		Refused(client, "R an index column added after S0, searched AT (VERSION => S0)",
		        Search("SELECT rowid FROM lake.main.rt" + At(s0), "'omega'", "'lake.main.rt'", "['fresh']"),
		        "Invalid Input Error: QoD: search_columns_unsupported: fresh of lake.main.rt is not a column of the table at "
		        "the snapshot the input reads");
		// The same code for a column the table does not have, from the describer: a shape refusal (Invalid Input),
		// never a Permission Error, so never booked as a denial.
		Refused(client, "R a column the table does not have",
		        Search("SELECT rowid FROM lake.main.rt", "'omega'", "'lake.main.rt'", "['nosuch']"),
		        "Invalid Input Error: QoD: search_columns_unsupported: nosuch is not a column of lake.main.rt");
	}

	std::ostringstream rel;
	rel << std::scientific << max_rel;
	Say("   F compared " + std::to_string(compared) + " searches with the reference, max relative score error " +
	    rel.str());
	Check(max_rel == 0 && compared > 0, "F every compared score is bit-identical to the reference");
	g_population = "table";
}

// ---- G1 to G3: GetAllFiles re-entrancy (7.1 step 1, changes 23 and 65; Task 13) ------------------------

//! What MeasureG1 counted for one statement.
struct G1Counts {
	bool ran = false;
	//! Statements bound on other contexts: before the search hook (the client's own bind), in the hook (before,
	//! while and after it resolves the view), while it resolves, and after the hook until the statement ended.
	int before_hook = 0;
	int in_hook = 0;
	int resolving = 0;
	int after_hook = 0;
	size_t hook_contexts = 0;
	size_t after_contexts = 0;
	//! The hook's statements on DuckLake's own context (the one execution binds on) and on any other (the node's
	//! own metadata connections, DuckLakeTableIdAt's), and how many such other contexts.
	int hook_ducklake = 0;
	int hook_other = 0;
	size_t other_contexts = 0;
	//! qod-node's post_bind passes as node-internal over the statement (NodeInternalPassesForTest).
	uint64_t internal_passes = 0;
};

//! G1 (change 23) on `client`: the statements bound on other contexts while the client's statement `sql` runs,
//! by phase (G1::Phase), each counted by the post_bind registered after the search's and by the one registered
//! before qod-node's. Checks, for every such statement: qod-node's post_bind was called on it (the two counts
//! agree) and passed it as node-internal (its counter moved by exactly their number), its context carries no quack
//! session tag, and (when g_g1.authz is set) no verdict counter moved while the hooks ran on it. One "== G1
//! <kind> miss|hit" line. `clear`: the liveness cache cleared first (a miss: GetAllFiles and the liveness scan run
//! in the hook), else as it is (after a miss at the same S, a hit).
G1Counts MeasureG1(Connection &client, const std::string &sql, const std::string &kind_p, bool clear) {
	auto kind = kind_p + (clear ? " miss" : " hit");
	if (clear) {
		qod::search::ClearLivenessCacheForTest();
	}
	auto client_ptr = client.context.get();
	{
		std::lock_guard<std::mutex> guard(g_g1.lock);
		g_g1.client = client_ptr;
		for (int p = 0; p < 5; p++) {
			g_g1.counts[p].clear();
			g_g1.untagged[p] = 0;
			g_g1.pre[p] = 0;
		}
		g_g1.open.clear();
		g_g1.counters_moved = 0;
		g_g1.unpaired = 0;
		g_g1.phase = G1::BeforeHook;
		g_g1.on = true;
	}
	std::atomic<int> hook_begins {0}, hook_ends {0};
	qod::SearchHookObserverForTest([&, client_ptr](ClientContext &context, bool begin) {
		if (&context != client_ptr) {
			return;
		}
		(begin ? hook_begins : hook_ends)++;
		std::lock_guard<std::mutex> guard(g_g1.lock);
		g_g1.phase = begin ? G1::HookBefore : G1::AfterHook;
	});
	qod::ResolveObserverForTest([](bool begin) {
		std::lock_guard<std::mutex> guard(g_g1.lock);
		g_g1.phase = begin ? G1::Resolving : G1::HookAfter;
	});
	auto passes0 = qod::NodeInternalPassesForTest();
	Rows rows;
	std::string error;
	G1Counts out;
	out.ran = Try(client, sql, rows, error);
	auto passes1 = qod::NodeInternalPassesForTest();
	qod::ResolveObserverForTest(nullptr);
	qod::SearchHookObserverForTest(nullptr);
	std::lock_guard<std::mutex> guard(g_g1.lock);
	g_g1.on = false;
	int counted[5] = {0, 0, 0, 0, 0};
	int untagged = 0, pre = 0, total = 0;
	std::set<const ClientContext *> hook_contexts, after_contexts;
	for (int p = 0; p < 5; p++) {
		for (auto &e : g_g1.counts[p]) {
			counted[p] += e.second;
			if (p >= G1::HookBefore && p <= G1::HookAfter) {
				hook_contexts.insert(e.first);
			} else if (p == G1::AfterHook) {
				after_contexts.insert(e.first);
			}
		}
		untagged += g_g1.untagged[p];
		pre += g_g1.pre[p];
		total += counted[p];
	}
	out.before_hook = counted[G1::BeforeHook];
	out.resolving = counted[G1::Resolving];
	out.in_hook = counted[G1::HookBefore] + counted[G1::Resolving] + counted[G1::HookAfter];
	out.after_hook = counted[G1::AfterHook];
	out.hook_contexts = hook_contexts.size();
	out.after_contexts = after_contexts.size();
	out.internal_passes = passes1 - passes0;
	if (after_contexts.size() == 1) {
		auto ducklake = *after_contexts.begin();
		for (int p = G1::HookBefore; p <= G1::HookAfter; p++) {
			for (auto &e : g_g1.counts[p]) {
				(e.first == ducklake ? out.hook_ducklake : out.hook_other) += e.second;
			}
		}
		out.other_contexts = hook_contexts.size() - hook_contexts.count(ducklake);
	} else {
		out.hook_ducklake = out.hook_other = -1;
	}
	bool same_pre = true;
	for (int p = 0; p < 5; p++) {
		same_pre = same_pre && g_g1.pre[p] == counted[p];
	}
	Check(out.ran && rows.size() == 10, "G1 " + kind + ": the search ran (10 rows)", FirstLine(error));
	Check(hook_begins == 1 && hook_ends == 1, "G1 " + kind + ": the search hook ran once on the client's statement",
	      std::to_string(hook_begins.load()) + " begin(s), " + std::to_string(hook_ends.load()) + " end(s)");
	Check(same_pre,
	      "G1 " + kind + ": the post_bind registered before qod-node's saw the same statements in every phase (so "
	                     "qod-node's ran on each)",
	      std::to_string(pre) + " vs " + std::to_string(total));
	Check(out.internal_passes == static_cast<uint64_t>(total),
	      "G1 " + kind + ": qod-node's post_bind passed each of them as node-internal and returned at once (its "
	                     "node-internal passes moved by exactly their number)",
	      std::to_string(out.internal_passes) + " passes for " + std::to_string(total) + " statements");
	Check(untagged == total, "G1 " + kind + ": none of their contexts carries the quack session tag",
	      std::to_string(untagged) + " untagged of " + std::to_string(total));
	if (g_g1.authz) {
		Check(g_g1.counters_moved == 0 && g_g1.unpaired == 0,
		      "G1 " + kind + ": no verdict counter (plan_cache_hits, plan_cache_misses, decide_calls, local_refusals) "
		                     "moved while qod-node's hooks ran on any of them",
		      std::to_string(g_g1.counters_moved) + " moved, " + std::to_string(g_g1.unpaired) + " unpaired");
	}
	Say("== G1 " + kind + ": before the search hook " + std::to_string(out.before_hook) + "; in the hook " +
	    std::to_string(out.in_hook) + " statement(s) on " + std::to_string(out.hook_contexts) + " other context(s), " +
	    std::to_string(out.hook_ducklake) + " on DuckLake's own and " + std::to_string(out.hook_other) + " on " +
	    std::to_string(out.other_contexts) + " node metadata connection(s) (" +
	    std::to_string(counted[G1::HookBefore]) + " before the view, " + std::to_string(out.resolving) +
	    " while it resolves (GetAllFiles and the liveness scan), " + std::to_string(counted[G1::HookAfter]) +
	    " after it); after the hook (optimizer and execution) " + std::to_string(out.after_hook) + " on " +
	    std::to_string(out.after_contexts) + " context(s); untagged " + std::to_string(untagged) +
	    "; node-internal passes " + std::to_string(out.internal_passes));
	return out;
}

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

//! The verdicts G1's fresh lake asks for (the real decide client, its verdict cache in front): every plan and
//! screen allowed for u-plain, as the manager signs them, each search call of a plan with the table population.
qod_test::HttpAnswer FreshReplica(const qod_test::HttpRequest &request) {
	qod_test::HttpAnswer answer;
	try {
		auto body = qod::ParseJson(request.body);
		auto description = qod::FromBase64Url(body.Get("description", qod::JsonValue::Kind::STRING).string);
		qod_test::VerdictFields fields;
		fields.kind = body.Get("kind", qod::JsonValue::Kind::STRING).string;
		fields.principal = "u-plain";
		fields.fingerprint = qod::Hex(qod::Sha256(description));
		fields.expires_at = now_ms + 60000;
		if (fields.kind == "plan") {
			auto described = qod::ParseJson(description);
			if (auto searches = described.GetOptional("searches", qod::JsonValue::Kind::ARRAY)) {
				std::string entries = "[";
				for (auto &s : searches->array) {
					entries += (entries.size() > 1 ? "," : "") + std::string("{\"call\":") +
					           std::to_string(s.Get("call", qod::JsonValue::Kind::INTEGER).integer) +
					           ",\"population\":\"table\"}";
				}
				fields.search = entries + "]";
			}
		}
		answer.body = qod_test::SignVerdict(qod_test::VerdictJson(fields));
	} catch (std::exception &) {
		answer.status = 400;
	}
	return answer;
}

//! A fresh lake of its own instance (Run's pages shape: 2 files of 10,000 rows, a bundle each, built on the
//! system principal's connection), on a DuckDB-file catalog or, `postgres`, a Postgres one where
//! QOD_P3_PG_DSN_FILE names a file holding a libpq key=value connection string (never printed), in a database of
//! its own on that server, created here and dropped by DropFresh. The G1 counters sit around qod-node's and the
//! search's hooks; `real_decider`: qod-node's own decide client (its verdict cache) against FreshReplica, else
//! the stand-in Recorder; `extra` registers more hooks after them all (G3's probe).
struct Fresh {
	std::unique_ptr<DuckDB> db;
	std::unique_ptr<Connection> setup, sysc, client;
	std::shared_ptr<qod::NodeAuthz> authz;
	std::unique_ptr<qod_test::TestHttpServer> replica;
	fs::path base;
	std::string kind, data, root, saved_root;
	bool postgres = false;
	bool created_database = false;
	bool attached = false;
	bool ok = false;
};

const std::string kFreshDatabase = "qod_t13_g";

std::string FreshInsert(int64_t from, int64_t to) {
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	return "INSERT INTO lake.main.pages SELECT i, 't' || i, " + body +
	       ", CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(" + std::to_string(from) + ", " +
	       std::to_string(to) + ") r(i) ORDER BY i";
}

bool PgAdmin(Connection &setup, const std::string &stmt) {
	return Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach the admin database") &&
	       Exec(setup, "CALL postgres_execute('pgadmin', " + Quote(stmt) + ", use_transaction => false)", stmt) &&
	       Exec(setup, "DETACH pgadmin", "detach the admin database");
}

bool BuildFresh(Fresh &f, const fs::path &base, bool postgres, bool real_decider,
                const std::function<void(DatabaseInstance &)> &extra = nullptr, int inline_limit = 10) {
	f.base = base;
	f.postgres = postgres;
	f.kind = postgres ? "postgres" : "duckdb-file";
	f.saved_root = g_bundle_root;
	fs::remove_all(base);
	fs::create_directories(base / "data");
	fs::create_directories(base / "bundles");
	f.data = fs::canonical(base / "data").string() + "/";
	f.root = fs::canonical(base / "bundles").string() + "/";
	f.db = std::make_unique<DuckDB>(nullptr);
	auto &instance = *f.db->instance;
	{
		PlannerExtension pre;
		pre.post_bind_function = CountingPreBind;
		PlannerExtension::Register(DBConfig::GetConfig(instance), pre);
	}
	f.authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	if (real_decider) {
		f.replica = std::make_unique<qod_test::TestHttpServer>(FreshReplica);
		qod::RegisterNodeAuthz(instance, f.authz);
	} else {
		qod::RegisterNodeAuthz(instance, f.authz, Recorder);
	}
	qod::RegisterSearchFunctions(instance);
	{
		PlannerExtension counter;
		counter.post_bind_function = CountingPostBind;
		PlannerExtension::Register(DBConfig::GetConfig(instance), counter);
	}
	if (extra) {
		extra(instance);
	}
	f.setup = std::make_unique<Connection>(*f.db);
	auto &setup = *f.setup;
	for (std::string ext : {"ducklake", "parquet", "postgres"}) {
		if (!postgres && ext == "postgres") {
			continue;
		}
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("== G " + f.kind + ": " + ext + " unavailable: skipped");
			return false;
		}
	}
	std::string attach = "ATTACH " + Quote("ducklake:" + (base / "meta.ducklake").string()) + " AS lake (DATA_PATH " +
	                     Quote(f.data) + ", DATA_INLINING_ROW_LIMIT " + std::to_string(inline_limit) + ")";
	if (postgres) {
		std::ifstream in(std::getenv("QOD_P3_PG_DSN_FILE"));
		std::string dsn((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		auto parts = ParseDsn(dsn);
		g_secret_password = parts["password"];
		auto secret = "CREATE TEMPORARY SECRET (TYPE postgres, HOST " + Quote(parts["host"]) + ", PORT " + parts["port"] +
		              ", USER " + Quote(parts["user"]) + ", PASSWORD " + Quote(parts["password"]) + ")";
		if (g_secret_password.empty() || setup.Query(secret)->HasError()) {
			Check(false, "G postgres: the secret could not be created (statement not printed)");
			return false;
		}
		if (!PgAdmin(setup, "DROP DATABASE IF EXISTS " + kFreshDatabase + " WITH (FORCE)") ||
		    !PgAdmin(setup, "CREATE DATABASE " + kFreshDatabase)) {
			return false;
		}
		f.created_database = true;
		attach = "ATTACH 'ducklake:postgres:dbname=" + kFreshDatabase + "' AS lake (DATA_PATH " + Quote(f.data) +
		         ", DATA_INLINING_ROW_LIMIT " + std::to_string(inline_limit) + ")";
	}
	f.attached = Exec(setup, attach, "attach the " + f.kind + " lake");
	bool ok = f.attached &&
	          Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, f10 INTEGER)",
	               "pages") &&
	          Exec(setup, FreshInsert(0, 10000), "file 1") && Exec(setup, FreshInsert(10000, 20000), "file 2");
	g_indexes.Clear();
	g_indexes.OverrideRoot(nullptr);
	g_indexes.Install();
	ok = ok && IndexTable(setup, "pages", {"title", "body"}, f.root);
	g_bundle_root = f.root;
	f.sysc = std::make_unique<Connection>(*f.db);
	f.client = std::make_unique<Connection>(*f.db);
	auto replica = f.replica ? f.replica->Url() : std::string();
	int64_t files = 0;
	ok = ok && Install(*f.sysc, "acme/acme_db/bi", true, false, replica) &&
	     BuildSegments(setup, *f.sysc, "pages", f.root, files) && Install(*f.client, "acme/acme_db/bi", false, false, replica);
	Check(ok && files == 2, "G " + f.kind + ": the fresh lake (2 data files, a bundle each)", std::to_string(files));
	f.ok = ok && files == 2;
	return f.ok;
}

void DropFresh(Fresh &f) {
	f.client.reset();
	f.sysc.reset();
	if (f.setup) {
		if (f.attached) {
			Exec(*f.setup, "DETACH lake", "detach the lake");
		}
		if (f.created_database) {
			PgAdmin(*f.setup, "DROP DATABASE IF EXISTS " + kFreshDatabase + " WITH (FORCE)");
			Rows left;
			std::string error;
			Exec(*f.setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach the admin database");
			auto counted = Try(*f.setup,
			                   "SELECT count(*) FROM postgres_query('pgadmin', 'SELECT 1 FROM pg_database WHERE datname = ''" +
			                       kFreshDatabase + "'' ')",
			                   left, error);
			Check(counted && !left.empty() && left[0][0].GetValue<int64_t>() == 0,
			      "G postgres: the run's database is dropped", FirstLine(error));
			Exec(*f.setup, "DETACH pgadmin", "detach the admin database");
		}
	}
	f.setup.reset();
	f.db.reset();
	f.replica.reset();
	g_secret_password.clear();
	g_bundle_root = f.saved_root;
	g_indexes.Clear();
	fs::remove_all(f.base);
}

//! The node's verdict counters, read as the manager's probe reads them (qod_authz_stats on the system principal's
//! connection): plan_cache_hits, plan_cache_misses, decide_calls.
std::vector<int64_t> SqlVerdictCounters(Connection &sysc) {
	Rows rows;
	std::string error;
	if (!Try(sysc, "SELECT plan_cache_hits, plan_cache_misses, decide_calls FROM system.main.qod_authz_stats()", rows,
	         error) ||
	    rows.size() != 1) {
		Check(false, "read qod_authz_stats()", FirstLine(error));
		return {-1, -1, -1};
	}
	return {rows[0][0].GetValue<int64_t>(), rows[0][1].GetValue<int64_t>(), rows[0][2].GetValue<int64_t>()};
}

//! G2 (change 23, the second risk): another connection commits while the client's search runs, `where` "hook":
//! right after the search hook returned (SearchHookObserverForTest's end, before the optimizer, which reads the
//! catalog again for the scan's filter, and before execution), or "finish": in Finish, before qs_search
//! (SearchCallObserverForTest). The commit inserts 20,000 rows (a new data file) and deletes about 1% of the table,
//! the reference's own hits among them. The search must answer exactly what the same search answers at the hook's
//! S, read with AT (VERSION => S) before the commit, with no search_snapshot_unavailable: every row the scan
//! returned located in the segments the hook resolved (RowidMap::Locate, Task 11). Then the commit is shown to have
//! landed, and a new search at the new S to answer differently.
void RunG2(Fresh &f, const std::string &where, const std::string &filter, int64_t &next_id) {
	auto &setup = *f.setup;
	auto &client = *f.client;
	auto name = "G2 " + f.kind + " " + where + (filter.empty() ? "" : " (" + filter.substr(7) + ")");
	auto s = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	Rows want_rows, rows, after_rows;
	std::string want_error, error, after_error, why;
	auto want_ran = Try(client, Search("SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(s) + ")" + filter),
	                    want_rows, want_error);
	auto want = Hits(want_rows);
	Check(want_ran && want.size() == 10, name + ": the reference at S = " + std::to_string(s), FirstLine(want_error));
	if (!want_ran || want.size() != 10) {
		return;
	}
	auto count0 = Int(setup, "SELECT count(*) FROM lake.main.pages");
	auto files0 = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'pages')");
	std::string hits_list;
	for (auto &h : want) {
		hits_list += (hits_list.empty() ? "" : ", ") + std::to_string(h.rowid);
	}
	auto from = next_id;
	next_id += 20000;
	std::atomic<int> commits {0};
	std::atomic<bool> committed {false};
	auto commit = [&]() {
		if (commits++ != 0) {
			return;
		}
		committed = Exec(setup, FreshInsert(from, from + 20000), name + ": insert 20,000 rows") &&
		            Exec(setup, "DELETE FROM lake.main.pages WHERE rowid IN (" + hits_list + ") OR id % 100 = 1",
		                 name + ": delete about 1%");
	};
	auto client_ptr = client.context.get();
	if (where == "hook" || where == "bind") {
		auto at_begin = where == "bind";
		qod::SearchHookObserverForTest([&, client_ptr, at_begin](ClientContext &context, bool begin) {
			if (&context == client_ptr && begin == at_begin) {
				commit();
			}
		});
	} else {
		qod::SearchCallObserverForTest([&](ClientContext &) { commit(); });
	}
	qod::ResetLastSearchCallForTest();
	auto ran = Try(client, Search("SELECT rowid FROM lake.main.pages" + filter), rows, error);
	qod::SearchHookObserverForTest(nullptr);
	qod::SearchCallObserverForTest(nullptr);
	auto record = qod::LastSearchCallForTest();
	auto at = record.view ? record.view->snapshot_id : -1;
	Check(committed.load() && commits.load() == 1,
	      name + (where == "bind" ? ": the commit ran once, after the client's statement first read the lake (its bind) "
	                                "and before the search hook read S"
	                              : ": the commit ran once, between the hook and execution"),
	      std::to_string(commits.load()) + " call(s)");
	Check(ran && at == s && error.find("search_snapshot_unavailable") == std::string::npos &&
	          SameHits(Hits(rows), want, true, why),
	      name + ": the search answers exactly what it answers at the hook's S (AT (VERSION => " + std::to_string(s) +
	          ")), no search_snapshot_unavailable",
	      ran ? "view at " + std::to_string(at) + why : FirstLine(error));
	auto s2 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	auto count1 = Int(setup, "SELECT count(*) FROM lake.main.pages");
	auto files1 = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'pages')");
	auto with_deletes = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'pages') WHERE delete_file IS NOT NULL");
	auto deleted = count0 + 20000 - count1;
	Check(s2 > s && files1 == files0 + 1 && deleted > 0 && deleted * 100 <= (count0 + 20000) * 2,
	      name + ": the commit landed (S " + std::to_string(s) + " -> " + std::to_string(s2) + ", one data file more, " +
	          std::to_string(deleted) + " rows deleted, " + std::to_string(with_deletes) +
	          " data file(s) with a delete file)",
	      "files " + std::to_string(files0) + " -> " + std::to_string(files1) + ", rows " + std::to_string(count0) +
	          " -> " + std::to_string(count1));
	qod::ResetLastSearchCallForTest();
	auto after_ran = Try(client, Search("SELECT rowid FROM lake.main.pages" + filter), after_rows, after_error);
	auto after_at = qod::LastSearchCallForTest().view ? qod::LastSearchCallForTest().view->snapshot_id : -1;
	std::string unused;
	Check(after_ran && after_at == s2 && after_rows.size() == 10 && !SameHits(Hits(after_rows), want, true, unused),
	      name + ": a new search sees the commit (S " + std::to_string(s2) + ", other hits: the reference's were deleted)",
	      after_ran ? "view at " + std::to_string(after_at) : FirstLine(after_error));
}

//! G1 and G2 on a fresh lake of `postgres`'s catalog kind, with qod-node's own decide client and verdict cache.
//! G1: a search with a liveness-cache miss, then the same search (a hit, and a verdict cache hit); per statement
//! the counts of MeasureG1, and over each search, read through qod_authz_stats(): every plan_cache_miss was a
//! decide call of the client's own statement (the replica's requests), none of the nested statements'.
void RunFreshCases(const fs::path &base, bool postgres) {
	Fresh f;
	if (!BuildFresh(f, base, postgres, true)) {
		DropFresh(f);
		return;
	}
	g_g1.authz = f.authz.get();
	auto sql = Search("SELECT rowid FROM lake.main.pages WHERE id < 20000");
	auto c0 = SqlVerdictCounters(*f.sysc);
	auto r0 = f.replica->Requests();
	auto miss = MeasureG1(*f.client, sql, f.kind, true);
	auto c1 = SqlVerdictCounters(*f.sysc);
	auto r1 = f.replica->Requests();
	auto hit = MeasureG1(*f.client, sql, f.kind, false);
	auto c2 = SqlVerdictCounters(*f.sysc);
	auto r2 = f.replica->Requests();
	g_g1.authz = nullptr;
	auto delta = [](const std::vector<int64_t> &a, const std::vector<int64_t> &b, size_t i) { return b[i] - a[i]; };
	Check(delta(c0, c1, 1) >= 1 && delta(c0, c1, 1) == r1 - r0 && delta(c0, c1, 2) == r1 - r0,
	      "G1 " + f.kind + " miss: plan_cache_misses moved only for the client's own statement (each miss one decide "
	                       "call the replica answered)",
	      "misses +" + std::to_string(delta(c0, c1, 1)) + ", decide calls +" + std::to_string(delta(c0, c1, 2)) +
	          ", replica requests +" + std::to_string(r1 - r0));
	Check(delta(c1, c2, 1) == 0 && delta(c1, c2, 0) >= 1 && r2 == r1,
	      "G1 " + f.kind + " hit: the verdict came from the cache, plan_cache_misses did not move, no decide call",
	      "hits +" + std::to_string(delta(c1, c2, 0)) + ", misses +" + std::to_string(delta(c1, c2, 1)) +
	          ", replica requests +" + std::to_string(r2 - r1));
	// The counts on this fixture. P16 (change 65), which counted from the resolve's start: a miss binds 6 statements
	// on DuckLake's own context while the hook resolves and 2 during execution, a hit none and the same 2. Phase 1
	// (Task 12's index check before the view) reads S (ducklake_current_snapshot: 1 statement on DuckLake's context)
	// and the target's table id (DuckLakeTableIdAt: 1 statement on a node metadata connection of its own) in the hook
	// before the view, on a hit too; so a miss binds 6 on DuckLake's context in the hook (1 + 5 while resolving), a hit
	// 1, each 1 more on one metadata connection, and both 2 during execution. Before the hook, the client's own bind
	// loads DuckLake's snapshot for its transaction (1, which P16 did not count).
	auto shape = [](const G1Counts &c) {
		return std::to_string(c.before_hook) + " before the hook, " + std::to_string(c.hook_ducklake) + " + " +
		       std::to_string(c.hook_other) + " on " + std::to_string(c.other_contexts) + " in it (" +
		       std::to_string(c.resolving) + " resolving), " + std::to_string(c.after_hook) + " after";
	};
	Check(miss.ran && miss.before_hook == 1 && miss.hook_ducklake == 6 && miss.resolving == 5 && miss.hook_other == 1 &&
	          miss.other_contexts == 1 && miss.after_hook == 2,
	      "G1 " + f.kind + " miss: in the hook 6 statements on DuckLake's context (5 while resolving) and 1 on a node "
	                       "metadata connection, 2 during execution",
	      shape(miss));
	Check(hit.ran && hit.before_hook == 1 && hit.hook_ducklake == 1 && hit.resolving == 0 && hit.hook_other == 1 &&
	          hit.other_contexts == 1 && hit.after_hook == 2,
	      "G1 " + f.kind + " hit: in the hook 1 statement on DuckLake's context (none resolving) and 1 on a node metadata "
	                       "connection, 2 during execution",
	      shape(hit));
	int64_t next_id = 20000;
	RunG2(f, "hook", "", next_id);
	RunG2(f, "hook", " WHERE id >= 0", next_id);
	RunG2(f, "finish", " WHERE id >= 0", next_id);
	RunG2(f, "bind", " WHERE id >= 0", next_id);
	DropFresh(f);
}

// ---- Y0 to Y8: read-your-writes in a writing transaction (Task 14) ------------------------------------------

//! Y0's probe (label probe): a post_bind registered after qod-node's and the search's. Armed, on the client's own
//! context, on a plan reading lake.main.pages, it lists what the search hook's code path sees there, in the client's
//! transaction: the target scan's bind data (MultiFileBindData::file_list, GetAllFiles, each entry's options) and the
//! target's own scan (qod::search::ScanTargetRows: filename, file_row_number, rowid). No production change.
struct Y0Probe {
	const ClientContext *client = nullptr;
	std::atomic<bool> armed {false};
	bool ran = false;
	std::string error;
	std::vector<std::string> files;
	std::vector<qod::search::TargetRow> rows;
};
Y0Probe g_y0;

std::string Y0Short(const std::string &text, const std::string &data) {
	return text.rfind(data, 0) == 0 ? "<data>/" + text.substr(data.size()) : text;
}

void Y0ProbePostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!g_y0.armed.load() || &input.context != g_y0.client || !statement.plan) {
		return;
	}
	std::function<LogicalGet *(LogicalOperator &)> find = [&](LogicalOperator &op) -> LogicalGet * {
		if (op.type == LogicalOperatorType::LOGICAL_GET) {
			auto &get = op.Cast<LogicalGet>();
			auto table = get.GetTable();
			if (table && table->name == "pages" && dynamic_cast<MultiFileBindData *>(get.bind_data.get())) {
				return &get;
			}
		}
		for (auto &child : op.children) {
			if (child) {
				if (auto found = find(*child)) {
					return found;
				}
			}
		}
		return nullptr;
	};
	auto get = find(*statement.plan);
	if (!get) {
		return;
	}
	g_y0.armed = false;
	g_y0.ran = true;
	try {
		auto &multi = get->bind_data->Cast<MultiFileBindData>();
		for (auto &file : multi.file_list->GetAllFiles()) {
			std::string line = file.path;
			if (file.extended_info) {
				std::map<std::string, std::string> sorted;
				for (auto &o : file.extended_info->options) {
					if (o.first == "encryption_key" || o.first == "etag" || o.first == "last_modified" ||
					    o.first == "validate_external_file_cache") {
						continue;
					}
					sorted[o.first] = o.second.IsNull() ? "NULL" : o.second.ToString();
				}
				for (auto &o : sorted) {
					line += " " + o.first + "=" + o.second;
				}
			}
			g_y0.files.push_back(line);
		}
		g_y0.rows = qod::search::ScanTargetRows(input.context, *get);
	} catch (std::exception &e) {
		g_y0.error = e.what();
	}
}

//! One probe: arms the hook, binds a plain read of the target on the client (in its transaction), returns what the
//! hook saw.
bool Y0Run(Connection &client, const std::string &label, std::vector<std::string> &files,
           std::vector<qod::search::TargetRow> &rows) {
	g_y0.client = client.context.get();
	g_y0.ran = false;
	g_y0.error.clear();
	g_y0.files.clear();
	g_y0.rows.clear();
	g_y0.armed = true;
	Rows out;
	std::string error;
	auto ok = Try(client, "SELECT rowid FROM lake.main.pages LIMIT 1", out, error);
	g_y0.armed = false;
	Check(ok && g_y0.ran && g_y0.error.empty(), "Y0 " + label + ": the probe listed the bind data and the target scan",
	      ok ? g_y0.error : FirstLine(error));
	files = g_y0.files;
	rows = g_y0.rows;
	return ok && g_y0.ran && g_y0.error.empty();
}

//! The target scan's rows grouped by file name: rows, positions and rowids (min and max).
void Y0Say(const std::string &label, const std::vector<std::string> &files,
           const std::vector<qod::search::TargetRow> &rows, const std::string &data) {
	Say("   Y0 " + label + ": bind data file list (" + std::to_string(files.size()) + " entries):");
	for (auto &f : files) {
		Say("      " + Y0Short(f, data));
	}
	struct G {
		int64_t n = 0, pmin = INT64_MAX, pmax = INT64_MIN, rmin = INT64_MAX, rmax = INT64_MIN;
	};
	std::map<std::string, G> groups;
	for (auto &r : rows) {
		auto &g = groups[r.filename];
		g.n++;
		g.pmin = std::min(g.pmin, r.file_row_number);
		g.pmax = std::max(g.pmax, r.file_row_number);
		g.rmin = std::min(g.rmin, r.rowid);
		g.rmax = std::max(g.rmax, r.rowid);
	}
	Say("   Y0 " + label + ": the target scan's rows by filename (" + std::to_string(rows.size()) + " rows):");
	for (auto &g : groups) {
		Say("      " + Y0Short(g.first, data) + ": " + std::to_string(g.second.n) + " rows, file_row_number " +
		    std::to_string(g.second.pmin) + ".." + std::to_string(g.second.pmax) + ", rowid " +
		    std::to_string(g.second.rmin) + ".." + std::to_string(g.second.rmax));
	}
}

//! Y0 (label probe, Task 14 step 1): in one transaction, INSERT 20 rows (inlined) and 20,000 rows (a data file),
//! DELETE 1% of the committed rows, UPDATE 5 committed rows; then list, from the search hook's code path, the bind
//! data's file list and the target scan's (filename, file_row_number, rowid); twice, to see the positions stay; then
//! after 5 more inlined rows and deletes of 3 transaction-local inlined and 3 transaction-local file rows, once more.
//! Also ducklake_list_files at S in that transaction (what the search's listing reads).
void RunY0(Fresh &f) {
	auto &client = *f.client;
	auto &setup = *f.setup;
	auto name = "Y0 " + f.kind;
	auto s0 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	Exec(client, "BEGIN TRANSACTION", name + " begin");
	bool ok = Exec(client, FreshInsert(100000, 100020), name + " insert 20 rows") &&
	          Exec(client, FreshInsert(200000, 220000), name + " insert 20,000 rows") &&
	          Exec(client, "DELETE FROM lake.main.pages WHERE id < 20000 AND id % 100 = 7", name + " delete 1%") &&
	          Exec(client, "UPDATE lake.main.pages SET body = 'zzupdated ' || body WHERE id IN (11, 12, 13, 10011, 10012)",
	               name + " update 5 rows");
	std::vector<std::string> f1, f1b, f2;
	std::vector<qod::search::TargetRow> r1, r1b, r2;
	if (ok && Y0Run(client, name + " after the writes", f1, r1)) {
		Y0Say(name + " after the writes", f1, r1, f.data);
	}
	Rows listing, newrows, updated;
	std::string lerr, nerr, uerr;
	auto listed = Try(client,
	                  "SELECT data_file, delete_file IS NOT NULL FROM ducklake_list_files('lake', 'pages') ORDER BY ALL",
	                  listing, lerr);
	Say("   " + name + ": ducklake_list_files in the transaction (S " + std::to_string(s0) + "): " +
	    (listed ? std::to_string(listing.size()) + " rows" : "failed: " + FirstLine(lerr)));
	for (auto &row : listing) {
		Say("      " + Y0Short(row[0].ToString(), f.data) + " delete_file=" + row[1].ToString());
	}
	if (Try(client,
	        "SELECT min(id), max(id), count(*), min(rowid), max(rowid) FROM lake.main.pages WHERE id >= 100000 GROUP BY "
	        "id >= 200000 ORDER BY 1",
	        newrows, nerr)) {
		for (auto &row : newrows) {
			Say("   " + name + ": new rows id " + row[0].ToString() + ".." + row[1].ToString() + " (" + row[2].ToString() +
			    "): rowid " + row[3].ToString() + ".." + row[4].ToString());
		}
	}
	if (Try(client, "SELECT id, rowid, filename FROM lake.main.pages WHERE id IN (11, 12, 13, 10011, 10012) ORDER BY id",
	        updated, uerr)) {
		for (auto &row : updated) {
			Say("   " + name + ": updated id " + row[0].ToString() + ": rowid " + row[1].ToString() + ", filename " +
			    Y0Short(row[2].ToString(), f.data));
		}
	} else {
		Say("   " + name + ": the filename column by SQL: " + FirstLine(uerr));
	}
	auto key = [](const qod::search::TargetRow &r) {
		return r.filename + "|" + std::to_string(r.file_row_number) + "|" + std::to_string(r.rowid);
	};
	if (ok && Y0Run(client, name + " again", f1b, r1b)) {
		std::set<std::string> a, b;
		for (auto &r : r1) {
			a.insert(key(r));
		}
		for (auto &r : r1b) {
			b.insert(key(r));
		}
		Check(a == b && f1 == f1b, name + ": a second listing in the transaction gives the same files and rows",
		      std::to_string(a.size()) + " / " + std::to_string(b.size()));
	}
	ok = ok && Exec(client, FreshInsert(100100, 100105), name + " insert 5 more rows") &&
	     Exec(client, "DELETE FROM lake.main.pages WHERE id IN (100001, 100002, 100003, 200001, 200002, 200003)",
	          name + " delete 6 transaction-local rows");
	if (ok && Y0Run(client, name + " after more writes", f2, r2)) {
		Y0Say(name + " after more writes", f2, r2, f.data);
		// A data file's rows (committed or the transaction's own) keep (filename, position, rowid) but the 3 deleted;
		// the transaction's inlined rows keep their rowids but the 3 deleted, while their positions may move (the
		// search reads them by rowid, through the fallback, never by position).
		const std::string local = "__ducklake_inlined_transaction_local_data";
		std::set<std::string> later;
		std::set<int64_t> later_local;
		for (auto &r : r2) {
			later.insert(key(r));
			if (r.filename == local) {
				later_local.insert(r.rowid);
			}
		}
		int64_t file_gone = 0, local_gone = 0, local_moved = 0;
		for (auto &r : r1) {
			if (r.filename == local) {
				if (!later_local.count(r.rowid)) {
					local_gone++;
				} else if (!later.count(key(r))) {
					local_moved++;
				}
			} else if (!later.count(key(r))) {
				file_gone++;
			}
		}
		Say("   " + name + ": after more writes, " + std::to_string(local_moved) +
		    " of the transaction's inlined rows kept their rowid at another position");
		Check(file_gone == 3 && local_gone == 3,
		      name + ": after more writes, every earlier data file row keeps (filename, position, rowid) and every earlier "
		             "inlined row of the transaction its rowid, but the 3 deleted of each",
		      std::to_string(file_gone) + " file rows and " + std::to_string(local_gone) + " inlined rows gone");
	}
	auto dups = Int(client, "SELECT count(*) FROM (SELECT rowid FROM lake.main.pages GROUP BY rowid HAVING count(*) > 1)");
	Say("   " + name + ": rowids held by more than one row in the transaction: " + std::to_string(dups));
	Exec(client, "ROLLBACK", name + " rollback");
}

//! An insert of rows [from, to) into lake.main.pages, the fresh lake's generator (FreshInsert) with `term` put first
//! in every `every`-th row's body (every row when 1).
std::string YInsert(int64_t from, int64_t to, const std::string &term, int64_t every = 1) {
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	return "INSERT INTO lake.main.pages (id, title, body, lang, f10) SELECT i, 't' || i, CASE WHEN i % " + std::to_string(every) + " = 0 THEN '" +
	       term + " ' ELSE '' END || " + body +
	       ", CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(" + std::to_string(from) + ", " +
	       std::to_string(to) + ") r(i) ORDER BY i";
}

//! A search's answer, with each hit's id read on the same connection (in its transaction): rowids change at COMMIT,
//! ids do not.
struct YAnswer {
	bool ran = false;
	std::string error;
	std::vector<Hit> hits;
	std::vector<int64_t> ids;
};

std::string YSql(const std::string &input, const std::string &query, uint32_t k) {
	return Search(input, Quote(query), kTable, kCols, ", k := " + std::to_string(k));
}

YAnswer YRun(Connection &con, const std::string &input, const std::string &query, uint32_t k) {
	YAnswer a;
	Rows rows;
	a.ran = Try(con, YSql(input, query, k), rows, a.error);
	if (!a.ran) {
		return a;
	}
	a.hits = Hits(rows);
	if (a.hits.empty()) {
		return a;
	}
	std::string list;
	for (auto &h : a.hits) {
		list += (list.empty() ? "" : ", ") + std::to_string(h.rowid);
	}
	Rows idrows;
	std::string err;
	std::map<int64_t, std::vector<int64_t>> by_rowid;
	if (Try(con, "SELECT rowid, id FROM lake.main.pages WHERE rowid IN (" + list + ")", idrows, err)) {
		for (auto &r : idrows) {
			by_rowid[r[0].GetValue<int64_t>()].push_back(r[1].GetValue<int64_t>());
		}
	}
	for (auto &h : a.hits) {
		auto &ids = by_rowid[h.rowid];
		a.ids.push_back(ids.size() == 1 ? ids[0] : -1);
	}
	return a;
}

//! `got` and `want` hold the same (id, score) pairs, scores bit-identical, in any order (rowids differ across a
//! COMMIT, so tie order may too); both complete (fewer hits than k).
bool SameById(const YAnswer &got, const YAnswer &want, uint32_t k, std::string &why) {
	auto pairs = [](const YAnswer &a) {
		std::vector<std::pair<int64_t, float>> p;
		for (size_t i = 0; i < a.hits.size(); i++) {
			p.emplace_back(a.ids[i], a.hits[i].score);
		}
		std::sort(p.begin(), p.end());
		return p;
	};
	auto g = pairs(got), w = pairs(want);
	if (got.hits.size() >= k || want.hits.size() >= k) {
		why = "an answer is not complete (" + std::to_string(got.hits.size()) + " / " + std::to_string(want.hits.size()) +
		      " hits, k " + std::to_string(k) + ")";
		return false;
	}
	for (auto &p : g) {
		if (p.first < 0) {
			why = "a hit's id could not be read";
			return false;
		}
	}
	if (g.size() != w.size()) {
		why = "got " + std::to_string(g.size()) + " hits, want " + std::to_string(w.size());
		return false;
	}
	for (size_t i = 0; i < g.size(); i++) {
		if (g[i].first != w[i].first || g[i].second != w[i].second) {
			why = "at " + std::to_string(i) + ": id " + std::to_string(g[i].first) + "@" + std::to_string(g[i].second) +
			      ", want id " + std::to_string(w[i].first) + "@" + std::to_string(w[i].second);
			return false;
		}
	}
	return true;
}

//! The search on `con` (in its transaction) against the reference computed there by plain SQL: the rows of
//! lake.main.pages as the transaction sees them, a fresh segment, the table population (the fresh lake's verdict).
YAnswer YCheck(Connection &con, const std::string &name, const std::string &input, const std::string &query, uint32_t k) {
	auto got = YRun(con, input, query, k);
	std::string rerr, why;
	auto want = Reference(con, "lake.main.pages", "", input, query, k, rerr);
	Check(got.ran && rerr.empty() && !want.empty() && SameHits(got.hits, want, true, why),
	      name + ": equals the reference of the rows as the transaction sees them (bit-identical scores)",
	      got.ran ? (rerr.empty() ? why : FirstLine(rerr)) : FirstLine(got.error));
	return got;
}

//! The same rowids, ranks and bit-identical scores (an answer before a transaction and after its ROLLBACK).
void YSame(const std::string &name, const YAnswer &got, const YAnswer &want) {
	std::string why;
	Check(got.ran && want.ran && SameHits(got.hits, want.hits, true, why), name,
	      got.ran ? why : FirstLine(got.error));
}

const std::string kYAll = "SELECT rowid FROM lake.main.pages";
// Rare terms of the generator: a complete answer (fewer hits than k) on the fresh lake and its inserts.
const std::string kYRare = "w9000 w12000 w31000";

void RunY1toY8(Fresh &f) {
	auto &client = *f.client;
	auto &setup = *f.setup;
	auto kind = f.kind;
	Exec(setup, "CREATE TABLE lake.main.other2 (id BIGINT, body VARCHAR)", "other2") &&
	    Exec(setup, "INSERT INTO lake.main.other2 SELECT i, 'w1 w10' FROM range(0, 100) r(i)", "other2 rows");
	// The answers before any transaction: the whole table, and a complete rare-term answer.
	auto b0 = YCheck(client, "Y " + kind + " before (w1 w10)", kYAll, "w1 w10", 10);
	auto b0r = YCheck(client, "Y " + kind + " before (rare terms)", kYAll, kYRare, 2000);
	Check(b0r.ran && b0r.hits.size() > 10 && b0r.hits.size() < 2000,
	      "Y " + kind + ": the rare-term answer is complete and not trivial", std::to_string(b0r.hits.size()));

	// Y1: insert, then search, in one transaction: 20 inlined rows (zqinl) and 20,000 file rows (zqfil in every
	// hundredth).
	{
		auto name = "Y1 " + kind;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(100000, 100020, "zqinl"), name + " insert 20 rows (inlined)") &&
		             Exec(client, YInsert(200000, 220000, "zqfil", 100), name + " insert 20,000 rows (a data file)");
		qod::ResetLastSearchCallForTest();
		auto inl = YCheck(client, name + " zqinl", kYAll, "zqinl", 30);
		auto record = qod::LastSearchCallForTest();
		bool ids_ok = inl.hits.size() == 20;
		for (auto id : inl.ids) {
			ids_ok = ids_ok && id >= 100000 && id < 100020;
		}
		Check(wrote && ids_ok, name + ": the 20 inlined rows of the transaction are found, and only they",
		      std::to_string(inl.hits.size()) + " hits");
		Check(record.view && record.view->transaction_local_names.size() == 2 && record.fallback_segments >= 2,
		      name + ": the view names the transaction's 2 sources (its data file and its inlined rows), each a fallback "
		             "segment",
		      record.view ? std::to_string(record.view->transaction_local_names.size()) + " names, " +
		                        std::to_string(record.fallback_segments) + " fallback segments"
		                  : "no view");
		auto fil = YCheck(client, name + " zqfil", kYAll, "zqfil", 300);
		ids_ok = fil.hits.size() == 200;
		for (auto id : fil.ids) {
			ids_ok = ids_ok && id >= 200000 && id < 220000 && id % 100 == 0;
		}
		Check(ids_ok, name + ": the 200 file rows of the transaction holding zqfil are found, and only they",
		      std::to_string(fil.hits.size()) + " hits");
		YCheck(client, name + " w1 w10 over every row", kYAll, "w1 w10", 10);
		YCheck(client, name + " w1 w10 over the committed rows", "SELECT rowid FROM lake.main.pages WHERE id < 20000",
		       "w1 w10", 10);
		Exec(client, "ROLLBACK", name + " rollback");
		YSame("Y5 " + kind + " after Y1's ROLLBACK: the search before the transaction", YRun(client, kYAll, "w1 w10", 10),
		      b0);
	}

	// Y2: delete, then search: 1% of the committed rows and the reference's own hits; then a whole committed file.
	{
		auto name = "Y2 " + kind;
		std::string hits_list;
		for (auto &h : b0.hits) {
			hits_list += (hits_list.empty() ? "" : ", ") + std::to_string(h.rowid);
		}
		Rows dead_rows;
		std::string err;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Try(client, "SELECT rowid FROM lake.main.pages WHERE id % 100 = 7 OR rowid IN (" + hits_list + ")",
		                 dead_rows, err) &&
		             Exec(client, "DELETE FROM lake.main.pages WHERE id % 100 = 7 OR rowid IN (" + hits_list + ")",
		                  name + " delete 1%");
		std::set<int64_t> dead;
		for (auto &r : dead_rows) {
			dead.insert(r[0].GetValue<int64_t>());
		}
		auto got = YCheck(client, name + " w1 w10", kYAll, "w1 w10", 10);
		auto rare = YCheck(client, name + " rare terms", kYAll, kYRare, 2000);
		bool none_dead = got.hits.size() == 10;
		for (auto &a : {got, rare}) {
			for (auto &h : a.hits) {
				none_dead = none_dead && !dead.count(h.rowid);
			}
		}
		Check(wrote && dead.size() >= 200 && none_dead, name + ": no deleted committed row appears (" +
		                                                    std::to_string(dead.size()) + " deleted)");
		// A delete of every row of the first file: DuckLake drops the file from the scan's list while the listing at S
		// still names it.
		Exec(client, "DELETE FROM lake.main.pages WHERE id < 10000", name + " delete a whole file");
		auto files_listed = Int(client, "SELECT count(*) FROM ducklake_list_files('lake', 'pages')");
		qod::ResetLastSearchCallForTest();
		auto whole = YCheck(client, name + " a whole committed file deleted", kYAll, "w1 w10", 10);
		auto whole_record = qod::LastSearchCallForTest();
		Check(files_listed == 2 && whole_record.view && whole_record.view->files.size() == 1,
		      name + ": the listing at S still names both committed files, the view holds the one the scan lists",
		      std::to_string(files_listed) + " listed, " +
		          (whole_record.view ? std::to_string(whole_record.view->files.size()) : std::string("no")) +
		          " in the view");
		bool none_first = whole.hits.size() == 10;
		for (auto id : whole.ids) {
			none_first = none_first && id >= 10000;
		}
		Check(none_first, name + ": no row of the deleted file appears");
		Exec(client, "ROLLBACK", name + " rollback");
		YSame("Y5 " + kind + " after Y2's ROLLBACK: the search before the transaction", YRun(client, kYAll, "w1 w10", 10),
		      b0);
	}

	// Y3: update, then search: the old value's doc is dead, the new value's row is found.
	std::string upd_ids;
	{
		Rows ids;
		std::string err;
		Try(client,
		    "SELECT id FROM ((SELECT id FROM lake.main.pages WHERE id < 10000 AND list_contains(string_split(body, ' '), "
		    "'w1') ORDER BY id LIMIT 3) UNION ALL (SELECT id FROM lake.main.pages WHERE id >= 10000 AND "
		    "list_contains(string_split(body, ' '), 'w1') ORDER BY id LIMIT 2)) ORDER BY id",
		    ids, err);
		for (auto &r : ids) {
			upd_ids += (upd_ids.empty() ? "" : ", ") + r[0].ToString();
		}
		auto name = "Y3 " + kind;
		auto only = "SELECT rowid FROM lake.main.pages WHERE id IN (" + upd_ids + ")";
		auto before = YRun(client, only, "w1", 10);
		Check(ids.size() == 5 && before.ran && before.hits.size() == 5,
		      name + ": before, the 5 rows' old values hold w1 (5 hits)", std::to_string(before.hits.size()));
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, "UPDATE lake.main.pages SET body = 'zqupd w7' WHERE id IN (" + upd_ids + ")",
		                  name + " update 5 rows");
		auto old_value = YRun(client, only, "w1", 10);
		Check(wrote && old_value.ran && old_value.hits.empty(), name + ": the old values' docs are dead (w1: no hit)",
		      old_value.ran ? std::to_string(old_value.hits.size()) + " hits" : FirstLine(old_value.error));
		auto fresh = YCheck(client, name + " zqupd", kYAll, "zqupd", 10);
		std::set<int64_t> want_rowids, got_rowids;
		for (auto &h : before.hits) {
			want_rowids.insert(h.rowid);
		}
		for (auto &h : fresh.hits) {
			got_rowids.insert(h.rowid);
		}
		Check(fresh.hits.size() == 5 && got_rowids == want_rowids,
		      name + ": the 5 new values are found, under the rows' own rowids");
		YCheck(client, name + " w1 w10", kYAll, "w1 w10", 10);
		Exec(client, "ROLLBACK", name + " rollback");
		YSame("Y5 " + kind + " after Y3's ROLLBACK: the search before the transaction", YRun(client, kYAll, "w1 w10", 10),
		      b0);
	}

	// Y4: a write to another table of the same lake, then a search: it runs, the other table's write unseen.
	{
		auto name = "Y4 " + kind;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, "INSERT INTO lake.main.other2 SELECT i, 'w1 w10 w1' FROM range(100, 30000) r(i)",
		                  name + " insert into other2") &&
		             Exec(client, "DELETE FROM lake.main.other2 WHERE id < 50", name + " delete from other2");
		qod::ResetLastSearchCallForTest();
		auto got = YRun(client, kYAll, "w1 w10", 10);
		auto record = qod::LastSearchCallForTest();
		YSame(name + ": the search equals the one before the transaction", got, b0);
		Check(wrote && record.view && record.view->transaction_local_names.empty(),
		      name + ": the view names no transaction-local source of pages");
		Exec(client, "ROLLBACK", name + " rollback");
	}
	// Y4b: one statement that writes another table of the lake from a search of pages, in auto-commit and in a
	// transaction that has already written: it runs and writes the search's rows.
	{
		auto name = "Y4b " + kind;
		auto write = "INSERT INTO lake.main.other2 SELECT rowid, 'from search' FROM (" + YSql(kYAll, "w1 w10", 10) + ")";
		bool ran = Exec(client, write, name + " auto-commit");
		auto auto_rows = Int(client, "SELECT count(*) FROM lake.main.other2 WHERE body = 'from search'");
		bool tx = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		          Exec(client, YInsert(100000, 100005, "zqinl"), name + " insert 5 rows") && Exec(client, write, name);
		auto tx_rows = Int(client, "SELECT count(*) FROM lake.main.other2 WHERE body = 'from search'");
		Exec(client, "ROLLBACK", name + " rollback");
		Exec(setup, "DELETE FROM lake.main.other2 WHERE body = 'from search'", name + " clean up");
		Check(ran && auto_rows == 10 && tx && tx_rows == 20,
		      name + ": INSERT ... SELECT FROM a search runs in auto-commit and in a writing transaction",
		      std::to_string(auto_rows) + " then " + std::to_string(tx_rows) + " rows");
	}
	// Y4c: a prepared search executed again in the transaction after a write: DuckDB rebinds it, and the second EXECUTE
	// answers with the write (the 3 new rows), equal to the transaction's reference (fix round 1, I1).
	auto execute_rows = [](PreparedStatement &prepared, Rows &rows, std::string &error) {
		rows.clear();
		auto result = prepared.Execute();
		if (result->HasError()) {
			error = FirstLine(result->GetError());
			return false;
		}
		while (auto chunk = result->Fetch()) {
			for (idx_t r = 0; r < chunk->size(); r++) {
				std::vector<Value> row;
				for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
					row.push_back(chunk->GetValue(c, r));
				}
				rows.push_back(std::move(row));
			}
		}
		return true;
	};
	{
		auto name = "Y4c " + kind;
		Exec(client, "BEGIN TRANSACTION", name + " begin");
		auto prepared = client.Prepare(YSql(kYAll, "zqprep", 10));
		Rows rows1, rows2;
		std::string e1, e2, rerr, why;
		bool ran1 = !prepared->HasError() && execute_rows(*prepared, rows1, e1);
		Exec(client, YInsert(100000, 100003, "zqprep"), name + " insert 3 rows");
		bool ran2 = ran1 && execute_rows(*prepared, rows2, e2);
		auto want = Reference(client, "lake.main.pages", "", kYAll, "zqprep", 10, rerr);
		Check(ran1 && rows1.empty() && ran2 && rows2.size() == 3 && rerr.empty() &&
		          SameHits(Hits(rows2), want, true, why),
		      name + ": BEGIN; EXECUTE p; INSERT; EXECUTE p: the first finds nothing, the second is rebound and finds the 3 "
		             "new rows, equal to the transaction's reference",
		      prepared->HasError() ? FirstLine(prepared->GetError())
		                           : (!ran1 ? e1 : (!ran2 ? e2 : std::to_string(rows2.size()) + " hits " + why)));
		Exec(client, "ROLLBACK", name + " rollback");
	}
	// Y4d: BEGIN; INSERT; PREPARE p; EXECUTE p, with no write between PREPARE and EXECUTE: it answers, equal to the
	// transaction's reference, through the C++ API and through SQL PREPARE / EXECUTE.
	{
		auto name = "Y4d " + kind;
		Exec(client, "BEGIN TRANSACTION", name + " begin");
		Exec(client, YInsert(100000, 100003, "zqprep"), name + " insert 3 rows");
		auto prepared = client.Prepare(YSql(kYAll, "zqprep w1", 10));
		Rows rows;
		std::string e, rerr, why;
		bool ran = !prepared->HasError() && execute_rows(*prepared, rows, e);
		auto want = Reference(client, "lake.main.pages", "", kYAll, "zqprep w1", 10, rerr);
		Check(ran && rerr.empty() && SameHits(Hits(rows), want, true, why),
		      name + ": BEGIN; INSERT; PREPARE p; EXECUTE p (C++ API): equal to the transaction's reference",
		      prepared->HasError() ? FirstLine(prepared->GetError()) : (!ran ? e : why));
		Rows sql_rows;
		std::string sql_error;
		bool sql_ran = Exec(client, "PREPARE y4d AS " + YSql(kYAll, "zqprep w1", 10), name + " SQL PREPARE") &&
		               Try(client, "EXECUTE y4d", sql_rows, sql_error);
		Check(sql_ran && SameHits(Hits(sql_rows), want, true, why),
		      name + ": BEGIN; INSERT; PREPARE y4d AS ...; EXECUTE y4d (SQL): equal to the transaction's reference",
		      sql_ran ? why : FirstLine(sql_error));
		Exec(client, "DEALLOCATE y4d", name + " deallocate");
		Exec(client, "ROLLBACK", name + " rollback");
	}

	// Y10 to Y12 (fix round 1, I2; pinned in fix round 2): DDL, a flush and a merge in the writing transaction, each
	// pinned to the outcome observed on DuckDB 2.0 and its DuckLake, so a change of either shows as a failure: an
	// answer equal to the reference of the rows as the transaction sees them (`refusal` empty), or a refusal holding
	// `refusal`, never a wrong answer.
	auto pinned = [&](const std::string &name, const std::string &query, const std::string &refusal) {
		Rows rows;
		std::string error, rerr, why;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, YSql(kYAll, query, 10), rows, error);
		auto record = qod::LastSearchCallForTest();
		if (record.view) {
			size_t local_files = 0, committed_files = 0;
			for (auto &f : record.view->files) {
				(f.transaction_local ? local_files : committed_files)++;
			}
			Say("   " + name + ": the view: " + std::to_string(committed_files) + " committed file(s), " +
			    std::to_string(local_files) + " transaction-local file(s), " +
			    std::to_string(record.view->transaction_local_names.size()) + " transaction-local name(s), " +
			    std::to_string(record.fallback_segments) + " fallback segment(s)");
		}
		if (!refusal.empty()) {
			Check(!ran && error.find(refusal) != std::string::npos, name + ": refused with " + refusal,
			      ran ? "it answered " + std::to_string(rows.size()) + " hits" : FirstLine(error));
			return;
		}
		auto want = ran ? Reference(client, "lake.main.pages", "", kYAll, query, 10, rerr) : std::vector<Hit> {};
		Check(ran && rerr.empty() && !want.empty() && SameHits(Hits(rows), want, true, why),
		      name + ": answered, equal to the transaction's reference",
		      !ran ? FirstLine(error) : (rerr.empty() ? why : FirstLine(rerr)));
	};
	{
		auto name = "Y10 " + kind;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, "ALTER TABLE lake.main.pages ADD COLUMN extra VARCHAR", name + " add a column") &&
		             Exec(client, YInsert(100000, 100005, "zqddl"), name + " insert 5 rows");
		Check(wrote, name + " ADD COLUMN: the writes ran");
		pinned(name + " ADD COLUMN", "zqddl w1", "");
		Exec(client, "ROLLBACK", name + " rollback");
		wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		        Exec(client, "DROP TABLE lake.main.pages", name + " drop") &&
		        Exec(client,
		             "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, f10 INTEGER)",
		             name + " create under the same name") &&
		        Exec(client, YInsert(100000, 100005, "zqddl"), name + " insert 5 rows");
		Check(wrote, name + " DROP + CREATE: the writes ran");
		pinned(name + " DROP + CREATE", "zqddl w1", "QoD: search_index_unavailable: no index on lake.main.pages");
		Rows rows;
		std::string err;
		Try(client, "ROLLBACK", rows, err);
		YSame(name + ": after the ROLLBACK, the search before the transaction", YRun(client, kYAll, "w1 w10", 10), b0);
	}
	{
		auto name = "Y11 " + kind;
		Rows rows;
		std::string err;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(100000, 100020, "zqflush"), name + " insert 20 rows (inlined)") &&
		             Exec(client, "DELETE FROM lake.main.pages WHERE id % 100 = 5 AND id < 20000", name + " delete 1%");
		bool flushed = wrote && Try(client, "CALL ducklake_flush_inlined_data('lake')", rows, err);
		Say("   " + name + ": ducklake_flush_inlined_data in the transaction: " + (flushed ? "ran" : FirstLine(err)));
		Check(wrote, name + ": the writes ran");
		Check(flushed, name + ": ducklake_flush_inlined_data runs in the transaction", FirstLine(err));
		if (flushed) {
			pinned(name + " after the flush", "zqflush w1", "");
		}
		Try(client, "ROLLBACK", rows, err);
		YSame(name + ": after the ROLLBACK, the search before the transaction", YRun(client, kYAll, "w1 w10", 10), b0);
	}
	{
		auto name = "Y12 " + kind;
		Rows rows;
		std::string err;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(200000, 201000, "zqmerge"), name + " insert 1,000 rows (a file)") &&
		             Exec(client, YInsert(201000, 202000, "zqmerge"), name + " insert 1,000 rows (another file)");
		bool merged = wrote && Try(client, "CALL ducklake_merge_adjacent_files('lake')", rows, err);
		Say("   " + name + ": ducklake_merge_adjacent_files in the transaction: " + (merged ? "ran" : FirstLine(err)));
		Check(wrote, name + ": the writes ran");
		Check(merged, name + ": ducklake_merge_adjacent_files runs in the transaction", FirstLine(err));
		if (merged) {
			pinned(name + " after the merge", "zqmerge w1", "");
		}
		Try(client, "ROLLBACK", rows, err);
		YSame(name + ": after the ROLLBACK, the search before the transaction", YRun(client, kYAll, "w1 w10", 10), b0);
	}

	// Y6: the liveness cache is bypassed by every search in a modifying transaction, and never hit.
	{
		auto name = "Y6 " + kind;
		YRun(client, kYAll, "w1 w10", 10);
		auto warm = qod::search::LivenessCacheCountersForTest();
		YRun(client, kYAll, "w1 w10", 10);
		auto c0 = qod::search::LivenessCacheCountersForTest();
		Check(c0.hits == warm.hits + 1, name + ": in auto-commit the second search is a cache hit (the cache is on)");
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(100000, 100005, "zqinl"), name + " insert 5 rows");
		bool ran = true;
		for (int i = 0; i < 3; i++) {
			auto a = YRun(client, kYAll, "w1 w10", 10);
			ran = ran && a.ran;
		}
		auto c1 = qod::search::LivenessCacheCountersForTest();
		Check(wrote && ran && c1.bypassed == c0.bypassed + 3 && c1.hits == c0.hits && c1.misses == c0.misses,
		      name + ": 3 searches in the transaction: bypassed +3, hits +0, misses +0",
		      "bypassed +" + std::to_string(c1.bypassed - c0.bypassed) + ", hits +" + std::to_string(c1.hits - c0.hits) +
		          ", misses +" + std::to_string(c1.misses - c0.misses));
		Exec(client, "ROLLBACK", name + " rollback");
	}

	// Y7: the caps count the transaction's own rows and sources.
	{
		auto name = "Y7 " + kind;
		auto refused_then_rollback = [&](const std::string &what, const std::vector<std::string> &writes,
		                                 const std::string &expected) {
			bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin");
			for (auto &w : writes) {
				wrote = wrote && Exec(client, w, name + " " + what);
			}
			auto got = YRun(client, kYAll, "w1 w10", 10);
			Check(wrote && !got.ran && got.error.find("QoD: search_index_behind: ") != std::string::npos &&
			          got.error.find(expected) != std::string::npos,
			      name + " " + what + ": refused search_index_behind (" + expected + ")",
			      got.ran ? "it ran" : FirstLine(got.error));
			Rows rows;
			std::string err;
			auto rolled = Try(client, "ROLLBACK", rows, err);
			Check(rolled, name + " " + what + ": the ROLLBACK succeeds", FirstLine(err));
			YSame(name + " " + what + ": then the search before the transaction", YRun(client, kYAll, "w1 w10", 10), b0);
		};
		refused_then_rollback("250,000 rows inserted", {YInsert(1000000, 1250000, "zqbig")}, "unindexed live rows");
		qod::search::SetMaxUnindexedRowsForTest(10);
		refused_then_rollback("20 inlined rows past a cap of 10", {YInsert(100000, 100020, "zqinl")},
		                      "20 unindexed live rows (cap 10)");
		qod::search::SetMaxUnindexedRowsForTest(200000);
		qod::search::SetMaxUnindexedSourcesForTest(1);
		refused_then_rollback("a file and inlined rows past a source cap of 1",
		                      {YInsert(100000, 100020, "zqinl"), YInsert(200000, 220000, "zqfil", 100)},
		                      "2 unindexed sources (cap 1)");
		qod::search::SetMaxUnindexedSourcesForTest(64);
	}

	// Y8: AT (VERSION => n) in a modifying transaction reads snapshot n, none of the transaction's own rows;
	// AT (TIMESTAMP => t) stays refused.
	{
		auto name = "Y8 " + kind;
		auto n = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		auto at = "SELECT rowid FROM lake.main.pages AT (VERSION => " + std::to_string(n) + ")";
		auto outside = YRun(client, at, "w1 w10", 10);
		auto outside_rare = YRun(client, at, kYRare, 2000);
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(100000, 100020, "w1 w10"), name + " insert 20 rows (inlined)") &&
		             Exec(client, YInsert(200000, 220000, "w1 w10"), name + " insert 20,000 rows (a data file)") &&
		             Exec(client, "DELETE FROM lake.main.pages WHERE id % 100 = 3 OR id < 10000", name + " delete") &&
		             Exec(client, "UPDATE lake.main.pages SET body = 'w1 w10' WHERE id IN (" + upd_ids + ")",
		                  name + " update");
		qod::ResetLastSearchCallForTest();
		YSame(name + ": AT (VERSION => " + std::to_string(n) + ") in the transaction equals it outside",
		      YRun(client, at, "w1 w10", 10), outside);
		auto at_record = qod::LastSearchCallForTest();
		if (at_record.view) {
			int64_t local_live = 0;
			for (size_t i = 0; i < at_record.view->files.size(); i++) {
				if (at_record.view->files[i].transaction_local) {
					local_live += at_record.view->liveness[i].live_count;
				}
			}
			for (auto &t : at_record.view->inlined_tables) {
				if (t.transaction_local) {
					local_live += t.live_rows;
				}
			}
			Say("   " + name + ": the AT view lists " + std::to_string(at_record.view->transaction_local_names.size()) +
			    " transaction-local source(s), " + std::to_string(local_live) + " live rows in them");
			Check(local_live == 0 && at_record.view->snapshot_id == n,
			      name + ": the AT view is at " + std::to_string(n) + " and holds no live row of the transaction's own");
		} else {
			Check(false, name + ": the AT search recorded its view");
		}
		YSame(name + ": AT (VERSION => " + std::to_string(n) + ") in the transaction equals it outside (rare terms)",
		      YRun(client, at, kYRare, 2000), outside_rare);
		Check(wrote, name + ": the writes ran");
		Rows times;
		std::string terr;
		Try(setup, "SELECT snapshot_time FROM ducklake_snapshots('lake') WHERE snapshot_id = " + std::to_string(n), times,
		    terr);
		if (!times.empty()) {
			auto refused = YRun(client,
			                    "SELECT rowid FROM lake.main.pages AT (TIMESTAMP => " + Quote(times[0][0].ToString()) +
			                        "::TIMESTAMPTZ)",
			                    "w1 w10", 10);
			Check(!refused.ran && refused.error.find("QoD: search_at_timestamp_unsupported: ") != std::string::npos,
			      name + ": AT (TIMESTAMP => t) in the transaction: refused search_at_timestamp_unsupported",
			      refused.ran ? "it ran" : FirstLine(refused.error));
		} else {
			Check(false, name + ": the snapshot time of " + std::to_string(n), FirstLine(terr));
		}
		Exec(client, "ROLLBACK", name + " rollback");
	}

	// Y5: after COMMIT, a fresh auto-commit search equals the in-transaction answers of Y1 to Y3's writes, by id and
	// bit-identical score (rowids change at COMMIT). Inserts and deletes in one transaction, the update in another:
	// DuckLake 2.0 repeats rowids inside a transaction that inserts inlined rows, writes a data file and updates
	// (Y0), and the search refuses an ambiguous rowid.
	{
		auto name = "Y5 " + kind;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(100100, 100120, "zqinl2 w9000"), name + " insert 20 rows (inlined)") &&
		             Exec(client, YInsert(300000, 320000, "zqfil2 w9000", 100), name + " insert 20,000 rows (a file)") &&
		             Exec(client, "DELETE FROM lake.main.pages WHERE id % 100 = 9 AND id < 20000", name + " delete 1%");
		auto t_rare = YCheck(client, name + " rare terms in the transaction", kYAll, kYRare, 2000);
		auto t_inl = YCheck(client, name + " zqinl2 in the transaction", kYAll, "zqinl2", 30);
		auto t_fil = YCheck(client, name + " zqfil2 in the transaction", kYAll, "zqfil2", 300);
		Check(wrote && Exec(client, "COMMIT", name + " commit"), name + ": the inserts and deletes committed");
		std::string why;
		Check(SameById(YRun(client, kYAll, kYRare, 2000), t_rare, 2000, why),
		      name + ": after COMMIT, the rare-term answer equals the transaction's", why);
		Check(SameById(YRun(client, kYAll, "zqinl2", 30), t_inl, 30, why),
		      name + ": after COMMIT, zqinl2 equals the transaction's", why);
		Check(SameById(YRun(client, kYAll, "zqfil2", 300), t_fil, 300, why),
		      name + ": after COMMIT, zqfil2 equals the transaction's", why);
		wrote = Exec(client, "BEGIN TRANSACTION", name + " begin (update)") &&
		        Exec(client, "UPDATE lake.main.pages SET body = 'zqupd2 w9000' WHERE id IN (" + upd_ids + ")",
		             name + " update 5 rows");
		auto t_upd = YCheck(client, name + " zqupd2 in the transaction", kYAll, "zqupd2", 30);
		auto t_rare2 = YCheck(client, name + " rare terms in the update's transaction", kYAll, kYRare, 2000);
		Check(wrote && t_upd.hits.size() == 5 && Exec(client, "COMMIT", name + " commit (update)"),
		      name + ": the update committed");
		Check(SameById(YRun(client, kYAll, "zqupd2", 30), t_upd, 30, why),
		      name + ": after the update's COMMIT, zqupd2 equals the transaction's", why);
		Check(SameById(YRun(client, kYAll, kYRare, 2000), t_rare2, 2000, why),
		      name + ": after the update's COMMIT, the rare-term answer equals the transaction's", why);
	}

	// Y9 (Y0's finding): a transaction that inserts inlined rows, writes a data file and updates holds repeated
	// rowids (DuckLake 2.0); a search whose input reads one is refused search_rowid_ambiguous, never answered.
	{
		auto name = "Y9 " + kind;
		bool wrote = Exec(client, "BEGIN TRANSACTION", name + " begin") &&
		             Exec(client, YInsert(400000, 400020, "zqdup"), name + " insert 20 rows (inlined)") &&
		             Exec(client, YInsert(500000, 520000, "zqdup"), name + " insert 20,000 rows (a file)") &&
		             Exec(client, "UPDATE lake.main.pages SET body = 'zqdup w1' WHERE id IN (" + upd_ids + ")",
		                  name + " update 5 rows");
		auto dups = Int(client, "SELECT count(*) FROM (SELECT rowid FROM lake.main.pages GROUP BY rowid HAVING count(*) > 1)");
		if (dups > 0) {
			auto got = YRun(client, kYAll, "zqdup", 10);
			Check(wrote && !got.ran && got.error.find("QoD: search_rowid_ambiguous: ") != std::string::npos,
			      name + ": " + std::to_string(dups) + " repeated rowids in the transaction: refused search_rowid_ambiguous",
			      got.ran ? "it ran" : FirstLine(got.error));
		} else {
			// A DuckLake that keeps rowids unique in the transaction: the search must then answer exactly.
			Say("   " + name + ": no rowid repeats in the transaction (a DuckLake that keeps them unique)");
			Check(wrote, name + ": the writes ran");
			YCheck(client, name + " zqdup", kYAll, "zqdup", 10);
		}
		Rows rows;
		std::string err;
		Check(Try(client, "ROLLBACK", rows, err), name + ": the ROLLBACK succeeds", FirstLine(err));
	}
}

//! Y0 to Y8 on a fresh lake of `postgres`'s catalog kind (DATA_INLINING_ROW_LIMIT 100, so a 20-row insert is
//! inlined and a 20,000-row one is a data file), with qod-node's own decide client and Y0's probe registered last.
void RunYCases(const fs::path &base, bool postgres) {
	Fresh f;
	auto probe = [](DatabaseInstance &instance) {
		PlannerExtension y0;
		y0.post_bind_function = Y0ProbePostBind;
		PlannerExtension::Register(DBConfig::GetConfig(instance), y0);
	};
	if (!BuildFresh(f, base, postgres, true, probe, 100)) {
		DropFresh(f);
		return;
	}
	RunY0(f);
	RunY1toY8(f);
	DropFresh(f);
}


#ifndef _WIN32
//! G3's probe state (in the forked child only).
struct G3State {
	const ClientContext *client = nullptr;
	std::atomic<bool> in_hook {false};
	std::atomic<int> probes {0};
	std::atomic<uint64_t> locks {0};
};
G3State g_g3;

//! G3's probe: a post_bind registered after qod-node's and the search's (the probe pattern of part 2 Task 1, M2).
//! On each statement bound on another context while the client's search hook runs, it takes every lock the search
//! code holds, one after the other, and releases it.
void LockProbePostBind(PlannerExtensionInput &input, BoundStatement &) {
	if (!g_g3.in_hook.load() || &input.context == g_g3.client) {
		return;
	}
	g_g3.locks += qod::TakeSearchLocksForTest(*input.context.db);
	g_g3.probes++;
}

bool WriteAll(int fd, const std::string &text) {
	size_t at = 0;
	while (at < text.size()) {
		auto n = ::write(fd, text.data() + at, text.size() - at);
		if (n <= 0) {
			return false;
		}
		at += static_cast<size_t>(n);
	}
	return true;
}

//! The forked child: a fresh DuckDB-file lake with the probe, "ready" on `fd`, the search (the hook holding the
//! liveness cache's lock across the resolve when `control`), then "done <ran> <rows> <probes> <locks>" and exit.
[[noreturn]] void G3Child(const fs::path &base, bool control, int fd) {
	Fresh f;
	auto built = BuildFresh(f, base, false, false, [](DatabaseInstance &db) {
		PlannerExtension probe;
		probe.post_bind_function = LockProbePostBind;
		PlannerExtension::Register(DBConfig::GetConfig(db), probe);
	});
	if (!built) {
		WriteAll(fd, "failed\n");
		::_exit(1);
	}
	g_g3.client = f.client->context.get();
	auto client_ptr = g_g3.client;
	qod::SearchHookObserverForTest([client_ptr](ClientContext &context, bool begin) {
		if (&context == client_ptr) {
			g_g3.in_hook = begin;
		}
	});
	qod::search::ClearLivenessCacheForTest();
	qod::search::HoldLivenessLockAcrossResolveForTest(control);
	WriteAll(fd, "ready\n");
	Rows rows;
	std::string error;
	auto ran = Try(*f.client, Search("SELECT rowid FROM lake.main.pages WHERE id < 20000"), rows, error);
	WriteAll(fd, "done " + std::to_string(ran ? 1 : 0) + " " + std::to_string(rows.size()) + " " +
	                 std::to_string(g_g3.probes.load()) + " " + std::to_string(g_g3.locks.load()) + "\n");
	::_exit(0);
}

//! The next line from `fd` within `timeout_ms`; false on a timeout or the end of the stream.
bool ReadLine(int fd, int timeout_ms, std::string &line) {
	line.clear();
	auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
	while (true) {
		auto left = std::chrono::duration_cast<std::chrono::milliseconds>(until - std::chrono::steady_clock::now()).count();
		if (left <= 0) {
			return false;
		}
		struct pollfd p {fd, POLLIN, 0};
		auto polled = ::poll(&p, 1, static_cast<int>(left));
		if (polled <= 0) {
			continue;
		}
		char c;
		auto n = ::read(fd, &c, 1);
		if (n <= 0) {
			return false;
		}
		if (c == '\n') {
			return true;
		}
		line += c;
	}
}

//! One G3 child: "hung", "failed", or the child's done line. The parent waits 120 s for the fixture, then 5 s for
//! the search (the watchdog), and kills a child that did not answer in time.
std::string RunG3Child(const fs::path &base, bool control) {
	int fds[2];
	if (::pipe(fds) != 0) {
		return "failed: no pipe";
	}
	std::cout.flush();
	std::cerr.flush();
	auto pid = ::fork();
	if (pid < 0) {
		::close(fds[0]);
		::close(fds[1]);
		return "failed: no fork";
	}
	if (pid == 0) {
		::close(fds[0]);
		G3Child(base, control, fds[1]);
	}
	::close(fds[1]);
	std::string line, outcome;
	if (!ReadLine(fds[0], 120000, line) || line != "ready") {
		outcome = "failed: " + (line.empty() ? std::string("no fixture") : line);
	} else if (!ReadLine(fds[0], 5000, line)) {
		outcome = "hung";
	} else {
		outcome = line;
	}
	if (outcome == "hung" || outcome.rfind("failed", 0) == 0) {
		::kill(pid, SIGKILL);
	}
	int status = 0;
	::waitpid(pid, &status, 0);
	::close(fds[0]);
	fs::remove_all(base);
	return outcome;
}

//! G3 (7.1 step 1, the third point: the hook holds no lock while it calls MultiFileList::GetAllFiles). Run before
//! any instance of the parent exists, so the child forks a process with no other thread.
void RunG3(const fs::path &base) {
	auto control = RunG3Child(base / "g3-control", true);
	if (control == "hung") {
		Say("HUNG (G3 control: the hook held the liveness cache's lock across the resolve; the child was stopped)");
	}
	Check(control == "hung" || control.rfind("done 0 ", 0) == 0,
	      "G3 control: with the hook holding the liveness cache's lock across GetAllFiles, the probe on DuckLake's "
	      "nested statements blocks (HUNG) or the lock refuses, so the probe can see a held lock",
	      control);
	auto run = RunG3Child(base / "g3", false);
	if (run == "hung") {
		Say("HUNG (G3: a nested statement's probe waited for a lock the search hook held; the child was stopped)");
	}
	std::istringstream in(run);
	std::string word;
	int ran = 0, probes = 0;
	size_t rows = 0;
	uint64_t locks = 0;
	in >> word >> ran >> rows >> probes >> locks;
	Check(word == "done" && ran == 1 && rows == 10 && probes > 0 && locks > 0,
	      "G3 a post_bind registered after qod-node's took every search lock on each statement DuckLake bound while "
	      "the search hook ran, and never blocked (5 s watchdog)",
	      run == "hung" ? "HUNG" : run + " (" + std::to_string(probes) + " probes, " + std::to_string(locks) + " locks)");
}
#else
//! G3 forks a child: POSIX only (a Windows node runs the same hook; the lock rule is checked on the other platforms).
void RunG3(const fs::path &) {
	Say("== G3: no fork on Windows: skipped");
}
#endif

// ---- M1 to M8: Phase 1 (Task 4): two columns, every match, the records, the build, branches, query_json -----


void RunPhase1(DuckDB &db, Connection &setup, Connection &sysc, Connection &client, const std::string &data,
               const fs::path &base) {
	g_population = "table";
	g_bundle_root = data;
	const std::string pages = "lake.main.pages";
	const std::string all = "SELECT rowid FROM lake.main.pages";
	// M1: two columns. The reference reads both fields; `title:w1` outside ['body'] is refused (rule 6).
	for (auto query : {std::string("w1 t5"), std::string("t15 w10"), std::string("title:t7 OR body:w3")}) {
		Rows rows;
		std::string error, rerr, why;
		auto ran = Try(client, Search(all, Quote(query), kTable, "['title', 'body']"), rows, error);
		auto want = Reference(setup, pages, "", all, query, 10, rerr, "", {"title", "body"});
		Check(ran && rerr.empty() && rows.size() == 10 && SameHits(Hits(rows), want, true, why),
		      "M1 qod_search(['title', 'body'], '" + query + "') equals the reference over both fields",
		      ran ? why + rerr : FirstLine(error));
	}
	Refused(client, "M1 title:w1 with columns ['body']", Search(all, "'title:w1'"),
	        "Invalid Input Error: QoD: search_field_not_allowed: title");
	Refused(client, "M1 title:w1 with columns ['body'] (qod_search_matches)", Matches(all, "'title:w1'"),
	        "Invalid Input Error: QoD: search_field_not_allowed: title");

	// M2: every match. The count equals the reference's (the rows whose body holds the token); with_score the
	// scores equal qod_search's for the same rowids; the facet counts over the join-back equal the reference's.
	{
		Rows rows;
		std::string error;
		auto ran = Try(client, "SELECT count(*) FROM qod_search_matches((" + all + "), 'lake.main.pages', ['body'], 'w1')",
		               rows, error);
		auto want = Int(setup, "SELECT count(*) FROM lake.main.pages WHERE list_contains(string_split(body, ' '), 'w1')");
		Check(ran && rows.size() == 1 && rows[0][0].GetValue<int64_t>() == want && want > 2 * 2048,
		      "M2 count(*) of qod_search_matches 'w1' equals the reference count (" + std::to_string(want) +
		          ", over several chunks)",
		      ran ? (rows.empty() ? "no row" : rows[0][0].ToString()) : FirstLine(error));
		Rows nulls;
		ran = Try(client,
		          "SELECT count(*), count(score) FROM qod_search_matches((" + all + "), 'lake.main.pages', ['body'], 'w1')",
		          nulls, error);
		Check(ran && nulls.size() == 1 && nulls[0][0].GetValue<int64_t>() == want && nulls[0][1].GetValue<int64_t>() == 0,
		      "M2 without with_score every score is NULL", ran ? "" : FirstLine(error));
		Rows joined;
		ran = Try(client,
		          "SELECT max(abs(m.score - s.score)), count(*) FROM qod_search_matches((" + all +
		              "), 'lake.main.pages', ['body'], 'w1 w10', with_score := true) m JOIN qod_search((" + all +
		              "), 'lake.main.pages', ['body'], 'w1 w10', k := 1000) s ON m.rowid = s.rowid",
		          joined, error);
		Check(ran && joined.size() == 1 && joined[0][1].GetValue<int64_t>() == 1000 && joined[0][0].GetValue<double>() == 0,
		      "M2 with_score: on the 1,000 rowids of qod_search's top 1,000, the scores are equal (max abs difference 0)",
		      ran ? joined[0][0].ToString() + " over " + joined[0][1].ToString() : FirstLine(error));
		Rows facets, facets_want;
		ran = Try(client,
		          "SELECT p.lang, count(*) FROM qod_search_matches((" + all +
		              "), 'lake.main.pages', ['body'], 'w1') m JOIN lake.main.pages p ON p.rowid = m.rowid GROUP BY p.lang "
		              "ORDER BY 1",
		          facets, error);
		Try(setup,
		    "SELECT lang, count(*) FROM lake.main.pages WHERE list_contains(string_split(body, ' '), 'w1') GROUP BY lang "
		    "ORDER BY 1",
		    facets_want, error);
		bool same = ran && facets.size() == facets_want.size() && facets.size() == 2;
		for (size_t i = 0; same && i < facets.size(); i++) {
			same = facets[i][0] == facets_want[i][0] && facets[i][1] == facets_want[i][1];
		}
		Check(same, "M2 GROUP BY over the join-back gives the reference facet counts", ran ? "" : FirstLine(error));
		// A filtered input under the allowed population: the matching set is the input's matches.
		Rows filtered;
		ran = Try(client,
		          "SELECT count(*) FROM qod_search_matches((SELECT rowid FROM lake.main.pages WHERE f10 = 0), "
		          "'lake.main.pages', ['body'], 'w1')",
		          filtered, error);
		auto want_f = Int(setup, "SELECT count(*) FROM lake.main.pages WHERE f10 = 0 AND list_contains(string_split(body, "
		                         "' '), 'w1')");
		Check(ran && filtered.size() == 1 && filtered[0][0].GetValue<int64_t>() == want_f,
		      "M2 a filtered input: the matches of the input's rows only", ran ? filtered[0][0].ToString() : FirstLine(error));
	}

	// M3: the record checks, each refused before any bundle is opened.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.m3n (id BIGINT, body VARCHAR)", "m3n") &&
		          Exec(setup, "INSERT INTO lake.main.m3n SELECT i, 'w1 w2' FROM range(0, 100) r(i)", "m3n rows");
		Refused(client, "M3 a table with no record", Search("SELECT rowid FROM lake.main.m3n", kQuery, "'lake.main.m3n'"),
		        "QoD: search_index_unavailable: no index on lake.main.m3n");
		g_indexes.Change("lake", "tail", [](qod::search::IndexRecord &r) { r.state = "disabled"; });
		Refused(client, "M3 a disabled record", Search("SELECT rowid FROM lake.main.tail", kQuery, "'lake.main.tail'"),
		        "QoD: search_index_unavailable: no index on lake.main.tail");
		g_indexes.Change("lake", "tail", [](qod::search::IndexRecord &r) { r.state = "ready"; });
		ok = ok && Exec(setup, "CREATE TABLE lake.main.m3d (id BIGINT, body VARCHAR)", "m3d") && IndexTable(setup, "m3d") &&
		     Exec(setup, "DROP TABLE lake.main.m3d", "drop m3d") &&
		     Exec(setup, "CREATE TABLE lake.main.m3d (id BIGINT, body VARCHAR)", "m3d again") &&
		     Exec(setup, "INSERT INTO lake.main.m3d SELECT i, 'w1 w2' FROM range(0, 100) r(i)", "m3d rows");
		Refused(client, "M3 a table dropped and recreated under its name (another table id)",
		        Search("SELECT rowid FROM lake.main.m3d", kQuery, "'lake.main.m3d'"),
		        "QoD: search_index_unavailable: no index on lake.main.m3d");
		Refused(client, "M3 a column outside the index", Search(all, kQuery, kTable, "['lang']"),
		        "QoD: search_columns_unsupported: lang of lake.main.pages is not indexed");
		// m3t: indexed on body and bundled, then a column added and indexed, renames of the table and the column.
		ok = ok && Exec(setup, "CREATE TABLE lake.main.m3t (id BIGINT, body VARCHAR)", "m3t") && IndexTable(setup, "m3t") &&
		     Exec(setup, "INSERT INTO lake.main.m3t SELECT i, 'w' || (i % 7) || ' w1 w' || (i % 3) FROM range(0, 3000) "
		                 "r(i) ORDER BY i",
		          "m3t rows");
		int64_t files = 0;
		ok = ok && BuildSegments(setup, sysc, "m3t", data, files);
		auto s0 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		ok = ok && Exec(setup, "ALTER TABLE lake.main.m3t ADD COLUMN extra VARCHAR", "m3t extra") &&
		     IndexTable(setup, "m3t", {"body", "extra"}, data, "lake", "lake.main.m3t");
		Refused(client, "M3 an index column with no field id at S",
		        Search("SELECT rowid FROM lake.main.m3t" + At(s0), kQuery, "'lake.main.m3t'", "['extra']"),
		        "QoD: search_columns_unsupported: extra of lake.main.m3t is not a column of the table at the snapshot the "
		        "input reads");
		IndexTable(setup, "m3t", {"body"}, data);
		ok = ok && Exec(setup, "ALTER TABLE lake.main.m3t RENAME TO m3t2", "rename m3t");
		{
			Rows rows;
			std::string error, rerr, why;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, Search("SELECT rowid FROM lake.main.m3t2", kQuery, "'lake.main.m3t2'"), rows, error);
			auto last = qod::LastSearchCallForTest();
			auto want = Reference(setup, "lake.main.m3t2", "", "SELECT rowid FROM lake.main.m3t2", "w1 w10", 10, rerr);
			Check(ok && ran && rows.size() == 10 && last.fallback_segments == 0 && SameHits(Hits(rows), want, true, why),
			      "M3 after RENAME TO m3t2 the search on m3t2 works with the same record, through its bundle",
			      ran ? why + rerr + " fallback " + std::to_string(last.fallback_segments) : FirstLine(error));
		}
		ok = ok && Exec(setup, "ALTER TABLE lake.main.m3t2 RENAME COLUMN body TO text", "rename body to text");
		{
			Rows rows;
			std::string error, rerr, why;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, Search("SELECT rowid FROM lake.main.m3t2", kQuery, "'lake.main.m3t2'", "['text']"), rows,
			               error);
			auto last = qod::LastSearchCallForTest();
			auto want = Reference(setup, "lake.main.m3t2", "", "SELECT rowid FROM lake.main.m3t2", "w1 w10", 10, rerr, "",
			                      {"text"});
			Check(ok && ran && rows.size() == 10 && last.fallback_segments == 0 && SameHits(Hits(rows), want, true, why),
			      "M3 after RENAME COLUMN body TO text the search with columns ['text'] works, through its bundle",
			      ran ? why + rerr : FirstLine(error));
			auto ran_q = Try(client, Search("SELECT rowid FROM lake.main.m3t2", "'text:w1'", "'lake.main.m3t2'", "['text']"),
			                 rows, error);
			Check(ran_q && rows.size() == 10, "M3 the query's prefix names the column by its new name", FirstLine(error));
		}
		Refused(client, "M3 after the rename, columns ['body']",
		        Search("SELECT rowid FROM lake.main.m3t2", kQuery, "'lake.main.m3t2'", "['body']"),
		        "QoD: search_columns_unsupported: body");
	}

	// M4: the build, as the system principal: published at SegmentPath under the record, its size on disk, opened
	// under the record's MAC key only; an input with fewer text columns than the index has fields refused.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.m4 (id BIGINT, title VARCHAR, body VARCHAR)", "m4") &&
		          IndexTable(setup, "m4", {"title", "body"}) &&
		          Exec(setup, "INSERT INTO lake.main.m4 SELECT i, 't' || (i % 5), 'w' || (i % 7) FROM range(0, 500) r(i)",
		               "m4 rows");
		Rows listed;
		std::string error;
		ok = ok && Try(setup, "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM "
		                      "ducklake_list_files('lake', 'm4')",
		               listed, error) &&
		     listed.size() == 1;
		auto record = RecordOf("m4");
		std::string path = ok ? listed[0][0].GetValue<std::string>() : "";
		auto tail = "), 'lake.main.m4', " + Quote(path) + ", " + (ok ? listed[0][1].ToString() : "0") + "::UBIGINT, " +
		            (ok ? listed[0][2].ToString() : "0") + "::UBIGINT)";
		Rows built;
		ok = ok && Try(sysc,
		               "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT, title, body FROM "
		               "lake.main.m4 ORDER BY file_row_number" +
		                   tail,
		               built, error) &&
		     built.size() == 1;
		auto expected = qod::search::SegmentPath(data, record.index_id, qod::Hex(qod::Sha256(path)));
		std::error_code ec;
		auto size = fs::file_size(expected, ec);
		Check(ok && built[0][0].ToString() == expected && !ec && built[0][2].GetValue<int64_t>() == int64_t(size) &&
		          built[0][1].GetValue<int64_t>() == 500,
		      "M4 the build publishes 500 docs at SegmentPath(bundle_root, index_id, path_key), bytes = its size on disk",
		      ok ? built[0][0].ToString() + " " + built[0][2].ToString() + " vs " + std::to_string(size) : FirstLine(error));
		qod::search::DataFileRef file;
		file.path = path;
		file.bundle_key = qod::Hex(qod::Sha256(path));
		file.file_size = ok ? listed[0][1].GetValue<uint64_t>() : 0;
		file.footer_size = ok ? listed[0][2].GetValue<uint64_t>() : 0;
		qod::search::ClearSegmentCacheForTest();
		auto copy = file;
		qod::search::QueryMemory memory(uint64_t(1) << 40, false);
		auto opened = qod::search::LoadSegment(*setup.context, expected, record, copy, memory);
		auto other = record;
		other.mac_key = qod_test::TestMacKey("00000000-0000-8000-8000-000000000001");
		qod::search::ClearSegmentCacheForTest();
		copy = file;
		auto refused = qod::search::LoadSegment(*setup.context, expected, other, copy, memory);
		qod::search::ClearSegmentCacheForTest();
		Check(opened && opened->num_docs == 500 && !refused,
		      "M4 the bundle reopens under the record's MAC key, and reads as missing under another key");
		Rows fewer;
		auto ran = Try(sysc,
		               "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT, body FROM "
		               "lake.main.m4 ORDER BY file_row_number" +
		                   tail,
		               fewer, error);
		Check(!ran && error.find("QoD: search_invalid_argument: qod_search_build_segment's input carries 1 text columns, "
		                         "and the index has 2 fields") != std::string::npos,
		      "M4 an input with fewer text columns than the index has fields: search_invalid_argument",
		      ran ? "it ran" : FirstLine(error));
		ran = Try(sysc,
		          "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT, body FROM "
		          "lake.main.m3n ORDER BY file_row_number), 'lake.main.m3n', '/nowhere', 1::UBIGINT, 1::UBIGINT)",
		          fewer, error);
		Check(!ran && error.find("QoD: search_index_unavailable: no index on lake.main.m3n") != std::string::npos,
		      "M4 the build of a table with no record: search_index_unavailable", ran ? "it ran" : FirstLine(error));
	}

	// M5 (rule 9): on a branch pool's handle every search call is refused.
	{
		Connection branch(db);
		if (Install(branch, "acme/acme_db/__br_0011aabb")) {
			Refused(branch, "M5 qod_search on a branch pool", Search(all), "Invalid Input Error: QoD: search_branch_unsupported");
			Refused(branch, "M5 qod_search_matches on a branch pool", Matches(all, "'w1'"),
			        "Invalid Input Error: QoD: search_branch_unsupported");
		}
	}

	// M6: query_json is Phase 3.
	Refused(client, "M6 qod_search with a non-NULL query_json", Search(all, kQuery, kTable, kCols, ", query_json := '{}'"),
	        "QoD: search_query_unsupported: query_json is Phase 3");
	Refused(client, "M6 qod_search_matches with a non-NULL query_json",
	        Matches(all, kQuery, kTable, kCols, ", query_json := '{}'"),
	        "QoD: search_query_unsupported: query_json is Phase 3");

	// Task 3's forward requirements: every bundle refusal takes exactly the missing bundle's path (the fallback
	// within its caps, else search_index_behind), whatever the reason, and an object past the cap is never read.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.mm (id BIGINT, body VARCHAR)", "mm") && IndexTable(setup, "mm") &&
		          Exec(setup, "INSERT INTO lake.main.mm SELECT i, 'w' || (i % 7) || ' w1 w' || (i % 3) FROM range(0, 2000) "
		                      "r(i) ORDER BY i",
		               "mm rows");
		int64_t files = 0;
		ok = ok && BuildSegments(setup, sysc, "mm", data, files) && files == 1;
		auto record = RecordOf("mm");
		Rows listed;
		std::string error;
		Try(setup, "SELECT data_file FROM ducklake_list_files('lake', 'mm')", listed, error);
		auto bundle = qod::search::SegmentPath(data, record.index_id, qod::Hex(qod::Sha256(listed.at(0)[0].ToString())));
		std::string good;
		{
			std::ifstream in(bundle, std::ios::binary);
			good.assign((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		}
		auto write = [&](const std::string &bytes) {
			std::ofstream out(bundle, std::ios::binary | std::ios::trunc);
			out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
			qod::search::ClearSegmentCacheForTest();
		};
		auto sql = Search("SELECT rowid FROM lake.main.mm", kQuery, "'lake.main.mm'");
		Rows want_rows;
		auto ran_good = Try(client, sql, want_rows, error);
		struct Variant {
			std::string name;
			std::function<void()> plant;
		};
		auto flipped = good;
		flipped[flipped.size() / 2] = static_cast<char>(flipped[flipped.size() / 2] ^ 0x01);
		auto mac_flipped = good;
		mac_flipped.back() = static_cast<char>(mac_flipped.back() ^ 0x80);
		std::vector<Variant> variants = {
		    {"missing", [&] {
			     fs::remove(bundle);
			     qod::search::ClearSegmentCacheForTest();
		     }},
		    {"a byte of its body flipped (bundle_mac_mismatch)", [&] { write(flipped); }},
		    {"a byte of its MAC flipped (bundle_mac_mismatch)", [&] { write(mac_flipped); }},
		    {"truncated (bundle_corrupt)", [&] { write(good.substr(0, 20)); }},
		    {"larger than the bundle cap", [&] {
			     write(good);
			     fs::resize_file(bundle, qod::search::kMaxBundleBytes + 1);
			     qod::search::ClearSegmentCacheForTest();
		     }},
		};
		std::vector<std::string> behind;
		for (auto &variant : variants) {
			variant.plant();
			auto before = qod::search::SegmentCacheCountersForTest();
			Rows rows;
			std::string err, why;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, sql, rows, err);
			auto last = qod::LastSearchCallForTest();
			auto after = qod::search::SegmentCacheCountersForTest();
			Check(ok && ran_good && ran && last.fallback_segments == 1 && SameHits(Hits(rows), Hits(want_rows), true, why),
			      "F-MAC a bundle " + variant.name + ": the file through the fallback, the same answer",
			      ran ? why + " fallback " + std::to_string(last.fallback_segments) : FirstLine(err));
			if (variant.name == "larger than the bundle cap") {
				Check(after.oversized == before.oversized + 1 && after.bytes_read == before.bytes_read,
				      "F-MAC a bundle past the cap (" + std::to_string(qod::search::kMaxBundleBytes + 1) +
				          " bytes) is refused from its size: none of its bytes read",
				      std::to_string(after.bytes_read - before.bytes_read) + " bytes read");
			}
			qod::search::SetMaxUnindexedRowsForTest(100);
			Rows none;
			auto ran_cap = Try(client, sql, none, err);
			behind.push_back(ran_cap ? "it ran" : FirstLine(err));
			qod::search::SetMaxUnindexedRowsForTest(200000);
		}
		bool same = !behind.empty() && behind[0].find("QoD: search_index_behind: lake.main.mm has 2000 unindexed live rows "
		                                              "(cap 100)") != std::string::npos;
		for (auto &b : behind) {
			same = same && b == behind[0];
		}
		Check(same, "F-MAC past the fallback cap every refusal reason reads the same search_index_behind", behind[0]);
		write(good);
	}
	(void)base;
}

// ---- Fix round 1 of Task 4: streaming failures, error classes, unreadable metadata, the table id at S,
// the allowed population's memory text, a non-text field, the metadata reads ---------------------------

void RunFixRound1(DuckDB &db, Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	g_population = "table";
	g_bundle_root = data;
	const std::string all = "SELECT rowid FROM lake.main.pages";
	// lake.main.big: about 100,000 matches of 'w1' (50 chunks), more than the stream buffers ahead.
	auto matches = Matches("SELECT rowid FROM lake.main.big", "'w1'", "'lake.main.big'");
	const int64_t kAfter = 20;
	// I1 (a): a library failure, and a cancel, after at least one chunk went out fail the statement with the named
	// error, streamed or materialized: never a truncated success.
	for (auto cancel : {false, true}) {
		auto what = std::string(cancel ? "a cancel" : "a library failure");
		auto arm = [&](int64_t n) {
			if (cancel) {
				qod::CancelMatchesAfterChunksForTest(n);
			} else {
				qod::FailMatchesAfterChunksForTest(n);
			}
		};
		auto expected = std::string(cancel ? "Interrupted" : "QoD: search_invalid_argument");
		arm(kAfter);
		qod::ResetLastSearchCallForTest();
		// A stream on the submitted query: chunks reach the client as the engine produces them.
		idx_t rows = 0;
		bool failed = false;
		std::string error;
		try {
			QueryResultStream<> streamed(client.Submit(matches));
			while (true) {
				auto chunk = streamed.Fetch();
				if (streamed.HasError()) {
					failed = true;
					error = streamed.GetError();
					break;
				}
				if (!chunk || chunk->size() == 0) {
					break;
				}
				rows += chunk->size();
			}
		} catch (std::exception &e) {
			failed = true;
			error = ErrorData(e).Message();
		}
		auto last = qod::LastSearchCallForTest();
		Check(failed && error.find(expected) != std::string::npos && (!cancel || last.library_cancelled),
		      "I1 " + what + " after " + std::to_string(kAfter) + " chunks went out: the streamed statement fails with " +
		          expected + " (" + std::to_string(rows) + " rows reached the client first, none read as a complete "
		                                                    "answer)",
		      failed ? FirstLine(error) : "it ended as a success with " + std::to_string(rows) + " rows");
		Rows materialized;
		std::string merror;
		auto ran = Try(client, matches, materialized, merror);
		Check(!ran && materialized.empty() && merror.find(expected) != std::string::npos,
		      "I1 " + what + " after " + std::to_string(kAfter) + " chunks: the materialized statement fails with " +
		          expected + ", no row",
		      ran ? "it ran" : FirstLine(merror));
		arm(-1);
		Rows whole;
		ran = Try(client, "SELECT count(*) FROM (" + matches + ")", whole, merror);
		Check(ran && whole.size() == 1 && whole[0][0].GetValue<int64_t>() > kAfter * 2048,
		      "I1 the hook off: every match again",
		      ran ? whole[0][0].ToString() : FirstLine(merror));
	}

	// I3: an unreadable catalog is retryable, never "no index", in the hook and the build.
	{
		qod::FailSearchMetadataReadsForTest(true, false);
		Refused(client, "I3 the table id cannot be read", Search(all),
		        "Permission Error: QoD: authorization unavailable, retry: search index record unavailable");
		Rows rows;
		std::string error;
		auto ran = Try(sysc,
		               "SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, NULL::BIGINT, 'x', 'y'), "
		               "'lake.main.pages', '/nowhere', 1::UBIGINT, 1::UBIGINT)",
		               rows, error);
		Check(!ran && error.find("QoD: authorization unavailable, retry: search index record unavailable") !=
		                  std::string::npos,
		      "I3 the build, the table id unreadable: retryable", ran ? "it ran" : FirstLine(error));
		qod::FailSearchMetadataReadsForTest(false, true);
		ran = Try(sysc,
		          "SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, NULL::BIGINT, 'x', 'y'), "
		          "'lake.main.pages', '/nowhere', 1::UBIGINT, 1::UBIGINT)",
		          rows, error);
		Check(!ran && error.find("QoD: authorization unavailable, retry: the encryption of lake.main.pages's DuckLake "
		                         "could not be read") != std::string::npos,
		      "I3 and M1 the build, the encryption unreadable: retryable, saying so", ran ? "it ran" : FirstLine(error));
		qod::FailSearchMetadataReadsForTest(false, false);
		ran = Try(client, Search(all), rows, error);
		Check(ran && rows.size() == 10, "I3 the catalog readable again: the search runs", FirstLine(error));
	}

	// m1: another connection drops and recreates the table under its name after the client's transaction took
	// its snapshot: the client searches the table it reads, under its own record, never the other's.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.race (id BIGINT, body VARCHAR)", "race") && IndexTable(setup, "race") &&
		          Exec(setup, "INSERT INTO lake.main.race SELECT i, 'oldw w' || (i % 5) FROM range(0, 1000) r(i) ORDER BY i",
		               "race rows");
		int64_t files = 0;
		ok = ok && BuildSegments(setup, sysc, "race", data, files);
		auto old_record = RecordOf("race");
		ok = ok && Exec(client, "BEGIN TRANSACTION", "begin") &&
		     Int(client, "SELECT count(*) FROM lake.main.race") == 1000;
		ok = ok && Exec(setup, "DROP TABLE lake.main.race", "drop race") &&
		     Exec(setup, "CREATE TABLE lake.main.race (id BIGINT, body VARCHAR)", "race again") &&
		     Exec(setup, "INSERT INTO lake.main.race SELECT i, 'neww w' || (i % 5) FROM range(0, 1000) r(i) ORDER BY i",
		          "race new rows") &&
		     IndexTable(setup, "race", {"body"}, data, "lake", "lake.main.race#new");
		int64_t new_files = 0;
		ok = ok && BuildSegments(setup, sysc, "race", data, new_files);
		auto new_table_id = Int(setup, "SELECT table_id FROM __ducklake_metadata_lake.ducklake_table WHERE table_name = "
		                               "'race' AND end_snapshot IS NULL");
		Rows olds, news;
		std::string e1, e2;
		qod::ResetLastSearchCallForTest();
		auto ran_old = Try(client, Search("SELECT rowid FROM lake.main.race", "'oldw'", "'lake.main.race'"), olds, e1);
		auto ran_new = Try(client, Search("SELECT rowid FROM lake.main.race", "'neww'", "'lake.main.race'"), news, e2);
		Exec(client, "COMMIT", "commit");
		bool own = ran_old && olds.size() == 10 && ran_new && news.empty();
		bool refused = !ran_old && e1.find("QoD: search_") != std::string::npos;
		Check(ok && old_record.table_id != new_table_id && (own || refused),
		      "m1 a drop and recreate after the client's snapshot: the client's own table under its own record (or a "
		      "refusal), never the other table's",
		      own ? (!ok ? "the setup failed" : old_record.table_id == new_table_id ? "the same table id" : "its own table")
		          : ran_old ? std::to_string(olds.size()) + " old hits, " + std::to_string(news.size()) + " new hits"
		                         : FirstLine(e1));
	}

	// m2: in the allowed population search_memory_exceeded prints no figure; the table population keeps it.
	{
		Rows rows;
		std::string table_error, allowed_error;
		qod::SetSearchMemoryForTest(1000);
		auto ran_t = Try(client, Search(all), rows, table_error);
		g_population = "allowed";
		auto ran_a = Try(client, Search(all), rows, allowed_error);
		g_population = "table";
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		auto tail_a = allowed_error.substr(allowed_error.find("search_memory_exceeded") == std::string::npos
		                                       ? 0
		                                       : allowed_error.find("search_memory_exceeded"));
		bool digits = tail_a.find_first_of("0123456789") != std::string::npos;
		Check(!ran_a && allowed_error.find("QoD: search_memory_exceeded: the search needs more memory than its budget") !=
		                    std::string::npos &&
		          !digits,
		      "m2 the allowed population: search_memory_exceeded with no byte figure", FirstLine(allowed_error));
		Check(!ran_t && table_error.find("QoD: search_memory_exceeded: the search") != std::string::npos &&
		          table_error.find(" bytes") != std::string::npos,
		      "m2 the table population keeps the byte figure", FirstLine(table_error));
	}
	// m2: an index field of another type than VARCHAR, outside the call's columns, is not projected; inside them it
	// is refused.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.m2t (id BIGINT, body VARCHAR, n INTEGER)", "m2t") &&
		          IndexTable(setup, "m2t", {"body", "n"}) &&
		          Exec(setup, "INSERT INTO lake.main.m2t SELECT i, 'w' || (i % 7) || ' w1', i FROM range(0, 500) r(i)",
		               "m2t rows");
		Rows rows;
		std::string error, rerr, why;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.m2t", "'w1 w3'", "'lake.main.m2t'"), rows, error);
		auto last = qod::LastSearchCallForTest();
		auto want = Reference(setup, "lake.main.m2t", "", "SELECT rowid FROM lake.main.m2t", "w1 w3", 10, rerr);
		Check(ok && ran && last.fallback_segments >= 1 && SameHits(Hits(rows), want, true, why),
		      "m2 an INTEGER index field outside the call's columns: not projected, the search equals the reference",
		      ran ? why + rerr : FirstLine(error));
		RefusedAtRun(client, "m2 the INTEGER field as the call's column",
		             Search("SELECT rowid FROM lake.main.m2t", "'w1'", "'lake.main.m2t'", "['n']"),
		             "QoD: search_columns_unsupported: search indexes VARCHAR columns only");
	}

	// M2: the hook's metadata reads name only the call's target, and run after the statement's verdict.
	{
		std::vector<std::pair<int, std::string>> reads;
		std::mutex lock;
		qod::SearchMetadataReadObserverForTest([&](const std::string &sql) {
			std::lock_guard<std::mutex> guard(lock);
			reads.emplace_back(g_decide_seq.load(), sql);
		});
		auto before = g_decide_seq.load();
		Rows rows;
		std::string error;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.tail", kQuery, "'lake.main.tail'"), rows, error);
		qod::SearchMetadataReadObserverForTest(nullptr);
		bool named = !reads.empty();
		bool after = !reads.empty();
		for (auto &r : reads) {
			named = named && r.second.find("__ducklake_metadata_lake") != std::string::npos &&
			        r.second.find("'tail'") != std::string::npos && r.second.find("'main'") != std::string::npos &&
			        r.second.find("'pages'") == std::string::npos && r.second.find("'other'") == std::string::npos;
			after = after && r.first > before;
		}
		Check(ran && named && after,
		      "M2 the hook's metadata reads (" + std::to_string(reads.size()) +
		          ") name only the call's target, each after the statement's plan verdict",
		      ran ? (reads.empty() ? "no read" : reads.front().second) : FirstLine(error));
	}
	(void)db;
}


// ---- B1 to B4: bounds and budget (Task 11, spec 9, ruling 10) ---------------------------------------------

//! B1 to B4 on lake.main.bounds (1,000,000 rows in 8 bundled files) and lake.main.fb4 (100,000 rows in 4
//! unbundled files, read through the fallback).
void RunBounds(Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	auto insert = [](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.bounds SELECT i, 'w' || (i % 50) || ' w' || (i % 7) || ' x' || (i % 1000) FROM range(" +
		       std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = Exec(setup, "CREATE TABLE lake.main.bounds (id BIGINT, body VARCHAR)", "bounds");
	for (int f = 0; ok && f < 8; f++) {
		ok = Exec(setup, insert(int64_t(f) * 125000, int64_t(f + 1) * 125000), "bounds file " + std::to_string(f + 1));
	}
	ok = ok && Exec(setup, "CREATE TABLE lake.main.fb4 (id BIGINT, body VARCHAR)", "fb4");
	for (int f = 0; ok && f < 4; f++) {
		ok = Exec(setup,
		          "INSERT INTO lake.main.fb4 SELECT i, 'w' || (i % 50) || ' w' || (i % 7) || ' y' || i FROM range(" +
		              std::to_string(f * 25000) + ", " + std::to_string((f + 1) * 25000) + ") r(i) ORDER BY i",
		          "fb4 file " + std::to_string(f + 1));
	}
	int64_t files = 0;
	ok = ok && IndexTable(setup, "bounds", {"body"}, data) && IndexTable(setup, "fb4", {"body"}, data) &&
	     BuildSegments(setup, sysc, "bounds", data, files);
	Check(ok && files == 8, "B the fixture: bounds in 8 bundled files", std::to_string(files));
	if (!ok) {
		return;
	}
	const std::string table = "'lake.main.bounds'";
	const std::string one = "SELECT rowid FROM lake.main.bounds WHERE id % 100 = 0";
	const std::string all = "SELECT rowid FROM lake.main.bounds";

	// B1: the bitset bytes each local state charges are its touched segments' ceil(num_docs / 64) words.
	for (auto &input : {std::make_pair(std::string("1%"), one), std::make_pair(std::string("100%"), all)}) {
		Rows rows;
		std::string error, rerr, why;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, Search(input.second, "'w1'", table), rows, error);
		auto record = qod::LastSearchCallForTest();
		auto want = Reference(setup, "lake.main.bounds", "", input.second, "w1", 10, rerr);
		bool exact = record.local_bitset_bytes.size() == record.local_segments.size() &&
		             !record.local_bitset_bytes.empty();
		uint64_t charged = 0, all_words = 0;
		std::string detail;
		for (size_t l = 0; exact && l < record.local_segments.size(); l++) {
			uint64_t expected = 0;
			for (auto s : record.local_segments[l]) {
				exact = exact && s < record.num_docs.size();
				expected += exact ? (uint64_t(record.num_docs[s]) + 63) / 64 * 8 : 0;
			}
			exact = exact && record.local_bitset_bytes[l] == expected;
			charged += record.local_bitset_bytes[l];
			if (l < 3) {
				detail += std::to_string(record.local_bitset_bytes[l]) + " bytes for " +
				          std::to_string(record.local_segments[l].size()) + " segments; ";
			}
		}
		for (auto n : record.num_docs) {
			all_words += (uint64_t(n) + 63) / 64;
		}
		Check(ran && rerr.empty() && SameHits(Hits(rows), want, true, why) && record.num_docs.size() == 8,
		      "T11 B1 " + input.first + ": the reference result", ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
		Check(ran && exact,
		      "T11 B1 " + input.first + ": each local state charged sum(ceil(num_docs / 64)) * 8 bytes for the segments it "
		      "touched, never a function of the largest rowid",
		      std::to_string(record.local_bitset_bytes.size()) + " local states: " + detail + "all segments' words " +
		          std::to_string(all_words * 8) + " bytes, charged in all " + std::to_string(charged));
	}

	// B2: a budget just below what the node charges before the library call refuses at the node; the least budget
	// the search runs under gives the reference, one byte less refuses. One local state, so the charges repeat.
	Exec(setup, "SET threads = 1", "threads 1");
	{
		Rows rows;
		std::string error, rerr, why;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, Search(one, "'w1'", table), rows, error);
		auto normal = qod::LastSearchCallForTest();
		auto held = normal.held_bytes;
		auto want = Reference(setup, "lake.main.bounds", "", one, "w1", 10, rerr);
		Check(ran && normal.called && held > 0 && normal.memory_peak >= held, "T11 B2 the bytes the node holds at the library call",
		      std::to_string(held) + " held, peak " + std::to_string(normal.memory_peak));
		auto run = [&](uint64_t budget, Rows &out, std::string &err, qod::SearchCallRecord &rec) {
			qod::SetSearchMemoryForTest(budget);
			qod::ResetLastSearchCallForTest();
			auto r = Try(client, Search(one, "'w1'", table), out, err);
			rec = qod::LastSearchCallForTest();
			qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
			return r;
		};
		qod::SearchCallRecord rec;
		ran = run(normal.memory_peak - 1, rows, error, rec);
		Check(!ran && rows.empty() && !rec.called && error.find("QoD: search_memory_exceeded: the search") != std::string::npos &&
		          error.find(" bytes") != std::string::npos,
		      "T11 B2 a budget one byte below the node's peak: search_memory_exceeded at the node (the library never called), "
		      "no row, the figure kept in the table population",
		      ran ? "it ran" : FirstLine(error));
		g_population = "allowed";
		ran = run(normal.memory_peak - 1, rows, error, rec);
		g_population = "table";
		auto tail = error.substr(std::min(error.size(), error.find("search_memory_exceeded")));
		Check(!ran && rows.empty() &&
		          error.find("QoD: search_memory_exceeded: the search needs more memory than its budget") != std::string::npos &&
		          tail.find_first_of("0123456789") == std::string::npos,
		      "T11 B2 the same in the allowed population: no byte figure", FirstLine(error));
		uint64_t lo = normal.memory_peak - 1, hi = uint64_t(4) << 30;
		while (lo + 1 < hi) {
			auto mid = lo + (hi - lo) / 2;
			Rows r;
			std::string e;
			if (run(mid, r, e, rec)) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		Rows at, below;
		std::string at_error, below_error;
		auto ran_at = run(hi, at, at_error, rec);
		auto ran_below = run(hi - 1, below, below_error, rec);
		Check(ran_at && SameHits(Hits(at), want, true, why) && !ran_below && below.empty() &&
		          below_error.find("QoD: search_memory_exceeded") != std::string::npos,
		      "T11 B2 the least budget the search runs under (" + std::to_string(hi) + " bytes, the node holding " +
		          std::to_string(normal.memory_peak) + "): the reference; one byte less: search_memory_exceeded, no row",
		      ran_at ? (ran_below ? "one byte less ran" : FirstLine(below_error)) : FirstLine(at_error));
	}
	Exec(setup, "SET threads = 16", "threads 16");

	// B3: the fallback's builders are bounded jointly. fb4's 4 files have no bundle; with every builder held open
	// until the scan ends, a budget above any one builder's memory and below their sum refuses.
	{
		const std::string fb = "SELECT rowid FROM lake.main.fb4";
		const std::string fbt = "'lake.main.fb4'";
		Rows rows;
		std::string error, rerr, why;
		qod::search::HoldFallbackBuildersForTest(true);
		auto ran = Try(client, Search(fb, "'w1'", fbt), rows, error);
		auto held_open = qod::search::FallbackCountersForTest();
		qod::search::HoldFallbackBuildersForTest(false);
		auto want = Reference(setup, "lake.main.fb4", "", fb, "w1", 10, rerr);
		Check(ran && SameHits(Hits(rows), want, true, why) && held_open.builder_max > 0 &&
		          held_open.builders_open_peak >= held_open.builder_sum * 9 / 10,
		      "T11 B3 every builder held open: the reference, its builders all open at once",
		      std::to_string(held_open.builder_max) + " the largest, " + std::to_string(held_open.builder_sum) +
		          " in all, " + std::to_string(held_open.builders_open_peak) + " open at once" +
		          (ran ? "" : ": " + FirstLine(error)));
		// Below their sum, above one builder with its finish (three times it: the builder and its finish's bound).
		auto budget = held_open.builder_sum - held_open.builder_max / 4;
		Check(budget > 3 * held_open.builder_max, "T11 B3 the budget holds one builder and its finish",
		      std::to_string(budget) + " against " + std::to_string(3 * held_open.builder_max));
		qod::SetSearchMemoryForTest(budget);
		qod::search::HoldFallbackBuildersForTest(true);
		ran = Try(client, Search(fb, "'w1'", fbt), rows, error);
		qod::search::HoldFallbackBuildersForTest(false);
		Check(!ran && rows.empty() && error.find("QoD: search_memory_exceeded") != std::string::npos,
		      "T11 B3 a budget of " + std::to_string(budget) + " bytes, above each builder's and below their sum, with every "
		      "builder open: search_memory_exceeded (the joint bound), no row",
		      ran ? "it ran" : FirstLine(error));
		g_population = "allowed";
		qod::search::HoldFallbackBuildersForTest(true);
		auto ran_allowed = Try(client, Search(fb, "'w1'", fbt), rows, error);
		qod::search::HoldFallbackBuildersForTest(false);
		g_population = "table";
		auto tail = error.substr(std::min(error.size(), error.find("search_memory_exceeded")));
		Check(!ran_allowed && rows.empty() &&
		          error.find("QoD: search_memory_exceeded: the search needs more memory than its budget") != std::string::npos &&
		          tail.find_first_of("0123456789") == std::string::npos,
		      "T11 B3 the joint bound in the allowed population: no byte figure", ran_allowed ? "it ran" : FirstLine(error));
		ran = Try(client, Search(fb, "'w1'", fbt), rows, error);
		auto sequential = qod::search::FallbackCountersForTest();
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Check(ran && SameHits(Hits(rows), want, true, why) && sequential.builders_open_peak <= sequential.builder_max * 11 / 10,
		      "T11 B3 the same budget with the scan's own order (one builder open at a time): the reference",
		      (ran ? why : FirstLine(error)) + "; open at once " + std::to_string(sequential.builders_open_peak));
		// Review I2: while a segment is opened the query holds both the bundle and its copy; the bundle's charge goes
		// only once it is freed.
		Check(sequential.last_bundle > 0 && sequential.held_at_open >= sequential.held_after_open + sequential.last_bundle,
		      "T11 B3 the bundle stays charged beside its copy while the segment opens, released once freed",
		      std::to_string(sequential.held_at_open) + " held at the open, " + std::to_string(sequential.held_after_open) +
		          " after, bundle " + std::to_string(sequential.last_bundle));
	}

	// B4: cancellation in the drain, in the fallback's indexing and between segments: an interrupt, no row.
	{
		Rows rows;
		std::string error;
		qod::search::InterruptAfterRowsForTest(100000);
		auto ran = Try(client, Search(all, "'w1'", table), rows, error);
		qod::search::InterruptAfterRowsForTest(0);
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos,
		      "T11 B4 an interrupt after 100,000 drained rows: DuckDB's interrupt error, no row", ran ? "it ran" : FirstLine(error));
		qod::search::InterruptFallbackAfterRowsForTest(70000);
		ran = Try(client, Search("SELECT rowid FROM lake.main.fb4", "'w1'", "'lake.main.fb4'"), rows, error);
		qod::search::InterruptFallbackAfterRowsForTest(0);
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos,
		      "T11 B4 an interrupt at 70,000 docs of fallback indexing: DuckDB's interrupt error, no row",
		      ran ? "it ran" : FirstLine(error));
		qod::ResetLastSearchCallForTest();
		qod::InterruptBetweenSegmentsForTest(3);
		ran = Try(client, Search(one, "'w1'", table), rows, error);
		qod::InterruptBetweenSegmentsForTest(0);
		auto record = qod::LastSearchCallForTest();
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos && !record.called,
		      "T11 B4 an interrupt after 3 of 8 segments in Finish: DuckDB's interrupt error before the library call, no row",
		      ran ? "it ran" : FirstLine(error) + (record.called ? " (the library was called)" : ""));
		qod::ResetLastSearchCallForTest();
		qod::InterruptBetweenSegmentLoadsForTest(3);
		ran = Try(client, Search(one, "'w1'", table), rows, error);
		qod::InterruptBetweenSegmentLoadsForTest(0);
		record = qod::LastSearchCallForTest();
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos && !record.called,
		      "T11 B4 an interrupt between segment loads (review M8): DuckDB's interrupt error before any search, no row",
		      ran ? "it ran" : FirstLine(error));
		Exec(client, "SELECT 1", "the connection after the interrupts");
	}

	// Review M4: DuckDB's memory_limit refusing an allowed bitset names the block's size; in the allowed population
	// the refusal reads with no figure.
	{
		Rows rows;
		std::string table_error, allowed_error;
		qod::search::FailBufferWordsForTest(true);
		auto ran_t = Try(client, Search(one, "'w1'", table), rows, table_error);
		g_population = "allowed";
		auto ran_a = Try(client, Search(one, "'w1'", table), rows, allowed_error);
		g_population = "table";
		qod::search::FailBufferWordsForTest(false);
		auto tail = allowed_error.substr(std::min(allowed_error.size(), allowed_error.find("search_memory_exceeded")));
		Check(!ran_a && rows.empty() &&
		          allowed_error.find("QoD: search_memory_exceeded: the search needs more memory than its budget") !=
		              std::string::npos &&
		          tail.find_first_of("0123456789") == std::string::npos,
		      "T11 M4 a bitset memory_limit refuses, the allowed population: no figure", FirstLine(allowed_error));
		Check(!ran_t && table_error.find("could not allocate block of size") != std::string::npos,
		      "T11 M4 the same in the table population: DuckDB's own text", FirstLine(table_error));
	}

	// Review M7: rowids that do not start at 0. 1,000,000 rows inserted and deleted, then 100,000 more in 2 files:
	// the live rowids run from 1,000,000, and each local state's bitsets follow the 2 live segments' docs.
	{
		bool ok2 = Exec(setup, "CREATE TABLE lake.main.offset (id BIGINT, body VARCHAR)", "offset") &&
		           Exec(setup, "INSERT INTO lake.main.offset SELECT i, 'w1 x' || i FROM range(0, 1000000) r(i)", "offset old") &&
		           Exec(setup, "DELETE FROM lake.main.offset", "offset delete");
		for (int f = 0; ok2 && f < 2; f++) {
			ok2 = Exec(setup,
			           "INSERT INTO lake.main.offset SELECT i, 'w' || (i % 50) || ' w1 y' || i FROM range(" +
			               std::to_string(1000000 + f * 50000) + ", " + std::to_string(1000000 + (f + 1) * 50000) +
			               ") r(i) ORDER BY i",
			           "offset file " + std::to_string(f + 1));
		}
		int64_t offset_files = 0;
		ok2 = ok2 && IndexTable(setup, "offset", {"body"}, data) && BuildSegments(setup, sysc, "offset", data, offset_files);
		auto least = ok2 ? Int(setup, "SELECT min(rowid) FROM lake.main.offset") : -1;
		auto largest = ok2 ? Int(setup, "SELECT max(rowid) FROM lake.main.offset") : -1;
		Rows rows;
		std::string error, rerr, why;
		qod::ResetLastSearchCallForTest();
		auto ran = ok2 && Try(client, Search("SELECT rowid FROM lake.main.offset", "'w1'", "'lake.main.offset'"), rows, error);
		auto record = qod::LastSearchCallForTest();
		auto want = Reference(setup, "lake.main.offset", "", "SELECT rowid FROM lake.main.offset", "w1", 10, rerr);
		bool exact = !record.local_bitset_bytes.empty();
		uint64_t most = 0;
		for (size_t l = 0; exact && l < record.local_segments.size(); l++) {
			uint64_t expected = 0;
			for (auto seg : record.local_segments[l]) {
				expected += (uint64_t(record.num_docs[seg]) + 63) / 64 * 8;
			}
			exact = exact && record.local_bitset_bytes[l] == expected;
			most = std::max(most, record.local_bitset_bytes[l]);
		}
		Check(ran && least >= 1000000 && SameHits(Hits(rows), want, true, why) && exact && record.num_docs.size() == 2 &&
		          most * 8 < uint64_t(largest),
		      "T11 B1 rowids from " + std::to_string(least) + " to " + std::to_string(largest) +
		          ": the reference; each local state charged its touched segments' words, at most " + std::to_string(most) +
		          " bytes, far below a bitset of the largest rowid (" + std::to_string((largest + 64) / 64 * 8) + ")",
		      ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
	}
}

// ---- C0 to C6: the caches against memory_limit, the borrowed open, missing bundles (Task 12) ---------------

//! The process's resident size now (not the peak, review M6).
uint64_t ResidentBytes() {
#ifdef __APPLE__
	mach_task_basic_info info;
	mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
	if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, reinterpret_cast<task_info_t>(&info), &count) != KERN_SUCCESS) {
		return 0;
	}
	return info.resident_size;
#elif defined(_WIN32)
	// No /proc and no sysconf: not measured on Windows. The figure is only reported (C0's line), never checked.
	return 0;
#else
	std::ifstream statm("/proc/self/statm");
	uint64_t size = 0, resident = 0;
	statm >> size >> resident;
	return resident * static_cast<uint64_t>(::sysconf(_SC_PAGESIZE));
#endif
}

//! The EXTENSION tag's buffer-manager usage of `db` (what duckdb_memory() reports for it).
uint64_t ExtensionBytes(DatabaseInstance &db) {
	for (auto &info : BufferManager::GetBufferManager(db).GetMemoryUsageInfo()) {
		if (info.tag == MemoryTag::EXTENSION) {
			return info.size;
		}
	}
	return 0;
}

std::string Pct(double part, double whole) {
	std::ostringstream out;
	out.precision(2);
	out << std::fixed << (whole > 0 ? 100.0 * part / whole : 0.0) << "%";
	return out.str();
}

//! C0 (Task 12 Step 1): the heap a borrowed segment adds beyond its bundle's bytes, on a 1M-doc bundle: the corpus
//! QOD_SEARCH_C0_CORPUS names (a Parquet file with a `body` column; the part 2 pages_1m in the task's run), else a
//! generated corpus of 1,000,000 docs of 20 to 49 tokens. Built with the library, held in C++ memory, opened with
//! qs_segment_open_borrowed and searched once; the library's own counter (qs_test_allocated_bytes) before the open,
//! after it and after the search, cross-checked by the resident size.
void RunC0(Connection &setup) {
#ifndef QOD_SEARCH_TEST_HOOKS
	(void)setup;
	Say("== C0 skipped: it reads the library's allocation counter, which only a build with QOD_SEARCH_TEST_HOOKS has");
#else
	auto corpus = std::getenv("QOD_SEARCH_C0_CORPUS");
	bool from_file = corpus && *corpus && fs::exists(corpus);
	// In batches of 100,000 rows, so the corpus is never held whole.
	auto batch = [&](uint64_t from, uint64_t to) {
		return from_file ? "SELECT body FROM read_parquet(" + Quote(corpus) + ", file_row_number = true) WHERE "
		                       "file_row_number >= " + std::to_string(from) + " AND file_row_number < " + std::to_string(to) +
		                       " ORDER BY file_row_number"
		                 : "SELECT array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
		                   "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ') FROM "
		                   "range(" + std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	qod_test::ReferenceSource ref("/qod-c0/pages_1m", {"body"});
	char *err = nullptr;
	auto builder = qs_builder_new(&ref.source, nullptr, UINT64_MAX, &err);
	if (!builder) {
		Check(false, "C0 a builder", Take(err));
		return;
	}
	auto start = std::chrono::steady_clock::now();
	uint64_t p = 0;
	bool added = true;
	for (uint64_t from = 0; from < 1000000 && added; from += 100000) {
		auto result = setup.Query(batch(from, from + 100000));
		if (result->HasError()) {
			qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
			Check(false, "C0 the corpus reads", FirstLine(result->GetError()));
			return;
		}
		uint64_t got = 0;
		while (added) {
			auto chunk = result->Fetch();
			if (!chunk || chunk->size() == 0) {
				break;
			}
			for (idx_t r = 0; r < chunk->size() && added; r++) {
				auto value = chunk->GetValue(0, r);
				std::string text = value.IsNull() ? std::string() : value.ToString();
				QsText cell = text.empty() ? QsText {nullptr, 0} : QsText {text.data(), text.size()};
				added = qs_builder_add(builder, p, static_cast<int64_t>(p), -1, &cell, 1, &err) == 0;
				p++;
				got++;
			}
		}
		if (got == 0) {
			break;
		}
	}
	if (!added) {
		Check(false, "C0 the corpus indexes", Take(err));
		qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
		return;
	}
	uint8_t *bytes = nullptr;
	size_t len = 0;
	uint64_t docs = 0;
	if (qs_builder_finish(builder, &bytes, &len, &docs, &err) != 0) {
		Check(false, "C0 the bundle builds", Take(err));
		return;
	}
	// The bundle's bytes in C++ memory, as the node holds them (a buffer-manager block): outside the library's count.
	std::vector<uint8_t> bundle(bytes, bytes + len);
	qs_bytes_free(bytes, len);
	auto build_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
	std::vector<uint64_t> live((docs + 63) / 64, ~uint64_t(0));
	if (docs % 64) {
		live.back() = (uint64_t(1) << (docs % 64)) - 1;
	}
	std::vector<QsHit> out(10);
	const uint64_t *lives[] = {live.data()};
	size_t words[] = {live.size()};
	auto a0 = qs_test_allocated_bytes();
	auto r0 = ResidentBytes();
	auto segment = qs_segment_open_borrowed(bundle.data(), bundle.size(), &ref.source, &err);
	if (!segment) {
		Check(false, "C0 the borrowed open", Take(err));
		return;
	}
	auto a1 = qs_test_allocated_bytes();
	auto r1 = ResidentBytes();
	const QsSegment *segments[] = {segment};
	QsSearchArgs args {};
	args.segments = segments;
	args.n_segments = 1;
	args.live = lives;
	args.live_words = words;
	args.population = QS_POPULATION_TABLE;
	args.query = "w1 w10";
	args.fields = ref.fields.data();
	args.n_fields = ref.fields.size();
	args.k1 = 1.2f;
	args.b = 0.75f;
	args.k = 10;
	args.path = QS_PATH_EXHAUSTIVE;
	args.memory_budget = UINT64_MAX;
	size_t n = 0;
	auto rc = qs_search(&args, out.data(), out.size(), &n, &err);
	auto a2 = qs_test_allocated_bytes();
	auto r2 = ResidentBytes();
	auto reported = qs_segment_heap_bytes(segment);
	qs_segment_free(segment);
	auto a3 = qs_test_allocated_bytes();
	auto opened = static_cast<int64_t>(a1) - static_cast<int64_t>(a0);
	auto searched = static_cast<int64_t>(a2) - static_cast<int64_t>(a0);
	auto ratio = static_cast<double>(std::max(opened, searched)) / static_cast<double>(bundle.size());
	Say("== C0 corpus " + std::string(from_file ? "pages_1m (QOD_SEARCH_C0_CORPUS)" : "generated") + ": " +
	    std::to_string(docs) + " docs, bundle " + std::to_string(bundle.size()) + " bytes, built in " + Ms(build_ms) +
	    " ms; borrowed open: library heap +" + std::to_string(opened) + " bytes (" + Pct(double(opened), double(bundle.size())) +
	    " of the bundle), qs_segment_heap_bytes " + std::to_string(reported) + "; after one search +" +
	    std::to_string(searched) + "; after the free " + std::to_string(static_cast<int64_t>(a3) - static_cast<int64_t>(a0)) +
	    "; resident +" + std::to_string(static_cast<int64_t>(r1) - static_cast<int64_t>(r0)) + " at the open, +" +
	    std::to_string(static_cast<int64_t>(r2) - static_cast<int64_t>(r0)) + " after the search");
	Say(std::string("== C0 ") + (ratio > 0.10 ? "the derived heap exceeds 10% of the bundle: the owner's fallback "
	                                             "(a reservation of qs_segment_heap_bytes per cache entry) applies"
	                                           : "the derived heap is within 10% of the bundle"));
	Check(rc == 0 && n == 10, "C0 the search over the borrowed 1M bundle answers 10 hits", rc == 0 ? "" : Take(err));
	Check(opened >= 0 && searched <= opened && static_cast<uint64_t>(opened) <= reported &&
	          reported - static_cast<uint64_t>(opened) <= 4096,
	      "C0 qs_segment_heap_bytes is what the borrowed open keeps on the library's heap, and a search keeps nothing "
	      "more",
	      "measured " + std::to_string(opened) + ", after the search " + std::to_string(searched) + ", reported " +
	          std::to_string(reported));
#endif
}

//! The bundle bytes on disk of `table`'s index under `root`.
uint64_t BundleBytesOnDisk(const std::string &root, const std::string &table) {
	auto folder = fs::path(root) / ".qod_search" / RecordOf(table).index_id;
	uint64_t total = 0;
	std::error_code ec;
	for (auto &entry : fs::directory_iterator(folder, ec)) {
		if (entry.path().extension() == ".seg") {
			total += fs::file_size(entry.path(), ec);
		}
	}
	return total;
}

//! The bundle path of `table`'s only data file under `root`, and the file's path.
std::string OnlyBundlePath(Connection &setup, const std::string &root, const std::string &table, std::string &file) {
	Rows listed;
	std::string error;
	Try(setup, "SELECT data_file FROM ducklake_list_files('lake', " + Quote(table) + ")", listed, error);
	if (listed.size() != 1) {
		return "";
	}
	file = listed[0][0].GetValue<std::string>();
	return qod::search::SegmentPath(root, RecordOf(table).index_id, qod::Hex(qod::Sha256(file)));
}

//! One search of `table` on `con` ('w1 w10', input `input` or the whole table), with its call record and its
//! reference (a fresh segment of the superuser's live rows), compared bit for bit.
struct CRun {
	bool ran = false;
	bool same = false;
	std::vector<Hit> hits;
	qod::SearchCallRecord record;
	std::string error;
};

CRun CSearch(Connection &setup, Connection &con, const std::string &table, const std::string &input = "") {
	CRun run;
	auto in = input.empty() ? "SELECT rowid FROM lake.main." + table : input;
	Rows rows;
	qod::ResetLastSearchCallForTest();
	run.ran = Try(con, Search(in, kQuery, Quote("lake.main." + table)), rows, run.error);
	run.record = qod::LastSearchCallForTest();
	run.hits = Hits(rows);
	std::string rerr, why;
	auto want = Reference(setup, "lake.main." + table, "", in, "w1 w10", 10, rerr);
	run.same = run.ran && rerr.empty() && !want.empty() && SameHits(run.hits, want, true, why);
	if (!run.same && run.error.empty()) {
		run.error = rerr.empty() ? why : rerr;
	}
	return run;
}

//! The opens of `path` that `con`'s counting FileSystem saw while `run` ran.
idx_t OpensOf(const std::string &path, const std::function<void()> &run) {
	{
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		g_fs_opens.clear();
		g_fs_counting = true;
	}
	run();
	std::lock_guard<std::mutex> lock(g_fs_mutex);
	g_fs_counting = false;
	return g_fs_opens.count(path) ? g_fs_opens[path] : 0;
}

void RunCaches(DuckDB &db, Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	auto insert = [](const std::string &table, int64_t from, int64_t to) {
		return "INSERT INTO lake.main." + table + " SELECT i, 'w' || (i % 50) || ' w' || (i % 7) || ' x' || (i % 1000) "
		       "FROM range(" + std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = true;
	for (std::string table : {"c1", "c2", "c3a", "c3b", "c4", "c4b", "c4r", "c4f", "c5", "c6", "c6at"}) {
		ok = ok && Exec(setup, "CREATE TABLE lake.main." + table + " (id BIGINT, body VARCHAR)", table);
	}
	// c2: one file of 400,000 docs of 20 to 49 tokens, whose bundle is larger than the database's other buffer memory.
	ok = ok && Exec(setup,
	                "INSERT INTO lake.main.c2 SELECT i, array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), "
	                "lambda j: 'w' || floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ') "
	                "FROM range(0, 400000) r(i) ORDER BY i",
	                "c2 rows");
	ok = ok && Exec(setup, insert("c1", 0, 20000), "c1 file 1") && Exec(setup, insert("c1", 20000, 40000), "c1 file 2") &&
	     Exec(setup, insert("c3a", 0, 30000), "c3a") && Exec(setup, insert("c3b", 30000, 60000), "c3b");
	for (std::string table : {"c4", "c4b", "c4r", "c4f", "c5", "c6", "c6at"}) {
		ok = ok && Exec(setup, insert(table, 0, 5000), table + " rows");
	}
	for (std::string table : {"c1", "c2", "c3a", "c3b", "c4", "c4b", "c4r", "c4f", "c5", "c6at"}) {
		ok = ok && IndexTable(setup, table, {"body"}, data);
	}
	int64_t files = 0;
	ok = ok && BuildSegments(setup, sysc, "c1", data, files) && files == 2 && BuildSegments(setup, sysc, "c2", data, files) &&
	     files == 1 && BuildSegments(setup, sysc, "c3a", data, files) &&
	     BuildSegments(setup, sysc, "c3b", data, files);
	Check(ok, "C the fixture: c1 in 2 bundled files, c3a and c3b bundled, c4, c4b, c4r, c5 unbundled, c6 unindexed");
	if (!ok) {
		return;
	}
	auto c1_bundles = BundleBytesOnDisk(data, "c1");

	// C1: every byte the caches hold is the buffer manager's (EXTENSION), under a memory_limit.
	{
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		Exec(setup, "SET memory_limit = '512MB'", "C1 memory_limit");
		auto run = CSearch(setup, client, "c1");
		auto seg = qod::search::SegmentCacheCountersForTest();
		auto live = qod::search::LivenessCacheCountersForTest();
		auto ext = Int(setup, "SELECT sum(memory_usage_bytes)::BIGINT FROM duckdb_memory() WHERE tag = 'EXTENSION'");
		auto expected = seg.bundle_bytes + seg.rowid_bytes + seg.reserved_bytes + live.block_bytes;
		Check(run.same && seg.entries == 2 && seg.bundle_bytes == c1_bundles && seg.reserved_bytes > 0 &&
		          live.block_bytes > 0 && ext >= 0 && static_cast<uint64_t>(ext) >= expected,
		      "C1 under memory_limit 512MB: duckdb_memory()'s EXTENSION usage holds the open bundles, their lists and "
		      "reservations, and the liveness entries' bitsets",
		      run.same ? "EXTENSION " + std::to_string(ext) + " bytes, expected at least " + std::to_string(expected) +
		                     " (bundles " + std::to_string(seg.bundle_bytes) + " of " + std::to_string(c1_bundles) +
		                     " on disk, lists " + std::to_string(seg.rowid_bytes) + ", reserved heap " +
		                     std::to_string(seg.reserved_bytes) + ", liveness " + std::to_string(live.block_bytes) + ")"
		               : run.error);
		Say("== C1 EXTENSION " + std::to_string(ext) + " bytes; cache: " + std::to_string(seg.entries) + " entries, " +
		    std::to_string(seg.bundle_bytes) + " bundle bytes, " + std::to_string(seg.reserved_bytes) +
		    " reserved heap bytes (" + Pct(double(seg.reserved_bytes), double(seg.bundle_bytes)) +
		    " of the bundles); liveness " + std::to_string(live.block_bytes) + " block bytes");
		Exec(setup, "RESET memory_limit", "C1 reset memory_limit");
	}

	// C2: the cache's reservation refused: the search keeps its copy as temporary memory, counted, and the cache
	// holds nothing; under a memory_limit below one bundle, search_memory_exceeded and no row.
	{
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		qod::search::RefuseCacheReservationForTest(true);
		qod::search::ResetTemporaryBundleBytesForTest();
		std::atomic<uint64_t> during {0};
		qod::SearchCallObserverForTest([&](ClientContext &context) {
			auto now = ExtensionBytes(*context.db);
			if (now > during.load()) {
				during = now;
			}
		});
		auto before = qod::search::SegmentCacheCountersForTest();
		auto run = CSearch(setup, client, "c1");
		qod::SearchCallObserverForTest(nullptr);
		qod::search::RefuseCacheReservationForTest(false);
		auto after = qod::search::SegmentCacheCountersForTest();
		auto temporary = qod::search::TemporaryBundleBytesForTest();
		Check(run.same && after.entries == 0 && after.temporary == before.temporary + 2 && temporary >= c1_bundles &&
		          during.load() >= c1_bundles,
		      "C2 the cache's reservation refused: the reference result, the bundles kept as the search's temporary "
		      "memory (counted in EXTENSION during the search), the cache empty",
		      run.same ? "temporary " + std::to_string(after.temporary - before.temporary) + " bundles, " +
		                     std::to_string(temporary) + " bytes charged, EXTENSION during the search " +
		                     std::to_string(during.load()) + ", bundles " + std::to_string(c1_bundles) + ", entries " +
		                     std::to_string(after.entries)
		               : run.error);
		// memory_limit below one bundle (c2's, a single file larger than the database's other buffer memory): no block
		// for it, search_memory_exceeded, no row.
		auto one = BundleBytesOnDisk(data, "c2");
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		auto used_now = static_cast<uint64_t>(
		    std::max<int64_t>(Int(setup, "SELECT sum(memory_usage_bytes)::BIGINT FROM duckdb_memory()"), 0));
		auto limit = used_now + one / 2;
		Exec(setup, "SET memory_limit = '" + std::to_string(limit) + "B'", "C2 memory_limit below one bundle");
		Rows rows;
		std::string error;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.c2", kQuery, "'lake.main.c2'"), rows, error);
		Exec(setup, "RESET memory_limit", "C2 reset memory_limit");
		auto left = qod::search::SegmentCacheCountersForTest();
		Check(limit < one && !ran && rows.empty() && error.find("QoD: search_memory_exceeded") != std::string::npos &&
		          left.entries == 0,
		      "C2 memory_limit below one bundle (" + std::to_string(limit) + " bytes, the other buffer memory " +
		          std::to_string(used_now) + ", the bundle " + std::to_string(one) +
		          "): search_memory_exceeded, no row, nothing cached",
		      ran ? "it ran: " + Show(Hits(rows)) : FirstLine(error));
		// The real memory_limit path (review I2): a limit whose quarter (the search caches' share) is below the
		// entry's held bytes while its free room holds them: the cache does not take the bundle, the search reads it
		// as its own copy, counted in EXTENSION while it runs, and returns the reference result.
		// The reference first: it reads every row of c2, which the limit is not sized for.
		std::string rerr, why;
		auto want = Reference(setup, "lake.main.c2", "", "SELECT rowid FROM lake.main.c2", "w1 w10", 10, rerr);
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		auto used = static_cast<uint64_t>(
		    std::max<int64_t>(Int(setup, "SELECT sum(memory_usage_bytes)::BIGINT FROM duckdb_memory()"), 0));
		auto above = used + one + (uint64_t(64) << 20);
		// The bundle is above a quarter of this limit, so under the default budget (a quarter of memory_limit, Task 16)
		// its copy would pass the search's budget too: this case is about the cache, so the budget is fixed above it.
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
		Exec(setup, "SET memory_limit = '" + std::to_string(above) + "B'", "C2 memory_limit above one bundle");
		during = 0;
		qod::SearchCallObserverForTest([&](ClientContext &context) {
			auto now = ExtensionBytes(*context.db);
			if (now > during.load()) {
				during = now;
			}
		});
		auto t0 = qod::search::SegmentCacheCountersForTest();
		Rows edge_rows;
		std::string edge_error;
		auto edge_ran =
		    Try(client, Search("SELECT rowid FROM lake.main.c2", kQuery, "'lake.main.c2'"), edge_rows, edge_error);
		qod::SearchCallObserverForTest(nullptr);
		auto t1 = qod::search::SegmentCacheCountersForTest();
		Exec(setup, "RESET memory_limit", "C2 reset memory_limit");
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		auto edge_same = edge_ran && rerr.empty() && !want.empty() && SameHits(Hits(edge_rows), want, true, why);
		Check(one > above / 4 && edge_same && t1.temporary == t0.temporary + 1 && t1.entries == 0 &&
		          during.load() >= one && during.load() <= above,
		      "C2 memory_limit " + std::to_string(above) + " bytes (a quarter below the bundle's " + std::to_string(one) +
		          ", the free room above it): the reference result, the bundle kept as the search's copy, counted in "
		          "EXTENSION during the search, the cache empty after",
		      edge_same ? "temporary +" + std::to_string(t1.temporary - t0.temporary) + ", entries " +
		                      std::to_string(t1.entries) + ", EXTENSION during the search " + std::to_string(during.load())
		                : edge_ran ? (rerr.empty() ? why : rerr) : FirstLine(edge_error));
		qod::search::ClearSegmentCacheForTest();
	}

	// C3: eviction under a byte bound of one bundle, and an entry a running search holds stays. The liveness cache is
	// off here, so the open segments have the search caches' whole share (review I1).
	{
		qod::search::SetLivenessCacheBoundsForTest(64, 0);
		qod::search::ClearSegmentCacheForTest();
		auto a = CSearch(setup, client, "c3a");
		auto held_a = qod::search::SegmentCacheCountersForTest().held_bytes;
		auto bundle_a = qod::search::SegmentCacheCountersForTest().bundle_bytes;
		qod::search::ClearSegmentCacheForTest();
		auto b = CSearch(setup, client, "c3b");
		auto held_b = qod::search::SegmentCacheCountersForTest().held_bytes;
		qod::search::ClearSegmentCacheForTest();
		qod::search::SetSegmentCacheBytesForTest(std::max(held_a, held_b));
		auto e0 = qod::search::SegmentCacheCountersForTest().evictions;
		bool alternate = a.same && b.same && held_a > 0 && held_b > 0;
		uint64_t most = 0;
		for (int i = 0; i < 6 && alternate; i++) {
			auto run = CSearch(setup, client, i % 2 ? "c3b" : "c3a");
			auto c = qod::search::SegmentCacheCountersForTest();
			most = std::max(most, c.entries);
			alternate = run.same && c.entries <= 1 && c.held_bytes <= c.byte_bound;
		}
		auto e1 = qod::search::SegmentCacheCountersForTest().evictions;
		Check(alternate && most == 1 && e1 >= e0 + 5,
		      "C3 a byte bound of one bundle, two tables searched alternately: every search is the reference, the cache "
		      "never holds more than one entry",
		      "evictions " + std::to_string(e1 - e0) + ", most entries " + std::to_string(most));
		// A search paused inside Finish holds c3a's entry; a concurrent search of c3b cannot evict it, and keeps its
		// own copy as temporary memory.
		qod::search::ClearSegmentCacheForTest();
		Connection other(db);
		Install(other, "acme/acme_db/bi");
		std::mutex m;
		std::condition_variable cv;
		bool paused = false, release = false;
		std::atomic<int> calls {0};
		qod::SearchCallObserverForTest([&](ClientContext &) {
			if (calls++ == 0) {
				std::unique_lock<std::mutex> lock(m);
				paused = true;
				cv.notify_all();
				cv.wait_for(lock, std::chrono::seconds(60), [&] { return release; });
			}
		});
		CRun held;
		std::thread first([&] { held = CSearch(setup, other, "c3a"); });
		bool reached = false;
		{
			std::unique_lock<std::mutex> lock(m);
			reached = cv.wait_for(lock, std::chrono::seconds(60), [&] { return paused; });
		}
		auto before = qod::search::SegmentCacheCountersForTest();
		auto concurrent = CSearch(setup, client, "c3b");
		auto during_pause = qod::search::SegmentCacheCountersForTest();
		{
			std::lock_guard<std::mutex> lock(m);
			release = true;
		}
		cv.notify_all();
		first.join();
		qod::SearchCallObserverForTest(nullptr);
		Check(reached && held.same && concurrent.same && during_pause.entries == 1 &&
		          during_pause.bundle_bytes == bundle_a && during_pause.temporary == before.temporary + 1,
		      "C3 an entry a running search holds is not evicted by a concurrent search, which keeps its own copy",
		      std::string(reached ? "" : "the first search never paused; ") + "entries " +
		          std::to_string(during_pause.entries) + ", bundle bytes " + std::to_string(during_pause.bundle_bytes) +
		          " (c3a " + std::to_string(bundle_a) + "), temporary +" +
		          std::to_string(during_pause.temporary - before.temporary) + (held.same ? "" : "; first: " + held.error) +
		          (concurrent.same ? "" : "; second: " + concurrent.error));
		qod::search::SetSegmentCacheBytesForTest(qod::search::kDefault);
		qod::search::SetLivenessCacheBoundsForTest(64, uint64_t(256) << 20);
		qod::search::ClearSegmentCacheForTest();
	}

	// C7: the search caches together hold at most min(QOD_SEARCH_SEGMENT_CACHE_BYTES, memory_limit / 4) (review I1).
	{
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		// The references first: c2's reads every row, which the limit is not sized for.
		std::map<std::string, std::vector<Hit>> wants;
		std::string rerr;
		for (std::string table : {"c1", "c3a", "c3b", "c2"}) {
			wants[table] = Reference(setup, "lake.main." + table, "", "SELECT rowid FROM lake.main." + table, "w1 w10", 10,
			                         rerr);
		}
		auto limit = uint64_t(96) << 20;
		// c2's bundle is above a quarter of this limit: under the default budget (a quarter of memory_limit, Task 16)
		// its copy would pass the search's budget too, so the budget is fixed above it while the caches are measured.
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
		Exec(setup, "SET memory_limit = '" + std::to_string(limit) + "B'", "C7 memory_limit");
		auto share = qod::search::SearchCachesBound(BufferManager::GetBufferManager(*db.instance));
		bool within = share == limit / 4 && rerr.empty();
		bool same = true;
		std::string shown;
		auto t0 = qod::search::SegmentCacheCountersForTest().temporary;
		for (std::string table : {"c1", "c3a", "c3b", "c2", "c1", "c3a"}) {
			Rows rows;
			std::string error, why;
			auto ran = Try(client, Search("SELECT rowid FROM lake.main." + table, kQuery, Quote("lake.main." + table)), rows,
			               error);
			auto seg = qod::search::SegmentCacheCountersForTest();
			auto live = qod::search::LivenessCacheCountersForTest();
			if (!(ran && SameHits(Hits(rows), wants[table], true, why))) {
				same = false;
				shown += table + " " + (ran ? why : FirstLine(error)) + "; ";
			}
			within = within && seg.held_bytes + live.bytes <= share;
			shown += table + " " + std::to_string(seg.held_bytes + live.bytes) + "; ";
		}
		auto t1 = qod::search::SegmentCacheCountersForTest();
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Check(same && within && t1.entries >= 1 && t1.temporary >= t0 + 1 && !wants["c2"].empty(),
		      "C7 under memory_limit 96MB the open segments and the liveness entries together stay within a quarter of "
		      "it, and c2's bundle, larger than that, is read as the search's copy",
		      "share " + std::to_string(share) + ": " + shown);
		// A share of one byte: no liveness view is stored beside it, and every bundle is the search's copy.
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		qod::search::SetSegmentCacheBytesForTest(1);
		auto l0 = qod::search::LivenessCacheCountersForTest();
		Exec(setup, "RESET memory_limit", "C7 reset memory_limit");
		auto run = CSearch(setup, client, "c1");
		auto l1 = qod::search::LivenessCacheCountersForTest();
		auto seg = qod::search::SegmentCacheCountersForTest();
		Check(run.same && l1.over_share == l0.over_share + 1 && l1.entries == 0 && seg.entries == 0,
		      "C7 a joint share of one byte: the liveness view is served and not stored, the bundles are the search's copy",
		      "over_share +" + std::to_string(l1.over_share - l0.over_share) + ", liveness entries " +
		          std::to_string(l1.entries) + ", segment entries " + std::to_string(seg.entries));
		qod::search::SetSegmentCacheBytesForTest(qod::search::kDefault);
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		// Fix round 2: the segment cache cannot starve the liveness cache. A joint bound that would hold both c3a's
		// and c3b's segments beside their two views: the segments may take no more than the bound less the liveness
		// cache's slice (an eighth), so one of them is kept, and c2's view, larger than the liveness cache's size then,
		// is stored.
		CSearch(setup, client, "c3a");
		CSearch(setup, client, "c3b");
		auto warm_seg = qod::search::SegmentCacheCountersForTest();
		auto warm_live = qod::search::LivenessCacheCountersForTest();
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		auto joint = warm_seg.held_bytes + warm_live.bytes + 1024;
		qod::search::SetSegmentCacheBytesForTest(joint);
		auto ra = CSearch(setup, client, "c3a");
		auto rb = CSearch(setup, client, "c3b");
		auto filled_seg = qod::search::SegmentCacheCountersForTest();
		auto filled_live = qod::search::LivenessCacheCountersForTest();
		// c2's search: its view is stored; its bundle (far past the bound) is the search's copy. The reference reads
		// every row, so its comparison is c2's own CSearch (no memory_limit is set here).
		auto rc = CSearch(setup, client, "c2");
		auto after_seg = qod::search::SegmentCacheCountersForTest();
		auto after_live = qod::search::LivenessCacheCountersForTest();
		Check(warm_seg.entries == 2 && ra.same && rb.same && rc.same && filled_seg.entries == 1 &&
		          filled_live.entries == 2 && after_live.entries == 3 && after_live.bytes > filled_live.bytes * 2 &&
		          after_seg.held_bytes + after_live.bytes <= joint,
		      "C7 the segment cache keeps the liveness cache's slice: under a joint bound the segments could fill, one "
		      "segment is kept and a larger new view is stored",
		      "joint " + std::to_string(joint) + ", segments " + std::to_string(filled_seg.entries) + " (" +
		          std::to_string(filled_seg.held_bytes) + " bytes), liveness " + std::to_string(filled_live.entries) +
		          " then " + std::to_string(after_live.entries) + " entries (" + std::to_string(filled_live.bytes) +
		          " then " + std::to_string(after_live.bytes) + " bytes)");
		qod::search::SetSegmentCacheBytesForTest(qod::search::kDefault);
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		// Review M5: a liveness block memory_limit refuses is asked again once the caches' unheld entries are evicted.
		CSearch(setup, client, "c3a");
		auto r0 = qod::search::SegmentCacheCountersForTest();
		auto b0 = qod::search::LivenessCacheCountersForTest();
		qod::search::RefuseLivenessBlockOnceForTest();
		auto rm = CSearch(setup, client, "c1");
		auto r1 = qod::search::SegmentCacheCountersForTest();
		auto b1 = qod::search::LivenessCacheCountersForTest();
		Check(rm.same && r0.entries == 1 && r1.evictions > r0.evictions && b1.block_refused == b0.block_refused &&
		          b1.entries == b0.entries + 1,
		      "C7 a refused liveness block: the caches' unheld entries are evicted, the block is asked again and the view "
		      "stored",
		      "segment evictions +" + std::to_string(r1.evictions - r0.evictions) + ", block_refused +" +
		          std::to_string(b1.block_refused - b0.block_refused) + ", liveness entries " + std::to_string(b0.entries) +
		          " then " + std::to_string(b1.entries));
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
	}

	// C4: missing bundles (D8), on a connection whose FileSystem counts every open.
	Connection counted(db);
	if (!WrapClientFileSystem(counted) || !Install(counted, "acme/acme_db/bi")) {
		Check(false, "C4 a counting connection");
		return;
	}
	{
		qod::search::ClearSegmentCacheForTest();
		std::string file;
		auto path = OnlyBundlePath(setup, data, "c4", file);
		CRun r1, r2, r3;
		auto o1 = OpensOf(path, [&] { r1 = CSearch(setup, counted, "c4"); });
		auto o2 = OpensOf(path, [&] { r2 = CSearch(setup, counted, "c4"); });
		Check(!path.empty() && r1.same && r2.same && o1 == 1 && o2 == 0 && r1.record.fallback_docs == 5000 &&
		          r2.record.fallback_docs == 5000,
		      "C4 one unbundled file: the first search makes one bundle open, the second none (the miss is cached)",
		      "opens " + std::to_string(o1) + " then " + std::to_string(o2) + (r1.same && r2.same ? "" : ": " + r1.error + r2.error));
		BuildSegments(setup, sysc, "c4", data, files);
		auto o3 = OpensOf(path, [&] { r3 = CSearch(setup, counted, "c4"); });
		Check(r3.same && o3 == 1 && r3.record.fallback_docs == 0,
		      "C4 qod_search_build_segment on this node publishes it: the next search opens it, indexed",
		      "opens " + std::to_string(o3) + ", fallback docs " + std::to_string(r3.record.fallback_docs) +
		          (r3.same ? "" : ": " + r3.error));
		std::string why;
		Check(r1.same && r3.same && SameHits(r1.hits, r3.hits, true, why),
		      "C4 the fallback's answer and the indexed answer are the same, bit for bit", why);
	}
	{
		std::string file;
		auto path = OnlyBundlePath(setup, data, "c4b", file);
		std::vector<idx_t> opens;
		bool same = true;
		auto step = [&]() {
			CRun run;
			opens.push_back(OpensOf(path, [&] { run = CSearch(setup, counted, "c4b"); }));
			same = same && run.same;
		};
		step();
		step();
		g_indexes.Change("lake", "c4b", [](qod::search::IndexRecord &r) { r.segments_version++; });
		step();
		step();
		qod::search::AdvanceSearchClockForTest(60 * 1000 + 1);
		step();
		step();
		qod::search::SetMissTtlForTest(0);
		step();
		step();
		qod::search::SetMissTtlForTest(qod::search::kDefault);
		std::string shown;
		for (auto o : opens) {
			shown += std::to_string(o) + " ";
		}
		Check(same && opens == std::vector<idx_t> {1, 0, 1, 0, 1, 0, 1, 1},
		      "C4 a miss lives until the record's segments_version moves and at most 60 s, and a lifetime of 0 caches "
		      "none",
		      "opens per search: " + shown);
	}
	{
		// A refused bundle (garbage under the bundle's name) is cached like a miss (review M3): read once, then no
		// FileSystem call at all, an object of another size included, until the record's version moves.
		std::string file;
		auto path = OnlyBundlePath(setup, data, "c4r", file);
		fs::create_directories(fs::path(path).parent_path());
		auto plant = [&](size_t size) {
			std::ofstream out(path, std::ios::binary | std::ios::trunc);
			std::string junk(size, 'q');
			out.write(junk.data(), static_cast<std::streamsize>(junk.size()));
		};
		plant(100000);
		std::vector<uint64_t> read;
		std::vector<idx_t> opens;
		bool same = true;
		auto step = [&]() {
			auto before = qod::search::SegmentCacheCountersForTest().bytes_read;
			CRun run;
			opens.push_back(OpensOf(path, [&] { run = CSearch(setup, counted, "c4r"); }));
			read.push_back(qod::search::SegmentCacheCountersForTest().bytes_read - before);
			same = same && run.same && run.record.fallback_docs == 5000;
		};
		auto r0 = qod::search::SegmentCacheCountersForTest().refused_hits;
		step();
		step();
		plant(100001);
		step();
		auto r1 = qod::search::SegmentCacheCountersForTest().refused_hits;
		g_indexes.Change("lake", "c4r", [](qod::search::IndexRecord &r) { r.segments_version++; });
		step();
		std::error_code ec;
		fs::remove(path, ec);
		std::string shown;
		for (size_t i = 0; i < read.size(); i++) {
			shown += std::to_string(opens[i]) + " open(s) " + std::to_string(read[i]) + " bytes; ";
		}
		Check(same && read == std::vector<uint64_t> {100000, 0, 0, 100001} && opens == std::vector<idx_t> {1, 0, 0, 1} &&
		          r1 == r0 + 2,
		      "C4 a refused bundle is cached like a miss: read once, then no FileSystem call (another object's size "
		      "included) until the record's version moves; the fallback answers every search",
		      shown);
#ifndef _WIN32
		// Only a real not-found is a miss: an index folder the node may not enter (EACCES, not ENOENT) is a read
		// failure, counted refused and never cached, so each search tries the bundle again.
		if (::geteuid() == 0) {
			Say("note C4 an index folder the node may not enter: not checked as root (root enters any folder)");
		} else {
			g_indexes.Change("lake", "c4r", [](qod::search::IndexRecord &r) { r.segments_version++; });
			auto folder = fs::path(path).parent_path();
			auto before = qod::search::SegmentCacheCountersForTest();
			fs::permissions(folder, fs::perms::none, ec);
			std::vector<idx_t> denied_opens;
			bool denied_same = true;
			for (int i = 0; i < 2; i++) {
				CRun run;
				denied_opens.push_back(OpensOf(path, [&] { run = CSearch(setup, counted, "c4r"); }));
				denied_same = denied_same && run.same && run.record.fallback_docs == 5000;
			}
			fs::permissions(folder, fs::perms::owner_all, ec);
			auto after = qod::search::SegmentCacheCountersForTest();
			Check(denied_same && after.refused == before.refused + 2 && after.missing == before.missing &&
			          after.reads == before.reads + 2 && after.missing_hits == before.missing_hits,
			      "C4 an index folder the node may not enter (mode 000): refused each time, never cached as missing; the "
			      "fallback answers",
			      "refused +" + std::to_string(after.refused - before.refused) + ", missing +" +
			          std::to_string(after.missing - before.missing) + ", reads +" +
			          std::to_string(after.reads - before.reads) + ", opens " + std::to_string(denied_opens[0]) + " " +
			          std::to_string(denied_opens[1]));
		}
#endif
	}
	{
		// Review M4: a build on this node that forgets a key while a search's probe of it is under way: the probe
		// stores no miss, so the next search opens the published bundle.
		BuildSegments(setup, sysc, "c4f", data, files);
		std::string file;
		auto path = OnlyBundlePath(setup, data, "c4f", file);
		auto aside = path + ".aside";
		std::error_code ec;
		fs::rename(path, aside, ec);
		qod::search::ClearSegmentCacheForTest();
		Rows listed;
		std::string lerr;
		Try(setup, "SELECT data_file_size_bytes, data_file_footer_size FROM ducklake_list_files('lake', 'c4f')", listed,
		    lerr);
		auto record = RecordOf("c4f");
		std::atomic<int> raced {0};
		qod::search::MissProbeObserverForTest([&]() {
			if (raced++ == 0 && listed.size() == 1) {
				// The bundle is published and its key forgotten after the probe found nothing.
				std::error_code back;
				fs::rename(aside, path, back);
				qod::search::ForgetMissingBundle(*setup.context, record.index_id, qod::Hex(qod::Sha256(file)),
				                                 listed[0][0].GetValue<uint64_t>(), listed[0][1].GetValue<uint64_t>());
			}
		});
		CRun r1, r2;
		auto o1 = OpensOf(path, [&] { r1 = CSearch(setup, counted, "c4f"); });
		qod::search::MissProbeObserverForTest(nullptr);
		auto o2 = OpensOf(path, [&] { r2 = CSearch(setup, counted, "c4f"); });
		Check(!ec && raced.load() >= 1 && r1.same && r2.same && o1 == 1 && r1.record.fallback_docs == 5000 && o2 == 1 &&
		          r2.record.fallback_docs == 0,
		      "C4 a key forgotten while a probe of it ran: the probe stores no miss, and the next search opens the bundle",
		      "opens " + std::to_string(o1) + " then " + std::to_string(o2) + ", fallback docs " +
		          std::to_string(r1.record.fallback_docs) + " then " + std::to_string(r2.record.fallback_docs));
	}

	// C5: invariant 3 under a stale miss: a bundle published behind the cache's back changes no result or score.
	{
		BuildSegments(setup, sysc, "c5", data, files);
		std::string file;
		auto path = OnlyBundlePath(setup, data, "c5", file);
		auto aside = path + ".aside";
		std::error_code ec;
		fs::rename(path, aside, ec);
		qod::search::ClearSegmentCacheForTest();
		const std::vector<std::string> inputs = {"", "SELECT rowid FROM lake.main.c5 WHERE id % 3 = 0"};
		for (auto &input : inputs) {
			CSearch(setup, counted, "c5", input);
		}
		fs::rename(aside, path, ec);
		std::vector<CRun> stale, fresh;
		for (auto &input : inputs) {
			stale.push_back(CSearch(setup, counted, "c5", input));
		}
		qod::search::ClearSegmentCacheForTest();
		for (auto &input : inputs) {
			fresh.push_back(CSearch(setup, counted, "c5", input));
		}
		bool same = !ec;
		std::string why;
		for (size_t i = 0; i < inputs.size() && same; i++) {
			same = stale[i].same && fresh[i].same && stale[i].record.fallback_docs == 5000 &&
			       fresh[i].record.fallback_docs == 0 && SameHits(stale[i].hits, fresh[i].hits, true, why);
		}
		Check(same,
		      "C5 a stale miss (the bundle published behind the cache's back): the fallback's results and scores equal "
		      "the indexed path's, bit for bit, whole table and filtered input",
		      why.empty() ? std::string(ec ? ec.message() : "") : why);
	}

	// C6: no index, a disabled one, or an unindexed column is refused before any liveness resolve.
	{
		std::atomic<int> resolves {0};
		qod::ResolveObserverForTest([&](bool begin) {
			if (begin) {
				resolves++;
			}
		});
		auto refused = [&](const std::string &name, const std::string &sql, const std::string &expected) {
			resolves = 0;
			Refused(client, name, sql, expected);
			Check(resolves.load() == 0, name + ": no liveness resolve before the refusal",
			      std::to_string(resolves.load()) + " resolves");
		};
		refused("C6 an unindexed table", Search("SELECT rowid FROM lake.main.c6", kQuery, "'lake.main.c6'"),
		        "QoD: search_index_unavailable: no index on lake.main.c6");
		g_indexes.Change("lake", "c4", [](qod::search::IndexRecord &r) { r.state = "disabled"; });
		refused("C6 a disabled index", Search("SELECT rowid FROM lake.main.c4", kQuery, "'lake.main.c4'"),
		        "QoD: search_index_unavailable");
		g_indexes.Change("lake", "c4", [](qod::search::IndexRecord &r) { r.state = "ready"; });
		refused("C6 an unindexed column", Search("SELECT rowid FROM lake.main.pages", kQuery, kTable, "['lang']"),
		        "QoD: search_columns_unsupported: lang of lake.main.pages is not indexed");
		resolves = 0;
		auto run = CSearch(setup, client, "c4");
		Check(run.same && resolves.load() == 1, "C6 an indexed search resolves its view once",
		      std::to_string(resolves.load()) + " resolves" + (run.same ? "" : ": " + run.error));
		// Review M6: an AT read keeps the view-first path. c4 searched AT a snapshot before a commit: the view of that
		// snapshot, resolved once, the reference result there.
		auto v_c4 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		Exec(setup, "INSERT INTO lake.main.c4 SELECT i, 'w1 w10 w1' FROM range(5000, 5100) r(i)", "C6 a later commit to c4");
		auto at_c4 = " AT (VERSION => " + std::to_string(v_c4) + ")";
		resolves = 0;
		Rows rows;
		std::string error, rerr, why;
		auto ran =
		    Try(setup, Search("SELECT rowid FROM lake.main.c4" + at_c4, kQuery, "'lake.main.c4'"), rows, error);
		auto want = Reference(setup, "lake.main.c4", at_c4, "SELECT rowid FROM lake.main.c4" + at_c4, "w1 w10", 10, rerr);
		Check(ran && rerr.empty() && SameHits(Hits(rows), want, true, why) && resolves.load() == 1,
		      "C6 an AT read: the view at that snapshot first, resolved once, the reference result there",
		      ran ? (rerr.empty() ? why + " " + std::to_string(resolves.load()) + " resolves" : rerr) : FirstLine(error));
		// The review's case, a table dropped and recreated since and searched AT a snapshot where its index was ready,
		// never reaches the index check: the lineage rule (Task 5) refuses an input that scans another table than the
		// one the call names now, before any resolve or record lookup.
		int64_t built = 0;
		BuildSegments(setup, sysc, "c6at", data, built);
		auto v_old = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
		Exec(setup, "DROP TABLE lake.main.c6at", "C6 drop c6at");
		Exec(setup, "CREATE TABLE lake.main.c6at (id BIGINT, body VARCHAR)", "C6 recreate c6at");
		Exec(setup, insert("c6at", 0, 100), "C6 the new c6at's rows");
		auto at = " AT (VERSION => " + std::to_string(v_old) + ")";
		resolves = 0;
		rows.clear();
		ran = Try(setup, Search("SELECT rowid FROM lake.main.c6at" + at, kQuery, "'lake.main.c6at'"), rows, error);
		Check(built == 1 && !ran && error.find("QoD: search_input_wrong_table") != std::string::npos && resolves.load() == 0,
		      "C6 a dropped and recreated table searched AT a snapshot where its index was ready: refused by the lineage "
		      "rule (search_input_wrong_table), not by the index check, before any resolve",
		      ran ? "it ran" : FirstLine(error));
		qod::ResolveObserverForTest(nullptr);
	}
	qod::search::ClearSegmentCacheForTest();
	qod::search::ClearLivenessCacheForTest();

	// C8 (Task 16 fix round 2, LEAD PROVISIONAL RULING): a bundle copy the query budget refuses (the cache takes no
	// bundle at a joint share of one byte) says how to fix it, in both populations; the allowed population's text
	// still carries no figure.
	{
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		qod::search::SetSegmentCacheBytesForTest(1);
		const std::string hint = "raise QOD_SEARCH_QUERY_MEMORY for nodes whose bundles exceed it";
		const std::string sql = Search("SELECT rowid FROM lake.main.c2", kQuery, "'lake.main.c2'");
		auto first_error = [&](const std::string &population) {
			g_population = population;
			Rows rows;
			std::string error;
			auto ran = Try(client, sql, rows, error);
			g_population = "table";
			return ran ? std::string("it ran") : FirstLine(error);
		};
		// Above every bitset and below c2's bundle (about 40 MB): only the copy passes it.
		qod::SetSearchMemoryForTest(uint64_t(16) << 20);
		auto table_text = first_error("table");
		auto allowed_text = first_error("allowed");
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		qod::search::SetSegmentCacheBytesForTest(qod::search::kDefault);
		qod::search::ClearSegmentCacheForTest();
		qod::search::ClearLivenessCacheForTest();
		Check(table_text.find("QoD: search_memory_exceeded: the search's bundle copies need ") != std::string::npos &&
		          table_text.find("past its budget of 16777216 bytes") != std::string::npos &&
		          table_text.size() > hint.size() &&
		          table_text.compare(table_text.size() - hint.size() - 2, hint.size() + 2, "; " + hint) == 0,
		      "C8 a bundle copy past the budget, the table population: the figures, then how to fix it", table_text);
		Check(allowed_text ==
		          "Invalid Input Error: QoD: search_memory_exceeded: the search needs more memory than its budget; " + hint,
		      "C8 the same in the allowed population: no figure, then how to fix it", allowed_text);
		// Another charge past the budget keeps its own text, with no hint.
		qod::SetSearchMemoryForTest(1024);
		auto small = first_error("table");
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Check(small.find("QoD: search_memory_exceeded") != std::string::npos && small.find(hint) == std::string::npos,
		      "C8 a budget of 1 KiB, refused before any bundle is read: no bundle hint", small);
	}
}

// ---- K0 to K3: the node's search knobs, read once at registration (Task 16) ------------------------------


size_t LineCount(const std::string &text) {
	return static_cast<size_t>(std::count(text.begin(), text.end(), '\n'));
}

struct KnobCase {
	std::string name;
	uint64_t fallback;
	uint64_t least;
	uint64_t greatest;
	bool or_zero;
	uint64_t set; // a valid value other than the default
};

const std::vector<KnobCase> kKnobCases = {
    {"QOD_SEARCH_MAX_UNINDEXED_ROWS", 200000, 0, 100000000, false, 1234},
    {"QOD_SEARCH_MAX_UNINDEXED_SOURCES", 64, 1, 4096, false, 7},
    {"QOD_SEARCH_QUERY_MEMORY", 0, uint64_t(64) << 20, uint64_t(1) << 40, true, uint64_t(128) << 20},
    {"QOD_SEARCH_MAX_K", 10000, 1, 10000, false, 50},
    {"QOD_SEARCH_LIVENESS_CACHE_ENTRIES", 64, 0, 100000, false, 3},
    {"QOD_SEARCH_LIVENESS_CACHE_BYTES", uint64_t(256) << 20, 0, uint64_t(1) << 40, false, uint64_t(5) << 20},
    {"QOD_SEARCH_SEGMENT_CACHE_BYTES", uint64_t(4) << 30, 0, uint64_t(1) << 40, false, uint64_t(6) << 30},
    {"QOD_SEARCH_MISS_TTL_SEC", 60, 0, 60, false, 30},
    {"QOD_SEARCH_MAX_BUNDLE_BYTES", uint64_t(1) << 30, uint64_t(1) << 20, uint64_t(64) << 30, false, uint64_t(2) << 20},
};

void UnsetKnobs() {
	for (auto &knob : kKnobCases) {
		SetEnvForTest(knob.name.c_str(), nullptr);
	}
}

void RunKnobs(DuckDB &db, Connection &setup, Connection &sysc, Connection &client, const std::string &data) {
	(void)sysc;
	// K0: every knob read at registration; a value outside its range, or not a plain decimal number, keeps the
	// knob's default with one stderr line naming the variable (spec 9).
	{
		auto knobs = qod::SearchKnobsForTest();
		Check(knobs.size() == kKnobCases.size(), "K0 the node reads nine knobs", std::to_string(knobs.size()));
		UnsetKnobs();
		auto said = RegisterKnobs();
		knobs = qod::SearchKnobsForTest();
		bool defaults = true;
		for (auto &knob : kKnobCases) {
			defaults = defaults && knobs.count(knob.name) && knobs[knob.name] == knob.fallback;
		}
		Check(defaults && said.empty(), "K0 none set: every default, nothing on stderr", FirstLine(said));
		for (auto &knob : kKnobCases) {
			SetEnvForTest(knob.name.c_str(), std::to_string(knob.set).c_str());
		}
		said = RegisterKnobs();
		knobs = qod::SearchKnobsForTest();
		bool set = true;
		for (auto &knob : kKnobCases) {
			set = set && knobs[knob.name] == knob.set;
		}
		Check(set && said.empty(), "K0 all nine set: each read at registration, nothing on stderr", FirstLine(said));
		UnsetKnobs();
		for (auto &knob : kKnobCases) {
			std::vector<std::string> good = {std::to_string(knob.least), std::to_string(knob.greatest)};
			if (knob.or_zero) {
				good.push_back("0");
			}
			for (auto &text : good) {
				SetEnvForTest(knob.name.c_str(), text.c_str());
				said = RegisterKnobs();
				knobs = qod::SearchKnobsForTest();
				Check(knobs[knob.name] == std::stoull(text) && said.empty(),
				      "K0 " + knob.name + "=" + text + " (a bound): read, nothing on stderr", FirstLine(said));
			}
			std::vector<std::string> bad = {"abc", "-1", " 5", "5 ", "1e3", "+5", "18446744073709551616",
			                                std::to_string(knob.greatest + 1)};
			if (knob.least > 0) {
				bad.push_back(std::to_string(knob.least - 1));
			}
			if (knob.or_zero) {
				bad.push_back("1");
			}
			for (auto &text : bad) {
				SetEnvForTest(knob.name.c_str(), text.c_str());
				said = RegisterKnobs();
				knobs = qod::SearchKnobsForTest();
				Check(knobs[knob.name] == knob.fallback && LineCount(said) == 1 &&
				          said.find("[qod-node] " + knob.name + " ") != std::string::npos,
				      "K0 " + knob.name + "='" + text + "': the default holds, one stderr line names the variable",
				      FirstLine(said));
			}
			SetEnvForTest(knob.name.c_str(), nullptr);
		}
		RegisterKnobs();
	}

	// K1 (the brief's case): QOD_SEARCH_MAX_UNINDEXED_ROWS=10 in the node's environment, a search over 11
	// unindexed rows fails search_index_behind; with "abc" the default holds and one stderr line names the key.
	{
		g_population = "table";
		bool ok = Exec(setup, "CREATE TABLE lake.main.k1 (id BIGINT, body VARCHAR)", "k1") &&
		          IndexTable(setup, "k1", {"body"}, data) &&
		          Exec(setup, "INSERT INTO lake.main.k1 SELECT i, 'w1 w' || i FROM range(0, 11) r(i)", "k1 rows");
		const std::string sql = Search("SELECT rowid FROM lake.main.k1", "'w1'", Quote("lake.main.k1"));
		SetEnvForTest("QOD_SEARCH_MAX_UNINDEXED_ROWS", "10");
		auto said = RegisterKnobs();
		Rows rows;
		std::string err;
		auto ran = Try(client, sql, rows, err);
		Check(ok && said.empty() && qod::search::MaxUnindexedRows() == 10 && !ran && rows.empty() &&
		          err.find("QoD: search_index_behind: lake.main.k1 has 11 unindexed live rows (cap 10)") != std::string::npos,
		      "K1 QOD_SEARCH_MAX_UNINDEXED_ROWS=10: a search over 11 unindexed rows fails search_index_behind",
		      ran ? "it ran" : FirstLine(err));
		SetEnvForTest("QOD_SEARCH_MAX_UNINDEXED_ROWS", "abc");
		said = RegisterKnobs();
		ran = Try(client, sql, rows, err);
		Check(qod::search::MaxUnindexedRows() == 200000 && LineCount(said) == 1 &&
		          said.find("QOD_SEARCH_MAX_UNINDEXED_ROWS") != std::string::npos && ran && rows.size() == 10,
		      "K1 QOD_SEARCH_MAX_UNINDEXED_ROWS=abc: the default (200,000) holds, one stderr line names the key, the "
		      "search answers",
		      FirstLine(said) + (ran ? "" : " | " + FirstLine(err)));
		SetEnvForTest("QOD_SEARCH_MAX_UNINDEXED_ROWS", nullptr);
		RegisterKnobs();
	}

	// K2: QOD_SEARCH_MAX_K bounds k (LEAD RULING, Task 16 fix round 1): a call that names no k takes
	// min(10, QOD_SEARCH_MAX_K); an explicit k above the bound is refused.
	{
		const std::string base = "SELECT rowid FROM lake.main.pages";
		SetEnvForTest("QOD_SEARCH_MAX_K", "5");
		RegisterKnobs();
		Rows rows;
		std::string err;
		auto seven = Try(client, Search(base, kQuery, kTable, kCols, ", k := 7"), rows, err);
		auto seven_error = err;
		auto five = Try(client, Search(base, kQuery, kTable, kCols, ", k := 5"), rows, err);
		auto five_rows = rows.size();
		auto unnamed = Try(client, Search(base), rows, err);
		Check(!seven && seven_error.find("k must be between 1 and 5") != std::string::npos && five && five_rows == 5 &&
		          unnamed && rows.size() == 5,
		      "K2 QOD_SEARCH_MAX_K=5: k := 7 refused naming the bound, k := 5 answers 5 rows, a call naming no k "
		      "answers 5 rows (min(10, 5))",
		      seven ? "k := 7 ran" : FirstLine(seven_error) + (unnamed ? " | " + std::to_string(rows.size()) + " rows" : " | " + FirstLine(err)));
		SetEnvForTest("QOD_SEARCH_MAX_K", "12");
		RegisterKnobs();
		unnamed = Try(client, Search(base), rows, err);
		Check(unnamed && rows.size() == 10, "K2 QOD_SEARCH_MAX_K=12: a call naming no k answers 10 rows (the default)",
		      unnamed ? std::to_string(rows.size()) + " rows" : FirstLine(err));
		SetEnvForTest("QOD_SEARCH_MAX_K", nullptr);
		RegisterKnobs();
		auto again = Try(client, Search(base, kQuery, kTable, kCols, ", k := 7"), rows, err);
		Check(again && rows.size() == 7, "K2 unset again: k := 7 answers", again ? "" : FirstLine(err));
	}

	// K3: QOD_SEARCH_QUERY_MEMORY unset (0) is a quarter of memory_limit, read per query.
	{
		UnsetKnobs();
		RegisterKnobs();
		auto &buffers = BufferManager::GetBufferManager(*db.instance);
		const std::string sql = Search("SELECT rowid FROM lake.main.pages");
		bool all = true;
		std::string detail;
		for (std::string limit : {"2GiB", "1GiB"}) {
			Exec(setup, "SET memory_limit = '" + limit + "'", "K3 memory_limit " + limit);
			qod::ResetLastSearchCallForTest();
			Rows rows;
			std::string err;
			auto ran = Try(client, sql, rows, err);
			auto record = qod::LastSearchCallForTest();
			auto quarter = static_cast<uint64_t>(buffers.GetMaxMemory()) / 4;
			all = all && ran && record.called && record.memory_limit == quarter;
			detail += limit + ": " + (ran ? std::to_string(record.memory_limit) + " vs " + std::to_string(quarter) : FirstLine(err)) + "; ";
		}
		Exec(setup, "RESET memory_limit", "K3 reset memory_limit");
		Check(all, "K3 QOD_SEARCH_QUERY_MEMORY unset: each query's budget is a quarter of memory_limit at that query", detail);
		SetEnvForTest("QOD_SEARCH_QUERY_MEMORY", "134217728");
		RegisterKnobs();
		qod::ResetLastSearchCallForTest();
		Rows rows;
		std::string err;
		auto ran = Try(client, sql, rows, err);
		Check(ran && qod::LastSearchCallForTest().memory_limit == (uint64_t(128) << 20),
		      "K3 QOD_SEARCH_QUERY_MEMORY=134217728: that budget, whatever memory_limit says", ran ? "" : FirstLine(err));
		SetEnvForTest("QOD_SEARCH_QUERY_MEMORY", nullptr);
		RegisterKnobs();
	}
}

int Run(const fs::path &goldens) {
	auto base = fs::temp_directory_path() / ("qod-search-engine-" + std::to_string(::getpid()));
	fs::remove_all(base);
	fs::create_directories(base / "data");
	auto data = fs::canonical(base / "data").string() + "/";

	// ---- G3 first: its children fork a process that holds no instance and no other thread yet (Task 13) --------
	RunG3(base);

	DuckDB db(nullptr);
	{
		PlannerExtension pre;
		pre.post_bind_function = CountingPreBind;
		PlannerExtension::Register(DBConfig::GetConfig(*db.instance), pre);
	}
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, Recorder);
	qod::RegisterSearchFunctions(*db.instance);
	{
		PlannerExtension counter;
		counter.post_bind_function = CountingPostBind;
		PlannerExtension::Register(DBConfig::GetConfig(*db.instance), counter);
	}
	Connection setup(db);
	for (std::string ext : {"ducklake", "parquet"}) {
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("search_engine_test: " + ext + " unavailable, skipped");
			return 77;
		}
	}
	if (!Exec(setup, "ATTACH " + Quote("ducklake:" + (base / "meta.ducklake").string()) + " AS lake (DATA_PATH " +
	                     Quote(data) + ", DATA_INLINING_ROW_LIMIT 10)",
	          "attach")) {
		return 1;
	}
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	auto insert = [&](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.pages SELECT i, 't' || i, " + body +
		       ", CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(" + std::to_string(from) +
		       ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, f10 INTEGER)",
	               "pages") &&
	          Exec(setup, insert(0, 10000), "file 1");
	auto v1 = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	auto v1_time = Rows();
	std::string terr;
	Try(setup, "SELECT snapshot_time FROM ducklake_snapshots('lake') WHERE snapshot_id = " + std::to_string(v1), v1_time,
	    terr);
	ok = ok && Exec(setup, insert(10000, 20000), "file 2") &&
	     Exec(setup, "CREATE TABLE lake.main.other (id BIGINT, body VARCHAR)", "other") &&
	     Exec(setup, "INSERT INTO lake.main.other SELECT i * 3, 'w1 w2' FROM range(0, 4000) r(i)", "other rows") &&
	     Exec(setup, "CREATE VIEW lake.main.v AS SELECT rowid AS rid, * FROM lake.main.pages", "view") &&
	     Exec(setup, "CREATE SECURE VIEW memory.main.sv AS SELECT rowid AS rid, id FROM lake.main.pages", "secure view") &&
	     Exec(setup, "CREATE TABLE lake.main.tail (id BIGINT, body VARCHAR)", "tail") &&
	     Exec(setup, "INSERT INTO lake.main.tail SELECT i, 'w0 w' || (i % 7) FROM range(0, 1000) r(i) ORDER BY i",
	          "tail rows") &&
	     Exec(setup, "CREATE TABLE lake.main.big (id BIGINT, body VARCHAR)", "big") &&
	     Exec(setup, "INSERT INTO lake.main.big SELECT i, array_to_string(list_transform(range(30), lambda j: 'w' || "
	                 "((i * 7 + j * 13) % 40)), ' ') FROM range(0, 200000) r(i) ORDER BY i",
	          "big rows");
	if (!ok) {
		return 1;
	}
	// The stand-in index records: pages on (title, body), the others on body, their bundles under `data`.
	g_bundle_root = data;
	g_indexes.OverrideRoot([] { return g_bundle_root; });
	g_indexes.Install();
	if (!IndexTable(setup, "pages", {"title", "body"}, data) || !IndexTable(setup, "other", {"body"}, data) ||
	    !IndexTable(setup, "tail", {"body"}, data) || !IndexTable(setup, "big", {"body"}, data)) {
		return 1;
	}
	// The builder is the system principal's only (spec 13), as qod_authz_stats.
	Connection sysc(db);
	if (!Install(sysc, "acme/acme_db/bi", true)) {
		return 1;
	}
	{
		const std::string build = "SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, NULL::BIGINT, "
		                          "'x', 'y'), 'lake.main.pages', '/nowhere', 1::UBIGINT, 1::UBIGINT)";
		Connection root(db);
		Install(root, "acme/acme_db/bi", false, true);
		Connection tenant(db);
		Install(tenant, "acme/acme_db/bi");
		for (auto con : {std::make_pair(&setup, "an untagged connection"), std::make_pair(&root, "a superuser"),
		                 std::make_pair(&tenant, "a tenant user")}) {
			Rows rows;
			std::string error;
			auto ran = Try(*con.first, build, rows, error);
			Check(!ran && error.find("QoD: function qod_search_build_segment is superuser-only") != std::string::npos,
			      std::string("qod_search_build_segment refused to ") + con.second, ran ? "it ran" : FirstLine(error));
		}
	}
	int64_t files = 0, tail_files = 0, big_files = 0;
	if (!BuildSegments(setup, sysc, "pages", data, files) || !BuildSegments(setup, sysc, "tail", data, tail_files) ||
	    !BuildSegments(setup, sysc, "big", data, big_files)) {
		return 1;
	}
	Check(files == 2 && tail_files == 1, "the fixture: pages in 2 data files, tail in 1",
	      std::to_string(files) + ", " + std::to_string(tail_files));
	Check(Int(setup, "SELECT count(*) FROM lake.main.pages") == 20000, "pages holds 20,000 rows");
	Exec(setup, "DELETE FROM lake.main.tail WHERE id >= 900", "tail's dead tail");
	// Every connection takes its index records from the stand-in record source (g_indexes); a quack session's
	// tenant principal its population from the stand-in decider's verdict entries.
	g_bundle_root = data;

	Connection client(db);
	if (!Install(client, "acme/acme_db/bi")) {
		return 1;
	}
	g_g1.client = client.context.get();

	// ---- A1 to A9: admitted, equal to the reference --------------------------------------------
	struct Admitted {
		std::string name, input, allowed;
	};
	const std::string pages = "lake.main.pages";
	std::vector<Admitted> admitted = {
	    {"A1 bare input", "SELECT rowid FROM lake.main.pages", "SELECT rowid FROM lake.main.pages"},
	    {"A2 WHERE f10 = 0", "SELECT rowid FROM lake.main.pages WHERE f10 = 0",
	     "SELECT rowid FROM lake.main.pages WHERE f10 = 0"},
	    {"A3 the rule-3 wrap",
	     "SELECT rowid FROM (SELECT *, rowid FROM lake.main.pages WHERE (f10 = 0)) AS pages WHERE lang = 'fr'",
	     "SELECT rowid FROM lake.main.pages WHERE f10 = 0 AND lang = 'fr'"},
	    {"A4 IN (SELECT id FROM other)", "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other)",
	     "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other)"},
	    {"A5 correlated EXISTS",
	     "SELECT rowid FROM lake.main.pages WHERE EXISTS (SELECT 1 FROM lake.main.other o WHERE o.id = pages.id)",
	     "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other)"},
	    // f10 = id % 10, so every pages row with a matching other row passes.
	    {"A6 correlated scalar comparison",
	     "SELECT rowid FROM lake.main.pages WHERE f10 = (SELECT min(o.id % 10) FROM lake.main.other o WHERE o.id = "
	     "pages.id)",
	     "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other)"},
	    {"A7 uncorrelated scalar", "SELECT rowid FROM lake.main.pages WHERE id > (SELECT 5)",
	     "SELECT rowid FROM lake.main.pages WHERE id > 5"},
	    {"A10 two predicate subqueries in one filter",
	     "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other) AND EXISTS (SELECT 1 FROM "
	     "lake.main.other o WHERE o.id = pages.id + 3)",
	     "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM lake.main.other) AND id + 3 IN (SELECT id FROM "
	     "lake.main.other)"},
	};
	for (auto &c : admitted) {
		Rows rows;
		std::string error;
		auto ran = Try(client, Search(c.input), rows, error);
		std::string rerr;
		auto want = Reference(setup, pages, "", c.allowed, "w1 w10", 10, rerr);
		std::string why;
		Check(ran && rerr.empty() && rows.size() == 10 && SameHits(Hits(rows), want, true, why),
		      c.name + ": 10 rows equal to the reference", ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
	}
	{
		// A8: AT (VERSION => v1) on the input: S from it (only file 1 is visible there).
		Rows rows;
		std::string error;
		auto at = " AT (VERSION => " + std::to_string(v1) + ")";
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.pages" + at), rows, error);
		std::string rerr, why;
		auto want = Reference(setup, pages, at, "SELECT rowid FROM lake.main.pages" + at, "w1 w10", 10, rerr);
		bool old_only = true;
		for (auto &hit : Hits(rows)) {
			old_only = old_only && hit.rowid < 10000;
		}
		Check(ran && rows.size() == 10 && old_only && SameHits(Hits(rows), want, true, why),
		      "A8 AT (VERSION => " + std::to_string(v1) + ") on the input: S from it, equal to the reference",
		      ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
	}
	{
		// A9: the spec's join-back example.
		Rows rows;
		std::string error;
		auto sql = "SELECT p.id, p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), "
		           "'lake.main.pages', ['body'], 'w1 w10') h JOIN lake.main.pages p ON p.rowid = h.rowid ORDER BY "
		           "h.score DESC, h.rowid";
		auto ran = Try(client, sql, rows, error);
		std::string rerr, why;
		auto want = Reference(setup, pages, "", "SELECT rowid FROM lake.main.pages WHERE lang = 'fr'", "w1 w10", 10, rerr);
		Check(ran && rows.size() == 10 && SameHits(Hits(rows, 0, 2, DConstants::INVALID_INDEX), want, false, why),
		      "A9 the join-back example: ids and scores equal to the reference",
		      ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
	}

	// ---- W1: the row-policy rewriter's actual output through the lineage check (Task 12) ----------
	{
		// golden/search-rewriter-outputs.sql, written by RowPolicyRewriterSearchDuckLakeSpec: the manager's
		// RowPolicyRewriter output for the holder of the row policy f10 = 0 on lake.main.pages (one line: a
		// predicate holding a subquery that admits the same rows), for spec 4.1's examples and Task 2's search
		// statements, on tables named as this fixture's (no mapping needed). Each line must pass FindSearchCalls
		// (it runs), take the verdict's allowed population (the stand-in decider answers what DecideRoute gives
		// a row-policy holder), and read only rows of the policy: its first output column is an id, a rowid or
		// f10 (rowid = id here), so every first value is a multiple of 10. With the policy's predicate replaced
		// by true, the same line reads other rows, so the check is not vacuous.
		std::ifstream in(goldens.parent_path() / "search-rewriter-outputs.sql");
		std::vector<std::string> lines;
		std::string line;
		while (std::getline(in, line)) {
			if (!line.empty() && line.rfind("--", 0) != 0) {
				lines.push_back(line);
			}
		}
		Check(lines.size() >= 6, "W1 the rewriter outputs golden holds the examples",
		      std::to_string(lines.size()) + " statements");
		// Task 2's bare and schema-qualified references resolve in lake, as the rewriter's context lake.main.
		Connection wcon(db);
		Exec(wcon, "USE lake", "W1 USE lake");
		if (Install(wcon, "acme/acme_db/bi")) {
			auto saved = g_population;
			g_population = "allowed";
			const std::vector<std::string> predicates = {"(f10 = 0)",
			                                             "(f10 IN (SELECT min(id % 10) FROM lake.main.other))"};
			for (size_t n = 0; n < lines.size(); n++) {
				auto name = "W1 line " + std::to_string(n + 1);
				Rows rows;
				std::string error;
				qod::ResetLastSearchCallForTest();
				auto ran = Try(wcon, lines[n], rows, error);
				auto last = qod::LastSearchCallForTest();
				bool policy = !rows.empty();
				for (auto &row : rows) {
					policy = policy && !row[0].IsNull() && row[0].GetValue<int64_t>() % 10 == 0;
				}
				Check(ran && policy && last.called && last.population == QS_POPULATION_ALLOWED,
				      name + ": admitted, the allowed population, " + std::to_string(rows.size()) +
				          " rows all of the policy",
				      ran ? (last.called ? "population " + std::to_string(last.population) : "no search call recorded")
				          : FirstLine(error));
				auto open = lines[n];
				for (auto &p : predicates) {
					for (size_t at = open.find(p); at != std::string::npos; at = open.find(p, at)) {
						open.replace(at, p.size(), "(true)");
					}
				}
				Rows wide;
				auto ran_open = Try(wcon, open, wide, error);
				bool other = false;
				for (auto &row : wide) {
					other = other || (!row[0].IsNull() && row[0].GetValue<int64_t>() % 10 != 0);
				}
				Check(ran_open && other, name + ": with the predicate replaced by true it reads rows the policy hides",
				      ran_open ? std::to_string(wide.size()) + " rows" : FirstLine(error));
			}
			g_population = saved;
		}
	}

	// ---- CS1: the column-policy search separation's output through the lineage check (Phase 1, change 9) --
	{
		// golden/search-cls-outputs.sql, written by SearchSeparationDuckLakeSpec: the manager's ColumnPolicyRewriter
		// output for a column-policy holder (a mask on title, a mask on lang, the input's WHERE reading it), then the
		// RowPolicyRewriter's for the holder of a mask and the row policy f10 = 0, on this fixture's tables. Each line
		// must be admitted: it runs, its search call is recorded and takes the allowed population (rule 1 holds for
		// the separated input, whose rewrite added no derived table and kept rowid a bare column). The first line
		// with its table argument computed (`'lake.main.' || 'pages'`) is refused by rule 7, as the separation passes
		// a non-constant argument as written.
		std::ifstream in(goldens.parent_path() / "search-cls-outputs.sql");
		std::vector<std::string> lines;
		std::string line;
		while (std::getline(in, line)) {
			if (!line.empty() && line.rfind("--", 0) != 0) {
				lines.push_back(line);
			}
		}
		Check(lines.size() >= 6, "CS1 the column-policy outputs golden holds the examples",
		      std::to_string(lines.size()) + " statements");
		Connection ccon(db);
		Exec(ccon, "USE lake", "CS1 USE lake");
		if (Install(ccon, "acme/acme_db/bi")) {
			auto saved = g_population;
			g_population = "allowed";
			for (size_t n = 0; n < lines.size(); n++) {
				Rows rows;
				std::string error;
				qod::ResetLastSearchCallForTest();
				auto ran = Try(ccon, lines[n], rows, error);
				auto last = qod::LastSearchCallForTest();
				Check(ran && last.called && last.population == QS_POPULATION_ALLOWED,
				      "CS1 line " + std::to_string(n + 1) + ": admitted, the allowed population, " +
				          std::to_string(rows.size()) + " rows",
				      ran ? (last.called ? "population " + std::to_string(last.population) : "no search call recorded")
				          : FirstLine(error));
			}
			if (!lines.empty()) {
				auto computed = lines[0];
				const std::string constant = "'lake.main.pages', ['body']";
				auto at = computed.find(constant);
				if (at != std::string::npos) {
					computed.replace(at, constant.size(), "'lake.main.' || 'pages', ['body']");
				}
				Rows rows;
				std::string error;
				auto ran = Try(ccon, computed, rows, error);
				Check(at != std::string::npos && !ran && error.find("search_argument_not_constant") != std::string::npos,
				      "CS1 a computed table argument is refused by rule 7", ran ? "ran" : FirstLine(error));
			}
			// CS2 (Task 9 security review I1): an input correlated to an earlier FROM item of the outer
			// statement (a table in-out function's subquery binds laterally) is refused on the node too, the
			// second check behind the gateway's scope-aware refusal: an outer column read in the input's WHERE.
			{
				Rows rows;
				std::string error;
				auto sql = "SELECT h.score FROM lake.main.pages p, qod_search((SELECT rowid FROM lake.main.pages WHERE "
				           "p.title = 't2'), 'lake.main.pages', ['body'], 'w1 w10') h";
				auto ran = Try(ccon, sql, rows, error);
				const std::string refusal = "QoD: search_unsupported_plan: qod_search with a LATERAL or correlated input";
				Check(!ran && error.find(refusal) != std::string::npos,
				      "CS2 an input correlated to the outer statement's FROM item is refused",
				      ran ? "ran, " + std::to_string(rows.size()) + " rows" : FirstLine(error));
			}
			g_population = saved;
		}
	}

	// ---- W2: the prototype build endpoint's statements on the node (Task 12) -----------------------
	{
		// The texts of SearchPrototypeHandlers (manager, POST /api/search/prototype/build-segments), as its
		// spec pins them, run on the system principal's connection: the flush, the listing, the live rows
		// per file, each file's Parquet schema and row count, and the build over the table's own scan by
		// field id. lake.main.wt holds one plain file (5,000 rows) and one flushed file (3 inlined rows,
		// carrying the internal row id and snapshot columns). Keep in sync with SearchPrototypeHandlers.
		auto lit = [](const std::string &s) {
			std::string out = "'";
			for (auto c : s) {
				out += c == '\'' ? std::string("''") : std::string(1, c);
			}
			return out + "'";
		};
		bool ok = Exec(setup, "CREATE TABLE lake.main.wt (id BIGINT, body VARCHAR)", "W2 wt") &&
		          Exec(setup, "INSERT INTO lake.main.wt SELECT i, 'w' || (i % 7) || ' w1 w' || (i % 3) FROM range(5000) "
		                      "r(i) ORDER BY i",
		               "W2 wt file") &&
		          Exec(setup, "INSERT INTO lake.main.wt VALUES (5000, 'w1 w2'), (5001, 'w2'), (5002, 'w1 w1')",
		               "W2 wt inlined") &&
		          IndexTable(setup, "wt");
		Rows rows;
		std::string error;
		ok = ok && Try(sysc, "CALL ducklake_flush_inlined_data('lake', schema_name => 'main', table_name => 'wt')", rows,
		               error);
		Check(ok, "W2 the flush, as the system principal", FirstLine(error));
		Rows listed, live;
		ok = ok &&
		     Try(sysc,
		         "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files('lake', 'wt', "
		         "schema => 'main')",
		         listed, error) &&
		     Try(sysc,
		         "SELECT filename, min(rowid - file_row_number), max(rowid - file_row_number), count(*), "
		         "min(file_row_number), max(file_row_number) FROM \"lake\".\"main\".\"wt\" GROUP BY filename",
		         live, error);
		Check(ok && listed.size() == 2 && live.size() == 2, "W2 the listing and the live rows: 2 files",
		      ok ? std::to_string(listed.size()) + " listed, " + std::to_string(live.size()) + " live" : FirstLine(error));
		int built = 0, with_snapshots = 0;
		for (auto &file : listed) {
			if (!ok) {
				break;
			}
			auto path = file[0].GetValue<std::string>();
			Rows schema;
			ok = Try(sysc,
			         "SELECT s.name, m.num_rows FROM parquet_schema(" + lit(path) + ") s, parquet_file_metadata(" +
			             lit(path) + ") m",
			         schema, error);
			bool snapshots = false;
			for (auto &c : schema) {
				snapshots = snapshots || c[0].ToString() == "_ducklake_internal_snapshot_id";
			}
			with_snapshots += snapshots;
			Rows out;
			ok = ok && Try(sysc,
			               "SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, rowid "
			               "AS row_id, " +
			                   std::string(snapshots ? "snapshot_id" : "-1::BIGINT") +
			                   " AS snapshot_id, \"body\" AS text FROM \"lake\".\"main\".\"wt\" WHERE filename = " +
			                   lit(path) + " ORDER BY pos), 'lake.main.wt', " + lit(path) + ", " + file[1].ToString() +
			                   "::UBIGINT, " + file[2].ToString() + "::UBIGINT)",
			               out, error) &&
			     out.size() == 1;
			built += ok;
		}
		Check(ok && built == 2 && with_snapshots == 1, "W2 both bundles built, one from a flushed file's snapshots",
		      ok ? std::to_string(built) + " built" : FirstLine(error));
		qod::ResetLastSearchCallForTest();
		Rows hits;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.wt", "'w1 w2'", "'lake.main.wt'"), hits, error);
		auto last = qod::LastSearchCallForTest();
		std::string rerr, why;
		auto want = Reference(setup, "lake.main.wt", "", "SELECT rowid FROM lake.main.wt", "w1 w2", 10, rerr);
		Check(ran && hits.size() == 10 && last.fallback_segments == 0 && SameHits(Hits(hits), want, true, why),
		      "W2 a search over the endpoint's bundles: no fallback, equal to the reference",
		      ran ? (rerr.empty() ? why + " fallback " + std::to_string(last.fallback_segments) : rerr) : FirstLine(error));
	}

	// ---- R1 to R27: refused before any read ------------------------------------------------------
	Refused(client, "R1 VALUES input", Search("SELECT * FROM (VALUES (1::BIGINT), (2::BIGINT)) t(r)"),
	        "search_input_not_governed");
	Refused(client, "R2 range(5) input", Search("SELECT range FROM range(5)"), "search_input_not_governed");
	Refused(client, "R3 a scan of other, table pages", Search("SELECT rowid FROM lake.main.other"),
	        "Invalid Input Error: QoD: search_input_wrong_table");
	Refused(client, "R4 a join in FROM",
	        Search("SELECT p.rowid FROM lake.main.pages p JOIN lake.main.other o ON p.id = o.id"),
	        "search_input_not_governed");
	Refused(client, "R4 a comma join under WHERE p.id = o.id",
	        Search("SELECT p.rowid FROM lake.main.pages p, lake.main.other o WHERE p.id = o.id"),
	        "search_input_not_governed: a join written in FROM");
	Refused(client, "R4 a CROSS JOIN under WHERE p.id = o.id",
	        Search("SELECT p.rowid FROM lake.main.pages p CROSS JOIN lake.main.other o WHERE p.id = o.id"),
	        "search_input_not_governed: a join written in FROM");
	Refused(client, "R4 a CROSS JOIN under WHERE true",
	        Search("SELECT p.rowid FROM lake.main.pages p CROSS JOIN lake.main.other o WHERE true"),
	        "search_input_not_governed: a join written in FROM");
	Refused(client, "R5 a LEFT JOIN", Search("SELECT p.rowid FROM lake.main.pages p LEFT JOIN lake.main.other o ON p.id = o.id"),
	        "search_input_not_governed");
	Refused(client, "R6 UNION with other",
	        Search("SELECT rowid FROM lake.main.pages UNION SELECT rowid FROM lake.main.other"),
	        "search_input_not_governed");
	Refused(client, "R7 a CTE", Search("WITH x AS (SELECT rowid AS r FROM lake.main.pages) SELECT r FROM x"),
	        "search_input_not_governed");
	Refused(client, "R8 the plain view v (no rowid: DuckDB's bind error)", Search("SELECT rowid FROM lake.main.v"), "");
	Refused(client, "R9 the secure view", Search("SELECT rid FROM memory.main.sv"), "search_input_not_governed");
	Refused(client, "R10 SELECT rid FROM v", Search("SELECT rid FROM lake.main.v"),
	        "Invalid Input Error: QoD: search_input_not_governed: a view");
	// R28 to R30 (security review I1): a table function on the input's FROM path, query() and query_table() above
	// all, whose own SQL would hide a view or a FROM join from the plan walk.
	Refused(client, "R28 query_table() over the plain view v", Search("SELECT rid FROM query_table('lake.main.v')"),
	        "Invalid Input Error: QoD: search_input_not_governed: a table function");
	Refused(client, "R29 query() over the plain view v", Search("SELECT rid FROM query('SELECT rid FROM lake.main.v')"),
	        "Invalid Input Error: QoD: search_input_not_governed: a table function");
	Refused(client, "R30 query() holding a comma join",
	        Search("SELECT r FROM query('SELECT p.rowid AS r FROM lake.main.pages p, lake.main.other o WHERE p.id = "
	               "o.id')"),
	        "Invalid Input Error: QoD: search_input_not_governed: a table function");
	Refused(client, "R11 rowid + 1", Search("SELECT rowid + 1 FROM lake.main.pages"), "search_input_not_governed");
	Refused(client, "R12 rowid + 1 AS rowid", Search("SELECT rowid + 1 AS rowid FROM lake.main.pages"),
	        "search_input_not_governed");
	Refused(client, "R13 a select-list subquery, two columns",
	        Search("SELECT rowid, (SELECT max(id) FROM lake.main.other o WHERE o.id = pages.id) FROM lake.main.pages"),
	        "search_input_not_governed");
	Refused(client, "R13 a select-list subquery, one column",
	        Search("SELECT rowid FROM (SELECT rowid, (SELECT max(id) FROM lake.main.other o WHERE o.id = pages.id) AS n "
	               "FROM lake.main.pages) s"),
	        "search_input_not_governed");
	Refused(client, "R14 LIMIT 5", Search("SELECT rowid FROM lake.main.pages LIMIT 5"), "search_input_not_governed");
	Refused(client, "R15 DISTINCT", Search("SELECT DISTINCT rowid FROM lake.main.pages"), "search_input_not_governed");
	Refused(client, "R16 a window", Search("SELECT rowid FROM lake.main.pages QUALIFY row_number() OVER () < 5"),
	        "search_input_not_governed");
	Refused(client, "R17 an aggregate", Search("SELECT max(rowid) FROM lake.main.pages"), "search_input_not_governed");
	Refused(client, "R18 no input (DuckDB's bind error)", "SELECT * FROM qod_search('lake.main.pages', ['body'], 'w1')",
	        "");
	Refused(client, "R19 a computed table name",
	        Search("SELECT rowid FROM lake.main.pages", kQuery, "'lake.main.' || 'pages'"),
	        "Invalid Input Error: QoD: search_argument_not_constant: table");
	{
		Connection branch(db);
		if (Install(branch, "acme/acme_db/__br_0011aabb")) {
			Refused(branch, "R20 a branch pool", Search("SELECT rowid FROM lake.main.pages"),
			        "Invalid Input Error: QoD: search_branch_unsupported");
		}
	}
	{
		// R21: rule 4 takes S from the input; the node does not check the join-back's snapshot.
		auto at = " AT (VERSION => " + std::to_string(v1) + ")";
		for (auto pair : {std::make_pair(at, std::string()), std::make_pair(std::string(), at)}) {
			Rows rows;
			std::string error;
			auto sql = "SELECT h.rowid, p.id FROM qod_search((SELECT rowid FROM lake.main.pages" + pair.first +
			           "), 'lake.main.pages', ['body'], 'w1 w10') h LEFT JOIN lake.main.pages AS p" +
			           pair.second + " ON p.rowid = h.rowid ORDER BY h.score DESC, h.rowid";
			auto ran = Try(client, sql, rows, error);
			int64_t matched = 0, newer = 0;
			for (auto &row : rows) {
				matched += !row[1].IsNull();
				newer += row[0].GetValue<int64_t>() >= 10000;
			}
			Check(ran, std::string("R21 AT on the ") + (pair.first.empty() ? "join-back" : "input") + " only: admitted",
			      ran ? std::to_string(rows.size()) + " hits, " + std::to_string(matched) + " joined, " +
			                std::to_string(newer) + " rowids of file 2"
			          : FirstLine(error));
		}
	}
	Refused(client, "R22 OFFSET 5", Search("SELECT rowid FROM lake.main.pages OFFSET 5"), "search_input_not_governed");
	Refused(client, "R23 USING SAMPLE 10%", Search("SELECT rowid FROM lake.main.pages USING SAMPLE 10%"),
	        "search_input_not_governed");
	Refused(client, "R23 TABLESAMPLE 10%", Search("SELECT rowid FROM lake.main.pages TABLESAMPLE 10%"),
	        "search_input_not_governed");
	Refused(client, "R24 UNNEST", Search("SELECT unnest([rowid]) FROM lake.main.pages"), "search_input_not_governed");
	Refused(client, "R25 title:w0 with ['body']", Search("SELECT rowid FROM lake.main.pages", "'title:w0'"),
	        "search_field_not_allowed");
	if (!v1_time.empty()) {
		Refused(client, "R26 AT (TIMESTAMP => a past time)",
		        Search("SELECT rowid FROM lake.main.pages AT (TIMESTAMP => " + Quote(v1_time[0][0].ToString()) +
		               "::TIMESTAMPTZ)"),
		        "QoD: search_at_timestamp_unsupported: AT (TIMESTAMP => ...) is not supported");
	} else {
		Check(false, "R26 the snapshot time of " + std::to_string(v1), FirstLine(terr));
	}
	const std::string filtered = "SELECT rowid FROM lake.main.pages WHERE id < 20000";
	// R27 (Task 14, read-your-writes): a search after the transaction's own write runs, and equals the reference of
	// the rows as the transaction sees them (Y1 to Y9 cover it on both catalogs).
	for (auto write : {std::string("INSERT INTO lake.main.pages VALUES (50000, 't', 'w1', 'fr', 0)"),
	                   std::string("DELETE FROM lake.main.pages WHERE id = 3")}) {
		Exec(client, "BEGIN TRANSACTION", "begin");
		Exec(client, write, "the write");
		Rows rows;
		std::string error, rerr, why;
		auto ran = Try(client, Search(filtered), rows, error);
		auto want = Reference(client, pages, "", filtered, "w1 w10", 10, rerr);
		Check(ran && rerr.empty() && rows.size() == 10 && SameHits(Hits(rows), want, true, why),
		      "R27 after " + write.substr(0, 6) + " in the transaction: runs, equal to the transaction's reference",
		      ran ? (rerr.empty() ? why : FirstLine(rerr)) : FirstLine(error));
		Exec(client, "ROLLBACK", "rollback");
	}
	auto equals_reference = [&](Connection &con, const std::string &name, const std::string &input) {
		Rows rows;
		std::string error, rerr, why;
		auto ran = Try(con, Search(input), rows, error);
		auto want = Reference(setup, pages, "", input, "w1 w10", 10, rerr);
		Check(ran && rerr.empty() && rows.size() == 10 && SameHits(Hits(rows), want, true, why), name,
		      ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
	};
	{
		Exec(client, "BEGIN TRANSACTION", "begin");
		Exec(client, "DELETE FROM lake.main.pages WHERE id = 3", "delete");
		Exec(client, "COMMIT", "commit");
		equals_reference(client, "R27 the same search after COMMIT runs, equal to the fresh reference", filtered);
	}
	{
		// B1 (review C1): a committed insert of 3,000 rows, a third data file with no bundle and none of
		// its rows in the input: its live docs count in the TABLE population, so they go to the fallback
		// (Task 11), and the search equals the reference with no bundle.
		Exec(setup, insert(20000, 23000), "a third, unbundled data file");
		equals_reference(client, "B1 a live data file with no bundle, outside the input: through the fallback, equal "
		                         "to the fresh reference",
		                 filtered);
		int64_t now_files = 0;
		BuildSegments(setup, sysc, "pages", data, now_files);
		equals_reference(client, "B1 once its bundle is built: equal to the fresh reference", filtered);
		// B2: inlined live rows (2 rows under DATA_INLINING_ROW_LIMIT 10), outside the input.
		Exec(setup, "INSERT INTO lake.main.pages VALUES (50000, 't', 'w1', 'fr', 0), (50001, 't', 'w10', 'en', 1)",
		     "inlined rows");
		equals_reference(client, "B2 inlined live rows outside the input: through the fallback, with no flush, equal "
		                         "to the fresh reference",
		                 filtered);
		Exec(setup, "CALL ducklake_flush_inlined_data('lake')", "flush the inlined rows");
		BuildSegments(setup, sysc, "pages", data, now_files);
		equals_reference(client, "B2 once flushed and bundled: equal to the fresh reference", filtered);
	}
	{
		// I2: a search call in a view, macro or query() body, at any depth, refused for every principal.
		Exec(setup,
		     "CREATE VIEW lake.main.hv AS SELECT rowid AS r, score FROM qod_search((SELECT rid FROM lake.main.v), "
		     "'lake.main.' || 'pages', ['body'], 'w1 w10')",
		     "a view calling qod_search");
		Exec(setup, "CREATE VIEW lake.main.hv2 AS SELECT r FROM lake.main.hv", "a view over it");
		Exec(setup,
		     "CREATE VIEW lake.main.hv3 AS WITH c AS (SELECT rowid AS r FROM qod_search((SELECT rowid FROM "
		     "lake.main.pages), 'lake.main.pages', ['body'], 'w1')) SELECT r FROM c",
		     "a view calling it in a CTE");
		Exec(setup,
		     "CREATE MACRO memory.main.sm() AS TABLE SELECT rowid AS r FROM qod_search((SELECT rowid FROM "
		     "lake.main.pages), 'lake.main.pages', ['body'], 'w1')",
		     "a table macro calling it");
		const std::string body =
		    "Invalid Input Error: QoD: search_input_not_governed: a search function called inside a view, macro or query() "
		    "body";
		Refused(client, "I2 the view alone (P3a)", "SELECT r, score FROM lake.main.hv", body);
		Refused(client, "I2 the view after an unreferenced CTE showing one call (P3b)",
		        "WITH u AS (SELECT rowid FROM qod_search((SELECT rowid FROM lake.main.pages), 'lake.main.pages', "
		        "['body'], 'x')) SELECT r, score FROM lake.main.hv",
		        body);
		Refused(client, "I2 a plain view through another view", "SELECT r FROM lake.main.hv2", body);
		Refused(client, "I2 a view calling it in a CTE", "SELECT r FROM lake.main.hv3", body);
		Refused(setup, "I2 the view, as a superuser off a quack session", "SELECT r FROM lake.main.hv2", body);
		Refused(setup, "I2 a table macro, as a superuser", "SELECT r FROM memory.main.sm()", body);
		// Fix round 2 (security re-review n1): a search function inside a query() literal, at any depth, is refused
		// for every principal (spec 4.2 rule 1), before its arguments are judged.
		const std::string in_query =
		    "Invalid Input Error: QoD: search_input_not_governed: a search function called inside a query() body";
		Refused(setup, "I2 a query() literal with a computed table, as a superuser",
		        "SELECT * FROM query('SELECT * FROM qod_search((SELECT rowid FROM lake.main.pages), ''lake.main.'' || "
		        "''pages'', [''body''], ''w1'')')",
		        in_query);
		const std::string literal = "SELECT * FROM query('SELECT * FROM qod_search((SELECT rowid FROM lake.main.pages), "
		                            "''lake.main.pages'', [''body''], ''w1'')')";
		Refused(client, "I2 qod_search inside a query() literal, as a tenant", literal, in_query);
		Refused(setup, "I2 qod_search inside a query() literal, as a superuser", literal, in_query);
		Refused(client, "I2 qod_search_matches inside a query() literal",
		        "SELECT * FROM query('SELECT * FROM qod_search_matches((SELECT rowid FROM lake.main.pages), "
		        "''lake.main.pages'', [''body''], ''w1'')')",
		        in_query);
		Refused(client, "I2 qod_search inside a query() literal nested two levels",
		        "SELECT * FROM query('SELECT * FROM query(''SELECT * FROM qod_search((SELECT rowid FROM "
		        "lake.main.pages), ''''lake.main.pages'''', [''''body''''], ''''w1'''')'')')",
		        in_query);
		Rows built;
		std::string error;
		auto ran = Try(sysc,
		               "SELECT * FROM query('SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, "
		               "NULL::BIGINT, ''x'', ''y''), ''lake.main.pages'', ''/nowhere'', 1::UBIGINT, 1::UBIGINT)')",
		               built, error);
		Check(!ran && error.find(in_query) != std::string::npos,
		      "I2 qod_search_build_segment inside a query() literal, as the system principal: refused",
		      ran ? "it ran" : FirstLine(error));
		ran = Try(sysc,
		          "SELECT * FROM query('SELECT * FROM query(''SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, "
		          "0::BIGINT, NULL::BIGINT, ''''x'''', ''''y''''), ''''lake.main.pages'''', ''''/nowhere'''', 1::UBIGINT, "
		          "1::UBIGINT)'')')",
		          built, error);
		Check(!ran && error.find(in_query) != std::string::npos,
		      "I2 qod_search_build_segment inside a query() literal nested two levels: refused",
		      ran ? "it ran" : FirstLine(error));
	}
	{
		// The query limits (spec 9), at bind.
		std::string long_query;
		while (long_query.size() < 5000) {
			long_query += "w0 ";
		}
		Refused(client, "E0 a query of 5,000 bytes", Search(filtered, Quote(long_query)), "QoD: search_query_too_large");
		Refused(client, "E0 a query nesting 33 parentheses",
		        Search(filtered, Quote(std::string(33, '(') + "w0" + std::string(33, ')'))), "QoD: search_query_too_large");
	}

	// ---- D1 to D4: the descriptions --------------------------------------------------------------
	{
		std::string d1, d2, d3, d4, a1;
		auto f1 = Fingerprint(client, "EXPLAIN " + Search("SELECT rowid FROM lake.main.pages"), d1);
		auto f2 = Fingerprint(client, "EXPLAIN " + Search("SELECT rowid FROM lake.main.pages", kQuery, kTable, "['title']"), d2);
		auto f3 = Fingerprint(client, "EXPLAIN " + Search("SELECT rowid FROM lake.main.other", kQuery, "'lake.main.other'"), d3);
		auto f4 = Fingerprint(client, "EXPLAIN " + Search("SELECT rowid FROM lake.main.pages", "'w0 OR w5'"), d4);
		Fingerprint(client, Search("SELECT rowid FROM lake.main.pages"), a1);
		auto golden = ReadFile(goldens / "search-a1.json");
		Check(a1.find("\"searches\":[{\"call\":0,\"columns\":[\"body\"],\"table\":\"lake.main.pages\"}]") !=
		              std::string::npos &&
		          a1.find("\"access\":\"read\",\"catalog\":\"lake\",\"catalogType\":\"ducklake\",\"columns\":[\"body\"]") !=
		              std::string::npos,
		      "D1 A1's description: pages read with column body, and a searches entry", a1);
		Check(!golden.empty() && a1 == golden, "D1 A1's description equals tests/golden/descriptions/search-a1.json",
		      golden.empty() ? "no golden" : "");
		Check(!f1.empty() && !f2.empty() && f1 != f2, "D2 ['body'] and ['title'] differ in fingerprint", f1 + " / " + f2);
		Check(!f3.empty() && f1 != f3, "D3 'lake.main.pages' and 'lake.main.other' differ in fingerprint", f3);
		Check(!f4.empty() && f1 == f4, "D4 another query string shares the fingerprint", f4);
		Say("   D fingerprints: A1 " + f1 + ", ['title'] " + f2 + ", other " + f3 + ", other query " + f4);
	}

	// ---- E0: the ABI as the node drives it --------------------------------------------------------
	{
		qod::ResetLastSearchCallForTest();
		Rows rows;
		std::string error;
		auto ran = Try(client, Search("SELECT rowid FROM lake.main.tail", "'w0'", "'lake.main.tail'"), rows, error);
		auto record = qod::LastSearchCallForTest();
		Check(ran && record.called && record.num_docs.size() == 1 && record.num_docs[0] == 1000 &&
		          record.live_words == std::vector<size_t> {16} && record.allowed_words == std::vector<size_t> {16} &&
		          record.liveness_words == std::vector<size_t> {15},
		      "E0 a FileLiveness whose tail is dead goes with live_words = ceil(num_docs / 64) = 16 (its words: 15)",
		      ran ? "" : FirstLine(error));
		bool alive = true;
		for (auto &hit : Hits(rows)) {
			alive = alive && hit.rowid < 900;
		}
		Check(ran && rows.size() == 10 && alive && record.out_cap == 10 && record.k == 10,
		      "E0 the dead tail is never returned; out_cap = k", Show(Hits(rows)));
		qod::CancelInFinishForTest(true);
		ran = Try(client, Search(filtered), rows, error);
		qod::CancelInFinishForTest(false);
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos,
		      "E0 the cancel flag set in Finish: an interrupt error and no row", FirstLine(error));
		qod::SetSearchMemoryForTest(1024);
		ran = Try(client, Search(filtered), rows, error);
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Check(!ran && rows.empty() && error.find("QoD: search_memory_exceeded") != std::string::npos,
		      "E0 a memory limit of 1 KiB: search_memory_exceeded (the node's own check) and no row", FirstLine(error));
		// The budget passed is the limit less the bitsets held; one just above them is the library's to refuse.
		// One local state (threads = 1), so the bytes held are the same from run to run.
		Exec(setup, "SET threads = 1", "threads 1");
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
		qod::ResetLastSearchCallForTest();
		ran = Try(setup, Search(filtered), rows, error);
		auto normal = qod::LastSearchCallForTest();
		Check(ran && normal.called && normal.held_bytes > 0 &&
		          normal.memory_budget == (uint64_t(4) << 30) - normal.held_bytes,
		      "E0 memory_budget = the limit (4 GiB) less the bitsets held",
		      std::to_string(normal.memory_budget) + " = 4 GiB - " + std::to_string(normal.held_bytes));
		qod::SetSearchMemoryForTest(normal.held_bytes + 64);
		qod::ResetLastSearchCallForTest();
		ran = Try(setup, Search(filtered), rows, error);
		auto tight = qod::LastSearchCallForTest();
		qod::SetSearchMemoryForTest(qod::search::kQueryMemoryKnob.fallback);
		Exec(setup, "SET threads = 16", "threads 16");
		Check(!ran && rows.empty() && tight.called && tight.memory_budget == 64 &&
		          error.find("QoD: search_memory_exceeded: the call needs more than its budget of 64 bytes") != std::string::npos,
		      "E0 a budget of 64 bytes past the bitsets: the library answers search_memory_exceeded, no row",
		      FirstLine(error));
	}

	// ---- E1, E2, E4 on the superuser connection ---------------------------------------------------
	{
		Rows reference;
		std::string error;
		Exec(setup, "SET threads = 1", "threads 1");
		Try(setup, Search(filtered), reference, error);
		Exec(setup, "SET threads = 16", "threads 16");
		int multi = 0, once = 0, combined = 0, equal = 0;
		for (int run = 0; run < 40; run++) {
			ResetSinkCountersForTest();
			Rows rows;
			std::string why;
			if (!Try(setup, Search(filtered), rows, error)) {
				continue;
			}
			auto c = SinkCountersForTest();
			multi += c.locals > 1;
			once += c.finalizes == 1;
			combined += c.combines == c.locals;
			equal += SameHits(Hits(rows), Hits(reference), true, why);
		}
		Check(reference.size() == 10 && multi >= 39 && once == 40 && combined == 40 && equal == 40,
		      "E1 threads = 16 over 40 runs: more than one local state, one finalize, combines = locals, the "
		      "threads = 1 results",
		      "multi " + std::to_string(multi) + ", once " + std::to_string(once) + ", combined " +
		          std::to_string(combined) + ", equal " + std::to_string(equal));
		qod::search::SkipSwapForTest(true);
		Rows rows;
		auto ran = Try(setup, Search(filtered), rows, error);
		qod::search::SkipSwapForTest(false);
		Check(!ran && rows.empty() && error.find(qod::search::kNotThroughSink) != std::string::npos,
		      "E2 an unswapped call fails with kNotThroughSink, no partial result", FirstLine(error));
		ResetSinkCountersForTest();
		qod::search::InterruptAfterRowsForTest(10000);
		ran = Try(setup, Search("SELECT rowid FROM lake.main.pages WHERE id < 20000"), rows, error);
		qod::search::InterruptAfterRowsForTest(0);
		auto c = SinkCountersForTest();
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos && c.max_rows_between_checks <= 65536,
		      "E4 interrupted after 10,000 of 20,000 input rows: an interrupt error, no row, at most 65,536 rows "
		      "between checks",
		      FirstLine(error) + "; max between checks " + std::to_string(c.max_rows_between_checks));
	}

	// ---- E4b and E0 cancel: a long search over big (200,000 rows) ---------------------------------------
	{
		const std::string big = "SELECT rowid FROM lake.main.big";
		std::string terms;
		for (int t = 0; t < 40; t++) {
			terms += (t ? " w" : "w") + std::to_string(t);
		}
		auto query = Quote(terms);
		Exec(setup, "SET threads = 1", "threads 1");
		ResetSinkCountersForTest();
		Rows rows;
		std::string error;
		auto start = std::chrono::steady_clock::now();
		auto ran = Try(setup, Search(big, query, "'lake.main.big'"), rows, error);
		auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
		auto c = SinkCountersForTest();
		Check(ran && rows.size() == 10 && c.interrupt_checks > 0 && c.max_rows_between_checks > 0 &&
		          c.max_rows_between_checks <= 65536,
		      "E4b 200,000 input rows on one local state: interrupt checks ran, at most 65,536 rows between them",
		      (ran ? "" : FirstLine(error) + "; ") + std::to_string(c.interrupt_checks) + " checks, max " +
		          std::to_string(c.max_rows_between_checks) + ", " + std::to_string(int(ms)) + " ms");
		// The client interrupts the statement while qs_search runs; the watcher copies it into the cancel flag.
		const std::string e0 = "E0 an interrupt while qs_search runs: the watcher sets the flag, qs_search answers "
		                       "search_cancelled, no row";
		std::thread interrupter;
		qod::ResetLastSearchCallForTest();
#ifdef QOD_SEARCH_TEST_HOOKS
		// Deterministic (the library's test-hooks hold): qs_search waits past its statistics, before it scores, for its
		// cancel flag, and the client interrupts only once the call is held, so the interrupt lands inside qs_search
		// on any host, however fast.
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
#else
		// Timed (no hold in a build without the test hooks): 5 ms in. It counts only when the library itself answered
		// search_cancelled; an interrupt that landed outside qs_search (a fast host) is noted, never passed.
		qod::SearchCallObserverForTest([&](ClientContext &context) {
			interrupter = std::thread([&context] {
				std::this_thread::sleep_for(std::chrono::milliseconds(5));
				context.Interrupt();
			});
		});
#endif
		ran = Try(setup, Search(big, query, "'lake.main.big'"), rows, error);
		qod::SearchCallObserverForTest(nullptr);
		if (interrupter.joinable()) {
			interrupter.join();
		}
		auto record = qod::LastSearchCallForTest();
#ifdef QOD_SEARCH_TEST_HOOKS
		Check(qs_test_search_holds() == holds + 1, "E0 qs_search reached the hold",
		      std::to_string(qs_test_search_holds() - holds) + " holds");
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos && record.library_cancelled,
		      e0 + " (held in qs_search)",
		      FirstLine(error) + (record.library_cancelled ? "" : " (qs_search did not report search_cancelled)"));
#else
		if (record.library_cancelled) {
			Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos, e0, FirstLine(error));
		} else {
			Say("note " + e0 + ": not reached on this host (the interrupt landed outside qs_search: " +
			    (ran ? std::to_string(rows.size()) + " rows answered" : FirstLine(error)) +
			    "); no cancel inside the library to check");
			if (!ran) {
				Check(rows.empty() && error.find("Interrupt") != std::string::npos,
				      "E0 the interrupt outside qs_search: Interrupted, no row", FirstLine(error));
			}
		}
#endif
		Exec(setup, "SELECT 1", "the connection after the interrupt");
		Exec(setup, "SET threads = 16", "threads 16");
	}

	// ---- G1: GetAllFiles re-entrancy (change 23) ----------------------------------------------------
	MeasureG1(client, Search(filtered), "duckdb-file", true);
	MeasureG1(client, Search(filtered), "duckdb-file", false);

	// ---- E5: a prepared statement searches with the S of each EXECUTE (invariant 4) -----------------
	{
		auto sql = Search(filtered);
		auto prepared = client.Prepare(sql);
		Check(!prepared->HasError(), "E5 PREPARE", prepared->HasError() ? FirstLine(prepared->GetError()) : "");
		if (!prepared->HasError()) {
			auto fetch = [](unique_ptr<QueryResult> result, Rows &rows, std::string &error) {
				rows.clear();
				if (result->HasError()) {
					error = result->GetError();
					return false;
				}
				while (auto chunk = result->Fetch()) {
					for (idx_t r = 0; r < chunk->size(); r++) {
						rows.push_back({chunk->GetValue(0, r), chunk->GetValue(1, r), chunk->GetValue(2, r)});
					}
				}
				return true;
			};
			Rows r0, r1, r2;
			std::string error;
			fetch(prepared->Execute(), r0, error);
			auto top = r0.empty() ? -1 : r0[0][0].GetValue<int64_t>();
			Exec(setup, "INSERT INTO lake.main.pages VALUES (60000, 't', 'w1 w10', 'fr', 0), (60001, 't', 'w1', 'en', 1)",
			     "another connection's insert");
			Exec(setup, "CALL ducklake_flush_inlined_data('lake')", "flush it");
			int64_t now_files = 0;
			BuildSegments(setup, sysc, "pages", data, now_files);
			Exec(setup, "DELETE FROM lake.main.pages WHERE id = " + std::to_string(top), "another connection's delete");
			ResetSinkCountersForTest();
			Exec(client, "BEGIN TRANSACTION", "begin");
			auto ran = fetch(prepared->Execute(), r1, error);
			Exec(client, "COMMIT", "commit");
			auto swaps = SinkCountersForTest().swaps;
			Try(client, sql, r2, error);
			std::string why, rerr, why_ref;
			auto want = Reference(setup, pages, "", filtered, "w1 w10", 10, rerr);
			Check(rerr.empty() && SameHits(Hits(r1), want, true, why_ref),
			      "E5 the EXECUTE's results equal the fresh reference at that point", rerr.empty() ? why_ref : rerr);
			bool gone = true;
			for (auto &row : r1) {
				gone = gone && row[0].GetValue<int64_t>() != top;
			}
			Check(ran && r0.size() == 10 && r1.size() == 10 && gone && SameHits(Hits(r1), Hits(r2), true, why) && swaps >= 1,
			      "E5 EXECUTE after another connection's commits equals a fresh statement's run (rebound, swaps " +
			          std::to_string(swaps) + "), without the deleted top hit",
			      ran ? why : FirstLine(error));
		}
	}

	// ---- T9: the verdict's search entries and the index records (search spec 4.2 rule 8, ruling 18) -----
	{
		auto sql = Search("SELECT rowid FROM lake.main.pages WHERE f10 = 0");
		auto reset = [&] {
			g_population = "table";
			g_bundle_root = data;
			g_omit_search = false;
			g_indexes.Fail(false);
		};
		reset();
		{
			Rows rows;
			std::string error;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, sql, rows, error);
			auto last = qod::LastSearchCallForTest();
			Check(ran && rows.size() == 10 && last.called && last.population == QS_POPULATION_TABLE,
			      "T9 a verdict entry 'table' searches the table population", ran ? "" : FirstLine(error));
		}
		{
			g_population = "allowed";
			Rows rows;
			std::string error;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, sql, rows, error);
			auto last = qod::LastSearchCallForTest();
			Check(ran && rows.size() == 10 && last.called && last.population == QS_POPULATION_ALLOWED,
			      "T9 a stand-in decider answering 'allowed' makes the call use the allowed population",
			      ran ? "population " + std::to_string(last.population) : FirstLine(error));
			g_population = "table";
		}
		g_omit_search = true;
		Refused(client, "T9 a search plan's verdict without search entries", sql, "QoD: search_population_missing");
		g_omit_search = false;
		// The record source cannot answer: retryable, before any read.
		g_indexes.Fail(true);
		Refused(client, "T9 the record source cannot answer", sql,
		        "QoD: authorization unavailable, retry: search index record unavailable");
		g_indexes.Fail(false);
		{
			// The bundle folder comes from the record, never from the node: a record root holding no bundle finds none.
			Rows rows;
			std::string error;
			auto ran = Try(client, sql, rows, error);
			auto elsewhere = base / "elsewhere";
			fs::create_directories(elsewhere);
			g_bundle_root = fs::canonical(elsewhere).string() + "/";
			{
				// Task 11: no bundle under the record's root, so every live row goes to the fallback.
				Rows none;
				qod::ResetLastSearchCallForTest();
				auto ran_none = Try(client, sql, none, error);
				auto record = qod::LastSearchCallForTest();
				auto live = Int(setup, "SELECT count(*) FROM lake.main.pages");
				Check(ran && ran_none && none.size() == 10 && SameHits(Hits(none), Hits(rows), true, error) &&
				          record.fallback_docs == static_cast<uint64_t>(live),
				      "T9 a record root holding no bundle: every live row through the fallback, the same answer",
				      ran_none ? error : FirstLine(error));
				qod::search::SetMaxUnindexedRowsForTest(1000);
				RefusedAtRun(client, "T9 a record root holding no bundle, past a cap of 1,000", sql,
				             "QoD: search_index_behind: lake.main.pages has " + std::to_string(live) +
				                 " unindexed live rows (cap 1000)");
				qod::search::SetMaxUnindexedRowsForTest(200000);
			}
			g_bundle_root = data;
			// Off a quack session the record source serves too (the table population).
			Rows off;
			qod::ResetLastSearchCallForTest();
			auto ran_off = Try(setup, sql, off, error);
			auto last = qod::LastSearchCallForTest();
			Check(ran_off && SameHits(Hits(off), Hits(rows), true, error) && last.population == QS_POPULATION_TABLE,
			      "T9 off a quack session: the record's bundles, the table population, the same answer",
			      ran_off ? error : FirstLine(error));
		}
		{
			// A two-call plan: one entry per call, each under its own index.
			auto two = Search("SELECT rowid FROM lake.main.pages WHERE f10 = 0") + " UNION ALL " +
			           Search("SELECT rowid FROM lake.main.tail", kQuery, "'lake.main.tail'");
			Rows rows;
			std::string error;
			auto ran = Try(client, "SELECT count(*) FROM (" + two + ")", rows, error);
			Check(ran && rows.size() == 1 && rows[0][0].GetValue<int64_t>() == 20, "T9 two calls, two entries",
			      ran ? rows.empty() ? "no row" : rows[0][0].ToString() : FirstLine(error));
		}
		{
			// Superusers and the system principal never call the decider (ruling 18): a plan holding search calls
			// takes the local allow, the table population and the record source's records.
			Connection root(db);
			Connection system(db);
			if (Install(root, "acme/acme_db/bi", false, true) && Install(system, "acme/acme_db/bi", true)) {
				for (auto pair : {std::make_pair(&root, std::string("u-root")), std::make_pair(&system, std::string("system"))}) {
					auto &con = *pair.first;
					auto &id = pair.second;
					g_plan_decides.clear();
					g_search_decides.clear();
					Rows rows;
					std::string error;
					auto plain = Try(con, "SELECT count(*) FROM lake.main.pages", rows, error);
					Check(plain && g_plan_decides[id] == 0, "T9 " + id + ": a plan without search calls takes no decide call",
					      plain ? std::to_string(g_plan_decides[id]) + " calls" : FirstLine(error));
					rows.clear();
					qod::ResetLastSearchCallForTest();
					g_omit_search = true;
					auto ran = Try(con, sql, rows, error);
					g_omit_search = false;
					auto last = qod::LastSearchCallForTest();
					Check(ran && rows.size() == 10 && last.population == QS_POPULATION_TABLE && g_plan_decides[id] == 0,
					      "T9 " + id + ": a search plan takes no decide call, the table population",
					      ran ? std::to_string(g_plan_decides[id]) + " plan calls" : FirstLine(error));
				}
			}
		}
		reset();
	}

	// ---- M1 to M8 and the bundle refusals (Phase 1, Task 4) -----------------------------------------
	RunPhase1(db, setup, sysc, client, data, base);
	RunFixRound1(db, setup, sysc, client, data);

	// ---- V1 to V8 and the bundle removal (Task 10) -------------------------------------------------
	RunVisibility(setup, sysc, client, data);
	RunCacheKeysAndPins(setup, sysc, client, base, data);

	// ---- F1 to F8: the fallback (Task 11) --------------------------------------------------------------
	RunFallback(db, setup, sysc, client, data);

	// ---- B1 to B4: bounds and budget (Task 11) ---------------------------------------------------------
	RunBounds(setup, sysc, client, data);

	// ---- C0 to C6: the caches against memory_limit, missing bundles (Task 12) ------------------------------
	RunC0(setup);
	RunCaches(db, setup, sysc, client, data);

	// ---- K0 to K3: the node's search knobs, read at registration (Task 16) ------------------------------------
	RunKnobs(db, setup, sysc, client, data);

	// ---- G1 and G2 on a fresh DuckDB-file lake (Task 13); the Postgres catalog's run is search_engine_postgres ------
	RunFreshCases(base / "g-duckdb", false);

	// ---- Y0 to Y8 on a fresh DuckDB-file lake (Task 14); the Postgres catalog's run is search_engine_postgres ------
	RunYCases(base / "y-duckdb", false);

	fs::remove_all(base);
	Say("search_engine_test: checks " + std::to_string(g_checks) + ", failed " + std::to_string(g_failures));
	return g_failures == 0 ? 0 : 1;
}

//! search_engine_postgres: G1 and G2 on a Postgres catalog (Task 13), where QOD_P3_PG_DSN_FILE names a libpq
//! key=value connection string file (never printed); 77 (skipped) when it is not set, missing, or postgres cannot
//! load.
//! --only-y: Y0 to Y8 alone, on the DuckDB-file catalog (a development aid; ctest runs the whole program).
int RunOnlyY() {
	auto base = fs::temp_directory_path() / ("qod-search-engine-y-" + std::to_string(::getpid()));
	RunYCases(base / "y-duckdb", false);
	fs::remove_all(base);
	Say("search_engine_test --only-y: checks " + std::to_string(g_checks) + ", failed " + std::to_string(g_failures));
	return g_failures == 0 && g_checks > 0 ? 0 : 1;
}

int RunPostgres() {
	auto dsn = std::getenv("QOD_P3_PG_DSN_FILE");
	if (!dsn || !*dsn || !fs::exists(dsn)) {
		Say("search_engine_test --postgres: QOD_P3_PG_DSN_FILE not set or missing, skipped");
		return 77;
	}
	{
		DuckDB probe(nullptr);
		Connection con(probe);
		for (std::string ext : {"ducklake", "parquet", "postgres"}) {
			if (con.Query("INSTALL " + ext)->HasError() || con.Query("LOAD " + ext)->HasError()) {
				Say("search_engine_test --postgres: " + ext + " unavailable, skipped");
				return 77;
			}
		}
	}
	auto base = fs::temp_directory_path() / ("qod-search-engine-pg-" + std::to_string(::getpid()));
	if (!OnlyY()) {
		RunFreshCases(base / "g-postgres", true);
	}
	RunYCases(base / "y-postgres", true);
	fs::remove_all(base);
	Say("search_engine_test --postgres: checks " + std::to_string(g_checks) + ", failed " + std::to_string(g_failures));
	return g_failures == 0 && g_checks > 0 ? 0 : 1;
}

} // namespace

int main(int argc, char **argv) {
	if (argc < 2) {
		std::cerr << "usage: search_engine_test <golden descriptions dir> [--postgres]" << std::endl;
		return 2;
	}
	bool postgres = false;
	for (int i = 2; i < argc; i++) {
		std::string arg = argv[i];
		if (arg == "--postgres") {
			postgres = true;
		} else if (arg == "--only-y") {
			g_only_y = true;
		}
	}
	if (postgres) {
		return RunPostgres();
	}
	if (g_only_y) {
		return RunOnlyY();
	}
	return Run(fs::path(argv[1]));
}
