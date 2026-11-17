#include "search_segments.hpp"

#include "search_visibility.hpp"

#include "crypto.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/common/random_engine.hpp"
#include "duckdb/main/database.hpp"
#include "duckdb/storage/object_cache.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <list>
#include <optional>
#include <cstdio>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <tuple>

namespace qod::search {

using namespace duckdb;

namespace {

//! (bundle path, index id, bundle key, file size, footer size, table id and field ids, MAC key digest).
using CacheKey = std::tuple<std::string, std::string, std::string, uint64_t, uint64_t, std::string, std::string>;
//! (index id, path key, file size, footer size): the missing-bundle cache's key (D8).
using MissKey = std::tuple<std::string, std::string, uint64_t, uint64_t>;

constexpr uint64_t kDefaultCacheBytes = kSegmentCacheBytesKnob.fallback;
//! The longest a miss is remembered (D8: QOD_SEARCH_MISS_TTL_SEC may only lower it).
constexpr uint64_t kMaxMissTtlSec = kMissTtlSecKnob.greatest;
//! The most keys the missing-bundle cache holds; past it the expired ones go, then the oldest.
constexpr size_t kMaxMisses = 65536;
constexpr const char *kCacheName = "qod_search_segment_cache";

//! The process's settings and counts (the caches themselves are each instance's).
struct Settings {
	// Read at registration (ReadSegmentSettingsFromEnvironment); the defaults until then.
	std::atomic<uint64_t> max_bytes {kMaxBundleBytes};
	std::atomic<uint64_t> cache_bytes {kDefaultCacheBytes};
	std::atomic<uint64_t> miss_ttl_sec {kMissTtlSecKnob.fallback};
	std::atomic<int64_t> clock_offset_ms {0};
	std::atomic<bool> refuse_reservation {false};
	std::atomic<uint64_t> reads {0}, hits {0}, missing {0}, refused {0}, oversized {0}, bytes_read {0};
	std::atomic<uint64_t> missing_hits {0}, refused_hits {0}, temporary {0}, evictions {0}, temporary_bytes {0};
};

Settings &TheSettings() {
	static Settings settings;
	return settings;
}

int64_t NowMs() {
	auto now = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch());
	return static_cast<int64_t>(now.count()) + TheSettings().clock_offset_ms.load();
}

//! What the missing-bundle cache remembers of a key: the record's segments_version then, when, and the object's
//! size for a refused one (-1: missing).
struct Miss {
	uint64_t version = 0;
	int64_t at_ms = 0;
	int64_t object_size = -1;
};

struct Entry {
	CacheKey key;
	std::shared_ptr<const OpenSegment> segment;
};

//! One database instance's open-segment and missing-bundle caches, in its ObjectCache (never evicted by it), so
//! the blocks the entries hold go before the instance's buffer manager does.
class SegmentCache : public ObjectCacheEntry {
public:
	static string ObjectType() {
		return kCacheName;
	}
	string GetObjectType() override {
		return ObjectType();
	}
	optional_idx GetEstimatedCacheMemory() const override {
		return optional_idx();
	}

	std::mutex lock;
	//! Most recently used first.
	std::list<Entry> lru;
	std::map<CacheKey, std::list<Entry>::iterator> index;
	uint64_t held = 0;
	std::map<MissKey, Miss> misses;
	//! Review M4: a key a build on this node forgot, with the sequence number of the forget and when, so a probe that
	//! began before it never stores its (stale) miss; `sequence` counts forgets.
	std::map<MissKey, std::pair<uint64_t, int64_t>> forgotten;
	uint64_t sequence = 0;
	//! Files already logged as unindexed, by cache key.
	std::set<CacheKey> logged;

