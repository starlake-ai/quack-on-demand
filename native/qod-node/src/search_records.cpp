#include "search_records.hpp"

#include "crypto.hpp"
#include "decide_client.hpp"
#include "describer.hpp"
#include "json.hpp"
#include "json_writer.hpp"
#include "node_hooks.hpp"
#include "wire_text.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/catalog/catalog.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/storage/object_cache.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <iostream>
#include <iterator>
#include <mutex>
#include <set>
#include <stdexcept>

namespace qod::search {

using namespace duckdb;

namespace {

std::mutex g_lock;
IndexRecordSource g_source;

[[noreturn]] void Unavailable() {
	throw PermissionException(kRecordUnavailable);
}

bool AllZero(const std::array<uint8_t, 32> &key) {
	uint8_t any = 0;
	for (auto b : key) {
		any |= b;
	}
	return any == 0;
}

} // namespace

bool IsCanonicalUuid(const std::string &text) {
	if (text.size() != 36) {
		return false;
	}
	for (size_t i = 0; i < text.size(); i++) {
		auto c = text[i];
		if (i == 8 || i == 13 || i == 18 || i == 23) {
			if (c != '-') {
				return false;
			}
		} else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
			return false;
		}
	}
	return true;
}

void SetIndexRecordSource(IndexRecordSource source) {
	std::lock_guard<std::mutex> guard(g_lock);
	g_source = std::move(source);
}

std::optional<IndexRecord> IndexRecordFor(ClientContext &context, const std::string &catalog, int64_t table_id) {
	IndexRecordSource source;
	{
		std::lock_guard<std::mutex> guard(g_lock);
		source = g_source;
	}
	if (!source || table_id < 0) {
		return std::nullopt;
	}
	auto record = source(context, catalog, table_id);
	if (!record) {
		return std::nullopt;
	}
	// A record is checked before anything reads it: a malformed one is the source failing, never a
	// record the search trusts (the MAC key above all: never a zero-filled default, Task 3's review).
	if (record->table_id != table_id || !WellFormedRecord(*record)) {
		Unavailable();
	}
	return record;
}

bool WellFormedRecord(const IndexRecord &record) {
	if (!IsCanonicalUuid(record.index_id) || (record.state != "ready" && record.state != "disabled") ||
	    record.bundle_root.empty() || record.fields.empty() || record.fields.size() > 32 || AllZero(record.mac_key)) {
		return false;
	}
	std::set<int64_t> ids;
	for (auto &field : record.fields) {
		if (field.field_id < 0 || !ids.insert(field.field_id).second || field.column.empty()) {
			return false;
		}
	}
	return true;
}

// ---- the signed per-node record fetch --------------------------------------------------------------

