// Describer cases that need a DuckDB extension: a CREATE SECRET (httpfs) never carries its options,
// and a DuckLake flush (ducklake) is a known extension operator over a COPY of the inlined rows.
// Exits 77 (CTest skip) when an extension cannot be installed or loaded here.

#include "describe_harness.hpp"
#include "init_runner.hpp"
#include "json_writer.hpp"
#include "locations.hpp"

#include <filesystem>
#include <iostream>
#include <string>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

namespace {

namespace fs = std::filesystem;
using qod_test::TheCapture;

int failures = 0;

void Fail(const std::string &what) {
	std::cerr << "FAIL: " << what << std::endl;
	failures++;
}

bool Load(duckdb::Connection &con, const std::string &extension) {
	return !con.Query("INSTALL " + extension)->HasError() && !con.Query("LOAD " + extension)->HasError();
}

void Expect(const std::string &what, const std::string &description, const std::string &needle) {
	if (description.find(needle) == std::string::npos) {
		Fail(what + ": the description lacks " + needle + "\n  got " + description + " (refused: \"" +
		     TheCapture().refused + "\")");
	}
}

//! The first description captured since `from` that holds `needle`, or every one joined when none does
//! (so a failure shows them all).
std::string DescriptionWith(size_t from, const std::string &needle) {
	auto &all = TheCapture().all;
	std::string joined;
	for (auto i = from; i < all.size(); i++) {
		if (all[i].find(needle) != std::string::npos) {
			return all[i];
		}
		joined += (joined.empty() ? "" : "\n  ") + all[i];
	}
	return joined;
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	qod_test::RegisterCapture(db);
	duckdb::Connection con(db);
	if (!Load(con, "httpfs") || !Load(con, "ducklake")) {
		std::cout << "describer_extension_test: httpfs or ducklake unavailable, skipped" << std::endl;
		return 77;
	}
	auto &capture = TheCapture();
	capture.principal = qod_test::Root();

	// ---- a secret's options never reach a description
	capture.on = true;
	con.Query("CREATE SECRET s1 (TYPE s3, KEY_ID 'AKIAQODFAKEKEYID', SECRET 'qod-not-a-real-secret')");
	capture.on = false;
	const std::string secret =
	    "{\"control\":[{\"op\":\"create_secret\",\"target\":null,\"type\":\"s3\"}],\"functions\":[],\"objects\":[],"
	    "\"settings\":[],\"statement\":\"CREATE_SECRET\",\"v\":1}";
	if (capture.canonical != secret) {
		Fail("CREATE SECRET:\n  expected " + secret + "\n  got      " + capture.canonical);
	}
	if (capture.canonical.find("AKIAQODFAKEKEYID") != std::string::npos ||
	    capture.canonical.find("qod-not-a-real-secret") != std::string::npos) {
		Fail("CREATE SECRET: a secret option reached the description");
	}

	// ---- a DuckLake flush: the known operator, its COPY into the lake's data folder, the table read
	auto scratch = fs::temp_directory_path() / ("qod-describer-lake-" + std::to_string(::getpid()));
	fs::create_directories(scratch);
	auto dir = fs::canonical(scratch).generic_string();
	try {
		qod::RunStrict(con,
		               "ATTACH 'ducklake:" + dir + "/lake.ducklake' AS lake (DATA_PATH '" + dir + "/lake_data/')",
		               "attach");
		qod::RunStrict(con, "CREATE TABLE lake.t (id INTEGER, v VARCHAR)", "create");
		qod::RunStrict(con, "INSERT INTO lake.t VALUES (1, 'a'), (2, 'b')", "insert");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake setup: ") + error.what());
	}
	// DuckLake runs its metadata queries while the flush executes, so they are described after it:
	// judge the description that names the operator.
	auto from = capture.all.size();
	capture.on = true;
	con.Query("CALL ducklake_flush_inlined_data('lake')");
	capture.on = false;
	const std::string flush_op =
	    "{\"op\":\"extension_operator\",\"target\":\"DUCKLAKE_FLUSH_DATA\",\"type\":\"ducklake\"}";
	auto flush = DescriptionWith(from, flush_op);
	// Which ClientContext DuckLake's own metadata queries bind on (CONTRACT Addendum 4 item 7, for Task
	// 10): reported, not asserted.
	size_t nested = 0;
	size_t nested_on_client = 0;
	for (auto i = from; i < capture.all.size(); i++) {
		if (capture.all[i].find("__ducklake_metadata_lake") != std::string::npos) {
			nested++;
			nested_on_client += capture.contexts[i] == con.context.get() ? 1 : 0;
		}
	}
	std::cout << "describer_extension_test: the flush bound " << (capture.all.size() - from)
	          << " plans; " << nested << " read __ducklake_metadata_lake, " << nested_on_client
	          << " of them on the client's own ClientContext" << std::endl;
	Expect("DuckLake flush", flush, flush_op);
	// The data folder as the describer renders a COPY target (ResolveLocalPath: '\\' on Windows, where
	// DuckLake also writes backslash paths), in its JSON spelling, without the closing quote.
	auto lake_data = qod::ResolveLocalPath(dir + "/lake_data/");
	if (!lake_data) {
		Fail("DuckLake flush: the lake's data folder " + dir + "/lake_data/ does not resolve");
	} else {
		auto lake_data_json = qod::CanonicalJson(qod::JsonString(*lake_data));
		lake_data_json.pop_back();
		Expect("DuckLake flush", flush, "{\"op\":\"copy_to\",\"target\":" + lake_data_json);
	}
	Expect("DuckLake flush", flush, "\"catalog\":\"lake\",\"catalogType\":\"ducklake\"");
	Expect("DuckLake flush", flush, "\"kind\":\"table\",\"name\":\"t\"");
	con.Query("DETACH lake");
	fs::remove_all(scratch);

	// ---- a scalar function an extension loaded at runtime is described by its catalog name, so the
	// manager can refuse it as not classified (fts is not among the functions --list-functions sees)
	if (Load(con, "fts")) {
		capture.principal = qod_test::Alice();
		capture.on = true;
		con.Query("SELECT stem('running', 'porter')");
		capture.on = false;
		Expect("an extension scalar function", capture.canonical,
		       "{\"args\":[],\"catalog\":\"system\",\"name\":\"stem\",\"schema\":\"main\",\"type\":\"scalar\"");
		capture.principal = qod_test::Root();
	} else {
		std::cout << "describer_extension_test: fts unavailable, the extension scalar case skipped" << std::endl;
	}

	if (failures == 0) {
		std::cout << "describer_extension_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
