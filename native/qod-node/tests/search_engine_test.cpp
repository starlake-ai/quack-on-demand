// Search Phase 0 part 2, Task 8 (spec 4.1, 4.2 rules 1, 2, 4, 5, 7, 9, 7.1, 7.6): qod_search end to end
// in qod-node, on a DuckLake with a DuckDB-file catalog.
//
// Fixture: lake.main.pages(id, title, body, lang, f10) of 20,000 rows (Task 3's rule: f10 = id % 10,
// lang 'fr' for even ids, a Zipf body) in 2 data files, lake.main.other(id, body), a plain view
// lake.main.v AS SELECT rowid AS rid, * FROM lake.main.pages and a secure view memory.main.sv over pages;
// a bundle per data file built with qod_search_build_segment; qod-node's hooks with a decider that allows
// everything and records the descriptions; the measured connection tagged as a quack session with a
// tenant principal (pool acme/acme_db/bi).
//
// The reference (also Tasks 10 and 11): a fresh single segment built through qs_builder_* from the live
// rows, searched with QS_POPULATION_TABLE, an all-ones live bitset and the allowed bitset marking the
// rows the case's input returns. Cases: A1 to A9 admitted (rowids, ranks and bit-identical scores against the
// reference); R1 to R27 refused (or recorded) before any segment is read and before any sink plan; D1 to
// D4 the descriptions; E0 to E5 the execution through the sink; G1 the GetAllFiles re-entrancy count;
// T9 the verdict's search entries; V0 to V8 visibility at S, the liveness cache and the bundle removal
// (Task 10, on lake.main.vt: 30,000 rows in 3 files, a delete file, an update flushed, a merge); V9 the
// cache's key, its bounds and a catalog pinned at a snapshot (Task 10 fix round 1, a SQLite-catalog lake).
// W1 the row-policy rewriter's actual output (golden/search-rewriter-outputs.sql, Task 12) through the lineage
// check; W2 the prototype build endpoint's statements (SearchPrototypeHandlers) on the system principal's
// connection, then a search over those bundles. F1 to F8 the fallback for unindexed files and inlined rows
// and its cap (Task 11, on lake.main.ft and lake.main.big): results equal to the reference with no bundle
// and no flush, the cap refused before any row is read, no file opened outside the target scan's own, the
// memory budget and the interrupt.
// Exits 77 when ducklake cannot load.

#include "authz.hpp"
#include "crypto.hpp"
#include "decide_client.hpp"
#include "json.hpp"
#include "node_hooks.hpp"
#include "search_fallback.hpp"
#include "search_functions.hpp"
#include "search_lineage.hpp"
#include "search_segments.hpp"
#include "search_sink.hpp"
#include "search_visibility.hpp"
#include "test_handles.hpp"

#include "qod_search.h"

#include "duckdb.hpp"
#include "duckdb/common/opener_file_system.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <unistd.h>
#include <thread>
#include <vector>

using namespace duckdb;
namespace fs = std::filesystem;
using qod::search::ResetSinkCountersForTest;
using qod::search::SinkCountersForTest;

namespace {

int g_failures = 0;
int g_checks = 0;

//! The part 2 Postgres's password while G1's Postgres run holds it (never printed: Say redacts it).
std::string g_secret_password;

void Say(const std::string &line) {
	auto text = line;
	if (!g_secret_password.empty()) {
		size_t at;
		while ((at = text.find(g_secret_password)) != std::string::npos) {
			text.replace(at, g_secret_password.size(), "<redacted>");
		}
	}
	std::cout << text << std::endl;
}

void Check(bool ok, const std::string &what, const std::string &detail = "") {
	g_checks++;
	if (!ok) {
		g_failures++;
	}
	Say(std::string(ok ? "ok   " : "FAIL ") + what + (detail.empty() ? "" : ": " + detail));
}

std::string FirstLine(const std::string &text) {
	auto line = text.substr(0, text.find('\n'));
	return line.size() > 400 ? line.substr(0, 400) + "..." : line;
}

std::string Quote(const std::string &text) {
	return "'" + text + "'";
}

using Rows = std::vector<std::vector<Value>>;

bool Try(Connection &con, const std::string &sql, Rows &out, std::string &error) {
	out.clear();
	auto result = con.Query(sql);
	if (result->HasError()) {
		error = result->GetError();
		return false;
	}
	while (auto chunk = result->Fetch()) {
		for (idx_t r = 0; r < chunk->size(); r++) {
			std::vector<Value> row;
			for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
				row.push_back(chunk->GetValue(c, r));
			}
			out.push_back(std::move(row));
		}
	}
	error.clear();
	return true;
}

bool Exec(Connection &con, const std::string &sql, const std::string &what) {
	Rows rows;
	std::string error;
	if (!Try(con, sql, rows, error)) {
		Check(false, "setup " + what, FirstLine(error));
		return false;
	}
	return true;
}

int64_t Int(Connection &con, const std::string &sql) {
	Rows rows;
	std::string error;
	if (!Try(con, sql, rows, error) || rows.empty()) {
		Check(false, "setup read " + sql, FirstLine(error));
		return -1;
	}
	return rows[0][0].GetValue<int64_t>();
}

// ---- hits and the reference --------------------------------------------------------------------

struct Hit {
	int64_t rowid;
	float score;
	int32_t rank;
};

