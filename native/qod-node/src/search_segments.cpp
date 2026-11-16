#include "search_segments.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/common/random_engine.hpp"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <tuple>

namespace qod::search {

using namespace duckdb;

namespace {

using CacheKey = std::tuple<std::string, std::string, std::string, uint64_t, uint64_t>;

struct Cache {
	std::mutex lock;
	std::map<CacheKey, std::shared_ptr<const OpenSegment>> segments;
	//! Files already logged as unindexed, by cache key.
	std::set<CacheKey> logged;
	std::atomic<uint64_t> reads {0}, hits {0}, missing {0}, refused {0};
};

Cache &TheCache() {
	static Cache cache;
	return cache;
}

void LogOnce(Cache &cache, const CacheKey &key, const std::string &what) {
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
}

std::shared_ptr<OpenSegment> WrapSegment(QsSegment *segment, uint64_t bytes) {
	auto open = std::make_shared<OpenSegment>();
	open->segment = segment;
	open->num_docs = qs_segment_num_docs(segment);
	open->explicit_rowids = qs_segment_has_explicit_rowids(segment) == 1;
	open->has_snapshot_column = qs_segment_has_snapshot_column(segment) == 1;
	open->first_rowid = open->num_docs > 0 ? qs_segment_rowid(segment, 0) : -1;
	open->bytes = bytes;
	if (open->explicit_rowids) {
		open->rowid_docs.reserve(open->num_docs);
		for (uint32_t doc = 0; doc < open->num_docs; doc++) {
			open->rowid_docs.emplace_back(qs_segment_rowid(segment, doc), doc);
		}
		std::sort(open->rowid_docs.begin(), open->rowid_docs.end());
	}
	return open;
}

std::shared_ptr<const OpenSegment> LoadSegment(ClientContext &context, const std::string &bundle_path,
                                               const std::string &index_id, DataFileRef &file) {
	auto &cache = TheCache();
	CacheKey key {bundle_path, index_id, file.bundle_key, file.file_size, file.footer_size};
	std::shared_ptr<const OpenSegment> found;
	{
		std::lock_guard<std::mutex> guard(cache.lock);
		auto it = cache.segments.find(key);
		if (it != cache.segments.end()) {
			found = it->second;
		}
	}
	if (!found) {
		cache.reads++;
		auto &fs = FileSystem::GetFileSystem(context);
		std::vector<uint8_t> bytes;
		try {
			auto handle = fs.OpenFile(bundle_path, FileFlags::FILE_FLAGS_READ | FileFlags::FILE_FLAGS_NULL_IF_NOT_EXISTS);
			if (!handle) {
				cache.missing++;
				LogOnce(cache, key, "no bundle for " + file.path + " (unindexed)");
				return nullptr;
			}
			auto size = handle->GetFileSize();
			bytes.resize(size);
			idx_t at = 0;
			while (at < size) {
				auto got = handle->Read(bytes.data() + at, size - at);
				if (got <= 0) {
					break;
				}
				at += static_cast<idx_t>(got);
			}
			if (at != size) {
				cache.refused++;
				LogOnce(cache, key, "the bundle for " + file.path + " could not be read whole (unindexed)");
				return nullptr;
			}
		} catch (InterruptException &) {
			throw;
		} catch (std::exception &error) {
			cache.refused++;
			LogOnce(cache, key, "the bundle for " + file.path + " could not be read (unindexed): " + error.what());
			return nullptr;
		}
		QsSource source;
		source.index_id = index_id.c_str();
		source.path_key = file.bundle_key.c_str();
		source.path = file.path.c_str();
		source.file_size = file.file_size;
		source.footer_size = file.footer_size;
		source.record_count = -1;
		source.row_id_start = file.row_id_start >= 0 ? file.row_id_start : -1;
		source.has_internal_row_id = -1;
		source.has_internal_snapshot = -1;
		char *err = nullptr;
		auto segment = qs_segment_open(bytes.data(), bytes.size(), &source, &err);
		if (!segment) {
			cache.refused++;
			LogOnce(cache, key, "the bundle for " + file.path + " was refused (unindexed): " + Taken(err));
			return nullptr;
		}
		auto open = WrapSegment(segment, bytes.size());
		std::lock_guard<std::mutex> guard(cache.lock);
		auto inserted = cache.segments.emplace(key, std::move(open));
		found = inserted.first->second;
	} else {
		cache.hits++;
	}
	file.has_internal_row_id = found->explicit_rowids;
	file.has_internal_snapshot = found->has_snapshot_column;
	file.flags_known = true;
	return found;
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

SegmentCacheCounters SegmentCacheCountersForTest() {
	auto &cache = TheCache();
	return SegmentCacheCounters {cache.reads.load(), cache.hits.load(), cache.missing.load(), cache.refused.load()};
}

void ClearSegmentCacheForTest() {
	auto &cache = TheCache();
	std::lock_guard<std::mutex> guard(cache.lock);
	cache.segments.clear();
	cache.logged.clear();
}

} // namespace qod::search
