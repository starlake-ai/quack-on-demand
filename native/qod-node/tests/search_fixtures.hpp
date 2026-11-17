#pragma once

// QoD Search tests: the fixtures every search suite shares (the engine test's, moved here unchanged by Task 17):
// checks and output, statements and their rows, the hits and the fresh-segment reference, the stand-in decider
// (FakeQuackSession, Recorder) and the stand-in index record source (g_indexes), a connection tagged as a quack
// session with a handle minted by qod_test::MintWith (Install), the search statements, the refusal checks with
// the segment and sink counters, the bundle build through qod_search_build_segment, the client file system
// counting every open, the knob registration, and OpenNode, which builds a node instance as the engine test's
// Run does. Header only, for test programs: every definition sits in this translation unit's unnamed namespace.

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
#include "duckdb/common/opener_file_system.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_data.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/query_result_stream.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
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
#include <vector>

#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif

using namespace duckdb;
namespace fs = std::filesystem;
using qod::search::ResetSinkCountersForTest;
using qod::search::SinkCountersForTest;

namespace {

int g_failures = 0;
int g_checks = 0;

//! The test Postgres's password while a Postgres catalog run holds it (never printed: Say redacts it).
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
//! `column` is the column read for the docs, as named at `at` (body by default); `columns`, when given, the
//! columns read (in that order, each a field of the segment and of the search), `column` then unused.
std::vector<Hit> Reference(Connection &setup, const std::string &table, const std::string &at,
                           const std::string &allowed_sql, const std::string &query, uint32_t k, std::string &error,
                           const std::string &column = "body", const std::vector<std::string> &columns = {}) {
	auto read = columns.empty() ? std::vector<std::string> {column} : columns;
	std::string select = "SELECT rowid";
	for (auto &c : read) {
		select += ", " + c;
	}
	Rows rows;
	if (!Try(setup, select + " FROM " + table + at + " ORDER BY rowid", rows, error)) {
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
	// The search's own column names: the segment's columns are named as the call names them (the query's
	// field prefixes read the call's columns).
	auto names = columns.empty() ? std::vector<std::string> {"body"} : columns;
	qod_test::ReferenceSource ref("/qod-reference/" + table, names);
	char *err = nullptr;
	auto builder = qs_builder_new(&ref.source, nullptr, UINT64_MAX, &err);
	if (!builder) {
		error = Take(err);
		return {};
	}
	std::vector<std::string> texts(read.size());
	std::vector<QsText> cells(read.size());
	for (size_t i = 0; i < rows.size(); i++) {
		for (size_t c = 0; c < read.size(); c++) {
			texts[c] = rows[i][c + 1].IsNull() ? std::string() : rows[i][c + 1].GetValue<std::string>();
			cells[c] = texts[c].empty() ? QsText {nullptr, 0} : QsText {texts[c].data(), texts[c].size()};
		}
		if (qs_builder_add(builder, i, rows[i][0].GetValue<int64_t>(), -1, cells.data(), cells.size(), &err) != 0) {
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
	auto segment = qs_segment_open(bytes, len, &ref.source, &err);
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
	QsSearchArgs args {};
	args.segments = segments;
	args.n_segments = 1;
	args.live = lives;
	args.live_words = lens;
	args.allowed = alloweds;
	args.allowed_words = lens;
	args.population = QS_POPULATION_TABLE;
	args.query = query.c_str();
	args.fields = ref.fields.data();
	args.n_fields = ref.fields.size();
	args.k1 = 1.2f;
	args.b = 0.75f;
	args.k = k;
	args.path = QS_PATH_EXHAUSTIVE;
	args.memory_budget = UINT64_MAX;
	auto rc = qs_search(&args, out.data(), out.size(), &n, &err);
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

// ---- the decider, the session -----------------------------------------------------------------

struct FakeQuackSession : public ClientContextState {};
int64_t now_ms = 1761200000000;
std::vector<std::string> g_descriptions;

//! What the stand-in decider answers each search call of a plan (search spec 4.2 rule 8), as the manager's
//! DecideRoute does: the population (g_population); no "search" at all when g_omit_search. The index
//! records come from the stand-in record source (g_indexes), never the verdict (ruling 18).
std::string g_population = "table";
bool g_omit_search = false;
//! Plan decide calls per principal id, and how many of them described search calls.
std::map<std::string, int> g_plan_decides, g_search_decides;
//! Every plan decide call, counted (review M2: the metadata reads come after the verdict).
std::atomic<int> g_decide_seq {0};

//! The stand-in index records (Task 7 installs the fetch), served by (catalog alias, table id). Every record's
//! bundle root is g_bundle_root while that is set (a test moving the root), else its own.
qod_test::TestIndexes g_indexes;
std::string g_bundle_root;

qod::DecideResult Recorder(const qod::DecideCall &call) {
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	if (call.kind == "plan") {
		g_decide_seq++;
		g_descriptions.push_back(call.description);
		g_plan_decides[call.principal.id]++;
		auto described = qod::ParseJson(call.description);
		if (auto searches = described.GetOptional("searches", qod::JsonValue::Kind::ARRAY)) {
			g_search_decides[call.principal.id]++;
			for (auto &s : searches->array) {
				qod::SearchEntry entry;
				entry.call = static_cast<uint64_t>(s.Get("call", qod::JsonValue::Kind::INTEGER).integer);
				entry.population = g_population;
				if (!g_omit_search) {
					result.verdict.search.push_back(entry);
				}
			}
		}
	}
	return result;
}

//! Registers the index of lake.main.`table` (or `catalog`) on `columns`, its bundles under `root`.
bool IndexTable(Connection &setup, const std::string &table, const std::vector<std::string> &columns = {"body"},
                const std::string &root = "", const std::string &catalog = "lake", const std::string &name = "",
                const std::string &meta = "") {
	std::string error;
	auto ok = g_indexes.Add(setup, catalog, table, columns, root.empty() ? g_bundle_root : root, error, name, meta);
	Check(ok, "register the index of " + catalog + ".main." + table, FirstLine(error));
	return ok;
}

//! The record registered for `catalog`.main.`table`.
qod::search::IndexRecord RecordOf(const std::string &table, const std::string &catalog = "lake") {
	auto found = g_indexes.Find(catalog, table);
	return found ? *found : qod::search::IndexRecord();
}

//! The index's fields as the build's input names them: ", title, body".
std::string TextColumns(const std::string &table, const std::string &catalog = "lake") {
	std::string out;
	for (auto &field : RecordOf(table, catalog).fields) {
		out += ", " + field.column;
	}
	return out;
}

//! `replica`: where the connection's plans are decided (empty: MintSpec's, which the stand-in decider never calls).
bool Install(Connection &con, const std::string &pool, bool system = false, bool superuser = false,
             const std::string &replica = "") {
	con.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
	qod_test::MintSpec spec;
	spec.principal_id = system ? "system" : superuser ? "u-root" : "u-plain";
	spec.exp = now_ms + 60000;
	spec.pool = pool;
	if (!replica.empty()) {
		spec.replica = replica;
	}
	spec.system = system;
	spec.superuser = superuser;
	auto installed = con.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		Check(false, "tenant prelude for " + pool, FirstLine(installed->GetError()));
		return false;
	}
	return true;
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
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT" +
		           TextColumns(table) + " FROM lake.main." + table + " WHERE filename = " + Quote(path) +
		           " ORDER BY file_row_number), 'lake.main." + table + "', " + Quote(path) + ", " + file[1].ToString() +
		           "::UBIGINT, " + file[2].ToString() + "::UBIGINT)";
		// The build takes its bundle root from the record: `root` for this build.
		auto saved_root = g_bundle_root;
		g_bundle_root = root;
		auto ran = Try(builder, sql, built, error);
		g_bundle_root = saved_root;
		if (!ran || built.size() != 1) {
			Check(false, "build the bundle of " + table + " " + path, FirstLine(error));
			return false;
		}
		g_built.insert(path);
		Say("   built " + built[0][0].ToString() + ": " + built[0][1].ToString() + " docs, " + built[0][2].ToString() +
		    " bytes");
	}
	return true;
}

// ---- the client file system, counting every open ------------------------------------------------

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

//! The .seg files of `table`'s index under `root`, deleted; how many.
int RemoveBundles(const std::string &root, const std::string &table) {
	auto folder = fs::path(root) / ".qod_search" / qod_test::TestIndexId(table);
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

// ---- every match ------------------------------------------------------------------------------

//! `sql`'s rows as Hits (rowid, score, rank when it has one).
std::vector<Hit> MatchHits(const Rows &rows) {
	std::vector<Hit> out;
	for (auto &row : rows) {
		out.push_back(Hit {row[0].GetValue<int64_t>(), row[1].IsNull() ? -1.0f : row[1].GetValue<float>(), 0});
	}
	return out;
}

std::string Matches(const std::string &input, const std::string &query, const std::string &table = kTable,
                    const std::string &columns = kCols, const std::string &extra = "") {
	return "SELECT * FROM qod_search_matches((" + input + "), " + table + ", " + columns + ", " + query + extra + ")";
}

// ---- the knobs ----------------------------------------------------------------------------------

//! Sets `name` to `value` in this process's environment, or removes it (nullptr).
void SetEnvForTest(const char *name, const char *value) {
#ifdef _WIN32
	_putenv_s(name, value ? value : "");
#else
	if (value) {
		setenv(name, value, 1);
	} else {
		unsetenv(name);
	}
#endif
}

//! Registers the search functions on a fresh instance, as a node does at its start, which reads every knob
//! (process-wide); returns what registration wrote to stderr.
std::string RegisterKnobs() {
	std::stringstream captured;
	auto saved = std::cerr.rdbuf(captured.rdbuf());
	{
		DuckDB scratch(nullptr);
		qod::RegisterSearchFunctions(*scratch.instance);
	}
	std::cerr.rdbuf(saved);
	return captured.str();
}

// ---- the node instance and the client's view of a result ------------------------------------------

//! A node instance as the engine test's Run builds one: qod-node's authorization hooks with the stand-in decider
//! (Recorder), the search functions, ducklake and parquet loaded, a DuckLake `lake` with a DuckDB-file catalog at
//! base/meta.ducklake, its data under base/data, and the stand-in record source installed, every record's bundles
//! under that data folder. `setup` is untagged (a superuser off a quack session: the fixture's own statements and
//! the references), `sysc` the system principal (the bundle builds), `client` a tenant principal of pool
//! acme/acme_db/bi.
struct Node {
	fs::path base;
	std::string data;
	std::unique_ptr<DuckDB> db;
	//! qod-node's authorization state (its verdict cache and counters).
	std::shared_ptr<qod::NodeAuthz> authz;
	//! With a replica (OpenNode's `replica`): the HTTP server the node's own decide client asks.
	std::unique_ptr<qod_test::TestHttpServer> replica;
	std::unique_ptr<Connection> setup, sysc, client;
	//! 0: ready; 77: ducklake or parquet cannot load; 1: the fixture failed.
	int status = 1;
};

//! Builds `node` under the temporary folder (a fresh base named after `name` and the process), the lake inlining at
//! most `inlining` rows per insert. With `replica`, the node decides through its own decide client (and so its verdict
//! cache) against an HTTP server answering with `replica`, which every connection's handle names; else through the
//! stand-in Recorder. Sets node.status.
void OpenNode(Node &node, const std::string &name, int64_t inlining = 10,
              std::function<qod_test::HttpAnswer(const qod_test::HttpRequest &)> replica = nullptr) {
	node.base = fs::temp_directory_path() / (name + "-" + std::to_string(::getpid()));
	fs::remove_all(node.base);
	fs::create_directories(node.base / "data");
	node.data = fs::canonical(node.base / "data").string() + "/";
	node.db = std::make_unique<DuckDB>(nullptr);
	node.authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	if (replica) {
		node.replica = std::make_unique<qod_test::TestHttpServer>(replica);
		qod::RegisterNodeAuthz(*node.db->instance, node.authz);
	} else {
		qod::RegisterNodeAuthz(*node.db->instance, node.authz, Recorder);
	}
	qod::RegisterSearchFunctions(*node.db->instance);
	node.setup = std::make_unique<Connection>(*node.db);
	for (std::string ext : {"ducklake", "parquet"}) {
		if (node.setup->Query("INSTALL " + ext)->HasError() || node.setup->Query("LOAD " + ext)->HasError()) {
			Say(name + ": " + ext + " unavailable, skipped");
			node.status = 77;
			return;
		}
	}
	if (!Exec(*node.setup,
	          "ATTACH " + Quote("ducklake:" + (node.base / "meta.ducklake").string()) + " AS lake (DATA_PATH " +
	              Quote(node.data) + ", DATA_INLINING_ROW_LIMIT " + std::to_string(inlining) + ")",
	          "attach lake")) {
		return;
	}
	g_bundle_root = node.data;
	g_indexes.OverrideRoot([] { return g_bundle_root; });
	g_indexes.Install();
	node.sysc = std::make_unique<Connection>(*node.db);
	node.client = std::make_unique<Connection>(*node.db);
	auto url = node.replica ? node.replica->Url() : std::string();
	if (!Install(*node.sysc, "acme/acme_db/bi", true, false, url) || !Install(*node.client, "acme/acme_db/bi", false, false, url)) {
		return;
	}
	node.status = 0;
}

//! Removes the node's folder (the connections and the instance first).
void CloseNode(Node &node) {
	node.client.reset();
	node.sysc.reset();
	node.authz.reset();
	node.replica.reset();
	node.setup.reset();
	node.db.reset();
	std::error_code ec;
	fs::remove_all(node.base, ec);
}

//! What a client reading `sql` as a stream received: the rows that reached it before the statement ended, and
//! whether it ended in an error (with its text).
struct Streamed {
	idx_t rows = 0;
	bool failed = false;
	std::string error;
};

Streamed Stream(Connection &con, const std::string &sql) {
	Streamed out;
	try {
		auto submitted = con.Submit(sql);
		if (submitted->HasError()) {
			// Refused before any chunk (a bind or planning error): the result holds the error itself.
			out.failed = true;
			out.error = submitted->GetError();
			return out;
		}
		QueryResultStream<> streamed(std::move(submitted));
		while (true) {
			auto chunk = streamed.Fetch();
			if (streamed.HasError()) {
				out.failed = true;
				out.error = streamed.GetError();
				break;
			}
			if (!chunk || chunk->size() == 0) {
				break;
			}
			out.rows += chunk->size();
		}
	} catch (std::exception &e) {
		out.failed = true;
		out.error = ErrorData(e).Message();
	}
	return out;
}

//! The end of a suite: the counts, and the exit code (0 only when every check passed and at least one ran).
int Finish(const std::string &suite) {
	Say(suite + ": checks " + std::to_string(g_checks) + ", failed " + std::to_string(g_failures));
	return g_failures == 0 && g_checks > 0 ? 0 : 1;
}

} // namespace
