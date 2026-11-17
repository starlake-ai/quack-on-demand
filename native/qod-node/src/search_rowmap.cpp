#include "search_rowmap.hpp"

#include "duckdb/common/exception.hpp"

#include <algorithm>
#include <limits>
#include <string>

namespace qod {
namespace search {

using namespace duckdb;

namespace {

bool Bit(const uint64_t *words, uint32_t doc) {
	return (words[doc >> 6] >> (doc & 63)) & 1;
}

[[noreturn]] void PastLargest() {
	throw InvalidInputException("QoD: search_snapshot_unavailable: a segment's rowids pass the largest rowid");
}

} // namespace

RowidMap::RowidMap(const std::vector<std::shared_ptr<const OpenSegment>> &segments,
                   const std::vector<const std::vector<uint64_t> *> &live) {
	if (segments.size() != live.size() || segments.size() >= kNoHint) {
		throw InvalidInputException("QoD: search_internal: the rowid map's segments and live bitsets differ");
	}
	segment_count_ = segments.size();
	live_.reserve(segments.size());
	ranges_.reserve(segments.size());
	for (size_t s = 0; s < segments.size(); s++) {
		auto &segment = *segments[s];
		auto &bits = *live[s];
		if (bits.size() != (static_cast<size_t>(segment.num_docs) + 63) / 64) {
			throw InvalidInputException("QoD: search_internal: a live bitset is not sized by its segment's docs");
		}
		live_.push_back(bits.data());
		total_docs_ += segment.num_docs;
		if (segment.num_docs == 0) {
			continue;
		}
		Range range {0, 0, static_cast<uint32_t>(s), false, nullptr, 0};
		if (segment.explicit_rowids) {
			auto pairs = segment.RowidDocs();
			auto n = segment.RowidDocCount();
			if (n != segment.num_docs) {
				throw InvalidInputException("QoD: search_internal: an explicit segment's rowid list is not one per doc");
			}
			if (pairs[n - 1].first == std::numeric_limits<int64_t>::max()) {
				PastLargest();
			}
			range.lo = pairs[0].first;
			range.hi = pairs[n - 1].first + 1;
			range.pairs = pairs;
			range.n_pairs = n;
		} else {
			if (segment.first_rowid > std::numeric_limits<int64_t>::max() - static_cast<int64_t>(segment.num_docs)) {
				PastLargest();
			}
			range.lo = segment.first_rowid;
			range.hi = segment.first_rowid + static_cast<int64_t>(segment.num_docs);
		}
		ranges_.push_back(range);
	}
	std::sort(ranges_.begin(), ranges_.end(), [](const Range &a, const Range &b) {
		return a.lo < b.lo || (a.lo == b.lo && a.segment < b.segment);
	});
	max_hi_.resize(ranges_.size());
	for (size_t i = 0; i < ranges_.size(); i++) {
		max_hi_[i] = i == 0 ? ranges_[i].hi : std::max(max_hi_[i - 1], ranges_[i].hi);
	}
	fast_.reserve(ranges_.size());
	for (size_t i = 0; i < ranges_.size(); i++) {
		auto &range = ranges_[i];
		range.alone = (i == 0 || max_hi_[i - 1] <= range.lo) && (i + 1 == ranges_.size() || ranges_[i + 1].lo >= range.hi);
		auto implicit_alone = range.alone && !range.pairs;
		fast_.push_back(Fast {range.lo, implicit_alone ? static_cast<uint64_t>(range.hi - range.lo) : 0,
		                      live_[range.segment], range.segment});
	}
}

uint64_t RowidMap::BytesFor(size_t segments) {
	return static_cast<uint64_t>(segments) *
	       (sizeof(Range) + sizeof(int64_t) + sizeof(Fast) + sizeof(const uint64_t *));
}

std::pair<uint32_t, uint32_t> RowidMap::LocateSlow(int64_t rowid, uint32_t &hint) const {
	int live = 0;
	uint32_t segment = 0, doc = 0, answered = kNoHint;
	auto visit = [&](size_t i) {
		auto &range = ranges_[i];
		if (rowid < range.lo || rowid >= range.hi) {
			return;
		}
		auto consider = [&](uint32_t d) {
			if (Bit(live_[range.segment], d) && ++live == 1) {
				segment = range.segment;
				doc = d;
				answered = static_cast<uint32_t>(i);
			}
		};
		if (!range.pairs) {
			consider(static_cast<uint32_t>(rowid - range.lo));
			return;
		}
		auto begin = range.pairs;
		auto end = range.pairs + range.n_pairs;
		auto first = std::lower_bound(begin, end, std::make_pair(rowid, uint32_t(0)));
		for (auto it = first; it != end && it->first == rowid; ++it) {
			consider(it->second);
		}
	};
	if (hint < ranges_.size() && ranges_[hint].alone && rowid >= ranges_[hint].lo && rowid < ranges_[hint].hi) {
		// No other interval holds this rowid: its candidates are this interval's docs alone.
		visit(hint);
	} else {
		auto end = std::upper_bound(ranges_.begin(), ranges_.end(), rowid,
		                            [](int64_t value, const Range &range) { return value < range.lo; });
		for (auto i = static_cast<int64_t>(end - ranges_.begin()) - 1; i >= 0 && max_hi_[i] > rowid; i--) {
			visit(static_cast<size_t>(i));
		}
	}
	if (live > 1) {
		throw InvalidInputException("QoD: search_rowid_ambiguous: rowid " + std::to_string(rowid) +
		                            " is live in more than one doc");
	}
	if (live == 0) {
		// Every doc live at S is in a bundle or a fallback segment, and the input traces to the target scan at S
		// (rule 1), so this is a disagreement between two reads at S: refused, never skipped.
		throw InvalidInputException("QoD: search_snapshot_unavailable: rowid " + std::to_string(rowid) +
		                            " of the input is live in no segment");
	}
	hint = answered;
	return {segment, doc};
}

} // namespace search
} // namespace qod
