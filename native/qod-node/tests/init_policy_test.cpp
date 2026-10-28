// Unit tests for the tenant initSql allowlist (init_policy, Task 20 Part C) on DuckDB's own parser:
// each allowed form, each refused form of the problem statement and its neighbours, the marker
// sections, --check-init's verdict, and a real run where refused statements are skipped and a trusted
// section runs the same statements unrestricted. Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/extension_helper.hpp"
#include "init_policy.hpp"
#include "init_policy_lists.hpp"
#include "screen_rules.hpp"

#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;
duckdb::ClientContext *context = nullptr;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

void Allowed(const std::string &sql, const std::string &what) {
	duckdb::unique_ptr<duckdb::SQLStatement> parsed;
	auto reason = qod::JudgeInitStatement(*context, sql, parsed);
	if (!reason.empty()) {
		std::cerr << "FAIL: refused: " << what << ": " << reason << std::endl;
		failures++;
	} else if (!parsed) {
		std::cerr << "FAIL: " << what << ": nothing to run" << std::endl;
		failures++;
	}
}

void Refused(const std::string &sql, const std::string &needle, const std::string &what) {
	duckdb::unique_ptr<duckdb::SQLStatement> parsed;
	auto reason = qod::JudgeInitStatement(*context, sql, parsed);
	if (reason.empty()) {
		std::cerr << "FAIL: admitted: " << what << std::endl;
		failures++;
	} else if (reason.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << reason << "\" does not mention \"" << needle << "\"" << std::endl;
		failures++;
	} else if (parsed) {
		std::cerr << "FAIL: " << what << ": a refused statement came back to run" << std::endl;
		failures++;
	}
}

std::string Scalar(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		return "error: " + result->GetError();
	}
	auto chunk = result->Fetch();
	return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "";
}

