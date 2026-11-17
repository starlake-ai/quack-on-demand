#include "search_fallback.hpp"

#include "crypto.hpp"
#include "search_knobs.hpp"
#include "search_memory.hpp"

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
#include <set>
#include <unordered_map>

namespace qod::search {

using namespace duckdb;

namespace {

constexpr uint64_t kDefaultCap = kMaxUnindexedRowsKnob.fallback;
constexpr uint64_t kDefaultSourceCap = kMaxUnindexedSourcesKnob.fallback;
//! Rows of the fallback's scan between two interrupt checks (spec 9 asks for at most 65,536); the scan
//! itself checks between chunks too (DriveChunks).
constexpr uint64_t kRowsPerCheck = 2048;

std::atomic<uint64_t> g_cap {kDefaultCap};
std::atomic<uint64_t> g_source_cap {kDefaultSourceCap};
std::atomic<uint64_t> g_calls {0}, g_refused {0}, g_segments {0}, g_rows {0}, g_index_ns {0};
std::atomic<uint64_t> g_builder_max {0}, g_builder_sum {0}, g_builders_open_peak {0};
std::atomic<uint64_t> g_held_at_open {0}, g_held_after_open {0}, g_last_bundle {0};
std::atomic<bool> g_hold_builders {false};
std::atomic<uint64_t> g_interrupt_after {0};
std::atomic<bool> g_record_rowids {false};
std::atomic<bool> g_inflate_liveness {false};
std::mutex g_hooks_lock;
std::vector<int64_t> g_rowids;
std::function<void(ClientContext &)> g_source_observer;
std::function<void(bool)> g_scan_observer;

std::string Taken(char *err) {
	std::string text = err ? err : "search_internal: no message";
	qs_string_free(err);
	return text;
}

//! A library error of the builder as the query's error: a cancel as DuckDB's interrupt, a memory refusal as the
//! query's own (no figure in the allowed population), anything else as an ordinary error (never an internal one,
//! which would invalidate the database).
[[noreturn]] void BuilderFailed(char *err, const QueryMemory &memory) {
	auto text = Taken(err);
	if (StringUtil::StartsWith(text, "search_cancelled")) {
		throw InterruptException();
	}
	if (StringUtil::StartsWith(text, "search_memory_exceeded")) {
		memory.Exceeded(text.substr(text.find(':') + 1));
	}
	throw InvalidInputException("QoD: " + text);
}

//! What a builder holds right after qs_builder_new, charged before it is made: Tantivy's initial term table
//! sizes it (13.6 MB measured, c_abi_builder_memory), and the charge is trued up to qs_builder_memory once made.
constexpr uint64_t kBuilderStartBytes = uint64_t(16) << 20;


[[noreturn]] void Unavailable(const std::string &why) {
	throw InvalidInputException("QoD: search_snapshot_unavailable: " + why);
}

//! search_index_behind (spec 9, ruling 7): a PermissionException, whose text no user SQL can choose (a user's
//! error('...') is an InvalidInputException), so the manager answers exactly this shape as a retryable UNAVAILABLE
//! (FlightSqlRouter.isSearchIndexBehind) and never as a denial. `what` names the backlog in the table population and
//! no count in the allowed one, as that count includes hidden rows (OWNER RULING 2026-11-16, ruling 17); the cap is
//! configuration and stays. The router matches the prefix and the fixed ending "; retry once the indexer catches
//! up" both: change one only with FlightSqlRouter.isSearchIndexBehind.
[[noreturn]] void IndexBehind(const std::string &table, const std::string &what, uint64_t cap) {
	throw PermissionException("QoD: search_index_behind: " + table + " has " + what + " (cap " + std::to_string(cap) +
	                          "); retry once the indexer catches up");
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
	std::vector<QsField> fields; // qs.fields points here
	QsBuilder *builder = nullptr;
	//! What the query's memory holds for the builder (its memory as last read, qs_builder_memory).
	uint64_t builder_charged = 0;
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
                                                   const std::vector<DataFileRef> &unindexed,
                                                   const IndexRecord &record, const std::set<int64_t> &required,
                                                   uint64_t cap, uint64_t source_cap, const FallbackScan &scan,
                                                   QueryMemory &memory, bool allowed) {
	g_calls++;
	auto record_rowids = g_record_rowids.load();
	if (record_rowids) {
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
		IndexBehind(table,
		            allowed ? "more unindexed rows than the search reads"
		                    : std::to_string(backlog) + " unindexed live rows",
		            cap);
	}
	if (sources.size() > source_cap) {
		g_refused++;
		IndexBehind(table,
		            allowed ? "more unindexed sources than the search reads"
		                    : std::to_string(sources.size()) + " unindexed sources",
		            source_cap);
	}
	if (sources.empty()) {
		return {};
	}
	if (g_inflate_liveness.load()) {
		sources.front()->rows++;
	}
	std::vector<std::string> names;
	std::unordered_map<std::string, Source *> by_name;
	for (auto &source : sources) {
		source->key = qod::Hex(qod::Sha256(source->name));
		auto &qs = source->qs;
		for (auto &field : record.fields) {
			source->fields.push_back(QsField {field.column.c_str(), field.field_id});
		}
		qs.index_id = record.index_id.c_str();
		qs.table_id = record.table_id;
		qs.fields = source->fields.data();
		qs.n_fields = source->fields.size();
		// The record's own 32 bytes: an in-memory segment is opened, so it carries a MAC like any bundle.
		qs.mac_key = record.mac_key.data();
		qs.mac_key_len = record.mac_key.size();
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
	uint64_t read = 0;
	uint64_t index_ns = 0;
	auto interrupt_after = g_interrupt_after.load();
	auto hold = g_hold_builders.load();
	uint64_t builder_max = 0, builder_sum = 0, open_now = 0, open_peak = 0;
	uint64_t held_at_open = 0, held_after_open = 0, last_bundle = 0;
	Source *current = nullptr;
	// The builders' memory is the query's (spec 9, the joint bound): each is charged before it is made and trued
	// up to qs_builder_memory after every add, so the builders open at once and the finished segments share one
	// budget, whatever order the scan's rows of several sources come in.
	auto settle = [&](Source &source, uint64_t charged) {
		auto now = qs_builder_memory(source.builder);
		if (now > charged) {
			memory.Charge(now - charged, "fallback builders");
		} else {
			memory.Release(charged - now);
		}
		open_now = open_now - source.builder_charged + now;
		open_peak = std::max(open_peak, open_now);
		source.builder_charged = now;
	};
	// Finishes a source whose rows are all in: its builder becomes an in-memory segment.
	auto finish = [&](Source &source) {
		uint8_t *bytes = nullptr;
		size_t len = 0;
		uint64_t built = 0;
		char *err = nullptr;
		auto builder = source.builder;
		auto builder_bytes = source.builder_charged;
		builder_max = std::max(builder_max, builder_bytes);
		builder_sum += builder_bytes;
		// The finish's own bound (twice what the builder holds: the finalized files, their packing, the bundle),
		// charged before; the builder checks it against what the query leaves, before it finalizes the writer.
		auto bound = qs_builder_finish_bound(builder);
		memory.Charge(bound, "fallback builders' bundles");
		qs_builder_set_budget(builder, memory.Remaining() + builder_bytes + bound);
		source.builder = nullptr;
		auto t0 = std::chrono::steady_clock::now();
		auto rc = qs_builder_finish(builder, &bytes, &len, &built, &err);
		source.builder_charged = 0;
		open_now -= builder_bytes;
		if (rc != 0) {
			memory.Release(builder_bytes + bound);
			BuilderFailed(err, memory);
		}
		// The builder is gone and its bundle (`len` bytes) lives: the charge becomes the bundle's, never less than it
		// (review I2).
		if (len > builder_bytes + bound) {
			memory.Charge(len - (builder_bytes + bound), "fallback builders' bundles");
		} else {
			memory.Release(builder_bytes + bound - len);
		}
		// The segment copies the bundle, and an explicit segment keeps its (rowid, doc) list: both charged before
		// they are made, beside the bundle's own charge, the list at its largest (one entry per doc), trued up once
		// the segment says; the bundle's charge goes once it is freed.
		auto charged = len + built * sizeof(std::pair<int64_t, uint32_t>);
		try {
			memory.Charge(charged, "fallback segments");
		} catch (...) {
			qs_bytes_free(bytes, len);
			memory.Release(len);
			throw;
		}
		held_at_open = memory.Held();
		auto segment = qs_segment_open(bytes, len, &source.qs, &err);
		qs_bytes_free(bytes, len);
		memory.Release(len);
		held_after_open = memory.Held();
		last_bundle = len;
		index_ns += Elapsed(t0);
		if (!segment) {
			// A segment built here and refused here: an ordinary error, never a different answer.
			throw InvalidInputException("QoD: search_internal: a fallback segment could not be opened: " + Taken(err));
		}
		auto open = WrapSegment(segment, len);
		source.bytes = len + open->rowid_docs.capacity() * sizeof(open->rowid_docs[0]);
		if (source.bytes > charged) {
			memory.Charge(source.bytes - charged, "fallback segments");
		} else {
			memory.Release(charged - source.bytes);
		}
		source.open = std::move(open);
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
		std::vector<int64_t> field_ids;
		for (auto &field : record.fields) {
			field_ids.push_back(field.field_id);
		}
		std::vector<QsText> texts(field_ids.size());
		ScanTargetTexts(
		    context, scan.table, scan.at_clause, field_ids, required, &names,
		    [&](const string_t &filename, int64_t, int64_t rowid, const std::vector<TextCell> &cells) {
			    // Every kRowsPerCheck rows (spec 9 asks for at most 65,536 docs of fallback indexing).
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
				    memory.Charge(kBuilderStartBytes, "fallback builders");
				    char *err = nullptr;
				    // The builder's own budget is what the query leaves it, set again before every call below.
				    source.builder = qs_builder_new(&source.qs, reinterpret_cast<const uint8_t *>(&cancel),
				                                    memory.Remaining() + kBuilderStartBytes, &err);
				    if (!source.builder) {
					    memory.Release(kBuilderStartBytes);
					    BuilderFailed(err, memory);
				    }
				    settle(source, kBuilderStartBytes);
			    }
			    // A NULL text indexes as an empty one, as qod_search_build_segment's.
			    for (size_t f = 0; f < cells.size(); f++) {
				    texts[f] = cells[f].is_null || cells[f].len == 0 ? QsText {nullptr, 0} : QsText {cells[f].text, cells[f].len};
			    }
			    // The builder's own bound on what this add may allocate (from the texts' lengths alone), charged before it,
			    // and the builder's budget set to what the query leaves it: the add is refused before the writer reads
			    // the texts when the two pass it (Task 11 fix round 1).
			    auto estimate = qs_builder_add_bound(source.builder, texts.data(), texts.size());
			    memory.Charge(estimate, "fallback builders");
			    qs_builder_set_budget(source.builder, memory.Remaining() + source.builder_charged + estimate);
			    char *add_err = nullptr;
			    auto t0 = std::chrono::steady_clock::now();
			    auto rc = qs_builder_add(source.builder, source.docs, rowid, -1, texts.data(), texts.size(), &add_err);
			    index_ns += Elapsed(t0);
			    if (rc != 0) {
				    memory.Release(estimate);
				    BuilderFailed(add_err, memory);
			    }
			    settle(source, source.builder_charged + estimate);
			    if (record_rowids) {
				    source.rowids.push_back(rowid);
			    }
			    source.docs++;
			    if (static_cast<int64_t>(source.docs) == source.rows && !hold) {
				    finish(source);
			    }
		    });
		for (auto &source : sources) {
			// HoldFallbackBuildersForTest: the builders were all open until now.
			if (source->builder && static_cast<int64_t>(source->docs) == source->rows) {
				finish(*source);
			}
		}
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
			// In the allowed population no figure (security review m1): both count rows the population hides.
			Unavailable(allowed ? "the fallback scan of " + table +
			                          " returned another count of rows of a source than its liveness at S holds"
			                    : "the fallback scan of " + table + " returned " + std::to_string(source->docs) +
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
	g_builder_max = builder_max;
	g_builder_sum = builder_sum;
	g_builders_open_peak = open_peak;
	g_held_at_open = held_at_open;
	g_held_after_open = held_after_open;
	g_last_bundle = last_bundle;
	if (record_rowids) {
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
	g_cap = ReadKnob(kMaxUnindexedRowsKnob);
	g_source_cap = ReadKnob(kMaxUnindexedSourcesKnob);
}

#ifdef QOD_NODE_TEST_HOOKS
void SetMaxUnindexedRowsForTest(uint64_t cap) {
	g_cap = cap;
}

void SetMaxUnindexedSourcesForTest(uint64_t cap) {
	g_source_cap = cap;
}

FallbackCounters FallbackCountersForTest() {
	return FallbackCounters {g_calls.load(),       g_refused.load(),     g_segments.load(),
	                         g_rows.load(),        g_index_ns.load(),    g_builder_max.load(),
	                         g_builder_sum.load(), g_builders_open_peak.load(), g_held_at_open.load(),
	                         g_held_after_open.load(), g_last_bundle.load()};
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

void InflateFallbackLivenessForTest(bool on) {
	g_inflate_liveness = on;
}

void RecordFallbackRowidsForTest(bool on) {
	g_record_rowids = on;
}

void HoldFallbackBuildersForTest(bool on) {
	g_hold_builders = on;
}

std::vector<int64_t> FallbackRowidsForTest() {
	std::lock_guard<std::mutex> lock(g_hooks_lock);
	return g_rowids;
}

uint64_t TakeFallbackLocksForTest() {
	std::lock_guard<std::mutex> lock(g_hooks_lock);
	return 1;
}
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
