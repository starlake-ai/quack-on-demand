// Golden tests for the describer: every <case>.sql under the directory given as argv[1] runs on a
// fresh in-memory DuckDB after _setup.sql (and <case>.setup.sql when present), and the description
// of its last bound plan must equal <case>.json byte for byte, or its refusal <case>.refused, or
// hold every line of <case>.contains. @DIR@ stands for a scratch directory's real path. Also walks
// DuckDB's whole LogicalOperatorType and ExpressionClass enums: every member is described or
// refused, so a DuckDB upgrade that adds one fails here until it is classified.

#include "crypto.hpp"
#include "describe_harness.hpp"
#include "describer.hpp"
#include "duckdb/common/enum_util.hpp"
#include "duckdb/parser/parsed_data/drop_info.hpp"
#include "duckdb/planner/operator/logical_drop.hpp"
#include "duckdb/planner/operator/logical_dummy_scan.hpp"
#include "duckdb/planner/operator/logical_extension_operator.hpp"
#include "duckdb/planner/operator/logical_secure_view.hpp"
#include "init_runner.hpp"
#include "sql_script.hpp"

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

namespace {

namespace fs = std::filesystem;
using qod_test::TheCapture;
using qod_test::WithDir;

int failures = 0;

void Fail(const std::string &what) {
	std::cerr << "FAIL: " << what << std::endl;
	failures++;
}

bool Exists(const fs::path &path) {
	return fs::exists(path);
}

std::string Read(const fs::path &path) {
	std::ifstream in(path, std::ios::binary);
	std::stringstream buffer;
	buffer << in.rdbuf();
	return buffer.str();
}

//! The file's content without its one trailing newline.
std::string ReadLine(const fs::path &path) {
	auto text = Read(path);
	if (!text.empty() && text.back() == '\n') {
		text.pop_back();
	}
	return text;
}

void RunScript(duckdb::Connection &con, const std::string &sql, const std::string &label) {
	for (auto &statement : qod::SplitStatements(sql)) {
		qod::RunStrict(con, statement, label);
	}
}

void RunCase(const fs::path &dir, const std::string &name, const std::string &scratch) {
#ifdef _WIN32
	for (auto suffix : {".sql", ".setup.sql", ".json", ".contains"}) {
		if (Exists(dir / (name + suffix)) && Read(dir / (name + suffix)).find("@DIR@") != std::string::npos) {
			std::cout << "skip on Windows: " << name << std::endl;
			return;
		}
	}
#endif
	duckdb::DuckDB db(nullptr);
	qod_test::RegisterCapture(db);
	duckdb::Connection con(db);
	auto &capture = TheCapture();
	capture = qod_test::Capture();
	capture.principal = name.rfind("superuser_", 0) == 0 ? qod_test::Root() : qod_test::Alice();
	try {
		RunScript(con, WithDir(Read(dir / "_setup.sql"), scratch), "setup");
		if (Exists(dir / (name + ".setup.sql"))) {
			RunScript(con, WithDir(Read(dir / (name + ".setup.sql")), scratch), "case setup");
		}
	} catch (std::exception &error) {
		Fail(name + ": setup failed: " + error.what());
		return;
	}
	capture.on = true;
	auto result = con.Query(WithDir(ReadLine(dir / (name + ".sql")), scratch));
	capture.on = false;

	if (Exists(dir / (name + ".refused"))) {
		auto expected = ReadLine(dir / (name + ".refused"));
		if (capture.refused != expected) {
			Fail(name + ": expected the refusal \"" + expected + "\", got \"" + capture.refused + "\" (described: " +
			     capture.canonical + ")");
		}
		return;
	}
	if (!capture.refused.empty() || capture.described == 0) {
		Fail(name + ": no description (refused: \"" + capture.refused + "\"; statement error: " +
		     (result->HasError() ? result->GetError() : std::string("none")) + ")");
		return;
	}
	// Only a File function's locations leave the node (CONTRACT Addendum 4 item 6): no other argument a
	// statement names, such as a connection string's password or a key, reaches a description.
	for (auto secret : {"qod-not-a-real-password", "0123456789112345"}) {
		if (capture.canonical.find(secret) != std::string::npos) {
			Fail(name + ": an argument reached the description: " + capture.canonical);
		}
	}
	if (capture.fingerprint != qod::Hex(qod::Sha256(capture.canonical))) {
		Fail(name + ": the fingerprint is not the SHA-256 of the canonical bytes");
	}
	if (Exists(dir / (name + ".json"))) {
		auto expected = WithDir(ReadLine(dir / (name + ".json")), scratch);
		if (capture.canonical != expected) {
			Fail(name + ":\n  expected " + expected + "\n  got      " + capture.canonical);
		}
		return;
	}
	if (Exists(dir / (name + ".contains"))) {
		std::istringstream lines(WithDir(Read(dir / (name + ".contains")), scratch));
		std::string line;
		while (std::getline(lines, line)) {
			if (!line.empty() && capture.canonical.find(line) == std::string::npos) {
				Fail(name + ": the description lacks " + line + "\n  got " + capture.canonical);
			}
		}
		return;
	}
	Fail(name + ": no .json, .refused or .contains expectation");
}

void RunGoldens(const fs::path &dir) {
	auto scratch = fs::temp_directory_path() / ("qod-describer-" + std::to_string(::getpid()));
	fs::create_directories(scratch);
	auto real = fs::canonical(scratch).generic_string();
	std::vector<std::string> names;
	for (auto &entry : fs::directory_iterator(dir)) {
		auto file = entry.path().filename().string();
		const std::string suffix = ".sql";
		if (file.size() <= suffix.size() || file.compare(file.size() - suffix.size(), suffix.size(), suffix) != 0 ||
		    file[0] == '_' || file.find(".setup.sql") != std::string::npos) {
			continue;
		}
		names.push_back(file.substr(0, file.size() - suffix.size()));
	}
	std::sort(names.begin(), names.end());
	if (names.size() < 149) {
		Fail("expected the full golden corpus, found " + std::to_string(names.size()) + " cases in " + dir.string());
	}
	for (auto &name : names) {
		RunCase(dir, name, real);
	}
	fs::remove_all(scratch);
	std::cout << "describer_test: " << names.size() << " golden cases" << std::endl;
}

//! Every member of DuckDB's operator and expression enums (EnumUtil throws for a non-member) is
//! described or refused: UNKNOWN means a DuckDB upgrade added one nobody classified.
void CheckEnums() {
	for (int i = 0; i < 256; i++) {
		auto type = static_cast<duckdb::LogicalOperatorType>(i);
		std::string name;
		try {
			name = duckdb::EnumUtil::ToString(type);
		} catch (std::exception &) {
			continue;
		}
		if (qod::OperatorHandling(type) == qod::Handling::UNKNOWN) {
			Fail("LogicalOperatorType " + name + " is neither described nor refused");
		}
	}
	for (int i = 0; i < 256; i++) {
		auto expression_class = static_cast<duckdb::ExpressionClass>(i);
		std::string name;
		try {
			name = duckdb::EnumUtil::ToString(expression_class);
		} catch (std::exception &) {
			continue;
		}
		if (qod::ExpressionHandling(expression_class) == qod::Handling::UNKNOWN) {
			Fail("ExpressionClass " + name + " is neither described nor refused");
		}
	}
	// The nearest things the walk must still refuse: a parsed expression, the invalid operator.
	if (qod::ExpressionHandling(duckdb::ExpressionClass::FUNCTION) != qod::Handling::REFUSED ||
	    qod::OperatorHandling(duckdb::LogicalOperatorType::LOGICAL_INVALID) != qod::Handling::REFUSED ||
	    qod::OperatorHandling(duckdb::LogicalOperatorType::LOGICAL_CONNECT) != qod::Handling::REFUSED) {
		Fail("a parsed expression, LOGICAL_INVALID and LOGICAL_CONNECT must be refused");
	}
}

//! The location rendering and COPY FROM's path, which a plan cannot show offline for s3:// (DuckDB
//! opens a COPY FROM's file while binding, before post_bind runs).
void CheckLocationHelpers() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	auto &context = *con.context;
	auto path = [&](const std::string &sql) { return qod::CopyFromLiteralPath(context, sql); };
	auto expect = [&](const std::string &got, const std::string &want, const std::string &what) {
		if (got != want) {
			Fail(what + ": expected \"" + want + "\", got \"" + got + "\"");
		}
	};
	expect(path("COPY t FROM 's3://b/in/x.csv'"), "s3://b/in/x.csv", "COPY FROM an s3 URL");
	expect(path("COPY t FROM '/srv/in/x.csv' (HEADER)"), "/srv/in/x.csv", "COPY FROM a local path, with options");
	expect(path("EXPLAIN COPY t FROM 's3://b/in/x.csv'"), "s3://b/in/x.csv", "under EXPLAIN");
	expect(path("COPY t TO 's3://b/out/x.csv'"), "", "a COPY TO is not a COPY FROM");
	expect(path("SELECT 1"), "", "not a COPY");
	expect(path("COPY t FROM 's3://b/in/x.csv'; SELECT 1"), "", "two statements");
	expect(qod::DescribedLocation("s3://b/in/x.csv", true), "s3://b/in/x.csv", "an s3 URL is kept");
	expect(qod::DescribedLocation("rel/x.csv", false), "rel/x.csv", "a trusted relative path is kept");
	try {
		qod::DescribedLocation("rel/x.csv", true);
		Fail("a relative path was accepted for a tenant user");
	} catch (duckdb::PermissionException &error) {
		expect(duckdb::ErrorData(error).RawMessage(), "QoD: rel/x.csv is outside the tenant's authorized locations",
		       "the relative path refusal");
	}
	// The screen's rule (MakeLocation): no other scheme, and an s3:// URL must name a bucket.
	expect(qod::DescribedLocation("http://h.invalid/x.csv", false), "http://h.invalid/x.csv",
	       "a trusted http URL is kept");
	// What a kept URL loses (review M4): its userinfo, query string and fragment, s3:// excepted.
	expect(qod::RedactedUrl("https://user:pw@acct.invalid/c/x.csv?sv=1&sig=qod-not-a-real-password#f"),
	       "https://acct.invalid/c/x.csv", "a URL loses its userinfo, query and fragment");
	expect(qod::DescribedLocation("https://acct.invalid/c/x.csv?sig=qod-not-a-real-password", false),
	       "https://acct.invalid/c/x.csv", "a kept URL is redacted");
	expect(qod::RedactedUrl("http://h.invalid/a@b/x.csv"), "http://h.invalid/a@b/x.csv",
	       "an '@' past the authority is part of the path");
	expect(qod::RedactedUrl("s3://b/x.csv?versionId=1"), "s3://b/x.csv?versionId=1", "an s3 URL is kept whole");
	expect(qod::RedactedUrl("/srv/a?b"), "/srv/a?b", "a local path is no URL");
	for (auto refused : {"http://h.invalid/x.csv", "file:///srv/x.csv", "s3://", "/srv/../x.csv"}) {
		try {
			qod::DescribedLocation(refused, true);
			Fail(std::string(refused) + " was accepted for a tenant user");
		} catch (duckdb::PermissionException &error) {
			expect(duckdb::ErrorData(error).RawMessage(),
			       "QoD: " + std::string(refused) + " is outside the tenant's authorized locations",
			       std::string("the refusal of ") + refused);
		}
	}
}

