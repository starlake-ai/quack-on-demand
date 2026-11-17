// QoD Search adversarial suite (spec 4.2's adversarial list, P8's R and D cases, spec 13's named exception;
// spec 12 Phase 1 item 19, invariant 2), for qod_search and qod_search_matches alike.
//
// Fixture: lake.main.pages(id, title, body, lang, f10) of 4,000 rows in 2 bundled data files, lake.main.other(id,
// body), a plain view lake.main.v (rowid AS rid), a plain view lake.main.pv over pages (a policy view), a secure
// view memory.main.sv, and a second lake lake2 (its own DuckDB-file catalog) with lake2.main.docs. Index records
// from the stand-in record source; the client is a tenant principal whose plans the stand-in decider allows.
//
// A: every shape of the list is refused with its code before any bundle is opened: the segment cache's reads, hits,
// missing and refused counters unchanged, no fallback call, no sink plan, and the client file system opening
// nothing under .qod_search. Then the verdict cache pair (decided twice), the P2 case (an input over a policy view
// after a FROM item with a rowid), the rewriter's output for a row policy whose predicate holds a subquery
// (admitted, the policy's rows only), qod_search_build_segment as a tenant and as a superuser (refused), qod_knn and
// qod_hybrid (unknown functions).
// N: the named exception's calls (spec 13): every call a search makes names the resolved alias, schema and target
// only, and opens only files its listing named for the target; session macros shadowing the called names change
// nothing; a client statement calling the exception's functions is refused by the parse screen; a row policy on
// another table of the statement adds no call naming it; a search over a second lake names only that lake's alias.
// Exits 77 when ducklake cannot load.

#include "search_fixtures.hpp"
#include "search_snapshot.hpp"
#include "sql_script.hpp"

