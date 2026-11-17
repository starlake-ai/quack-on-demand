#pragma once

// QoD Search (spec 6.2, 7.1 steps 3 and 4): bundle I/O through DuckDB's FileSystem (the Rust core does no
// I/O), the node's open-segment cache and each segment's rowid map (spec 6.2, 7.1 steps 3 and 4).
// Built only with QOD_NODE_SEARCH=ON.

#include "search_knobs.hpp"
#include "search_memory.hpp"
#include "search_records.hpp"
#include "search_snapshot.hpp"

#include "qod_search.h"

#include "duckdb.hpp"
#include "duckdb/storage/buffer/buffer_handle.hpp"
#include "duckdb/storage/buffer_manager.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace qod::search {

//! One opened bundle, immutable once loaded: shared by every search that reads it, on any thread.
//!
//! A bundle LoadSegment reads (Task 12, spec 9, ruling 8) holds its bytes in one block of DuckDB's buffer manager
//! (pinned for the segment's life, so memory_limit counts it), opened in place with qs_segment_open_borrowed; an
//! explicit segment's (rowid, doc) list is a block of its own. What the library holds besides (heap_bytes, its
//! token count and rowid columns: 4 bytes a doc a field, and 8 a doc for each explicit column) is reserved in the
//! buffer manager for a cache entry (ReserveMemory) and charged to the search's QueryMemory for a copy the cache did
//! not take (Task 12, provisional: C0 measured 1.5% of a 1M-doc bundle of 615-character docs, the C1 fixture's
//! 3-token docs 26.9%). The fallback's in-memory segments (WrapSegment) hold their list in `rowid_docs`.
struct OpenSegment {
	using RowidDoc = std::pair<int64_t, uint32_t>;

	OpenSegment() = default;
	OpenSegment(const OpenSegment &) = delete;
	OpenSegment &operator=(const OpenSegment &) = delete;
	//! Frees the segment first, then the blocks it read, then the reservation.
	~OpenSegment();

	QsSegment *segment = nullptr;
	uint32_t num_docs = 0;
	//! qs_segment_has_explicit_rowids: false means rowid = first_rowid + doc.
	bool explicit_rowids = false;
	bool has_snapshot_column = false;
	int64_t first_rowid = -1;
	//! Explicit rowids, a segment WrapSegment made (the fallback's, tests'): (rowid, doc) for every doc, sorted.
	std::vector<RowidDoc> rowid_docs;
	//! The bundle's size in bytes.
	uint64_t bytes = 0;
	//! qs_segment_heap_bytes: what the library holds for the segment besides the bundle's bytes.
	uint64_t heap_bytes = 0;
	//! Everything the segment holds: the bundle, its (rowid, doc) list, heap_bytes. A cache entry's charge
	//! against the cache's byte bound; a search's charge to its QueryMemory for a copy the cache did not take.
	uint64_t held_bytes = 0;

	//! The explicit (rowid, doc) list, sorted: num_docs entries for an explicit segment, none for an implicit one.
	const RowidDoc *RowidDocs() const {
		return rowid_block_docs ? rowid_block_docs : rowid_docs.data();
	}
	size_t RowidDocCount() const {
		return rowid_block_docs ? rowid_block_count : rowid_docs.size();
	}

	// Where LoadSegment's bytes live (none for WrapSegment's).
	duckdb::BufferHandle bundle_block;
	duckdb::BufferHandle rowid_block;
	const RowidDoc *rowid_block_docs = nullptr;
	size_t rowid_block_count = 0;
	//! The buffer manager that holds `reserved` bytes for the segment (ReserveMemory), freed with it.
	duckdb::BufferManager *reserved_in = nullptr;
	uint64_t reserved = 0;
};

//! `segment` (owned from now on, freed with the result) with its doc count, flags and rowid map; `bytes` is
//! the bundle's size. Used by LoadSegment and by the fallback's in-memory segments (Task 11).
std::shared_ptr<OpenSegment> WrapSegment(QsSegment *segment, uint64_t bytes);

