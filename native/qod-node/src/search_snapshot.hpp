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
#include "duckdb/planner/operator/logical_get.hpp"

#include <cstdint>
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
struct FileLiveness {
	std::vector<uint64_t> words;
	int64_t live_count = 0;
	int64_t end = 0; // one past the highest live position (positions at or past it are not live)
	bool IsLive(int64_t position) const {
		auto w = static_cast<size_t>(position >> 6);
		return position >= 0 && w < words.size() && ((words[w] >> (position & 63)) & 1);
	}
};

//! An inlined data table of the target (one per schema version), read in Task 11.
struct InlinedTable {
	std::string table_name;
	int64_t schema_version = -1;
	int64_t live_rows = 0; // rows of it the target's scan returns at S
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
	std::vector<InlinedTable> inlined_tables;
	//! Every ruled call made, as "<function>(<arguments>)" with the names and paths it was given, in
	//! order (a log; the tests also count the files opened).
	std::vector<std::string> reads;
	//! Time spent in the target's own scan for the liveness, in ms (the dominant cost, P6).
	double target_scan_ms = 0;
};

//! S and everything visible at S for `target`'s table, in the client's transaction. Called from a
//! post_bind hook (Task 8) on the search call's target scan, after the verdict (see the precondition
//! above). Throws duckdb::InvalidInputException:
//!   - "search_uncommitted_writes: ..." when the client's transaction has modified the target's
//!     database (inserts and deletes alike, OWNER RULING 2026-11-14);
//!   - "search_snapshot_unavailable: <why>" for anything else it cannot do: a target that is not a
//!     DuckLake table scan, AT (TIMESTAMP => t) (refused for Phase 0, OWNER RULING 2026-11-14), a data
//!     file the listing and the bind data disagree on, a scanned file neither listed nor inlined, and
//!     any failure of the driven calls.
SnapshotView ResolveSnapshotView(duckdb::ClientContext &context, duckdb::LogicalGet &target);

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
//! inlined deletes (P6).
std::vector<std::pair<int64_t, int64_t>> HiddenPositions(const FileLiveness &live, int64_t record_count);

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

} // namespace qod::search