	//! Under `lock`: unheld entries (no running search holds them: the cache's is the only reference) evicted from
	//! the least recently used, until `fits()` holds or none is left; each one evicted moves into `gone`, freed by
	//! the caller once it released the lock.
	template <class FITS>
	void EvictUnheld(FITS fits, std::vector<std::shared_ptr<const OpenSegment>> &gone) {
		auto it = lru.end();
		while (!fits() && it != lru.begin()) {
			--it;
			if (it->segment.use_count() > 1) {
				continue;
			}
			held -= it->segment->held_bytes;
			gone.push_back(std::move(it->segment));
			index.erase(it->key);
			it = lru.erase(it);
			TheSettings().evictions++;
		}
	}
};

#ifdef QOD_NODE_TEST_HOOKS
std::mutex g_registry_lock;
std::vector<duckdb::weak_ptr<SegmentCache>> g_registry;

std::vector<duckdb::shared_ptr<SegmentCache>> Registered() {
	std::lock_guard<std::mutex> guard(g_registry_lock);
	std::vector<duckdb::shared_ptr<SegmentCache>> out;
	for (auto it = g_registry.begin(); it != g_registry.end();) {
		if (auto cache = it->lock()) {
			out.push_back(std::move(cache));
			++it;
		} else {
			it = g_registry.erase(it);
		}
	}
	return out;
}
#endif

duckdb::shared_ptr<SegmentCache> CacheFor(DatabaseInstance &db) {
	auto cache = db.GetObjectCache().GetOrCreate<SegmentCache>(kCacheName);
	if (!cache) {
		throw InvalidInputException("QoD: search_internal: the node's segment cache is not available");
	}
#ifdef QOD_NODE_TEST_HOOKS
	std::lock_guard<std::mutex> guard(g_registry_lock);
	bool known = false;
	for (auto &weak : g_registry) {
		known = known || weak.lock().get() == cache.get();
	}
	if (!known) {
		g_registry.push_back(duckdb::weak_ptr<SegmentCache>(cache));
	}
#endif
	return cache;
}

void LogOnce(SegmentCache &cache, const CacheKey &key, const std::string &what) {
	std::lock_guard<std::mutex> guard(cache.lock);
	if (cache.logged.insert(key).second) {
		std::cerr << "[qod-node] search: " << what << std::endl;
	}
}

//! The text of a library error, freed.
std::string Taken(char *err) {
	std::string text = err ? err : "search_internal: no message";
	qs_string_free(err);
	return text;
}

#ifdef QOD_NODE_TEST_HOOKS
std::mutex g_probe_lock;
std::function<void()> g_probe_observer;
#endif

//! Remembers `key` as missing (`object_size` -1) or refused (the object's size) under `version` (D8), unless a build
//! on this node forgot the key after the probe began (`probe`, the cache's forget sequence then: review M4).
void RememberMiss(SegmentCache &cache, const MissKey &key, uint64_t version, int64_t object_size, uint64_t probe) {
#ifdef QOD_NODE_TEST_HOOKS
	std::function<void()> observer;
	{
		std::lock_guard<std::mutex> guard(g_probe_lock);
		observer = g_probe_observer;
	}
	if (observer) {
		observer();
	}
#endif
	if (TheSettings().miss_ttl_sec.load() == 0) {
		return;
	}
	auto now = NowMs();
	auto ttl_ms = static_cast<int64_t>(TheSettings().miss_ttl_sec.load()) * 1000;
	std::lock_guard<std::mutex> guard(cache.lock);
	auto forgot = cache.forgotten.find(key);
	if (forgot != cache.forgotten.end() && forgot->second.first > probe) {
		return;
	}
	if (cache.misses.size() >= kMaxMisses && cache.misses.find(key) == cache.misses.end()) {
		for (auto it = cache.misses.begin(); it != cache.misses.end();) {
			it = now - it->second.at_ms >= ttl_ms ? cache.misses.erase(it) : std::next(it);
		}
		if (cache.misses.size() >= kMaxMisses) {
			auto oldest = cache.misses.begin();
			for (auto it = cache.misses.begin(); it != cache.misses.end(); ++it) {
				if (it->second.at_ms < oldest->second.at_ms) {
					oldest = it;
				}
			}
			cache.misses.erase(oldest);
		}
	}
	cache.misses[key] = Miss {version, now, object_size};
}

//! What the missing-bundle cache holds for `key` under `version` now, dropping a stale entry: none, or the miss.
std::optional<Miss> LookUpMiss(SegmentCache &cache, const MissKey &key, uint64_t version) {
	auto ttl_ms = static_cast<int64_t>(TheSettings().miss_ttl_sec.load()) * 1000;
	auto now = NowMs();
	std::lock_guard<std::mutex> guard(cache.lock);
	auto it = cache.misses.find(key);
	if (it == cache.misses.end()) {
		return std::nullopt;
	}
	// A recorded build moves the version (D8); the lifetime caps the rest, a clock that went back included.
	if (it->second.version != version || now - it->second.at_ms >= ttl_ms || now < it->second.at_ms) {
		cache.misses.erase(it);
		return std::nullopt;
	}
	return it->second;
}

//! A block of `size` bytes from the buffer manager (MemoryTag::EXTENSION, never destroyed, pinned while the handle
//! lives). When memory_limit refuses it, the cache's unheld entries are evicted for room and it is asked once
//! more; a second refusal throws DuckDB's OutOfMemoryException.
BufferHandle AllocateBlock(BufferManager &buffers, SegmentCache &cache, uint64_t size) {
	try {
		return buffers.Allocate(MemoryTag::EXTENSION, size, false);
	} catch (OutOfMemoryException &) {
	}
	std::vector<std::shared_ptr<const OpenSegment>> gone;
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto before = cache.held;
		cache.EvictUnheld([&] { return before - cache.held >= size; }, gone);
	}
	gone.clear();
	return buffers.Allocate(MemoryTag::EXTENSION, size, false);
}

