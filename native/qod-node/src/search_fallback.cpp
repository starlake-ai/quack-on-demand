#include "search_fallback.hpp"

#include "crypto.hpp"

#include "qod_search.h"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/main/client_context.hpp"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <unordered_map>

namespace qod::search {

using namespace duckdb;

namespace {

constexpr uint64_t kDefaultCap = 200000;
constexpr uint64_t kDefaultSourceCap = 64;
//! Rows of the fallback's scan between two interrupt checks (spec 9 asks for at most 65,536); the scan
//! itself checks between chunks too (DriveChunks).
constexpr uint64_t kRowsPerCheck = 2048;

std::atomic<uint64_t> g_cap {kDefaultCap};
std::atomic<uint64_t> g_source_cap {kDefaultSourceCap};
std::atomic<uint64_t> g_calls {0}, g_refused {0}, g_segments {0}, g_rows {0}, g_index_ns {0};
std::atomic<uint64_t> g_interrupt_after {0};
std::atomic<bool> g_record_rowids {false};
std::mutex g_hooks_lock;
std::vector<int64_t> g_rowids;
std::function<void(ClientContext &)> g_source_observer;
std::function<void(bool)> g_scan_observer;

std::string Taken(char *err) {
	std::string text = err ? err : "search_internal: no message";
	qs_string_free(err);
	return text;
}

//! A library error of the builder as the query's error: a cancel as DuckDB's interrupt, anything else as
//! an ordinary error (never an internal one, which would invalidate the database).
[[noreturn]] void BuilderFailed(char *err) {
	auto text = Taken(err);
	if (StringUtil::StartsWith(text, "search_cancelled")) {
		throw InterruptException();
	}
	throw InvalidInputException("QoD: " + text);
}

[[noreturn]] void Unavailable(const std::string &why) {
	throw InvalidInputException("QoD: search_snapshot_unavailable: " + why);
}

[[noreturn]] void MemoryExceeded(uint64_t held, uint64_t budget) {
	throw InvalidInputException("QoD: search_memory_exceeded: the search's fallback segments hold " +
	                            std::to_string(held) + " bytes, past its budget of " + std::to_string(budget) +
	                            " bytes");
}

uint64_t Elapsed(std::chrono::steady_clock::time_point since) {
	return static_cast<uint64_t>(
	    std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - since).count());
}

//! One source of the scan: its builder while its rows arrive.
struct Source {
	std::string name;
	int64_t rows = 0;
	bool inlined = false;
	size_t file_index = 0;
	std::string key; // SHA-256 of `name`
	QsSource qs {};
	QsBuilder *builder = nullptr;
	uint64_t docs = 0;
	std::vector<int64_t> rowids; // when recorded
	std::shared_ptr<const OpenSegment> open;
	uint64_t bytes = 0;
	~Source() {
		if (builder) {
			// Consumes and frees a builder that never finished.
			qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
		}
	}
};

void ReadCap(const char *name, std::atomic<uint64_t> &cap, uint64_t fallback) {
	auto text = std::getenv(name);
	uint64_t value = 0;
	if (!text || !*text) {
		cap = fallback;
	} else if (ParseCap(text, value)) {
		cap = value;
	} else {
		std::cerr << "[qod-node] " << name << " is not a number that fits 64 bits; using " << fallback << std::endl;
		cap = fallback;
	}
}

} // namespace

bool ParseCap(const char *text, uint64_t &value) {
	if (!text || !*text) {
		return false;
	}
	for (auto c = text; *c; c++) {
		if (*c < '0' || *c > '9') {
			return false;
		}
	}
	errno = 0;
	char *end = nullptr;
	auto parsed = std::strtoull(text, &end, 10);
	if (errno == ERANGE || !end || *end != '\0') {
		return false;
	}
	value = parsed;
	return true;
}

