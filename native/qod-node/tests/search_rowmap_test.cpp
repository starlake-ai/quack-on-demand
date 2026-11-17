// QoD Search (spec 7.1 step 3, 9, ruling 10, Task 11): RowidMap, rowid to its one live (segment, doc), on
// segments made by hand (no bundle: OpenSegment's rowid fields only). The prototype's Locate cases (Q1's
// overlapping ranges and merged file, the repeated rowid of search_engine_test V3), then the bounds: rowids far
// past every segment cost no allocation before they are refused, and a scan-order drain of 10^7 lookups over 300
// segments is fast (Release builds only).

#include "search_rowmap.hpp"

#include "duckdb/common/exception.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <new>
#include <string>
#include <vector>

// ---- a counting allocator: the bytes operator new hands out while counting is on ------------------

namespace {
std::atomic<bool> g_counting {false};
std::atomic<uint64_t> g_bytes {0};
std::atomic<uint64_t> g_largest {0};

void Count(std::size_t size) {
	if (g_counting.load(std::memory_order_relaxed)) {
		g_bytes += size;
		auto largest = g_largest.load();
		while (size > largest && !g_largest.compare_exchange_weak(largest, size)) {
		}
	}
}
} // namespace

void *operator new(std::size_t size) {
	Count(size);
	if (auto p = std::malloc(size ? size : 1)) {
		return p;
	}
	throw std::bad_alloc();
}
void *operator new[](std::size_t size) {
	Count(size);
	if (auto p = std::malloc(size ? size : 1)) {
		return p;
	}
	throw std::bad_alloc();
}
void operator delete(void *p) noexcept {
	std::free(p);
}
void operator delete[](void *p) noexcept {
	std::free(p);
}
void operator delete(void *p, std::size_t) noexcept {
	std::free(p);
}
void operator delete[](void *p, std::size_t) noexcept {
	std::free(p);
}