namespace {

using JsonKind = qod::JsonValue::Kind;

std::string Folded(const std::string &name) {
	std::string out = name;
	std::transform(out.begin(), out.end(), out.begin(),
	               [](unsigned char c) { return static_cast<char>(c >= 'A' && c <= 'Z' ? c + 32 : c); });
	return out;
}

//! Throws when `value` is not an object holding exactly the members `names`.
void ExactMembers(const qod::JsonValue &value, const std::set<std::string> &names, const char *what) {
	if (value.kind != JsonKind::OBJECT) {
		throw std::invalid_argument(std::string("malformed answer: ") + what + " is not an object");
	}
	for (auto &member : value.object) {
		if (!names.count(member.first)) {
			throw std::invalid_argument(std::string("malformed answer: ") + what + " holds a member it does not have");
		}
	}
	for (auto &name : names) {
		if (!value.object.count(name)) {
			throw std::invalid_argument(std::string("malformed answer: ") + what + " lacks a member");
		}
	}
}

const std::set<std::string> kAnswerMembers {"expiresAt", "issuedAt", "missing", "node", "nonce", "records", "v"};
// No k1 or b (D10): a record holding any other member is refused.
const std::set<std::string> kRecordMembers {"bundleRoot", "catalog", "fields",  "indexId",        "macKey",
                                            "state",      "table",   "tableId", "segmentsVersion"};
const std::set<std::string> kFieldMembers {"column", "fieldId"};
const std::set<std::string> kMissingMembers {"catalog", "tableId"};

IndexRecord ReadRecord(const qod::JsonValue &entry) {
	ExactMembers(entry, kRecordMembers, "a record");
	IndexRecord record;
	record.table_id = entry.Get("tableId", JsonKind::INTEGER).integer;
	record.table = entry.Get("table", JsonKind::STRING).string;
	record.index_id = entry.Get("indexId", JsonKind::STRING).string;
	record.bundle_root = entry.Get("bundleRoot", JsonKind::STRING).string;
	record.state = entry.Get("state", JsonKind::STRING).string;
	auto version = entry.Get("segmentsVersion", JsonKind::INTEGER).integer;
	if (version < 0) {
		throw std::invalid_argument("malformed answer: a negative segments version");
	}
	record.segments_version = static_cast<uint64_t>(version);
	for (auto &field : entry.Get("fields", JsonKind::ARRAY).array) {
		ExactMembers(field, kFieldMembers, "a field");
		record.fields.push_back(
		    IndexField {field.Get("column", JsonKind::STRING).string, field.Get("fieldId", JsonKind::INTEGER).integer});
	}
	auto &mac_hex = entry.Get("macKey", JsonKind::STRING).string;
	if (mac_hex.size() != 64 || !qod::IsLowerHex(mac_hex)) {
		throw std::invalid_argument("malformed answer: a MAC key is not 64 lowercase hex digits");
	}
	auto mac = qod::FromHex(mac_hex);
	if (mac.size() != record.mac_key.size()) {
		throw std::invalid_argument("malformed answer: a MAC key is not 32 bytes");
	}
	std::copy(mac.begin(), mac.end(), record.mac_key.begin());
	if (!WellFormedRecord(record)) {
		throw std::invalid_argument("malformed answer: a malformed record");
	}
	return record;
}

std::shared_ptr<RecordClient> &InstalledClient() {
	static std::shared_ptr<RecordClient> client;
	return client;
}
std::mutex g_client_lock;

std::shared_ptr<RecordClient> CurrentClient() {
	std::lock_guard<std::mutex> guard(g_client_lock);
	return InstalledClient();
}

} // namespace

RecordAnswer VerifyIndexRecords(const std::string &text, const std::string &node_key, const std::string &node_id,
                                const std::string &nonce, const std::vector<RecordTable> &tables) {
	auto dot = text.find('.');
	if (dot == std::string::npos || text.find('.', dot + 1) != std::string::npos) {
		throw std::invalid_argument("malformed answer");
	}
	auto mac_hex = text.substr(dot + 1);
	if (mac_hex.size() != 64 || !IsLowerHex(mac_hex)) {
		throw std::invalid_argument("malformed answer MAC");
	}
	std::string bytes;
	try {
		bytes = FromBase64Url(text.substr(0, dot));
	} catch (std::invalid_argument &) {
		throw std::invalid_argument("malformed answer");
	}
	auto expected = HmacSha256(node_key, bytes);
	if (!ConstantTimeEquals(FromHex(mac_hex), std::string(reinterpret_cast<const char *>(expected.data()), 32))) {
		throw std::invalid_argument("answer MAC does not verify");
	}
	JsonValue json;
	try {
		json = ParseJson(bytes);
	} catch (std::invalid_argument &) {
		// Never the parser's message: it may quote the text, which holds MAC keys.
		throw std::invalid_argument("malformed answer");
	}
	RecordAnswer answer;
	std::map<std::pair<std::string, int64_t>, size_t> asked;
	for (size_t i = 0; i < tables.size(); i++) {
		asked[{tables[i].first, tables[i].second}] = i;
	}
	answer.records.resize(tables.size());
	std::vector<bool> answered(tables.size(), false);
	auto place = [&](const std::string &catalog, int64_t table_id, std::optional<IndexRecord> record) {
		auto it = asked.find({catalog, table_id});
		if (it == asked.end()) {
			throw std::invalid_argument("answer names a table not asked for");
		}
		if (answered[it->second]) {
			throw std::invalid_argument("answer names a table twice");
		}
		answered[it->second] = true;
		answer.records[it->second] = std::move(record);
	};
	try {
		ExactMembers(json, kAnswerMembers, "the answer");
		if (json.Get("v", JsonKind::INTEGER).integer != 1) {
			throw std::invalid_argument("unsupported answer version");
		}
		if (json.Get("node", JsonKind::STRING).string != node_id) {
			throw std::invalid_argument("answer is for another node");
		}
		if (json.Get("nonce", JsonKind::STRING).string != nonce) {
			throw std::invalid_argument("answer is for another handle");
		}
		// The lifetime only: both times are the replica's, and the node keeps the answer for it from receipt.
		auto issued_at = json.Get("issuedAt", JsonKind::INTEGER).integer;
		auto expires_at = json.Get("expiresAt", JsonKind::INTEGER).integer;
		if (expires_at <= issued_at) {
			throw std::invalid_argument("answer lifetime is not positive");
		}
		if (expires_at - issued_at > kMaxRecordAnswerMs) {
			throw std::invalid_argument("answer valid too long");
		}
		answer.lifetime_ms = expires_at - issued_at;
		for (auto &entry : json.Get("records", JsonKind::ARRAY).array) {
			auto record = ReadRecord(entry);
			place(entry.Get("catalog", JsonKind::STRING).string, record.table_id, std::move(record));
		}
		for (auto &entry : json.Get("missing", JsonKind::ARRAY).array) {
			ExactMembers(entry, kMissingMembers, "a missing table");
			place(entry.Get("catalog", JsonKind::STRING).string, entry.Get("tableId", JsonKind::INTEGER).integer,
			      std::nullopt);
		}
	} catch (std::invalid_argument &error) {
		std::string what = error.what();
		throw std::invalid_argument(what.rfind("malformed answer", 0) == 0 || what.rfind("answer", 0) == 0 ||
		                                    what.rfind("unsupported", 0) == 0
		                                ? what
		                                : "malformed answer: " + what);
	}
	for (bool done : answered) {
		if (!done) {
			throw std::invalid_argument("answer leaves a table unanswered");
		}
	}
	return answer;
}

