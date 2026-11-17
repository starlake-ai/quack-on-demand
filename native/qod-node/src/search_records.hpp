#pragma once

// QoD Search (spec 6.1, 4.2 rule 8, ruling 18): an index record as the node holds it, and where the node
// takes it from. Built only with QOD_NODE_SEARCH=ON.
//
// A record names the index, the DuckLake table id it pins (ruling 21), the folder its bundles live under,
// its fields bound by DuckLake field id, its state and its bundle MAC key (spec 13). Every one of them comes
// from the record source, never from a statement's arguments or a verdict. A serving node installs the
// signed per-node fetch (InstallRecordFetch: POST /api/internal/search/records on the replica the
// connection's handle names, verified and cached per node); without it the process-wide source answers
// "no record", so every search call is refused search_index_unavailable, and tests install a stand-in.

#include "describer.hpp"

#include "duckdb.hpp"

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace qod::search {

//! One indexed column: `column` its name when the index was created (for display only), `field_id` the
//! DuckLake field id that binds it.
struct IndexField {
	std::string column;
	int64_t field_id;
};

struct IndexRecord {
	std::string index_id;            // canonical UUID
	std::string table;               // catalog.schema.table at create, lowercase: for display and errors only
	int64_t table_id = -1;           // the DuckLake table id the index pins: the lookup key (review M9)
	std::string bundle_root;
	std::vector<IndexField> fields;  // index order; `column` is the name at create, for display; the field id binds
	std::string state;               // "ready" or "disabled"
	//! Never printed, logged or put in an error: any debug print of a record omits it.
	std::array<uint8_t, 32> mac_key{};
	uint64_t segments_version = 0;
};

//! Some(record), or none for a table with no index, looked up by the target's catalog alias and its DuckLake
//! table id, so a renamed table keeps its index. Throws PermissionException with
//! "QoD: authorization unavailable, retry: search index record unavailable" when the source cannot answer.
using IndexRecordSource =
    std::function<std::optional<IndexRecord>(duckdb::ClientContext &, const std::string &catalog, int64_t table_id)>;

//! Installs the process-wide source (Task 7 installs the fetch; tests a stand-in). An empty function puts
//! back the default, which answers none for every table.
void SetIndexRecordSource(IndexRecordSource source);

//! The record of `catalog`'s table `table_id` from the installed source, checked: a record whose table id is
//! not `table_id`, whose index id is not a canonical UUID, whose state is neither "ready" nor "disabled",
//! with no bundle root, with no field or more than 32, with two fields of one field id, a negative field id
//! or a MAC key of all zeros is refused as the source failing (the PermissionException above): a malformed
//! record never reaches the library. None for a table with no index.
std::optional<IndexRecord> IndexRecordFor(duckdb::ClientContext &context, const std::string &catalog, int64_t table_id);

//! The refusal text of a source that cannot answer, retryable (spec 9, Flight UNAVAILABLE).
constexpr const char *kRecordUnavailable = "QoD: authorization unavailable, retry: search index record unavailable";

//! True for a canonical lowercase hyphenated UUID of 36 characters.
bool IsCanonicalUuid(const std::string &text);

//! False for a record no search may trust: an index id that is not a canonical UUID, a state neither
//! "ready" nor "disabled", no bundle root, no field or more than 32, two fields of one field id, a
//! negative field id, an empty column name, or a MAC key of all zeros. IndexRecordFor's checks but the
//! table id's.
bool WellFormedRecord(const IndexRecord &record);

// ---- the signed per-node record fetch (Task 7) -----------------------------------------------------

//! The path every replica serves index records on (SearchRecordRoute).
constexpr const char *kRecordsPath = "/api/internal/search/records";
//! The most tables one call asks for (SearchRecordRoute.MaxTables).
constexpr size_t kMaxRecordTables = 16;
//! The longest lifetime (expiresAt - issuedAt) an answer may carry: the replica sends 10 s.
constexpr int64_t kMaxRecordAnswerMs = 60 * 1000;
//! The longest the node keeps an answer from when it receives it: the lifetime the answer carries, at
//! most this. Measured on the node's own clock from receipt, so the clocks need not agree.
constexpr int64_t kRecordKeepMs = 10 * 1000;
//! How many (catalog, table id) answers the node keeps.
constexpr size_t kMaxCachedRecords = 4096;

//! A table a statement searches or builds: its catalog alias as DuckDB names it, and its DuckLake table id.
using RecordTable = std::pair<std::string, int64_t>;

//! A replica's verified answer: its lifetime (expiresAt - issuedAt), and each table asked, in the order
//! asked, with its record or none (missing).
struct RecordAnswer {
	int64_t lifetime_ms = 0;
	std::vector<std::optional<IndexRecord>> records;
};

//! Verifies a replica's answer `text` (`<base64url(JSON)>.<hex HMAC-SHA256(node key, JSON bytes)>`, the
//! verdicts' format) to a call for `tables` with the handle `nonce`. Throws std::invalid_argument naming the
//! first failed check: the format, the MAC, the version, another node, another nonce, a lifetime
//! (expiresAt - issuedAt) not positive or over kMaxRecordAnswerMs, a member the answer or a record does not
//! have (k1 and b included, D10), a malformed record (WellFormedRecord, a MAC key not exactly 64 lowercase hex
//! digits), a table not asked, one answered twice or one left unanswered. The answer's times are the
//! replica's and are compared with nothing on the node's clock. A message never holds a key or the answer's
//! text.
RecordAnswer VerifyIndexRecords(const std::string &text, const std::string &node_key, const std::string &node_id,
                                const std::string &nonce, const std::vector<RecordTable> &tables);

//! True when two data paths name the same folder: equal in DuckLake's stored form (StorePath: on Windows a local
//! path's '\\' read as '/', as the catalog's ducklake_metadata keeps data_path) once trailing '/' are dropped from both.
//! The manager's bundleRoot is the database's dataPath as configured, which may spell a Windows path either way.
//! Fails closed: a path that ends in ':' once trimmed (a drive's root, a scheme with no folder), and on Windows a UNC or
//! device path (it starts with two separators), match nothing; letters compare exactly (no case folding).
bool SameDataPath(const std::string &a, const std::string &b);
//! SameDataPath with the platform's rule given: `backslash_separator` is true on Windows (the tests drive both).
bool SameDataPathAs(const std::string &a, const std::string &b, bool backslash_separator);

//! DuckLakeDataPath of `catalog` for the record check of FetchIndexRecords (Task 12, carried from Task 7), kept on
//! the database instance (its ObjectCache) by (catalog folded to lowercase, the attached catalog's oid) for
//! kRecordKeepMs from the read: a DETACH and ATTACH under the same alias takes a new oid, so its path is read
//! again. Only a found path is kept (None and Unreadable are read again each time); every use still compares the
//! record's bundle root with it.
DuckLakeIdRead CatalogDataPath(duckdb::ClientContext &context, const std::string &catalog, std::string &data_path);

//! Fetches index records from the replica a connection's handle names and keeps every answer, a record or
//! none, by (catalog folded to lowercase, table id) for its lifetime from receipt, at most kRecordKeepMs.
//! Records are not per principal: the fetch runs after the statement's verdict (or a superuser's or the system
//! principal's local allow) admitted it, and the handle only proves which node of which pool asks. Thread
//! safe.
class RecordClient {
public:
	RecordClient(std::string node_id, std::string node_key, int64_t timeout_ms, std::function<int64_t()> clock);

	//! The record of each of `tables` (1 to kMaxRecordTables), in order: from the cache while an answer is
	//! kept, else from `replica` in one call for the rest, signed with this node's key and carrying the
	//! connection's current handle `handle` (whose nonce is `nonce`), then kept. Throws PermissionException
	//! kRecordUnavailable (retryable) when the replica cannot be called, answers anything but 200, or answers
	//! something that does not verify; nothing is kept then.
	std::vector<std::optional<IndexRecord>> Lookup(const std::string &replica, const std::string &handle,
	                                               const std::string &nonce, const std::vector<RecordTable> &tables);

	//! Drops what is kept for `catalog`'s table `table_id`.
	void Forget(const std::string &catalog, int64_t table_id);

	//! How many answers are kept.
	size_t CachedEntries();

private:
	struct Kept {
		std::optional<IndexRecord> record;
		int64_t until = 0;
	};

	std::string node_id_;
	std::string node_key_;
	int64_t timeout_ms_;
	std::function<int64_t()> clock_;
	std::mutex lock_;
	std::map<std::pair<std::string, int64_t>, Kept> kept_;
};

//! Installs `client` as the process-wide record source (SetIndexRecordSource), through FetchIndexRecords: on a
//! quack session it looks records up with the connection's current handle; off one (the node's own
//! connections) it answers none and asks nothing.
void InstallRecordFetch(std::shared_ptr<RecordClient> client);

//! The record of each of `tables` (catalog alias, DuckLake table id) for the statement on `context`, in the
//! order asked, through the client InstallRecordFetch installed: none for a table with no index, and none for
//! every table off a quack session or with no client installed (nothing asked). A record is used only when its
//! bundle root is the catalog's own DuckLake data path (DuckLakeDataPath, SameDataPath; security review M1 and
//! M2: an alias that names another catalog than the tenant-db's, through a collision or a change under running
//! nodes, would otherwise get that tenant-db's record): any other record, or a catalog recording no single
//! data path, is none, and what the client kept for that table is dropped. Throws PermissionException
//! kRecordUnavailable when the connection has no current handle, the client cannot answer, or the catalog's
//! metadata cannot be read.
std::vector<std::optional<IndexRecord>> FetchIndexRecords(duckdb::ClientContext &context,
                                                          const std::vector<RecordTable> &tables);

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS).
#ifdef QOD_NODE_TEST_HOOKS
//! Milliseconds added to CatalogDataPath's clock (a test moving past kRecordKeepMs).
void AdvanceDataPathClockForTest(int64_t ms);
//! Takes and releases, one after the other, the record source's lock, the installed client's lock and the lock
//! guarding it, and `db`'s data path cache's lock (when it has one) (TakeSearchLocksForTest); returns how many.
uint64_t TakeRecordLocksForTest(duckdb::DatabaseInstance &db);
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod::search
