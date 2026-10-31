// Engine test for the S3 environment guard (spec decision 23). CTest runs it with fake AWS_* and
// DUCKDB_S3_* values in this test's environment only (the ENVIRONMENT property in CMakeLists.txt):
// httpfs copies them into GLOBAL s3_* settings when it loads, where any session could read them
// with current_setting() and every unmatched request would sign with them. A database opened without
// the guard shows them (proof the environment reached this process); one opened with it shows none,
// and keeps what a statement sets after the load. A failure names the setting, never a value. Exits
// 77 (CTest's skip) when httpfs cannot be installed on this host.

#include "duckdb.hpp"
#include "node_config.hpp"
#include "sql_script.hpp"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

int failures = 0;

void Expect(bool ok, const std::string &what) {
	if (!ok) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

//! The value of `name` as DuckDB renders it ("NULL" for none), or "error".
std::string Setting(duckdb::Connection &con, const std::string &name) {
	auto result = con.Query("SELECT current_setting(" + qod::QuoteLiteral(name) + ")::VARCHAR");
	if (result->HasError()) {
		return "error";
	}
	auto chunk = result->FetchRaw();
	return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "no row";
}

bool Loads(duckdb::Connection &con, const std::string &extension) {
	return !con.Query("INSTALL " + extension)->HasError() && !con.Query("LOAD " + extension)->HasError();
}

std::string Env(const char *name) {
	auto v = std::getenv(name);
	return v ? std::string(v) : std::string();
}

} // namespace

int main() {
	auto key_id = Env("AWS_ACCESS_KEY_ID");
	if (key_id.empty()) {
		std::cerr << "FAIL: run by ctest: the fake AWS_* environment is missing" << std::endl;
		return 1;
	}
	{
		duckdb::DBConfig plain;
		duckdb::DuckDB db(nullptr, &plain);
		duckdb::Connection con(db);
		if (!Loads(con, "httpfs")) {
			std::cout << "SKIP: httpfs cannot be installed on this host" << std::endl;
			return 77;
		}
		Expect(Setting(con, "s3_access_key_id") == key_id,
		       "without the guard, httpfs copies AWS_ACCESS_KEY_ID into s3_access_key_id");
	}

	duckdb::DBConfig config;
	qod::RegisterS3EnvironmentGuard(config);
	duckdb::DuckDB db(nullptr, &config);
	duckdb::Connection con(db);
	Expect(Loads(con, "httpfs"), "httpfs loads under the guard");
	for (auto name : {"s3_access_key_id", "s3_secret_access_key", "s3_session_token", "s3_region", "s3_endpoint",
	                  "s3_kms_key_id"}) {
		Expect(Setting(con, name) == "NULL", std::string(name) + " still carries the environment's value");
	}
	Expect(Setting(con, "s3_use_ssl") == "true", "s3_use_ssl is back to httpfs's default");
	Expect(Setting(con, "s3_requester_pays") == "false", "s3_requester_pays is back to httpfs's default");

	// The guard acts when httpfs loads, not after: an init statement's own setting stays, and a LOAD of
	// the loaded extension changes nothing.
	Expect(!con.Query("SET GLOBAL s3_region = 'eu-west-3'")->HasError(), "SET GLOBAL s3_region");
	Expect(!con.Query("LOAD httpfs")->HasError(), "LOAD httpfs again");
	Expect(Setting(con, "s3_region") == "eu-west-3", "an operator's s3_region after the load stays");

	// The aws extension, when this host has it, signs no unmatched s3:// URL with the instance's
	// credential chain.
	if (Loads(con, "aws")) {
		Expect(Setting(con, "aws_default_credentials") == "false", "aws_default_credentials after LOAD aws");
	} else {
		std::cout << "note: aws cannot be installed on this host; its default-credentials check is not run"
		          << std::endl;
	}

	if (failures == 0) {
		std::cout << "s3_env_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
