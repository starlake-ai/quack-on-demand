#pragma once

// QoD Search (Phase 0 prototype, docs/superpowers/plans/2026-11-14-search-phase0-prototype.md): the
// node's search table functions. Built only with QOD_NODE_SEARCH=ON.

#include "search_snapshot.hpp"

#include "duckdb.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace qod {

//! Registers on `db` the prototype's search functions. Part 2 Task 5: the drain probes
//!  - qod_search_drain(input TABLE) -> (rows BIGINT, max_rowid BIGINT, drain_ms DOUBLE): every rowid of
//!    the input's one BIGINT column set in one bitset, on one thread (MaxThreads() = 1); one row;
//!  - qod_search_drain_parallel(input TABLE): the same drain per local state, no thread bound, one row
//!    per local state (nothing summed across them): a timing reference only, never a search.
//!  - qod_search_drain_sink(input TABLE) -> (rows BIGINT, max_rowid BIGINT, drain_ms DOUBLE, locals BIGINT):
//!    the same drain executed by the search sink (search_sink.hpp, Task 7a): in parallel local states,
//!    merged once each, one row. Also registers the sink's optimizer and operator extensions.
//! Every drain probe refuses a rowid at or above search::kMaxRowidExclusive (2^27) and takes its bitsets
//! from DuckDB's buffer manager, so they count against memory_limit.
//! Part 2 Task 8 adds the prototype's search (spec 4.1, 7.1):
//!  - qod_search(input TABLE, table VARCHAR, columns VARCHAR[], query VARCHAR, k INTEGER := 10,
//!    query_json VARCHAR := NULL) -> (rowid BIGINT, score FLOAT, rank INTEGER), executed only through the
//!    search sink: the input drained in parallel into per-segment allowed bitsets, one qs_search in Finish;
//!    a live data file with no usable bundle and the inlined rows live at S go to the query's in-memory
//!    fallback segments (Task 11, search_fallback.hpp), up to QOD_SEARCH_MAX_UNINDEXED_ROWS;
//!  - qod_search_build_segment(input TABLE, table VARCHAR, column VARCHAR, bundle_root VARCHAR, path VARCHAR,
//!    file_size UBIGINT, footer_size UBIGINT) -> (path VARCHAR, docs BIGINT, bytes BIGINT): one data file's
//!    bundle from (pos, row_id, snapshot_id, text) rows in position order, published atomically under
//!    SegmentPath; the system principal only, on a quack session (spec 13), as qod_authz_stats;
//!  - a post_bind hook, registered after qod-node's own (so it runs once the verdict admitted the
//!    statement): the lineage check (FindSearchCalls), then ResolveSnapshotView on each call's target
//!    scan, the index record and the population (on a quack session from the statement's verdict,
//!    StatementSearchEntries; refused search_population_missing without one), stored in the call's
//!    SearchTarget.
//! Phase 1 removes the probes.
void RegisterSearchFunctions(duckdb::DatabaseInstance &db);

//! The bundle root of every index on a connection off a quack session (the node's own connections,
//! in-process tests), which has no verdict: the prototype's stand-in, with the table population. On a
//! quack session the verdict's search entries alone give each call's population and index record (search
//! spec 4.2 rule 8, Task 9), and this is never read. Empty (the default): such a call is refused at bind,
//! search_index_unavailable.
void SetPrototypeBundleRoot(const std::string &root);

//! Test hooks (tests only).
//! The query memory limit (QOD_SEARCH_QUERY_MEMORY, read once at registration; 4 GiB by default).
void SetSearchMemoryForTest(uint64_t bytes);
//! When true, Finish sets the search's cancel flag before calling qs_search.
void CancelInFinishForTest(bool cancel);
//! What the last qs_search call of the process received, per segment in call order.
struct SearchCallRecord {
	std::vector<size_t> live_words;
	std::vector<size_t> allowed_words;
	std::vector<uint32_t> num_docs;
	//! The length of each segment's FileLiveness::words before the resize.
	std::vector<size_t> liveness_words;
	size_t out_cap = 0;
	uint32_t k = 0;
	uint64_t memory_budget = 0;
	int population = -1;
	bool called = false;
	//! The bitset bytes held when the budget was computed: memory_budget = the limit - held_bytes.
	uint64_t held_bytes = 0;
	//! qs_search itself answered search_cancelled.
	bool library_cancelled = false;
	//! The snapshot view the call searched at (Task 10) and whether it came from the liveness cache. On a hit
	//! its `reads` and `target_scan_ms` describe the resolve that filled the cache entry, not this call.
	std::shared_ptr<const search::SnapshotView> view;
	bool view_hit = false;
	//! The fallback (Task 11): its in-memory segments, their docs and bytes, and the time the factory spent
	//! building them (counting the unindexed rows, scanning and indexing).
	size_t fallback_segments = 0;
	uint64_t fallback_docs = 0;
	uint64_t fallback_bytes = 0;
	double fallback_ms = 0;
};
SearchCallRecord LastSearchCallForTest();
void ResetLastSearchCallForTest();
//! Called on Finish's thread, after the cancel watcher started and right before qs_search.
void SearchCallObserverForTest(std::function<void(duckdb::ClientContext &)> observer);
//! Called with true right before and false right after the hook's CachedSnapshotView (ResolveSnapshotView
//! on a miss).
void ResolveObserverForTest(std::function<void(bool)> observer);

//! Test hook (tests and the drain bench only): the drain bitsets in a std::vector instead of DuckDB's
//! buffer manager (Task 7a Step 1's timing comparison). Takes effect for local states made after it.
void UseVectorBitsetsForTest(bool use);

} // namespace qod