std::vector<Hit> Hits(const Rows &rows, idx_t rowid_col = 0, idx_t score_col = 1, idx_t rank_col = 2) {
	std::vector<Hit> hits;
	for (auto &row : rows) {
		hits.push_back(Hit {row[rowid_col].GetValue<int64_t>(), row[score_col].GetValue<float>(),
		                    rank_col == DConstants::INVALID_INDEX ? 0 : row[rank_col].GetValue<int32_t>()});
	}
	return hits;
}

std::string Show(const std::vector<Hit> &hits) {
	std::ostringstream out;
	for (size_t i = 0; i < hits.size() && i < 4; i++) {
		out << (i ? " " : "") << hits[i].rowid << "@" << hits[i].score;
	}
	if (hits.size() > 4) {
		out << " ...";
	}
	return "[" + out.str() + "] (" + std::to_string(hits.size()) + ")";
}

//! The same rowids in the same order, ranks 1..n (when `ranked`), and bit-identical scores: every reference is
//! the same library's qs_search over a fresh segment, the same f32 order (invariant 7's determinism ruling,
//! Task 11 review minor 2).
bool SameHits(const std::vector<Hit> &got, const std::vector<Hit> &want, bool ranked, std::string &why) {
	if (got.size() != want.size()) {
		why = "got " + Show(got) + ", want " + Show(want);
		return false;
	}
	for (size_t i = 0; i < got.size(); i++) {
		if (got[i].rowid != want[i].rowid || (ranked && got[i].rank != int32_t(i + 1)) || got[i].score != want[i].score) {
			why = "at " + std::to_string(i) + ": got " + Show(got) + ", want " + Show(want);
			return false;
		}
	}
	return true;
}

std::string Take(char *err) {
	std::string text = err ? err : "";
	qs_string_free(err);
	return text;
}

//! A fresh single segment of `table`'s live rows (at `at`), searched for `query` with the TABLE
//! population and the allowed bitset marking the rowids `allowed_sql` returns.
//! `column` is the column read for the docs, as named at `at` (body by default).
std::vector<Hit> Reference(Connection &setup, const std::string &table, const std::string &at,
                           const std::string &allowed_sql, const std::string &query, uint32_t k, std::string &error,
                           const std::string &column = "body") {
	Rows rows;
	if (!Try(setup, "SELECT rowid, " + column + " FROM " + table + at + " ORDER BY rowid", rows, error)) {
		return {};
	}
	Rows allowed_rows;
	if (!Try(setup, allowed_sql, allowed_rows, error)) {
		return {};
	}
	std::set<int64_t> allowed;
	for (auto &row : allowed_rows) {
		allowed.insert(row[0].GetValue<int64_t>());
	}
	std::string path = "/qod-reference/" + table;
	auto key = qod::Hex(qod::Sha256(path));
	QsSource source {"00000000-0000-8000-8000-000000000000", key.c_str(), path.c_str(), 1, 1, -1, -1, -1, -1};
	char *err = nullptr;
	auto builder = qs_builder_new(&source, nullptr, UINT64_MAX, &err);
	if (!builder) {
		error = Take(err);
		return {};
	}
	for (size_t i = 0; i < rows.size(); i++) {
		auto text = rows[i][1].IsNull() ? std::string() : rows[i][1].GetValue<std::string>();
		if (qs_builder_add(builder, i, rows[i][0].GetValue<int64_t>(), -1, text.data(), text.size(), &err) != 0) {
			error = Take(err);
			qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
			return {};
		}
	}
	uint8_t *bytes = nullptr;
	size_t len = 0;
	uint64_t docs = 0;
	if (qs_builder_finish(builder, &bytes, &len, &docs, &err) != 0) {
		error = Take(err);
		return {};
	}
	auto segment = qs_segment_open(bytes, len, &source, &err);
	qs_bytes_free(bytes, len);
	if (!segment) {
		error = Take(err);
		return {};
	}
	size_t words = (rows.size() + 63) / 64;
	std::vector<uint64_t> live(words, ~uint64_t(0));
	std::vector<uint64_t> allowed_bits(words, 0);
	for (size_t i = 0; i < rows.size(); i++) {
		if (allowed.count(rows[i][0].GetValue<int64_t>())) {
			allowed_bits[i >> 6] |= uint64_t(1) << (i & 63);
		}
	}
	const QsSegment *segments[] = {segment};
	const uint64_t *lives[] = {live.data()};
	const uint64_t *alloweds[] = {allowed_bits.data()};
	size_t lens[] = {words};
	std::vector<QsHit> out(k);
	size_t n = 0;
	auto rc = qs_search(segments, 1, lives, lens, alloweds, lens, QS_POPULATION_TABLE, query.c_str(), "body", 1.2f,
	                    0.75f, k, nullptr, UINT64_MAX, out.data(), out.size(), &n, &err);
	qs_segment_free(segment);
	if (rc != 0) {
		error = Take(err);
		return {};
	}
	std::vector<Hit> hits;
	for (size_t i = 0; i < n; i++) {
		hits.push_back(Hit {out[i].rowid, out[i].score, int32_t(i + 1)});
	}
	return hits;
}

// ---- the decider, the session, the G1 counter --------------------------------------------------

struct FakeQuackSession : public ClientContextState {};
int64_t now_ms = 1761200000000;
std::vector<std::string> g_descriptions;

//! What the stand-in decider answers each search call of a plan (Task 9, search spec 4.2 rule 8), as the
//! manager's DecideRoute does: the population (g_population), the prototype index id of the described
//! table and column (unless g_wrong_index), and g_bundle_root; no "search" at all when g_omit_search.
std::string g_population = "table";
std::string g_bundle_root;
bool g_omit_search = false;
bool g_wrong_index = false;
//! Plan decide calls per principal id, and how many of them described search calls.
std::map<std::string, int> g_plan_decides, g_search_decides;

