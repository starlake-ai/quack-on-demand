#pragma once

// QoD Search (spec 9, 7.5, 6.3): the fallback for unindexed rows. Built only
// with QOD_NODE_SEARCH=ON.
//
// A live data file of the target with no usable segment, and the target's inlined rows live at S, are
// read and indexed into in-memory segments for the one query, up to QOD_SEARCH_MAX_UNINDEXED_ROWS
// (default 200,000) per query. Past it the query fails with search_index_behind, naming the table and
// the backlog (no count in the allowed population), never a file path, before any row is read.
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

#include "search_memory.hpp"
#include "search_records.hpp"
#include "search_segments.hpp"
#include "search_snapshot.hpp"

#include "duckdb.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <set>
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
	//! What the query holds for it, charged to its QueryMemory: the segment's bytes and its rowid map.
	uint64_t bytes = 0;
};

//! The fallback segments of `view` for `unindexed` (data files of `view.files` with live rows and no
//! usable segment) and every inlined table with live rows, in that order. First the unindexed rows and
//! sources are counted from the view (FileLiveness::live_count, InlinedTable::live_rows), before any row is
//! read: past `cap` rows, duckdb::PermissionException("QoD: search_index_behind: <table> has <n> unindexed
//! live rows (cap <cap>); retry once the indexer catches up"), past `source_cap` sources, the same with
//! "<n> unindexed sources (cap <cap>)", <table> being catalog.schema.table. With `allowed` (the call's allowed
//! population, spec 7.5) the text names no count, as it counts rows the population hides (ruling 17): "<table>
//! has more unindexed rows than the search reads (cap <cap>)", and "... more unindexed sources than the search
//! reads (cap <cap>)". A PermissionException (ruling 7, Task 10): no user SQL raises one with a chosen text, so
//! the manager answers exactly this shape as a retryable UNAVAILABLE and never books it as a denial. Then every
//! source is read in ONE
//! target scan (ScanTargetTexts, an IN filter on `filename` over the sources' names, review I1), projecting
//! every field of `record` by its DuckLake field id at the scan's snapshot (review C1; a field of `required`,
//! the call's, missing there is search_columns_unsupported, any other reads NULL), so a fallback segment holds
//! the index's fields as a bundle does; each row dispatched to its
//! source's builder; a builder is made when its source's first row arrives and finished once its live count
//! is in. Its memory is the query's (`memory`, QOD_SEARCH_QUERY_MEMORY): every builder, charged before it is made
//! and trued up to qs_builder_memory after every add, so the builders open at once are bounded jointly with the
//! finished segments and everything else the query holds (spec 9, Task 11), and each finished segment's bundle and
//! rowid list, charged before they are made; past the budget QoD: search_memory_exceeded (no figure in the allowed
//! population). The client's interrupt is checked between the scan's chunks and every 2,048 rows (an
//! InterruptException). A source whose rows differ in
//! count from the view's fails closed (QoD: search_snapshot_unavailable). Each segment's QsSource carries the
//! record's index id, table id, fields and MAC key.
std::vector<FallbackSegment> BuildFallbackSegments(duckdb::ClientContext &context, const SnapshotView &view,
                                                   const std::vector<DataFileRef> &unindexed,
                                                   const IndexRecord &record, const std::set<int64_t> &required,
                                                   uint64_t cap, uint64_t source_cap, const FallbackScan &scan,
                                                   QueryMemory &memory, bool allowed);

//! The caps, read once at node start (RegisterSearchFunctions): QOD_SEARCH_MAX_UNINDEXED_ROWS (default
//! 200,000, 0 to 100,000,000) and QOD_SEARCH_MAX_UNINDEXED_SOURCES (default 64, 1 to 4096), as
//! search_knobs.hpp reads them: a value that is not a plain decimal number (a sign, a space, any other
//! character), that overflows 64 bits or that is outside its range is not taken: the default is used and one
//! line on stderr says so, so a bad value never means "no cap". The manager sets both on every node
//! (quack-on-demand.search) and refuses the same values at its boot.
uint64_t MaxUnindexedRows();
uint64_t MaxUnindexedSources();
void ReadFallbackCapsFromEnvironment();
//! The strict parse above, exposed for its test: true and `value` set for a plain decimal number that fits.
bool ParseCap(const char *text, uint64_t &value);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! Test hooks.
void SetMaxUnindexedRowsForTest(uint64_t cap);
void SetMaxUnindexedSourcesForTest(uint64_t cap);
struct FallbackCounters {
	uint64_t calls = 0;    // BuildFallbackSegments calls
	uint64_t refused = 0;  // past a cap
	uint64_t segments = 0; // segments built
	uint64_t rows = 0;     // rows read and indexed
	uint64_t index_ns = 0; // time in the builders (adds, finish) and qs_segment_open; the rest is the scan
	//! The last call's builders (Task 11): the largest builder's memory at its finish, the sum over its builders,
	//! and the most memory its builders held open at once.
	uint64_t builder_max = 0;
	uint64_t builder_sum = 0;
	uint64_t builders_open_peak = 0;
	//! The last finished source: what the query held right before its segment was opened (the bundle and the copy
	//! both charged) and right after (the bundle freed and released), and its bundle's bytes.
	uint64_t held_at_open = 0;
	uint64_t held_after_open = 0;
	uint64_t last_bundle = 0;
};
FallbackCounters FallbackCountersForTest();
//! Called on the factory's thread right before the fallback's scan (a test interrupts the query there).
void FallbackSourceObserverForTest(std::function<void(duckdb::ClientContext &)> observer);
//! Called with true right before and false right after the fallback's scan (F6 counts the opens in between).
void FallbackScanObserverForTest(std::function<void(bool)> observer);
//! When n > 0, the fallback calls the client context's Interrupt() once n rows were read (a mid-scan interrupt).
void InterruptFallbackAfterRowsForTest(uint64_t n);
//! When on, the first source of each fallback counts one live row more than the view holds, so its scan comes
//! back one row short (search_snapshot_unavailable).
void InflateFallbackLivenessForTest(bool on);
//! When on, every fallback segment's rowids are recorded (FallbackRowidsForTest, sorted, last call only).
void RecordFallbackRowidsForTest(bool on);
std::vector<int64_t> FallbackRowidsForTest();
//! When on, every builder stays open until the scan ends and is finished only then, so a query's builders are all
//! open at once (rows of several sources interleaved, which the scan does not promise to avoid).
void HoldFallbackBuildersForTest(bool on);
//! Takes and releases the fallback's own lock (TakeSearchLocksForTest); returns 1.
uint64_t TakeFallbackLocksForTest();

#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
