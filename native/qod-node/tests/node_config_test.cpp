// Engine tests for the extension policy (spec decision 17): qod-node fixes allow_community_extensions,
// allow_unsigned_extensions and autoinstall_known_extensions in the instance's config before the
// database opens, all three off unless a flag turns one on, and SQL run in the database (the init SQL
// included) cannot turn either allow_* setting on. Built with -DQOD_NODE_TESTS=ON, run by CTest.

#include "duckdb.hpp"
#include "node_config.hpp"
#include "options.hpp"
#include "sql_script.hpp"

#include <filesystem>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Expect(bool ok, const std::string &what) {
	if (!ok) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

const std::vector<std::string> kSettings = {"allow_community_extensions", "allow_unsigned_extensions",
                                            "autoinstall_known_extensions"};

//! The value of `name` as DuckDB renders it, or the error.
std::string Setting(duckdb::Connection &con, const std::string &name) {
	auto result = con.Query("SELECT current_setting(" + qod::QuoteLiteral(name) + ")::VARCHAR");
	if (result->HasError()) {
		return "error: " + result->GetError();
	}
	auto chunk = result->FetchRaw();
	return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "no row";
}

//! The error of `sql` on `con`, empty when it succeeds.
std::string ErrorOf(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	return result->HasError() ? result->GetError() : "";
}

//! The policy the command line `qod-node --list-settings <flags>` asks for.
qod::ExtensionPolicy PolicyOf(const std::vector<std::string> &flags) {
	std::vector<std::string> args = {"qod-node", "--list-settings"};
	args.insert(args.end(), flags.begin(), flags.end());
	return qod::ParseOptions(args).extensions;
}

//! The three settings, in kSettings order, of a database opened under `flags`.
std::vector<std::string> SettingsUnder(const std::vector<std::string> &flags) {
	duckdb::DBConfig config;
	qod::ApplyExtensionPolicy(config, PolicyOf(flags));
	duckdb::DuckDB db(nullptr, &config);
	duckdb::Connection con(db);
	std::vector<std::string> values;
	for (auto &name : kSettings) {
		values.push_back(Setting(con, name));
	}
	return values;
}

std::string Joined(const std::vector<std::string> &values) {
	std::string out;
	for (auto &v : values) {
		out += (out.empty() ? "" : ",") + v;
	}
	return out;
}

void ExpectSettings(const std::vector<std::string> &flags, const std::string &expected, const std::string &what) {
	auto got = Joined(SettingsUnder(flags));
	Expect(got == expected, what + ": got " + got + ", expected " + expected);
}

} // namespace

int main() {
	// Off unless asked for: DuckDB's own defaults turn community extensions and autoinstall on.
	ExpectSettings({}, "false,false,false", "no flag");
	ExpectSettings({"--allow-community-extensions"}, "true,false,false", "--allow-community-extensions alone");
	ExpectSettings({"--allow-unsigned-extensions"}, "false,true,false", "--allow-unsigned-extensions alone");
	ExpectSettings({"--autoinstall-known-extensions"}, "false,false,true", "--autoinstall-known-extensions alone");
	ExpectSettings({"--allow-community-extensions", "--allow-unsigned-extensions", "--autoinstall-known-extensions"},
	               "true,true,true", "all three flags");

	// A flag takes no value: spelled with one, it is an unknown option.
	try {
		PolicyOf({"--allow-community-extensions=true"});
		Expect(false, "--allow-community-extensions=true was accepted");
	} catch (std::invalid_argument &error) {
		Expect(std::string(error.what()).find("unknown option") != std::string::npos,
		       std::string("--allow-community-extensions=true: ") + error.what());
	}

	duckdb::DBConfig config;
	qod::ApplyExtensionPolicy(config, PolicyOf({}));
	duckdb::DuckDB db(nullptr, &config);
	duckdb::Connection con(db);

	// Once the database runs, DuckDB refuses to turn an allow_* setting on, from any statement: the
	// init SQL (pool and database initSql included) cannot undo the policy.
	auto community = ErrorOf(con, "SET allow_community_extensions = true");
	Expect(community.find("Cannot change allow_community_extensions setting while database is running") !=
	           std::string::npos,
	       "SET allow_community_extensions = true: " + community);
	auto unsigned_ext = ErrorOf(con, "SET GLOBAL allow_unsigned_extensions = true");
	Expect(unsigned_ext.find("Cannot change allow_unsigned_extensions setting while database is running") !=
	           std::string::npos,
	       "SET GLOBAL allow_unsigned_extensions = true: " + unsigned_ext);
	Expect(Setting(con, "allow_community_extensions") == "false", "allow_community_extensions after the attempt");
	Expect(Setting(con, "allow_unsigned_extensions") == "false", "allow_unsigned_extensions after the attempt");

	// With autoinstall off a statement needing a known extension that is not installed names the
	// extension instead of downloading it. An empty extension directory makes "not installed" certain
	// whatever this machine has installed.
	std::random_device random;
	auto dir = std::filesystem::temp_directory_path() / ("qod-node-ext-" + std::to_string(random()));
	std::filesystem::create_directories(dir);
	auto set_dir = ErrorOf(con, "SET extension_directories = [" + qod::QuoteLiteral(dir.string()) + "]");
	Expect(set_dir.empty(), "SET extension_directories: " + set_dir);
	auto excel = ErrorOf(con, "SELECT * FROM read_xlsx('qod-missing.xlsx')");
	Expect(excel.find("Install it first using \"INSTALL excel\"") != std::string::npos,
	       "read_xlsx with autoinstall off: " + excel);
	Expect(std::filesystem::is_empty(dir), "read_xlsx with autoinstall off downloaded into " + dir.string());
	std::filesystem::remove_all(dir);

	// autoinstall_known_extensions alone DuckDB lets a session turn on at runtime: the manager's
	// setting classification reserves it to superusers (Task 2), not the engine.
	Expect(ErrorOf(con, "SET GLOBAL autoinstall_known_extensions = true").empty(),
	       "SET GLOBAL autoinstall_known_extensions = true was refused by DuckDB");

	// Both shared file caches are off at open: they serve every connection of the node by path, so
	// one user could read bytes another user's keys fetched. DuckDB's own default turns the external
	// file cache on.
	{
		duckdb::DBConfig plain;
		duckdb::DuckDB plain_db(nullptr, &plain);
		duckdb::Connection plain_con(plain_db);
		Expect(Setting(plain_con, "enable_external_file_cache") == "true",
		       "DuckDB's default external file cache is on (the policy below must change something)");
	}
	{
		duckdb::DBConfig cached;
		qod::ApplyExtensionPolicy(cached, PolicyOf({}));
		qod::ApplyCachePolicy(cached);
		duckdb::DuckDB cached_db(nullptr, &cached);
		duckdb::Connection cached_con(cached_db);
		Expect(Setting(cached_con, "enable_external_file_cache") == "false", "the external file cache at open");
		Expect(Setting(cached_con, "enable_http_metadata_cache") == "false", "the HTTP metadata cache at open");
	}

	if (failures == 0) {
		std::cout << "node_config_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
