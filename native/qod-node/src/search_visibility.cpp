#include "search_visibility.hpp"

#include "search_knobs.hpp"
#include "search_memory.hpp"
#include "search_segments.hpp"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/main/attached_database.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/database.hpp"
#include "duckdb/storage/buffer_manager.hpp"
#include "duckdb/storage/object_cache.hpp"
#include "duckdb/transaction/meta_transaction.hpp"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <iostream>
#include <list>
#include <map>
#include <mutex>
#include <tuple>

namespace qod::search {

using namespace duckdb;

namespace {

//! (catalog oid, table oid): one entry per table, at its newest S seen (review I2). The cache is the database
//! instance's own (Task 12), so attach oids, which restart per instance (review M1), never meet another's.
using TableKey = std::tuple<idx_t, uint64_t>;

constexpr uint64_t kDefaultEntries = kLivenessCacheEntriesKnob.fallback;
constexpr uint64_t kDefaultBytes = kLivenessCacheBytesKnob.fallback;
constexpr const char *kCacheName = "qod_search_liveness_cache";

//! The process's bounds and counts (the caches themselves are each instance's).
struct Settings {
	// Read at registration (ReadLivenessSettingsFromEnvironment); the defaults until then.
	std::atomic<uint64_t> capacity {kDefaultEntries};
	std::atomic<uint64_t> byte_bound {kDefaultBytes};
	std::atomic<uint64_t> hits {0}, misses {0}, bypassed {0}, replaced {0}, evictions {0}, too_large {0},
	    block_refused {0}, over_share {0};
};

Settings &TheSettings() {
	static Settings settings;
	return settings;
}

struct Entry {
	TableKey key;
	int64_t snapshot;
	std::shared_ptr<const SnapshotView> view;
	uint64_t bytes;
};

//! The block a cached view's liveness words live in (SnapshotView::liveness_block).
struct LivenessBlock {
	BufferHandle handle;
	uint64_t bytes = 0;
};

class Cache : public ObjectCacheEntry {
public:
	static string ObjectType() {
		return kCacheName;
	}
	string GetObjectType() override {
		return ObjectType();
	}
	optional_idx GetEstimatedCacheMemory() const override {
		return optional_idx(); // never evicted by the ObjectCache
	}

	std::mutex lock;
	//! Most recently used first.
	std::list<Entry> lru;
	std::map<TableKey, std::list<Entry>::iterator> index;
	uint64_t bytes = 0;