qod::DecideResult Recorder(const qod::DecideCall &call) {
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	if (call.kind == "plan") {
		g_descriptions.push_back(call.description);
		g_plan_decides[call.principal.id]++;
		auto described = qod::ParseJson(call.description);
		if (auto searches = described.GetOptional("searches", qod::JsonValue::Kind::ARRAY)) {
			g_search_decides[call.principal.id]++;
			for (auto &s : searches->array) {
				qod::SearchEntry entry;
				entry.call = static_cast<uint64_t>(s.Get("call", qod::JsonValue::Kind::INTEGER).integer);
				entry.population = g_population;
				entry.index_id = g_wrong_index ? "00000000-0000-8000-8000-000000000000"
				                               : qod::search::PrototypeIndexId(
				                                     s.Get("table", qod::JsonValue::Kind::STRING).string,
				                                     s.Get("columns", qod::JsonValue::Kind::ARRAY).array.at(0).string);
				entry.bundle_root = g_bundle_root;
				if (!g_omit_search) {
					result.verdict.search.push_back(entry);
				}
			}
		}
	}
	return result;
}

bool Install(Connection &con, const std::string &pool, bool system = false, bool superuser = false) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
	qod_test::MintSpec spec;
	spec.principal_id = system ? "system" : superuser ? "u-root" : "u-plain";
	spec.exp = now_ms + 60000;
	spec.pool = pool;
	spec.system = system;
	spec.superuser = superuser;
	auto installed = con.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		Check(false, "tenant prelude for " + pool, FirstLine(installed->GetError()));
		return false;
	}
	return true;
}

//! G1 (change 23): statements bound on contexts other than the client's while the hook resolves the
//! snapshot view (GetAllFiles and the target scan), and after it, until the client's statement ends.
struct G1 {
	std::mutex lock;
	const ClientContext *client = nullptr;
	bool on = false;
	bool resolving = false;
	bool resolved = false;
	std::map<const ClientContext *, int> during, after;
	int untagged_during = 0;
	int untagged_after = 0;
	//! The same counts from a post_bind registered BEFORE qod-node's (Task 16): equal counts mean DuckDB called
	//! every post_bind between the two, qod-node's included, on each of those statements (it calls them in
	//! registration order, and a hook that threw would have failed the statement).
	int pre_during = 0;
	int pre_after = 0;
};
G1 g_g1;

void CountingPreBind(PlannerExtensionInput &input, BoundStatement &) {
	std::lock_guard<std::mutex> guard(g_g1.lock);
	if (!g_g1.on || &input.context == g_g1.client) {
		return;
	}
	if (g_g1.resolving) {
		g_g1.pre_during++;
	} else if (g_g1.resolved) {
		g_g1.pre_after++;
	}
}

void CountingPostBind(PlannerExtensionInput &input, BoundStatement &) {
	std::lock_guard<std::mutex> guard(g_g1.lock);
	if (!g_g1.on || &input.context == g_g1.client) {
		return;
	}
	auto untagged = input.context.registered_state->Get<ClientContextState>(qod::kQuackSessionKey) == nullptr;
	if (g_g1.resolving) {
		g_g1.during[&input.context]++;
		g_g1.untagged_during += untagged;
	} else if (g_g1.resolved) {
		g_g1.after[&input.context]++;
		g_g1.untagged_after += untagged;
	}
}

// ---- the statements ----------------------------------------------------------------------------

const std::string kTable = "'lake.main.pages'";
const std::string kCols = "['body']";
const std::string kQuery = "'w1 w10'";

std::string Search(const std::string &input, const std::string &query = kQuery, const std::string &table = kTable,
                   const std::string &columns = kCols, const std::string &extra = "") {
	return "SELECT * FROM qod_search((" + input + "), " + table + ", " + columns + ", " + query + extra + ")";
}

struct Counters {
	uint64_t reads, hits, plans;
};

Counters Now() {
	auto cache = qod::search::SegmentCacheCountersForTest();
	return Counters {cache.reads, cache.hits, SinkCountersForTest().plans};
}

//! A refused statement: fails with `expected` in its error, before any segment is read and before any
//! sink plan (E3). Empty `expected`: any error, recorded.
void Refused(Connection &con, const std::string &name, const std::string &sql, const std::string &expected) {
	ResetSinkCountersForTest();
	auto before = Now();
	Rows rows;
	std::string error;
	auto ok = Try(con, sql, rows, error);
	auto after = Now();
	Check(!ok && (expected.empty() || error.find(expected) != std::string::npos),
	      name + " refused" + (expected.empty() ? "" : " with " + expected), ok ? "it ran" : FirstLine(error));
	Check(after.reads == before.reads && after.hits == before.hits && after.plans == 0,
	      name + ": no segment read, no sink plan (E3)",
	      "reads " + std::to_string(after.reads - before.reads) + ", hits " + std::to_string(after.hits - before.hits) +
	          ", plans " + std::to_string(after.plans));
}

//! A statement refused when it runs (the sink's factory), with `expected` in its error and no row.
void RefusedAtRun(Connection &con, const std::string &name, const std::string &sql, const std::string &expected) {
	Rows rows;
	std::string error;
	auto ok = Try(con, sql, rows, error);
	Check(!ok && rows.empty() && error.find(expected) != std::string::npos, name + ": refused with " + expected,
	      ok ? "it ran: " + Show(Hits(rows)) : FirstLine(error));
}

