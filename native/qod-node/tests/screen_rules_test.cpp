// Unit tests for screen_rules on DuckDB's own parser: every content rule of the parse screen with the
// nearest statement it must still admit, location extraction (readers, COPY, query() at any depth) and
// the token-count backstop. Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "locations.hpp"
#include "screen_rules.hpp"
#include "sql_script.hpp"

#include <filesystem>
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

void Refused(const std::string &sql, const std::string &needle, const std::string &what) {
	auto outcome = qod::ScreenStatement(*context, sql);
	if (outcome.refusal.empty()) {
		std::cerr << "FAIL: admitted: " << what << std::endl;
		failures++;
	} else if (outcome.refusal.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << outcome.refusal << "\" does not mention \"" << needle << "\""
		          << std::endl;
		failures++;
	}
}

std::vector<qod::Location> Admitted(const std::string &sql, size_t count, const std::string &what) {
	auto outcome = qod::ScreenStatement(*context, sql);
	if (!outcome.refusal.empty()) {
		std::cerr << "FAIL: " << what << ": refused: " << outcome.refusal << std::endl;
		failures++;
	} else if (outcome.facts.locations.size() != count) {
		std::cerr << "FAIL: " << what << ": " << outcome.facts.locations.size() << " locations, expected " << count
		          << std::endl;
		failures++;
	}
	return outcome.facts.locations;
}

void ExpectLocation(const std::vector<qod::Location> &locations, const std::string &given, const std::string &access,
                    const std::string &via, const std::string &what) {
	for (auto &location : locations) {
		if (location.given == given && location.access == access && location.via == via) {
			return;
		}
	}
	std::cerr << "FAIL: " << what << ": no " << access << " of " << given << " via " << via << std::endl;
	failures++;
}

