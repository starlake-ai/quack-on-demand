#pragma once

// QoD Search (spec 9, ruling 10): the per-query memory budget QOD_SEARCH_QUERY_MEMORY and the bitset words the
// sink takes from DuckDB's buffer manager. Built only with QOD_NODE_SEARCH=ON.

#include "duckdb.hpp"
#include "duckdb/storage/buffer/buffer_handle.hpp"
#include "duckdb/storage/buffer_manager.hpp"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>

namespace qod {
namespace search {

//! The per-query budget QOD_SEARCH_QUERY_MEMORY: every allocation of a search in proportion to its data (the
//! live and allowed bitsets of every local state, the rowid map, the fallback's builders jointly and its
//! segments, the hits, the library's own share through Remaining()) is charged before it is made; past the
//! budget: InvalidInputException("QoD: search_memory_exceeded: ..."). Thread-safe: the sink's local states
//! charge concurrently. The budget is QOD_SEARCH_QUERY_MEMORY as registration read it (search_knobs.hpp), or, when
//! that is 0 (the default), a quarter of memory_limit as it is at the query: charged before each allocation, it
//! fires before DuckDB's memory_limit refuses a block, and it alone bounds the library's heap, which DuckDB does
//! not see; a fixed value above memory_limit leaves DuckDB's limit to fire first.
//!
//! `hide_figures` is the call's allowed population (spec 7.5): its refusals name no byte count, since the counts
//! follow rows and fields the population hides (Task 4 security review m2); a table population's name the bytes.
class QueryMemory {
public:
	QueryMemory(uint64_t budget, bool hide_figures);
	QueryMemory(const QueryMemory &) = delete;
	QueryMemory &operator=(const QueryMemory &) = delete;

	//! Charges `bytes` for `what` (a plural noun phrase, "allowed bitsets"); throws, charging nothing, when the
	//! search would then hold more than its budget. `fix`, when given, ends the refusal's text in both populations:
	//! how to make the charge fit (no figure).
	void Charge(uint64_t bytes, const char *what, const char *fix = nullptr);
	//! Gives back bytes charged before (never more than held).
	void Release(uint64_t bytes);
	uint64_t Held() const;
	//! What the budget leaves: passed to the library as its memory_budget.
	uint64_t Remaining() const;
	uint64_t Budget() const;
	bool HidesFigures() const;
	//! search_memory_exceeded: with `detail` (byte counts) for the table population; with no figure in the
	//! allowed population, or when `detail` is empty; then "; `fix`" in both when `fix` is given.
	[[noreturn]] void Exceeded(const std::string &detail, const char *fix = nullptr) const;

private:
	uint64_t budget_;
	bool hide_figures_;
	std::atomic<uint64_t> held_ {0};
};

//! `words` zeroed 64-bit words taken from DuckDB's buffer manager (AllocateTemporaryMemory, pinned for its life),
//! so they count against memory_limit as well (spec 7.6, P7a). A request past memory_limit fails with DuckDB's
//! out-of-memory error, whose text names the block's size; when `memory` hides figures (the allowed population),
//! it fails through QueryMemory::Exceeded instead, with no figure (Task 11 fix round 1). Movable; an empty one
//! holds nothing.
class BufferWords {
public:
	BufferWords() = default;
	BufferWords(duckdb::BufferManager &buffers, size_t words, const QueryMemory &memory);
	BufferWords(BufferWords &&other) noexcept;
	BufferWords &operator=(BufferWords &&other) noexcept;
	BufferWords(const BufferWords &) = delete;
	BufferWords &operator=(const BufferWords &) = delete;

	uint64_t *Data() {
		return words_;
	}
	const uint64_t *Data() const {
		return words_;
	}
	size_t Size() const {
		return size_;
	}
	bool Empty() const {
		return size_ == 0;
	}
	//! Frees the words (the block goes back to the buffer manager).
	void Reset();

private:
	duckdb::shared_ptr<duckdb::BlockHandle> block_;
	duckdb::BufferHandle pin_;
	uint64_t *words_ = nullptr;
	size_t size_ = 0;
};

//! Evicts the search caches' entries no running search holds (the open segments first, then the liveness views)
//! from `db`'s caches until at least `bytes` of their buffer-manager memory went back, or none is left; returns
//! what went. BufferWords and the segment cache ask it when memory_limit refuses a block, so a full cache never
//! stands in a search's own way (Task 12). Defined in search_segments.cpp.
uint64_t ReleaseSearchCaches(duckdb::DatabaseInstance &db, uint64_t bytes);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! When on, every BufferWords request fails as DuckDB's memory_limit refuses one (an OutOfMemoryException naming
//! the block's size), so a test reaches that path without starving the database.
void FailBufferWordsForTest(bool on);
#endif // QOD_NODE_TEST_HOOKS

} // namespace search
} // namespace qod