std::string Fingerprint(Connection &con, const std::string &sql, std::string &description) {
	g_descriptions.clear();
	Rows rows;
	std::string error;
	Try(con, sql, rows, error);
	if (g_descriptions.empty()) {
		description = "(none: " + FirstLine(error) + ")";
		return "";
	}
	description = g_descriptions.front();
	return qod::Hex(qod::Sha256(description));
}

std::string ReadFile(const fs::path &path) {
	std::ifstream in(path);
	std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
		text.pop_back();
	}
	return text;
}

//! Builds every data file's bundle of `table` with qod_search_build_segment.
//! The data files whose bundles are built (BuildSegments builds each once).
std::set<std::string> g_built;

//! Builds the bundle of every data file of `table` not built yet, with qod_search_build_segment on the
//! system principal's connection `builder` (the files listed on `setup`).
bool BuildSegments(Connection &setup, Connection &builder, const std::string &table, const std::string &root,
                   int64_t &files) {
	Rows listed;
	std::string error;
	if (!Try(setup,
	         "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files('lake', " +
	             Quote(table) + ")",
	         listed, error)) {
		Check(false, "list " + table + "'s files", FirstLine(error));
		return false;
	}
	files = static_cast<int64_t>(listed.size());
	for (auto &file : listed) {
		auto path = file[0].GetValue<std::string>();
		if (g_built.count(path)) {
			continue;
		}
		Rows built;
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT, body FROM "
		           "lake.main." +
		           table + " WHERE filename = " + Quote(path) + " ORDER BY file_row_number), 'lake.main." + table +
		           "', 'body', " + Quote(root) + ", " + Quote(path) + ", " + file[1].ToString() + "::UBIGINT, " +
		           file[2].ToString() + "::UBIGINT)";
		if (!Try(builder, sql, built, error) || built.size() != 1) {
			Check(false, "build the bundle of " + table + " " + path, FirstLine(error));
			return false;
		}
		g_built.insert(path);
		Say("   built " + built[0][0].ToString() + ": " + built[0][1].ToString() + " docs, " + built[0][2].ToString() +
		    " bytes");
	}
	return true;
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
		           ", file_row_number = true) ORDER BY file_row_number), " + Quote(index_table) + ", 'body', " + Quote(root) + ", " +
		           Quote(path) + ", " + file[1].ToString() + "::UBIGINT, " + file[2].ToString() + "::UBIGINT)";
		if (!Try(builder, sql, built, error) || built.size() != 1) {
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

//! The bundle of `file` (index lake.main.vt / body) under `root`, through the node's segment cache, opened as
//! the search opens it: under the index id bound to body's field id (BundleSourceId, Task 11 review C1).
std::shared_ptr<const qod::search::OpenSegment> VSegment(Connection &setup, const std::string &root,
                                                         const qod::search::DataFileRef &file) {
	auto index_id = qod::search::PrototypeIndexId("lake.main.vt", "body");
	auto source_id = qod::search::BundleSourceId(index_id, ColumnFieldId(setup, "vt", "body"));
	auto copy = file;
	return qod::search::LoadSegment(*setup.context, qod::search::SegmentPath(root, index_id, file.bundle_key), source_id,
	                                copy);
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
	bool ok = Exec(setup, "CREATE TABLE lake.main.vt (id BIGINT, body VARCHAR, f10 INTEGER)", "vt");
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
		Check(ok1 && ok2 && !c1.view_hit && c2.view_hit && c1.view == c2.view && k1.misses == k0.misses + 1 &&
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
		auto folder = fs::path(data) / ".qod_search" / qod::search::PrototypeIndexId("lake.main.vt", "body");
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
		             "QoD: search_index_behind: lake.main.vt has " + std::to_string(live) + " unindexed rows (cap " +
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
	          insert("pt", 0, 500) && insert("pt", 500, 1000) && insert("pt2", 0, 700);
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
		// Review M9: each bitset is shrunk to fit, so the counted bytes are the allocated ones (capacity).
		for (auto record : {&pt, &pt2}) {
			bool fitted = record->view != nullptr;
			uint64_t allocated = 0;
			if (record->view) {
				for (auto &live : record->view->liveness) {
					fitted = fitted && live.words.capacity() == live.words.size();
					allocated += live.words.capacity() * sizeof(uint64_t);
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

std::mutex g_fs_mutex;
bool g_fs_counting = false;
std::map<std::string, idx_t> g_fs_opens;

//! The client's FileSystem, counting every open while on (Task 6's, search_snapshot_test.cpp).
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

//! The .seg files of `table`'s index under `root`, deleted; how many.
int RemoveBundles(const std::string &root, const std::string &table) {
	auto folder = fs::path(root) / ".qod_search" / qod::search::PrototypeIndexId(table, "body");
	int removed = 0;
	std::error_code ec;
	for (auto &entry : fs::directory_iterator(folder, ec)) {
		if (entry.path().extension() == ".seg") {
			removed += fs::remove(entry.path(), ec) ? 1 : 0;
		}
	}
	qod::search::ClearSegmentCacheForTest();
	return removed;
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
	if (!Exec(setup, "CREATE TABLE lake.main.ft (id BIGINT, body VARCHAR, f10 INTEGER)", "ft") ||
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
		          err.find("QoD: search_index_behind: lake.main.ft has 1500 unindexed rows (cap 1000); retry once the indexer "
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
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
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
		             "QoD: search_index_behind: lake.main.big has 200000 unindexed rows (cap 199999)");
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
		bool made = Exec(setup, "CREATE TABLE lake.main.st (id BIGINT, body VARCHAR, f10 INTEGER)", "st");
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

	// F10 (review minor 6): a prepared search in a transaction that then writes to the lake: refused.
	{
		Exec(client, "BEGIN TRANSACTION", "F10 begin");
		auto prepared = client.Prepare(all);
		Rows rows;
		std::string err;
		bool first = false, second_refused = false;
		if (!prepared->HasError()) {
			auto r1 = prepared->Execute();
			first = !r1->HasError();
			Exec(client, "INSERT INTO lake.main.ft VALUES (30000, 'zqc', 0)", "F10 a write in the transaction");
			auto r2 = prepared->Execute();
			second_refused = r2->HasError() && r2->GetError().find("QoD: search_uncommitted_writes") != std::string::npos;
			err = r2->HasError() ? FirstLine(r2->GetError()) : "it ran";
		} else {
			err = FirstLine(prepared->GetError());
		}
		Exec(client, "ROLLBACK", "F10 rollback");
		Check(first && second_refused,
		      "F10 BEGIN; EXECUTE p; INSERT ...; EXECUTE p: the second EXECUTE is refused search_uncommitted_writes", err);
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

	// R (review C1): a rename swap. rt(body, notes) bundled, then body renamed secret and notes renamed body. The
	// verdict judges today's body (notes' field), so every search reads notes' field: through the fallback (the
	// old bundle is bound to the field now called secret, so it reads as missing) and through a bundle rebuilt
	// for today's body, at the current S and at AT (VERSION => S0), as a tenant. 'alpha' (the old body's word,
	// now secret's) never matches.
	{
		bool ok = Exec(setup, "CREATE TABLE lake.main.rt (id BIGINT, body VARCHAR, notes VARCHAR, f10 INTEGER)", "rt") &&
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
		auto run = [&](const std::string &label, const std::string &at, const std::string &column_at,
		               size_t fallback_segments) {
			auto input = "SELECT rowid FROM lake.main.rt" + at;
			for (auto query : {std::string("omega"), std::string("omega w3"), std::string("alpha")}) {
				qod::ResetLastSearchCallForTest();
				Rows rows;
				std::string err, rerr, why;
				auto ran = Try(client, Search(input, Quote(query), "'lake.main.rt'"), rows, err);
				auto record = qod::LastSearchCallForTest();
				auto want = Reference(setup, "lake.main.rt", at, input, query, 10, rerr, column_at);
				bool alpha = query == "alpha";
				Check(ran && rerr.empty() && record.fallback_segments == fallback_segments &&
				          (alpha ? rows.empty() : rows.size() == 10) && SameHits(Hits(rows), want, true, why),
				      "R " + label + ": '" + query + "' reads today's body (" + column_at + " at that S)" +
				          (alpha ? ", no hit" : ", equal to the reference") + ", " + std::to_string(fallback_segments) +
				          " fallback segments",
				      ran ? why + rerr + " (" + std::to_string(record.fallback_segments) + " fallback segments)"
				          : FirstLine(err));
			}
		};
		run("current S, the old bundle bound to another field: through the fallback", "", "body", 1);
		run("AT (VERSION => S0), through the fallback", At(s0), "notes", 1);
		g_built.clear();
		BuildSegments(setup, sysc, "rt", data, rt_files);
		run("current S, a bundle rebuilt for today's body", "", "body", 0);
		run("AT (VERSION => S0), the rebuilt bundle", At(s0), "notes", 0);
		// A field the snapshot read does not hold: refused before any segment is read.
		Exec(setup, "ALTER TABLE lake.main.rt ADD COLUMN fresh VARCHAR", "add fresh");
		Refused(client, "R a column added after S0, searched AT (VERSION => S0)",
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

//! G1 (change 23) on `client`: the statements bound on other contexts while the search's hook resolves the
//! snapshot view (GetAllFiles and the liveness scan) and after it until the statement ends, each counted by
//! the post_bind registered after qod-node's and by the one registered before it. One "== G1 <kind> miss|hit"
//! line.
//! `clear`: the liveness cache cleared first (a miss: GetAllFiles and the liveness scan run in the hook), else
//! as it is (after a miss at the same S, a hit).
void MeasureG1(Connection &client, const std::string &sql, const std::string &kind_p, bool clear) {
	auto kind = kind_p + (clear ? " miss" : " hit");
	if (clear) {
		qod::search::ClearLivenessCacheForTest();
	}
	{
		std::lock_guard<std::mutex> guard(g_g1.lock);
		g_g1.client = client.context.get();
		g_g1.during.clear();
		g_g1.after.clear();
		g_g1.untagged_during = g_g1.untagged_after = g_g1.pre_during = g_g1.pre_after = 0;
		g_g1.resolving = g_g1.resolved = false;
		g_g1.on = true;
	}
	qod::ResolveObserverForTest([](bool begin) {
		std::lock_guard<std::mutex> guard(g_g1.lock);
		g_g1.resolving = begin;
		if (!begin) {
			g_g1.resolved = true;
		}
	});
	Rows rows;
	std::string error;
	auto ran = Try(client, sql, rows, error);
	qod::ResolveObserverForTest(nullptr);
	std::lock_guard<std::mutex> guard(g_g1.lock);
	g_g1.on = false;
	int during = 0, after = 0;
	for (auto &e : g_g1.during) {
		during += e.second;
	}
	for (auto &e : g_g1.after) {
		after += e.second;
	}
	Check(ran && rows.size() == 10, "G1 " + kind + ": the search ran (10 rows)", FirstLine(error));
	Check(g_g1.pre_during == during && g_g1.pre_after == after,
	      "G1 " + kind + ": the post_bind registered before qod-node's saw the same statements (so qod-node's ran on "
	                     "each)",
	      std::to_string(g_g1.pre_during) + "/" + std::to_string(g_g1.pre_after) + " vs " + std::to_string(during) + "/" +
	          std::to_string(after));
	Say("== G1 " + kind + ": while the hook resolves (GetAllFiles and the liveness scan) " + std::to_string(during) +
	    " statement(s) bound on " + std::to_string(g_g1.during.size()) + " other context(s), untagged " +
	    std::to_string(g_g1.untagged_during) + "; during execution " + std::to_string(after) + " on " +
	    std::to_string(g_g1.after.size()) + " context(s), untagged " + std::to_string(g_g1.untagged_after) +
	    "; qod-node's post_bind called on each (pre-hook counts " + std::to_string(g_g1.pre_during) + "/" +
	    std::to_string(g_g1.pre_after) + ")");
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

//! G1 on a fresh lake (Task 16 Step 3, owed from Task 8): `postgres` true, a Postgres catalog, where
//! QOD_P2_PG_DSN_FILE names a file holding a libpq key=value connection string (never printed), in a database
//! of its own on that server, created here and dropped at the end; false, a DuckDB-file catalog (the same
//! fixture, for figures beside it). The fixture is Run's pages shape (2 files of 10,000 rows, one bundle each,
//! built on the system principal's connection), then the same search as Run's G1, a miss then a hit. The
//! Postgres run is skipped (a line, no failure) when the variable is not set or postgres cannot load.
void RunG1Fresh(const fs::path &base, bool postgres) {
	auto dsn_env = std::getenv("QOD_P2_PG_DSN_FILE");
	if (postgres && (!dsn_env || !*dsn_env || !fs::exists(dsn_env))) {
		Say("== G1 postgres: QOD_P2_PG_DSN_FILE not set or missing: skipped");
		return;
	}
	const std::string kind = postgres ? "postgres" : "duckdb-file fresh";
	fs::remove_all(base);
	fs::create_directories(base / "data");
	fs::create_directories(base / "bundles");
	auto data = fs::canonical(base / "data").string() + "/";
	auto root = fs::canonical(base / "bundles").string() + "/";
	const std::string db_name = "qod_t16_g1";

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
	for (std::string ext : {"ducklake", "parquet", "postgres"}) {
		if (!postgres && ext == "postgres") {
			continue;
		}
		if (setup.Query("INSTALL " + ext)->HasError() || setup.Query("LOAD " + ext)->HasError()) {
			Say("== G1 " + kind + ": " + ext + " unavailable: skipped");
			return;
		}
	}
	auto admin = [&](const std::string &stmt) {
		return Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach the admin database") &&
		       Exec(setup, "CALL postgres_execute('pgadmin', " + Quote(stmt) + ", use_transaction => false)", stmt) &&
		       Exec(setup, "DETACH pgadmin", "detach the admin database");
	};
	std::string attach = "ATTACH " + Quote("ducklake:" + (base / "meta.ducklake").string()) + " AS lake (DATA_PATH " +
	                     Quote(data) + ", DATA_INLINING_ROW_LIMIT 10)";
	if (postgres) {
		std::ifstream in(dsn_env);
		std::string dsn((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		auto parts = ParseDsn(dsn);
		g_secret_password = parts["password"];
		auto secret = "CREATE TEMPORARY SECRET (TYPE postgres, HOST " + Quote(parts["host"]) + ", PORT " + parts["port"] +
		              ", USER " + Quote(parts["user"]) + ", PASSWORD " + Quote(parts["password"]) + ")";
		if (setup.Query(secret)->HasError()) {
			Check(false, "G1 postgres: the secret could not be created (statement not printed)");
			g_secret_password.clear();
			return;
		}
		if (!admin("DROP DATABASE IF EXISTS " + db_name + " WITH (FORCE)") || !admin("CREATE DATABASE " + db_name)) {
			g_secret_password.clear();
			return;
		}
		attach = "ATTACH 'ducklake:postgres:dbname=" + db_name + "' AS lake (DATA_PATH " + Quote(data) +
		         ", DATA_INLINING_ROW_LIMIT 10)";
	}
	const std::string body = "array_to_string(list_transform(range(20 + (hash(i, 'len') % 30)::BIGINT), lambda j: 'w' "
	                         "|| floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')";
	auto insert = [&](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.pages SELECT i, 't' || i, " + body +
		       ", CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(" + std::to_string(from) +
		       ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = Exec(setup, attach, "attach the " + kind + " lake") &&
	          Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, f10 INTEGER)",
	               "pages") &&
	          Exec(setup, insert(0, 10000), "file 1") && Exec(setup, insert(10000, 20000), "file 2");
	{
		Connection sysc(db);
		Connection client(db);
		int64_t files = 0;
		auto saved_root = g_bundle_root;
		g_bundle_root = root;
		ok = ok && Install(sysc, "acme/acme_db/bi", true) && BuildSegments(setup, sysc, "pages", root, files) &&
		     Install(client, "acme/acme_db/bi");
		Check(ok && files == 2, "G1 " + kind + ": the fixture (2 data files, a bundle each)", std::to_string(files));
		if (ok) {
			MeasureG1(client, Search("SELECT rowid FROM lake.main.pages WHERE id < 20000"), kind, true);
			MeasureG1(client, Search("SELECT rowid FROM lake.main.pages WHERE id < 20000"), kind, false);
		}
		g_bundle_root = saved_root;
	}
	Exec(setup, "DETACH lake", "detach the lake");
	if (!postgres) {
		fs::remove_all(base);
		return;
	}
	admin("DROP DATABASE IF EXISTS " + db_name + " WITH (FORCE)");
	Rows left;
	std::string error;
	Exec(setup, "ATTACH 'dbname=postgres' AS pgadmin (TYPE postgres)", "attach the admin database");
	auto counted = Try(setup,
	                   "SELECT count(*) FROM postgres_query('pgadmin', 'SELECT 1 FROM pg_database WHERE datname = ''" +
	                       db_name + "'' ')",
	                   left, error);
	Check(counted && !left.empty() && left[0][0].GetValue<int64_t>() == 0, "G1 postgres: the run's database is dropped",
	      FirstLine(error));
	Exec(setup, "DETACH pgadmin", "detach the admin database");
	g_secret_password.clear();
	fs::remove_all(base);
}

int Run(const fs::path &goldens) {
	auto base = fs::temp_directory_path() / ("qod-search-engine-" + std::to_string(::getpid()));
	fs::remove_all(base);
	fs::create_directories(base / "data");
	auto data = fs::canonical(base / "data").string() + "/";

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
	// The builder is the system principal's only (spec 13), as qod_authz_stats.
	Connection sysc(db);
	if (!Install(sysc, "acme/acme_db/bi", true)) {
		return 1;
	}
	{
		const std::string build = "SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, NULL::BIGINT, "
		                          "'x'), 'lake.main.pages', 'body', " +
		                          Quote(data) + ", '/nowhere', 1::UBIGINT, 1::UBIGINT)";
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
	// Connections off a quack session (setup) take the prototype stand-in; the quack sessions take the
	// stand-in decider's verdict entries (Task 9).
	qod::SetPrototypeBundleRoot(data);
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
		               "W2 wt inlined");
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
			                   lit(path) + " ORDER BY pos), 'lake.main.wt', 'body', " + lit(data) + ", " + lit(path) +
			                   ", " + file[1].ToString() + "::UBIGINT, " + file[2].ToString() + "::UBIGINT)",
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
	        "search_input_wrong_table");
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
	Refused(client, "R10 SELECT rid FROM v", Search("SELECT rid FROM lake.main.v"), "search_input_not_governed: a view");
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
	        "search_argument_not_constant: table");
	{
		Connection branch(db);
		if (Install(branch, "acme/acme_db/__br_0011aabb")) {
			Refused(branch, "R20 a branch pool", Search("SELECT rowid FROM lake.main.pages"), "search_branch_unsupported");
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
	for (auto write : {std::string("INSERT INTO lake.main.pages VALUES (50000, 't', 'w1', 'fr', 0)"),
	                   std::string("DELETE FROM lake.main.pages WHERE id = 3")}) {
		Exec(client, "BEGIN TRANSACTION", "begin");
		Exec(client, write, "the write");
		Refused(client, "R27 after " + write.substr(0, 6) + " in the transaction", Search(filtered),
		        "QoD: search_uncommitted_writes");
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
		const std::string body = "search_input_not_governed: a search function called inside a view, macro or query() body";
		Refused(client, "I2 the view alone (P3a)", "SELECT r, score FROM lake.main.hv", body);
		Refused(client, "I2 the view after an unreferenced CTE showing one call (P3b)",
		        "WITH u AS (SELECT rowid FROM qod_search((SELECT rowid FROM lake.main.pages), 'lake.main.pages', "
		        "['body'], 'x')) SELECT r, score FROM lake.main.hv",
		        body);
		Refused(client, "I2 a plain view through another view", "SELECT r FROM lake.main.hv2", body);
		Refused(client, "I2 a view calling it in a CTE", "SELECT r FROM lake.main.hv3", body);
		Refused(setup, "I2 the view, as a superuser off a quack session", "SELECT r FROM lake.main.hv2", body);
		Refused(setup, "I2 a table macro, as a superuser", "SELECT r FROM memory.main.sm()", body);
		Refused(setup, "I2 a query() literal with a computed table, as a superuser",
		        "SELECT * FROM query('SELECT * FROM qod_search((SELECT rowid FROM lake.main.pages), ''lake.main.'' || "
		        "''pages'', [''body''], ''w1'')')",
		        "search_argument_not_constant: table");
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
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
		Check(!ran && rows.empty() && error.find("QoD: search_memory_exceeded") != std::string::npos,
		      "E0 a memory limit of 1 KiB: search_memory_exceeded (the node's own check) and no row", FirstLine(error));
		// The budget passed is the limit less the bitsets held; one just above them is the library's to refuse.
		// One local state (threads = 1), so the bytes held are the same from run to run.
		Exec(setup, "SET threads = 1", "threads 1");
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
		qod::SetSearchMemoryForTest(uint64_t(4) << 30);
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
		// The client interrupts 5 ms into qs_search; the watcher copies it into the cancel flag.
		std::thread interrupter;
		qod::SearchCallObserverForTest([&](ClientContext &context) {
			interrupter = std::thread([&context] {
				std::this_thread::sleep_for(std::chrono::milliseconds(5));
				context.Interrupt();
			});
		});
		qod::ResetLastSearchCallForTest();
		ran = Try(setup, Search(big, query, "'lake.main.big'"), rows, error);
		qod::SearchCallObserverForTest(nullptr);
		if (interrupter.joinable()) {
			interrupter.join();
		}
		auto record = qod::LastSearchCallForTest();
		Check(!ran && rows.empty() && error.find("Interrupt") != std::string::npos && record.library_cancelled,
		      "E0 an interrupt while qs_search runs: the watcher sets the flag, qs_search answers search_cancelled, "
		      "no row",
		      FirstLine(error) + (record.library_cancelled ? "" : " (qs_search did not report search_cancelled)"));
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

	// ---- T9: the verdict's search entries (search spec 4.2 rule 8, Task 9) ------------------------
	{
		auto sql = Search("SELECT rowid FROM lake.main.pages WHERE f10 = 0");
		auto reset = [] {
			g_population = "table";
			g_bundle_root.clear();
			g_omit_search = false;
			g_wrong_index = false;
		};
		reset();
		g_bundle_root = data;
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
		g_wrong_index = true;
		Refused(client, "T9 a verdict entry naming another index", sql,
		        "QoD: search_index_unavailable: the verdict's index record for lake.main.pages (body) names another "
		        "index");
		g_wrong_index = false;
		{
			// The bundle folder comes from the verdict, never from the stand-in: an empty stand-in root changes
			// nothing on a quack session, and a verdict root holding no bundle finds none.
			qod::SetPrototypeBundleRoot("");
			Rows rows;
			std::string error;
			auto ran = Try(client, sql, rows, error);
			Check(ran && rows.size() == 10, "T9 a quack session's search ignores the stand-in root",
			      ran ? "" : FirstLine(error));
			auto elsewhere = base / "elsewhere";
			fs::create_directories(elsewhere);
			g_bundle_root = fs::canonical(elsewhere).string() + "/";
			{
				// Task 11: no bundle under the verdict's root, so every live row goes to the fallback.
				Rows none;
				qod::ResetLastSearchCallForTest();
				auto ran_none = Try(client, sql, none, error);
				auto record = qod::LastSearchCallForTest();
				auto live = Int(setup, "SELECT count(*) FROM lake.main.pages");
				Check(ran_none && none.size() == 10 && SameHits(Hits(none), Hits(rows), true, error) &&
				          record.fallback_docs == static_cast<uint64_t>(live),
				      "T9 a verdict root holding no bundle: every live row through the fallback, the same answer",
				      ran_none ? error : FirstLine(error));
				qod::search::SetMaxUnindexedRowsForTest(1000);
				RefusedAtRun(client, "T9 a verdict root holding no bundle, past a cap of 1,000", sql,
				             "QoD: search_index_behind: lake.main.pages has " + std::to_string(live) +
				                 " unindexed rows (cap 1000)");
				qod::search::SetMaxUnindexedRowsForTest(200000);
			}
			g_bundle_root = data;
			Refused(setup, "T9 off a quack session, an empty stand-in root", sql, "QoD: search_index_unavailable");
			qod::SetPrototypeBundleRoot(data);
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
			// Superusers and the system principal: a plan holding search calls takes one record-only decide
			// call; any other plan none.
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
					auto ran = Try(con, sql, rows, error);
					auto last = qod::LastSearchCallForTest();
					Check(ran && rows.size() == 10 && last.population == QS_POPULATION_TABLE && g_search_decides[id] >= 1 &&
					          g_plan_decides[id] == g_search_decides[id],
					      "T9 " + id + ": a search plan takes record-only decide calls, each carrying search calls only",
					      ran ? std::to_string(g_plan_decides[id]) + " plan calls, " + std::to_string(g_search_decides[id]) +
					                " with searches"
					          : FirstLine(error));
					g_omit_search = true;
					Refused(con, "T9 " + id + ": a record-only verdict without entries", sql,
					        "QoD: search_population_missing");
					g_omit_search = false;
				}
			}
		}
		{
			// The prototype index id: the vectors AuthzDeciderSearchSpec (Scala) checks too.
			std::ifstream in(goldens.parent_path() / "search-index-id-v1.txt");
			std::string line;
			int vectors = 0;
			bool all = static_cast<bool>(in);
			while (std::getline(in, line)) {
				if (line.empty() || line[0] == '#') {
					continue;
				}
				auto t1 = line.find('\t');
				auto t2 = line.find('\t', t1 + 1);
				if (t1 == std::string::npos || t2 == std::string::npos) {
					all = false;
					continue;
				}
				vectors++;
				all = all && qod::search::PrototypeIndexId(line.substr(0, t1), line.substr(t1 + 1, t2 - t1 - 1)) ==
				                 line.substr(t2 + 1);
			}
			Check(all && vectors >= 4, "T9 PrototypeIndexId gives the shared vectors", std::to_string(vectors) + " vectors");
		}
		reset();
		g_bundle_root = data;
	}

	// ---- V1 to V8 and the bundle removal (Task 10) -------------------------------------------------
	RunVisibility(setup, sysc, client, data);
	RunCacheKeysAndPins(setup, sysc, client, base, data);

	// ---- F1 to F8: the fallback (Task 11) --------------------------------------------------------------
	RunFallback(db, setup, sysc, client, data);

	// ---- G1 on a Postgres catalog (Task 16 Step 3) -----------------------------------------------------
	RunG1Fresh(base / "g1-duckdb", false);
	RunG1Fresh(base / "g1-postgres", true);

	fs::remove_all(base);
	Say("search_engine_test: checks " + std::to_string(g_checks) + ", failed " + std::to_string(g_failures));
	return g_failures == 0 ? 0 : 1;
}

} // namespace

int main(int argc, char **argv) {
	if (argc < 2) {
		std::cerr << "usage: search_engine_test <golden descriptions dir>" << std::endl;
		return 2;
	}
	return Run(fs::path(argv[1]));
}