const std::string kBucket = "s3://acme-etl/";

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	context = con.context.get();

	// ---- readers: literal locations are collected, anything else is refused
	auto csv = Admitted("SELECT * FROM read_csv('s3://acme-etl/in/x.csv')", 1, "a reader on an s3 URL");
	ExpectLocation(csv, "s3://acme-etl/in/x.csv", "read", "read_csv", "the reader's location");
	Expect(csv.size() == 1 && csv[0].resolved == csv[0].given, "an s3 URL is sent as given");
	Admitted("FROM read_parquet(['s3://acme-etl/a.parquet', 's3://acme-etl/b.parquet'])", 2, "a list of files");
	Admitted("SELECT * FROM read_csv('s3://acme-etl/x.csv', header = true)", 1, "a named parameter is no location");
	ExpectLocation(Admitted("SELECT * FROM \"READ_CSV\"('s3://acme-etl/x.csv')", 1, "a quoted, upper-case reader"),
	               "s3://acme-etl/x.csv", "read", "read_csv", "names compare in lower case");
	Admitted("SELECT * FROM system.main.read_json('s3://acme-etl/j.json')", 1, "a qualified reader");
	Refused("SELECT * FROM read_csv(getvariable('p'))", "QoD: read_csv needs a literal file name", "a computed name");
	Refused("SELECT * FROM read_csv('s3://acme-etl/' || 'x.csv')", "needs a literal file name", "a concatenation");
	Refused("SELECT * FROM read_parquet(['s3://acme-etl/a.parquet', getvariable('p')])", "needs a literal file name",
	        "a list holding a computed name");
	Refused("SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "a relative path");
	Refused("SELECT * FROM read_csv('https://example.com/x.csv')", "uses a scheme QoD does not admit", "https");
	Refused("SELECT * FROM read_csv('gs://b/x.csv')", "uses a scheme QoD does not admit", "gs");
	Refused("SELECT * FROM read_csv('s3://')", "names no bucket", "no bucket");
	auto local = (std::filesystem::temp_directory_path() / "qod-screen-local.csv").u8string();
	auto local_locations = Admitted("SELECT * FROM read_csv(" + qod::QuoteLiteral(local) + ")", 1, "a local path");
	auto expected_local = qod::ResolveLocalPath(local);
	Expect(local_locations.size() == 1 && expected_local && local_locations[0].resolved == *expected_local,
	       "a local path is sent resolved");
#ifndef _WIN32
	Refused("SELECT * FROM read_text('/data/acme/../../etc/passwd')", "empty, '.' or '..'", "a '..' segment");
#else
	Refused("SELECT * FROM read_text('C:\\data\\acme\\..\\..\\x.txt')", "empty, '.' or '..'", "a '..' segment");
#endif

	// ---- every place a reader can hide in a query
	Refused("SELECT 1 WHERE EXISTS (SELECT * FROM read_csv('x.csv'))", "QoD: x.csv ", "a subquery in WHERE");
	Admitted("WITH c AS (SELECT * FROM read_json('s3://acme-etl/j.json')) SELECT * FROM c", 1, "a CTE");
	Admitted("WITH c AS MATERIALIZED (SELECT 1 AS a) SELECT * FROM c", 0, "a materialized CTE");
	Refused("SELECT * FROM range(3) r JOIN read_csv('x.csv') ON true", "QoD: x.csv ", "a join");
	Refused("SELECT list_transform([1], lambda x: (SELECT count(*) FROM read_csv('x.csv')))", "QoD: x.csv ",
	        "a lambda");
	Refused("VALUES ((SELECT count(*) FROM read_csv('x.csv')))", "QoD: x.csv ", "a VALUES list");
	Refused("SELECT * FROM t AT (VERSION => (SELECT count(*) FROM read_csv('x.csv')))", "QoD: x.csv ", "an AT clause");
	Refused("PIVOT read_csv('x.csv') ON c USING sum(v)", "QoD: x.csv ",
	        "a PIVOT source (2.0 also reads its values through CREATE TYPE ... AS ENUM)");
	Refused("INSERT INTO t SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "INSERT ... SELECT");
	Admitted("INSERT INTO t SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "INSERT from an s3 file");
	Refused("UPDATE t SET a = 1 FROM read_csv('x.csv') s", "QoD: x.csv ", "UPDATE ... FROM");
	Refused("DELETE FROM t USING read_csv('x.csv') s", "QoD: x.csv ", "DELETE ... USING");
	Refused("MERGE INTO t USING read_csv('x.csv') s ON t.a = s.a WHEN MATCHED THEN DELETE", "QoD: x.csv ", "MERGE");
	Admitted("CREATE TABLE t2 AS SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "CREATE TABLE AS");
	Admitted("CREATE VIEW v AS SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "CREATE VIEW");
	Refused("CALL read_text('x.csv')", "QoD: x.csv ", "CALL");
	Refused("SELECT * FROM \"read_csv\" ('x.csv')", "QoD: x.csv ", "a quoted name and a blank before the parenthesis");

	// ---- the token count: a reader where the walk does not go is refused, never admitted unseen
	Refused("ALTER TABLE t ALTER COLUMN a SET DEFAULT (SELECT count(*) FROM read_csv('s3://acme-etl/x.csv'))",
	        "QoD: read_csv is called where QoD cannot check its arguments", "a reader in an ALTER default");
	Refused("CREATE TABLE t3 (a INT CHECK ((SELECT count(*) FROM read_csv('s3://acme-etl/x.csv')) > 0))",
	        "QoD: the statement does not parse: subqueries prohibited in CHECK constraints",
	        "a reader in a CHECK constraint (DuckDB 2.0's parser refuses the subquery)");
	Refused("ALTER TABLE t ALTER COLUMN a SET DEFAULT (SELECT count(*) FROM read_csv/* x */('s3://acme-etl/x.csv'))",
	        "read_csv is called where QoD cannot check its arguments", "a comment between name and parenthesis");
	Admitted("SELECT read_csv FROM t", 0, "a column named like a reader is no call");
	Refused("CREATE MACRO m() AS TABLE SELECT * FROM read_csv('s3://acme-etl/x.csv')",
	        "QoD: CREATE MACRO is reserved to superusers", "a table macro");
	Refused("CREATE MACRO twice(x) AS x * 2", "CREATE MACRO is reserved to superusers", "a scalar macro");

	// ---- table functions in table position: the readers, query functions and classified ones only
	Admitted("SELECT * FROM range(3)", 0, "an admitted table function");
	Refused("SELECT * FROM iceberg_scan('s3://acme-etl/ice/t')",
	        "QoD: table function iceberg_scan is not available to non-superusers",
	        "a superuser function whose bind reads its path");
	Refused("SELECT * FROM quack_query('quack:127.0.0.1:9494', 'SELECT 1')",
	        "table function quack_query is not available to non-superusers", "a function that connects out");
	Refused("SELECT * FROM no_such_function_qod()", "table function no_such_function_qod is not available",
	        "an unclassified function");
	Refused("CALL iceberg_scan('s3://acme-etl/ice/t')", "table function iceberg_scan is not available",
	        "the same through CALL");
	Refused("SELECT * FROM query('SELECT * FROM iceberg_scan(''s3://acme-etl/ice/t'')')",
	        "table function iceberg_scan is not available", "the same inside query()");
	Refused("SELECT * FROM range(1) r JOIN quack_query('quack:x', 'SELECT 1') q ON true",
	        "table function quack_query is not available", "the same in a join");

	// ---- COPY
	ExpectLocation(Admitted("COPY t FROM 's3://acme-etl/in.csv'", 1, "COPY FROM"), "s3://acme-etl/in.csv", "read",
	               "copy_from", "COPY FROM reads");
	ExpectLocation(Admitted("COPY t TO 's3://acme-etl/out.parquet' (FORMAT parquet)", 1, "COPY TO"),
	               "s3://acme-etl/out.parquet", "write", "copy_to", "COPY TO writes");
	Admitted("COPY (SELECT * FROM read_csv('s3://acme-etl/in.csv')) TO 's3://acme-etl/out.csv'", 2,
	         "COPY of a query reading a file");
	ExpectLocation(Admitted("COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg)", 1, "COPY TO FORMAT iceberg"),
	               "s3://acme-lake/ns/t", "write", "copy_to", "FORMAT iceberg is a location like any other");
	Refused("COPY t TO (getvariable('p'))", "QoD: COPY needs a literal file name", "a computed COPY target");
	Refused("COPY t TO $1", "COPY needs a literal file name", "a parameter as COPY target");
	Refused("COPY t TO out.csv", "QoD: out.csv ", "an identifier COPY target is a relative path");
	Refused("COPY t FROM 'x.csv'", "QoD: x.csv ", "a relative COPY source");
	Refused("WITH x AS (COPY (SELECT 1) TO 'rel.csv') SELECT 1", "QoD: rel.csv ", "COPY TO as a CTE body");
	Refused("COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), FILENAME_PATTERN '../x_{i}')",
	        "QoD: COPY option filename_pattern names a path", "a file name pattern leaving the target");
	Refused("COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), FILENAME_PATTERN 'sub/x_{i}')",
	        "COPY option filename_pattern names a path", "a file name pattern with a separator");
	Admitted("COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), FILENAME_PATTERN 'part_{i}')", 1,
	         "a plain file name pattern");
	Admitted("COPY t FROM 's3://acme-etl/in.csv' (DATEFORMAT '%d/%m/%Y')", 1, "a date format is text, not a path");
	Admitted("COPY t TO 's3://acme-etl/o.csv' (HEADER true, DELIMITER '|')", 1, "plain options");

	// ---- statements reserved to superusers, and their nearest admitted neighbors
	Refused("EXPORT DATABASE 's3://acme-etl/dump'", "EXPORT DATABASE is reserved to superusers", "EXPORT");
	Refused("IMPORT DATABASE 's3://acme-etl/dump'", "PRAGMA import_database is reserved to superusers", "IMPORT");
	Refused("PRAGMA enable_profiling", "PRAGMA enable_profiling is reserved to superusers", "a PRAGMA off the list");
	Refused("COPY FROM DATABASE a TO b", "PRAGMA copy_database is reserved to superusers",
	        "COPY FROM DATABASE without SCHEMA or DATA is a PRAGMA");
	Admitted("PRAGMA table_info('t')", 0, "PRAGMA table_info");
	Admitted("PRAGMA version", 0, "PRAGMA version");
	Admitted("PRAGMA show_tables", 0, "PRAGMA show_tables");
	Refused("SELECT * FROM json_execute_serialized_sql('{}')", "json_execute_serialized_sql is reserved to superusers",
	        "json_execute_serialized_sql");
	Refused("PRAGMA json_execute_serialized_sql('{}')", "json_execute_serialized_sql is reserved to superusers",
	        "its PRAGMA form");
	Refused("SELECT * FROM register_external_resource_type('x')", "register_external_resource_type is reserved",
	        "register_external_resource_type");
	Refused("SELECT create_external_resource('x')", "create_external_resource is reserved", "create_external_resource");
	Admitted("SELECT json_serialize_sql('SELECT 1')", 0, "json_serialize_sql runs nothing");
	for (auto describe : {"DESCRIBE t", "DESCRIBE SELECT 1", "SUMMARIZE t", "SHOW t", "SELECT * FROM (DESCRIBE t)",
	                      "SHOW main.t"}) {
		Refused(describe, "reserved to superusers", describe);
	}
	for (auto listing : {"SHOW TABLES", "SHOW DATABASES", "SHOW ALL TABLES", "SHOW TABLES FROM main", "DESCRIBE"}) {
		Admitted(listing, 0, listing);
	}
	Refused("CONNECT", "CONNECT and DISCONNECT are reserved to superusers", "CONNECT");
	Refused("DISCONNECT", "CONNECT and DISCONNECT are reserved to superusers", "DISCONNECT");
	Refused("ATTACH 's3://acme-etl/x.db' AS x", "ATTACH is reserved to superusers", "ATTACH");
	Admitted("DETACH x", 0, "DETACH (the plan decides)");
	for (auto plain : {"LOAD httpfs", "INSTALL httpfs", "FORCE INSTALL httpfs"}) {
		Admitted(plain, 0, plain);
	}
	for (auto load : {"LOAD '/tmp/x.duckdb_extension'", "INSTALL httpfs FROM 'http://example.com'",
	                  "INSTALL httpfs FROM community", "CREATE EXTENSION REPOSITORY r WITH PREFIX 'http://example.com'",
	                  "DROP EXTENSION REPOSITORY r"}) {
		Refused(load, "LOAD or INSTALL", load);
	}

	// ---- query(): its literal is screened like the outer SQL, at any depth up to the limit
	ExpectLocation(Admitted("SELECT * FROM query('SELECT * FROM read_csv(''s3://acme-etl/x.csv'')')", 1, "query()"),
	               "s3://acme-etl/x.csv", "read", "read_csv", "a reader inside query()");
	Admitted("SELECT * FROM query('SELECT * FROM query(''SELECT * FROM read_csv(''''s3://acme-etl/x.csv'''')'')')", 1,
	         "query() inside query()");
	Refused("SELECT * FROM query('SELECT * FROM read_csv(''x.csv'')')", "QoD: x.csv ", "a relative path in query()");
	Refused("SELECT * FROM query(getvariable('q'))", "QoD: query() needs a literal SQL string", "a computed query()");
	Refused("SELECT * FROM query('SELECT 1' || '')", "query() needs a literal SQL string", "a concatenated query()");
	Refused("SELECT * FROM query('SELECT * FROM json_execute_serialized_sql(''{}'')')",
	        "json_execute_serialized_sql is reserved to superusers", "a refused call inside query()");
	Refused("SELECT * FROM query('DESCRIBE t')", "reserved to superusers", "DESCRIBE inside query()");
	std::string nested = "SELECT 1";
	for (int level = 1; level <= qod::kMaxQueryNesting; level++) {
		nested = "SELECT * FROM query(" + qod::QuoteLiteral(nested) + ")";
	}
	Admitted(nested, 0, "query() nested to the limit");
	Refused("SELECT * FROM query(" + qod::QuoteLiteral(nested) + ")", "nests deeper than", "one level past the limit");
	Admitted("SELECT * FROM query_table('t')", 0, "query_table() of a name");
	Admitted("SELECT * FROM query_table(['a', 'b'], true)", 0, "query_table() of a list, by name");
	Refused("SELECT * FROM query_table(getvariable('t'))", "QoD: query_table() needs literal table names",
	        "a computed query_table()");
	Refused("SELECT * FROM query_table('t', getvariable('b'))", "query_table() needs literal table names",
	        "a computed by_name flag");

	// ---- wrappers and multi-statement requests
	Refused("EXPLAIN SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "EXPLAIN");
	Admitted("PREPARE p AS SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "PREPARE");
	Refused("PREPARE p AS DESCRIBE t", "reserved to superusers", "PREPARE of DESCRIBE");
	Admitted("EXECUTE p", 0, "EXECUTE");
	Refused("SELECT 1; SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "the second statement of a request");
	Admitted("SELECT 1; SELECT 2", 0, "two plain statements");
	Admitted("SET VARIABLE p = 42", 0, "SET VARIABLE");
	Refused("SET VARIABLE p = (SELECT count(*) FROM read_csv('x.csv'))", "QoD: x.csv ", "a reader in a SET value");

	// ---- decision 23: secrets, and the settings an S3 request is signed or cached with, are the
	// superusers'
	for (auto secret : {"CREATE SECRET s (TYPE s3, KEY_ID 'k', SECRET 's')",
	                    "CREATE TEMPORARY SECRET s (TYPE s3, KEY_ID 'k', SECRET 's')",
	                    "CREATE PERSISTENT SECRET s (TYPE s3, KEY_ID 'k', SECRET 's')",
	                    "CREATE OR REPLACE SECRET qod_uc_0123abcd IN connection (TYPE s3, KEY_ID 'k', SECRET 's', "
	                    "SCOPE 's3://acme-etl/')",
	                    "CREATE SECRET IF NOT EXISTS s IN memory (TYPE s3)", "DROP SECRET s",
	                    "DROP TEMPORARY SECRET IF EXISTS s", "DROP SECRET qod_uc_0123abcd FROM connection",
	                    "SELECT 1; DROP SECRET s"}) {
		Refused(secret, "QoD: secrets are reserved to superusers", secret);
	}
	// Every storage, DuckDB 2.0's transaction-scoped one included, and every wrapper the walk follows.
	for (auto secret : {"CREATE SECRET s IN connection (TYPE s3)", "CREATE TEMPORARY SECRET s IN connection (TYPE s3)",
	                    "CREATE SECRET s IN transaction (TYPE s3)", "CREATE TEMPORARY SECRET s IN transaction (TYPE s3)",
	                    "CREATE SECRET s IN memory (TYPE s3)", "CREATE PERSISTENT SECRET s IN local_file (TYPE s3)",
	                    "CREATE SECRET s IN some_other_storage (TYPE s3)", "CREATE SECRET s IN \"Connection\" (TYPE s3)",
	                    "CREATE OR REPLACE PERSISTENT SECRET s (TYPE s3)", "CREATE OR REPLACE TEMPORARY SECRET s (TYPE s3)",
	                    "CREATE PERSISTENT SECRET IF NOT EXISTS s (TYPE s3)", "CREATE SECRET (TYPE s3)",
	                    "CREATE SECRET IF NOT EXISTS s IN transaction (TYPE s3)", "DROP PERSISTENT SECRET s",
	                    "DROP SECRET IF EXISTS s", "DROP SECRET s FROM transaction", "DROP SECRET s FROM memory",
	                    "DROP PERSISTENT SECRET s FROM local_file", "DROP SECRET s FROM some_other_storage",
	                    "EXPLAIN CREATE SECRET s (TYPE s3)", "EXPLAIN ANALYZE CREATE SECRET s IN connection (TYPE s3)",
	                    "EXPLAIN DROP SECRET s", "EXPLAIN ANALYZE DROP SECRET s FROM connection",
	                    "CREATE SECRET s (TYPE s3); SELECT 1", "SELECT 1; CREATE SECRET s IN transaction (TYPE s3)",
	                    "SELECT 1; SELECT 2; DROP SECRET s FROM local_file",
	                    "SELECT * FROM query('CREATE SECRET s IN connection (TYPE s3)')",
	                    "SELECT * FROM query('DROP SECRET s')",
	                    "SELECT * FROM query('SELECT * FROM query(''CREATE SECRET s (TYPE s3)'')')",
	                    "SELECT * FROM range(1) WHERE EXISTS (SELECT * FROM query('DROP SECRET s FROM transaction'))"}) {
		Refused(secret, "QoD: secrets are reserved to superusers", secret);
	}
	Refused("PREPARE p AS CREATE SECRET s (TYPE s3)", "QoD: the statement does not parse",
	        "DuckDB 2.0 cannot PREPARE a CREATE SECRET");
	Refused("SELECT 1; SET s3_region = 'eu-west-1'", "SET and RESET of s3_region are reserved",
	        "a SET in a multi-statement request");
	Refused("SELECT * FROM query('SET s3_endpoint = ''evil.example.com''')", "SET and RESET of s3_endpoint are reserved",
	        "a SET inside query()");
	Refused("SET s3_region TO 'eu-west-1'", "SET and RESET of s3_region are reserved", "SET ... TO");
	Refused("RESET SESSION s3_endpoint", "SET and RESET of s3_endpoint are reserved", "RESET SESSION");
	for (auto setting : {"SET s3_access_key_id = 'AKIA'", "SET SESSION s3_secret_access_key = 'x'",
	                     "SET GLOBAL s3_endpoint = 'evil.example.com'", "RESET s3_region",
	                     "SET S3_Region = 'eu-west-1'", "SET s3_url_scheme_aliases = 'x'",
	                     "SET enable_global_s3_configuration = true", "SET enable_external_file_cache = true",
	                     "SET GLOBAL enable_http_metadata_cache = true", "SET parquet_metadata_cache = true",
	                     "SET external_file_cache_spill = true", "SET aws_default_credentials = true",
	                     "SET http_proxy = 'evil:3128'", "EXPLAIN SET s3_region = 'eu-west-1'"}) {
		Refused(setting, "are reserved to superusers", setting);
	}
	Refused("SET S3_Region = 'eu-west-1'", "QoD: SET and RESET of s3_region are reserved to superusers",
	        "the refusal names the setting in lower case");
	Refused("PRAGMA s3_region = 'eu-west-1'", "reserved to superusers", "the PRAGMA spelling of SET");
	Refused("PREPARE p AS SET s3_region = 'eu-west-1'", "QoD: the statement does not parse",
	        "DuckDB 2.0 cannot PREPARE a SET");
	Admitted("SET VARIABLE s3_region = 'x'", 0, "a variable named like a setting");
	Admitted("SET threads = 4", 0, "SET of another setting (the plan decides)");
	Refused("ATTACH 's3://acme-etl/lake.duckdb' AS lake (READ_ONLY)", "ATTACH is reserved to superusers",
	        "ATTACH of an s3 database");

	// ---- left to decision 16: a file in table position is no table for these sessions
	Admitted("SELECT * FROM 's3://acme-etl/x.parquet'", 0, "a string in table position");
	Admitted("SELECT * FROM \"s3://acme-etl/x.parquet\"", 0, "a quoted identifier in table position");

	// ---- a kind=memory pool's own catalog is the plan verdict's to judge (Task 6 covers it for `*` grants)
	Admitted("SELECT * FROM memory.main.x", 0, "a read of memory.main.x");
	Admitted("INSERT INTO memory.main.x VALUES (1)", 0, "a write to memory.main.x");

	// ---- what the parser cannot read is refused
	Refused("SELEC 1", "QoD: the statement does not parse", "a syntax error");

	if (failures == 0) {
		std::cout << "screen_rules_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