namespace {

const std::vector<std::string> kFunctions = {"qod_search", "qod_search_matches"};

//! `fn` over `input` (a statement's text), as Search does for qod_search.
std::string Call(const std::string &fn, const std::string &input, const std::string &query = kQuery,
                 const std::string &table = kTable, const std::string &columns = kCols) {
	return "SELECT * FROM " + fn + "((" + input + "), " + table + ", " + columns + ", " + query + ")";
}

//! Opens under a .qod_search folder the client's file system saw while counting.
idx_t BundleOpens() {
	std::lock_guard<std::mutex> lock(g_fs_mutex);
	idx_t n = 0;
	for (auto &open : g_fs_opens) {
		n += open.first.find(".qod_search") != std::string::npos ? open.second : 0;
	}
	return n;
}

//! `sql` refused with `expected` in its error (empty: any error, recorded), before any bundle is opened.
void RefusedBeforeOpen(Connection &con, const std::string &name, const std::string &sql, const std::string &expected) {
	ResetSinkCountersForTest();
	auto cache = qod::search::SegmentCacheCountersForTest();
	auto fallback = qod::search::FallbackCountersForTest().calls;
	{
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		g_fs_opens.clear();
		g_fs_counting = true;
	}
	Rows rows;
	std::string error;
	auto ran = Try(con, sql, rows, error);
	{
		std::lock_guard<std::mutex> lock(g_fs_mutex);
		g_fs_counting = false;
	}
	auto after = qod::search::SegmentCacheCountersForTest();
	auto opens = BundleOpens();
	Check(!ran && rows.empty() && (expected.empty() || error.find(expected) != std::string::npos),
	      name + ": refused" + (expected.empty() ? "" : " with " + expected), ran ? "it ran" : FirstLine(error));
	Check(after.reads == cache.reads && after.hits == cache.hits && after.missing == cache.missing &&
	          after.refused == cache.refused && after.missing_hits == cache.missing_hits &&
	          qod::search::FallbackCountersForTest().calls == fallback && SinkCountersForTest().plans == 0 && opens == 0,
	      name + ": no bundle opened (segment counters 0), no fallback, no sink plan",
	      "reads " + std::to_string(after.reads - cache.reads) + ", hits " + std::to_string(after.hits - cache.hits) +
	          ", missing " + std::to_string(after.missing - cache.missing) + ", bundle opens " + std::to_string(opens) +
	          ", sink plans " + std::to_string(SinkCountersForTest().plans));
}

//! The screen as quack calls it, for connection `connection_id` and the statement `sql`.
std::string ScreenCall(const std::string &connection_id, const std::string &sql) {
	return std::string("SELECT ") + qod::kScreenFunction + "(" + qod::QuoteLiteral(connection_id) + ", " +
	       qod::QuoteLiteral(sql) + ")";
}

//! Every ruled call of `view` names the alias `alias`, schema main and table `table`, and every path it reads is one
//! the view lists for that table (the port of search_snapshot_test's ReadsOnlyTarget to a whole search).
bool ReadsOnlyTarget(const qod::search::SnapshotView &view, const std::string &alias, const std::string &table,
                     std::string &why) {
	std::set<std::string> paths;
	for (auto &f : view.files) {
		paths.insert(f.path);
	}
	for (auto &d : view.delete_files) {
		paths.insert(d.path);
	}
	if (view.catalog_alias != alias || view.schema_name != "main" || view.table_name != table) {
		why = "the view resolved " + view.catalog_alias + "." + view.schema_name + "." + view.table_name;
		return false;
	}
	if (view.reads.empty()) {
		why = "no call recorded";
		return false;
	}
	for (auto &r : view.reads) {
		bool ok = false;
		if (r == "ducklake_current_snapshot('" + alias + "')") {
			ok = true;
		} else if (r.rfind("ducklake_list_files('" + alias + "', '" + table + "', schema => 'main', snapshot_version => ",
		                   0) == 0) {
			ok = true;
		} else if (r.rfind("ducklake_scan('" + alias + "'.'main'.'" + table + "'", 0) == 0) {
			ok = true;
		} else if (r.rfind("parquet_scan('", 0) == 0) {
			auto end = r.find("')");
			ok = end != std::string::npos && paths.count(r.substr(14, end - 14));
		}
		if (!ok) {
			why = "unexpected call: " + r;
			return false;
		}
	}
	return true;
}

//! A search on `con` and the view its call searched at (the liveness cache cleared first, so the calls are this
//! search's own).
bool SearchView(Connection &con, const std::string &sql, Rows &rows, qod::SearchCallRecord &record, std::string &error) {
	qod::search::ClearLivenessCacheForTest();
	qod::ResetLastSearchCallForTest();
	auto ran = Try(con, sql, rows, error);
	record = qod::LastSearchCallForTest();
	return ran && record.called && record.view;
}

// ---- the verdict cache pair (rule 5), on a node deciding through its own decide client --------------

std::atomic<int> g_replica_plans {0};
//! When set, the replica refuses every plan reading lake.main.pages's body as a search column, as AuthzDecider refuses
//! a column carrying a mask (spec 4.2 rule 5; AuthzDeciderSearchSpec pins the decider's half).
std::atomic<bool> g_mask_body {false};
const std::string kMaskReason = "column body of lake.main.pages carries a column policy";

//! A replica allowing every plan and screen of u-plain as the manager signs them, each search call of a plan with
//! the table population; counts the plan decisions it answered.
qod_test::HttpAnswer AllowingReplica(const qod_test::HttpRequest &request) {
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
			g_replica_plans++;
			if (g_mask_body && description.find("\"columns\":[\"body\"],\"table\":\"lake.main.pages\"") != std::string::npos) {
				fields.decision = "refuse";
				fields.reason = kMaskReason;
			}
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

//! What one statement cost the replica: whether it ran, its plan decisions, its verdict cache hits.
struct Decided {
	bool ran = false;
	int decisions = 0;
	int64_t hits = 0;
	std::string error;
};

//! Two statements that differ only in `table` or in a column argument never share a cached verdict: the same
//! statement again is a cache hit (no decision), each such variant is decided.
void RunVerdictCache() {
	g_indexes.Clear();
	Node node;
	OpenNode(node, "qod-search-adversarial-cache", 10, AllowingReplica);
	if (node.status != 0) {
		Check(false, "the verdict cache node", std::to_string(node.status));
		return;
	}
	auto &setup = *node.setup;
	bool ok = Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR)", "cache pages") &&
	          Exec(setup, "INSERT INTO lake.main.pages SELECT i, 't' || i, 'w1 w' || (i % 7) FROM range(0, 300) r(i)",
	               "cache pages rows") &&
	          Exec(setup, "CREATE TABLE lake.main.other (id BIGINT, title VARCHAR, body VARCHAR)", "cache other") &&
	          Exec(setup, "INSERT INTO lake.main.other SELECT i, 't' || i, 'w1 w2' FROM range(0, 300) r(i)",
	               "cache other rows") &&
	          IndexTable(setup, "pages", {"title", "body"}, node.data) &&
	          IndexTable(setup, "other", {"title", "body"}, node.data);
	if (!ok) {
		CloseNode(node);
		return;
	}
	auto decided = [&](const std::string &sql) {
		Decided out;
		auto before = g_replica_plans.load();
		auto hits = node.authz->Stats().plan_cache_hits.load();
		Rows rows;
		out.ran = Try(*node.client, sql, rows, out.error);
		out.decisions = g_replica_plans.load() - before;
		out.hits = static_cast<int64_t>(node.authz->Stats().plan_cache_hits.load() - hits);
		return out;
	};
	const std::string input = "SELECT rowid FROM lake.main.pages";
	for (auto &fn : kFunctions) {
		auto first = decided(Call(fn, input, "'w1'"));
		auto again = decided(Call(fn, input, "'w1'"));
		auto title = decided(Call(fn, input, "'w1'", kTable, "['title']"));
		auto both = decided(Call(fn, input, "'w1'", kTable, "['title', 'body']"));
		auto swapped = decided(Call(fn, input, "'w1'", kTable, "['body', 'title']"));
		// `table` alone differs: the same input, the call naming other (refused by rule 2 once it is decided).
		auto table = decided(Call(fn, input, "'w1'", "'lake.main.other'"));
		Check(first.ran && again.ran && first.decisions > 0 && again.decisions == 0 && again.hits > 0,
		      fn + " the same statement again: a verdict cache hit, no decision",
		      "decided " + std::to_string(first.decisions) + " then " + std::to_string(again.decisions) + ", hits " +
		          std::to_string(again.hits) + " " + FirstLine(first.error + again.error));
		Check(title.ran && both.ran && swapped.ran && title.decisions > 0 && both.decisions > 0 && swapped.decisions > 0,
		      fn + " the same statement with ['title'], ['title', 'body'] or ['body', 'title']: each decided again",
		      std::to_string(title.decisions) + ", " + std::to_string(both.decisions) + ", " +
		          std::to_string(swapped.decisions) + " " + FirstLine(title.error + both.error + swapped.error));
		Check(!table.ran && table.error.find("search_input_wrong_table") != std::string::npos && table.decisions > 0,
		      fn + " the same statement with `table` naming other: decided again (then refused by rule 2)",
		      std::to_string(table.decisions) + " decisions; " + (table.ran ? "it ran" : FirstLine(table.error)));
	}
	// A masked column (4.2's list, the node's half): a plan verdict refusing the search column refuses the statement
	// before any bundle is opened; the same call on the unmasked column runs.
	int64_t files = 0;
	if (BuildSegments(setup, *node.sysc, "pages", node.data, files)) {
		g_mask_body = true;
		for (auto &fn : kFunctions) {
			// An input no earlier statement had, so its plan is decided now (never a verdict cached before the mask).
			const std::string masked_input = "SELECT rowid FROM lake.main.pages WHERE title IS NOT NULL";
			RefusedBeforeOpen(*node.client, fn + " a masked search column: the verdict refuses it",
			                  Call(fn, masked_input, "'w1'"), "QoD: " + kMaskReason);
			auto title = decided(Call(fn, masked_input, "'w1'", kTable, "['title']"));
			Check(title.ran, fn + " the same call on the unmasked title runs", FirstLine(title.error));
		}
		g_mask_body = false;
	}
	CloseNode(node);
}

//! What one search's hook and fallback read beside the named exception's recorded calls: the hook's metadata
//! statements (SearchMetadataReadObserverForTest) and the fallback's text scans (TextScanTargetsForTest).
struct Observed {
	std::vector<std::string> metadata;
	std::vector<qod::search::TextScanRecord> scans;
};

bool ObservedSearch(Connection &con, const std::string &sql, Rows &rows, qod::SearchCallRecord &record, Observed &seen,
                    std::string &error) {
	std::mutex lock;
	qod::SearchMetadataReadObserverForTest([&](const std::string &text) {
		std::lock_guard<std::mutex> guard(lock);
		seen.metadata.push_back(text);
	});
	qod::search::ResetTextScanTargetsForTest();
	auto ran = SearchView(con, sql, rows, record, error);
	qod::SearchMetadataReadObserverForTest(nullptr);
	seen.scans = qod::search::TextScanTargetsForTest();
	return ran;
}

//! Every fallback text scan of `seen` names `alias`.main.`table` and only sources the view lists (its data files, its
//! inlined tables); at least one scan ran.
bool ScansOnlyTarget(const Observed &seen, const qod::search::SnapshotView &view, const std::string &alias,
                     const std::string &table, std::string &why) {
	std::set<std::string> sources;
	for (auto &f : view.files) {
		sources.insert(f.path);
	}
	for (auto &t : view.inlined_tables) {
		sources.insert(t.table_name);
	}
	if (seen.scans.empty()) {
		why = "no fallback scan ran";
		return false;
	}
	for (auto &scan : seen.scans) {
		if (scan.catalog != alias || scan.schema != "main" || scan.table != table) {
			why = "a scan of " + scan.catalog + "." + scan.schema + "." + scan.table;
			return false;
		}
		for (auto &file : scan.files) {
			if (!sources.count(file)) {
				why = "a scan of a source the view does not list: " + file;
				return false;
			}
		}
	}
	return true;
}

//! Every metadata statement of `seen` reads `meta` (a __ducklake_metadata_<alias> catalog, quoted) and names
//! `'table'`, and none names any of `others`.
bool MetadataOnlyTarget(const Observed &seen, const std::string &meta, const std::string &table,
                        const std::vector<std::string> &others, std::string &why) {
	if (seen.metadata.empty()) {
		why = "no metadata read";
		return false;
	}
	for (auto &sql : seen.metadata) {
		bool ok = sql.find("\"" + meta + "\".") != std::string::npos && sql.find("'" + table + "'") != std::string::npos;
		for (auto &o : others) {
			ok = ok && sql.find(o) == std::string::npos;
		}
		if (!ok) {
			why = "metadata read: " + sql;
			return false;
		}
	}
	return true;
}

int Run() {
	Node node;
	OpenNode(node, "qod-search-adversarial");
	if (node.status != 0) {
		return node.status;
	}
	auto &setup = *node.setup;
	auto &sysc = *node.sysc;
	auto &client = *node.client;
	auto &data = node.data;
	auto insert = [](int64_t from, int64_t to) {
		return "INSERT INTO lake.main.pages SELECT i, 't' || i, 'w' || (i % 50) || ' w1 w' || (i % 7) || ' w10 x' || i, "
		       "CASE WHEN i % 2 = 0 THEN 'fr' ELSE 'en' END, (i % 10)::INTEGER FROM range(" +
		       std::to_string(from) + ", " + std::to_string(to) + ") r(i) ORDER BY i";
	};
	bool ok = Exec(setup, "CREATE TABLE lake.main.pages (id BIGINT, title VARCHAR, body VARCHAR, lang VARCHAR, f10 INTEGER)",
	               "pages") &&
	          Exec(setup, insert(0, 2000), "pages file 1");
	ok = ok && Exec(setup, insert(2000, 4000), "pages file 2") &&
	     Exec(setup, "CREATE TABLE lake.main.other (id BIGINT, body VARCHAR)", "other") &&
	     Exec(setup, "INSERT INTO lake.main.other SELECT i * 3, 'w1 w2' FROM range(0, 800) r(i)", "other rows") &&
	     Exec(setup, "CREATE VIEW lake.main.v AS SELECT rowid AS rid, * FROM lake.main.pages", "view v") &&
	     Exec(setup, "CREATE VIEW lake.main.pv AS SELECT * FROM lake.main.pages", "policy view pv") &&
	     Exec(setup, "CREATE SECURE VIEW memory.main.sv AS SELECT rowid AS rid, id FROM lake.main.pages", "secure view") &&
	     IndexTable(setup, "pages", {"title", "body"}, data) && IndexTable(setup, "other", {"body"}, data);
	int64_t files = 0, other_files = 0;
	ok = ok && BuildSegments(setup, sysc, "pages", data, files) && BuildSegments(setup, sysc, "other", data, other_files);
	Check(ok && files == 2, "the fixture: pages in 2 bundled data files", std::to_string(files));
	if (!ok) {
		CloseNode(node);
		return 1;
	}
	// A delete file, so the calls read one too.
	Exec(setup, "DELETE FROM lake.main.pages WHERE id % 97 = 5", "pages delete file");
	Check(WrapClientFileSystem(client), "the client's file system counts its opens");
	const std::string pages = "SELECT rowid FROM lake.main.pages";

	// ---- A: the adversarial list, both functions ---------------------------------------------------
	// Fixtures of the shapes below: a view over a view, one of the target's data files read by path; the temp table
	// macro over the target is made on the client's connection just for its shape (a session holding a temp macro
	// cannot read a secure view).
	Exec(setup, "CREATE VIEW lake.main.vv AS SELECT rid FROM lake.main.v", "view over a view");
	Rows a_file;
	std::string a_file_error;
	Try(setup, "SELECT data_file FROM ducklake_list_files('lake', 'pages') ORDER BY data_file LIMIT 1", a_file, a_file_error);
	auto data_file = a_file.empty() ? std::string("/nowhere.parquet") : a_file[0][0].GetValue<std::string>();
	// Each refusal with the reason the node gives (P8's record, the security verification's list).
	const std::string ng = "search_input_not_governed: ";
	const std::string join = ng + "a join outside a predicate subquery's filter";
	const std::string computed = ng + "a column computed by an expression";
	const std::string table_function = ng + "a table function";
	struct Shape {
		std::string name, input, expected;
	};
	const std::vector<Shape> shapes = {
	    {"VALUES", "SELECT * FROM (VALUES (1::BIGINT), (2::BIGINT)) t(r)", ng + "EXPRESSION_GET"},
	    {"range", "SELECT range FROM range(5)", ng + "a scan that is not of a DuckLake table (range)"},
	    {"another table", "SELECT rowid FROM lake.main.other",
	     "search_input_wrong_table: the input scans lake.main.other, not lake.main.pages"},
	    {"a join in FROM", "SELECT p.rowid FROM lake.main.pages p JOIN lake.main.other o ON p.id = o.id", join},
	    {"a comma join widening rows", "SELECT p.rowid FROM lake.main.pages p, lake.main.other o", join},
	    {"a CROSS JOIN under WHERE", "SELECT p.rowid FROM lake.main.pages p CROSS JOIN lake.main.other o WHERE p.id = o.id",
	     ng + "a join written in FROM"},
	    {"a LEFT JOIN", "SELECT p.rowid FROM lake.main.pages p LEFT JOIN lake.main.other o ON p.id = o.id", join},
	    {"a RIGHT JOIN", "SELECT p.rowid FROM lake.main.other o RIGHT JOIN lake.main.pages p ON p.id = o.id", join},
	    {"a FULL OUTER JOIN", "SELECT p.rowid FROM lake.main.pages p FULL OUTER JOIN lake.main.other o ON p.id = o.id",
	     join},
	    {"a LATERAL subquery",
	     "SELECT p.rowid FROM lake.main.pages p, LATERAL (SELECT o.id FROM lake.main.other o WHERE o.id = p.id) l", join},
	    {"an ASOF JOIN", "SELECT p.rowid FROM lake.main.pages p ASOF JOIN lake.main.other o ON p.id >= o.id", join},
	    {"a POSITIONAL JOIN", "SELECT p.rowid FROM lake.main.pages p POSITIONAL JOIN lake.main.other o", join},
	    {"a UNION with another table", "SELECT rowid FROM lake.main.pages UNION SELECT rowid FROM lake.main.other",
	     ng + "DISTINCT"},
	    {"a UNION ALL of the target", "SELECT rowid FROM lake.main.pages UNION ALL SELECT rowid FROM lake.main.pages",
	     ng + "UNION"},
	    {"a CTE", "WITH x AS (SELECT rowid AS r FROM lake.main.pages) SELECT r FROM x", ng + "CTE"},
	    {"a MATERIALIZED CTE over query_table()",
	     "WITH x AS MATERIALIZED (SELECT rid FROM query_table('lake.main.v')) SELECT rid FROM x", ng + "CTE"},
	    {"a NOT MATERIALIZED CTE over query_table()",
	     "WITH x AS NOT MATERIALIZED (SELECT rid FROM query_table('lake.main.v')) SELECT rid FROM x", ng + "CTE"},
	    {"a plain view's rowid (DuckDB's bind error)", "SELECT rowid FROM lake.main.v",
	     "Referenced column \"rowid\" not found in FROM clause!"},
	    {"a view selecting rowid AS rid", "SELECT rid FROM lake.main.v", ng + "a view"},
	    {"an alias column list over a view", "SELECT x FROM lake.main.v AS t(x)", ng + "a view"},
	    {"a view over a view", "SELECT rid FROM lake.main.vv", ng + "a view"},
	    {"a secure view over the target", "SELECT rid FROM memory.main.sv", ng + "SECURE_VIEW"},
	    {"a temp table macro over the target", "SELECT r FROM tm()", table_function},
	    {"an expression over rowid", "SELECT rowid + 1 FROM lake.main.pages", computed},
	    {"rowid + 1 AS rowid", "SELECT rowid + 1 AS rowid FROM lake.main.pages", computed},
	    {"rowid * 1 in a derived table", "SELECT r FROM (SELECT rowid * 1 AS r FROM lake.main.pages) s", computed},
	    {"a select-list subquery",
	     "SELECT rowid FROM (SELECT rowid, (SELECT max(id) FROM lake.main.other o WHERE o.id = pages.id) AS n FROM "
	     "lake.main.pages) s",
	     join},
	    {"a select-list subquery, two columns",
	     "SELECT rowid, (SELECT max(id) FROM lake.main.other o WHERE o.id = pages.id) FROM lake.main.pages",
	     ng + "the input must have exactly one column, a BIGINT rowid"},
	    {"a LIMIT", "SELECT rowid FROM lake.main.pages LIMIT 5", ng + "LIMIT"},
	    {"an OFFSET", "SELECT rowid FROM lake.main.pages OFFSET 5", ng + "LIMIT"},
	    {"DISTINCT", "SELECT DISTINCT rowid FROM lake.main.pages", ng + "DISTINCT"},
	    {"a window (QUALIFY)", "SELECT rowid FROM lake.main.pages QUALIFY row_number() OVER () < 5", ng + "WINDOW"},
	    {"an aggregate", "SELECT max(rowid) FROM lake.main.pages", ng + "AGGREGATE"},
	    {"UNNEST in the select list", "SELECT unnest([rowid]) FROM lake.main.pages", ng + "UNNEST"},
	    {"UNNEST in FROM", "SELECT r FROM unnest([1::BIGINT, 2::BIGINT]) t(r)",
	     ng + "a scan that is not of a DuckLake table (unnest)"},
	    {"USING SAMPLE on the table", "SELECT rowid FROM lake.main.pages USING SAMPLE 10%", ng + "SAMPLE"},
	    {"TABLESAMPLE on the table", "SELECT rowid FROM lake.main.pages TABLESAMPLE 10%", ng + "SAMPLE"},
	    {"a sample on a subquery", "SELECT rowid FROM (SELECT rowid FROM lake.main.pages) s USING SAMPLE 10%", ng + "SAMPLE"},
	    {"query_table() over the view", "SELECT rid FROM query_table('lake.main.v')", table_function},
	    {"FROM-first query_table()", "FROM query_table('lake.main.v') SELECT rid", table_function},
	    {"query() over the view", "SELECT rid FROM query('SELECT rid FROM lake.main.v')", table_function},
	    {"query() holding a comma join",
	     "SELECT r FROM query('SELECT p.rowid AS r FROM lake.main.pages p, lake.main.other o WHERE p.id = o.id')",
	     table_function},
	    {"read_parquet of one of the target's data files",
	     "SELECT file_row_number FROM read_parquet(" + Quote(data_file) + ", file_row_number = true)",
	     ng + "a scan that is not of a DuckLake table (read_parquet)"},
	    {"a target scan AT a version whose name was a table dropped since", "", ""},
	};
	// The AT mismatch: a table named like the target at an earlier version, dropped and created again since, so
	// that the input's scan at that version is another table than the one `table` names now.
	ok = Exec(setup, "CREATE TABLE lake.main.gone (id BIGINT, body VARCHAR)", "gone") &&
	     Exec(setup, "INSERT INTO lake.main.gone SELECT i, 'w1 w' || i FROM range(0, 50) r(i)", "gone rows");
	auto gone_at = Int(setup, "SELECT id FROM ducklake_current_snapshot('lake')");
	ok = ok && Exec(setup, "DROP TABLE lake.main.gone", "drop gone") &&
	     Exec(setup, "CREATE TABLE lake.main.gone (id BIGINT, body VARCHAR)", "gone again") &&
	     Exec(setup, "INSERT INTO lake.main.gone SELECT i, 'w1' FROM range(0, 20) r(i)", "gone again rows") &&
	     IndexTable(setup, "gone", {"body"}, data);
	Check(ok, "the AT mismatch's table: dropped and created again since version " + std::to_string(gone_at));
	Connection root(*node.db);
	Install(root, "acme/acme_db/bi", false, true);
	const std::string gone_input = "SELECT rowid FROM lake.main.gone AT (VERSION => " + std::to_string(gone_at) + ")";
	for (auto &fn : kFunctions) {
		for (auto &shape : shapes) {
			if (shape.input.empty()) {
				// A tenant is refused by the describer (a table dropped since cannot be judged by its name); a superuser,
				// whom the describer does not judge, by rule 2's oid check.
				RefusedBeforeOpen(client, fn + " A " + shape.name + ", as a tenant",
				                  Call(fn, gone_input, "'w1'", "'lake.main.gone'"),
				                  "QoD: lake.main.gone at that snapshot is a table dropped since, and its name now belongs to "
				                  "another table or view");
				RefusedBeforeOpen(root, fn + " A " + shape.name + ", as a superuser (rule 2)",
				                  Call(fn, gone_input, "'w1'", "'lake.main.gone'"), "QoD: search_input_wrong_table");
				continue;
			}
			bool macro = shape.input == "SELECT r FROM tm()";
			if (macro) {
				Exec(client, "CREATE TEMP MACRO tm() AS TABLE SELECT rowid AS r FROM lake.main.pages", "temp table macro");
			}
			RefusedBeforeOpen(client, fn + " A " + shape.name, Call(fn, shape.input), shape.expected);
			if (macro) {
				Exec(client, "DROP MACRO TABLE temp.main.tm", "drop the temp table macro");
			}
		}
		RefusedBeforeOpen(client, fn + " A a call with no input subquery (DuckDB's bind error)",
		                  "SELECT * FROM " + fn + "('lake.main.pages', ['body'], 'w1')",
		                  "Table function \"" + fn + "\" requires a subquery as its TABLE parameter");
		RefusedBeforeOpen(client, fn + " A a field outside columns (title:w0 with ['body'])",
		                  Call(fn, pages, "'title:w0'"), "QoD: search_field_not_allowed: title");
		RefusedBeforeOpen(client, fn + " A a field outside columns in a group (w1 OR (lang:fr))",
		                  Call(fn, pages, "'w1 OR (lang:fr)'"), "QoD: search_field_not_allowed: lang");
		RefusedBeforeOpen(client, fn + " A a column outside the index (['lang'])", Call(fn, pages, kQuery, kTable, "['lang']"),
		                  "QoD: search_columns_unsupported: lang of lake.main.pages is not indexed");
		RefusedBeforeOpen(client, fn + " A a computed table argument", Call(fn, pages, kQuery, "'lake.main.' || 'pages'"),
		                  "QoD: search_argument_not_constant: table");
		RefusedBeforeOpen(client, fn + " A a computed columns argument", Call(fn, pages, kQuery, kTable, "['bo' || 'dy']"),
		                  "QoD: search_argument_not_constant");
		// P2's owed case: an input over a policy view (the rewriter's wrap, which needs a rowid the view lacks) placed
		// after a FROM item that has a rowid, which DuckDB's implicit lateral join binds the wrap's rowid to.
		RefusedBeforeOpen(client, fn + " A P2: an input over a policy view after a FROM item with a rowid",
		                  "SELECT h.rowid FROM lake.main.other o, " + fn +
		                      "((SELECT rowid FROM (SELECT *, rowid FROM lake.main.pv WHERE (f10 = 4)) AS pv), "
		                      "'lake.main.pages', ['body'], 'w1') h",
		                  "QoD: search_input_not_governed: a column that does not come from the target scan");
		// CS2: an input correlated to an earlier FROM item of the statement.
		RefusedBeforeOpen(client, fn + " A an input correlated to an earlier FROM item",
		                  "SELECT h.rowid FROM lake.main.pages p, " + fn +
		                      "((SELECT rowid FROM lake.main.pages WHERE p.title = 't2'), 'lake.main.pages', ['body'], "
		                      "'w1') h",
		                  "QoD: search_unsupported_plan: " + fn + " with a LATERAL or correlated input");
		// I2: a search call inside a query() literal (at two depths) or a view body, for every principal.
		const std::string in_query = "QoD: search_input_not_governed: a search function called inside a query() body";
		RefusedBeforeOpen(client, fn + " A a search call inside a query() literal",
		                  "SELECT * FROM query('SELECT * FROM " + fn +
		                      "((SELECT rowid FROM lake.main.pages), ''lake.main.pages'', [''body''], ''w1'')')",
		                  in_query);
		RefusedBeforeOpen(client, fn + " A a search call inside a query() literal nested two levels",
		                  "SELECT * FROM query('SELECT * FROM query(''SELECT * FROM " + fn +
		                      "((SELECT rowid FROM lake.main.pages), ''''lake.main.pages'''', [''''body''''], "
		                      "''''w1'''')'')')",
		                  in_query);
		auto view = "call_" + fn;
		Exec(setup,
		     "CREATE VIEW lake.main." + view + " AS SELECT rowid AS r FROM " + fn +
		         "((SELECT rowid FROM lake.main.pages), 'lake.main.pages', ['body'], 'w1')",
		     "a view calling " + fn);
		RefusedBeforeOpen(client, fn + " A a search call inside a view body", "SELECT r FROM lake.main." + view,
		                  "QoD: search_input_not_governed: a search function called inside a view, macro or query() body");
		RefusedBeforeOpen(root, fn + " A a search call inside a view body, as a superuser", "SELECT r FROM lake.main." + view,
		                  "QoD: search_input_not_governed: a search function called inside a view, macro or query() body");
	}

	// The rewriter's output for a row policy whose predicate holds a subquery: admitted, the allowed population, and
	// every row it returns is of the policy (other's ids are multiples of 3, so min(id % 10) is 0: f10 = 0).
	{
		auto saved = g_population;
		g_population = "allowed";
		const std::string wrapped =
		    "SELECT rowid FROM (SELECT *, rowid FROM lake.main.pages WHERE (f10 IN (SELECT min(id % 10) FROM "
		    "lake.main.other))) AS pages WHERE lang = 'fr'";
		Rows policy_rows;
		std::string perr;
		Try(setup, "SELECT rowid FROM lake.main.pages WHERE f10 = 0 AND lang = 'fr'", policy_rows, perr);
		std::set<int64_t> policy;
		for (auto &row : policy_rows) {
			policy.insert(row[0].GetValue<int64_t>());
		}
		for (auto &fn : kFunctions) {
			Rows rows;
			std::string error;
			qod::ResetLastSearchCallForTest();
			auto ran = Try(client, Call(fn, wrapped, "'w1'"), rows, error);
			auto last = qod::LastSearchCallForTest();
			bool inside = !rows.empty();
			for (auto &row : rows) {
				inside = inside && policy.count(row[0].GetValue<int64_t>());
			}
			Check(ran && inside && last.population == QS_POPULATION_ALLOWED,
			      fn + " A the rewriter's output for a row policy holding a subquery: admitted, the allowed population, " +
			          std::to_string(rows.size()) + " rows, all of the policy",
			      ran ? "" : FirstLine(error));
		}
		g_population = saved;
	}

	// qod_search_build_segment as a tenant principal and as a superuser.
	{
		const std::string build = "SELECT * FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, NULL::BIGINT, "
		                          "'x', 'y'), 'lake.main.pages', '/nowhere', 1::UBIGINT, 1::UBIGINT)";
		Connection root(*node.db);
		Install(root, "acme/acme_db/bi", false, true);
		RefusedBeforeOpen(client, "A qod_search_build_segment as a tenant principal", build,
		                  "QoD: function qod_search_build_segment is superuser-only");
		RefusedBeforeOpen(root, "A qod_search_build_segment as a superuser", build,
		                  "QoD: function qod_search_build_segment is superuser-only");
		RefusedBeforeOpen(setup, "A qod_search_build_segment off a quack session", build,
		                  "QoD: function qod_search_build_segment is superuser-only");
	}

	// qod_knn and qod_hybrid are not built in Phase 1: any call is an unknown function, for every principal. (Their
	// names switch the wrap and the star rule on at the gateway through Task 5's detector, SearchFunctionsSpec.)
	{
		Connection root(*node.db);
		Install(root, "acme/acme_db/bi", false, true);
		for (auto &call : {std::string("SELECT * FROM qod_knn((SELECT rowid FROM lake.main.pages), 'lake.main.pages', "
		                               "'body', [1.0, 2.0], 3)"),
		                   std::string("SELECT * FROM qod_hybrid((SELECT rowid FROM lake.main.pages), 'lake.main.pages', "
		                               "['body'], 'w1', 'body', [1.0, 2.0])")}) {
			auto fn = call.substr(14, call.find('(') - 14);
			RefusedBeforeOpen(client, "A " + fn + " as a tenant: an unknown function", call, "does not exist");
			RefusedBeforeOpen(root, "A " + fn + " as a superuser: an unknown function", call, "does not exist");
		}
	}

	// ---- N: the named exception's calls (spec 13) ---------------------------------------------------
	// Inlined rows, so that every search of pages below runs the fallback's text scan too.
	Exec(setup, "INSERT INTO lake.main.pages VALUES (90000, 't', 'w1 w2', 'fr', 0), (90001, 't', 'w1', 'en', 1)",
	     "pages inlined rows");
	for (auto &fn : kFunctions) {
		Rows rows;
		std::string error, why, why_scan;
		qod::SearchCallRecord record;
		Observed seen;
		auto ran = ObservedSearch(client, Call(fn, pages, "'w1'"), rows, record, seen, error);
		Check(ran && ReadsOnlyTarget(*record.view, "lake", "pages", why) && record.view->delete_files.size() >= 1 &&
		          ScansOnlyTarget(seen, *record.view, "lake", "pages", why_scan),
		      fn + " N every call of a search names lake, main, pages only and reads only files its listing named (" +
		          std::to_string(ran ? record.view->reads.size() : 0) + " calls, " + std::to_string(seen.scans.size()) +
		          " fallback scans; the view lists a delete file)",
		      ran ? why + why_scan : FirstLine(error));
		if (ran) {
			Say("   " + fn + " calls: " + std::to_string(record.view->reads.size()) + ", first " + record.view->reads.front());
		}
	}
	{
		// Session macros named as the called functions change nothing: every call is looked up in the system catalog.
		Rows before, after;
		std::string error, why;
		qod::SearchCallRecord r1, r2;
		auto ran1 = SearchView(client, Search(pages, "'w1'"), before, r1, error);
		bool shadowed =
		    Exec(client, "CREATE TEMP MACRO ducklake_current_snapshot(a) AS TABLE SELECT 999::UBIGINT AS id", "shadow 1") &&
		    Exec(client,
		         "CREATE TEMP MACRO ducklake_list_files(a, b, schema := NULL, snapshot_version := NULL) AS TABLE SELECT "
		         "'x' AS data_file",
		         "shadow 2") &&
		    Exec(client, "CREATE TEMP MACRO parquet_scan(a) AS TABLE SELECT 1 AS x", "shadow 3") &&
		    Exec(client, "CREATE TEMP MACRO read_parquet(a) AS TABLE SELECT 1 AS x", "shadow 4");
		auto ran2 = SearchView(client, Search(pages, "'w1'"), after, r2, error);
		Check(ran1 && shadowed && ran2 && SameHits(Hits(after), Hits(before), true, why) &&
		          r2.view->reads == r1.view->reads && ReadsOnlyTarget(*r2.view, "lake", "pages", why),
		      "N session macros named ducklake_current_snapshot, ducklake_list_files, parquet_scan, read_parquet change "
		      "neither the calls nor the answer",
		      ran2 ? why : FirstLine(error));
		for (auto m : {"ducklake_current_snapshot", "ducklake_list_files", "parquet_scan", "read_parquet"}) {
			Exec(client, std::string("DROP MACRO TABLE temp.main.") + m, std::string("drop ") + m);
		}
	}
	{
		// A client statement calling the exception's functions is refused by the parse screen to a tenant principal.
		Connection internal(*node.db);
		qod_test::MintSpec spec;
		spec.principal_id = "u-plain";
		spec.exp = now_ms + 60000;
		Exec(internal, ScreenCall("c-adv", qod_test::Prelude(qod_test::MintWith(spec))), "the screen admits the prelude");
		for (auto &sql : {std::string("SELECT * FROM ducklake_list_files('acme_lake', 'pages')"),
		                  std::string("SELECT * FROM ducklake_list_files('lake', 'pages')"),
		                  std::string("SELECT * FROM ducklake_current_snapshot('lake')"),
		                  std::string("SELECT * FROM ducklake_scan('lake', 'pages')")}) {
			Rows rows;
			std::string error;
			auto ran = Try(internal, ScreenCall("c-adv", sql), rows, error);
			Check(!ran && error.find("QoD:") != std::string::npos && error.find("superuser") != std::string::npos,
			      "N the parse screen refuses a tenant's " + sql, ran ? "admitted" : FirstLine(error));
		}
	}
	for (auto &fn : kFunctions) {
		// A search reading pages while other holds a row policy (its wrap in a predicate subquery): the calls name pages.
		auto saved = g_population;
		g_population = "allowed";
		Rows rows;
		std::string error, why, why_scan, why_meta;
		qod::SearchCallRecord record;
		Observed seen;
		auto ran = ObservedSearch(client,
		                          Call(fn,
		                               "SELECT rowid FROM lake.main.pages WHERE id IN (SELECT id FROM (SELECT *, rowid FROM "
		                               "lake.main.other WHERE (id % 2 = 0)) AS other)",
		                               "'w1'"),
		                          rows, record, seen, error);
		g_population = saved;
		bool none_other = ran;
		for (auto &r : ran ? record.view->reads : std::vector<std::string>()) {
			none_other = none_other && r.find("other") == std::string::npos;
		}
		Check(ran && ReadsOnlyTarget(*record.view, "lake", "pages", why) && none_other &&
		          ScansOnlyTarget(seen, *record.view, "lake", "pages", why_scan) &&
		          MetadataOnlyTarget(seen, "__ducklake_metadata_lake", "pages", {"'other'"}, why_meta),
		      fn + " N an input reading pages while other holds a row policy: every call, fallback scan (" +
		          std::to_string(seen.scans.size()) + ") and metadata read (" + std::to_string(seen.metadata.size()) +
		          ") names pages only",
		      ran ? why + why_scan + why_meta : FirstLine(error));
	}
	{
		// A second lake: the calls name its alias only.
		fs::create_directories(node.base / "data2");
		auto data2 = fs::canonical(node.base / "data2").string() + "/";
		bool ok2 = Exec(setup,
		                "ATTACH " + Quote("ducklake:" + (node.base / "meta2.ducklake").string()) + " AS lake2 (DATA_PATH " +
		                    Quote(data2) + ", DATA_INLINING_ROW_LIMIT 10)",
		                "attach lake2") &&
		           Exec(setup, "CREATE TABLE lake2.main.docs (id BIGINT, body VARCHAR)", "lake2 docs") &&
		           Exec(setup, "INSERT INTO lake2.main.docs SELECT i, 'w1 w' || (i % 9) FROM range(0, 500) r(i)",
		                "lake2 docs rows") &&
		           Exec(setup, "DELETE FROM lake2.main.docs WHERE id % 11 = 0", "lake2 delete") &&
		           IndexTable(setup, "docs", {"body"}, data, "lake2");
		for (auto &fn : kFunctions) {
			Rows rows;
			std::string error, why, why_scan, why_meta;
			qod::SearchCallRecord record;
			Observed seen;
			auto ran = ok2 && ObservedSearch(client,
			                                 Call(fn, "SELECT rowid FROM lake2.main.docs", "'w1'", "'lake2.main.docs'"), rows,
			                                 record, seen, error);
			bool lake2_only = ran;
			for (auto &r : ran ? record.view->reads : std::vector<std::string>()) {
				lake2_only = lake2_only && r.find("'lake2'") != std::string::npos;
			}
			Check(ran && !rows.empty() && ReadsOnlyTarget(*record.view, "lake2", "docs", why) && lake2_only &&
			          ScansOnlyTarget(seen, *record.view, "lake2", "docs", why_scan) &&
			          MetadataOnlyTarget(seen, "__ducklake_metadata_lake2", "docs", {"\"__ducklake_metadata_lake\"."}, why_meta),
			      fn + " N a search over a table of a second lake: every call, fallback scan (" +
			          std::to_string(seen.scans.size()) + ") and metadata read (" + std::to_string(seen.metadata.size()) +
			          ") names lake2 only (" + std::to_string(rows.size()) + " rows, through the fallback)",
			      ran ? why + why_scan + why_meta : FirstLine(error));
		}
	}
	CloseNode(node);
	RunVerdictCache();
	return Finish("search_adversarial_test");
}

} // namespace

int main() {
	return Run();
}