bool SameDataPath(const std::string &a, const std::string &b) {
	auto trimmed = [](std::string path) {
		while (!path.empty() && path.back() == '/') {
			path.pop_back();
		}
		return path;
	};
	auto left = trimmed(a);
	return !left.empty() && left == trimmed(b);
}

RecordClient::RecordClient(std::string node_id, std::string node_key, int64_t timeout_ms,
                           std::function<int64_t()> clock)
    : node_id_(std::move(node_id)), node_key_(std::move(node_key)), timeout_ms_(timeout_ms), clock_(std::move(clock)) {
}

std::vector<std::optional<IndexRecord>> RecordClient::Lookup(const std::string &replica, const std::string &handle,
                                                             const std::string &nonce,
                                                             const std::vector<RecordTable> &tables) {
	if (tables.empty() || tables.size() > kMaxRecordTables) {
		Unavailable();
	}
	std::vector<std::optional<IndexRecord>> out(tables.size());
	// The positions still to fill, by key: a table named twice is asked once and filled at both.
	std::map<std::pair<std::string, int64_t>, std::vector<size_t>> ask;
	std::vector<RecordTable> asked;
	auto now = clock_();
	{
		std::lock_guard<std::mutex> guard(lock_);
		for (auto it = kept_.begin(); it != kept_.end();) {
			it = it->second.until <= now ? kept_.erase(it) : std::next(it);
		}
		for (size_t i = 0; i < tables.size(); i++) {
			auto key = std::make_pair(Folded(tables[i].first), tables[i].second);
			auto found = kept_.find(key);
			if (found != kept_.end()) {
				out[i] = found->second.record;
				continue;
			}
			auto &positions = ask[key];
			if (positions.empty()) {
				asked.push_back(tables[i]);
			}
			positions.push_back(i);
		}
	}
	if (asked.empty()) {
		return out;
	}
	std::vector<JsonValue> items;
	for (auto &table : asked) {
		auto name = table.first + " table " + std::to_string(table.second);
		items.push_back(JsonObject(
		    {{"catalog", JsonString(table.first)}, {"name", JsonString(name)}, {"tableId", JsonInt(table.second)}}));
	}
	HttpUrl url;
	try {
		url = ParseHttpUrl(replica);
	} catch (std::invalid_argument &error) {
		std::cerr << "[qod-node] search records: the handle's replica cannot be called (" << error.what() << ")"
		          << std::endl;
		Unavailable();
	}
	auto body = CanonicalJson(JsonObject({{"handle", JsonString(handle)}, {"tables", JsonArray(std::move(items))}}));
	HttpReply reply;
	try {
		reply = SignedPost(url, url.path + kRecordsPath, node_id_, node_key_, body, timeout_ms_);
	} catch (std::exception &error) {
		std::cerr << "[qod-node] search records call to " << url.authority << " failed: " << error.what() << std::endl;
		Unavailable();
	}
	if (reply.status != 200) {
		std::cerr << "[qod-node] search records call to " << url.authority << " answered HTTP " << reply.status
		          << std::endl;
		Unavailable();
	}
	RecordAnswer answer;
	try {
		answer = VerifyIndexRecords(TrimBlanks(reply.body), node_key_, node_id_, nonce, asked);
	} catch (std::invalid_argument &error) {
		// The check that failed only: never the answer, which holds MAC keys.
		std::cerr << "[qod-node] search records from " << url.authority << " refused: " << error.what() << std::endl;
		Unavailable();
	}
	// Kept for the answer's own lifetime from receipt, at most kRecordKeepMs, on this node's clock.
	auto until = clock_() + std::min(answer.lifetime_ms, kRecordKeepMs);
	std::lock_guard<std::mutex> guard(lock_);
	for (size_t j = 0; j < asked.size(); j++) {
		auto key = std::make_pair(Folded(asked[j].first), asked[j].second);
		// Every position of this table from the answer itself, never re-read from what is kept, which the
		// eviction below may already have dropped (review minor 3).
		for (auto i : ask[key]) {
			out[i] = answer.records[j];
		}
		if (kept_.size() >= kMaxCachedRecords && kept_.find(key) == kept_.end()) {
			// The answer whose time is up first goes: a scan of at most kMaxCachedRecords entries.
			auto first = kept_.begin();
			for (auto it = kept_.begin(); it != kept_.end(); ++it) {
				if (it->second.until < first->second.until) {
					first = it;
				}
			}
			kept_.erase(first);
		}
		kept_[key] = Kept {answer.records[j], until};
	}
	return out;
}