//! Session state a secure view's body depends on, and failures inside Describe:
//!  - a time-travel read through a boundary is refused (Addendum 4 item 12);
//!  - the set paths are held to the session default, then to the expected path (items 11 a and 22);
//!  - an exception other than a refusal becomes "QoD: statement could not be described" (review M5).
void CheckSessionState() {
	duckdb::DuckDB db(nullptr);
	qod_test::RegisterCapture(db);
	duckdb::Connection con(db);
	auto &capture = TheCapture();
	capture = qod_test::Capture();
	capture.principal = qod_test::Alice();
	for (auto sql : {"CREATE TABLE u (id INTEGER)", "CREATE SECURE VIEW sv AS SELECT id FROM main.u",
	                 "ATTACH ':memory:' AS m1"}) {
		qod::RunStrict(con, sql, "setup");
	}
	const std::string paths_refused = "QoD: statement uses a secure view while the session's search path differs "
	                                  "from the one it was given, which QoD does not support";
	auto read = [&](const std::string &what, const std::string &want_refused) {
		capture.on = true;
		capture.refused.clear();
		capture.canonical.clear();
		con.Query("SELECT * FROM memory.main.sv");
		capture.on = false;
		if (capture.refused != want_refused || (want_refused.empty() && capture.canonical.empty())) {
			Fail(what + ": expected " + (want_refused.empty() ? "a description" : "\"" + want_refused + "\"") +
			     ", got \"" + capture.refused + "\" / " + capture.canonical);
		}
	};
	qod::RunStrict(con, "USE m1.main", "use");
	read("another set path, none expected", paths_refused);
	qod::ExpectSessionPath(*con.context, "m1.main");
	read("the expected path", "");
	qod::ExpectSessionPath(*con.context, "M1.Main");
	read("the expected path, case aside", "");
	qod::RunStrict(con, "USE memory.main", "use");
	read("a set path other than the expected one", paths_refused);
	qod::ExpectSessionPath(*con.context, "memory.main");
	read("the expected path, again", "");
	qod::ExpectSessionPath(*con.context, "main");
	read("an expected path without its catalog matches nothing", paths_refused);
	qod::ExpectSessionPath(*con.context, "");
	read("no expected path: the session default", "");

	// A boundary read AT another snapshot, built by hand: an in-memory database has no time travel.
	con.BeginTransaction();
	auto &context = *con.context;
	duckdb::LogicalSecureView view("sv", duckdb::make_uniq<duckdb::LogicalDummyScan>(duckdb::TableIndex(0)));
	view.has_source = true;
	view.source_name = duckdb::QualifiedName(duckdb::Identifier("memory"), duckdb::Identifier("main"),
	                                         duckdb::Identifier("sv"));
	view.has_at_clause = true;
	std::string refused;
	try {
		qod::Describe(context, view, qod_test::Root());
	} catch (duckdb::PermissionException &error) {
		refused = duckdb::ErrorData(error).RawMessage();
	}
	if (refused != "QoD: statement uses a time-travel (AT) read of a secure view, which QoD does not support") {
		Fail("an AT read through a secure view: got \"" + refused + "\"");
	}
	con.Rollback();
}