//! Reserves `bytes` of the buffer manager for a cache entry's heap, making room as AllocateBlock does; false when
//! memory_limit refuses it.
bool Reserve(BufferManager &buffers, SegmentCache &cache, uint64_t bytes) {
	for (int attempt = 0; attempt < 2; attempt++) {
		try {
			if (TheSettings().refuse_reservation.load()) {
				throw OutOfMemoryException("could not reserve memory (test hook)");
			}
			buffers.ReserveMemory(bytes);
			return true;
		} catch (OutOfMemoryException &) {
		}
		if (attempt == 0) {
			std::vector<std::shared_ptr<const OpenSegment>> gone;
			{
				std::lock_guard<std::mutex> guard(cache.lock);
				auto before = cache.held;
				cache.EvictUnheld([&] { return before - cache.held >= bytes; }, gone);
			}
		}
	}
	return false;
}

//! The folders of `path` (an absolute local path or a URL), made where they are missing.
void MakeParents(FileSystem &fs, const std::string &path) {
	auto slash = path.find_last_of('/');
	if (slash == std::string::npos || slash == 0) {
		return;
	}
	auto parent = path.substr(0, slash);
	if (fs.DirectoryExists(parent)) {
		return;
	}
	MakeParents(fs, parent);
	if (!fs.DirectoryExists(parent)) {
		fs.CreateDirectory(parent);
	}
}

} // namespace

OpenSegment::~OpenSegment() {
	if (segment) {
		qs_segment_free(segment);
	}
	rowid_block.Destroy();
	bundle_block.Destroy();
	if (reserved_in && reserved) {
		reserved_in->FreeReservedMemory(reserved);
	}
}

std::shared_ptr<OpenSegment> WrapSegment(QsSegment *segment, uint64_t bytes) {
	auto open = std::make_shared<OpenSegment>();
	open->segment = segment;
	open->num_docs = qs_segment_num_docs(segment);
	open->explicit_rowids = qs_segment_has_explicit_rowids(segment) == 1;
	open->has_snapshot_column = qs_segment_has_snapshot_column(segment) == 1;
	open->first_rowid = open->num_docs > 0 ? qs_segment_rowid(segment, 0) : -1;
	open->bytes = bytes;
	open->heap_bytes = qs_segment_heap_bytes(segment);
	if (open->explicit_rowids) {
		open->rowid_docs.reserve(open->num_docs);
		for (uint32_t doc = 0; doc < open->num_docs; doc++) {
			open->rowid_docs.emplace_back(qs_segment_rowid(segment, doc), doc);
		}
		std::sort(open->rowid_docs.begin(), open->rowid_docs.end());
	}
	open->held_bytes = bytes + open->rowid_docs.capacity() * sizeof(OpenSegment::RowidDoc) + open->heap_bytes;
	return open;
}

