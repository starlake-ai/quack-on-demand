// Tests for the signed per-node index record fetch (search spec 4.2 rule 8, 13; Task 7): the checks of a
// replica's answer (VerifyIndexRecords) and RecordClient against a local HTTP replica answering canned
// signed bodies (the decide client tests' pattern): the request as the manager reads it, the cache by
// (catalog, table id) until the answer's expiresAt, `missing` cached the same way, and every answer the
// node must refuse with the retryable text, caching nothing. Built with -DQOD_NODE_SEARCH=ON
// -DQOD_NODE_TESTS=ON and run by CTest.

#include "crypto.hpp"
#include "describer.hpp"
#include "json.hpp"
#include "search_records.hpp"
#include "test_handles.hpp"
#include "test_http_server.hpp"

#include "duckdb.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/main/client_context.hpp"

#include <atomic>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

using qod::search::IndexRecord;
using qod::search::RecordClient;
using qod_test::kKey;
using qod_test::kNode;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

std::atomic<int64_t> now_ms {1761200000000};

const std::string kNonce = "0123456789abcdef0123456789abcdef";
const std::string kOtherNonce = "fedcba9876543210fedcba9876543210";
const std::string kIndexId = "0f0e0d0c-0b0a-4908-8706-050403020100";
const std::string kMacHex(64, 'a');

struct Replica {
	std::mutex lock;
	qod_test::HttpAnswer answer;
};
Replica replica;

void Answering(int status, const std::string &body) {
	std::lock_guard<std::mutex> guard(replica.lock);
	replica.answer = qod_test::HttpAnswer();
	replica.answer.status = status;
	replica.answer.body = body;
}

qod_test::HttpAnswer Answer(const qod_test::HttpRequest &) {
	std::lock_guard<std::mutex> guard(replica.lock);
	return replica.answer;
}

std::string Sign(const std::string &json, const std::string &key = kKey) {
	return qod::ToBase64Url(json) + "." + qod::Hex(qod::HmacSha256(key, json));
}

//! One record's JSON members, `extra` spliced in before the closing brace.
std::string RecordJson(const std::string &catalog, int64_t table_id, const std::string &extra = "",
                       const std::string &mac_hex = kMacHex) {
	return "{\"bundleRoot\":\"s3://lake/acme_lake/\",\"catalog\":\"" + catalog +
	       "\",\"fields\":[{\"column\":\"title\",\"fieldId\":2},{\"column\":\"body\",\"fieldId\":3}],\"indexId\":\"" +
	       kIndexId + "\",\"macKey\":\"" + mac_hex +
	       "\",\"segmentsVersion\":4,\"state\":\"ready\",\"table\":\"acme_lake.main.pages\",\"tableId\":" +
	       std::to_string(table_id) + extra + "}";
}

//! An answer issued at `issued_at` (the replica's clock; 0: the node's now) living `lifetime_ms`.
std::string AnswerJson(const std::string &records, const std::string &missing, const std::string &nonce = kNonce,
                       int64_t lifetime_ms = 10000, const std::string &node = kNode, int64_t issued_at = 0) {
	if (issued_at == 0) {
		issued_at = now_ms.load();
	}
	return "{\"expiresAt\":" + std::to_string(issued_at + lifetime_ms) + ",\"issuedAt\":" + std::to_string(issued_at) +
	       ",\"missing\":[" + missing + "],\"node\":\"" + node + "\",\"nonce\":\"" + nonce + "\",\"records\":[" +
	       records + "],\"v\":1}";
}

std::string MissingJson(const std::string &catalog, int64_t table_id) {
	return "{\"catalog\":\"" + catalog + "\",\"tableId\":" + std::to_string(table_id) + "}";
}

using Tables = std::vector<std::pair<std::string, int64_t>>;

//! The message of the PermissionException `lookup` throws, or "no exception".
template <class F>
std::string Thrown(F lookup) {
	try {
		lookup();
		return "no exception";
	} catch (duckdb::PermissionException &error) {
		return duckdb::ErrorData(error).RawMessage();
	}
}

void TestGoodAnswerCachedUntilExpiry(const std::string &url) {
	RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
	Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "")));
	auto first = client.Lookup(url, "the-handle", kNonce, Tables {{"acme_lake", 12}});
	Expect(first.size() == 1 && first[0].has_value(), "a good answer gives the record");
	if (first.size() == 1 && first[0]) {
		Expect(first[0]->index_id == kIndexId, "index id");
		Expect(first[0]->table_id == 12, "table id");
		Expect(first[0]->bundle_root == "s3://lake/acme_lake/", "bundle root");
		Expect(first[0]->fields.size() == 2 && first[0]->fields[1].field_id == 3, "fields");
		Expect(first[0]->state == "ready", "state");
		Expect(first[0]->segments_version == 4, "segments version");
		Expect(first[0]->mac_key[0] == 0xaa && first[0]->mac_key[31] == 0xaa, "MAC key decoded");
	}
}