void RecordClient::Forget(const std::string &catalog, int64_t table_id) {
	std::lock_guard<std::mutex> guard(lock_);
	kept_.erase({Folded(catalog), table_id});
}

size_t RecordClient::CachedEntries() {
	std::lock_guard<std::mutex> guard(lock_);
	return kept_.size();
}

namespace {

std::atomic<int64_t> g_data_path_clock_offset {0};

int64_t DataPathNowMs() {
	auto now = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch());
	return static_cast<int64_t>(now.count()) + g_data_path_clock_offset.load();
}

//! The instance's kept data paths: (catalog folded, catalog oid) to (path, until).
class DataPathCache : public ObjectCacheEntry {
public:
	static string ObjectType() {
		return "qod_search_data_paths";
	}
	string GetObjectType() override {
		return ObjectType();
	}
	optional_idx GetEstimatedCacheMemory() const override {
		return optional_idx();
	}
	std::mutex lock;
	std::map<std::pair<std::string, idx_t>, std::pair<std::string, int64_t>> kept;
};

//! The most catalogs one instance keeps a path for; past it the expired ones go, then all.
constexpr size_t kMaxDataPaths = 1024;

} // namespace

DuckLakeIdRead CatalogDataPath(ClientContext &context, const std::string &catalog, std::string &data_path) {
	data_path.clear();
	auto entry = Catalog::GetCatalogEntry(context, Identifier(catalog));
	if (!entry || entry->GetCatalogType() != "ducklake") {
		return DuckLakeIdRead::None;
	}
	auto cache = ObjectCache::GetObjectCache(context).GetOrCreate<DataPathCache>(DataPathCache::ObjectType());
	std::pair<std::string, idx_t> key {Folded(catalog), entry->GetOid()};
	auto now = DataPathNowMs();
	if (cache) {
		std::lock_guard<std::mutex> guard(cache->lock);
		auto it = cache->kept.find(key);
		if (it != cache->kept.end() && now < it->second.second && now >= it->second.second - kRecordKeepMs) {
			data_path = it->second.first;
			return DuckLakeIdRead::Found;
		}
	}
	auto read = DuckLakeDataPath(context, catalog, data_path);
	if (read == DuckLakeIdRead::Found && cache) {
		std::lock_guard<std::mutex> guard(cache->lock);
		if (cache->kept.size() >= kMaxDataPaths) {
			for (auto it = cache->kept.begin(); it != cache->kept.end();) {
				it = now >= it->second.second ? cache->kept.erase(it) : std::next(it);
			}
			if (cache->kept.size() >= kMaxDataPaths) {
				cache->kept.clear();
			}
		}
		cache->kept[key] = {data_path, now + kRecordKeepMs};
	}
	return read;
}

