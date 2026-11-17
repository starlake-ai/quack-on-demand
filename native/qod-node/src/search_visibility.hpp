#pragma once

// QoD Search (Phase 0 part 2, Task 10): visibility at S (spec 6.3, 7.5) and the liveness cache of the
// owner ruling 2026-11-14 (Task 6 review, point 3c). Built only with QOD_NODE_SEARCH=ON.
//
// Liveness comes from the target's own DuckLake scan at S (Task 6, points 3a and 3b): no delete file
// and no data-file footer is read, and a doc is matched by (file index, position), never by rowid.
// LiveDocs turns a file's FileLiveness into the doc-id bitset qs_search takes as `live`.
//
// The cache (3c) is the one cache keyed by S. It holds whole views, liveness included, and serves only
// a read at the current snapshot (no AT clause, no catalog pinned at a snapshot) in an auto-commit
// transaction that has not modified the target's database: such a read can meet S only while no commit has landed after it, so the view at S
// cannot have changed. An AT (VERSION => n) read never uses it, since a flush or a merge changes what
// an old S lists (change 22).

#include "search_snapshot.hpp"

#include "duckdb.hpp"
#include "duckdb/planner/operator/logical_get.hpp"

#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

namespace qod::search {

//! The view of `target` at S: ResolveSnapshotView's, or a cached one under the 3c rules (auto-commit,
//! the transaction has not modified the lake (ResolveSnapshotView refuses that anyway), no AT clause on
//! `target`, and a catalog not attached read-only, since a DuckLake attached with SNAPSHOT_VERSION or
//! SNAPSHOT_TIME is pinned to an old S with no AT clause, review I1). `hit` says which.
//! The cache is the database instance's own (its ObjectCache, Task 12), keyed by (Catalog::GetOid() of the
//! target entry's ParentCatalog(), SnapshotView::table_oid), holding one entry per table at its newest S seen: a
//! newer S replaces the entry, an older one never does, and a lookup hits only at the entry's own S. A DETACH and
//! ATTACH under the same alias gets a new oid (DatabaseManager::NextOid), so an old entry is never served again.
//! A stored view's liveness words live in one block of DuckDB's buffer manager (FileLiveness::block_words, the
//! block in SnapshotView::liveness_block), so memory_limit counts them (ruling 8); a view whose block the buffer
//! manager refuses (once the search caches' unheld entries are evicted) is served and not stored. With the open-segment
//! cache it holds at most SearchCachesBound (a quarter of memory_limit at most, review I1): a view that does not fit
//! beside what that cache holds is served and not stored.
//! Bounds, read once at registration (ReadLivenessSettingsFromEnvironment, search_knobs.hpp):
//! QOD_SEARCH_LIVENESS_CACHE_ENTRIES (default 64, 0 to 100,000) entries and QOD_SEARCH_LIVENESS_CACHE_BYTES
//! (default 256 MiB, 0 to 1 TiB) bytes, an entry counted as LivenessViewBytes; LRU
//! eviction past either; an entry larger than the byte bound is never stored; either bound at 0 turns the
//! cache off. QOD_SEARCH_SEGMENT_CACHE_BYTES bounds both caches together (SearchCachesBound: it or a quarter of
//! memory_limit, whichever is less), so it at 0 turns this cache off too; this cache keeps a slice of that joint
//! bound the segment cache never takes (LivenessSlice). A view the cache handles (stored or not) carries no encryption key (review M7). A hit returns
//! the view as resolved, so its `reads` and `target_scan_ms` describe the resolve that filled the entry,
//! not this call (review M4).
std::shared_ptr<const SnapshotView> CachedSnapshotView(duckdb::ClientContext &context, duckdb::LogicalGet &target,
                                                       bool &hit);

//! File `i`'s liveness as the segment's doc-id bitset, `num_docs` bits (the bundle's row count, since
//! DataFileRef::record_count is -1). Throws InvalidInputException("search_snapshot_unavailable:
//! liveness past the bundle's rows") when live.end > num_docs: the bundle and the file disagree.
std::vector<uint64_t> LiveDocs(const FileLiveness &live, uint32_t num_docs);

//! An entry's size for the byte bound: 8 bytes per liveness word of every file, as allocated (the block's words,
//! or words.capacity(); ResolveSnapshotView shrinks each bitset to fit, review M9), plus the bytes of every data
//! file's path and bundle key and every delete file's path and data file path.
uint64_t LivenessViewBytes(const SnapshotView &view);

//! What `db`'s liveness cache holds (LivenessViewBytes summed): its part of SearchCachesBound (review I1).
uint64_t LivenessCacheBytes(duckdb::DatabaseInstance &db);

//! The part of SearchCachesBound the open-segment cache never takes, so it cannot starve this cache: the lesser of
//! this cache's own byte bound and an eighth of the joint bound (Task 12 fix round 2). The segment cache admits
//! within the joint bound less the greater of this slice and what this cache holds.
uint64_t LivenessSlice(duckdb::BufferManager &buffers);

//! Reads QOD_SEARCH_LIVENESS_CACHE_ENTRIES and QOD_SEARCH_LIVENESS_CACHE_BYTES (search_knobs.hpp);
//! RegisterSearchFunctions calls it once, at the node's start.
void ReadLivenessSettingsFromEnvironment();

//! Evicts `db`'s cached views no running search holds, least recently used first, until their blocks gave back
//! `bytes` or none is left; returns the block bytes freed (ReleaseSearchCaches, Task 12).
uint64_t EvictUnheldLiveness(duckdb::DatabaseInstance &db, uint64_t bytes);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! Test hooks.
struct LivenessCacheCounters {
	uint64_t hits = 0;      // served from the cache
	uint64_t misses = 0;    // eligible and resolved (stored unless too large or older than the entry)
	uint64_t bypassed = 0;  // not eligible (an AT clause, an explicit transaction, a modified lake, a
	                        // read-only attach, a bound at 0)
	uint64_t replaced = 0;  // a newer S replaced a table's entry
	uint64_t evictions = 0; // entries evicted past a bound
	uint64_t too_large = 0; // resolved views larger than the byte bound, never stored
	uint64_t entries = 0;   // held now, every instance's
	uint64_t bytes = 0;     // held now, LivenessViewBytes summed
	uint64_t capacity = 0;
	uint64_t byte_bound = 0;
	uint64_t block_bytes = 0;   // held now in buffer-manager blocks (the entries' liveness words)
	uint64_t block_refused = 0; // views not stored because memory_limit refused their block
	uint64_t over_share = 0;    // views not stored because the search caches' joint share had no room
};
LivenessCacheCounters LivenessCacheCountersForTest();
void ClearLivenessCacheForTest();
//! Sets both bounds (evicting past them); tests restore the defaults, 64 and 256 MiB, after.
void SetLivenessCacheBoundsForTest(uint64_t entries, uint64_t bytes);
//! The two bounds as last set (registration, or the hook above): (entries, bytes).
std::pair<uint64_t, uint64_t> LivenessBoundsForTest();
//! The next liveness block is refused as memory_limit would refuse it (once), so a test reaches the retry after
//! ReleaseSearchCaches (review M5).
void RefuseLivenessBlockOnceForTest();
//! Task 13's G3 control: when on, CachedSnapshotView holds the cache's lock across ResolveSnapshotView (the defect G3
//! must see), so the probe on DuckLake's nested statements finds it held.
void HoldLivenessLockAcrossResolveForTest(bool on);
//! Takes and releases, one after the other, the registry's lock and `db`'s liveness cache's (when it has one)
//! (TakeSearchLocksForTest); returns how many it took.
uint64_t TakeLivenessLocksForTest(duckdb::DatabaseInstance &db);

#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
