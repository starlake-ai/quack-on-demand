#pragma once

// Search tests: a stand-in index record source (search_records.hpp; Task 7 installs the real fetch) and the
// fixed test MAC key. A record is registered for a DuckLake table read through `setup` (its table id and the
// field ids of the indexed columns, from the catalog's metadata), and served by (catalog alias, table id), as
// the node asks.

#include "crypto.hpp"
#include "search_records.hpp"

#include "qod_search.h"

#include "duckdb.hpp"

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace qod_test {

//! A version 8 UUID of SHA-256("qod-search-test-index|" + name): one index id per registered name.
inline std::string TestIndexId(const std::string &name) {
	auto digest = qod::Sha256("qod-search-test-index|" + name);
	digest[6] = static_cast<uint8_t>((digest[6] & 0x0f) | 0x80);
	digest[8] = static_cast<uint8_t>((digest[8] & 0x3f) | 0x80);
	auto hex = qod::Hex(digest.data(), 16);
	return hex.substr(0, 8) + "-" + hex.substr(8, 4) + "-" + hex.substr(12, 4) + "-" + hex.substr(16, 4) + "-" +
	       hex.substr(20, 12);
}

//! The library tests' test_mac_key: SHA-256("qod-search-test-mac-key|" + index id). Never printed.
inline std::array<uint8_t, 32> TestMacKey(const std::string &index_id) {
	auto digest = qod::Sha256("qod-search-test-mac-key|" + index_id);
	std::array<uint8_t, 32> key {};
	for (size_t i = 0; i < key.size(); i++) {
		key[i] = digest[i];
	}
	return key;
}

inline std::string Lower(std::string text) {
	for (auto &c : text) {
		if (c >= 'A' && c <= 'Z') {
			c = static_cast<char>(c - 'A' + 'a');
		}
	}
	return text;
}

//! The registered records, served by (catalog alias, table id). Thread-safe: the node asks from any
//! connection's thread.
class TestIndexes {
public:
	//! Registers the index of `catalog`.`schema`.`table` on `columns` (in index order), its bundles under
	//! `root`, under the id TestIndexId(`name`, default "catalog.schema.table"). The table id and the field ids
	//! are read now from `__ducklake_metadata_<meta>` (meta: `catalog` unless given), the table live under its
	//! name, each column live. Replaces a record of the same (catalog, table id). False with `error` set when
	//! the metadata cannot be read.
	bool Add(duckdb::Connection &setup, const std::string &catalog, const std::string &table,
	         const std::vector<std::string> &columns, const std::string &root, std::string &error,
	         const std::string &name = "", const std::string &meta = "", const std::string &schema = "main") {
		auto prefix = "__ducklake_metadata_" + (meta.empty() ? catalog : meta);
		auto tid = setup.Query("SELECT t.table_id FROM " + prefix + ".ducklake_table t JOIN " + prefix +
		                       ".ducklake_schema s USING (schema_id) WHERE t.end_snapshot IS NULL AND s.end_snapshot IS "
		                       "NULL AND s.schema_name = '" +
		                       schema + "' AND t.table_name = '" + table + "'");
		if (tid->HasError()) {
			error = tid->GetError();
			return false;
		}
		auto tid_chunk = tid->Fetch();
		if (!tid_chunk || tid_chunk->size() != 1) {
			error = "no single live table " + table;
			return false;
		}
		qod::search::IndexRecord record;
		record.table_id = tid_chunk->GetValue(0, 0).GetValue<int64_t>();
		record.table = Lower(catalog + "." + schema + "." + table);
		record.index_id = TestIndexId(name.empty() ? record.table : name);
		record.bundle_root = root;
		record.state = "ready";
		record.mac_key = TestMacKey(record.index_id);
		for (auto &column : columns) {
			auto fid = setup.Query("SELECT column_id FROM " + prefix + ".ducklake_column WHERE table_id = " +
			                       std::to_string(record.table_id) +
			                       " AND parent_column IS NULL AND end_snapshot IS NULL AND column_name = '" + column + "'");
			if (fid->HasError()) {
				error = fid->GetError();
				return false;
			}
			auto fid_chunk = fid->Fetch();
			if (!fid_chunk || fid_chunk->size() != 1) {
				error = "no single live column " + column + " of " + table;
				return false;
			}
			record.fields.push_back(qod::search::IndexField {column, fid_chunk->GetValue(0, 0).GetValue<int64_t>()});
		}
		std::lock_guard<std::mutex> guard(lock_);
		records_[{Lower(catalog), record.table_id}] = record;
		return true;
	}

	//! The record registered for `catalog`'s table `table` (live under that name in `setup`'s catalog), or none.
	std::optional<qod::search::IndexRecord> Find(const std::string &catalog, const std::string &table) {
		std::lock_guard<std::mutex> guard(lock_);
		for (auto &entry : records_) {
			if (entry.first.first == Lower(catalog) &&
			    entry.second.table.substr(entry.second.table.rfind('.') + 1) == Lower(table)) {
				return entry.second;
			}
		}
		return std::nullopt;
	}

	//! Applies `change` to every record of `catalog` named `table` (as registered).
	void Change(const std::string &catalog, const std::string &table,
	            const std::function<void(qod::search::IndexRecord &)> &change) {
		std::lock_guard<std::mutex> guard(lock_);
		for (auto &entry : records_) {
			if (entry.first.first == Lower(catalog) &&
			    entry.second.table.substr(entry.second.table.rfind('.') + 1) == Lower(table)) {
				change(entry.second);
			}
		}
	}

	void Clear() {
		std::lock_guard<std::mutex> guard(lock_);
		records_.clear();
	}

	//! When set, every record served carries this bundle root instead of its own (a test moving the root).
	void OverrideRoot(std::function<std::string()> root) {
		std::lock_guard<std::mutex> guard(lock_);
		root_ = std::move(root);
	}

	//! When true, the source throws as an unreachable replica would.
	void Fail(bool fail) {
		std::lock_guard<std::mutex> guard(lock_);
		fail_ = fail;
	}

	//! How many times the node asked.
	uint64_t Asked() {
		std::lock_guard<std::mutex> guard(lock_);
		return asked_;
	}

	//! Installs this registry as the node's record source.
	void Install() {
		qod::search::SetIndexRecordSource(
		    [this](duckdb::ClientContext &, const std::string &catalog, int64_t table_id)
		        -> std::optional<qod::search::IndexRecord> {
			    std::lock_guard<std::mutex> guard(lock_);
			    asked_++;
			    if (fail_) {
				    throw duckdb::PermissionException(qod::search::kRecordUnavailable);
			    }
			    auto it = records_.find({Lower(catalog), table_id});
			    if (it == records_.end()) {
				    return std::nullopt;
			    }
			    auto record = it->second;
			    if (root_) {
				    auto root = root_();
				    if (!root.empty()) {
					    record.bundle_root = root;
				    }
			    }
			    return record;
		    });
	}

private:
	std::mutex lock_;
	std::map<std::pair<std::string, int64_t>, qod::search::IndexRecord> records_;
	std::function<std::string()> root_;
	bool fail_ = false;
	uint64_t asked_ = 0;
};

//! A QsSource for a fresh reference segment over `fields` (column, field id): a fixed index id, its test MAC
//! key, path `path`. The pointers point into the arguments, which must outlive it.
struct ReferenceSource {
	std::string index_id = "00000000-0000-8000-8000-000000000000";
	std::string path;
	std::string key;
	std::vector<std::string> columns;
	std::vector<QsField> fields;
	std::array<uint8_t, 32> mac {};
	QsSource source {};

	ReferenceSource(const std::string &path_p, const std::vector<std::string> &columns_p) : path(path_p), columns(columns_p) {
		key = qod::Hex(qod::Sha256(path));
		mac = TestMacKey(index_id);
		for (size_t i = 0; i < columns.size(); i++) {
			fields.push_back(QsField {columns[i].c_str(), static_cast<int64_t>(i + 1)});
		}
		source.index_id = index_id.c_str();
		source.path_key = key.c_str();
		source.path = path.c_str();
		source.file_size = 1;
		source.footer_size = 1;
		source.table_id = 0;
		source.fields = fields.data();
		source.n_fields = fields.size();
		source.record_count = -1;
		source.row_id_start = -1;
		source.has_internal_row_id = -1;
		source.has_internal_snapshot = -1;
		source.mac_key = mac.data();
		source.mac_key_len = mac.size();
	}
	ReferenceSource(const ReferenceSource &) = delete;
	ReferenceSource &operator=(const ReferenceSource &) = delete;
};

} // namespace qod_test