//! The default of the largest bundle LoadSegment reads (QOD_SEARCH_MAX_BUNDLE_BYTES, 1 MiB to 64 GiB, read at
//! registration, search_knobs.hpp): a bundle object larger than the cap is never read, and its file is unindexed
//! as for a missing bundle (Task 3's security review: the size is checked before any byte is read, so no object can
//! make the node allocate past it). 1 GiB, about four times the largest bundle the prototype measured (267.6 MB,
//! one segment of 10M rows). A bundle is read whole into one buffer-manager block, so one under the cap but above
//! memory_limit fails the search (search_memory_exceeded) instead.
constexpr uint64_t kMaxBundleBytes = kMaxBundleBytesKnob.fallback;

//! The QsSource that `record`'s bundle of `file` records: the record's index id, table id, fields (in index
//! order) and MAC key, and the file's catalog fields. Its pointers point into `record`, `file` and `fields`,
//! which must outlive it.
QsSource BundleSource(const IndexRecord &record, const DataFileRef &file, std::vector<QsField> &fields);

//! The bundle of `file` at `bundle_path` for `record`, from the node's open-segment cache or read whole
//! through FileSystem::GetFileSystem(context) into a block of DuckDB's buffer manager and opened in place with
//! qs_segment_open_borrowed against BundleSource (the record's index id, table id, fields and MAC key; the file's
//! path_key = bundle_key, path, file_size, footer_size, row_id_start; record_count and the internal-column flags
//! -1). Null when the bundle is missing, larger than kMaxBundleBytes (its size read first, none of its bytes),
//! unreadable, or refused by the library for any reason (bundle_mac_mismatch, bundle_mismatch, bundle_corrupt):
//! the file is then unindexed, logged once per file with no key material, and every reason takes exactly that one
//! path, so no client-visible answer differs by it. Sets `file`'s internal-column flags from the bundle
//! (flags_known).
//!
//! The caches (Task 12) are the database instance's own (its ObjectCache), so their blocks go with it:
//! - Open segments, keyed by the bundle path, the file's key and sizes, the record's index id, table id and field
//!   ids, and a digest of its MAC key; an LRU within SearchCachesBound less the greater of what the liveness cache
//!   holds and its slice (LivenessSlice), an entry
//!   counted as its held_bytes, evicting only entries no running search holds. An entry takes the bundle's
//!   block, its list's block and a reservation of its heap_bytes from the buffer manager; when the buffer manager
//!   refuses one (memory_limit) once the cache's unheld entries are evicted, or the entry does not fit the bound
//!   beside the entries running searches hold, the search keeps its copy as temporary memory, charged to
//!   `memory` (QueryMemory::Charge, held_bytes), and the cache does not take it. A block the buffer manager
//!   refuses for that copy too fails the search, search_memory_exceeded.
//! - Missing bundles (OWNER RULING 2026-11-16, D8): a bundle found missing, or refused (oversized, unreadable,
//!   refused by the library), is remembered by (index id, path key, file size, footer size) with the record's
//!   segments_version and the time, and a lookup while the record's version is the same and less than
//!   QOD_SEARCH_MISS_TTL_SEC (default 60, at most 60, 0: off) has passed takes it as missing again with no
//!   FileSystem call (review M3: a good bundle moves the version, or a build on this node drops the key). A build
//!   on this node that publishes the bundle drops its key (ForgetMissingBundle), and a probe that began before that
//!   stores nothing (review M4).
std::shared_ptr<const OpenSegment> LoadSegment(duckdb::ClientContext &context, const std::string &bundle_path,
                                               const IndexRecord &record, DataFileRef &file, QueryMemory &memory);

//! What the search caches together (the open segments' held_bytes and the liveness entries' bytes) may hold on a
//! database whose buffer manager is `buffers`: QOD_SEARCH_SEGMENT_CACHE_BYTES or a quarter of memory_limit,
//! whichever is less (lead ruling 2026-11-17, provisional, review I1). Each cache admits an entry only within it,
//! beside what the other holds.
uint64_t SearchCachesBound(duckdb::BufferManager &buffers);
//! Reads QOD_SEARCH_MAX_BUNDLE_BYTES, QOD_SEARCH_SEGMENT_CACHE_BYTES and QOD_SEARCH_MISS_TTL_SEC (search_knobs.hpp);
//! RegisterSearchFunctions calls it once, at the node's start.
void ReadSegmentSettingsFromEnvironment();
//! The open-segment cache's held_bytes on `db`.
uint64_t SegmentCacheBytes(duckdb::DatabaseInstance &db);