QsSource BundleSource(const IndexRecord &record, const DataFileRef &file, std::vector<QsField> &fields) {
	fields.clear();
	for (auto &field : record.fields) {
		fields.push_back(QsField {field.column.c_str(), field.field_id});
	}
	QsSource source {};
	source.index_id = record.index_id.c_str();
	source.path_key = file.bundle_key.c_str();
	source.path = file.path.c_str();
	source.file_size = file.file_size;
	source.footer_size = file.footer_size;
	source.table_id = record.table_id;
	source.fields = fields.data();
	source.n_fields = fields.size();
	source.record_count = -1;
	source.row_id_start = file.row_id_start >= 0 ? file.row_id_start : -1;
	source.has_internal_row_id = -1;
	source.has_internal_snapshot = -1;
	// Always the record's own 32 bytes (IndexRecordFor refuses a record whose key is all zeros).
	source.mac_key = record.mac_key.data();
	source.mac_key_len = record.mac_key.size();
	return source;
}

namespace {

//! Fails the search for a block the buffer manager refused (memory_limit): search_memory_exceeded, with the size
//! only where QueryMemory shows figures.
[[noreturn]] void BlockRefused(const QueryMemory &memory, const char *what, uint64_t size) {
	memory.Exceeded(std::string("the buffer manager (memory_limit) cannot hold ") + what + " of " +
	                std::to_string(size) + " bytes");
}

void SetFlags(DataFileRef &file, const OpenSegment &open) {
	file.has_internal_row_id = open.explicit_rowids;
	file.has_internal_snapshot = open.has_snapshot_column;
	file.flags_known = true;
}

} // namespace