void ExpectRefused(RecordClient &client, const std::string &url, const std::string &what) {
	auto message = Thrown([&] { client.Lookup(url, "the-handle", kNonce, Tables {{"acme_lake", 12}}); });
	Expect(message == qod::search::kRecordUnavailable, what + ": the retryable text, got: " + message);
	Expect(client.CachedEntries() == 0, what + ": nothing cached");
}

} // namespace

int main() {
	qod_test::TestHttpServer server(Answer);
	auto url = server.Url();

	// The request: the path, the node, a signature over the body, the handle and every table asked.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), MissingJson("acme_lake", 13))));
		auto got = client.Lookup(url, "the-handle", kNonce, Tables {{"acme_lake", 12}, {"acme_lake", 13}});
		auto last = server.Last();
		Expect(last.path == qod::search::kRecordsPath, "the records path, got " + last.path);
		Expect(last.headers["x-qod-node"] == kNode, "X-QoD-Node names the node");
		Expect(last.headers["x-qod-signature"] == qod::Hex(qod::HmacSha256(kKey, last.body)),
		       "X-QoD-Signature is the body's HMAC under the node key");
		auto body = qod::ParseJson(last.body);
		Expect(body.Get("handle", qod::JsonValue::Kind::STRING).string == "the-handle", "the handle sent");
		auto &tables = body.Get("tables", qod::JsonValue::Kind::ARRAY).array;
		Expect(tables.size() == 2, "both tables asked");
		if (tables.size() == 2) {
			Expect(tables[1].Get("catalog", qod::JsonValue::Kind::STRING).string == "acme_lake", "catalog");
			Expect(tables[1].Get("tableId", qod::JsonValue::Kind::INTEGER).integer == 13, "table id");
			Expect(!tables[1].Get("name", qod::JsonValue::Kind::STRING).string.empty(), "a name for errors");
		}
		Expect(got.size() == 2 && got[0].has_value() && !got[1].has_value(), "the record, then missing");
	}

	// A good answer is cached until expiresAt: one HTTP call for two statements; past it, asked again.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "")));
		auto before = server.Requests();
		client.Lookup(url, "h1", kNonce, Tables {{"acme_lake", 12}});
		auto again = client.Lookup(url, "h2", kOtherNonce, Tables {{"ACME_LAKE", 12}});
		Expect(server.Requests() == before + 1, "two statements, one call");
		Expect(again.size() == 1 && again[0].has_value(), "the cached record");
		Expect(client.CachedEntries() == 1, "one entry kept");
		now_ms += 10001;
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "")));
		client.Lookup(url, "h3", kNonce, Tables {{"acme_lake", 12}});
		Expect(server.Requests() == before + 2, "past expiresAt the record is fetched again");
	}
	TestGoodAnswerCachedUntilExpiry(url);

	// `missing` is cached the same way.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson("", MissingJson("acme_lake", 12))));
		auto before = server.Requests();
		auto a = client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		auto b = client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(a.size() == 1 && !a[0].has_value() && b.size() == 1 && !b[0].has_value(), "missing is no record");
		Expect(server.Requests() == before + 1, "missing is cached");
	}

	// Clock skew (review minor 4): the node keeps an answer for its lifetime from receipt, whatever the
	// replica's clock says: a replica an hour behind, or an hour ahead, is served the same.
	for (int64_t skew : {-3600 * 1000LL, 3600 * 1000LL}) {
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 10000, kNode, now_ms.load() + skew)));
		auto before = server.Requests();
		auto got = client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(got.size() == 1 && got[0].has_value(), "a skewed replica's answer is used");
		now_ms += 9999;
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(server.Requests() == before + 1, "kept for its lifetime from receipt");
		now_ms += 1;
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(server.Requests() == before + 2, "fetched again once its lifetime from receipt passed");
	}
	// An answer of a shorter lifetime is kept that long only; a longer one at most kRecordKeepMs.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 2000)));
		auto before = server.Requests();
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		now_ms += 2000;
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(server.Requests() == before + 2, "a 2 s answer is kept 2 s");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 50000)));
		now_ms += 2000;
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		now_ms += qod::search::kRecordKeepMs;
		client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}});
		Expect(server.Requests() == before + 4, "a 50 s answer is kept kRecordKeepMs at most");
	}

	// A table named twice is asked once and filled at both places from the answer.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "")));
		auto before = server.Requests();
		auto got = client.Lookup(url, "h", kNonce, Tables {{"acme_lake", 12}, {"acme_lake", 12}});
		Expect(server.Requests() == before + 1, "one call for a table named twice");
		auto asked = qod::ParseJson(server.Last().body).Get("tables", qod::JsonValue::Kind::ARRAY).array.size();
		Expect(asked == 1, "the table asked once");
		Expect(got.size() == 2 && got[0].has_value() && got[1].has_value(), "both places filled");
	}

	// The data path comparison (security review M1, M2).
	Expect(qod::search::SameDataPath("s3://lake/acme_lake/", "s3://lake/acme_lake"), "a trailing slash is the same");
	Expect(qod::search::SameDataPath("/data/acme_lake//", "/data/acme_lake/"), "trailing slashes are the same");
	Expect(!qod::search::SameDataPath("s3://lake/acme_lake/", "s3://lake/globex_lake/"), "another folder");
	Expect(!qod::search::SameDataPath("s3://lake/acme_lake/", "s3://lake/acme_lake/sub/"), "a subfolder");
	Expect(!qod::search::SameDataPath("/", "/"), "an empty path names nothing");
	// Windows: DuckLake stores data_path with '/' (StorePath) while a configured dataPath may hold '\\'.
	Expect(qod::search::SameDataPathAs("C:\\qod\\data", "C:/qod/data/", true), "Windows: '\\' and '/' name one local folder");
	Expect(qod::search::SameDataPathAs("C:\\qod\\data\\", "C:/qod/data/", true), "Windows: a trailing '\\' is dropped too");
	Expect(!qod::search::SameDataPathAs("C:\\qod\\data", "C:/qod/other/", true), "Windows: another folder");
	Expect(!qod::search::SameDataPathAs("s3://lake/a\\b/", "s3://lake/a/b/", true), "Windows: a remote path keeps its '\\'");
	Expect(!qod::search::SameDataPathAs("/data/a\\b/", "/data/a/b/", false), "POSIX: '\\' is a name's character");
	// Fail closed (CI round 2 re-review): a case that differs, a UNC or device path, a drive's root, a bare scheme.
	Expect(!qod::search::SameDataPathAs("C:\\QOD\\Data", "C:/qod/data/", true), "Windows: a path differing in case is refused");
	Expect(!qod::search::SameDataPathAs("\\\\server\\share\\data", "//server/share/data/", true),
	       "Windows: a UNC path is refused");
	Expect(!qod::search::SameDataPathAs("\\\\server\\share\\data", "\\\\server\\share\\data", true),
	       "Windows: a UNC path is refused even against itself");
	Expect(!qod::search::SameDataPathAs("\\\\?\\C:\\qod\\data", "C:/qod/data/", true), "Windows: a device path is refused");
	Expect(!qod::search::SameDataPathAs("C:\\", "C:/", true), "Windows: a drive's root is refused");
	Expect(!qod::search::SameDataPathAs("C:", "C:", true), "Windows: a bare drive is refused");
	Expect(!qod::search::SameDataPath("s3://", "s3://"), "a scheme with no folder is refused");

	// Every bad answer: the retryable text, nothing cached.
	{
		RecordClient client(kNode, kKey, 2000, [] { return now_ms.load(); });
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), ""), std::string(32, '\x43')));
		ExpectRefused(client, url, "a bad signature");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), ""), kKey) + "0");
		ExpectRefused(client, url, "a malformed MAC");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kOtherNonce)));
		ExpectRefused(client, url, "a wrong nonce");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 10000, "quack-acme-db-bi-2")));
		ExpectRefused(client, url, "another node");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 0)));
		ExpectRefused(client, url, "an answer whose lifetime is not positive");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, -5)));
		ExpectRefused(client, url, "an answer expiring before it was issued");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), "", kNonce, 3600 * 1000)));
		ExpectRefused(client, url, "an answer valid too long");
		Answering(503, "index records cannot be served now");
		ExpectRefused(client, url, "a 503");
		Answering(401, "handle refused");
		ExpectRefused(client, url, "a 401");
		Answering(200, "not an answer");
		ExpectRefused(client, url, "a body that is no answer");
		// D10: no record may set k1 or b.
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12, ",\"k1\":1"), "")));
		ExpectRefused(client, url, "a record holding k1");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12, ",\"b\":1"), "")));
		ExpectRefused(client, url, "a record holding b");
		// The MAC key: exactly 64 lowercase hex digits, 32 bytes, not all zero.
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12, "", std::string(64, '0')), "")));
		ExpectRefused(client, url, "an all-zero MAC key");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12, "", std::string(62, 'a')), "")));
		ExpectRefused(client, url, "a short MAC key");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12, "", std::string(64, 'A')), "")));
		ExpectRefused(client, url, "an uppercase MAC key");
		// Every table answered once, and only tables asked.
		Answering(200, Sign(AnswerJson("", "")));
		ExpectRefused(client, url, "a table left unanswered");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12) + "," + RecordJson("acme_lake", 14), "")));
		ExpectRefused(client, url, "a record not asked for");
		Answering(200, Sign(AnswerJson(RecordJson("acme_lake", 12), MissingJson("acme_lake", 12))));
		ExpectRefused(client, url, "a table answered twice");
		Answering(200, Sign(AnswerJson(RecordJson("globex_lake", 12), "")));
		ExpectRefused(client, url, "a record of another catalog");
	}

	// A replica that cannot be reached.
	{
		RecordClient client(kNode, kKey, 500, [] { return now_ms.load(); });
		auto dead = "http://127.0.0.1:" + std::to_string(qod_test::UnusedPort());
		ExpectRefused(client, dead, "an unreachable replica");
	}

	// A DuckLake catalog's own data path, as the record's bundle root is compared with.
	{
		duckdb::DuckDB db(nullptr);
		duckdb::Connection con(db);
		if (con.Query("INSTALL ducklake")->HasError() || con.Query("LOAD ducklake")->HasError()) {
			std::cout << "search_records: ducklake cannot load; the data path read is not checked" << std::endl;
		} else {
			auto dir = std::filesystem::temp_directory_path() /
			           ("qod-records-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
			std::filesystem::create_directories(dir);
			std::string base = dir.generic_string();
			auto data = base + "/data/";
			auto attach = "ATTACH 'ducklake:" + base + "/meta.ducklake' AS lake (DATA_PATH '" + data + "')";
			auto attached = con.Query(attach);
			Expect(!attached->HasError(), "the lake attaches");
			con.BeginTransaction();
			std::string path;
			auto read = qod::DuckLakeDataPath(*con.context, "lake", path);
			Expect(read == qod::DuckLakeIdRead::Found, "the data path is read");
			Expect(qod::search::SameDataPath(path, data), "the data path is the attached one, got " + path);
			Expect(!qod::search::SameDataPath(path, base + "/other/"), "another folder differs");
			Expect(qod::DuckLakeDataPath(*con.context, "memory", path) == qod::DuckLakeIdRead::None,
			       "memory is no DuckLake catalog");
			con.Commit();
			// Task 12 (carried from Task 7): the record check's data path, kept per catalog and attach for at most
			// kRecordKeepMs, read again after a DETACH and ATTACH under the same alias and once the time is up.
			std::atomic<int> reads {0};
			qod::SearchMetadataReadObserverForTest([&](const std::string &sql) {
				if (sql.find("data_path") != std::string::npos) {
					reads++;
				}
			});
			auto kept = [&](const std::string &what, int want) {
				reads = 0;
				std::string got;
				con.BeginTransaction();
				auto r = qod::search::CatalogDataPath(*con.context, "lake", got);
				con.Commit();
				Expect(r == qod::DuckLakeIdRead::Found && qod::search::SameDataPath(got, data) && reads.load() == want,
				       "CatalogDataPath " + what + ": " + std::to_string(reads.load()) + " metadata read(s), want " +
				           std::to_string(want));
			};
			kept("first", 1);
			kept("again, kept", 0);
			con.Query("DETACH lake");
			Expect(!con.Query(attach)->HasError(), "the lake attaches again under the same alias");
			kept("after a DETACH and ATTACH, read again", 1);
			kept("again, kept", 0);
			qod::search::AdvanceDataPathClockForTest(qod::search::kRecordKeepMs + 1);
			kept("past kRecordKeepMs, read again", 1);
			std::string none;
			con.BeginTransaction();
			Expect(qod::search::CatalogDataPath(*con.context, "memory", none) == qod::DuckLakeIdRead::None,
			       "CatalogDataPath: memory is no DuckLake catalog");
			con.Commit();
			qod::SearchMetadataReadObserverForTest(nullptr);
			con.Query("DETACH lake");
			std::error_code ignored;
			std::filesystem::remove_all(dir, ignored);
		}
	}

	// Off a quack session the installed fetch serves no record and asks nothing.
	{
		auto client = std::make_shared<RecordClient>(kNode, kKey, 2000, [] { return now_ms.load(); });
		qod::search::InstallRecordFetch(client);
		duckdb::DuckDB db(nullptr);
		duckdb::Connection con(db);
		auto before = server.Requests();
		auto record = qod::search::IndexRecordFor(*con.context, "acme_lake", 12);
		Expect(!record.has_value(), "no record off a quack session");
		auto fetched = qod::search::FetchIndexRecords(*con.context, Tables {{"acme_lake", 12}, {"acme_lake", 13}});
		Expect(fetched.size() == 2 && !fetched[0] && !fetched[1],
		       "FetchIndexRecords off a quack session answers none for each table, in order");
		Expect(server.Requests() == before, "nothing asked off a quack session");
		qod::search::SetIndexRecordSource(nullptr);
	}

	if (failures == 0) {
		std::cout << "search_records: all checks passed" << std::endl;
		return 0;
	}
	std::cerr << failures << " check(s) failed" << std::endl;
	return 1;
}
