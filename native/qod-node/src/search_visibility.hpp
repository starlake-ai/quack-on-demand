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
#include <vector>

namespace qod::search {

//! The view of `target` at S: ResolveSnapshotView's, or a cached one under the 3c rules (auto-commit,
//! the transaction has not modified the lake (ResolveSnapshotView refuses that anyway), no AT clause on
//! `target`, and a catalog not attached read-only, since a DuckLake attached with SNAPSHOT_VERSION or
//! SNAPSHOT_TIME is pinned to an old S with no AT clause, review I1). `hit` says which.
//! Key: (the instance, Catalog::GetOid() of the target entry's ParentCatalog(), SnapshotView::table_oid),
//! holding one entry per table at its newest S seen: a newer S replaces the entry, an older one never does,
//! and a lookup hits only at the entry's own S. The instance is a process-unique id kept in the instance's
//! ObjectCache (attach oids restart per instance, review M1). The catalog oid is the catalog
//! Catalog::GetCatalog(context, view.catalog_alias) returns; a DETACH and ATTACH under the same alias gets a
//! new oid (DatabaseManager::NextOid), so an old entry is never served again.
//! Bounds, read once: QOD_SEARCH_LIVENESS_CACHE_ENTRIES (prototype: 64) entries and
//! QOD_SEARCH_LIVENESS_CACHE_BYTES (default 256 MiB) bytes, an entry counted as LivenessViewBytes; LRU
//! eviction past either; an entry larger than the byte bound is never stored; either bound at 0 turns the
//! cache off. A view the cache handles (stored or not) carries no encryption key (review M7). A hit returns
//! the view as resolved, so its `reads` and `target_scan_ms` describe the resolve that filled the entry,
//! not this call (review M4).
std::shared_ptr<const SnapshotView> CachedSnapshotView(duckdb::ClientContext &context, duckdb::LogicalGet &target,
                                                       bool &hit);

//! File `i`'s liveness as the segment's doc-id bitset, `num_docs` bits (the bundle's row count, since
//! DataFileRef::record_count is -1). Throws InvalidInputException("search_snapshot_unavailable:
//! liveness past the bundle's rows") when live.end > num_docs: the bundle and the file disagree.
std::vector<uint64_t> LiveDocs(const FileLiveness &live, uint32_t num_docs);

//! An entry's size for the byte bound: 8 bytes per liveness word of every file, as allocated
//! (words.capacity(); ResolveSnapshotView shrinks each bitset to fit, review M9), plus the bytes of every
//! data file's path and bundle key and every delete file's path and data file path.
uint64_t LivenessViewBytes(const SnapshotView &view);

//! Test hooks.
struct LivenessCacheCounters {
	uint64_t hits = 0;      // served from the cache
	uint64_t misses = 0;    // eligible and resolved (stored unless too large or older than the entry)
	uint64_t bypassed = 0;  // not eligible (an AT clause, an explicit transaction, a modified lake, a
	                        // read-only attach, a bound at 0)
	uint64_t replaced = 0;  // a newer S replaced a table's entry
	uint64_t evictions = 0; // entries evicted past a bound
	uint64_t too_large = 0; // resolved views larger than the byte bound, never stored
	uint64_t entries = 0;   // held now
	uint64_t bytes = 0;     // held now, LivenessViewBytes summed
	uint64_t capacity = 0;
	uint64_t byte_bound = 0;
};
LivenessCacheCounters LivenessCacheCountersForTest();
void ClearLivenessCacheForTest();
//! Sets both bounds (evicting past them); tests restore the defaults, 64 and 256 MiB, after.
void SetLivenessCacheBoundsForTest(uint64_t entries, uint64_t bytes);

} // namespace qod::search
