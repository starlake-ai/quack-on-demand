#include "search_memory.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/storage/buffer/block_handle.hpp"

#include <algorithm>
#include <cstring>

namespace qod {
namespace search {

using namespace duckdb;

namespace {

//! The largest budget taken as given: below it a charge's sum cannot wrap even with every local state charging
//! at once.
constexpr uint64_t kMaxBudget = uint64_t(1) << 62;

#ifdef QOD_NODE_TEST_HOOKS
std::atomic<bool> g_fail_buffer_words {false};
#endif

} // namespace

QueryMemory::QueryMemory(uint64_t budget, bool hide_figures)
    : budget_(std::min(budget, kMaxBudget)), hide_figures_(hide_figures) {
}

void QueryMemory::Charge(uint64_t bytes, const char *what, const char *fix) {
	if (bytes == 0) {
		return;
	}
	auto before = held_.load(std::memory_order_relaxed);
	// A compare-and-swap, so a refused charge never shows in Held(), even for a moment, to another local state.
	while (true) {
		if (bytes > budget_ || before > budget_ - bytes) {
			Exceeded(std::string("the search's ") + what + " need " + std::to_string(bytes) +
			         " bytes more, past its budget of " + std::to_string(budget_) + " bytes (" +
			         std::to_string(before) + " held)",
			         fix);
		}
		if (held_.compare_exchange_weak(before, before + bytes, std::memory_order_relaxed)) {
			return;
		}
	}
}

void QueryMemory::Release(uint64_t bytes) {
	auto before = held_.load(std::memory_order_relaxed);
	while (!held_.compare_exchange_weak(before, before - std::min(before, bytes), std::memory_order_relaxed)) {
	}
}

uint64_t QueryMemory::Held() const {
	return held_.load(std::memory_order_relaxed);
}

uint64_t QueryMemory::Remaining() const {
	auto held = Held();
	return held >= budget_ ? 0 : budget_ - held;
}

uint64_t QueryMemory::Budget() const {
	return budget_;
}

bool QueryMemory::HidesFigures() const {
	return hide_figures_;
}

void QueryMemory::Exceeded(const std::string &detail, const char *fix) const {
	auto tail = fix ? std::string("; ") + fix : std::string();
	if (hide_figures_ || detail.empty()) {
		throw InvalidInputException("QoD: search_memory_exceeded: the search needs more memory than its budget" + tail);
	}
	auto text = detail;
	while (!text.empty() && text[0] == ' ') {
		text.erase(0, 1);
	}
	throw InvalidInputException("QoD: search_memory_exceeded: " + text + tail);
}

BufferWords::BufferWords(BufferManager &buffers, size_t words, const QueryMemory &memory) {
	if (words == 0) {
		return;
	}
	auto bytes = words * sizeof(uint64_t);
	try {
#ifdef QOD_NODE_TEST_HOOKS
		if (g_fail_buffer_words.load()) {
			throw OutOfMemoryException("could not allocate block of size " + std::to_string(bytes) + " (test hook)");
		}
#endif
		try {
			block_ = buffers.AllocateTemporaryMemory(MemoryTag::EXTENSION, bytes);
		} catch (OutOfMemoryException &) {
			// The search caches' unheld entries give their memory back first (Task 12), then the block is asked once
			// more.
			if (ReleaseSearchCaches(buffers.GetDatabase(), bytes) == 0) {
				throw;
			}
			block_ = buffers.AllocateTemporaryMemory(MemoryTag::EXTENSION, bytes);
		}
		pin_ = buffers.Pin(block_);
	} catch (OutOfMemoryException &) {
		// DuckDB's text names the block's size, which follows the segment's docs: not for the allowed population.
		pin_ = BufferHandle();
		block_.reset();
		if (memory.HidesFigures()) {
			memory.Exceeded("");
		}
		throw;
	}
	words_ = reinterpret_cast<uint64_t *>(pin_.GetDataMutable());
	std::memset(words_, 0, bytes);
	size_ = words;
}

BufferWords::BufferWords(BufferWords &&other) noexcept
    : block_(std::move(other.block_)), pin_(std::move(other.pin_)), words_(other.words_), size_(other.size_) {
	other.words_ = nullptr;
	other.size_ = 0;
}

BufferWords &BufferWords::operator=(BufferWords &&other) noexcept {
	if (this != &other) {
		Reset();
		block_ = std::move(other.block_);
		pin_ = std::move(other.pin_);
		words_ = other.words_;
		size_ = other.size_;
		other.words_ = nullptr;
		other.size_ = 0;
	}
	return *this;
}

#ifdef QOD_NODE_TEST_HOOKS
void FailBufferWordsForTest(bool on) {
	g_fail_buffer_words = on;
}
#endif // QOD_NODE_TEST_HOOKS

void BufferWords::Reset() {
	pin_.Destroy();
	block_.reset();
	words_ = nullptr;
	size_ = 0;
}

} // namespace search
} // namespace qod