	//! Under `lock`; the view goes to `gone`, freed by the caller once it released the lock.
	void Erase(std::list<Entry>::iterator it, std::vector<std::shared_ptr<const SnapshotView>> &gone) {
		bytes -= it->bytes;
		gone.push_back(std::move(it->view));
		index.erase(it->key);
		lru.erase(it);
	}
	//! Under `lock`: least recently used first, past either bound (`count`: as evictions).
	void Fit(uint64_t capacity, uint64_t byte_bound, std::vector<std::shared_ptr<const SnapshotView>> &gone,
	         bool count) {
		while (!lru.empty() && (lru.size() > capacity || bytes > byte_bound)) {
			Erase(std::prev(lru.end()), gone);
			if (count) {
				TheSettings().evictions++;
			}
		}
	}
};

#ifdef QOD_NODE_TEST_HOOKS
std::atomic<bool> g_refuse_block_once {false};
std::atomic<bool> g_hold_lock_across_resolve {false};
std::mutex g_registry_lock;
std::vector<duckdb::weak_ptr<Cache>> g_registry;

std::vector<duckdb::shared_ptr<Cache>> Registered() {
	std::lock_guard<std::mutex> guard(g_registry_lock);
	std::vector<duckdb::shared_ptr<Cache>> out;
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

duckdb::shared_ptr<Cache> CacheFor(ClientContext &context) {
	auto cache = ObjectCache::GetObjectCache(context).GetOrCreate<Cache>(kCacheName);
	if (!cache) {
		throw InvalidInputException("QoD: search_snapshot_unavailable: the node's liveness cache is not available");
	}
#ifdef QOD_NODE_TEST_HOOKS
	std::lock_guard<std::mutex> guard(g_registry_lock);
	bool known = false;
	for (auto &weak : g_registry) {
		known = known || weak.lock().get() == cache.get();
	}
	if (!known) {
		g_registry.push_back(duckdb::weak_ptr<Cache>(cache));
	}
#endif
	return cache;
}

//! The 3c rules, short of S: the cache is on, auto-commit, no AT clause, a DuckLake target whose database
//! the transaction has not modified, and a catalog that is not attached read-only (review I1: a DuckLake
//! catalog attached with SNAPSHOT_VERSION or SNAPSHOT_TIME is pinned to an old S with no AT clause on the
//! scan, and DuckLake allows those only read-only; 2.0's headers expose the attach mode, not the pin, so
//! every read-only attach is bypassed, the over-bypass the review accepts).
bool Eligible(ClientContext &context, LogicalGet &target) {
	auto &settings = TheSettings();
	if (settings.capacity.load() == 0 || settings.byte_bound.load() == 0 ||
	    SearchCachesBound(BufferManager::GetBufferManager(DatabaseInstance::GetDatabase(context))) == 0) {
		return false;
	}
	if (!context.transaction.IsAutoCommit() || target.at_clause) {
		return false;
	}
	auto table = target.GetTable();
	if (!table) {
		return false;
	}
	auto &catalog = table->ParentCatalog();
	if (catalog.GetCatalogType() != "ducklake" || catalog.GetAttached().IsReadOnly()) {
		return false;
	}
	auto modified = MetaTransaction::Get(context).ModifiedDatabase();
	return !(modified && modified.get() == &catalog.GetAttached());
}

//! Moves every file's liveness words of `view` into one block of the buffer manager (Task 12, ruling 8: the cache
//! counts against memory_limit). False, `view` unchanged, when memory_limit refuses the block.
bool MoveToBlock(ClientContext &context, SnapshotView &view) {
	size_t total = 0;
	for (auto &live : view.liveness) {
		total += live.words.size();
	}
	if (total == 0) {
		return true;
	}
	auto block = std::make_shared<LivenessBlock>();
	// The instance's buffer manager: the block outlives the connection (the client's goes with it).
	auto &db = DatabaseInstance::GetDatabase(context);
	auto &buffers = BufferManager::GetBufferManager(db);
	auto size = total * sizeof(uint64_t);
	try {
#ifdef QOD_NODE_TEST_HOOKS
		if (g_refuse_block_once.exchange(false)) {
			throw OutOfMemoryException("could not allocate block of size " + std::to_string(size) + " (test hook)");
		}
#endif
		block->handle = buffers.Allocate(MemoryTag::EXTENSION, size, false);
	} catch (OutOfMemoryException &) {
		// The search caches' unheld entries give their memory back first, then the block is asked once more (review
		// M5, as the segment cache's blocks are).
		if (ReleaseSearchCaches(db, size) == 0) {
			return false;
		}
		try {
			block->handle = buffers.Allocate(MemoryTag::EXTENSION, size, false);
		} catch (OutOfMemoryException &) {
			return false;
		}
	}
	block->bytes = total * sizeof(uint64_t);
	auto words = reinterpret_cast<uint64_t *>(block->handle.GetDataMutable());
	for (auto &live : view.liveness) {
		std::copy(live.words.begin(), live.words.end(), words);
		live.block_words = words;
		live.block_count = live.words.size();
		words += live.words.size();
		std::vector<uint64_t>().swap(live.words);
	}
	view.liveness_block = std::move(block);
	return true;
}

} // namespace

uint64_t LivenessViewBytes(const SnapshotView &view) {
	uint64_t bytes = 0;
	for (auto &live : view.liveness) {
		bytes += (live.block_words ? live.block_count : live.words.capacity()) * sizeof(uint64_t);
	}
	for (auto &file : view.files) {
		bytes += file.path.size() + file.bundle_key.size();
	}
	for (auto &file : view.delete_files) {
		bytes += file.path.size() + file.data_file_path.size();
	}
	return bytes;
}

std::shared_ptr<const SnapshotView> CachedSnapshotView(ClientContext &context, LogicalGet &target, bool &hit) {
	hit = false;
	auto &settings = TheSettings();
	if (!Eligible(context, target)) {
		settings.bypassed++;
		return std::make_shared<const SnapshotView>(ResolveSnapshotView(context, target));
	}
	auto cache_ptr = CacheFor(context);
	auto &cache = *cache_ptr;
	auto table = target.GetTable();
	auto snapshot = CurrentSnapshotId(context, target);
	TableKey key {table->ParentCatalog().GetOid(), table->oid};
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.index.find(key);
		if (it != cache.index.end() && it->second->snapshot == snapshot) {
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			settings.hits++;
			hit = true;
			return it->second->view;
		}
	}
	// No lock is held across the resolve: DuckLake expands the target's file list (MultiFileList::GetAllFiles) by
	// binding statements on its own context, re-entrantly on this thread, and every post_bind runs on them (7.1 step
	// 1, Task 13's G3).
#ifdef QOD_NODE_TEST_HOOKS
	std::unique_lock<std::mutex> held(cache.lock, std::defer_lock);
	if (g_hold_lock_across_resolve.load()) {
		held.lock();
	}
	auto resolved = ResolveSnapshotView(context, target);
	if (held.owns_lock()) {
		held.unlock();
	}
#else
	auto resolved = ResolveSnapshotView(context, target);
#endif
	// The search reads no key; a view the cache may keep beyond the statement holds none (review M7).
	for (auto &file : resolved.files) {
		file.encryption_key.clear();
	}
	for (auto &file : resolved.delete_files) {
		file.encryption_key.clear();
	}
	if (resolved.snapshot_id != snapshot || resolved.table_oid != table->oid ||
	    !resolved.transaction_local_names.empty()) {
		// Not what the key says (it cannot happen in one transaction), or a view holding a transaction's own rows
		// (Eligible already excludes a modified lake; fix round 1, M4): served, never stored.
		settings.bypassed++;
		return std::make_shared<const SnapshotView>(std::move(resolved));
	}
	auto bytes = LivenessViewBytes(resolved);
	settings.misses++;
	if (bytes > settings.byte_bound.load()) {
		settings.too_large++;
		return std::make_shared<const SnapshotView>(std::move(resolved));
	}
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.index.find(key);
		if (it != cache.index.end() && it->second->snapshot > snapshot) {
			// An older S never replaces a newer one (a statement whose transaction began before a commit).
			return std::make_shared<const SnapshotView>(std::move(resolved));
		}
	}
	// The search caches' joint share (review I1), before any block is taken: this cache holds what the open-segment
	// cache leaves of it, which never takes this cache's slice (LivenessSlice). A view past it is served, unstored,
	// with no block taken and no segment evicted. The words are shrunk to fit, so the block's bytes are these.
	auto &db = DatabaseInstance::GetDatabase(context);
	auto total = SearchCachesBound(BufferManager::GetBufferManager(db));
	auto segments = SegmentCacheBytes(db);
	auto share = total > segments ? total - segments : 0;
	if (bytes > share) {
		settings.over_share++;
		return std::make_shared<const SnapshotView>(std::move(resolved));
	}
	if (!MoveToBlock(context, resolved)) {
		// memory_limit refuses the block: served from the statement's own memory, never stored.
		settings.block_refused++;
		return std::make_shared<const SnapshotView>(std::move(resolved));
	}
	bytes = LivenessViewBytes(resolved);
	auto view = std::make_shared<const SnapshotView>(std::move(resolved));
	std::vector<std::shared_ptr<const SnapshotView>> gone;
	std::lock_guard<std::mutex> guard(cache.lock);
	auto it = cache.index.find(key);
	if (it != cache.index.end()) {
		if (it->second->snapshot > snapshot) {
			return view;
		}
		if (it->second->snapshot == snapshot) {
			// Another statement stored the same S meanwhile: the same view, keep one.
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			return it->second->view;
		}
		cache.Erase(it->second, gone);
		settings.replaced++;
	}
	cache.lru.push_front(Entry {key, snapshot, view, bytes});
	cache.index[key] = cache.lru.begin();
	cache.bytes += bytes;
	cache.Fit(settings.capacity.load(), std::min<uint64_t>(settings.byte_bound.load(), share), gone, true);
	return view;
}

