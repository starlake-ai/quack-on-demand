// Tests for the catalogs a plan writes (Described::written_catalogs, decision 24): the catalogs whose
// entries it inserts into, updates, deletes from, creates, drops or alters, and those its ATTACH, DETACH
// and COPY FROM DATABASE target, lowercased, as the describer finds them in a bound plan; never part of
// the description. Built with -DQOD_NODE_TESTS=ON, run by CTest.

#include "describe_harness.hpp"
#include "describer.hpp"
#include "duckdb.hpp"
#include "sql_script.hpp"

#include <chrono>
#include <filesystem>
#include <iostream>
#include <set>
#include <string>

namespace {

using qod_test::TheCapture;

int failures = 0;

std::string Joined(const std::set<std::string> &names) {
	std::string out;
	for (auto &name : names) {
		out += (out.empty() ? "" : ",") + name;
	}
	return "[" + out + "]";
}

//! Runs `sql` on `con` and checks the written catalogs of its last described plan (the outermost),
//! whatever the statement then did when it ran.
void ExpectWritten(duckdb::Connection &con, const std::string &sql, const std::set<std::string> &expected) {
	auto &capture = TheCapture();
	capture.described = 0;
	capture.written.clear();
	capture.on = true;
	auto result = con.Query(sql);
	capture.on = false;
	if (capture.described == 0 || !capture.refused.empty()) {
		std::cerr << "FAIL: " << sql << ": not described ("
		          << (capture.refused.empty() && result->HasError() ? result->GetError() : capture.refused) << ")"
		          << std::endl;
		failures++;
	} else if (capture.written != expected) {
		std::cerr << "FAIL: " << sql << ": wrote " << Joined(capture.written) << ", expected " << Joined(expected)
		          << std::endl;
		failures++;
	}
}

//! Runs `sql` on `con` with the capture off; records a failure when it fails.
void Setup(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		std::cerr << "FAIL: setup " << sql << ": " << result->GetError() << std::endl;
		failures++;
	}
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	qod_test::RegisterCapture(db);
	duckdb::Connection con(db);
	TheCapture().principal = qod_test::Root();
	auto dir = std::filesystem::temp_directory_path() /
	           std::filesystem::u8path("qod-written-" +
	                                   std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
	std::filesystem::create_directories(dir);
	auto file = [&](const std::string &name) {
		return qod::QuoteLiteral((dir / std::filesystem::u8path(name)).u8string());
	};
	Setup(con, "ATTACH " + file("ro.duckdb") + " AS ro");
	Setup(con, "CREATE TABLE ro.main.t (v INTEGER)");
	Setup(con, "ATTACH ':memory:' AS \"MixedCase\"");
	Setup(con, "CREATE TABLE \"MixedCase\".main.t (v INTEGER)");
	Setup(con, "CREATE TABLE memory.main.m (v INTEGER)");

	// ---- a read writes nowhere, and a write that reads a catalog writes only its target
	ExpectWritten(con, "SELECT * FROM ro.main.t", {});
	ExpectWritten(con, "INSERT INTO memory.main.m SELECT v FROM ro.main.t", {"memory"});
	ExpectWritten(con, "CREATE TABLE memory.main.c AS SELECT v FROM ro.main.t", {"memory"});

	// ---- writes and DDL name their catalog, lowercased
	ExpectWritten(con, "INSERT INTO ro.main.t VALUES (1)", {"ro"});
	ExpectWritten(con, "UPDATE ro.main.t SET v = 2", {"ro"});
	ExpectWritten(con, "DELETE FROM ro.main.t WHERE v = 3", {"ro"});
	ExpectWritten(con, "INSERT INTO \"MixedCase\".main.t VALUES (1)", {"mixedcase"});
	ExpectWritten(con, "CREATE TABLE ro.main.u (v INTEGER)", {"ro"});
	ExpectWritten(con, "ALTER TABLE ro.main.u ADD COLUMN w INTEGER", {"ro"});
	ExpectWritten(con, "CREATE VIEW ro.main.w AS SELECT 1 AS v", {"ro"});
	ExpectWritten(con, "DROP VIEW ro.main.w", {"ro"});
	ExpectWritten(con, "DROP TABLE ro.main.u", {"ro"});
	ExpectWritten(con, "CREATE SCHEMA ro.s", {"ro"});
	ExpectWritten(con, "DROP SCHEMA ro.s", {"ro"});
	ExpectWritten(con, "COPY FROM DATABASE memory TO ro (SCHEMA)", {"ro"});
	// An unqualified write lands in the catalog its table resolves to, whatever path a USE set.
	Setup(con, "USE ro");
	ExpectWritten(con, "INSERT INTO t VALUES (4)", {"ro"});
	Setup(con, "USE memory");

	// ---- ATTACH and DETACH name the catalog they create, replace or remove
	ExpectWritten(con, "ATTACH ':memory:' AS scratch", {"scratch"});
	ExpectWritten(con, "DETACH scratch", {"scratch"});
	ExpectWritten(con, "ATTACH ':memory:' AS \"UPPER_X\"", {"upper_x"});
	ExpectWritten(con, "ATTACH OR REPLACE ':memory:' AS \"UPPER_X\"", {"upper_x"});
	ExpectWritten(con, "ATTACH IF NOT EXISTS " + file("ro.duckdb") + " AS ro", {"ro"});
	// Without AS, DuckDB names the catalog after the file when it runs (PhysicalAttach::GetData): both
	// names it may derive, with and without the extension prefix (a TYPE option keeps the prefix in the
	// path). The second statement fails when it runs (no such extension): it was described first.
	ExpectWritten(con, "ATTACH IF NOT EXISTS " + file("derived.duckdb"), {"derived"});
	ExpectWritten(con, "ATTACH 'nosuchext:lite.db'", {"lite", "nosuchext:lite"});

	std::error_code ignored;
	std::filesystem::remove_all(dir, ignored);
	if (failures == 0) {
		std::cout << "written_catalogs_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
