#pragma once

// QoD Search (Phase 0 part 2, Task 6): the snapshot source of a search call, spec 7.1 step 2.
//
// OWNER RULING 2026-11-14 (docs/search-PHASE0.md P1, spec 7.1 step 2 and section 13): a named
// exception, RULE_EXCEPTION(M4a+M4b), and ROWS_M4. Everything here is read by DuckLake's own entry
// points called from C++ on the client's context, in the client's transaction, with no statement: no
// SQL text is built, parsed or bound, so nothing reaches the parse screen or the verdict, and no
// ClientContext::Query runs (a nested Query deadlocks on context_lock, P1 point 1). The calls:
//   - ducklake_current_snapshot(<alias>)                                          S
//   - ducklake_list_files(<alias>, <table>, schema => .., snapshot_version => S)  data and delete files at S
//   - the target table's own DuckLake scan (GetScanFunction), virtual columns only  liveness at S
//   - the same scan with the indexed column, filtered on `filename`  the fallback's rows (Task 11)
//   - parquet_scan(<a delete file that listing named>), in ReadDeletePositions only  positions, snapshots
// plus the target scan's own bind data (its MultiFileList, GetAllFiles), which the client's statement
// already built. Every function is looked up in the system catalog by a fixed name (no client macro
// can shadow it); the alias, schema and table come from the target LogicalGet's resolved catalog entry,
// never from client text; every path read is one the listing named for the target table. Nothing read
// here is returned to the client.
//
// Precondition (spec 13, "only for a table the statement's verdict admitted"): the caller runs after
// qod-node's own post_bind has admitted the statement (Task 8 calls it from inside that hook, after
// the verdict); these functions do not check it.

#include "duckdb.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace qod::search {

//! One data file visible at S, from the target scan's bind data (GetAllFiles) matched by path to
//! ducklake_list_files at S.
struct DataFileRef {
	//! -1: no ruled call returns a data file id (ducklake_list_files and the bind data name files by
	//! path only, P6). Bundles are keyed by `bundle_key` instead (OWNER RULING 2026-11-14).
	int64_t data_file_id = -1;
	std::string path; // absolute, as the scan opens it
	//! Lowercase hex SHA-256 of `path`: the bundle's key (OWNER RULING 2026-11-14). The bundle footer
	//! records path, file size and footer size; the reader checks all three against this entry.
	std::string bundle_key;
	int64_t row_id_start = -1;   // -1 when the catalog holds NULL
	int64_t begin_snapshot = -1; // the file's snapshot as the scan applies it (-1: NULL)
	uint64_t file_size = 0;
	uint64_t footer_size = 0;
	//! -1: no ruled call returns it without reading the data file's footer, which spec 13 does not
	//! name; Task 8 takes it from the bundle (the indexed file's row count).
	int64_t record_count = -1;
	bool has_deletes = false;    // the bind data flags deletes (a delete file; NOT inlined-only deletes)
	bool flags_known = false;    // the two flags below are set (Task 8, Task 11)
	bool has_internal_row_id = false;   // the file carries _ducklake_internal_row_id
	bool has_internal_snapshot = false; // the file carries _ducklake_internal_snapshot_id
	std::string encryption_key; // empty unless encrypted (never logged)
	//! A data file the client's transaction wrote (Task 14): listed by the target scan's bind data with no snapshot,
	//! absent from ducklake_list_files, never bundled; its live rows go to the fallback.
	bool transaction_local = false;
};

//! One delete file visible at S.
struct DeleteFileRef {
	int64_t delete_file_id = -1; // -1: not returned by ducklake_list_files (P6)
	int64_t data_file_id = -1;   // -1, as DataFileRef::data_file_id
	size_t file_index = 0;       // the data file it deletes from, an index into SnapshotView::files
	std::string data_file_path;  // that file's path
	std::string path;
	int64_t begin_snapshot = -1; // -1: not returned; each position carries its own snapshot (6.3 rule 3)
	uint64_t file_size = 0;
	uint64_t footer_size = 0;
	std::string encryption_key;
};

