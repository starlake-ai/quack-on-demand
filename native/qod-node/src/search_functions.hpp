#pragma once

// QoD Search (spec 4.1, 4.2, 6.1, 7.1 to 7.7, 9, 13): the node's search table functions. Built only with
// QOD_NODE_SEARCH=ON.

#include "search_records.hpp"
#include "search_snapshot.hpp"

#include "duckdb.hpp"

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace qod {

//! Registers on `db`:
//!  - qod_search(input TABLE, table VARCHAR, columns VARCHAR[], query VARCHAR, k INTEGER := NULL,
//!    query_json VARCHAR := NULL) -> (rowid BIGINT, score FLOAT, rank INTEGER): the exact top k (1 to
//!    QOD_SEARCH_MAX_K; a NULL or absent k is min(10, QOD_SEARCH_MAX_K), LEAD RULING Task 16), executed
//!    only through the search sink (search_sink.hpp): the input drained in parallel into per-segment allowed
//!    bitsets, one qs_search in Finish. `columns` is a non-empty constant subset of the index's columns,
//!    judged on the statement's text (rule 7); a non-NULL `query_json` fails search_query_unsupported.
//!  - qod_search_matches(input TABLE, table VARCHAR, columns VARCHAR[], query VARCHAR, query_json VARCHAR :=
//!    NULL, with_score BOOLEAN := false) -> (rowid BIGINT, score FLOAT): every match, through the same sink
//!    and hook: Finish opens qs_matches_open, the sink's source pulls qs_matches_next, STANDARD_VECTOR_SIZE
//!    rows per chunk; `score` NULL unless `with_score`.
//!  - in both, a live data file with no usable bundle and the inlined rows live at S go to the query's
//!    in-memory fallback segments (search_fallback.hpp), up to QOD_SEARCH_MAX_UNINDEXED_ROWS;
//!  - qod_search_build_segment(input TABLE, table VARCHAR, path VARCHAR, file_size UBIGINT, footer_size
//!    UBIGINT) -> (path VARCHAR, docs BIGINT, bytes BIGINT): one data file's bundle from (pos, row_id,
//!    snapshot_id, <one VARCHAR per index field, in index order>) rows in position order, under the index
//!    record of `table` (IndexRecordFor: bundle root, fields, table id, MAC key, never an argument), published
//!    at SegmentPath(bundle_root, index_id, path_key) atomically on a local path and by the direct put on an
//!    object store; the system principal only, on a quack session (spec 13), as qod_authz_stats; refused on
//!    an encrypted DuckLake (search_encrypted_unsupported) before any row is read;
//!  - a post_bind hook, registered after qod-node's own (so it runs once the verdict admitted the
//!    statement): per call, the lineage check (FindSearchCalls: rules 1, 2, 7, the branch refusal of rule 9),
//!    the AT (TIMESTAMP) refusal (rule 4; a transaction that wrote to the target's lake is searched with its
//!    own writes, Task 14), the target's DuckLake table id
//!    (DuckLakeTableIdNow), its index record (IndexRecordFor: none or disabled is search_index_unavailable),
//!    each call column's field id (by its current name; it must be a field of the record and held by the
//!    table at S, else search_columns_unsupported), the population (the verdict's on a quack session for a
//!    tenant principal, refused search_population_missing without one; the table's for a superuser, the
//!    system principal and a connection off a quack session) and the view (CachedSnapshotView), stored in the
//!    call's SearchTarget.
void RegisterSearchFunctions(duckdb::DatabaseInstance &db);

//! RegisterSearchFunctions, then `records` installed as the process-wide index record source for quack
//! sessions (search::InstallRecordFetch): what a serving node registers. Every principal's records come
//! from it, the system principal's included (OWNER RULING 2026-11-16, D4).
void RegisterSearchFunctions(duckdb::DatabaseInstance &db, std::shared_ptr<search::RecordClient> records);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! Test hooks (tests only).
//! The query memory limit as configured (QOD_SEARCH_QUERY_MEMORY, read once at registration; 0 by default, a quarter
//! of memory_limit per query).
void SetSearchMemoryForTest(uint64_t bytes);
//! Every search knob's value as registration read it (search_knobs.hpp), by variable.
std::map<std::string, uint64_t> SearchKnobsForTest();
//! When true, Finish sets the search's cancel flag before calling qs_search (or qs_matches_open).
void CancelInFinishForTest(bool cancel);
//! What the last qs_search or qs_matches_open call of the process received, per segment in call order.
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
	//! qod_search_matches rather than qod_search.
	bool matches = false;
	//! The call's fields, (column, field id), as passed to the library.
	std::vector<std::pair<std::string, int64_t>> fields;
	//! The bitset bytes held when the budget was computed: memory_budget = the limit - held_bytes.
	uint64_t held_bytes = 0;
	//! The library itself answered search_cancelled.
	bool library_cancelled = false;
	//! The snapshot view the call searched at (Task 10) and whether it came from the liveness cache. On a hit
	//! its `reads` and `target_scan_ms` describe the resolve that filled the cache entry, not this call.
	std::shared_ptr<const search::SnapshotView> view;
	//! The view the call searched at, by address (the liveness cache's own on a hit): `view` is a copy of a view
	//! the cache stores (Task 12), whose words are in a block of its instance's buffer manager, never to outlive it.
	const search::SnapshotView *view_source = nullptr;
	bool view_hit = false;
	//! The fallback (Task 11): its in-memory segments, their docs and bytes, and the time the factory spent
	//! building them (counting the unindexed rows, scanning and indexing).
	size_t fallback_segments = 0;
	uint64_t fallback_docs = 0;
	uint64_t fallback_bytes = 0;
	double fallback_ms = 0;
	//! Task 11: per local sink state, in Merge order, the allowed bitset bytes it charged and the segments (indexes
	//! into num_docs) it allocated a bitset for; from the first Consume of any local state to Finish's start.
	std::vector<uint64_t> local_bitset_bytes;
	std::vector<std::vector<uint32_t>> local_segments;
	double drain_ms = 0;
	//! qod_search only: Finish's own time, from its start to the library's answer (B6).
	double finish_ms = 0;
	//! The query's memory budget, and what it held at its peak before the library call.
	uint64_t memory_limit = 0;
	uint64_t memory_peak = 0;
};
SearchCallRecord LastSearchCallForTest();
void ResetLastSearchCallForTest();
//! Called on Finish's thread, after the cancel watcher started and right before qs_search.
void SearchCallObserverForTest(std::function<void(duckdb::ClientContext &)> observer);
//! Called with true right before and false right after the hook's CachedSnapshotView (ResolveSnapshotView
//! on a miss).
void ResolveObserverForTest(std::function<void(bool)> observer);
//! Called with the statement's context and true when the search post_bind hook starts on a plan, false when it
//! returns or throws: on every statement it runs on, the ones DuckLake binds on its own context included.
void SearchHookObserverForTest(std::function<void(duckdb::ClientContext &, bool)> observer);
//! Takes, one after the other, every lock the search code holds anywhere (the test hooks' own, `db`'s liveness and
//! segment caches and their registries, the index record source, the installed record client, `db`'s data path
//! cache, the fallback's hooks) and releases it; returns how many it took. Task 13's G3: called from a post_bind
//! on the statements DuckLake binds while the search hook runs, it blocks if the hook held one of them.
uint64_t TakeSearchLocksForTest(duckdb::DatabaseInstance &db);
//! How many times the hook asked IndexRecordFor, since the process started.
uint64_t RecordLookupsForTest();
//! qod_search_matches: once `chunks` chunks went out, the next qs_matches_next fails (asked for 0 hits, the
//! library's search_invalid_argument) or is cancelled (the cancel byte set: search_cancelled). -1: off.
void FailMatchesAfterChunksForTest(int64_t chunks);
//! When n > 0, Finish calls the client context's Interrupt() once it has walked n segments, and its check before
//! the next segment answers it (Task 11, the check between segments). 0: off.
void InterruptBetweenSegmentsForTest(uint64_t n);
//! When n > 0, the factory calls the client context's Interrupt() once it has walked n of the view's data files
//! (loading their bundles), and its check before the next file answers it. 0: off.
void InterruptBetweenSegmentLoadsForTest(uint64_t n);
void CancelMatchesAfterChunksForTest(int64_t chunks);

#endif // QOD_NODE_TEST_HOOKS

} // namespace qod
