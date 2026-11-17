#pragma once

// QoD Search (spec 9, Task 16): the node's search knobs, one table. Each is read from the node's environment
// once, at registration (RegisterSearchFunctions, the node's start): unset or empty, its default; a plain decimal
// number within its range, that number; anything else (a sign, a space, an exponent, a number past 64 bits or
// outside the range), its default and one stderr line naming the variable. The manager sets every one of them on
// every node (quack-on-demand.search, SearchNodeConfig in Config.scala), refusing at its own boot what this
// table refuses; SearchNodeConfigSpec reads the lines below, so the two sides cannot drift apart.

#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace qod {
namespace search {

struct Knob {
	const char *name;
	uint64_t fallback;
	uint64_t least;
	uint64_t greatest;
	//! 0 is allowed too, below `least` (QOD_SEARCH_QUERY_MEMORY: 0 is a quarter of memory_limit).
	bool or_zero;
};

// One line per knob, plain decimal numbers (SearchNodeConfigSpec parses them): variable, default, least,
// greatest, whether 0 is also allowed.
// The rows cap of the per-query fallback (search_fallback.hpp).
constexpr Knob kMaxUnindexedRowsKnob {"QOD_SEARCH_MAX_UNINDEXED_ROWS", 200000, 0, 100000000, false};
// The sources cap of the per-query fallback.
constexpr Knob kMaxUnindexedSourcesKnob {"QOD_SEARCH_MAX_UNINDEXED_SOURCES", 64, 1, 4096, false};
// The per-query memory budget (search_memory.hpp): 0 (a quarter of memory_limit, read per query), else 64 MiB to
// 1 TiB. The floor covers the fallback's start charge of 16 MiB per in-memory builder (Task 11).
constexpr Knob kQueryMemoryKnob {"QOD_SEARCH_QUERY_MEMORY", 0, 67108864, 1099511627776, true};
// The largest k qod_search takes (the library's own maximum is 10000, MAX_K).
constexpr Knob kMaxKKnob {"QOD_SEARCH_MAX_K", 10000, 1, 10000, false};
// The liveness cache (search_visibility.hpp): entries, and its own byte bound (256 MiB, at most 1 TiB).
constexpr Knob kLivenessCacheEntriesKnob {"QOD_SEARCH_LIVENESS_CACHE_ENTRIES", 64, 0, 100000, false};
constexpr Knob kLivenessCacheBytesKnob {"QOD_SEARCH_LIVENESS_CACHE_BYTES", 268435456, 0, 1099511627776, false};
// The joint bound of the two search caches (search_segments.hpp, SearchCachesBound): 4 GiB, at most 1 TiB.
constexpr Knob kSegmentCacheBytesKnob {"QOD_SEARCH_SEGMENT_CACHE_BYTES", 4294967296, 0, 1099511627776, false};
// How long a missing bundle is remembered, in seconds (D8: at most 60).
constexpr Knob kMissTtlSecKnob {"QOD_SEARCH_MISS_TTL_SEC", 60, 0, 60, false};
// The largest bundle LoadSegment reads: 1 GiB, 1 MiB to 64 GiB.
constexpr Knob kMaxBundleBytesKnob {"QOD_SEARCH_MAX_BUNDLE_BYTES", 1073741824, 1048576, 68719476736, false};

//! `knob`'s value as read from `text` (the environment's value, or null when unset), as the header says.
inline uint64_t KnobValue(const Knob &knob, const char *text) {
	if (!text || !*text) {
		return knob.fallback;
	}
	uint64_t value = 0;
	bool ok = true;
	for (auto c = text; *c && ok; c++) {
		auto digit = static_cast<uint64_t>(*c - '0');
		if (*c < '0' || *c > '9' || value > (UINT64_MAX - digit) / 10) {
			ok = false;
		} else {
			value = value * 10 + digit;
		}
	}
	ok = ok && ((value >= knob.least && value <= knob.greatest) || (knob.or_zero && value == 0));
	if (!ok) {
		std::cerr << "[qod-node] " << knob.name << " is not " << (knob.or_zero ? "0 or " : "") << "a number from "
		          << knob.least << " to " << knob.greatest << "; using " << knob.fallback << std::endl;
		return knob.fallback;
	}
	return value;
}

//! `knob`'s value from the node's environment.
inline uint64_t ReadKnob(const Knob &knob) {
	return KnobValue(knob, std::getenv(knob.name));
}

} // namespace search
} // namespace qod