std::shared_ptr<const OpenSegment> LoadSegment(ClientContext &context, const std::string &bundle_path,
                                               const IndexRecord &record, DataFileRef &file, QueryMemory &memory) {
	auto &settings = TheSettings();
	auto cache_ptr = CacheFor(DatabaseInstance::GetDatabase(context));
	auto &cache = *cache_ptr;
	std::string ids = std::to_string(record.table_id);
	for (auto &field : record.fields) {
		ids += "," + std::to_string(field.field_id);
	}
	// The key's digest, never the key: the cache key is never printed, but holds no key material either.
	auto key_digest = Hex(Sha256(std::string(reinterpret_cast<const char *>(record.mac_key.data()), record.mac_key.size())));
	CacheKey key {bundle_path, record.index_id, file.bundle_key, file.file_size, file.footer_size, ids, key_digest};
	uint64_t probe = 0;
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.index.find(key);
		if (it != cache.index.end()) {
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			settings.hits++;
			auto found = it->second->segment;
			SetFlags(file, *found);
			return found;
		}
		probe = cache.sequence;
	}
	MissKey miss_key {record.index_id, file.bundle_key, file.file_size, file.footer_size};
	if (auto miss = LookUpMiss(cache, miss_key, record.segments_version)) {
		// D8: missing (or refused) until the record's version moves or a build on this node drops the key, at most the
		// lifetime; no FileSystem call either way (review M3).
		if (miss->object_size < 0) {
			settings.missing_hits++;
		} else {
			settings.refused_hits++;
		}
		return nullptr;
	}
	auto &fs = FileSystem::GetFileSystem(context);
	// The instance's own buffer manager, never the connection's (BufferManager::GetBufferManager(context) is the
	// client's, which goes with the connection): a cache entry and its reservation outlive the statement.
	auto &buffers = BufferManager::GetBufferManager(DatabaseInstance::GetDatabase(context));
	auto open = std::make_shared<OpenSegment>();
	uint64_t object_size = 0;
	try {
		settings.reads++;
		auto handle = fs.OpenFile(bundle_path, FileFlags::FILE_FLAGS_READ | FileFlags::FILE_FLAGS_NULL_IF_NOT_EXISTS);
		if (!handle) {
			settings.missing++;
			RememberMiss(cache, miss_key, record.segments_version, -1, probe);
			LogOnce(cache, key, "no bundle for " + file.path + " (unindexed)");
			return nullptr;
		}
		// The size first (a stat, or the object store's HEAD at open), and none of its bytes past the cap.
		auto size = handle->GetFileSize();
		object_size = size;
		if (size > settings.max_bytes.load()) {
			settings.oversized++;
			RememberMiss(cache, miss_key, record.segments_version, static_cast<int64_t>(size), probe);
			LogOnce(cache, key, "the bundle for " + file.path + " is larger than the bundle cap (unindexed)");
			return nullptr;
		}
		if (size == 0) {
			settings.refused++;
			RememberMiss(cache, miss_key, record.segments_version, 0, probe);
			LogOnce(cache, key, "the bundle for " + file.path + " is empty (unindexed)");
			return nullptr;
		}
		// Spec 9, ruling 8: the bytes are the buffer manager's from the first one read (a refusal: the catch below).
		open->bundle_block = AllocateBlock(buffers, cache, size);
		auto bytes = open->bundle_block.GetDataMutable();
		idx_t at = 0;
		while (at < size) {
			auto got = handle->Read(bytes + at, size - at);
			if (got <= 0) {
				break;
			}
			at += static_cast<idx_t>(got);
			settings.bytes_read += static_cast<uint64_t>(got);
		}
		if (at != size) {
			settings.refused++;
			RememberMiss(cache, miss_key, record.segments_version, static_cast<int64_t>(size), probe);
			LogOnce(cache, key, "the bundle for " + file.path + " could not be read whole (unindexed)");
			return nullptr;
		}
		open->bytes = size;
	} catch (InterruptException &) {
		throw;
	} catch (OutOfMemoryException &) {
		// memory_limit refused the bundle's block: the search's failure, not the bundle's.
		BlockRefused(memory, "a bundle", object_size);
	} catch (std::exception &) {
		settings.refused++;
		LogOnce(cache, key, "the bundle for " + file.path + " could not be read (unindexed)");
		return nullptr;
	}
	std::vector<QsField> fields;
	auto source = BundleSource(record, file, fields);
	char *err = nullptr;
	// In place (Task 12): the segment reads the block, which lives as long as `open`.
	auto segment = qs_segment_open_borrowed(open->bundle_block.Ptr(), open->bytes, &source, &err);
	if (!segment) {
		settings.refused++;
		RememberMiss(cache, miss_key, record.segments_version, static_cast<int64_t>(open->bytes), probe);
		// The library's code only (bundle_mac_mismatch, bundle_mismatch, bundle_corrupt): its messages name no
		// key, and the rest of a message is not needed to tell the three apart.
		auto text = Taken(err);
		LogOnce(cache, key, "the bundle for " + file.path + " was refused (unindexed): " + text.substr(0, text.find(':')));
		return nullptr;
	}
	open->segment = segment;
	open->num_docs = qs_segment_num_docs(segment);
	open->explicit_rowids = qs_segment_has_explicit_rowids(segment) == 1;
	open->has_snapshot_column = qs_segment_has_snapshot_column(segment) == 1;
	open->first_rowid = open->num_docs > 0 ? qs_segment_rowid(segment, 0) : -1;
	open->heap_bytes = qs_segment_heap_bytes(segment);
	uint64_t list_bytes = 0;
	if (open->explicit_rowids && open->num_docs > 0) {
		list_bytes = static_cast<uint64_t>(open->num_docs) * sizeof(OpenSegment::RowidDoc);
		try {
			open->rowid_block = AllocateBlock(buffers, cache, list_bytes);
		} catch (OutOfMemoryException &) {
			BlockRefused(memory, "a segment's rowid list", list_bytes);
		}
		auto docs = reinterpret_cast<OpenSegment::RowidDoc *>(open->rowid_block.GetDataMutable());
		for (uint32_t doc = 0; doc < open->num_docs; doc++) {
			new (docs + doc) OpenSegment::RowidDoc(qs_segment_rowid(segment, doc), doc);
		}
		std::sort(docs, docs + open->num_docs);
		open->rowid_block_docs = docs;
		open->rowid_block_count = open->num_docs;
	}
	open->held_bytes = open->bytes + list_bytes + open->heap_bytes;
	SetFlags(file, *open);
	// The cache takes the entry when its share holds it beside the entries running searches hold and the liveness
	// cache's, and the buffer manager reserves its heap; else it is this search's copy. Its share (lead ruling
	// 2026-11-17, provisional, review I1): the search caches together hold at most SearchCachesBound, a quarter of
	// memory_limit at most, since entries are pinned and a cache at the limit would leave the database no memory.
	auto total = SearchCachesBound(buffers);
	// The liveness cache's part: what it holds, and never less than its slice (fix round 2: no starving it).
	auto live = std::max(LivenessCacheBytes(DatabaseInstance::GetDatabase(context)), LivenessSlice(buffers));
	auto bound = total > live ? total - live : 0;
	std::shared_ptr<const OpenSegment> found;
	if (bound > 0 && open->held_bytes <= bound && Reserve(buffers, cache, open->heap_bytes)) {
		open->reserved_in = &buffers;
		open->reserved = open->heap_bytes;
		std::vector<std::shared_ptr<const OpenSegment>> gone;
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.index.find(key);
		if (it != cache.index.end()) {
			// Another search loaded it meanwhile: one copy is kept, ours goes.
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			found = it->second->segment;
		} else {
			auto need = open->held_bytes;
			cache.EvictUnheld([&] { return cache.held + need <= bound; }, gone);
			if (cache.held + need <= bound) {
				cache.lru.push_front(Entry {key, open});
				cache.index[key] = cache.lru.begin();
				cache.held += need;
				found = open;
			}
		}
	}
	if (found) {
		return found;
	}
	// The search's copy (spec 9): blocks of the buffer manager for the search's life, charged to its QueryMemory,
	// never the cache's; a heap reservation made for the cache stays with the copy until it goes (review M2). Under
	// the default budget (a quarter of memory_limit) a bundle the cache cannot hold (above its share, at most that
	// same quarter) does not fit the budget either: accepted (LEAD PROVISIONAL RULING, Task 16 fix round 2), so the
	// refusal says how to fix it.
	memory.Charge(open->held_bytes, "bundle copies", "raise QOD_SEARCH_QUERY_MEMORY for nodes whose bundles exceed it");
	settings.temporary++;
	settings.temporary_bytes += open->held_bytes;
	return open;
}