//! A two-part name in a secure view's definition that has become ambiguous (a database attached under
//! the name of the schema it qualifies) is refused (CONTRACT Addendum 4 item 17). DuckDB's binder
//! refuses it too, before post_bind, so the boundary is built by hand to reach the describer.
void CheckAmbiguousName() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	for (auto sql : {"CREATE SCHEMA s", "CREATE TABLE s.t (id INTEGER)",
	                 "CREATE SECURE VIEW sv_amb AS SELECT id FROM s.t", "ATTACH ':memory:' AS s"}) {
		qod::RunStrict(con, sql, "setup");
	}
	con.BeginTransaction();
	duckdb::LogicalSecureView view("sv_amb", duckdb::make_uniq<duckdb::LogicalDummyScan>(duckdb::TableIndex(0)));
	view.has_source = true;
	view.source_name = duckdb::QualifiedName(duckdb::Identifier("memory"), duckdb::Identifier("main"),
	                                         duckdb::Identifier("sv_amb"));
	std::string refused;
	try {
		qod::Describe(*con.context, view, qod_test::Alice());
	} catch (duckdb::PermissionException &error) {
		refused = duckdb::ErrorData(error).RawMessage();
	}
	if (refused != "QoD: statement uses a secure view whose definition names the table s.t ambiguously (a catalog "
	               "and a schema of that name), which QoD does not support") {
		Fail("an ambiguous two-part name: got \"" + refused + "\"");
	}
	con.Rollback();
}

