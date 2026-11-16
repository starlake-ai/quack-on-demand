#pragma once

// QoD Search (Phase 0 part 2, Task 8): bundle I/O through DuckDB's FileSystem (the Rust core does no
// I/O), the node's open-segment cache and each segment's rowid map (spec 6.2, 7.1 steps 3 and 4).
// Built only with QOD_NODE_SEARCH=ON.

#include "search_snapshot.hpp"

#include "qod_search.h"

#include "duckdb.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace qod::search {

//! One opened bundle, immutable once loaded: shared by every search that reads it, on any thread.
struct OpenSegment {
	OpenSegment() = default;
	OpenSegment(const OpenSegment &) = delete;
	OpenSegment &operator=(const OpenSegment &) = delete;
	~OpenSegment();

	QsSegment *segment = nullptr;
	uint32_t num_docs = 0;
	//! qs_segment_has_explicit_rowids: false means rowid = first_rowid + doc.
	bool explicit_rowids = false;
	bool has_snapshot_column = false;
	int64_t first_rowid = -1;
	//! Explicit rowids only: (rowid, doc) for every doc, sorted.
	std::vector<std::pair<int64_t, uint32_t>> rowid_docs;
	//! The bundle's size in bytes.
	uint64_t bytes = 0;
};

//! `segment` (owned from now on, freed with the result) with its doc count, flags and rowid map; `bytes` is
//! the bundle's size. Used by LoadSegment and by the fallback's in-memory segments (Task 11).
std::shared_ptr<OpenSegment> WrapSegment(QsSegment *segment, uint64_t bytes);

//! The bundle of `file` at `bundle_path` for `index_id` (the call's BundleSourceId: the index id bound to the
//! indexed column's field id, so a bundle built for another field reads as missing, review C1), from the node's open-segment cache or
//! read whole through FileSystem::GetFileSystem(context) and opened with qs_segment_open against the
//! file's catalog fields (QsSource: path_key = bundle_key, path, file_size, footer_size, row_id_start;
//! record_count and the internal-column flags -1). Null when the bundle is missing, mismatched or
//! corrupt: the file is then unindexed (logged once per file). Sets `file`'s internal-column flags from
//! the bundle (flags_known). The cache is process-wide, keyed by (bundle path, bundle_key, file_size,
//! footer_size) and `index_id`, unbounded (prototype; the spill-leaf LRU is Phase 2).
std::shared_ptr<const OpenSegment> LoadSegment(duckdb::ClientContext &context, const std::string &bundle_path,
                                               const std::string &index_id, DataFileRef &file);

//! Writes `bytes` to `path` atomically (6.2): to `<path>.tmp-<random>` through the FileSystem, then
//! FileSystem::MoveFile onto `path`, creating the folders on the way.
void WriteBundle(duckdb::ClientContext &context, const std::string &path, const uint8_t *bytes, size_t len);

//! Test hooks.
struct SegmentCacheCounters {
	uint64_t reads = 0;   // bundle files opened through the FileSystem (hits do not count)
	uint64_t hits = 0;    // served from the cache
	uint64_t missing = 0; // no bundle at the path
	uint64_t refused = 0; // a bundle qs_segment_open refused
};
SegmentCacheCounters SegmentCacheCountersForTest();
void ClearSegmentCacheForTest();

} // namespace qod::search