void ForgetMissingBundle(ClientContext &context, const std::string &index_id, const std::string &path_key,
                         uint64_t file_size, uint64_t footer_size) {
	auto cache = CacheFor(DatabaseInstance::GetDatabase(context));
	MissKey key {index_id, path_key, file_size, footer_size};
	auto now = NowMs();
	std::lock_guard<std::mutex> guard(cache->lock);
	cache->misses.erase(key);
	// A probe that began before this forget must not store its miss (review M4): stamped for twice the longest
	// lifetime, well past any probe.
	if (cache->forgotten.size() >= kMaxMisses) {
		for (auto it = cache->forgotten.begin(); it != cache->forgotten.end();) {
			it = now - it->second.second >= int64_t(2 * kMaxMissTtlSec * 1000) ? cache->forgotten.erase(it) : std::next(it);
		}
	}
	if (cache->forgotten.size() < kMaxMisses || cache->forgotten.count(key)) {
		cache->forgotten[key] = {++cache->sequence, now};
	} else {
		// Full of recent stamps: forget every miss instead, so no stale one can be stored behind this build.
		cache->misses.clear();
		cache->forgotten.clear();
		cache->forgotten[key] = {++cache->sequence, now};
	}
}

uint64_t SearchCachesBound(BufferManager &buffers) {
	return std::min<uint64_t>(TheSettings().cache_bytes.load(), buffers.GetMaxMemory() / 4);
}