//! Outside a transaction a catalog lookup fails with DuckDB's InternalException: Describe refuses
//! plainly instead (review M5).
void CheckDescribeFailure() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	con.Query("CREATE TABLE t (id INTEGER)");
	auto drop_info = duckdb::make_uniq<duckdb::DropInfo>();
	drop_info->type = duckdb::CatalogType::VIEW_ENTRY;
	drop_info->SetQualifiedName(duckdb::QualifiedName(duckdb::Identifier("memory"), duckdb::Identifier("main"),
	                                                  duckdb::Identifier("t")));
	duckdb::LogicalDrop drop(std::move(drop_info));
	std::string refused;
	try {
		qod::Describe(*con.context, drop, qod_test::Alice());
		Fail("a description outside a transaction did not fail");
	} catch (duckdb::PermissionException &error) {
		refused = duckdb::ErrorData(error).RawMessage();
	} catch (std::exception &error) {
		refused = std::string("not a refusal: ") + error.what();
	}
	if (refused != "QoD: statement could not be described") {
		Fail("a failing lookup: expected the plain refusal, got \"" + refused + "\"");
	}
}

//! An extension operator as an extension would plan it: `name` empty keeps DuckDB's default GetName
//! (CUSTOM_OP), `extension` empty keeps its default GetExtensionName (which throws).
struct FakeStep : public duckdb::LogicalExtensionOperator {
	FakeStep(std::string name_p, std::string extension_p, bool with_child)
	    : name(std::move(name_p)), extension(std::move(extension_p)) {
		if (with_child) {
			children.push_back(duckdb::make_uniq<duckdb::LogicalDummyScan>(duckdb::TableIndex(0)));
		}
	}
	duckdb::PhysicalOperator &CreatePlan(duckdb::ClientContext &, duckdb::PhysicalPlanGenerator &) override {
		throw duckdb::InternalException("FakeStep is never planned");
	}
	std::string GetName() const override {
		return name.empty() ? duckdb::LogicalExtensionOperator::GetName() : name;
	}
	std::string GetExtensionName() const override {
		return extension.empty() ? duckdb::LogicalExtensionOperator::GetExtensionName() : extension;
	}
	void ResolveTypes() override {
	}

