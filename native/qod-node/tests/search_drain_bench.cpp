// Search Phase 0 part 2, Task 5 (spec 7.6, 7.4): the node-local drain timing. Prints, asserts only that
// every statement runs and returns the expected row count.
//
// On an in-memory DuckDB with the search functions registered (no authorization: a timing of the
// drain itself), the part 2 stack's DuckLake catalog is attached read-only as `lake` from the DSN file
// $QOD_P2_DUCKLAKE_DSN_FILE (`ducklake:postgres:...`, mode 600, never printed) with the DATA_PATH
// read from data_path.txt beside it. Under SET threads = 16, for each size (pages_10m and
// pages_100m_drain) and each input (no filter, f10 = 0, f100 = 0), 5 warm-ups then 50 timed runs of
//   drain:    SELECT rows, max_rowid, drain_ms FROM qod_search_drain((SELECT rowid FROM <t> [WHERE f]))
//   parallel: SELECT sum(rows) FROM qod_search_drain_parallel((SELECT rowid FROM <t> [WHERE f]))
//   drain_vec, parallel_vec: the same two with the bitsets in a std::vector (Task 7a Step 1)
//   count:    SELECT count(*) FROM (SELECT rowid FROM <t> [WHERE f])
// each printed as `== drain <size> <filter> <function> p50=<ms> p95=<ms> rows=<n>` (nearest-rank
// percentiles of the wall time of Connection::Query, the result materialized); the drain line adds the
// p50 of the function's own drain_ms (first input row to final), the parallel line the number of local
// states (one untimed run).
//
// Overrides for a quick check, never for the run of record: QOD_P2_T5_SIZES (comma list of 1m, 10m,
// 100m_drain; default 10m,100m_drain), QOD_P2_T5_RUNS (default 50), QOD_P2_T5_WARMUP (default 5).
// Exits 77 when the DSN file is missing or ducklake or postgres cannot load. Built with
// QOD_NODE_SEARCH=ON and QOD_NODE_PROBES=ON (label probe).

#include "search_functions.hpp"

#include "duckdb.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

using namespace duckdb;

namespace {

std::string g_password;

//! The text with the catalog password masked: nothing printed carries it.
std::string Scrub(std::string text) {
	if (!g_password.empty()) {
		size_t at;
		while ((at = text.find(g_password)) != std::string::npos) {
			text.replace(at, g_password.size(), "***");
		}
	}
	return text;
}

std::string ReadFile(const std::filesystem::path &path) {
	std::ifstream in(path);
	return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

std::string Trim(std::string s) {
	while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) {
		s.pop_back();
	}
	return s;
}

std::string Quote(const std::string &s) {
	std::string out = "'";
	for (auto c : s) {
		out += c;
		if (c == '\'') {
			out += '\'';
		}
	}
	return out + "'";
}

double Percentile(std::vector<double> samples, double q) {
	std::sort(samples.begin(), samples.end());
	auto rank = static_cast<size_t>(std::ceil(q * static_cast<double>(samples.size())));
	return samples[rank == 0 ? 0 : rank - 1];
}

std::string Ms(double v) {
	std::ostringstream out;
	out << std::fixed << std::setprecision(3) << v;
	return out.str();
}

int64_t ExpectedRows(const std::string &size, const std::string &filter) {
	int64_t n = size == "1m" ? 1000000 : size == "10m" ? 10000000 : 100000000;
	return filter == "none" ? n : filter == "f10" ? n / 10 : n / 100;
}

std::vector<std::string> Split(const std::string &list) {
	std::vector<std::string> out;
	std::stringstream in(list);
	std::string item;
	while (std::getline(in, item, ',')) {
		if (!item.empty()) {
			out.push_back(item);
		}
	}
	return out;
}

//! Column `col` of the first row, or a NULL value when there is none.
Value FirstValue(QueryResult &result, idx_t col) {
	if (result.HasError()) {
		return Value();
	}
	auto chunk = result.Fetch();
	return chunk && chunk->size() > 0 ? chunk->GetValue(col, 0) : Value();
}

int EnvInt(const char *name, int fallback) {
	auto v = std::getenv(name);
	return v && *v ? std::atoi(v) : fallback;
}

} // namespace