uint64_t LivenessSlice(BufferManager &buffers) {
	return std::min<uint64_t>(TheSettings().byte_bound.load(), SearchCachesBound(buffers) / 8);
}

void ReadLivenessSettingsFromEnvironment() {
	auto &settings = TheSettings();
	settings.capacity = ReadKnob(kLivenessCacheEntriesKnob);
	settings.byte_bound = ReadKnob(kLivenessCacheBytesKnob);
}

uint64_t LivenessCacheBytes(DatabaseInstance &db) {
	auto cache = db.GetObjectCache().Get<Cache>(kCacheName);
	if (!cache) {
		return 0;
	}
	std::lock_guard<std::mutex> guard(cache->lock);
	return cache->bytes;
}

uint64_t EvictUnheldLiveness(DatabaseInstance &db, uint64_t bytes) {
	auto cache = db.GetObjectCache().Get<Cache>(kCacheName);
	if (!cache) {
		return 0;
	}
	std::vector<std::shared_ptr<const SnapshotView>> gone;
	uint64_t freed = 0;
	std::lock_guard<std::mutex> guard(cache->lock);
	auto it = cache->lru.end();
	while (freed < bytes && it != cache->lru.begin()) {
		--it;
		if (it->view.use_count() > 1) {
			continue;
		}
		auto block = it->view->liveness_block ? std::static_pointer_cast<LivenessBlock>(it->view->liveness_block) : nullptr;
		freed += block ? block->bytes : 0;
		auto victim = it;
		++it;
		cache->Erase(victim, gone);
		TheSettings().evictions++;
	}
	return freed;
}