//! Drops what the missing-bundle cache remembers of (index id, path key, file size, footer size), on this
//! context's database: called once a build on this node published that bundle.
void ForgetMissingBundle(duckdb::ClientContext &context, const std::string &index_id, const std::string &path_key,
                         uint64_t file_size, uint64_t footer_size);

//! Writes `bytes` to `path` atomically (6.2): to `<path>.tmp-<random>` through the FileSystem, then
//! FileSystem::MoveFile onto `path`, creating the folders on the way.
void WriteBundle(duckdb::ClientContext &context, const std::string &path, const uint8_t *bytes, size_t len);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! Test hooks. The counts are the process's, every instance's caches summed; `entries` to `reserved_bytes` are
//! what the caches hold now.
struct SegmentCacheCounters {
	uint64_t reads = 0;   // bundle files opened through the FileSystem (hits do not count)
	uint64_t hits = 0;    // served from the cache
	uint64_t missing = 0; // no bundle at the path
	uint64_t refused = 0; // a bundle qs_segment_open refused, or one that could not be read whole
	uint64_t oversized = 0; // a bundle larger than the cap (kMaxBundleBytes, or the test's), never read
	uint64_t bytes_read = 0; // bundle bytes read through the FileSystem
	uint64_t missing_hits = 0; // lookups the missing-bundle cache answered as missing, with no FileSystem call
	uint64_t refused_hits = 0; // lookups it answered as refused, with no FileSystem call
	uint64_t temporary = 0;    // bundles a search kept as temporary memory (the cache did not take them)
	uint64_t evictions = 0;    // entries evicted (past the bound, or for room in the buffer manager)
	uint64_t entries = 0;
	uint64_t held_bytes = 0;     // the entries' held_bytes summed
	uint64_t bundle_bytes = 0;   // their bundles' bytes
	uint64_t rowid_bytes = 0;    // their (rowid, doc) list blocks' bytes
	uint64_t reserved_bytes = 0; // their reservations (heap_bytes)
	uint64_t misses_held = 0;    // keys the missing-bundle cache holds
	uint64_t byte_bound = 0;
};
SegmentCacheCounters SegmentCacheCountersForTest();
//! Empties every instance's open-segment and missing-bundle caches and forgets the files logged.
void ClearSegmentCacheForTest();
//! The cap LoadSegment applies (kMaxBundleBytes unless a test lowered it; 0 puts it back).
void SetMaxBundleBytesForTest(uint64_t bytes);
//! The open-segment cache's byte bound (evicting unheld entries past it); kDefault puts the default back.
void SetSegmentCacheBytesForTest(uint64_t bytes);
//! The missing-bundle cache's lifetime in seconds (0 to 60); kDefault puts the default back.
void SetMissTtlForTest(uint64_t seconds);
//! The cap, the joint cache bound and the missing-bundle lifetime as last set (registration, or a test hook).
struct SegmentSettings {
	uint64_t max_bundle_bytes;
	uint64_t cache_bytes;
	uint64_t miss_ttl_sec;
};
SegmentSettings SegmentSettingsForTest();
constexpr uint64_t kDefault = UINT64_MAX;
//! Milliseconds added to the missing-bundle cache's clock (a test moving past the lifetime).
void AdvanceSearchClockForTest(int64_t ms);
//! Called right before LoadSegment stores a miss (review M4's race: a build forgetting the key meanwhile).
void MissProbeObserverForTest(std::function<void()> observer);
//! When on, the buffer manager is taken to refuse the cache's reservation of an entry's heap (as memory_limit
//! would), so the entry becomes the search's temporary copy.
void RefuseCacheReservationForTest(bool on);
//! The held_bytes of every temporary copy LoadSegment made since the last reset (C2), every thread's.
uint64_t TemporaryBundleBytesForTest();
void ResetTemporaryBundleBytesForTest();
//! Takes and releases, one after the other, the registry's lock, the miss probe's and `db`'s segment cache's (when
//! it has one) (TakeSearchLocksForTest); returns how many it took.
uint64_t TakeSegmentLocksForTest(duckdb::DatabaseInstance &db);

#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