namespace {

using qod::search::OpenSegment;
using qod::search::RowidMap;

int g_failures = 0;

void Check(bool ok, const std::string &what, const std::string &detail = "") {
	std::cout << (ok ? "ok   " : "FAIL ") << what << (detail.empty() ? "" : ": " + detail) << std::endl;
	if (!ok) {
		g_failures++;
	}
}

//! A segment of `docs` docs whose rowids are first + doc.
std::shared_ptr<const OpenSegment> Implicit(int64_t first, uint32_t docs) {
	auto s = std::make_shared<OpenSegment>();
	s->num_docs = docs;
	s->first_rowid = first;
	return s;
}

//! A segment whose doc d holds rowids[d] (Q1's merged file: {0, 3, 1}).
std::shared_ptr<const OpenSegment> Explicit(const std::vector<int64_t> &rowids) {
	auto s = std::make_shared<OpenSegment>();
	s->num_docs = static_cast<uint32_t>(rowids.size());
	s->explicit_rowids = true;
	s->first_rowid = rowids.empty() ? -1 : rowids[0];
	for (uint32_t d = 0; d < rowids.size(); d++) {
		s->rowid_docs.emplace_back(rowids[d], d);
	}
	std::sort(s->rowid_docs.begin(), s->rowid_docs.end());
	return s;
}

std::vector<uint64_t> Bits(uint32_t docs, bool on) {
	std::vector<uint64_t> words((docs + 63) / 64, on ? ~uint64_t(0) : 0);
	if (on && docs % 64) {
		words.back() = (uint64_t(1) << (docs % 64)) - 1;
	}
	return words;
}

void Set(std::vector<uint64_t> &bits, uint32_t doc, bool on) {
	if (on) {
		bits[doc >> 6] |= uint64_t(1) << (doc & 63);
	} else {
		bits[doc >> 6] &= ~(uint64_t(1) << (doc & 63));
	}
}

struct Map {
	std::vector<std::shared_ptr<const OpenSegment>> segments;
	std::vector<std::vector<uint64_t>> live;
	std::unique_ptr<RowidMap> map;
	void Build() {
		std::vector<const std::vector<uint64_t> *> pointers;
		for (auto &l : live) {
			pointers.push_back(&l);
		}
		map = std::make_unique<RowidMap>(segments, pointers);
	}
};

//! "segment/doc", or the error's message.
std::string At(const RowidMap &map, int64_t rowid, uint32_t &hint) {
	try {
		auto at = map.Locate(rowid, hint);
		return std::to_string(at.first) + "/" + std::to_string(at.second);
	} catch (std::exception &e) {
		return duckdb::ErrorData(e).RawMessage();
	}
}

std::string At(const RowidMap &map, int64_t rowid) {
	uint32_t hint = RowidMap::kNoHint;
	return At(map, rowid, hint);
}

bool Holds(const std::string &text, const std::string &needle) {
	return text.find(needle) != std::string::npos;
}

void Overlapping() {
	// [0, 1000) and [500, 1500): rowid 700 is doc 700 of the first and doc 200 of the second.
	Map m;
	m.segments = {Implicit(0, 1000), Implicit(500, 1000)};
	m.live = {Bits(1000, true), Bits(1000, true)};
	Set(m.live[0], 700, false);
	m.Build();
	Check(At(*m.map, 700) == "1/200", "R1 rowid 700, live only in [500, 1500): there", At(*m.map, 700));
	Set(m.live[0], 700, true);
	Set(m.live[1], 200, false);
	Check(At(*m.map, 700) == "0/700", "R1 rowid 700, live only in [0, 1000): there", At(*m.map, 700));
	Set(m.live[1], 200, true);
	auto both = At(*m.map, 700);
	Check(Holds(both, "QoD: search_rowid_ambiguous: rowid 700 is live in more than one doc"),
	      "R2 rowid 700 live in both: search_rowid_ambiguous", both);
	Set(m.live[0], 700, false);
	Set(m.live[1], 200, false);
	auto none = At(*m.map, 700);
	Check(Holds(none, "QoD: search_snapshot_unavailable: rowid 700 of the input is live in no segment"),
	      "R3 rowid 700 live in neither: search_snapshot_unavailable", none);
	Check(At(*m.map, 100) == "0/100" && At(*m.map, 1400) == "1/900", "R1 rowids held by one range only");
	// The hint never answers for a range another range overlaps: a hinted lookup still sees both candidates.
	uint32_t hint = RowidMap::kNoHint;
	Set(m.live[0], 700, true);
	Set(m.live[1], 200, true);
	At(*m.map, 600, hint);
	auto hinted = At(*m.map, 700, hint);
	Check(Holds(hinted, "search_rowid_ambiguous"), "R2 after a hint into the first range, still ambiguous", hinted);
	Check(m.map->TotalDocs() == 2000, "TotalDocs", std::to_string(m.map->TotalDocs()));
}

void Merged() {
	// Q1's merged file: rowids 0, 3, 1 at docs 0, 1, 2.
	Map m;
	m.segments = {Explicit({0, 3, 1})};
	m.live = {Bits(3, true)};
	m.Build();
	Check(At(*m.map, 0) == "0/0" && At(*m.map, 3) == "0/1" && At(*m.map, 1) == "0/2",
	      "R4 explicit rowids {0, 3, 1} locate each to its doc", At(*m.map, 0) + " " + At(*m.map, 3) + " " + At(*m.map, 1));
	Check(Holds(At(*m.map, 2), "search_snapshot_unavailable"), "R4 rowid 2, held by no doc", At(*m.map, 2));
	// An implicit range and an explicit file sharing rowid 50: the hint into the implicit range is not trusted.
	Map o;
	o.segments = {Implicit(0, 100), Explicit({50, 200, 7})};
	o.live = {Bits(100, true), Bits(3, true)};
	o.Build();
	uint32_t hint = RowidMap::kNoHint;
	At(*o.map, 10, hint);
	Check(Holds(At(*o.map, 50, hint), "search_rowid_ambiguous"), "R4 a rowid in an implicit range and a merged file");
	Set(o.live[0], 50, false);
	hint = RowidMap::kNoHint;
	At(*o.map, 10, hint);
	Check(At(*o.map, 50, hint) == "1/0" && At(*o.map, 200, hint) == "1/1", "R4 the merged file's live doc",
	      At(*o.map, 50));
}

void SameSegmentTwice() {
	// Security verification minor 3: one explicit segment whose two docs hold one rowid.
	Map m;
	m.segments = {Explicit({10, 42, 42, 7})};
	m.live = {Bits(4, true)};
	m.Build();
	Check(Holds(At(*m.map, 42), "QoD: search_rowid_ambiguous: rowid 42 is live in more than one doc"),
	      "R5b two live docs of one explicit segment holding one rowid: search_rowid_ambiguous", At(*m.map, 42));
	Set(m.live[0], 1, false);
	Check(At(*m.map, 42) == "0/2", "R5b one of them dead: the live one", At(*m.map, 42));
	Set(m.live[0], 2, false);
	Check(Holds(At(*m.map, 42), "search_snapshot_unavailable"), "R5b both dead: search_snapshot_unavailable");
	// Through the hint: the segment is the only interval, so it is alone, and its duplicates are still counted.
	Set(m.live[0], 1, true);
	Set(m.live[0], 2, true);
	uint32_t hint = RowidMap::kNoHint;
	At(*m.map, 10, hint);
	Check(Holds(At(*m.map, 42, hint), "search_rowid_ambiguous"), "R5b after a hint into the segment, still ambiguous");
}

void Repeated() {
	// search_engine_test V3: after an update and a flush, a rowid is held by a dead doc of file 2 and a live doc of
	// the flushed file (explicit there); Locate takes the live one.
	Map m;
	m.segments = {Implicit(0, 10000), Implicit(10000, 10000), Explicit({15000})};
	m.live = {Bits(10000, true), Bits(10000, true), Bits(1, true)};
	Set(m.live[1], 5000, false);
	m.Build();
	Check(At(*m.map, 15000) == "2/0", "R5 a repeated rowid resolves to its one live doc", At(*m.map, 15000));
	Check(At(*m.map, 15001) == "1/5001", "R5 its neighbour stays in file 2", At(*m.map, 15001));
}

void FarRowids() {
	Map m;
	m.segments = {Implicit(0, 1000), Implicit(1000, 1000), Explicit({3000, 2500, 2501})};
	m.live = {Bits(1000, true), Bits(1000, true), Bits(3, true)};
	m.Build();
	std::vector<uint64_t> totals;
	for (int64_t rowid : {int64_t(1) << 27, int64_t(1) << 40, int64_t(1) << 62, INT64_MAX, int64_t(-1), INT64_MIN}) {
		uint32_t hint = 0;
		At(*m.map, 10, hint); // a hint into a range, so the fast path sees the far rowid first
		g_bytes = 0;
		g_largest = 0;
		g_counting = true;
		auto text = At(*m.map, rowid, hint);
		g_counting = false;
		totals.push_back(g_bytes.load());
		Check(Holds(text, "QoD: search_snapshot_unavailable: rowid " + std::to_string(rowid)) &&
		          g_largest.load() < 1024 && g_bytes.load() < 16384,
		      "R6 rowid " + std::to_string(rowid) + ": search_snapshot_unavailable, no allocation sized by it",
		      std::to_string(g_bytes.load()) + " bytes in all, the largest " + std::to_string(g_largest.load()) + "; " +
		          text);
	}
	// What the refusal allocates (its message) is the same at 2^27 as at 2^62, but for the digits.
	Check(totals[2] < totals[0] + 256, "R6 2^62 allocates no more than 2^27 but for its digits",
	      std::to_string(totals[0]) + " and " + std::to_string(totals[2]));
	// A segment far up the rowid space: located, nothing sized by its rowids.
	Map far;
	far.segments = {Implicit(int64_t(1) << 40, 100), Implicit((int64_t(1) << 62) - 50, 50)};
	far.live = {Bits(100, true), Bits(50, true)};
	g_bytes = 0;
	g_counting = true;
	far.Build();
	g_counting = false;
	Check(At(*far.map, (int64_t(1) << 40) + 99) == "0/99" && At(*far.map, (int64_t(1) << 62) - 1) == "1/49" &&
	          g_bytes.load() < RowidMap::BytesFor(2) + 4096,
	      "R7 segments at 2^40 and just below 2^62: located; the map holds about BytesFor(2)",
	      std::to_string(g_bytes.load()) + " bytes built, BytesFor " + std::to_string(RowidMap::BytesFor(2)));
	// A segment whose rowids would pass INT64_MAX is refused when the map is built.
	Map over;
	over.segments = {Implicit(INT64_MAX - 10, 100)};
	over.live = {Bits(100, true)};
	bool refused = false;
	try {
		over.Build();
	} catch (std::exception &e) {
		refused = Holds(duckdb::ErrorData(e).RawMessage(), "search_snapshot_unavailable");
	}
	Check(refused, "R7 a segment whose rowids pass the largest rowid is refused");
}

void ScanOrder() {
	// 300 segments of 33,334 docs, contiguous, every doc live: 10,000,200 rowids, looked up in scan order with the
	// hint, as a local state of the drain does.
	const uint32_t per = 33334;
	Map m;
	for (int s = 0; s < 300; s++) {
		m.segments.push_back(Implicit(int64_t(s) * per, per));
		m.live.push_back(Bits(per, true));
	}
	m.Build();
	const int64_t total = int64_t(300) * per;
	uint64_t checksum = 0;
	double ms = 0;
	// One warm pass (the bitsets' first touch), then the best of three timed passes.
	for (int pass = 0; pass < 4; pass++) {
		uint32_t hint = RowidMap::kNoHint;
		uint64_t sum = 0;
		auto start = std::chrono::steady_clock::now();
		for (int64_t rowid = 0; rowid < total; rowid++) {
			auto at = m.map->Locate(rowid, hint);
			sum += at.first + at.second;
		}
		auto took = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
		checksum = sum;
		if (pass == 1 || (pass > 1 && took < ms)) {
			ms = took;
		}
	}
	uint64_t want = 0;
	for (uint64_t s = 0; s < 300; s++) {
		want += s * per + uint64_t(per) * (per - 1) / 2;
	}
	Check(checksum == want, "R8 10,000,200 scan-order lookups over 300 segments: every rowid where it is");
	// The 10 ms bound is a timing: asserted only when asked (QOD_SEARCH_ROWMAP_TIMING=1, a quiet host, a Release
	// build), so a shared CI runner does not flake on it; the figure is printed either way.
	auto timing = std::getenv("QOD_SEARCH_ROWMAP_TIMING");
#ifdef NDEBUG
	bool release = true;
#else
	bool release = false;
#endif
	if (timing && std::string(timing) == "1" && release) {
		Check(ms < 10.0,
		      "R8 10^7 scan-order lookups over 300 segments under 10 ms with the hint (best of 3 after a warm pass)",
		      std::to_string(ms) + " ms");
	} else {
		std::cout << "skip R8 timing bound (QOD_SEARCH_ROWMAP_TIMING=1 on a Release build asserts it): " << ms << " ms"
		          << std::endl;
	}
	// The same lookups with no hint kept, for the record.
	auto start = std::chrono::steady_clock::now();
	for (int64_t rowid = 0; rowid < total; rowid++) {
		uint32_t none = RowidMap::kNoHint;
		checksum += m.map->Locate(rowid, none).second;
	}
	auto cold = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
	std::cout << "   R8 with the hint " << ms << " ms, without " << cold << " ms" << std::endl;
}

} // namespace

int main() {
	Overlapping();
	Merged();
	Repeated();
	SameSegmentTwice();
	FarRowids();
	ScanOrder();
	std::cout << (g_failures == 0 ? "search_rowmap: all checks passed" : "search_rowmap: failures") << std::endl;
	return g_failures == 0 ? 0 : 1;
}
