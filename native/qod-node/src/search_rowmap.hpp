#pragma once

// QoD Search (spec 7.1 step 3, 7.5, 9, ruling 10): rowid to its one live (segment, doc). Built only with
// QOD_NODE_SEARCH=ON.

#include "search_segments.hpp"

#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

namespace qod {
namespace search {

//! Spec 9, ruling 10: rowid to (segment, doc) for one call's segments (bundles and fallback), built from each
//! segment's rowid column and live bitset. The prototype's SearchProgram::Locate moved into the sink's drain,
//! with its rule kept (review I4): a rowid's candidates are the docs holding it (Q1: ranges overlap and repeat),
//! and exactly one must be live at S. None: "QoD: search_snapshot_unavailable: rowid <r> of the input is live in
//! no segment". Several: "QoD: search_rowid_ambiguous: rowid <r> is live in more than one doc". Both refuse the
//! statement, never skip. Ruling 10's "dropped" is the sizing rule: nothing is ever sized or allocated from a
//! rowid's value, so a rowid outside every segment's range costs no memory before it is refused.
//!
//! The map holds one interval per segment, never a copy of a segment's rowids: an implicit segment's
//! [first_rowid, first_rowid + num_docs), and an explicit one's [its least rowid, its largest + 1), whose docs are
//! found in the segment's own sorted (rowid, doc) list. Its memory is BytesFor, a function of the segment count.
//! Immutable once built: Locate is called concurrently from every local sink state.
class RowidMap {
public:
	//! `live[i]` is segment i's live bitset at S, exactly ceil(num_docs / 64) words. Both outlive the map.
	RowidMap(const std::vector<std::shared_ptr<const OpenSegment>> &segments,
	         const std::vector<const std::vector<uint64_t> *> &live);

	//! The bytes a map of `segments` holds, charged before it is built.
	static uint64_t BytesFor(size_t segments);

	//! The one live (segment, doc) holding `rowid`; throws as above otherwise. Tries the interval that answered
	//! the previous lookup of the same local state first (the scan reads file by file), when no other interval
	//! shares a rowid with it, then the index. `hint` is the caller's, per local state; start it at kNoHint.
	std::pair<uint32_t, uint32_t> Locate(int64_t rowid, uint32_t &hint) const {
		if (hint < fast_.size()) {
			auto &f = fast_[hint];
			// Unsigned: a rowid below lo wraps far past span, so one comparison bounds both ends.
			auto offset = static_cast<uint64_t>(rowid) - static_cast<uint64_t>(f.lo);
			if (offset < f.span && ((f.live[offset >> 6] >> (offset & 63)) & 1)) {
				return {f.segment, static_cast<uint32_t>(offset)};
			}
		}
		return LocateSlow(rowid, hint);
	}

	uint64_t TotalDocs() const {
		return total_docs_;
	}
	size_t Segments() const {
		return segment_count_;
	}

	static constexpr uint32_t kNoHint = UINT32_MAX;

private:
	//! [lo, hi): an implicit segment's rowids are lo + doc; an explicit one's are in `pairs`, sorted.
	struct Range {
		int64_t lo, hi;
		uint32_t segment;
		//! No other interval shares a rowid with this one.
		bool alone;
		//! The explicit segment's sorted (rowid, doc) list (OpenSegment::RowidDocs), `n_pairs` long; null for implicit.
		const std::pair<int64_t, uint32_t> *pairs;
		size_t n_pairs;
	};
	//! The hint's view of interval i: span is hi - lo for an implicit interval no other interval overlaps, else 0
	//! (the hint then never answers alone).
	struct Fast {
		int64_t lo;
		uint64_t span;
		const uint64_t *live;
		uint32_t segment;
	};

	std::pair<uint32_t, uint32_t> LocateSlow(int64_t rowid, uint32_t &hint) const;

	std::vector<Range> ranges_;    // sorted by lo; overlapping allowed
	std::vector<int64_t> max_hi_;  // max_hi_[i] = the largest hi of ranges_[0..i]
	std::vector<Fast> fast_;       // per range, same order
	std::vector<const uint64_t *> live_; // per segment
	uint64_t total_docs_ = 0;
	size_t segment_count_ = 0;
};

} // namespace search
} // namespace qod