	std::string name;
	std::string extension;
};

//! Known extension operators by name and extension, and decision 19's unnamed steps judged from the
//! statement text: plans no offline database builds (DuckLake's flush is in describer_extension_test).
void CheckExtensionOperators() {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	con.Query("CREATE TABLE t (id INTEGER)");
	// Describe resolves catalog entries, as it does in post_bind, inside the statement's transaction.
	con.BeginTransaction();
	auto &context = *con.context;
	auto alice = qod_test::Alice();
	auto describe = [&](duckdb::LogicalOperator &plan, const std::string *text, std::string &refused) {
		refused.clear();
		try {
			return qod::Describe(context, plan, alice, text).canonical;
		} catch (duckdb::PermissionException &error) {
			refused = duckdb::ErrorData(error).RawMessage();
			return std::string();
		}
	};
	const std::string unnamed = "QoD: statement uses an extension operator that names itself neither by name nor by "
	                            "extension, which QoD does not support";
	std::string refused;

	// Iceberg's COPY TO: the format as the binder reads it, a literal target.
	for (std::string text : {"COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT iceberg)",
	                         "COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT 'ICEBERG')",
	                         "EXPLAIN COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT iceberg)",
	                         "COPY (SELECT 1) TO 's3://b/lake/t.iceberg'"}) {
		FakeStep step("", "", true);
		auto got = describe(step, &text, refused);
		const std::string want = "{\"control\":[{\"op\":\"copy_to\",\"target\":\"s3://b/lake/t";
		if (got.find(want) == std::string::npos || got.find("\"type\":\"iceberg\"}") == std::string::npos ||
		    got.find("\"statement\":\"CUSTOM_OP\"") == std::string::npos) {
			Fail("unnamed COPY TO for `" + text + "`: got " + got + " (refused: \"" + refused + "\")");
		}
	}
	for (std::string text :
	     {"COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT parquet)", "COPY t FROM 's3://b/x' (FORMAT iceberg)",
	      "COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT iceberg); SELECT 1", "SELECT 1", "CREATE INDEX i ON t (id)"}) {
		FakeStep step("", "", true);
		describe(step, &text, refused);
		if (refused != unnamed) {
			Fail("unnamed step for `" + text + "`: expected the refusal, got \"" + refused + "\"");
		}
	}
	{
		// No text, superusers included.
		FakeStep step("", "", true);
		describe(step, nullptr, refused);
		std::string root_refused;
		try {
			qod::Describe(context, step, qod_test::Root(), nullptr);
		} catch (duckdb::PermissionException &error) {
			root_refused = duckdb::ErrorData(error).RawMessage();
		}
		if (refused != unnamed || root_refused != unnamed) {
			Fail("an unnamed step without text was not refused (\"" + refused + "\", \"" + root_refused + "\")");
		}
	}
	{
		// A CREATE INDEX step judges its table's catalog: t is a DuckDB table, not a Postgres one.
		FakeStep step("", "", false);
		std::string text = "CREATE INDEX i ON t (id)";
		describe(step, &text, refused);
		if (refused != unnamed) {
			Fail("an unnamed CREATE INDEX on a DuckDB table: expected the refusal, got \"" + refused + "\"");
		}
	}
	{
		// The unnamed step must be the statement's root.
		FakeStep outer("ICEBERG_REWRITE_DATA_FILES", "", false);
		outer.children.push_back(duckdb::make_uniq<FakeStep>("", "", true));
		std::string text = "COPY (SELECT 1) TO 's3://b/lake/t' (FORMAT iceberg)";
		describe(outer, &text, refused);
		if (refused != unnamed) {
			Fail("an unnamed step below the root: expected the refusal, got \"" + refused + "\"");
		}
	}

	// Named operators: the extension must match.
	struct Named {
		const char *name;
		const char *extension;
		const char *control;
	};
	const std::vector<Named> operators = {
	    {"DUCKLAKE_FLUSH_DATA", "ducklake",
	     "{\"op\":\"extension_operator\",\"target\":\"DUCKLAKE_FLUSH_DATA\",\"type\":\"ducklake\"}"},
	    {"DUCKLAKE_COMPACTION", "ducklake",
	     "{\"op\":\"extension_operator\",\"target\":\"DUCKLAKE_COMPACTION\",\"type\":\"ducklake\"}"},
	    {"ICEBERG_REWRITE_DATA_FILES", "",
	     "{\"op\":\"extension_operator\",\"target\":\"ICEBERG_REWRITE_DATA_FILES\",\"type\":null}"},
	    {"DUCKLAKE_FLUSH_DATA", "other", nullptr},
	    {"ICEBERG_REWRITE_DATA_FILES", "iceberg", nullptr},
	    {"SOMETHING_ELSE", "", nullptr}};
	for (auto &named : operators) {
		FakeStep step(named.name, named.extension, true);
		auto got = describe(step, nullptr, refused);
		if (named.control && got.find(named.control) == std::string::npos) {
			Fail(std::string("extension operator ") + named.name + ": got " + got + " (refused: \"" + refused + "\")");
		}
		auto want_refused =
		    std::string("QoD: statement uses the extension operator ") + named.name + ", which QoD does not support";
		if (!named.control && refused != want_refused) {
			Fail(std::string("extension operator ") + named.name + "/" + named.extension +
			     ": expected the refusal, got \"" + refused + "\"");
		}
	}
}

} // namespace

int main(int argc, char **argv) {
	if (argc != 2) {
		std::cerr << "usage: describer_test <golden descriptions dir>" << std::endl;
		return 2;
	}
	CheckEnums();
	CheckLocationHelpers();
	CheckExtensionOperators();
	CheckSessionState();
	CheckDescribeFailure();
	CheckAmbiguousName();
	RunGoldens(argv[1]);
	if (failures == 0) {
		std::cout << "describer_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