std::vector<FallbackSegment> BuildFallbackSegments(ClientContext &context, const SnapshotView &view,
                                                   const std::vector<DataFileRef> &unindexed, int64_t field_id,
                                                   uint64_t cap, uint64_t source_cap, const FallbackScan &scan,
                                                   const std::string &source_id, uint64_t memory_budget) {
	g_calls++;
	auto record = g_record_rowids.load();
	if (record) {
		std::lock_guard<std::mutex> lock(g_hooks_lock);
		g_rowids.clear();
	}
	auto table = view.catalog_alias + "." + view.schema_name + "." + view.table_name;
	if (scan.table.oid != view.table_oid) {
		throw InvalidInputException("QoD: search_unbound: the fallback's table is not the search call's target");
	}
	// The backlog, from the view alone (spec 9): nothing is read before both caps are checked.
	std::vector<std::unique_ptr<Source>> sources;
	uint64_t backlog = 0;
	for (auto &file : unindexed) {
		auto index = FileIndexForScanName(view, file.path);
		if (index < 0) {
			Unavailable("an unindexed file is not a data file of the view");
		}
		auto rows = view.liveness[static_cast<size_t>(index)].live_count;
		if (rows <= 0) {
			continue;
		}
		auto source = std::make_unique<Source>();
		source->name = file.path;
		source->rows = rows;
		source->file_index = static_cast<size_t>(index);
		sources.push_back(std::move(source));
		backlog += static_cast<uint64_t>(rows);
	}
	for (auto &inlined : view.inlined_tables) {
		if (inlined.live_rows <= 0) {
			continue;
		}
		auto source = std::make_unique<Source>();
		source->name = inlined.table_name;
		source->rows = inlined.live_rows;
		source->inlined = true;
		sources.push_back(std::move(source));
		backlog += static_cast<uint64_t>(inlined.live_rows);
	}
	if (backlog > cap) {
		g_refused++;
		throw InvalidInputException("QoD: search_index_behind: " + table + " has " + std::to_string(backlog) +
		                            " unindexed rows (cap " + std::to_string(cap) +
		                            "); retry once the indexer catches up");
	}
	if (sources.size() > source_cap) {
		g_refused++;
		throw InvalidInputException("QoD: search_index_behind: " + table + " has " + std::to_string(sources.size()) +
		                            " unindexed sources (cap " + std::to_string(source_cap) +
		                            "); retry once the indexer catches up");
	}
	if (sources.empty()) {
		return {};
	}
	std::vector<std::string> names;
	std::unordered_map<std::string, Source *> by_name;
	for (auto &source : sources) {
		source->key = qod::Hex(qod::Sha256(source->name));
		auto &qs = source->qs;
		qs.index_id = source_id.c_str();
		qs.path_key = source->key.c_str();
		qs.path = source->name.c_str();
		// No file stands behind the segment: its catalog fields are unchecked, and the two sizes are only what
		// the segment records and open checks against itself.
		qs.file_size = 0;
		qs.footer_size = 0;
		qs.record_count = -1;
		qs.row_id_start = -1;
		qs.has_internal_row_id = -1;
		qs.has_internal_snapshot = -1;
		names.push_back(source->name);
		by_name[source->name] = source.get();
	}

	std::atomic<uint8_t> cancel {0};
	uint64_t held = 0;
	uint64_t read = 0;
	uint64_t index_ns = 0;
	auto interrupt_after = g_interrupt_after.load();
	Source *current = nullptr;
	// Finishes a source whose rows are all in: its builder becomes an in-memory segment.
	auto finish = [&](Source &source) {
		uint8_t *bytes = nullptr;
		size_t len = 0;
		uint64_t built = 0;
		char *err = nullptr;
		auto builder = source.builder;
		source.builder = nullptr;
		auto t0 = std::chrono::steady_clock::now();
		if (qs_builder_finish(builder, &bytes, &len, &built, &err) != 0) {
			BuilderFailed(err);
		}
		auto segment = qs_segment_open(bytes, len, &source.qs, &err);
		qs_bytes_free(bytes, len);
		index_ns += Elapsed(t0);
		if (!segment) {
			// A segment built here and refused here: an ordinary error, never a different answer.
			throw InvalidInputException("QoD: search_internal: a fallback segment could not be opened: " + Taken(err));
		}
		auto open = WrapSegment(segment, len);
		source.bytes = len + open->rowid_docs.capacity() * sizeof(open->rowid_docs[0]);
		source.open = std::move(open);
		held += source.bytes;
		if (held >= memory_budget) {
			MemoryExceeded(held, memory_budget);
		}
	};
	std::function<void(ClientContext &)> observer;
	std::function<void(bool)> scan_observer;
	{
		std::lock_guard<std::mutex> lock(g_hooks_lock);
		observer = g_source_observer;
		scan_observer = g_scan_observer;
	}
	if (observer) {
		observer(context);
	}
	if (context.IsInterrupted()) {
		throw InterruptException();
	}
	if (scan_observer) {
		scan_observer(true);
	}
	try {
		ScanTargetTextRows(
		    context, scan.table, scan.at_clause, field_id, &names,
		    [&](const string_t &filename, int64_t, int64_t rowid, const char *text, size_t len, bool) {
			    if (read % kRowsPerCheck == 0 && context.IsInterrupted()) {
				    cancel = 1;
				    throw InterruptException();
			    }
			    read++;
			    if (interrupt_after > 0 && read == interrupt_after) {
				    context.Interrupt();
			    }
			    if (!current || filename.GetSize() != current->name.size() ||
			        memcmp(filename.GetData(), current->name.data(), current->name.size()) != 0) {
				    auto found = by_name.find(filename.GetString());
				    if (found == by_name.end()) {
					    Unavailable("the fallback scan of " + table + " returned a row of another file");
				    }
				    current = found->second;
			    }
			    auto &source = *current;
			    if (static_cast<int64_t>(source.docs) >= source.rows) {
				    Unavailable("the fallback scan of " + table + " returned more rows than its liveness at S holds");
			    }
			    if (!source.builder) {
				    if (held >= memory_budget) {
					    MemoryExceeded(held, memory_budget);
				    }
				    char *err = nullptr;
				    source.builder = qs_builder_new(&source.qs, reinterpret_cast<const uint8_t *>(&cancel),
				                                    memory_budget - held, &err);
				    if (!source.builder) {
					    BuilderFailed(err);
				    }
			    }
			    // A NULL text indexes as an empty one, as qod_search_build_segment's.
			    char *add_err = nullptr;
			    auto t0 = std::chrono::steady_clock::now();
			    auto rc = qs_builder_add(source.builder, source.docs, rowid, -1, len ? text : nullptr, len, &add_err);
			    index_ns += Elapsed(t0);
			    if (rc != 0) {
				    BuilderFailed(add_err);
			    }
			    if (record) {
				    source.rowids.push_back(rowid);
			    }
			    source.docs++;
			    if (static_cast<int64_t>(source.docs) == source.rows) {
				    finish(source);
			    }
		    });
	} catch (...) {
		g_index_ns += index_ns;
		if (scan_observer) {
			scan_observer(false);
		}
		throw;
	}
	g_index_ns += index_ns;
	if (scan_observer) {
		scan_observer(false);
	}
	if (context.IsInterrupted()) {
		throw InterruptException();
	}
	std::vector<FallbackSegment> out;
	for (auto &source : sources) {
		if (static_cast<int64_t>(source->docs) != source->rows || !source->open) {
			Unavailable("the fallback scan of " + table + " returned " + std::to_string(source->docs) +
			            " rows of a source where its liveness at S holds " + std::to_string(source->rows));
		}
		FallbackSegment segment;
		segment.open = source->open;
		segment.inlined = source->inlined;
		segment.file_index = source->file_index;
		segment.bytes = source->bytes;
		out.push_back(std::move(segment));
	}
	g_segments += out.size();
	g_rows += read;
	if (record) {
		std::lock_guard<std::mutex> lock(g_hooks_lock);
		for (auto &source : sources) {
			g_rowids.insert(g_rowids.end(), source->rowids.begin(), source->rowids.end());
		}
		std::sort(g_rowids.begin(), g_rowids.end());
	}
	return out;
}