//! The positions of one data file live at S in the client's transaction: the file_row_numbers the
//! target's own scan returns (every rule DuckLake applies: delete files with their per-row snapshots,
//! inlined deletes, rows of a backdated file stamped after S). Bit p of `words` set = position p live.
//!
//! `words` as resolved; a view the liveness cache holds (Task 12) has its words in one block of DuckDB's buffer
//! manager instead (`block_words`, `block_count`, the block owned by SnapshotView::liveness_block) and `words`
//! empty. Readers take Words() and WordCount(), which name whichever holds them.
struct FileLiveness {
	std::vector<uint64_t> words;
	const uint64_t *block_words = nullptr;
	size_t block_count = 0;
	int64_t live_count = 0;
	int64_t end = 0; // one past the highest live position (positions at or past it are not live)
	const uint64_t *Words() const {
		return block_words ? block_words : words.data();
	}
	size_t WordCount() const {
		return block_words ? block_count : words.size();
	}
	bool IsLive(int64_t position) const {
		auto w = static_cast<size_t>(position >> 6);
		return position >= 0 && w < WordCount() && ((Words()[w] >> (position & 63)) & 1);
	}
};

//! An inlined data table of the target (one per schema version), read in Task 11.
struct InlinedTable {
	std::string table_name;
	int64_t schema_version = -1;
	int64_t live_rows = 0; // rows of it the target's scan returns at S
	//! The client transaction's own inlined rows (Task 14): the bind data's entry flagged transaction_local_data,
	//! named __ducklake_inlined_transaction_local_data; schema_version -1.
	bool transaction_local = false;
};

struct SnapshotView {
	int64_t snapshot_id = -1; // S
	//! The table id, parsed from an inlined data table's name (ducklake_inlined_data_<table>_<version>)
	//! when the target has one, else -1 (no ruled call returns it). Never a cache key: use table_oid.
	int64_t table_id = -1;
	//! The resolved entry's catalog oid (DuckLake keeps one oid per table id): the key for caches.
	uint64_t table_oid = 0;
	//! Empty: no ruled call returns the catalog's data_path; the index's own record names the bundle
	//! folder (Task 8).
	std::string data_path;
	std::string catalog_alias; // the resolved catalog of the target (never client text)
	std::string schema_name;
	std::string table_name;
	std::vector<DataFileRef> files;
	std::vector<DeleteFileRef> delete_files;
	//! Aligned with `files`: each file's live positions at S.
	std::vector<FileLiveness> liveness;
	//! The buffer-manager block holding every file's liveness words of a view the liveness cache holds (Task 12;
	//! FileLiveness::block_words point into it), shared by every copy of the view; null otherwise.
	std::shared_ptr<void> liveness_block;
	std::vector<InlinedTable> inlined_tables;
	//! The names of the client transaction's own sources of the target (Task 14), as the target scan's bind data
	//! lists them and its `filename` column names their rows: each data file the transaction wrote (listed with no
	//! snapshot) and its transaction-local inlined rows (the entry flagged transaction_local_data). Empty outside a
	//! transaction that modified the target's database.
	std::vector<std::string> transaction_local_names;
	//! Every ruled call made, as "<function>(<arguments>)" with the names and paths it was given, in
	//! order (a log; the tests also count the files opened).
	std::vector<std::string> reads;
	//! Time spent in the target's own scan for the liveness, in ms (the dominant cost, P6).
	double target_scan_ms = 0;
};

//! S and everything visible at S for `target`'s table, in the client's transaction. Called from a
//! post_bind hook (Task 8) on the search call's target scan, after the verdict (see the precondition
//! above). Read-your-writes (Task 14): in a transaction that has modified the target's database the view is
//! the transaction's own. The target scan applies its deletes (liveness); its data files and its inlined rows
//! are the bind data's entries with no snapshot and the entry flagged transaction_local_data, kept with
//! `transaction_local` set and named in `transaction_local_names`; a committed file the listing at S names while
//! the scan's list does not is one the transaction deleted whole (DuckLake drops it from the list), so it has
//! no live doc and is left out (at the current snapshot only: an AT read keeps every file of its snapshot).
//! Outside such a transaction either kind of entry fails closed; outside it or under an AT clause, so does a
//! listed file the scan's list lacks. Throws duckdb::InvalidInputException:
//!   - "search_at_timestamp_unsupported: ..." for AT (TIMESTAMP => t) on the target scan (refused through
//!     Phase 1: OWNER RULING 2026-11-14, kept by ruling 1 of 2026-11-16; spec 4.2 rule 4's code);
//!   - "search_snapshot_unavailable: <why>" for anything else it cannot do: a target that is not a
//!     DuckLake table scan, a data
//!     file the listing and the bind data disagree on, a scanned file neither listed nor inlined, and
//!     any failure of the driven calls.
SnapshotView ResolveSnapshotView(duckdb::ClientContext &context, duckdb::LogicalGet &target);