std::vector<uint64_t> LiveDocs(const FileLiveness &live, uint32_t num_docs) {
	if (live.end > static_cast<int64_t>(num_docs)) {
		throw InvalidInputException("QoD: search_snapshot_unavailable: liveness past the bundle's rows");
	}
	auto words = (static_cast<size_t>(num_docs) + 63) / 64;
	std::vector<uint64_t> out(words, 0);
	std::copy_n(live.Words(), std::min(live.WordCount(), words), out.begin());
	if (num_docs % 64 != 0 && !out.empty()) {
		// Bits at or past num_docs stay clear (they are already, as live.end <= num_docs).
		out.back() &= (uint64_t(1) << (num_docs % 64)) - 1;
	}
	return out;
}

#ifdef QOD_NODE_TEST_HOOKS
LivenessCacheCounters LivenessCacheCountersForTest() {
	auto &s = TheSettings();
	LivenessCacheCounters c;
	c.hits = s.hits.load();
	c.misses = s.misses.load();
	c.bypassed = s.bypassed.load();
	c.replaced = s.replaced.load();
	c.evictions = s.evictions.load();
	c.too_large = s.too_large.load();
	c.block_refused = s.block_refused.load();
	c.over_share = s.over_share.load();
	c.capacity = s.capacity.load();
	c.byte_bound = s.byte_bound.load();
	for (auto &cache : Registered()) {
		std::lock_guard<std::mutex> guard(cache->lock);
		c.entries += cache->lru.size();
		c.bytes += cache->bytes;
		for (auto &entry : cache->lru) {
			if (entry.view->liveness_block) {
				c.block_bytes += std::static_pointer_cast<LivenessBlock>(entry.view->liveness_block)->bytes;
			}
		}
	}
	return c;
}

void RefuseLivenessBlockOnceForTest() {
	g_refuse_block_once = true;
}

void HoldLivenessLockAcrossResolveForTest(bool on) {
	g_hold_lock_across_resolve = on;
}

uint64_t TakeLivenessLocksForTest(DatabaseInstance &db) {
	uint64_t taken = 0;
	{
		std::lock_guard<std::mutex> guard(g_registry_lock);
		taken++;
	}
	if (auto cache = db.GetObjectCache().Get<Cache>(kCacheName)) {
		std::lock_guard<std::mutex> guard(cache->lock);
		taken++;
	}
	return taken;
}

void ClearLivenessCacheForTest() {
	for (auto &cache : Registered()) {
		std::list<Entry> gone;
		std::lock_guard<std::mutex> guard(cache->lock);
		gone.swap(cache->lru);
		cache->index.clear();
		cache->bytes = 0;
	}
}

void SetLivenessCacheBoundsForTest(uint64_t entries, uint64_t bytes) {
	auto &s = TheSettings();
	s.capacity = entries;
	s.byte_bound = bytes;
	for (auto &cache : Registered()) {
		std::vector<std::shared_ptr<const SnapshotView>> gone;
		std::lock_guard<std::mutex> guard(cache->lock);
		cache->Fit(entries, bytes, gone, false);
	}
}

std::pair<uint64_t, uint64_t> LivenessBoundsForTest() {
	auto &s = TheSettings();
	return {s.capacity.load(), s.byte_bound.load()};
}
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
