// The describer's test harness: a post_bind hook that describes every bound plan of a connection
// for one principal and keeps the last description (the outermost: DuckDB binds a PREPARE's inner
// statement before the PREPARE itself), or the last refusal.

#pragma once

#include "describer.hpp"
#include "duckdb.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <string>
#include <vector>

namespace qod_test {

struct Capture {
	bool on = false;
	qod::Principal principal;
	//! Set by the last described plan; cleared by a refusal.
	std::string canonical;
	std::string fingerprint;
	//! Every description, in bind order: a statement an extension runs on the same context (DuckLake's
	//! metadata queries) is bound, and described, after the statement that ran it.
	std::vector<std::string> all;
	//! Set by the last refusal; cleared by a described plan.
	std::string refused;
	int described = 0;
};

inline Capture &TheCapture() {
	static Capture capture;
	return capture;
}

inline void CapturePostBind(duckdb::PlannerExtensionInput &input, duckdb::BoundStatement &statement) {
	auto &capture = TheCapture();
	if (!capture.on || !statement.plan) {
		return;
	}
	// A statement run by Connection::Query is an active query: its text is reachable.
	std::string text = input.context.GetCurrentQuery();
	try {
		auto described = qod::Describe(input.context, *statement.plan, capture.principal, &text);
		capture.canonical = described.canonical;
		capture.fingerprint = described.fingerprint;
		capture.all.push_back(described.canonical);
		capture.refused.clear();
		capture.described++;
	} catch (duckdb::PermissionException &error) {
		capture.refused = duckdb::ErrorData(error).RawMessage();
		capture.canonical.clear();
		throw;
	}
}

inline void RegisterCapture(duckdb::DuckDB &db) {
	duckdb::PlannerExtension planner;
	planner.post_bind_function = CapturePostBind;
	duckdb::PlannerExtension::Register(duckdb::DBConfig::GetConfig(*db.instance), planner);
}

inline qod::Principal Alice() {
	qod::Principal principal;
	principal.id = "u-alice";
	principal.name = "alice";
	principal.tenant = "acme";
	return principal;
}

inline qod::Principal Root() {
	qod::Principal principal;
	principal.id = "u-root";
	principal.name = "root";
	principal.superuser = true;
	return principal;
}

//! `text` with every @DIR@ replaced by `dir`.
inline std::string WithDir(std::string text, const std::string &dir) {
	const std::string marker = "@DIR@";
	for (auto at = text.find(marker); at != std::string::npos; at = text.find(marker, at + dir.size())) {
		text.replace(at, marker.size(), dir);
	}
	return text;
}

} // namespace qod_test