void ReadSegmentSettingsFromEnvironment() {
	auto &settings = TheSettings();
	settings.max_bytes = ReadKnob(kMaxBundleBytesKnob);
	settings.cache_bytes = ReadKnob(kSegmentCacheBytesKnob);
	settings.miss_ttl_sec = ReadKnob(kMissTtlSecKnob);
}

uint64_t SegmentCacheBytes(DatabaseInstance &db) {
	auto cache = db.GetObjectCache().Get<SegmentCache>(kCacheName);
	if (!cache) {
		return 0;
	}
	std::lock_guard<std::mutex> guard(cache->lock);
	return cache->held;
}

uint64_t ReleaseSearchCaches(DatabaseInstance &db, uint64_t bytes) {
	uint64_t freed = 0;
	if (auto cache = db.GetObjectCache().Get<SegmentCache>(kCacheName)) {
		std::vector<std::shared_ptr<const OpenSegment>> gone;
		std::lock_guard<std::mutex> guard(cache->lock);
		auto before = cache->held;
		cache->EvictUnheld([&] { return before - cache->held >= bytes; }, gone);
		freed = before - cache->held;
	}
	if (freed < bytes) {
		freed += EvictUnheldLiveness(db, bytes - freed);
	}
	return freed;
}

namespace {

//! Writes `len` bytes to `target` and closes it. On a remote file a failure aborts the upload rather
//! than closing it, since httpfs's Close completes the upload and so would publish a partial object.
void WriteWhole(FileSystem &fs, const std::string &target, const uint8_t *bytes, size_t len, bool remote) {
	auto handle = fs.OpenFile(target, FileFlags::FILE_FLAGS_WRITE | FileFlags::FILE_FLAGS_FILE_CREATE_NEW);
	auto abandon = [&]() {
		try {
			if (remote) {
				fs.AbortFileWrite(*handle);
			} else {
				handle->Close();
			}
		} catch (...) {
		}
	};
	try {
		idx_t at = 0;
		while (at < len) {
			auto wrote = handle->Write(const_cast<uint8_t *>(bytes) + at, len - at);
			if (wrote <= 0) {
				throw IOException("QoD: search_build: the bundle could not be written");
			}
			at += static_cast<idx_t>(wrote);
		}
		handle->Sync();
	} catch (...) {
		abandon();
		throw;
	}
	handle->Close();
}

} // namespace

void WriteBundle(ClientContext &context, const std::string &path, const uint8_t *bytes, size_t len) {
	auto &fs = FileSystem::GetFileSystem(context);
	MakeParents(fs, path);
	if (FileSystem::IsRemoteFile(path)) {
		// Object stores have no rename (httpfs: "MoveFile is not implemented"). An object becomes
		// visible only when its upload completes, at Close, so a reader never sees a partial bundle
		// (spec 6.2). The put is unconditional: two concurrent builds of one file end with the last
		// one's whole bundle (owner ruling 2026-11-16, Task 15a; a conditional put is Phase 2). The
		// final key is never removed on failure, as it may hold another build's good bundle.
		WriteWhole(fs, path, bytes, len, true);
		return;
	}
	RandomEngine random;
	char suffix[17];
	std::snprintf(suffix, sizeof(suffix), "%08x%08x", random.NextRandomInteger(), random.NextRandomInteger());
	auto temporary = path + ".tmp-" + suffix;
	try {
		WriteWhole(fs, temporary, bytes, len, false);
	} catch (...) {
		fs.TryRemoveFile(temporary);
		throw;
	}
	try {
		fs.MoveFile(temporary, path);
	} catch (...) {
		fs.TryRemoveFile(temporary);
		throw;
	}
}