//! S for a `target` with no AT clause, read as ResolveSnapshotView reads it: ducklake_current_snapshot of
//! the target's resolved catalog, in the client's transaction (Task 10's cache key; the cache never
//! serves a target with an AT clause). Throws search_snapshot_unavailable as ResolveSnapshotView does.
int64_t CurrentSnapshotId(duckdb::ClientContext &context, duckdb::LogicalGet &target);

//! The positions a delete file deletes, each with its own snapshot when the file has the column (-1
//! when it has none), as the file holds them: positions stamped after S are the caller's to drop (6.3
//! rule 3). Read by parquet_scan in the client's transaction through DuckDB's FileSystem; `file` must
//! be one of `view.delete_files`, else it throws search_snapshot_unavailable. The call is appended to
//! `reads` when given. ResolveSnapshotView does not call it (liveness comes from the scan).
std::vector<std::pair<int64_t, int64_t>> ReadDeletePositions(duckdb::ClientContext &context, const SnapshotView &view,
                                                             const DeleteFileRef &file,
                                                             std::vector<std::string> *reads = nullptr);

//! The positions of a file not live at S, as [begin, end) runs, given its row count (from the
//! bundle): deleted at S by any means, or stamped after S in a backdated file. Not the catalog's
//! inlined deletes (P6). Reads the bitset word by word.
std::vector<std::pair<int64_t, int64_t>> HiddenPositions(const FileLiveness &live, int64_t record_count);

//! One past the highest position within a file ResolveSnapshotView marks live: a scan position outside
//! [0, kMaxFilePosition) throws search_snapshot_unavailable before the bitset grows toward it.
constexpr int64_t kMaxFilePosition = int64_t(1) << 40;

#ifdef QOD_NODE_TEST_HOOKS
//! Test hook: marks [lo, hi) live in `live.words` as ResolveSnapshotView does (bounds checked the same
//! way; live_count and end are left alone).
void SetLiveRangeForTest(FileLiveness &live, int64_t lo, int64_t hi);
#endif // QOD_NODE_TEST_HOOKS

//! Which file a `filename` the target's scan returned names: its index in `view.files`, -1 for one of
//! `view.inlined_tables`; anything else throws search_snapshot_unavailable (never a silent miss).
int64_t FileIndexForScanName(const SnapshotView &view, const std::string &filename);

//! One row of the target's own scan at S: the file it came from (a data file's path, or an inlined
//! table's name), its position there, and its rowid.
struct TargetRow {
	std::string filename;
	int64_t file_row_number = -1;
	int64_t rowid = -1;
};

//! The rows live at S of the target's own DuckLake scan (GetScanFunction of its resolved entry, at its
//! AT clause), with no data column read: Task 11's route to the inlined rows (`only_file` = an inlined
//! table's name). `only_file`, when given, is a filter on `filename`: DuckLake's reader still opens
//! every file's footer and delete file, but returns no row of another file (P6).
std::vector<TargetRow> ScanTargetRows(duckdb::ClientContext &context, duckdb::LogicalGet &target,
                                      const std::string *only_file = nullptr);

//! One row of the target's own scan at S with one data column (Task 11): TargetRow plus the column's
//! text (empty for NULL).
struct TargetRowText {
	std::string filename;
	int64_t file_row_number = -1;
	int64_t rowid = -1;
	std::string text;
};

