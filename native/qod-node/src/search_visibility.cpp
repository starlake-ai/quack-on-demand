#include "search_visibility.hpp"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/main/attached_database.hpp"
#include "duckdb/main/client_context.hpp"
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

//! (the instance's id, catalog oid, table oid): one entry per table, at its newest S seen (review I2).
using TableKey = std::tuple<uint64_t, idx_t, uint64_t>;

//! A process-unique id per DatabaseInstance, held in the instance's own ObjectCache, so a new instance at a
//! reused address never meets an old one's entries (review M1: attach oids restart at 20000 per instance).
std::atomic<uint64_t> g_next_instance_id {1};

class InstanceTag : public ObjectCacheEntry {
public:
	InstanceTag() : id(g_next_instance_id++) {
	}
	static string ObjectType() {
		return "qod_search_instance_tag";
	}
	string GetObjectType() override {
		return ObjectType();
	}
	optional_idx GetEstimatedCacheMemory() const override {
		return optional_idx(); // never evicted
	}
	const uint64_t id;
};

uint64_t InstanceId(ClientContext &context) {
	auto tag = ObjectCache::GetObjectCache(context).GetOrCreate<InstanceTag>("qod_search_instance_tag");
	if (!tag) {
		throw InvalidInputException("QoD: search_snapshot_unavailable: the node's instance tag is not available");
	}
	return tag->id;
}

uint64_t NumberFromEnvironment(const char *name, uint64_t fallback, uint64_t max) {
	auto text = std::getenv(name);
	if (!text || !*text) {
		return fallback;
	}
	char *end = nullptr;
	auto value = std::strtoull(text, &end, 10);
	if (!end || *end != '\0' || value > max) {
		std::cerr << "[qod-node] " << name << " is not a number from 0 to " << max << "; using " << fallback
		          << std::endl;
		return fallback;
	}
	return value;
}

constexpr uint64_t kDefaultEntries = 64;
constexpr uint64_t kDefaultBytes = uint64_t(256) << 20;

struct Entry {
	TableKey key;
	int64_t snapshot;
	std::shared_ptr<const SnapshotView> view;
	uint64_t bytes;
};

struct Cache {
	std::mutex lock;
	//! Most recently used first.
	std::list<Entry> lru;
	std::map<TableKey, std::list<Entry>::iterator> index;
	uint64_t bytes = 0;
	uint64_t capacity = NumberFromEnvironment("QOD_SEARCH_LIVENESS_CACHE_ENTRIES", kDefaultEntries, 100000);
	uint64_t byte_bound = NumberFromEnvironment("QOD_SEARCH_LIVENESS_CACHE_BYTES", kDefaultBytes, uint64_t(1) << 40);
	std::atomic<uint64_t> hits {0}, misses {0}, bypassed {0}, replaced {0}, evictions {0}, too_large {0};

	void Erase(std::list<Entry>::iterator it) {
		bytes -= it->bytes;
		index.erase(it->key);
		lru.erase(it);
	}
};

Cache &TheCache() {
	static Cache cache;
	return cache;
}

//! The 3c rules, short of S: the cache is on, auto-commit, no AT clause, a DuckLake target whose database
//! the transaction has not modified, and a catalog that is not attached read-only (review I1: a DuckLake
//! catalog attached with SNAPSHOT_VERSION or SNAPSHOT_TIME is pinned to an old S with no AT clause on the
//! scan, and DuckLake allows those only read-only; 2.0's headers expose the attach mode, not the pin, so
//! every read-only attach is bypassed, the over-bypass the review accepts).
bool Eligible(ClientContext &context, LogicalGet &target, Cache &cache) {
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		if (cache.capacity == 0 || cache.byte_bound == 0) {
			return false;
		}
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

} // namespace

