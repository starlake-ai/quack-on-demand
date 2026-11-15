#pragma once

// QoD Search (Phase 0 prototype, docs/superpowers/plans/2026-11-14-search-phase0-prototype.md): the
// node's search table functions. Built only with QOD_NODE_SEARCH=ON.

#include "duckdb.hpp"

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
//! Tasks 8 to 11 add qod_search and qod_search_build_segment here. Phase 1 removes the probes.
void RegisterSearchFunctions(duckdb::DatabaseInstance &db);

//! Test hook (tests and the drain bench only): the drain bitsets in a std::vector instead of DuckDB's
//! buffer manager (Task 7a Step 1's timing comparison). Takes effect for local states made after it.
void UseVectorBitsetsForTest(bool use);

} // namespace qod