int main() {
	auto dsn_env = std::getenv("QOD_P2_DUCKLAKE_DSN_FILE");
	if (!dsn_env || !std::filesystem::exists(dsn_env)) {
		std::cout << "== QOD_P2_DUCKLAKE_DSN_FILE not set or missing: skipped" << std::endl;
		return 77;
	}
	std::filesystem::path dsn_file(dsn_env);
	auto dsn = Trim(ReadFile(dsn_file));
	auto data_path = Trim(ReadFile(dsn_file.parent_path() / "data_path.txt"));
	{
		auto at = dsn.find("password=");
		if (at != std::string::npos) {
			auto end = dsn.find(' ', at);
			g_password = dsn.substr(at + 9, end == std::string::npos ? std::string::npos : end - at - 9);
		}
	}
	auto sizes_env = std::getenv("QOD_P2_T5_SIZES");
	auto sizes = Split(sizes_env && *sizes_env ? sizes_env : "10m,100m_drain");
	int runs = EnvInt("QOD_P2_T5_RUNS", 50);
	int warmup = EnvInt("QOD_P2_T5_WARMUP", 5);

	DuckDB db(nullptr);
	qod::RegisterSearchFunctions(*db.instance);
	Connection con(db);
	for (std::string ext : {"ducklake", "postgres"}) {
		if (con.Query("INSTALL " + ext)->HasError() || con.Query("LOAD " + ext)->HasError()) {
			std::cout << "== " << ext << " unavailable: skipped" << std::endl;
			return 77;
		}
	}
	auto attached = con.Query("ATTACH " + Quote(dsn) + " AS lake (DATA_PATH " + Quote(data_path) + ", READ_ONLY)");
	if (attached->HasError()) {
		std::cout << "ERROR attach (statement not printed): " << Scrub(attached->GetError()) << std::endl;
		return 1;
	}
	auto threads = con.Query("SET threads = 16");
	if (threads->HasError()) {
		std::cout << "ERROR SET threads: " << Scrub(threads->GetError()) << std::endl;
		return 1;
	}
	std::cout << "== drain bench: runs=" << runs << " warmup=" << warmup << " threads="
	          << FirstValue(*con.Query("SELECT current_setting('threads')"), 0).ToString() << std::endl;

	int failures = 0;
	const std::vector<std::pair<std::string, std::string>> filters = {
	    {"none", ""}, {"f10", " WHERE f10 = 0"}, {"f100", " WHERE f100 = 0"}};
	for (auto &size : sizes) {
		auto table = "lake.main.pages_" + size;
		for (auto &filter : filters) {
			auto input = "(SELECT rowid FROM " + table + filter.second + ")";
			auto expected = ExpectedRows(size, filter.first);
			struct Statement {
				std::string function;
				std::string sql;
			};
			// drain_vec / parallel_vec: the same statements with the bitsets in a std::vector instead of
			// the buffer manager (Task 7a Step 1: the buffer manager form stays only when its 10M drain is
			// within 5% of the vector form's).
			std::vector<Statement> statements = {
			    {"drain", "SELECT rows, max_rowid, drain_ms FROM qod_search_drain(" + input + ")"},
			    {"drain_vec", "SELECT rows, max_rowid, drain_ms FROM qod_search_drain(" + input + ")"},
			    {"parallel", "SELECT sum(rows) FROM qod_search_drain_parallel(" + input + ")"},
			    {"parallel_vec", "SELECT sum(rows) FROM qod_search_drain_parallel(" + input + ")"},
			    {"count", "SELECT count(*) FROM " + input},
			};
			for (auto &st : statements) {
				qod::UseVectorBitsetsForTest(st.function.size() > 4 &&
				                             st.function.compare(st.function.size() - 4, 4, "_vec") == 0);
				std::vector<double> wall, inner;
				int64_t rows = -1;
				bool failed = false;
				for (int i = 0; i < warmup + runs && !failed; i++) {
					auto start = std::chrono::steady_clock::now();
					auto result = con.Query(st.sql);
					auto end = std::chrono::steady_clock::now();
					if (result->HasError()) {
						std::cout << "ERROR " << size << " " << filter.first << " " << st.function << ": "
						          << Scrub(result->GetError()) << std::endl;
						failed = true;
						break;
					}
					auto chunk = result->Fetch();
					if (!chunk || chunk->size() != 1) {
						std::cout << "ERROR " << size << " " << filter.first << " " << st.function << ": "
						          << (chunk ? chunk->size() : 0) << " result rows" << std::endl;
						failed = true;
						break;
					}
					rows = chunk->GetValue(0, 0).GetValue<int64_t>();
					if (i >= warmup) {
						wall.push_back(std::chrono::duration<double, std::milli>(end - start).count());
						if (st.function.rfind("drain", 0) == 0) {
							inner.push_back(chunk->GetValue(2, 0).GetValue<double>());
						}
					}
				}
				if (failed) {
					failures++;
					continue;
				}
				std::cout << "== drain " << size << " " << filter.first << " " << st.function
				          << " p50=" << Ms(Percentile(wall, 0.5)) << " p95=" << Ms(Percentile(wall, 0.95))
				          << " rows=" << rows;
				if (st.function.rfind("drain", 0) == 0) {
					std::cout << " drain_ms_p50=" << Ms(Percentile(inner, 0.5));
				}
				if (st.function.rfind("parallel", 0) == 0) {
					auto locals = con.Query("SELECT count(*) FROM qod_search_drain_parallel(" + input + ")");
					std::cout << " locals="
					          << (locals->HasError() ? std::string("error") : FirstValue(*locals, 0).ToString());
				}
				std::cout << " samples=" << wall.size();
				if (rows != expected) {
					std::cout << " MISMATCH expected=" << expected;
					failures++;
				}
				std::cout << std::endl;
			}
		}
	}
	std::cout << "== drain bench done: " << failures << " failure(s)" << std::endl;
	return failures == 0 ? 0 : 1;
}