//! The problem statement's examples and their neighbours: what a tenant admin must not run with the
//! node's privileges. The marker text in one of them is a credential stand-in no output may show.
const std::vector<std::string> kRefusedCorpus = {
    "CREATE SECURE VIEW leak AS SELECT * FROM read_text('/etc/passwd')",
    "CREATE OR REPLACE SECRET s3k (TYPE s3, KEY_ID 'AKIAQODINITMARKER', SECRET 'x')",
    "SET temp_directory = '/tmp/other-tenant'",
    "COPY (SELECT 1) TO '/tmp/qod-init-out.csv'",
    "SELECT * FROM postgres_query('pg', 'SELECT 1')",
    "CREATE VIEW v AS SELECT * FROM range(setval('s', 1))",
    "SET memory_limit = current_setting('max_memory')",
    "INSTALL httpfs FROM 'http://example.com/repo'",
    "LOAD '/tmp/x.duckdb_extension'",
    "ATTACH '/tmp/other.duckdb' AS other",
    "CALL pragma_version()",
    "CREATE MACRO m() AS 1",
    "CREATE TABLE t (a INTEGER)",
    "SELECT 1",
};

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	context = con.context.get();

	// ---- allowed: SET / RESET of a listed setting to a literal, at the default or GLOBAL scope
	Allowed("SET memory_limit = '2GB'", "memory_limit");
	Allowed("SET GLOBAL threads = 4", "GLOBAL threads");
	Allowed("SET threads TO 2", "SET ... TO");
	Allowed("SET preserve_insertion_order = false", "a boolean");
	Allowed("SET default_null_order = nulls_last", "a bare word, which the parser makes a string");
	Allowed("SET TimeZone = 'UTC'", "a setting name in another case");
	Allowed("SET TIME ZONE 'UTC'", "SET TIME ZONE");
	Allowed("RESET memory_limit", "RESET");
	Allowed("RESET GLOBAL threads", "RESET GLOBAL");
	Allowed("PRAGMA threads = 4", "PRAGMA as an assignment, which the parser makes a SET");
	// ---- allowed: a read-only PRAGMA with literal arguments
	Allowed("PRAGMA version", "PRAGMA version");
	Allowed("PRAGMA table_info('t')", "a literal PRAGMA argument");
	// ---- allowed: INSTALL / LOAD of a listed extension by bare name
	Allowed("INSTALL httpfs", "INSTALL httpfs");
	Allowed("LOAD httpfs", "LOAD httpfs");
	Allowed("FORCE INSTALL spatial", "FORCE INSTALL");
	Allowed("INSTALL postgres", "an alias DuckDB resolves (postgres_scanner)");
	Allowed("LOAD 'json'", "a quoted bare name");

	// ---- refused
	Refused(kRefusedCorpus[0], "CREATE is not allowed", "CREATE SECURE VIEW over a host file");
	Refused(kRefusedCorpus[1], "CREATE is not allowed", "CREATE SECRET");
	Refused(kRefusedCorpus[2], "temp_directory are not on the tenant initSql allowlist", "SET temp_directory");
	Refused(kRefusedCorpus[3], "COPY is not allowed", "COPY ... TO");
	Refused(kRefusedCorpus[4], "SELECT is not allowed", "postgres_query");
	Refused(kRefusedCorpus[5], "CREATE is not allowed", "a bind-time setval in a view");
	Refused(kRefusedCorpus[6], "must be a single literal", "a computed SET value");
	Refused(kRefusedCorpus[7], "no FROM", "INSTALL ... FROM a URL");
	Refused(kRefusedCorpus[8], "never a path or a URL", "LOAD of a file");
	Refused(kRefusedCorpus[9], "ATTACH is not allowed", "ATTACH");
	Refused(kRefusedCorpus[10], "CALL is not allowed", "CALL");
	Refused(kRefusedCorpus[11], "CREATE is not allowed", "CREATE MACRO");
	Refused(kRefusedCorpus[12], "CREATE is not allowed", "CREATE TABLE");
	Refused(kRefusedCorpus[13], "SELECT is not allowed", "SELECT");
	Refused("SET memory_limit = '2' || 'GB'", "must be a single literal", "an expression");
	Refused("SET memory_limit = (SELECT '2GB')", "must be a single literal", "a subquery");
	Refused("SET SESSION threads = 2", "default or the GLOBAL scope", "SESSION scope");
	Refused("SET VARIABLE x = 1", "default or the GLOBAL scope", "a variable");
	Refused("SET s3_access_key_id = 'AKIAQODINITMARKER'", "are not on the tenant initSql allowlist", "a secret");
	Refused("SET secret_directory = '/tmp'", "are not on the tenant initSql allowlist", "a reserved setting");
	Refused("SET extension_directory = '/tmp'", "are not on the tenant initSql allowlist", "a path setting");
	Refused("SET home_directory = '/tmp'", "are not on the tenant initSql allowlist", "home_directory");
	Refused("SET allow_community_extensions = true", "are not on the tenant initSql allowlist", "a superuser setting");
	Refused("RESET temp_directory", "are not on the tenant initSql allowlist", "RESET of an unlisted setting");
	Refused("USE memory.main", "schema are not on the tenant initSql allowlist", "USE, a SET of schema");
	Refused("PRAGMA enable_profiling", "not one of the read-only listings", "a PRAGMA off the list");
	Refused("PRAGMA table_info(current_setting('x'))", "PRAGMA arguments must be literals", "a computed argument");
	Refused("INSTALL h3 FROM community", "no FROM", "a community extension");
	Refused("INSTALL httpfs VERSION 'v1.0.0'", "VERSION", "a pinned version");
	Refused("INSTALL motherduck", "not on the tenant initSql allowlist", "an extension off the list");
	Refused("LOAD 'https://example.com/x.duckdb_extension'", "never a path or a URL", "LOAD of a URL");
	Refused("CHECKPOINT", "CALL is not allowed", "CHECKPOINT, a CALL in DuckDB 2.0");
	Refused("VACUUM", "VACUUM is not allowed", "VACUUM");
	Refused("EXPORT DATABASE '/tmp/x'", "EXPORT is not allowed", "EXPORT");
	Refused("PREPARE p AS SELECT 1", "PREPARE is not allowed", "PREPARE");
	Refused("INSERT INTO t VALUES (1)", "INSERT is not allowed", "DML");
	Refused("SELEC 'AKIAQODINITMARKER'", "the statement does not parse", "a parse error");
	Refused("SET threads = 1; SET threads = 2", "more than one statement", "two statements in one text");
	{
		duckdb::unique_ptr<duckdb::SQLStatement> parsed;
		auto reason = qod::JudgeInitStatement(*context, "SELEC 'AKIAQODINITMARKER'", parsed);
		Expect(reason.find("AKIAQODINITMARKER") == std::string::npos, "a refusal quotes no part of the text");
		Expect(qod::JudgeInitStatement(*context, "-- only a comment", parsed).empty() && !parsed,
		       "a comment holds nothing to judge or run");
	}

	// ---- the allowlist's lists: every setting reaches DuckDB's own name, no reserved one; every
	// extension is one of DuckDB's known extensions at the linked release
	for (auto name : qod::kInitSettings) {
		Expect(!qod::IsReservedSessionSetting(name), std::string("a reserved setting is listed: ") + name);
		duckdb::Value value;
		auto lookup = duckdb::DBConfig::GetConfig(*context).GetOptionByName(name);
		auto known = lookup != nullptr || context->TryGetCurrentSetting(name, value);
		Expect(known, std::string("an init setting DuckDB does not know: ") + name);
	}
	for (auto name : qod::kInitExtensions) {
		bool known = false;
		for (duckdb::idx_t i = 0; i < duckdb::ExtensionHelper::DefaultExtensionCount(); i++) {
			known = known || name == std::string(duckdb::ExtensionHelper::GetDefaultExtension(i).name);
		}
		Expect(known, std::string("an init extension is not one of DuckDB's known extensions: ") + name);
	}

	// ---- sections: marker lines only, before any tokenizing, failing closed
	{
		auto sections = qod::SplitInitSections("SET a = 1;\n-- qod:untrusted-begin database\nSELECT 'x\n"
		                                       "-- qod:untrusted-end\r\nSET b = 2;\n  -- qod:untrusted-begin pool  \n"
		                                       "SET c = 3;\n");
		Expect(sections.size() == 4, "four sections");
		if (sections.size() == 4) {
			Expect(sections[0].trusted && sections[0].sql == "SET a = 1;\n", "a trusted head");
			Expect(!sections[1].trusted && sections[1].label == "database" && sections[1].sql == "SELECT 'x\n",
			       "an unterminated string stays inside its section");
			Expect(sections[2].trusted && sections[2].sql == "SET b = 2;\n", "a CR before the newline still ends it");
			Expect(!sections[3].trusted && sections[3].label == "pool", "a section never closed runs untrusted");
		}
		auto nested = qod::SplitInitSections("-- qod:untrusted-begin pool\n-- qod:untrusted-begin\nSELECT 1;\n"
		                                     "-- qod:untrusted-end\nSELECT 2;\n-- qod:untrusted-end\nSELECT 3;\n");
		Expect(nested.size() == 2 && !nested[0].trusted && nested[0].label == "pool" &&
		           nested[0].sql == "SELECT 1;\n" && nested[1].trusted && nested[1].sql == "SELECT 2;\nSELECT 3;\n",
		       "a begin line inside a section keeps it untrusted; a stray end line closes nothing");
		auto labelled = qod::SplitInitSections("-- qod:untrusted-begin Pool\nSELECT 1;\n");
		Expect(labelled.size() == 1 && labelled[0].trusted, "a begin line with an odd label is no marker");
		Expect(qod::SplitInitSections("-- qod:untrusted-begin\n\n-- qod:untrusted-end\n").empty(),
		       "a blank section is dropped");
	}

	// ---- --check-init's verdict
	{
		auto verdict = qod::CheckInit(*context, "SET threads = 2;\nSELECT 1;\n-- note\n;\nINSTALL motherduck;");
		Expect(verdict.statements == 4, "four statements counted");
		Expect(verdict.refused.size() == 2 && verdict.refused[0].first == 2 && verdict.refused[1].first == 4,
		       "statements 2 and 4 refused");
		auto json = qod::InitVerdictJson(verdict);
		Expect(json.rfind("{\"refused\":[{\"reason\":\"SELECT is not allowed", 0) == 0,
		       "the verdict is canonical JSON: " + json);
		Expect(json.find("\"statement\":2}") != std::string::npos && json.find("\"statements\":4}") != std::string::npos,
		       "positions and the count: " + json);
		std::string corpus;
		for (auto &sql : kRefusedCorpus) {
			corpus += sql + ";\n";
		}
		auto all = qod::CheckInit(*context, corpus);
		Expect(all.statements == kRefusedCorpus.size() && all.refused.size() == kRefusedCorpus.size(),
		       "every statement of the refused corpus is refused");
		Expect(qod::InitVerdictJson(all).find("AKIAQODINITMARKER") == std::string::npos,
		       "the verdict quotes no statement text");
	}

	// ---- a real run: an untrusted section skips what it refuses and runs the rest; the same SQL in a
	// trusted section runs unrestricted
	{
		duckdb::DuckDB run_db(nullptr);
		duckdb::Connection run(run_db);
		auto skipped = qod::RunInit(run, "-- qod:untrusted-begin database\n"
		                                 "SET memory_limit = '1GB';\n"
		                                 "CREATE TABLE tenant_made AS SELECT 1 AS v;\n"
		                                 "CREATE SECURE VIEW tenant_leak AS SELECT * FROM range(3);\n"
		                                 "SET threads = 3;\n"
		                                 "-- qod:untrusted-end\n");
		Expect(skipped == 2, "two statements skipped, got " + std::to_string(skipped));
		Expect(Scalar(run, "SELECT current_setting('memory_limit')") == "953.6 MiB",
		       "the allowed SET took effect: " + Scalar(run, "SELECT current_setting('memory_limit')"));
		Expect(Scalar(run, "SELECT current_setting('threads')") == "3", "the statement after a refused one ran");
		Expect(Scalar(run, "SELECT count(*) FROM duckdb_tables() WHERE table_name = 'tenant_made'") == "0",
		       "a refused CREATE TABLE did not run");
		Expect(Scalar(run, "SELECT count(*) FROM duckdb_views() WHERE view_name = 'tenant_leak'") == "0",
		       "a refused CREATE SECURE VIEW did not run");
		auto trusted = qod::RunInit(run, "CREATE TABLE trusted_made AS SELECT 1 AS v;\n"
		                                 "CREATE SECURE VIEW trusted_view AS SELECT * FROM range(3);\n");
		Expect(trusted == 0, "a trusted section runs unrestricted");
		Expect(Scalar(run, "SELECT count(*) FROM duckdb_views() WHERE view_name = 'trusted_view'") == "1",
		       "the trusted secure view exists");
	}

	if (failures > 0) {
		std::cerr << failures << " failure(s)" << std::endl;
		return 1;
	}
	std::cout << "init_policy: all checks passed" << std::endl;
	return 0;
}