#ifdef QOD_NODE_TEST_HOOKS
SegmentCacheCounters SegmentCacheCountersForTest() {
	auto &s = TheSettings();
	SegmentCacheCounters c;
	c.reads = s.reads.load();
	c.hits = s.hits.load();
	c.missing = s.missing.load();
	c.refused = s.refused.load();
	c.oversized = s.oversized.load();
	c.bytes_read = s.bytes_read.load();
	c.missing_hits = s.missing_hits.load();
	c.refused_hits = s.refused_hits.load();
	c.temporary = s.temporary.load();
	c.evictions = s.evictions.load();
	c.byte_bound = s.cache_bytes.load();
	for (auto &cache : Registered()) {
		std::lock_guard<std::mutex> guard(cache->lock);
		c.entries += cache->lru.size();
		c.held_bytes += cache->held;
		c.misses_held += cache->misses.size();
		for (auto &entry : cache->lru) {
			c.bundle_bytes += entry.segment->bytes;
			c.rowid_bytes += entry.segment->rowid_block_count * sizeof(OpenSegment::RowidDoc);
			c.reserved_bytes += entry.segment->reserved;
		}
	}
	return c;
}

void MissProbeObserverForTest(std::function<void()> observer) {
	std::lock_guard<std::mutex> guard(g_probe_lock);
	g_probe_observer = std::move(observer);
}

void ClearSegmentCacheForTest() {
	for (auto &cache : Registered()) {
		std::list<Entry> gone;
		{
			std::lock_guard<std::mutex> guard(cache->lock);
			gone.swap(cache->lru);
			cache->index.clear();
			cache->held = 0;
			cache->misses.clear();
			cache->forgotten.clear();
			cache->logged.clear();
		}
	}
}

void SetMaxBundleBytesForTest(uint64_t bytes) {
	TheSettings().max_bytes = bytes == 0 ? kMaxBundleBytes : bytes;
}

void SetSegmentCacheBytesForTest(uint64_t bytes) {
	auto bound = bytes == kDefault ? kDefaultCacheBytes : bytes;
	TheSettings().cache_bytes = bound;
	for (auto &cache : Registered()) {
		std::vector<std::shared_ptr<const OpenSegment>> gone;
		std::lock_guard<std::mutex> guard(cache->lock);
		cache->EvictUnheld([&] { return cache->held <= bound; }, gone);
	}
}

SegmentSettings SegmentSettingsForTest() {
	auto &settings = TheSettings();
	return SegmentSettings {settings.max_bytes.load(), settings.cache_bytes.load(), settings.miss_ttl_sec.load()};
}

void SetMissTtlForTest(uint64_t seconds) {
	TheSettings().miss_ttl_sec = seconds == kDefault ? kMaxMissTtlSec : std::min(seconds, kMaxMissTtlSec);
}

void AdvanceSearchClockForTest(int64_t ms) {
	TheSettings().clock_offset_ms += ms;
}

void RefuseCacheReservationForTest(bool on) {
	TheSettings().refuse_reservation = on;
}

uint64_t TemporaryBundleBytesForTest() {
	return TheSettings().temporary_bytes.load();
}

void ResetTemporaryBundleBytesForTest() {
	TheSettings().temporary_bytes = 0;
}

uint64_t TakeSegmentLocksForTest(DatabaseInstance &db) {
	uint64_t taken = 0;
	{
		std::lock_guard<std::mutex> guard(g_registry_lock);
		taken++;
	}
	{
		std::lock_guard<std::mutex> guard(g_probe_lock);
		taken++;
	}
	if (auto cache = db.GetObjectCache().Get<SegmentCache>(kCacheName)) {
		std::lock_guard<std::mutex> guard(cache->lock);
		taken++;
	}
	return taken;
}
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
