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
	Refused("SELECT 1 AS a UNION ALL SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "the right side of a UNION");
	Refused("SELECT * FROM read_csv('x.csv') UNION SELECT 1", "QoD: x.csv ", "the left side of a UNION");
	Refused("SELECT 1 EXCEPT SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "EXCEPT");
	Refused("SELECT 1 INTERSECT SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "INTERSECT");
	ExpectLocation(Admitted("SELECT 1 AS a UNION ALL BY NAME SELECT * FROM read_parquet('s3://acme-etl/u.parquet')", 1,
	                        "a UNION BY NAME reading an s3 file"),
	               "s3://acme-etl/u.parquet", "read", "read_parquet", "the UNION's location");
	Refused("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r, read_csv('x.csv') WHERE n < 3) "
	        "SELECT * FROM r",
	        "QoD: x.csv ", "the recursive side of a recursive CTE");
	Refused("WITH RECURSIVE r(n) AS (SELECT count(*) FROM read_csv('x.csv') UNION ALL SELECT n + 1 FROM r WHERE n < 3) "
	        "SELECT * FROM r",
	        "QoD: x.csv ", "the anchor of a recursive CTE");
	Refused("WITH RECURSIVE r(n) USING KEY (n) AS (SELECT 1 UNION SELECT n + 1 FROM r JOIN read_csv('x.csv') ON true) "
	        "SELECT * FROM r",
	        "QoD: x.csv ", "a recursive CTE with USING KEY");
	ExpectLocation(Admitted("WITH RECURSIVE r(n) AS (SELECT count(*) FROM read_json('s3://acme-etl/r.json') UNION ALL "
	                        "SELECT n + 1 FROM r WHERE n < 3) SELECT * FROM r",
	                        1, "a recursive CTE reading an s3 file"),
	               "s3://acme-etl/r.json", "read", "read_json", "the recursive CTE's location");
	Admitted("WITH c AS MATERIALIZED (SELECT 1 AS a) SELECT * FROM c", 0, "a materialized CTE");
	Refused("SELECT * FROM range(3) r JOIN read_csv('x.csv') ON true", "QoD: x.csv ", "a join");
	Refused("SELECT list_transform([1], lambda x: (SELECT count(*) FROM read_csv('x.csv')))", "QoD: x.csv ",
	        "a lambda");
	Refused("VALUES ((SELECT count(*) FROM read_csv('x.csv')))", "QoD: x.csv ", "a VALUES list");
	Refused("SELECT * FROM t AT (VERSION => (SELECT count(*) FROM read_csv('x.csv')))",
	        "QoD: read_csv is not allowed in an AT clause", "a reader in an AT clause (a bind-time slot)");
	Refused("PIVOT read_csv('x.csv') ON c USING sum(v)", "QoD: x.csv ",
	        "a PIVOT source (2.0 also reads its values through CREATE TYPE ... AS ENUM)");
	Refused("INSERT INTO t SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "INSERT ... SELECT");
	Admitted("INSERT INTO t SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "INSERT from an s3 file");
	Refused("UPDATE t SET a = 1 FROM read_csv('x.csv') s", "QoD: x.csv ", "UPDATE ... FROM");
	Refused("DELETE FROM t USING read_csv('x.csv') s", "QoD: x.csv ", "DELETE ... USING");
	Refused("MERGE INTO t USING read_csv('x.csv') s ON t.a = s.a WHEN MATCHED THEN DELETE", "QoD: x.csv ", "MERGE");
	Admitted("CREATE TABLE t2 AS SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "CREATE TABLE AS");
	// A view body is walked like a SELECT (CONTRACT Addendum 4 item 13): its readers are located, its own
	// bind-time slots get the slot rule.
	ExpectLocation(Admitted("CREATE VIEW v AS SELECT * FROM read_csv('s3://acme-etl/x.csv')", 1, "CREATE VIEW"),
	               "s3://acme-etl/x.csv", "read", "read_csv", "a view's reader is a location like any other");
	Refused("CREATE VIEW v AS SELECT * FROM read_csv('x.csv')", "QoD: x.csv ", "a view's reader on a relative path");
	Admitted("CREATE VIEW v AS SELECT upper(a) FROM t", 0, "CREATE VIEW over a table");
	Refused("CALL read_text('x.csv')", "QoD: x.csv ", "CALL");
	Refused("SELECT * FROM \"read_csv\" ('x.csv')", "QoD: x.csv ", "a quoted name and a blank before the parenthesis");

	// ---- defaults, CHECK constraints and SET DEFAULT are walked (Addendum 4 item 15): a reader there is
	// judged like anywhere; the token count still backs the walk up
	ExpectLocation(Admitted("ALTER TABLE t ALTER COLUMN a SET DEFAULT (SELECT count(*) FROM "
	                        "read_csv('s3://acme-etl/x.csv'))",
	                        1, "a reader in an ALTER default"),
	               "s3://acme-etl/x.csv", "read", "read_csv", "is located");
	Refused("ALTER TABLE t ALTER COLUMN a SET DEFAULT (SELECT count(*) FROM read_csv('x.csv'))", "QoD: x.csv ",
	        "a relative reader in an ALTER default");
	Refused("CREATE TABLE t3 (a INT CHECK ((SELECT count(*) FROM read_csv('s3://acme-etl/x.csv')) > 0))",
	        "QoD: the statement does not parse: subqueries prohibited in CHECK constraints",
	        "a reader in a CHECK constraint (DuckDB 2.0's parser refuses the subquery)");
	Admitted("ALTER TABLE t ALTER COLUMN a SET DEFAULT (SELECT count(*) FROM read_csv/* x */('s3://acme-etl/x.csv'))",
	         1, "a comment between name and parenthesis");
	Admitted("SELECT read_csv FROM t", 0, "a column named like a reader is no call");
	Admitted("SELECT * FROM t AS read_csv(a)", 0, "an alias with a column list spelled like a reader is no call");
	Admitted("WITH read_csv(a) AS (SELECT 1) SELECT * FROM read_csv", 0, "nor a CTE with column names");
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

	// ---- C1: a COPY option DuckDB may read as a path is literal, both directions; a computed one could
	// spell any path, as the binder evaluates it. FILENAME_PATTERN and FILE_EXTENSION take one string
	// (I2), so every computed form of them is refused as not one.
	const std::string to_out = "COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), ";
	for (auto computed : {"FILENAME_PATTERN concat('..', '/', '..', '/pwn_{i}'))",
	                      "FILENAME_PATTERN ('..' || '/x_{i}'))", "FILENAME_PATTERN '../../x_{i}'::VARCHAR)",
	                      "FILENAME_PATTERN CAST('x_{i}' AS VARCHAR))", "FILENAME_PATTERN lower('X_{I}'))",
	                      "FILENAME_PATTERN getvariable('p'))", "FILENAME_PATTERN $1)",
	                      "FILE_EXTENSION concat('csv', '/../x'))", "FILE_EXTENSION ('pa' || 'rquet'))",
	                      "FILE_EXTENSION 'csv'::VARCHAR)", "FILENAME_PATTERN [concat('..', '/x')])",
	                      "FILENAME_PATTERN system.main.concat('x_', '{i}'))"}) {
		Refused(to_out + computed, "needs one literal string", std::string("a computed option: ") + computed);
	}
	Refused(to_out + "FILENAME_PATTERN (SELECT 'x_{i}'))", "QoD: the statement does not parse",
	        "a subquery as an option value (DuckDB 2.0's parser refuses it)");
	Refused("COPY t TO 's3://acme-etl/o.csv' (FORMAT csv, NO_SUCH_OPTION concat('..', '/', 'x_{i}'))",
	        "QoD: COPY option no_such_option is computed, so QoD cannot check that it names no path",
	        "the refusal names the option");
	Refused("COPY t TO 's3://acme-etl/o.csv' (FORMAT concat('cs', 'v'))", "COPY option format is computed",
	        "a computed FORMAT");
	Refused("COPY t FROM 's3://acme-etl/in.csv' (REJECTS_TABLE concat('a', 'b'))",
	        "COPY option rejects_table is computed", "COPY FROM: a computed option off the list");
	Refused("COPY t FROM 's3://acme-etl/in.json' (FORMAT json, COLUMNS {'a': concat('INT', 'EGER')})",
	        "COPY option columns is computed", "COPY FROM: a computed leaf in a struct option");
	Refused("COPY t FROM 's3://acme-etl/in.json' (FORMAT json, NO_SUCH_OPTION concat('..', '/x'))",
	        "COPY option no_such_option is computed", "an option nobody listed (an extension's)");
	for (auto escape : {"FILENAME_PATTERN '../x_{i}')", "FILENAME_PATTERN 'sub/x_{i}')",
	                    "FILENAME_PATTERN '..\\x_{i}')", "FILENAME_PATTERN \"../x_{i}\")",
	                    "FILE_EXTENSION 'csv/../../x')", "FILE_EXTENSION '../x')", "FILE_EXTENSION \"a/b\")",
	                    // The parser keeps a qualified name's last part, as text.
	                    "FILENAME_PATTERN \"x\".\"../y_{i}\")"}) {
		Refused(to_out + escape, "names a path", std::string("a literal escape: ") + escape);
	}
	Refused("COPY t FROM 's3://acme-etl/in.csv' (REJECTS_TABLE 'x/../y')", "COPY option rejects_table names a path",
	        "COPY FROM: a literal path in an option off the list");
	Refused("COPY t FROM 's3://acme-etl/in.json' (FORMAT json, NO_SUCH_OPTION '../x')",
	        "COPY option no_such_option names a path", "an unlisted option's literal path");
	for (auto admitted : {"FILENAME_PATTERN 'part_{i}')", "FILENAME_PATTERN \"part_{uuid}\")",
	                      "FILENAME_PATTERN 'part_{i}', FILE_EXTENSION 'pq')", "FILE_EXTENSION pq)",
	                      "PER_THREAD_OUTPUT, OVERWRITE_OR_IGNORE)", "WRITE_PARTITION_COLUMNS true)",
	                      "PARTITION_PATH concat('a=', a))", "ORDER_BY (a DESC))", "ROW_GROUP_SIZE 100000)",
	                      "ROW_GROUP_SIZE 100 * 1000)", "FILE_SIZE_BYTES '10MB')", "COMPRESSION zstd)",
	                      "COMPRESSION_LEVEL 3)", "KV_METADATA {'mime': 'application/json'})",
	                      "FIELD_IDS {'a': 1})", "CODEC 'snappy')"}) {
		Admitted(to_out + admitted, 1, std::string("a legitimate option: ") + admitted);
	}
	Admitted("COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a, b))", 1, "partitioned by two columns");
	Admitted("COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY a)", 1, "partitioned by one column");
	Admitted("COPY t TO 's3://acme-etl/o.csv' (FORMAT csv, FORCE_QUOTE *, NULL 'n/a', PREFIX '//', DATEFORMAT '%d/%m')",
	         1, "text options holding slashes");
	Admitted("COPY t TO 's3://acme-etl/o.csv' (FORMAT csv, FORCE_QUOTE (a, b), QUOTE '\"', ESCAPE '\\')", 1,
	         "column lists and a backslash escape");
	Admitted("COPY t TO 's3://acme-etl/o.json' (FORMAT json, ARRAY true)", 1, "a json writer option");
	Admitted("COPY t FROM 's3://acme-etl/in.csv' (FORMAT csv, AUTO_DETECT false, SKIP 1, FORCE_NOT_NULL (a))", 1,
	         "COPY FROM options");
	Admitted("COPY t FROM 's3://acme-etl/in.json' (FORMAT json, COLUMNS {'a': 'INTEGER'})", 1,
	         "COPY FROM: a literal struct option");
	Admitted("COPY t FROM 's3://acme-etl/in.csv' (REJECTS_TABLE 'rejects')", 1, "a literal rejects table");

	// ---- I2: FILENAME_PATTERN and FILE_EXTENSION take one string; any other literal is judged by the
	// value the binder takes, so a hex or bit literal by its bytes, not its digits
	for (auto shaped : {"FILENAME_PATTERN [X'2F2E2E2F2E2E2F78'])", "FILENAME_PATTERN X'2F2E2E2F78')",
	                    "FILENAME_PATTERN ['x', X'2F2E2E2F2E2E2F78'])", "FILENAME_PATTERN [B'00101111'])",
	                    "FILENAME_PATTERN B'00101111')", "FILENAME_PATTERN {'k': X'2F2E2E2F78'})",
	                    "FILENAME_PATTERN ('x_{i}', 'y'))", "FILENAME_PATTERN ['x_{i}'])", "FILENAME_PATTERN 1)",
	                    "FILENAME_PATTERN)", "FILE_EXTENSION [X'2F2E2E2F78'])", "FILE_EXTENSION X'2F2E2E2F78')",
	                    "FILE_EXTENSION ['csv', X'2F2E2E2F78'])", "FILE_EXTENSION {'e': 'csv'})"}) {
		Refused(to_out + shaped, "needs one literal string", std::string("not one string: ") + shaped);
	}
	Refused(to_out + "FILENAME_PATTERN [X'2F2E2E2F2E2E2F78'])",
	        "QoD: COPY option filename_pattern needs one literal string", "the refusal names the option");
	Refused("COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, FILENAME_PATTERN [X'2F2E2E2F78'])",
	        "COPY option filename_pattern needs one literal string", "under any format");
	for (auto decoded : {"REJECTS_TABLE [X'2F2E2E2F78'])", "REJECTS_TABLE ['r', X'2E2E'])",
	                     "NO_SUCH_OPTION {'k': X'2F2E2E2F78'})", "NO_SUCH_OPTION ('a', X'5C78'))"}) {
		Refused("COPY t FROM 's3://acme-etl/in.csv' (" + std::string(decoded), "names a path",
		        std::string("a nested hex literal, by its bytes: ") + decoded);
	}
	Admitted("COPY t FROM 's3://acme-etl/in.csv' (NO_SUCH_OPTION [X'6162', B'0101'])", 1,
	         "hex and bit literals spelling no path");

	// ---- M5: the allowlist holds for the core formats only; under any other, every option is literal
	for (auto strict : {"COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, COMPRESSION concat('zs', 'td'))",
	                    "COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, PARTITION_PATH concat('a=', a))",
	                    "COPY t TO 's3://acme-lake/ns/t' (FORMAT 'ICEBERG', ROW_GROUP_SIZE 100 * 1000)",
	                    "COPY t TO 's3://acme-etl/o.xlsx' (ROW_GROUP_SIZE 100 * 1000)",
	                    "COPY t FROM 's3://acme-etl/in.xlsx' (HEADER concat('tr', 'ue'))"}) {
		Refused(strict, "is computed, so QoD cannot check", strict);
	}
	for (auto strict : {"COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, PREFIX '../x')",
	                    "COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, DATEFORMAT '%d/%m/%Y')",
	                    "COPY t TO 's3://acme-etl/o.xlsx' (COMPRESSION 'a/b')"}) {
		Refused(strict, "names a path", strict);
	}
	for (auto core : {"COPY t TO 's3://acme-etl/o.parquet' (ROW_GROUP_SIZE 100 * 1000)",
	                  "COPY t TO 's3://acme-etl/o.csv.gz' (DATEFORMAT '%d/%m/%Y', HEADER)",
	                  "COPY t TO 's3://acme-etl/o.json' (DATEFORMAT '%d/%m/%Y')",
	                  "COPY t TO 's3://acme-etl/o.ndjson' (DATEFORMAT '%d/%m/%Y')",
	                  "COPY t TO 's3://acme-etl/out/' (PARTITION_BY (a), PARTITION_PATH concat('a=', a))",
	                  "COPY t TO 's3://acme-etl/o.bin' (FORMAT blob, COMPRESSION 'gzip')",
	                  "COPY t TO 's3://acme-etl/o' (FORMAT 'PARQUET', ROW_GROUP_SIZE 100 * 1000)"}) {
		Admitted(core, 1, core);
	}
	Admitted("COPY t TO 's3://acme-lake/ns/t' (FORMAT iceberg, COMPRESSION 'zstd', PARTITION_BY (a))", 1,
	         "literal options under another format");

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
	for (auto secret : {"CREATE SECRET s IN connection (TYPE s3)",
	                    "CREATE TEMPORARY SECRET s IN connection (TYPE s3)", "CREATE SECRET s IN transaction (TYPE s3)",
	                    "CREATE TEMPORARY SECRET s IN transaction (TYPE s3)", "CREATE SECRET s IN memory (TYPE s3)",
	                    "CREATE PERSISTENT SECRET s IN local_file (TYPE s3)",
	                    "CREATE SECRET s IN some_other_storage (TYPE s3)",
	                    "CREATE SECRET s IN \"Connection\" (TYPE s3)",
	                    "CREATE OR REPLACE PERSISTENT SECRET s (TYPE s3)",
	                    "CREATE OR REPLACE TEMPORARY SECRET s (TYPE s3)",
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
	                    "SELECT * FROM range(1) WHERE EXISTS "
	                    "(SELECT * FROM query('DROP SECRET s FROM transaction'))"}) {
		Refused(secret, "QoD: secrets are reserved to superusers", secret);
	}
	Refused("PREPARE p AS CREATE SECRET s (TYPE s3)", "QoD: the statement does not parse",
	        "DuckDB 2.0 cannot PREPARE a CREATE SECRET");
	Refused("SELECT 1; SET s3_region = 'eu-west-1'", "SET and RESET of s3_region are reserved",
	        "a SET in a multi-statement request");
	Refused("SELECT * FROM query('SET s3_endpoint = ''evil.example.com''')",
	        "SET and RESET of s3_endpoint are reserved", "a SET inside query()");
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
	// I1: how a request is signed, verified, routed or cached, in every form a session can spell it
	for (std::string name :
	     {"enable_server_cert_verification", "ca_cert_file", "extra_http_headers", "httpfs_enable_credential_refresh",
	      "httpfs_client_implementation", "httpfs_connection_caching", "http_keep_alive", "redact_http_logs",
	      "secret_directory", "allow_persistent_secrets", "allow_unredacted_secrets",
	      // I3, M6
	      "enable_curl_server_cert_verification", "gcs_user_project", "unsafe_disable_etag_checks"}) {
		auto refusal = "QoD: SET and RESET of " + name + " are reserved to superusers";
		for (auto form : {"SET " + name + " = 'x'", "SET SESSION " + name + " = 'x'", "SET GLOBAL " + name + " = 'x'",
		                  "SET " + name + " TO 'x'", "RESET " + name, "RESET SESSION " + name, "RESET GLOBAL " + name,
		                  "EXPLAIN SET SESSION " + name + " = 'x'", "EXPLAIN ANALYZE SET " + name + " = 'x'",
		                  "EXPLAIN RESET " + name, "SELECT 1; SET SESSION " + name + " = 'x'",
		                  "SELECT * FROM query('SET " + name + " = ''x''')", "PRAGMA " + name + " = 'x'"}) {
			Refused(form, refusal, form);
		}
		// DuckDB 2.0 has no SET LOCAL: it refuses it before the screen could reserve anything.
		for (auto form : {"SET LOCAL " + name + " = 'x'", "RESET LOCAL " + name}) {
			Refused(form, "QoD: the statement does not parse", form);
		}
	}
	Admitted("SET SESSION http_timeout = 30", 0, "an HTTP setting that changes no signing, verification or routing");
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

	// ---- Task 9 review C1 (CONTRACT Addendum 4 item 9): (i) a superuser-class function anywhere
	for (auto anywhere : {"SELECT current_setting('memory_limit')",
	                      "SELECT system.main.current_setting('memory_limit')",
	                      "SELECT \"CURRENT_SETTING\"('memory_limit')", "SELECT 'memory_limit'.current_setting()",
	                      "SELECT write_log('x')", "SELECT 1 WHERE quack_check_token('t') IS NOT NULL",
	                      "SELECT json_serialize_plan('SELECT 1')", "SELECT postgres_pin_connection('db')",
	                      "SELECT setval('seq1', 5)", "SELECT * FROM t ORDER BY current_setting('threads')",
	                      "SELECT (SELECT current_setting('threads'))", "INSERT INTO t VALUES (current_setting('x'))",
	                      "UPDATE t SET a = current_setting('x')",
	                      "SELECT * FROM query('SELECT current_setting(''x'')')",
	                      "SELECT list_transform([1], lambda x: current_setting('threads'))",
	                      // where the walk does not go: the token count refuses them too
	                      "ALTER TABLE t ALTER COLUMN a SET DEFAULT current_setting('memory_limit')",
	                      "CREATE TABLE t4 (a VARCHAR DEFAULT current_setting('memory_limit'))",
	                      "CREATE TABLE t5 (a INT CHECK (length(current_setting('threads')) > 0))"}) {
		Refused(anywhere, "is reserved to superusers", anywhere);
	}
	Refused("SELECT current_setting('memory_limit')", "QoD: current_setting is reserved to superusers",
	        "the refusal names the function");
	Admitted("SELECT nextval('seq1'), currval('seq1'), upper('a')", 0,
	         "sequence and allowed functions outside the bind-time slots (the plan shows them)");

	// ---- (ii) the review's reproductions, refused before bind
	Refused("SELECT * FROM range(setval('seq1', 5))", "setval is reserved to superusers", "range(setval(..))");
	Refused("SET default_order = CASE WHEN setval('seq1', 42) > 0 THEN 'ASC' END", "setval is reserved to superusers",
	        "SET ... setval");
	Refused("PIVOT (SELECT 1 AS v) ON v IN (current_setting('temp_directory')) USING count(*)",
	        "current_setting is reserved to superusers", "PIVOT ... IN (current_setting(..))");
	Refused("ALTER TABLE x ADD COLUMN w VARCHAR DEFAULT current_setting('memory_limit')",
	        "current_setting is reserved to superusers", "ADD COLUMN DEFAULT current_setting(..)");
	Refused("ALTER TABLE x ALTER v TYPE VARCHAR USING setval('seq1', 99)::VARCHAR", "setval is reserved to superusers",
	        "ALTER ... TYPE ... USING setval(..)");
	Refused("EXPLAIN EXECUTE p(setval('seq1', 88))", "setval is reserved to superusers",
	        "EXPLAIN EXECUTE p(setval(..))");

	// ---- (ii) one test per bind-time slot: a sequence function, and anything not classed allowed
	struct Slot {
		const char *sql;
		const char *refusal;
	};
	for (auto &slot : std::vector<Slot> {
	         {"SET default_order = CASE WHEN nextval('seq1') > 0 THEN 'ASC' END",
	          "QoD: nextval is not allowed in a SET value"},
	         {"SET SESSION threads = currval('seq1')", "currval is not allowed in a SET value"},
	         {"SELECT * FROM range(nextval('seq1'))", "QoD: nextval is not allowed in a table function's arguments"},
	         {"SELECT * FROM read_csv('s3://acme-etl/x.csv', sample_size = nextval('seq1'))",
	          "nextval is not allowed in a table function's arguments"},
	         {"CALL range(currval('seq1'))", "currval is not allowed in a table function's arguments"},
	         {"EXECUTE p(nextval('seq1'))", "QoD: nextval is not allowed in EXECUTE parameters"},
	         {"EXPLAIN EXECUTE p(currval('seq1'))", "currval is not allowed in EXECUTE parameters"},
	         {"SELECT * FROM t AT (VERSION => nextval('seq1'))", "QoD: nextval is not allowed in an AT clause"},
	         {"SELECT * FROM t AT (TIMESTAMP => now() - to_days(currval('seq1')))",
	          "currval is not allowed in an AT clause"},
	         {"SELECT 1 LIMIT nextval('seq1')", "QoD: nextval is not allowed in LIMIT and OFFSET"},
	         {"SELECT 1 LIMIT 1 OFFSET currval('seq1')", "currval is not allowed in LIMIT and OFFSET"},
	         {"SELECT 1 LIMIT (SELECT count(*) FROM duckdb_tables())",
	          "duckdb_tables is not allowed in LIMIT and OFFSET"},
	         {"PIVOT (SELECT 1 AS v) ON v IN (nextval('seq1')) USING count(*)",
	          "QoD: nextval is not allowed in a PIVOT IN list"},
	         {"SELECT COLUMNS(CASE WHEN nextval('seq1') > 0 THEN 'a' END) FROM t",
	          "QoD: nextval is not allowed in COLUMNS()"},
	         {"SELECT unnest([[1]], max_depth := nextval('seq1'))",
	          "QoD: nextval is not allowed in unnest's parameters"},
	         {"SELECT unnest([[1]], recursive := currval('seq1') > 0)",
	          "currval is not allowed in unnest's parameters"},
	         // a view body's own slots (Addendum 4 item 13)
	         {"CREATE VIEW v AS SELECT * FROM range(setval('seq1', 5))", "QoD: setval is reserved to superusers"},
	         {"CREATE VIEW v AS SELECT * FROM range(nextval('seq1'))",
	          "QoD: nextval is not allowed in a table function's arguments"},
	         {"CREATE VIEW v AS SELECT * FROM t LIMIT currval('seq1')", "currval is not allowed in LIMIT and OFFSET"},
	         {"CREATE VIEW v AS SELECT current_setting('memory_limit') AS m",
	          "QoD: current_setting is reserved to superusers"},
	         {"ALTER TABLE x ADD COLUMN w BIGINT DEFAULT nextval('seq1')",
	          "QoD: nextval is not allowed in ALTER TABLE ADD COLUMN"},
	         // IF NOT EXISTS keeps the plain ALTER, which runs the default itself (transform_alter.cpp:52-60)
	         {"ALTER TABLE x ADD COLUMN IF NOT EXISTS w BIGINT DEFAULT nextval('seq1')",
	          "nextval is not allowed in ALTER TABLE ADD COLUMN"},
	         {"ALTER TABLE x ADD COLUMN w BIGINT DEFAULT (SELECT count(*) FROM duckdb_tables())",
	          "duckdb_tables is not allowed in ALTER TABLE ADD COLUMN"},
	         {"ALTER TABLE x ALTER v TYPE BIGINT USING nextval('seq1')",
	          "QoD: nextval is not allowed in ALTER COLUMN TYPE USING"},
	         {"ALTER TABLE x ALTER COLUMN v SET DATA TYPE BIGINT USING currval('seq1')",
	          "currval is not allowed in ALTER COLUMN TYPE USING"},
	         {"COPY t TO 's3://acme-etl/o.parquet' (ROW_GROUP_SIZE nextval('seq1'))",
	          "QoD: nextval is not allowed in a COPY option"},
	         // a name the screen cannot resolve to a class: a macro, another catalog's function
	         {"SELECT 1 LIMIT my_macro()", "QoD: my_macro is not allowed in LIMIT and OFFSET"},
	         {"SELECT * FROM range(memory.main.upper('a'))", "upper is not allowed in a table function's arguments"},
	         {"SET threads = date_add(1, 2)", "date_add is not allowed in a SET value"},
	         // nor is nesting a way around it
	         {"SELECT * FROM range((SELECT nextval('seq1')))",
	          "nextval is not allowed in a table function's arguments"},
	         {"SELECT 1 LIMIT (SELECT max(n) FROM (SELECT nextval('seq1') AS n))",
	          "nextval is not allowed in LIMIT and OFFSET"}}) {
		Refused(slot.sql, slot.refusal, slot.sql);
	}
	Refused("SELECT 1::DECIMAL(nextval('seq1'), 2)", "QoD:",
	        "a function as a type modifier (DuckDB 2.0's parser refuses it before the type slot could)");

	// ---- allowed functions still pass in every slot
	for (auto allowed : {"SELECT * FROM range(10)", "SELECT * FROM range(5 + 1)", "SELECT * FROM range(abs(-3))",
	                     "SELECT * FROM generate_series(1, 10, 2)", "SELECT 1 LIMIT 5 + 1",
	                     "SELECT 1 LIMIT greatest(2, 3) OFFSET 1", "SET threads = 2 * 2",
	                     "SET default_order = CASE WHEN length('ab') > 0 THEN 'ASC' END",
	                     "EXECUTE p(1 + 1, upper('a'))", "SELECT * FROM t AT (VERSION => 1 + 1)",
	                     "PIVOT (SELECT 1 AS v) ON v IN (1 + 0, 2) USING count(*)",
	                     "SELECT COLUMNS(lower('A')) FROM t", "SELECT unnest([[1]], max_depth := 1 + 1)",
	                     "ALTER TABLE x ADD COLUMN w VARCHAR DEFAULT upper('a')",
	                     "ALTER TABLE x ALTER v TYPE VARCHAR USING upper(v::VARCHAR)",
	                     "SELECT * FROM range(3) LIMIT 2", "SELECT 1::DECIMAL(10, 2)",
	                     "ALTER TABLE x ADD COLUMN IF NOT EXISTS w VARCHAR DEFAULT upper('a')"}) {
		Admitted(allowed, 0, allowed);
	}
	Admitted("COPY t TO 's3://acme-etl/o.parquet' (ROW_GROUP_SIZE 100 * 1000)", 1, "a computed allowed COPY option");
	Admitted("SELECT * FROM read_csv('s3://acme-etl/x.csv', header = true, sample_size = 10 * 10)", 1,
	         "a reader's literal and computed allowed arguments");

	// ---- Task 9 re-review R1 (Addendum 4 item 15): PRAGMA arguments are a bind-time slot
	Refused("PRAGMA table_info(CASE WHEN nextval('s') > 0 THEN 't' END)",
	        "QoD: nextval is not allowed in PRAGMA arguments", "a sequence write in a PRAGMA argument");
	Refused("PRAGMA table_info(current_setting('memory_limit'))", "current_setting is reserved to superusers",
	        "a superuser-class function in a PRAGMA argument");
	Refused("PRAGMA table_info(my_macro())", "my_macro is not allowed in PRAGMA arguments",
	        "an unclassified name in a PRAGMA argument");
	Admitted("PRAGMA table_info('t')", 0, "PRAGMA table_info of a name");
	Admitted("PRAGMA table_info(lower('T'))", 0, "an allowed function in a PRAGMA argument");

	// ---- R2: a table, CTE or alias spelled like a superuser-class function is no call; a call where the
	// walk now goes is refused by the walk
	// SHOW is a reserved word in DuckDB 2.0: a name spelled so is quoted, and the token count unquotes it.
	Refused("SELECT * FROM t AS show(a)", "QoD: the statement does not parse: syntax error at or near \"show\"",
	        "unquoted, DuckDB's own parser refuses it, not the token count");
	for (std::string name : {"platform", "checkpoint", "\"show\"", "collations", "user_agent", "write_log"}) {
		for (auto form : {"INSERT INTO " + name + " (id) VALUES (1)", "INSERT INTO main." + name + " (id) VALUES (1)",
		                  "CREATE TABLE " + name + " (id INT)", "CREATE TABLE IF NOT EXISTS " + name + "(id INT)",
		                  "WITH " + name + "(a) AS (SELECT 1) SELECT * FROM " + name,
		                  "SELECT * FROM t AS " + name + "(a)", "SELECT * FROM range(3) AS " + name + "(a)",
		                  "CREATE VIEW " + name + "(a) AS SELECT 1", "CREATE INDEX i ON " + name + " (id)",
		                  "CREATE TABLE c (id INT REFERENCES " + name + "(id))",
		                  "COPY " + name + " (id) FROM 's3://acme-etl/in.csv'", "SELECT * FROM " + name,
		                  "UPDATE " + name + " SET id = 2", "SELECT " + name + " FROM t"}) {
			auto locations = form.rfind("COPY", 0) == 0 ? 1 : 0;
			Admitted(form, locations, form);
		}
	}
	for (auto refused : {"CREATE TABLE x (v VARCHAR DEFAULT current_setting('memory_limit'))",
	                     "CREATE TABLE x (v VARCHAR, CHECK (current_setting('memory_limit') IS NOT NULL))",
	                     "CREATE TABLE x (v INT CHECK (current_setting('threads') IS NOT NULL))",
	                     "CREATE TABLE x (v VARCHAR GENERATED ALWAYS AS (current_setting('threads')))",
	                     "ALTER TABLE x ALTER COLUMN v SET DEFAULT current_setting('memory_limit')",
	                     "ALTER TABLE x ADD CONSTRAINT c CHECK (current_setting('threads') IS NOT NULL)",
	                     "CREATE TRIGGER tr AFTER INSERT ON x FOR EACH ROW INSERT INTO y VALUES (write_log('x'))",
	                     "CREATE TRIGGER tr AFTER INSERT ON x FOR EACH ROW INSERT INTO y SELECT current_setting('x')",
	                     "CREATE INDEX i ON x (current_setting('threads'))"}) {
		Refused(refused, "is reserved to superusers", refused);
	}
	// The walk refuses them, not the token count: also behind a name that would hide one from the count.
	Refused("WITH current_setting(a) AS (SELECT 1) SELECT * FROM t WHERE current_setting('threads') IS NULL",
	        "QoD: current_setting is reserved to superusers", "a call beside a CTE of the same name");
	// table and schema options: what the catalog takes when the CREATE or ALTER runs
	Refused("CREATE TABLE x (v INT) WITH (o = nextval('s'))", "QoD: nextval is not allowed in table or schema options",
	        "a sequence in a table's options");
	Refused("ALTER TABLE x SET (o = currval('s'))", "currval is not allowed in table or schema options",
	        "a sequence in ALTER TABLE SET options");
	Admitted("CREATE TABLE x (v INT) WITH (o = upper('a'))", 0, "an allowed function in a table's options");

	// ---- what the parser cannot read is refused
	Refused("SELEC 1", "QoD: the statement does not parse", "a syntax error");

	if (failures == 0) {
		std::cout << "screen_rules_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