#ifdef QOD_NODE_TEST_HOOKS
void AdvanceDataPathClockForTest(int64_t ms) {
	g_data_path_clock_offset += ms;
}

uint64_t TakeRecordLocksForTest(DatabaseInstance &db) {
	uint64_t taken = 0;
	{
		std::lock_guard<std::mutex> guard(g_lock);
		taken++;
	}
	{
		std::lock_guard<std::mutex> guard(g_client_lock);
		taken++;
	}
	if (auto client = CurrentClient()) {
		client->CachedEntries();
		taken++;
	}
	if (auto cache = db.GetObjectCache().Get<DataPathCache>(DataPathCache::ObjectType())) {
		std::lock_guard<std::mutex> guard(cache->lock);
		taken++;
	}
	return taken;
}
#endif

std::vector<std::optional<IndexRecord>> FetchIndexRecords(ClientContext &context,
                                                          const std::vector<RecordTable> &tables) {
	std::vector<std::optional<IndexRecord>> found(tables.size());
	auto client = CurrentClient();
	if (!client || tables.empty() ||
	    context.registered_state->Get<ClientContextState>(kQuackSessionKey) == nullptr) {
		return found;
	}
	Installed installed;
	if (!SessionSnapshot(context, SystemNowMs(), installed) || installed.handle.empty() || installed.nonce.empty()) {
		Unavailable();
	}
	for (size_t at = 0; at < tables.size(); at += kMaxRecordTables) {
		auto end = std::min(tables.size(), at + kMaxRecordTables);
		std::vector<RecordTable> part(tables.begin() + static_cast<std::ptrdiff_t>(at),
		                              tables.begin() + static_cast<std::ptrdiff_t>(end));
		auto got = client->Lookup(installed.replica, installed.handle, installed.nonce, part);
		for (size_t j = 0; j < got.size(); j++) {
			found[at + j] = std::move(got[j]);
		}
	}
	// Each record is used only for the catalog whose own DuckLake data path it names (security review M1, M2).
	for (size_t i = 0; i < tables.size(); i++) {
		if (!found[i]) {
			continue;
		}
		std::string data_path;
		auto read = CatalogDataPath(context, tables[i].first, data_path);
		if (read == DuckLakeIdRead::Unreadable) {
			Unavailable();
		}
		if (read != DuckLakeIdRead::Found || !SameDataPath(found[i]->bundle_root, data_path)) {
			// The paths are not printed: a data path names where a tenant's files lie.
			std::cerr << "[qod-node] search records: the record of table " << tables[i].second << " of catalog "
			          << tables[i].first << " names another data path than the catalog's; not used" << std::endl;
			client->Forget(tables[i].first, tables[i].second);
			found[i] = std::nullopt;
		}
	}
	return found;
}

void InstallRecordFetch(std::shared_ptr<RecordClient> client) {
	{
		std::lock_guard<std::mutex> guard(g_client_lock);
		InstalledClient() = std::move(client);
	}
	SetIndexRecordSource([](ClientContext &context, const std::string &catalog,
	                        int64_t table_id) -> std::optional<IndexRecord> {
		auto got = FetchIndexRecords(context, {{catalog, table_id}});
		return got.empty() ? std::nullopt : std::move(got[0]);
	});
}

} // namespace qod::search