//! ScanTargetRows with `column` projected after the virtual columns: the same scan of the target's own
//! resolved entry at its AT clause (OWNER RULING ROWS_M4: never a parquet_scan of a data file by path),
//! through the client's FileSystem and transaction, so DuckLake applies every liveness rule at S and
//! decrypts with its own per-file keys. `column` is named as the target's scan names it at S and is projected
//! by its DuckLake field id; a stored VARCHAR column (search_columns_unsupported otherwise). Every row is
//! held at once: tests and small reads only; the fallback streams through ScanTargetTextRows.
std::vector<TargetRowText> ScanTargetRowsWithColumn(duckdb::ClientContext &context, duckdb::LogicalGet &target,
                                                    const std::string &column, const std::string *only_file);

//! The DuckLake field id of `column` (matched without case) in a DuckLake scan's bind data, -1 when it has
//! none or the bind data is not a read mapped by field id.
int64_t FieldIdOf(duckdb::FunctionData *bind_data, const std::string &column);

//! Whether a DuckLake scan's bind data holds a column of DuckLake field id `field_id`.
bool HasFieldId(duckdb::FunctionData *bind_data, int64_t field_id);

//! The DuckLake field id of `column` in `table` now: a scan of the entry bound with no AT clause (the entry
//! the verdict judged, review C1), -1 when none.
int64_t CurrentFieldId(duckdb::ClientContext &context, duckdb::TableCatalogEntry &table, const std::string &column);

//! Each row: its file name (a data file's path or an inlined table's name), position, rowid, and the
//! column's bytes (`text` null and `is_null` true for NULL). The bytes live only for the call.
using TargetTextSink = std::function<void(const duckdb::string_t &filename, int64_t file_row_number, int64_t rowid,
                                          const char *text, size_t len, bool is_null)>;

//! The streaming form of ScanTargetRowsWithColumn, from the target's resolved entry and its AT clause (the
//! LogicalGet is gone by execution; the search keeps both, SearchTarget), projecting the column of DuckLake
//! field id `field_id` at that snapshot (none there: search_columns_unsupported). One scan for every name of
//! `only_files` (an IN filter on `filename`; null: every file). A row of a file outside `only_files`, or with
//! no file, position or rowid, fails closed (search_snapshot_unavailable). The client's interrupt is checked
//! between chunks. Errors of the sink pass through as they are.
void ScanTargetTextRows(duckdb::ClientContext &context, duckdb::TableCatalogEntry &table,
                        duckdb::optional_ptr<duckdb::BoundAtClause> at_clause, int64_t field_id,
                        const std::vector<std::string> *only_files, const TargetTextSink &sink);

//! One column's value of a row: `text` null and `is_null` true for NULL; the bytes live only for the call.
struct TextCell {
	const char *text = nullptr;
	size_t len = 0;
	bool is_null = true;
};

//! Each row: its file name, position, rowid and one cell per asked field id, in their order.
using TargetTextsSink = std::function<void(const duckdb::string_t &filename, int64_t file_row_number, int64_t rowid,
                                           const std::vector<TextCell> &cells)>;

//! ScanTargetTextRows over several columns: the fields of DuckLake field ids `field_ids` (the index's, in
//! index order) projected at the scan's snapshot, in one scan. A field id of `required` (the call's) that the
//! table at that snapshot does not hold, or holds as another type than VARCHAR, is search_columns_unsupported;
//! any other such field reads NULL on every row (a column added, dropped or retyped since, which the call does
//! not query). The rest as ScanTargetTextRows.
void ScanTargetTexts(duckdb::ClientContext &context, duckdb::TableCatalogEntry &table,
                     duckdb::optional_ptr<duckdb::BoundAtClause> at_clause, const std::vector<int64_t> &field_ids,
                     const std::set<int64_t> &required, const std::vector<std::string> *only_files,
                     const TargetTextsSink &sink);

#ifdef QOD_NODE_TEST_HOOKS
//! Test hook: how many ScanTargetTextRows and ScanTargetTexts scans were driven in the process.
uint64_t TextScansForTest();
//! Test hook: each ScanTargetTexts scan (the fallback's) since the last reset, in order: the table it scanned, as
//! the resolved entry names it (catalog alias, schema, table), and the files it was given (`only_files`).
struct TextScanRecord {
	std::string catalog, schema, table;
	std::vector<std::string> files;
};
std::vector<TextScanRecord> TextScanTargetsForTest();
void ResetTextScanTargetsForTest();
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