uint64_t LivenessViewBytes(const SnapshotView &view) {
	uint64_t bytes = 0;
	for (auto &live : view.liveness) {
		bytes += live.words.capacity() * sizeof(uint64_t);
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
	auto &cache = TheCache();
	if (!Eligible(context, target, cache)) {
		cache.bypassed++;
		return std::make_shared<const SnapshotView>(ResolveSnapshotView(context, target));
	}
	auto table = target.GetTable();
	auto snapshot = CurrentSnapshotId(context, target);
	TableKey key {InstanceId(context), table->ParentCatalog().GetOid(), table->oid};
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.index.find(key);
		if (it != cache.index.end() && it->second->snapshot == snapshot) {
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			cache.hits++;
			hit = true;
			return it->second->view;
		}
	}
	auto resolved = ResolveSnapshotView(context, target);
	// The search reads no key; a view the cache may keep beyond the statement holds none (review M7).
	for (auto &file : resolved.files) {
		file.encryption_key.clear();
	}
	for (auto &file : resolved.delete_files) {
		file.encryption_key.clear();
	}
	auto view = std::make_shared<const SnapshotView>(std::move(resolved));
	if (view->snapshot_id != snapshot || view->table_oid != table->oid) {
		// Not what the key says (it cannot happen in one transaction): served, never stored.
		cache.bypassed++;
		return view;
	}
	auto bytes = LivenessViewBytes(*view);
	std::lock_guard<std::mutex> guard(cache.lock);
	cache.misses++;
	if (bytes > cache.byte_bound) {
		cache.too_large++;
		return view;
	}
	auto it = cache.index.find(key);
	if (it != cache.index.end()) {
		if (it->second->snapshot > snapshot) {
			// An older S never replaces a newer one (a statement whose transaction began before a commit).
			return view;
		}
		if (it->second->snapshot == snapshot) {
			// Another statement stored the same S meanwhile: the same view, keep one.
			cache.lru.splice(cache.lru.begin(), cache.lru, it->second);
			return it->second->view;
		}
		cache.Erase(it->second);
		cache.replaced++;
	}
	cache.lru.push_front(Entry {key, snapshot, view, bytes});
	cache.index[key] = cache.lru.begin();
	cache.bytes += bytes;
	while (cache.lru.size() > cache.capacity || cache.bytes > cache.byte_bound) {
		cache.Erase(std::prev(cache.lru.end()));
		cache.evictions++;
	}
	return view;
}

std::vector<uint64_t> LiveDocs(const FileLiveness &live, uint32_t num_docs) {
	if (live.end > static_cast<int64_t>(num_docs)) {
		throw InvalidInputException("QoD: search_snapshot_unavailable: liveness past the bundle's rows");
	}
	auto words = (static_cast<size_t>(num_docs) + 63) / 64;
	std::vector<uint64_t> out(words, 0);
	std::copy_n(live.words.begin(), std::min(live.words.size(), words), out.begin());
	if (num_docs % 64 != 0 && !out.empty()) {
		// Bits at or past num_docs stay clear (they are already, as live.end <= num_docs).
		out.back() &= (uint64_t(1) << (num_docs % 64)) - 1;
	}
	return out;
}

LivenessCacheCounters LivenessCacheCountersForTest() {
	auto &cache = TheCache();
	LivenessCacheCounters c;
	c.hits = cache.hits.load();
	c.misses = cache.misses.load();
	c.bypassed = cache.bypassed.load();
	c.replaced = cache.replaced.load();
	c.evictions = cache.evictions.load();
	c.too_large = cache.too_large.load();
	std::lock_guard<std::mutex> guard(cache.lock);
	c.entries = cache.lru.size();
	c.bytes = cache.bytes;
	c.capacity = cache.capacity;
	c.byte_bound = cache.byte_bound;
	return c;
}

void ClearLivenessCacheForTest() {
	auto &cache = TheCache();
	std::lock_guard<std::mutex> guard(cache.lock);
	cache.index.clear();
	cache.lru.clear();
	cache.bytes = 0;
}

void SetLivenessCacheBoundsForTest(uint64_t entries, uint64_t bytes) {
	auto &cache = TheCache();
	std::lock_guard<std::mutex> guard(cache.lock);
	cache.capacity = entries;
	cache.byte_bound = bytes;
	while (!cache.lru.empty() && (cache.lru.size() > cache.capacity || cache.bytes > cache.byte_bound)) {
		cache.Erase(std::prev(cache.lru.end()));
	}
}

} // namespace qod::search
