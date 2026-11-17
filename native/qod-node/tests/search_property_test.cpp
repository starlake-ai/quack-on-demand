// QoD Search property test (spec 12 Phase 1 item 18, invariants 2, 5 and 7): qod_search and qod_search_matches in
// qod-node over random DuckLake tables, against a reference that shares no code with the search path.
//
// Seeded: QOD_SEARCH_PROPERTY_SEED (default 20261117) and QOD_SEARCH_PROPERTY_CASES (default 200). Each case draws
// a table p<n>(id, a, b, f) with two text columns: 1 to 6 data files, then bundles for a random subset of them
// (built with qod_search_build_segment before any delete), then deletes by delete file and of inlined rows, more
// inlined rows and possibly an unbundled data file (both through the fallback). Then one search: a random row
// policy written as the input's WHERE with the population `allowed` (the node half: the rewriter's own output is
// Task 18's), or the table population with random filters only; 1 to 3 terms over 1 or 2 of the columns, in a
// random order; both functions (qod_search with a random k, qod_search_matches with and without scores).
//
// The reference is independent of the code under test: the governed input's rowids by a plain SQL scan with the
// same predicate (a superuser's connection), every row's texts by a superuser's plain SELECT, the tokens by the
// library's analyzer called through qs_test_tokenize (the library's test-hooks feature, the test programs only),
// and BM25 in f64 written here: over the population the verdict set (every live row, or the input's rows), N and
// df counted over it, avgdl from exact token counts, each doc's dl its fieldnorm code (Tantivy's 256 codes:
// identity to 40, then 8 codes per doubling step, rebuilt here from that rule), k1 = 1.2, b = 0.75. Asserted per
// case: every returned rowid is one of the governed input's (invariant 2); qod_search's ranks are 1..n and its
// rowids are the reference's top k, ties pooled at invariant 7's accuracy bound; every score within that bound
// (each matched term's contribution 1e-6 (1 + k1) max(1, idf)); qod_search_matches returns exactly the
// reference's matching set, with scores within the bound and bit-identical to qod_search's for the same rows.
// Exits 77 when ducklake cannot load or when the test programs do not link the test-hooks build.

#include "search_fixtures.hpp"

#include <cstdint>
#include <random>

