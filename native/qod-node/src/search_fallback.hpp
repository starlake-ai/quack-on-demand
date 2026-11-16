#pragma once

// QoD Search (Phase 0 part 2, Task 11): the fallback for unindexed rows (spec 9, 7.5, 6.3). Built only
// with QOD_NODE_SEARCH=ON.
//
// A live data file of the target with no usable segment, and the target's inlined rows live at S, are
// read and indexed into in-memory segments for the one query, up to QOD_SEARCH_MAX_UNINDEXED_ROWS
// (default 200,000) per query. Past it the query fails with search_index_behind, naming the table and
// the backlog, never a file path, before any row is read.
//
// OWNER RULING ROWS_M4: the rows come only from the target table's own DuckLake scan at S
// (ScanTargetTextRows, filtered on `filename`), through the client's FileSystem and transaction; never a
// parquet_scan of a data file by path. That scan returns exactly the rows live at S (every rule of 6.3),
// with their rowids, and decrypts with DuckLake's own per-file keys. Every source of a query is read in one
// such scan, and the indexed column is projected by its DuckLake field id (Task 11 review I1 and C1).
//
// A fallback segment is built with the same qs_builder_* calls as a bundle (the same analyzer,
// fieldnorms and exact token counts), so a score from it equals the score a bundle of the same rows
// gives (invariant 7) and does not depend on hidden rows (invariant 5). Its QsSource::path_key is the
// SHA-256 of the data file's path, or of the inlined table's name; its doc ids are the order of the
// scan's rows, each with the scan's rowid; every doc is live at S. Segments are never written to disk
// and never cached: each query builds its own, in its own transaction.

#include "search_segments.hpp"
#include "search_snapshot.hpp"

#include "duckdb.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace qod::search {

//! The target scan the fallback reads through: the search call's resolved table entry and its AT clause
//! (kept by the post_bind hook, SearchTarget, since the LogicalGet is gone by execution).
struct FallbackScan {
	duckdb::TableCatalogEntry &table;
	duckdb::optional_ptr<duckdb::BoundAtClause> at_clause;
};

//! One in-memory segment of the query.
struct FallbackSegment {
	std::shared_ptr<const OpenSegment> open;
	//! The rows of an inlined table (else those of an unindexed data file).
	bool inlined = false;
	//! The data file's index in SnapshotView::files (data files only).
	size_t file_index = 0;
	//! What the query holds for it: the segment's bytes and its rowid map.
	uint64_t bytes = 0;
};

//! The fallback segments of `view` for `unindexed` (data files of `view.files` with live rows and no
//! usable segment) and every inlined table with live rows, in that order. First the unindexed rows and
//! sources are counted from the view (FileLiveness::live_count, InlinedTable::live_rows), before any row is
//! read: past `cap` rows, duckdb::InvalidInputException("QoD: search_index_behind: <table> has <n> unindexed
//! rows (cap <cap>); retry once the indexer catches up"), past `source_cap` sources, the same with
//! "<n> unindexed sources (cap <cap>)", <table> being catalog.schema.table. Then every source is read in ONE
//! target scan (ScanTargetTextRows, an IN filter on `filename` over the sources' names, review I1), projecting
//! the column of DuckLake field id `field_id` at the scan's snapshot (review C1), each row dispatched to its
//! source's builder; a builder is made when its source's first row arrives and finished once its live count
//! is in. Under `memory_budget` bytes in all (QoD: search_memory_exceeded past it: each builder's own
//! accounting under what is left, plus what the finished segments hold). The client's interrupt is checked
//! between the scan's chunks and every 2,048 rows (an InterruptException). A source whose rows differ in
//! count from the view's fails closed (QoD: search_snapshot_unavailable). `source_id` is the call's
//! BundleSourceId (QsSource::index_id).
//! Deviations from the plan's signature: it takes the field id, the scan, the source cap, the source id and
//! the budget, and returns OpenSegments, which own the QsSegment and carry the rowid map the sink needs.
std::vector<FallbackSegment> BuildFallbackSegments(duckdb::ClientContext &context, const SnapshotView &view,
                                                   const std::vector<DataFileRef> &unindexed, int64_t field_id,
                                                   uint64_t cap, uint64_t source_cap, const FallbackScan &scan,
                                                   const std::string &source_id, uint64_t memory_budget);

//! The caps, read once at node start (RegisterSearchFunctions): QOD_SEARCH_MAX_UNINDEXED_ROWS (default
//! 200,000) and QOD_SEARCH_MAX_UNINDEXED_SOURCES (default 64). A value that is not a plain decimal number
//! (a sign, a space, any other character) or that overflows 64 bits is not taken: the default is used and
//! one line on stderr says so, so a bad value never means "no cap". Their forwarding on every runtime and
//! the boot range check are Phase 1.
uint64_t MaxUnindexedRows();
uint64_t MaxUnindexedSources();
void ReadFallbackCapsFromEnvironment();
//! The strict parse above, exposed for its test: true and `value` set for a plain decimal number that fits.
bool ParseCap(const char *text, uint64_t &value);

//! Test hooks.
void SetMaxUnindexedRowsForTest(uint64_t cap);
void SetMaxUnindexedSourcesForTest(uint64_t cap);
struct FallbackCounters {
	uint64_t calls = 0;    // BuildFallbackSegments calls
	uint64_t refused = 0;  // past a cap
	uint64_t segments = 0; // segments built
	uint64_t rows = 0;     // rows read and indexed
	uint64_t index_ns = 0; // time in the builders (adds, finish) and qs_segment_open; the rest is the scan
};
FallbackCounters FallbackCountersForTest();
//! Called on the factory's thread right before the fallback's scan (a test interrupts the query there).
void FallbackSourceObserverForTest(std::function<void(duckdb::ClientContext &)> observer);
//! Called with true right before and false right after the fallback's scan (F6 counts the opens in between).
void FallbackScanObserverForTest(std::function<void(bool)> observer);
//! When n > 0, the fallback calls the client context's Interrupt() once n rows were read (a mid-scan interrupt).
void InterruptFallbackAfterRowsForTest(uint64_t n);
//! When on, every fallback segment's rowids are recorded (FallbackRowidsForTest, sorted, last call only).
void RecordFallbackRowidsForTest(bool on);
std::vector<int64_t> FallbackRowidsForTest();

} // namespace qod::search
