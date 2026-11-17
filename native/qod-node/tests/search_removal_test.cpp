// QoD Search bundle removal and snapshots (spec 12 Phase 1 item 20, invariants 3 and 4, spec 6.3, 7.5, 9), for
// qod_search and qod_search_matches alike, in both statistics populations.
//
// Fixture: lake.main.rt(id, title, body, f10) indexed on (title, body), written in phases, one snapshot each: two
// data files (bundled), a delete by delete file, inlined rows, an inlined delete and a small delete of file rows,
// a third data file (bundled), a flush of the inlined rows (a file carrying the internal snapshot column, bundled
// from the file itself), a merge of the data files (bundled), a last delete. Every bundle is built from its file
// as the file holds it (positions, rowids, per-row snapshots), on the system principal's connection.
//
// R1: at the current snapshot and at every earlier AT (VERSION => n), each cell (the table population over the
// whole table and over f10 < 5, the allowed population over f10 % 2 = 0) and query, over (title, body) and over
// (body): qod_search equals a fresh single segment built at that snapshot from the population's rows (rowids,
// ranks, score bits); qod_search_matches equals that segment's every match. R2: every bundle removed and the
// fallback's caps raised above the table: every search of R1 again, bit-identical (rowids, ranks, score bits).
// R3: past either cap, search_index_behind, never another answer (the cap at the backlog answers identically,
// one below refuses, no count in the allowed population). R4: AT (TIMESTAMP => t) refused. R5: the liveness cache
// is never used (its hits unchanged) for an AT read, inside an explicit transaction and inside one that modified
// the lake, and is used (the control) for a plain read twice. Exits 77 when ducklake cannot load.

#include "search_fixtures.hpp"

#include <chrono>
#include <thread>