namespace {

#ifdef QOD_SEARCH_TEST_HOOKS

constexpr double kK1 = 1.2;
constexpr double kB = 0.75;

//! Tantivy's fieldnorm codes, rebuilt from their rule: 0 to 40 as themselves, then eight codes per step, the step
//! doubling from 2 (42, 44, ..., 56, 60, 64, ...), 256 codes in all.
std::vector<uint32_t> FieldnormCodes() {
	std::vector<uint32_t> codes;
	for (uint32_t v = 0; v <= 40; v++) {
		codes.push_back(v);
	}
	uint64_t step = 2;
	while (codes.size() < 256) {
		for (int i = 0; i < 8 && codes.size() < 256; i++) {
			codes.push_back(static_cast<uint32_t>(codes.back() + step));
		}
		step *= 2;
	}
	return codes;
}

//! A doc's dl as Tantivy keeps it: the largest code at or below its exact token count.
uint32_t Quantized(uint64_t count) {
	static const auto codes = FieldnormCodes();
	uint32_t best = 0;
	for (auto c : codes) {
		if (c <= count) {
			best = c;
		}
	}
	return best;
}

//! The library's analyzer over `text`.
std::vector<std::string> Tokens(const std::string &text, std::string &error) {
	char *err = nullptr;
	auto out = qs_test_tokenize(text.data(), text.size(), &err);
	if (!out) {
		error = Take(err);
		return {};
	}
	std::string joined = out;
	qs_string_free(out);
	std::vector<std::string> tokens;
	size_t at = 0;
	while (at < joined.size()) {
		auto end = joined.find('\n', at);
		tokens.push_back(joined.substr(at, end - at));
		at = end + 1;
	}
	return tokens;
}

//! A deterministic draw: every value from the one 64-bit generator, reduced by modulo (no distribution object,
//! whose output the standard leaves to each library).
struct Draw {
	std::mt19937_64 gen;
	explicit Draw(uint64_t seed) : gen(seed) {
	}
	uint64_t Below(uint64_t n) {
		return n == 0 ? 0 : gen() % n;
	}
	int64_t Between(int64_t lo, int64_t hi) {
		return lo + static_cast<int64_t>(Below(static_cast<uint64_t>(hi - lo + 1)));
	}
	bool Chance(int percent) {
		return Below(100) < static_cast<uint64_t>(percent);
	}
	double Unit() {
		return static_cast<double>(gen() >> 11) / 9007199254740992.0;
	}
};

//! The corpus' words: w0 to w29 drawn with a skew (low indexes common), and a few other spellings the analyzer
//! must fold or split.
const std::vector<std::string> kOddWords = {"W3", "w4,w5", "Café", "CAFÉ", "w7.w8", "(w1)", "x-w2", "W10"};

std::string Word(Draw &d) {
	if (d.Chance(4)) {
		return kOddWords[d.Below(kOddWords.size())];
	}
	auto u = d.Unit();
	return "w" + std::to_string(static_cast<int>(std::floor(u * u * 30)));
}

//! A cell: NULL (5%), empty (3%), else 1 to 50 words (one in ten up to 150, past the fieldnorm codes' identity).
std::string CellSql(Draw &d) {
	if (d.Chance(5)) {
		return "NULL";
	}
	if (d.Chance(3)) {
		return "''";
	}
	auto n = d.Chance(10) ? d.Between(40, 150) : d.Between(1, 50);
	std::string text;
	for (int64_t i = 0; i < n; i++) {
		text += (i ? " " : "") + Word(d);
	}
	std::string quoted = "'";
	for (auto c : text) {
		quoted += c == '\'' ? std::string("''") : std::string(1, c);
	}
	return quoted + "'";
}

//! One INSERT of `rows` rows from id `from`.
std::string InsertSql(const std::string &table, Draw &d, int64_t from, int64_t rows) {
	std::string sql = "INSERT INTO " + table + " VALUES ";
	for (int64_t i = 0; i < rows; i++) {
		sql += (i ? ", (" : "(") + std::to_string(from + i) + ", " + CellSql(d) + ", " + CellSql(d) + ", " +
		       std::to_string(d.Below(12)) + ")";
	}
	return sql;
}

//! A filter on the table's own columns (or on lake.main.aux).
std::string FilterSql(Draw &d) {
	switch (d.Below(7)) {
	case 0:
		return "id % " + std::to_string(d.Between(2, 5)) + " = " + std::to_string(d.Below(2));
	case 1:
		return "f < " + std::to_string(d.Between(2, 11));
	case 2:
		return "a IS NOT NULL";
	case 3:
		return "id BETWEEN " + std::to_string(d.Below(200)) + " AND " + std::to_string(d.Between(200, 900));
	case 4:
		return "id IN (SELECT id FROM lake.main.aux)";
	case 5:
		return "b LIKE '%w1%'";
	default:
		return "f <> " + std::to_string(d.Below(12));
	}
}

//! A row policy as the rewriter would place it, a predicate of the table's own columns.
std::string PolicySql(Draw &d) {
	switch (d.Below(4)) {
	case 0:
		return "(f % 3 = " + std::to_string(d.Below(3)) + ")";
	case 1:
		return "(f IN (" + std::to_string(d.Below(12)) + ", " + std::to_string(d.Below(12)) + ", " +
		       std::to_string(d.Below(12)) + "))";
	case 2:
		return "(id % 2 = " + std::to_string(d.Below(2)) + ")";
	default:
		return "(f >= " + std::to_string(d.Below(8)) + ")";
	}
}

//! One case's per-assertion tally: how many cases held, the first failure in full.
struct Tally {
	std::map<std::string, std::pair<int, int>> counts;
	std::map<std::string, std::string> first;
	void Add(const std::string &what, bool ok, const std::string &detail) {
		auto &c = counts[what];
		(ok ? c.first : c.second)++;
		if (!ok && !first.count(what)) {
			first[what] = detail;
		}
	}
};

//! A doc as the reference reads it: per call column, its exact token count, its fieldnorm, its term counts.
struct RefField {
	uint64_t length = 0;
	uint32_t dl = 0;
	std::map<std::string, uint32_t> tf;
};

struct RefScore {
	double score = 0;
	double bound = 0;
	bool matched = false;
};

//! The case's whole state, kept for its failure report.
struct Case {
	int n = 0;
	std::string table, input, query, columns, population, call;
	uint32_t k = 10;
};

std::string Describe(const Case &c) {
	return "case " + std::to_string(c.n) + " (" + c.population + ", k " + std::to_string(c.k) + ", " + c.columns +
	       ", '" + c.query + "'): " + c.call;
}

//! The rows `sql` returns, rowid first: the texts of the call's columns after it.
bool ReadRows(Connection &setup, const std::string &sql, Rows &rows, std::string &error) {
	return Try(setup, sql, rows, error);
}

bool RunCase(Node &node, Draw &d, int n, Tally &tally) {
	auto &setup = *node.setup;
	Case c;
	c.n = n;
	auto name = "p" + std::to_string(n);
	c.table = "lake.main." + name;
	if (!Exec(setup, "CREATE TABLE " + c.table + " (id BIGINT, a VARCHAR, b VARCHAR, f INTEGER)", "case table")) {
		return false;
	}
	// 1 to 6 data files of 11 to 150 rows each (above the inlining limit of 10, so each insert writes a file).
	int64_t next = 0;
	auto files = d.Between(1, 6);
	for (int64_t f = 0; f < files; f++) {
		auto rows = d.Between(11, 150);
		if (!Exec(setup, InsertSql(c.table, d, next, rows), "case file")) {
			return false;
		}
		next += rows;
	}
	if (!IndexTable(setup, name, {"a", "b"}, node.data)) {
		return false;
	}
	// Bundles for a random subset of the files (each with a chance of one half), built before any delete.
	Rows listed;
	std::string error;
	if (!Try(setup,
	         "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files('lake', " +
	             Quote(name) + ")",
	         listed, error)) {
		Check(false, "case " + std::to_string(n) + ": list the files", FirstLine(error));
		return false;
	}
	int bundled = 0;
	std::vector<std::string> bundled_paths;
	for (auto &file : listed) {
		if (!d.Chance(50)) {
			continue;
		}
		auto path = file[0].GetValue<std::string>();
		Rows built;
		auto sql = "SELECT * FROM qod_search_build_segment((SELECT file_row_number, rowid, NULL::BIGINT" +
		           TextColumns(name) + " FROM " + c.table + " WHERE filename = " + Quote(path) +
		           " ORDER BY file_row_number), " + Quote(c.table) + ", " + Quote(path) + ", " + file[1].ToString() +
		           "::UBIGINT, " + file[2].ToString() + "::UBIGINT)";
		if (!Try(*node.sysc, sql, built, error) || built.size() != 1) {
			Check(false, "case " + std::to_string(n) + ": build a bundle", FirstLine(error));
			return false;
		}
		bundled++;
		bundled_paths.push_back(path);
	}
	// Inlined rows, then deletes (by delete file on the data files, and of inlined rows), more inlined rows, and
	// at times one more data file with no bundle.
	if (d.Chance(60)) {
		auto rows = d.Between(1, 9);
		Exec(setup, InsertSql(c.table, d, next, rows), "case inlined rows");
		next += rows;
	}
	for (auto deletes = d.Below(3); deletes > 0; deletes--) {
		Exec(setup,
		     "DELETE FROM " + c.table + " WHERE id % " + std::to_string(d.Between(3, 9)) + " = " +
		         std::to_string(d.Below(3)),
		     "case delete");
	}
	if (d.Chance(40)) {
		auto rows = d.Between(1, 9);
		Exec(setup, InsertSql(c.table, d, next, rows), "case inlined rows after the deletes");
		next += rows;
	}
	if (d.Chance(25)) {
		auto rows = d.Between(11, 60);
		Exec(setup, InsertSql(c.table, d, next, rows), "case unbundled file");
		next += rows;
	}
	if (d.Chance(20)) {
		Exec(setup, "DELETE FROM " + c.table + " WHERE id = " + std::to_string(d.Below(static_cast<uint64_t>(next))),
		     "case last delete");
	}

	// The call: columns, query, population, input, function.
	std::vector<std::string> columns;
	switch (d.Below(4)) {
	case 0:
		columns = {"a"};
		break;
	case 1:
		columns = {"b"};
		break;
	case 2:
		columns = {"a", "b"};
		break;
	default:
		columns = {"b", "a"};
		break;
	}
	c.columns = "[";
	for (size_t i = 0; i < columns.size(); i++) {
		c.columns += (i ? ", '" : "'") + columns[i] + "'";
	}
	c.columns += "]";
	std::vector<std::string> terms;
	auto n_terms = d.Between(1, 3);
	while (static_cast<int64_t>(terms.size()) < n_terms) {
		std::string term;
		if (d.Chance(8)) {
			term = "zz" + std::to_string(d.Below(5)); // in no doc
		} else if (d.Chance(6)) {
			term = d.Chance(50) ? "Café" : "W" + std::to_string(d.Below(30));
		} else {
			auto u = d.Unit();
			term = "w" + std::to_string(static_cast<int>(std::floor(u * u * 30)));
		}
		auto folded = Tokens(term, error);
		bool dup = folded.size() != 1;
		for (auto &t : terms) {
			auto other = Tokens(t, error);
			dup = dup || (other.size() == 1 && other[0] == folded[0]);
		}
		if (!dup) {
			terms.push_back(term);
		}
	}
	for (size_t i = 0; i < terms.size(); i++) {
		c.query += (i ? " " : "") + terms[i];
	}
	bool allowed = d.Chance(50);
	c.population = allowed ? "allowed" : "table";
	std::vector<std::string> where;
	if (allowed) {
		where.push_back(PolicySql(d));
	}
	for (auto filters = d.Below(3); filters > 0; filters--) {
		where.push_back(FilterSql(d));
	}
	std::string predicate;
	for (size_t i = 0; i < where.size(); i++) {
		predicate += (i ? " AND " : "") + where[i];
	}
	c.input = "SELECT rowid FROM " + c.table + (predicate.empty() ? "" : " WHERE " + predicate);
	c.k = d.Chance(30) ? 10 : static_cast<uint32_t>(d.Between(1, 20));
	auto named_k = c.k == 10 && d.Chance(50) ? std::string() : ", k := " + std::to_string(c.k);
	auto with_score = d.Chance(70);

	// The reference: the input's rowids, every live row's texts, the population.
	Rows input_rows, live_rows;
	std::string select = "SELECT rowid";
	for (auto &col : columns) {
		select += ", " + col;
	}
	if (!ReadRows(setup, c.input, input_rows, error) ||
	    !ReadRows(setup, select + " FROM " + c.table + " ORDER BY rowid", live_rows, error)) {
		Check(false, "case " + std::to_string(n) + ": the reference's reads", FirstLine(error));
		return false;
	}
	std::set<int64_t> governed;
	for (auto &row : input_rows) {
		governed.insert(row[0].GetValue<int64_t>());
	}
	std::map<int64_t, std::vector<RefField>> docs;
	for (auto &row : live_rows) {
		auto &fields = docs[row[0].GetValue<int64_t>()];
		for (size_t col = 0; col < columns.size(); col++) {
			RefField field;
			if (!row[col + 1].IsNull()) {
				for (auto &token : Tokens(row[col + 1].GetValue<std::string>(), error)) {
					field.tf[token]++;
					field.length++;
				}
			}
			field.dl = Quantized(field.length);
			fields.push_back(std::move(field));
		}
	}
	if (!error.empty()) {
		Check(false, "case " + std::to_string(n) + ": tokenize", FirstLine(error));
		return false;
	}
	// Statistics over the population: every live row, or the live rows of the input.
	double big_n = 0;
	std::vector<double> total(columns.size(), 0);
	std::vector<std::map<std::string, double>> df(columns.size());
	std::vector<std::string> query_tokens;
	for (auto &t : terms) {
		query_tokens.push_back(Tokens(t, error)[0]);
	}
	for (auto &doc : docs) {
		if (allowed && !governed.count(doc.first)) {
			continue;
		}
		big_n++;
		for (size_t col = 0; col < columns.size(); col++) {
			total[col] += static_cast<double>(doc.second[col].length);
			for (auto &t : query_tokens) {
				df[col][t] += doc.second[col].tf.count(t) ? 1 : 0;
			}
		}
	}
	std::map<int64_t, RefScore> scores;
	for (auto rowid : governed) {
		auto it = docs.find(rowid);
		if (it == docs.end()) {
			continue; // not live: the input cannot return it
		}
		RefScore s;
		for (auto &t : query_tokens) {
			for (size_t col = 0; col < columns.size(); col++) {
				auto tf_it = it->second[col].tf.find(t);
				if (tf_it == it->second[col].tf.end()) {
					continue;
				}
				double tf = tf_it->second;
				double dfv = df[col][t];
				double idf = std::log(1.0 + (big_n - dfv + 0.5) / (dfv + 0.5));
				double avgdl = total[col] / big_n;
				double norm = kK1 * (1.0 - kB + kB * double(it->second[col].dl) / avgdl);
				s.score += idf * (1.0 + kK1) * (tf / (tf + norm));
				s.bound += 1e-6 * (1.0 + kK1) * std::max(1.0, idf);
				s.matched = true;
			}
		}
		if (s.matched) {
			scores[rowid] = s;
		}
	}
	std::vector<std::pair<int64_t, RefScore>> ranked(scores.begin(), scores.end());
	std::sort(ranked.begin(), ranked.end(), [](const std::pair<int64_t, RefScore> &l, const std::pair<int64_t, RefScore> &r) {
		return l.second.score != r.second.score ? l.second.score > r.second.score : l.first < r.first;
	});

	// qod_search.
	g_population = c.population;
	c.call = "SELECT * FROM qod_search((" + c.input + "), " + Quote(c.table) + ", " + c.columns + ", " + Quote(c.query) +
	         named_k + ")";
	Rows rows;
	// The fallback's share (review I1): the live rows outside the bundled files (unbundled files, inlined rows), read by
	// SQL, are exactly the docs the fallback indexes; the bundled files are read through their bundles. An input that
	// returns no row has nothing to return: the node may then skip the reads (fallback docs 0) or make them all, and
	// the tally counts those cases apart.
	std::string not_bundled;
	for (auto &path : bundled_paths) {
		not_bundled += (not_bundled.empty() ? "" : ", ") + Quote(path);
	}
	auto outside = Int(setup, "SELECT count(*) FROM " + c.table +
	                              (not_bundled.empty() ? std::string() : " WHERE filename NOT IN (" + not_bundled + ")"));
	// The bundled files holding a live row at S: each is served by its bundle (a read or a cache hit).
	auto live_bundled = not_bundled.empty() ? int64_t(0)
	                                        : Int(setup, "SELECT count(DISTINCT filename) FROM " + c.table +
	                                                         " WHERE filename IN (" + not_bundled + ")");
	auto fallback_ok = [&](uint64_t docs) {
		return docs == static_cast<uint64_t>(outside) || (governed.empty() && docs == 0);
	};
	// The bundles served: with the segment cache cleared, the distinct bundle files the client's file system opened.
	qod::search::ClearSegmentCacheForTest();
	auto cache = qod::search::SegmentCacheCountersForTest();
	{
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		g_fs_opens.clear();
		g_fs_counting = true;
	}
	qod::ResetLastSearchCallForTest();
	auto ran = Try(*node.client, c.call, rows, error);
	auto record = qod::LastSearchCallForTest();
	auto cache_after = qod::search::SegmentCacheCountersForTest();
	uint64_t served = 0;
	{
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		g_fs_counting = false;
		for (auto &open : g_fs_opens) {
			served += open.first.find(".qod_search") != std::string::npos && fs::exists(open.first) ? 1 : 0;
		}
	}
	auto served_ok = served == static_cast<uint64_t>(live_bundled) || (governed.empty() && served == 0);
	tally.Add(governed.empty() ? "an input returning no row: no read at all, or every bundled file with a live row served "
	                             "and the fallback's whole share"
	                           : "qod_search: every bundled file with a live row served by its bundle (none refused), the "
	                             "fallback exactly the live rows outside them",
	          ran && fallback_ok(record.fallback_docs) && cache_after.refused == cache.refused && served_ok,
	          Describe(c) + ": fallback docs " + std::to_string(record.fallback_docs) + ", live rows outside the bundles " +
	              std::to_string(outside) + ", bundles served " + std::to_string(served) + " of " +
	              std::to_string(live_bundled) + " bundled files with a live row");
	auto hits = ran ? Hits(rows) : std::vector<Hit>();
	tally.Add("qod_search runs", ran, Describe(c) + ": " + FirstLine(error));
	if (ran) {
		bool governed_only = true, ranks = true, within = true, order = true, complete = true;
		std::string why;
		std::set<int64_t> seen;
		for (size_t i = 0; i < hits.size(); i++) {
			auto &h = hits[i];
			governed_only = governed_only && governed.count(h.rowid) && seen.insert(h.rowid).second;
			ranks = ranks && h.rank == int32_t(i + 1);
			auto s = scores.find(h.rowid);
			if (s == scores.end()) {
				within = false;
				why = why.empty() ? "rowid " + std::to_string(h.rowid) + " matches nothing in the reference" : why;
				continue;
			}
			if (std::fabs(double(h.score) - s->second.score) > s->second.bound) {
				within = false;
				why = why.empty() ? "rowid " + std::to_string(h.rowid) + " scored " + std::to_string(h.score) + " vs " +
				                        std::to_string(s->second.score)
				                  : why;
			}
			if (i + 1 < hits.size()) {
				auto &next_hit = hits[i + 1];
				// The node's own order: score descending, ties by rowid ascending.
				order = order && (h.score > next_hit.score || (h.score == next_hit.score && h.rowid < next_hit.rowid));
				auto t = scores.find(next_hit.rowid);
				if (t != scores.end() && t->second.score > s->second.score + s->second.bound + t->second.bound) {
					order = false;
					why = why.empty() ? "rank " + std::to_string(i + 2) + " outscores rank " + std::to_string(i + 1) +
					                        " in the reference"
					                  : why;
				}
			}
		}
		auto want = std::min<size_t>(c.k, ranked.size());
		complete = hits.size() == want;
		if (complete && !hits.empty() && hits.size() < ranked.size()) {
			auto last = scores.find(hits.back().rowid);
			for (auto &r : ranked) {
				if (seen.count(r.first) || last == scores.end()) {
					continue;
				}
				if (r.second.score > last->second.score + last->second.bound + r.second.bound) {
					complete = false;
					why = why.empty() ? "rowid " + std::to_string(r.first) + " left out above the last hit" : why;
					break;
				}
			}
		}
		tally.Add("invariant 2: every qod_search rowid is one of the governed input's, once", governed_only,
		          Describe(c));
		tally.Add("qod_search ranks are 1..n", ranks, Describe(c));
		tally.Add("invariant 7: every qod_search score within the accuracy bound of the f64 reference", within,
		          Describe(c) + ": " + why);
		tally.Add("qod_search's order is the reference's, ties pooled at the bound (score desc, rowid asc)", order,
		          Describe(c) + ": " + why);
		tally.Add("qod_search returns min(k, matches) rows, none left out above its last", complete,
		          Describe(c) + ": got " + std::to_string(hits.size()) + ", want " + std::to_string(want) + " " + why);
		// Determinism: the same statement again, bit for bit.
		Rows again;
		std::string why2;
		auto ran2 = Try(*node.client, c.call, again, error);
		tally.Add("qod_search twice: bit-identical", ran2 && SameHits(Hits(again), hits, true, why2), Describe(c) + why2);
	}

	// qod_search_matches.
	c.call = "SELECT * FROM qod_search_matches((" + c.input + "), " + Quote(c.table) + ", " + c.columns + ", " +
	         Quote(c.query) + (with_score ? ", with_score := true" : "") + ")";
	Rows matched;
	auto matches_call = c.call;
	qod::ResetLastSearchCallForTest();
	auto ran_m = Try(*node.client, c.call, matched, error);
	auto record_m = qod::LastSearchCallForTest();
	tally.Add(governed.empty() ? "an input returning no row: no fallback doc, or its whole share (qod_search_matches)"
	                          : "qod_search_matches: the fallback indexes exactly the live rows outside the bundled files",
	          ran_m && fallback_ok(record_m.fallback_docs),
	          Describe(c) + ": fallback docs " + std::to_string(record_m.fallback_docs) + ", live rows outside " +
	              std::to_string(outside));
	tally.Add("qod_search_matches runs", ran_m, Describe(c) + ": " + FirstLine(error));
	if (ran_m) {
		std::set<int64_t> got;
		bool governed_only = true, within = true, null_scores = true, same_bits = true;
		std::map<int64_t, float> by_search;
		for (auto &h : hits) {
			by_search[h.rowid] = h.score;
		}
		for (auto &row : matched) {
			auto rowid = row[0].GetValue<int64_t>();
			governed_only = governed_only && governed.count(rowid) && got.insert(rowid).second;
			if (!with_score) {
				null_scores = null_scores && row[1].IsNull();
				continue;
			}
			auto s = scores.find(rowid);
			auto score = row[1].IsNull() ? std::nanf("") : row[1].GetValue<float>();
			within = within && s != scores.end() && std::fabs(double(score) - s->second.score) <= s->second.bound;
			auto b = by_search.find(rowid);
			same_bits = same_bits && (b == by_search.end() || b->second == score);
		}
		std::set<int64_t> want;
		for (auto &s : scores) {
			want.insert(s.first);
		}
		tally.Add("invariant 2: every qod_search_matches rowid is one of the governed input's, once", governed_only,
		          Describe(c));
		tally.Add("qod_search_matches returns exactly the reference's matching set", got == want,
		          Describe(c) + ": got " + std::to_string(got.size()) + ", want " + std::to_string(want.size()));
		if (with_score) {
			tally.Add("qod_search_matches scores within the bound, bit-identical to qod_search's", within && same_bits,
			          Describe(c));
		} else {
			tally.Add("qod_search_matches without scores: NULL scores", null_scores, Describe(c));
		}
	}
	// Invariant 5, in the allowed population: hidden rows removed (half of them, by a delete) and added (new rows
	// the input does not return) change no result and no score, bit for bit.
	if (allowed && ran) {
		auto search = "SELECT * FROM qod_search((" + c.input + "), " + Quote(c.table) + ", " + c.columns + ", " +
		              Quote(c.query) + named_k + ")";
		bool ok = Exec(setup,
		               "DELETE FROM " + c.table + " WHERE rowid NOT IN (" + c.input + ") AND id % 2 = " +
		                   std::to_string(d.Below(2)),
		               "case hidden rows removed") &&
		          Exec(setup, InsertSql(c.table, d, 1000000, d.Between(1, 30)), "case hidden candidates") &&
		          Exec(setup, "DELETE FROM " + c.table + " WHERE id >= 1000000 AND rowid IN (" + c.input + ")",
		               "case candidates the input returns");
		Rows input_after;
		ok = ok && ReadRows(setup, c.input, input_after, error);
		std::set<int64_t> governed_after;
		for (auto &row : input_after) {
			governed_after.insert(row[0].GetValue<int64_t>());
		}
		Rows again;
		std::string why;
		auto ran2 = ok && Try(*node.client, search, again, error);
		tally.Add("invariant 5: hidden rows removed and added, the governed input the same, every qod_search result and "
		          "score bit-identical",
		          ran2 && governed_after == governed && SameHits(Hits(again), hits, true, why),
		          Describe(c) + ": " + (ran2 ? why : FirstLine(error)));
		// The same for qod_search_matches: the same set, each score bit for bit (NULL stays NULL).
		Rows matched_again;
		auto ran3 = ok && ran_m && Try(*node.client, matches_call, matched_again, error);
		auto by_rowid = [](const Rows &r) {
			std::map<int64_t, std::pair<bool, float>> out;
			for (auto &row : r) {
				out[row[0].GetValue<int64_t>()] = {row[1].IsNull(), row[1].IsNull() ? 0.0f : row[1].GetValue<float>()};
			}
			return out;
		};
		tally.Add("invariant 5: hidden rows removed and added, every qod_search_matches row and score bit-identical",
		          ran3 && by_rowid(matched_again) == by_rowid(matched),
		          Describe(c) + ": " + (ran3 ? std::to_string(matched_again.size()) + " rows against " +
		                                           std::to_string(matched.size())
		                                     : FirstLine(error)));
	}
	g_population = "table";
	Say("   case " + std::to_string(n) + ": " + std::to_string(listed.size()) + " files (" + std::to_string(bundled) +
	    " bundled), " + std::to_string(docs.size()) + " live rows, " + std::to_string(governed.size()) +
	    " governed, " + std::to_string(scores.size()) + " matching, " + c.population + ", '" + c.query + "' on " +
	    c.columns);
	Exec(setup, "DROP TABLE " + c.table, "drop the case table");
	return true;
}

uint64_t EnvNumber(const char *name, uint64_t fallback) {
	auto text = std::getenv(name);
	if (!text || !*text) {
		return fallback;
	}
	try {
		return std::stoull(text);
	} catch (...) {
		Say(std::string("search_property_test: ") + name + " is not a number, using " + std::to_string(fallback));
		return fallback;
	}
}

int Run() {
	auto seed = EnvNumber("QOD_SEARCH_PROPERTY_SEED", 20261117);
	auto cases = EnvNumber("QOD_SEARCH_PROPERTY_CASES", 200);
	Say("search_property_test: seed " + std::to_string(seed) + ", " + std::to_string(cases) + " cases");
	{
		// The reference's two pieces, on their own: the fieldnorm codes and the analyzer.
		auto codes = FieldnormCodes();
		Check(codes.size() == 256 && codes[40] == 40 && codes[41] == 42 && codes[48] == 56 && codes[49] == 60 &&
		          codes[255] == 2013265944u,
		      "the fieldnorm codes: identity to 40, then eight per doubling step, the last 2013265944");
		std::string error;
		auto tokens = Tokens("W3 w4,w5 Café (w1) x-w2", error);
		Check(tokens == std::vector<std::string> {"w3", "w4", "w5", "café", "w1", "x", "w2"},
		      "qs_test_tokenize: lower case, split on every non-alphanumeric character", FirstLine(error));
	}
	Node node;
	OpenNode(node, "qod-search-property");
	if (node.status != 0) {
		return node.status;
	}
	Check(WrapClientFileSystem(*node.client), "the client's file system counts its opens");
	Exec(*node.setup, "CREATE TABLE lake.main.aux (id BIGINT)", "aux");
	Exec(*node.setup, "INSERT INTO lake.main.aux SELECT i * 7 FROM range(0, 200) r(i)", "aux rows");
	Draw d(seed);
	Tally tally;
	uint64_t ran = 0;
	for (uint64_t n = 0; n < cases; n++) {
		ran += RunCase(node, d, static_cast<int>(n), tally) ? 1 : 0;
	}
	Check(ran == cases, "every case ran its fixture", std::to_string(ran) + " of " + std::to_string(cases));
	for (auto &entry : tally.counts) {
		auto detail = tally.first.count(entry.first) ? tally.first[entry.first] : "";
		Check(entry.second.second == 0,
		      entry.first + ": " + std::to_string(entry.second.first) + " of " +
		          std::to_string(entry.second.first + entry.second.second) + " cases",
		      detail);
	}
	CloseNode(node);
	return Finish("search_property_test (seed " + std::to_string(seed) + ")");
}

#else

int Run() {
	Say("search_property_test: the reference needs the library's qs_test_tokenize (configure with "
	    "QOD_SEARCH_TEST_HOOKS=ON), skipped");
	return 77;
}

#endif // QOD_SEARCH_TEST_HOOKS

} // namespace

int main() {
	return Run();
}