uint64_t MaxUnindexedRows() {
	return g_cap.load();
}

uint64_t MaxUnindexedSources() {
	return g_source_cap.load();
}

void ReadFallbackCapsFromEnvironment() {
	ReadCap("QOD_SEARCH_MAX_UNINDEXED_ROWS", g_cap, kDefaultCap);
	ReadCap("QOD_SEARCH_MAX_UNINDEXED_SOURCES", g_source_cap, kDefaultSourceCap);
}

void SetMaxUnindexedRowsForTest(uint64_t cap) {
	g_cap = cap;
}

void SetMaxUnindexedSourcesForTest(uint64_t cap) {
	g_source_cap = cap;
}

FallbackCounters FallbackCountersForTest() {
	return FallbackCounters {g_calls.load(), g_refused.load(), g_segments.load(), g_rows.load(), g_index_ns.load()};
}

void FallbackSourceObserverForTest(std::function<void(ClientContext &)> observer) {
	std::lock_guard<std::mutex> lock(g_hooks_lock);
	g_source_observer = std::move(observer);
}

void FallbackScanObserverForTest(std::function<void(bool)> observer) {
	std::lock_guard<std::mutex> lock(g_hooks_lock);
	g_scan_observer = std::move(observer);
}

void InterruptFallbackAfterRowsForTest(uint64_t n) {
	g_interrupt_after = n;
}

void RecordFallbackRowidsForTest(bool on) {
	g_record_rowids = on;
}

std::vector<int64_t> FallbackRowidsForTest() {
	std::lock_guard<std::mutex> lock(g_hooks_lock);
	return g_rowids;
}

} // namespace qod::search