namespace {

const std::string kRt = "lake.main.rt";
const std::vector<std::string> kQueries = {"w1", "w1 w10", "w3 w7 w11", "title:t12 OR w2"};

//! Builds the bundle of every data file of lake.main.rt not built yet, from the file itself: its positions, its
//! rowids (_ducklake_internal_row_id, else row_id_start + position) and its per-row snapshots when it carries
//! _ducklake_internal_snapshot_id (a flushed or merged file).
bool BuildFromFiles(Connection &setup, Connection &sysc, std::set<std::string> &built, std::set<std::string> &with_snapshots) {
	Rows listed;
	std::string error;
	if (!Try(setup,
	         "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files('lake', 'rt')",
	         listed, error)) {
		Check(false, "list rt's files", FirstLine(error));
		return false;
	}
	for (auto &file : listed) {
		auto path = file[0].GetValue<std::string>();
		if (built.count(path)) {
			continue;
		}
		Rows columns;
		if (!Try(setup, "SELECT name FROM parquet_schema(" + Quote(path) + ")", columns, error)) {
			Check(false, "the columns of " + path, FirstLine(error));
			return false;
		}
		bool has_rowid = false, has_snapshot = false;
		for (auto &c : columns) {
			has_rowid = has_rowid || c[0].ToString() == "_ducklake_internal_row_id";
			has_snapshot = has_snapshot || c[0].ToString() == "_ducklake_internal_snapshot_id";
		}
		std::string rowid = "_ducklake_internal_row_id::BIGINT";
		if (!has_rowid) {
			Rows start;
			if (!Try(setup,
			         "SELECT row_id_start FROM __ducklake_metadata_lake.ducklake_data_file WHERE end_snapshot IS NULL AND "
			         "ends_with(" +
			             Quote(path) + ", path)",
			         start, error) ||
			    start.size() != 1) {
				Check(false, "the row_id_start of " + path, FirstLine(error));
				return false;
			}
			rowid = "(" + start[0][0].ToString() + " + file_row_number)::BIGINT";
		}
		auto snapshot = has_snapshot ? std::string("_ducklake_internal_snapshot_id::BIGINT") : std::string("NULL::BIGINT");
		Rows out;
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, " + rowid + ", " + snapshot +
		           TextColumns("rt") + " FROM read_parquet(" + Quote(path) +
		           ", file_row_number = true) ORDER BY file_row_number), " + Quote(kRt) + ", " + Quote(path) + ", " +
		           file[1].ToString() + "::UBIGINT, " + file[2].ToString() + "::UBIGINT)";
		if (!Try(sysc, sql, out, error) || out.size() != 1) {
			Check(false, "build the bundle of " + path, FirstLine(error));
			return false;
		}
		built.insert(path);
		if (has_snapshot) {
			with_snapshots.insert(path);
		}
		Say("   built " + fs::path(path).filename().string() + ": " + out[0][1].ToString() + " docs" +
		    (has_rowid ? ", explicit rowids" : "") + (has_snapshot ? ", per-row snapshots" : ""));
	}
	return true;
}

//! The rows of qod_search_matches as hits ordered by score descending, then rowid (its own order is the stream's).
std::vector<Hit> Ordered(const Rows &rows) {
	auto hits = MatchHits(rows);
	std::sort(hits.begin(), hits.end(), [](const Hit &l, const Hit &r) {
		return l.score != r.score ? l.score > r.score : l.rowid < r.rowid;
	});
	for (size_t i = 0; i < hits.size(); i++) {
		hits[i].rank = int32_t(i + 1);
	}
	return hits;
}

struct Cell {
	std::string name, population, where;
};

const std::vector<Cell> kCells = {
    {"table", "table", ""},
    {"table, f10 < 5", "table", " WHERE f10 < 5"},
    {"allowed, f10 % 2 = 0", "allowed", " WHERE f10 % 2 = 0"},
};

//! One search of R1 and R2: a snapshot, a cell, a query, a column list, a function.
struct Probe {
	std::string at, label, cell, query, columns, fn;
	std::vector<std::string> column_list;
	std::string population, where;
	std::vector<Hit> indexed;
	bool ran = false;
};

std::string ProbeSql(const Probe &p) {
	auto input = "SELECT rowid FROM " + kRt + p.at + p.where;
	if (p.fn == "qod_search") {
		return Search(input, Quote(p.query), Quote(kRt), p.columns);
	}
	return Matches(input, Quote(p.query), Quote(kRt), p.columns, ", with_score := true");
}

std::string ProbeName(const Probe &p) {
	return p.fn + " at " + p.label + ", " + p.cell + ", '" + p.query + "' on " + p.columns;
}

int Run() {
	Node node;
	OpenNode(node, "qod-search-removal");
	if (node.status != 0) {
		return node.status;
	}
	auto &setup = *node.setup;
	auto &sysc = *node.sysc;
	auto &client = *node.client;
	auto insert = [](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.rt SELECT i, 't' || (i % 40), array_to_string(list_transform(range(5 + (i % 23)), "
		       "lambda j: 'w' || ((i * 7 + j * 13) % 30)), ' '), (i % 10)::INTEGER FROM range(" +
		       std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	std::vector<std::pair<std::string, int64_t>> versions;
	auto snapshot = [&](const std::string &what) {
		versions.push_back({what, Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')")});
	};
	std::set<std::string> built, with_snapshots;
	bool ok = Exec(setup, "CREATE TABLE lake.main.rt (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER)", "rt") &&
	          IndexTable(setup, "rt", {"title", "body"}, node.data) && Exec(setup, insert(0, 3000), "rt file A") &&
	          Exec(setup, insert(3000, 5000), "rt file B") && BuildFromFiles(setup, sysc, built, with_snapshots);
	snapshot("two data files");
	ok = ok && Exec(setup, "DELETE FROM lake.main.rt WHERE id % 13 = 0", "a delete by delete file");
	snapshot("a delete file");
	ok = ok && Exec(setup, insert(5000, 5006), "inlined rows");
	snapshot("inlined rows");
	ok = ok && Exec(setup, "DELETE FROM lake.main.rt WHERE id IN (5001, 5004)", "an inlined delete") &&
	     Exec(setup, "DELETE FROM lake.main.rt WHERE id IN (7, 3007)", "a small delete of file rows");
	snapshot("an inlined delete");
	ok = ok && Exec(setup, insert(5006, 6000), "rt file C") && BuildFromFiles(setup, sysc, built, with_snapshots);
	snapshot("a third data file");
	// Liveness at old S across the flush and the merge: each earlier snapshot's answers now, kept for after both.
	// Before the flush (I1), each also reads every data file at its S through its bundle, and the fallback holds exactly
	// the live inlined rows at that S (none at the first two snapshots, 6 then 4 after the inlined delete).
	int fallback_as_expected = 0, fallback_probes = 0, inlined_probes = 0;
	std::string fallback_first;
	auto old_answers = [&](bool check_sources) {
		std::map<std::string, std::vector<Hit>> out;
		for (auto &v : versions) {
			auto at = " AT (VERSION => " + std::to_string(v.second) + ")";
			auto files = check_sources ? Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'rt', "
			                                        "snapshot_version => " + std::to_string(v.second) + ")")
			                           : 0;
			auto inlined = check_sources ? Int(setup, "SELECT count(*) FROM " + kRt + at +
			                                              " WHERE filename LIKE 'ducklake_inlined_data_%'")
			                             : 0;
			for (auto &fn : {std::string("qod_search"), std::string("qod_search_matches")}) {
				auto input = "SELECT rowid FROM " + kRt + at;
				auto sql = fn == "qod_search" ? Search(input, "'w1 w10'", Quote(kRt), "['title', 'body']")
				                              : Matches(input, "'w1 w10'", Quote(kRt), "['title', 'body']",
				                                        ", with_score := true");
				Rows rows;
				std::string error;
				auto cache = qod::search::SegmentCacheCountersForTest();
				qod::ResetLastSearchCallForTest();
				auto ran = Try(client, sql, rows, error);
				auto record = qod::LastSearchCallForTest();
				auto after = qod::search::SegmentCacheCountersForTest();
				if (ran) {
					out[fn + " " + std::to_string(v.second)] = fn == "qod_search" ? Hits(rows) : Ordered(rows);
				}
				if (!check_sources) {
					continue;
				}
				fallback_probes++;
				inlined_probes += inlined > 0;
				auto opened = (after.reads - cache.reads) + (after.hits - cache.hits);
				if (ran && after.refused == cache.refused && after.missing == cache.missing &&
				    opened == static_cast<uint64_t>(files) && record.fallback_docs == static_cast<uint64_t>(inlined) &&
				    (inlined > 0) == (record.fallback_segments > 0)) {
					fallback_as_expected++;
				} else if (fallback_first.empty()) {
					fallback_first = fn + " at VERSION " + std::to_string(v.second) + ": " + std::to_string(opened) +
					                 " bundles served of " + std::to_string(files) + " files, fallback docs " +
					                 std::to_string(record.fallback_docs) + " in " +
					                 std::to_string(record.fallback_segments) + " segments, inlined live " +
					                 std::to_string(inlined) + (ran ? "" : ": " + FirstLine(error));
				}
			}
		}
		return out;
	};
	auto before_flush = old_answers(true);
	Check(fallback_as_expected == fallback_probes && inlined_probes >= 6,
	      "before the flush, the " + std::to_string(fallback_probes) + " searches at the 5 snapshots read every data "
	      "file through its bundle and the fallback for exactly the live inlined rows (" + std::to_string(inlined_probes) +
	      " searches with inlined rows)",
	      fallback_first);
	ok = ok && Exec(setup, insert(6000, 6008), "more inlined rows") &&
	     Exec(setup, "CALL ducklake_flush_inlined_data('lake')", "the flush") &&
	     BuildFromFiles(setup, sysc, built, with_snapshots);
	snapshot("a flush");
	// Two more data files with no deletes (DuckLake merges only those), bundled, then the merge.
	ok = ok && Exec(setup, insert(6008, 6400), "rt file D") && Exec(setup, insert(6400, 6800), "rt file E") &&
	     BuildFromFiles(setup, sysc, built, with_snapshots);
	snapshot("two more data files");
	Rows merged;
	std::string merge_error;
	ok = ok && Try(setup, "CALL ducklake_merge_adjacent_files('lake')", merged, merge_error);
	int64_t created = 0;
	for (auto &row : merged) {
		created += row[3].GetValue<int64_t>();
	}
	auto before_merge_bundles = built.size();
	ok = ok && BuildFromFiles(setup, sysc, built, with_snapshots);
	Check(ok && created >= 1 && built.size() > before_merge_bundles,
	      "the merge wrote " + std::to_string(created) + " data file(s), now bundled", FirstLine(merge_error));
	snapshot("a merge");
	ok = ok && Exec(setup, "DELETE FROM lake.main.rt WHERE id % 17 = 1", "a delete after the merge");
	snapshot("a delete after the merge");
	{
		auto after = old_answers(false);
		bool same = before_flush.size() == 10; // 5 snapshots before the flush, both functions
		std::string first;
		for (auto &entry : before_flush) {
			std::string why;
			if (!after.count(entry.first) || !SameHits(after[entry.first], entry.second, true, why)) {
				same = false;
				first = first.empty() ? entry.first + ": " + why : first;
			}
		}
		Check(same, "the " + std::to_string(before_flush.size()) + " searches at the 5 snapshots before the flush "
		            "answer bit for bit the same after the flush, the merge and a later delete (liveness at old S)",
		      first);
	}
	auto files_now = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'rt')");
	Check(ok && !with_snapshots.empty() && built.size() >= 4,
	      "the fixture: " + std::to_string(versions.size()) + " snapshots, " + std::to_string(built.size()) +
	          " bundles built (" + std::to_string(with_snapshots.size()) + " from files carrying the internal snapshot "
	          "column), " + std::to_string(files_now) + " data files now");
	if (!ok) {
		CloseNode(node);
		return 1;
	}

	// ---- R1: indexed, against a fresh build at each snapshot ----------------------------------------
	std::vector<Probe> probes;
	std::vector<std::pair<std::string, std::string>> ats = {{"", "the current snapshot"}};
	for (auto &v : versions) {
		ats.push_back({" AT (VERSION => " + std::to_string(v.second) + ")",
		               "VERSION " + std::to_string(v.second) + " (after " + v.first + ")"});
	}
	for (auto &at : ats) {
		for (auto &cell : kCells) {
			for (auto &query : kQueries) {
				for (auto &columns : {std::vector<std::string> {"title", "body"}, std::vector<std::string> {"body"}}) {
					if (columns.size() == 1 && query.find("title:") != std::string::npos) {
						continue; // refused by rule 6 (A in the adversarial suite)
					}
					for (auto &fn : {std::string("qod_search"), std::string("qod_search_matches")}) {
						Probe p;
						p.at = at.first;
						p.label = at.second;
						p.cell = cell.name;
						p.population = cell.population;
						p.where = cell.where;
						p.query = query;
						p.column_list = columns;
						p.columns = columns.size() == 2 ? "['title', 'body']" : "['body']";
						p.fn = fn;
						probes.push_back(p);
					}
				}
			}
		}
	}
	// What each snapshot holds, read by SQL: its data files (each bundled), its live inlined rows (the fallback's only
	// source while every data file has a bundle) and its live rows.
	std::map<std::string, int64_t> files_at, inlined_at, live_at;
	for (auto &at : ats) {
		auto version = at.first.empty() ? std::string() : at.first.substr(at.first.find("=>") + 3);
		if (!version.empty()) {
			version = version.substr(0, version.find(')'));
		}
		files_at[at.first] = Int(setup, "SELECT count(*) FROM ducklake_list_files('lake', 'rt'" +
		                                    (version.empty() ? std::string() : ", snapshot_version => " + version) + ")");
		inlined_at[at.first] =
		    Int(setup, "SELECT count(*) FROM " + kRt + at.first + " WHERE filename LIKE 'ducklake_inlined_data_%'");
		live_at[at.first] = Int(setup, "SELECT count(*) FROM " + kRt + at.first);
	}
	int fresh_equal = 0, fresh_total = 0, bundled_as_expected = 0;
	std::string fresh_first, bundled_first;
	for (auto &p : probes) {
		g_population = p.population;
		Rows rows;
		std::string error, rerr, why;
		auto cache = qod::search::SegmentCacheCountersForTest();
		qod::ResetLastSearchCallForTest();
		p.ran = Try(client, ProbeSql(p), rows, error);
		auto record = qod::LastSearchCallForTest();
		auto after = qod::search::SegmentCacheCountersForTest();
		g_population = "table";
		// Bundles read (I1): each data file at S served by its bundle (a read or a cache hit, none missing or refused),
		// and the fallback holding exactly the live inlined rows at S, nothing at all when S holds none.
		auto opened = (after.reads - cache.reads) + (after.hits - cache.hits);
		bool bundled = p.ran && record.called && after.refused == cache.refused && after.missing == cache.missing &&
		               after.missing_hits == cache.missing_hits && after.refused_hits == cache.refused_hits &&
		               opened == static_cast<uint64_t>(files_at[p.at]) &&
		               record.fallback_docs == static_cast<uint64_t>(inlined_at[p.at]) &&
		               (inlined_at[p.at] > 0 || record.fallback_segments == 0);
		if (bundled) {
			bundled_as_expected++;
		} else if (bundled_first.empty()) {
			bundled_first = ProbeName(p) + ": " + std::to_string(opened) + " bundles served of " +
			                std::to_string(files_at[p.at]) + " files, refused " +
			                std::to_string(after.refused - cache.refused) + ", missing " +
			                std::to_string(after.missing - cache.missing) + ", fallback docs " +
			                std::to_string(record.fallback_docs) + " (inlined live " + std::to_string(inlined_at[p.at]) + ")";
		}
		p.indexed = p.fn == "qod_search" ? Hits(rows) : Ordered(rows);
		// The fresh build: the population's rows at that snapshot (the whole table, or the allowed rows), searched in
		// the table population with the input's rows allowed.
		auto population_at = p.population == "allowed" ? p.at + p.where : p.at;
		auto want = Reference(setup, kRt, population_at, "SELECT rowid FROM " + kRt + p.at + p.where, p.query,
		                      p.fn == "qod_search" ? 10 : 10000, rerr, "body", p.column_list);
		fresh_total++;
		if (p.ran && rerr.empty() && SameHits(p.indexed, want, true, why) && !rows.empty() && rows.size() < 10000) {
			fresh_equal++;
		} else if (fresh_first.empty()) {
			fresh_first = ProbeName(p) + ": " + (p.ran ? (rerr.empty() ? why : rerr) : FirstLine(error));
		}
	}
	Check(fresh_equal == fresh_total,
	      "R1 " + std::to_string(fresh_total) + " searches (" + std::to_string(ats.size()) +
	          " snapshots, both functions, both populations) equal a fresh build at their snapshot, score bits included",
	      fresh_first);
	std::string per_snapshot;
	for (auto &at : ats) {
		per_snapshot += std::to_string(files_at[at.first]) + "/" + std::to_string(inlined_at[at.first]) + " ";
	}
	Check(bundled_as_expected == fresh_total,
	      "R1 every search read every data file of its snapshot through its bundle (none missing or refused) and the "
	      "fallback only for the live inlined rows (files/inlined per snapshot: " + per_snapshot + ")",
	      bundled_first);

	// ---- R2: every bundle removed, the caps raised: bit-identical -----------------------------------
	auto removed = RemoveBundles(node.data, kRt);
	qod::search::ClearLivenessCacheForTest();
	qod::search::SetMaxUnindexedRowsForTest(100000000);
	qod::search::SetMaxUnindexedSourcesForTest(4096);
	int same = 0, fallback_used = 0;
	std::string same_first;
	for (auto &p : probes) {
		g_population = p.population;
		Rows rows;
		std::string error, why;
		qod::ResetLastSearchCallForTest();
		auto ran = Try(client, ProbeSql(p), rows, error);
		auto record = qod::LastSearchCallForTest();
		g_population = "table";
		auto got = p.fn == "qod_search" ? Hits(rows) : Ordered(rows);
		fallback_used += record.fallback_segments > 0 && record.num_docs.size() == record.fallback_segments &&
		                 record.fallback_docs == static_cast<uint64_t>(live_at[p.at]);
		if (ran && p.ran && SameHits(got, p.indexed, true, why)) {
			same++;
		} else if (same_first.empty()) {
			same_first = ProbeName(p) + ": " + (ran ? why : FirstLine(error));
		}
	}
	Check(removed == static_cast<int>(built.size()), "R2 every bundle removed",
	      std::to_string(removed) + " of " + std::to_string(built.size()));
	Check(same == static_cast<int>(probes.size()) && fallback_used == static_cast<int>(probes.size()),
	      "R2 with every bundle removed and the caps raised, the " + std::to_string(probes.size()) +
	          " searches of R1 through the fallback alone: identical rowids, ranks and score bits",
	      same_first + " (" + std::to_string(fallback_used) + " through the fallback alone, over every live row at S)");

	// ---- R3: past a cap, search_index_behind, never another answer ----------------------------------
	for (auto &fn : {std::string("qod_search"), std::string("qod_search_matches")}) {
		Probe p = probes.front();
		for (auto &q : probes) {
			if (q.fn == fn && q.at.empty() && q.population == "table" && q.where.empty()) {
				p = q;
				break;
			}
		}
		qod::search::SetMaxUnindexedRowsForTest(0);
		Rows rows;
		std::string error;
		auto ran = Try(client, ProbeSql(p), rows, error);
		const std::string marker = "lake.main.rt has ";
		auto at = error.find(marker);
		int64_t backlog = at == std::string::npos ? -1 : std::atoll(error.c_str() + at + marker.size());
		Check(!ran && rows.empty() && error.find("QoD: search_index_behind") != std::string::npos && backlog > 0,
		      fn + " R3 a rows cap of 0: search_index_behind naming the backlog (" + std::to_string(backlog) + "), no row",
		      ran ? "it ran" : FirstLine(error));
		if (backlog > 0) {
			std::string why;
			qod::search::SetMaxUnindexedRowsForTest(static_cast<uint64_t>(backlog));
			auto at_cap = Try(client, ProbeSql(p), rows, error);
			auto got = fn == "qod_search" ? Hits(rows) : Ordered(rows);
			Check(at_cap && SameHits(got, p.indexed, true, why),
			      fn + " R3 a rows cap equal to the backlog: the same answer, bit for bit", at_cap ? why : FirstLine(error));
			qod::search::SetMaxUnindexedRowsForTest(static_cast<uint64_t>(backlog - 1));
			auto below = Try(client, ProbeSql(p), rows, error);
			Check(!below && rows.empty() && error.find("QoD: search_index_behind") != std::string::npos,
			      fn + " R3 one row below the backlog: search_index_behind, no row", below ? "it ran" : FirstLine(error));
			g_population = "allowed";
			Probe allowed = p;
			allowed.where = " WHERE f10 % 2 = 0";
			auto ran_allowed = Try(client, ProbeSql(allowed), rows, error);
			g_population = "table";
			auto tail = error.substr(std::min(error.size(), error.find("search_index_behind")));
			auto cap_text = "(cap " + std::to_string(backlog - 1) + ")";
			if (tail.find(cap_text) != std::string::npos) {
				tail.erase(tail.find(cap_text), cap_text.size());
			}
			Check(!ran_allowed && rows.empty() && error.find("QoD: search_index_behind") != std::string::npos &&
			          tail.find_first_of("0123456789") == std::string::npos,
			      fn + " R3 the same cap in the allowed population: search_index_behind with no count, no row",
			      ran_allowed ? "it ran" : FirstLine(error));
		}
		qod::search::SetMaxUnindexedRowsForTest(100000000);
		qod::search::SetMaxUnindexedSourcesForTest(1);
		auto sources = Try(client, ProbeSql(p), rows, error);
		Check(!sources && rows.empty() && error.find("QoD: search_index_behind") != std::string::npos &&
		          error.find("unindexed sources (cap 1)") != std::string::npos,
		      fn + " R3 a sources cap of 1: search_index_behind, no row", sources ? "it ran" : FirstLine(error));
		qod::search::SetMaxUnindexedSourcesForTest(4096);
	}

	// ---- R4: AT (TIMESTAMP => t) -----------------------------------------------------------------------
	{
		Rows times;
		std::string error;
		Try(setup,
		    "SELECT snapshot_time FROM ducklake_snapshots('lake') WHERE snapshot_id = " + std::to_string(versions.front().second),
		    times, error);
		for (auto &fn : {std::string("qod_search"), std::string("qod_search_matches")}) {
			Probe p = probes.front();
			p.fn = fn;
			p.at = times.empty() ? "" : " AT (TIMESTAMP => " + Quote(times[0][0].ToString()) + "::TIMESTAMPTZ)";
			Rows rows;
			auto ran = Try(client, ProbeSql(p), rows, error);
			Check(!times.empty() && !ran && rows.empty() &&
			          error.find("QoD: search_at_timestamp_unsupported") != std::string::npos,
			      fn + " R4 AT (TIMESTAMP => t): search_at_timestamp_unsupported, no row", ran ? "it ran" : FirstLine(error));
		}
	}

	// ---- R5: the liveness cache, never used for an AT read or inside a transaction ----------------------
	{
		auto hits_over = [&](const std::vector<std::string> &statements, std::string &error) {
			auto before = qod::search::LivenessCacheCountersForTest();
			bool all = true;
			for (auto &sql : statements) {
				Rows rows;
				all = Try(client, sql, rows, error) && all;
			}
			auto after = qod::search::LivenessCacheCountersForTest();
			return std::make_tuple(all, after.hits - before.hits, after.bypassed - before.bypassed);
		};
		for (auto &fn : {std::string("qod_search"), std::string("qod_search_matches")}) {
			Probe p = probes.front();
			p.fn = fn;
			p.population = "table";
			p.where = "";
			p.at = "";
			auto plain = ProbeSql(p);
			Probe old = p;
			old.at = " AT (VERSION => " + std::to_string(versions[2].second) + ")";
			auto at_read = ProbeSql(old);
			std::string error;
			qod::search::ClearLivenessCacheForTest();
			auto control = hits_over({plain, plain}, error);
			Check(std::get<0>(control) && std::get<1>(control) >= 1,
			      fn + " R5 the control: a plain search twice, the second from the liveness cache",
			      std::to_string(std::get<1>(control)) + " hits " + FirstLine(error));
			auto at_twice = hits_over({at_read, at_read}, error);
			Check(std::get<0>(at_twice) && std::get<1>(at_twice) == 0 && std::get<2>(at_twice) >= 2,
			      fn + " R5 an AT read twice: the liveness cache bypassed, hits unchanged",
			      std::to_string(std::get<1>(at_twice)) + " hits, " + std::to_string(std::get<2>(at_twice)) + " bypassed " +
			          FirstLine(error));
			Exec(client, "BEGIN TRANSACTION", "begin");
			auto in_tx = hits_over({plain, plain}, error);
			Exec(client, "COMMIT", "commit");
			Check(std::get<0>(in_tx) && std::get<1>(in_tx) == 0 && std::get<2>(in_tx) >= 2,
			      fn + " R5 inside an explicit transaction: the liveness cache bypassed, hits unchanged",
			      std::to_string(std::get<1>(in_tx)) + " hits, " + std::to_string(std::get<2>(in_tx)) + " bypassed " +
			          FirstLine(error));
			Exec(client, "BEGIN TRANSACTION", "begin");
			Exec(client, "INSERT INTO lake.main.rt VALUES (90000, 't1', 'w1 w2', 0)", "the transaction's write");
			auto modified = hits_over({plain, plain}, error);
			Exec(client, "ROLLBACK", "rollback");
			Check(std::get<0>(modified) && std::get<1>(modified) == 0 && std::get<2>(modified) >= 2,
			      fn + " R5 inside a transaction that wrote to the lake: the liveness cache bypassed, hits unchanged",
			      std::to_string(std::get<1>(modified)) + " hits, " + std::to_string(std::get<2>(modified)) + " bypassed " +
			          FirstLine(error));
		}
	}
	CloseNode(node);
	return Finish("search_removal_test");
}

} // namespace

int main() {
	return Run();
}
