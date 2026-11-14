// Search Phase 0 probe (spec Q7): a segment bundle encrypted with its source data file's own DuckLake
// key, through DuckDB's EncryptionState API (duckdb/common/encryption_state.hpp). Prints lengths,
// encodings, equalities and outcomes, never a key or a key's hash.
// 1. What a DuckDB instance offers without httpfs (the mbedtls fallback) and with it (OpenSSL).
// 2. An ENCRYPTED DuckLake with two data files: where each file's key lives, its form, whether the keys
//    differ, and whether the target scan's bind data carries the same key (a post_bind hook reads
//    MultiFileBindData's per-file extended_info, so the search call needs no catalog read of its own).
// 3. AES-GCM over 1 MiB with the bind data's key: round trip, one-shot against chunked, a bundle file
//    written and read back through DuckDB's FileSystem and searched for a known term, and decryption
//    with a flipped ciphertext byte, a flipped tag byte, another file's key, a random key and another AAD.
// 4. A per-bundle subkey derived with HMAC-SHA256 from the file key (key separation from Parquet's use).
// The probe data goes under $QOD_SEARCH_PROBE_DIR when set (kept), else under the temp folder (removed).
// Exits 77 (CTest skip) when ducklake or httpfs (OpenSSL) cannot be installed or loaded.
//
// Not part of the default test run (network INSTALL, prints more than it asserts). Unix only:
//   cmake -S native/qod-node -B <build> -DQOD_NODE_TESTS=ON -DQOD_NODE_PROBES=ON -DDUCKDB_HOME=<.duckdb/2.0.0>
//   cmake --build <build> --target search_encryption_probe_test
//   QOD_SEARCH_PROBE_DIR=<data dir> ctest --test-dir <build> -R search_encryption_probe -V

#include "duckdb.hpp"
#include "duckdb/common/encryption_state.hpp"
#include "duckdb/common/encryption_types.hpp"
#include "duckdb/common/file_open_flags.hpp"
#include "duckdb/common/file_system.hpp"
#include "duckdb/common/multi_file/multi_file_list.hpp"
#include "duckdb/common/multi_file/multi_file_states.hpp"
#include "duckdb/common/types/blob.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/database.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cxxabi.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <mutex>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <typeinfo>
#include <unistd.h>
#include <vector>

using namespace duckdb;

namespace {

constexpr idx_t kTagLen = 16;
const std::string kNeedle = "qodneedle";

std::string Demangle(const char *name) {
	int status = 0;
	char *out = abi::__cxa_demangle(name, nullptr, nullptr, &status);
	std::string result = status == 0 && out ? out : name;
	std::free(out);
	return result;
}

// ---- post_bind capture of the scan's per-file keys -------------------------------------------

std::mutex capture_mutex;
bool capture_on = false;
ClientContext *capture_context = nullptr;
//! file name -> the encryption_key option of its extended_info (never printed).
std::map<std::string, Value> bind_keys;
std::set<std::string> bind_option_names;

std::string BaseName(const std::string &path) {
	auto slash = path.find_last_of('/');
	return slash == std::string::npos ? path : path.substr(slash + 1);
}

struct Collected {
	std::map<std::string, Value> keys;
	std::set<std::string> names;
};

void CollectKeys(LogicalOperator &op, Collected &out) {
	if (op.type == LogicalOperatorType::LOGICAL_GET) {
		auto &get = op.Cast<LogicalGet>();
		auto files = dynamic_cast<MultiFileBindData *>(get.bind_data.get());
		if (files && files->file_list) {
			// DuckLake's file list can expand lazily here, running its own metadata query (and so this
			// hook, re-entrantly on this thread): no lock is held while walking.
			for (auto &file : files->file_list->GetAllFiles()) {
				if (!file.extended_info) {
					continue;
				}
				for (auto &option : file.extended_info->options) {
					out.names.insert(option.first);
					if (option.first == "encryption_key") {
						out.keys[BaseName(file.path)] = option.second;
					}
				}
			}
		}
	}
	for (auto &child : op.children) {
		CollectKeys(*child, out);
	}
}

thread_local int walk_depth = 0;
std::atomic<idx_t> hooks_during_walk {0};
std::atomic<idx_t> hooks_during_walk_same_context {0};
thread_local ClientContext *walk_context = nullptr;

void CapturePostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (walk_depth > 0) {
		hooks_during_walk++;
		if (&input.context == walk_context) {
			hooks_during_walk_same_context++;
		}
	}
	{
		std::lock_guard<std::mutex> lock(capture_mutex);
		if (!capture_on || &input.context != capture_context || !statement.plan) {
			return;
		}
	}
	Collected found;
	walk_depth++;
	walk_context = &input.context;
	CollectKeys(*statement.plan, found);
	walk_depth--;
	std::lock_guard<std::mutex> lock(capture_mutex);
	for (auto &entry : found.keys) {
		bind_keys[entry.first] = entry.second;
	}
	bind_option_names.insert(found.names.begin(), found.names.end());
}

// ---- helpers ---------------------------------------------------------------------------------

int failures = 0;

void Check(bool ok, const std::string &what) {
	std::cout << (ok ? "PASS " : "FAIL ") << what << std::endl;
	if (!ok) {
		failures++;
	}
}

unique_ptr<QueryResult> Run(Connection &con, const std::string &sql, const std::string &what) {
	unique_ptr<QueryResult> result = con.Query(sql);
	if (result->HasError()) {
		std::cout << "ERROR " << what << ": " << result->GetError() << std::endl;
		failures++;
	}
	return result;
}

//! The key bytes as AES takes them: a BLOB as is, a VARCHAR base64-decoded.
std::string KeyBytes(const Value &value) {
	if (value.type().id() == LogicalTypeId::BLOB) {
		return StringValue::Get(value);
	}
	auto text = StringValue::Get(value);
	return Blob::FromBase64(string_t(text));
}

//! Every row of a result, as Values (the result is consumed).
std::vector<std::vector<Value>> Rows(QueryResult &result) {
	std::vector<std::vector<Value>> rows;
	while (true) {
		auto chunk = result.Fetch();
		if (!chunk || chunk->size() == 0) {
			return rows;
		}
		for (idx_t row = 0; row < chunk->size(); row++) {
			std::vector<Value> values;
			for (idx_t col = 0; col < chunk->ColumnCount(); col++) {
				values.push_back(chunk->GetValue(col, row));
			}
			rows.push_back(std::move(values));
		}
	}
}

std::string TypeName(const EncryptionUtil *util) {
	if (!util) {
		return "<null>";
	}
	auto &ref = *util;
	return Demangle(typeid(ref).name());
}

std::string Describe(const Value &value) {
	std::ostringstream out;
	out << "type " << value.type().ToString();
	if (value.IsNull()) {
		out << ", NULL";
		return out.str();
	}
	auto raw = StringValue::Get(value);
	out << ", " << raw.size() << " characters";
	if (value.type().id() == LogicalTypeId::VARCHAR) {
		bool base64_alphabet = std::all_of(raw.begin(), raw.end(), [](unsigned char c) {
			return std::isalnum(c) || c == '+' || c == '/' || c == '=';
		});
		out << ", base64 alphabet only: " << (base64_alphabet ? "yes" : "no");
		try {
			out << ", base64-decodes to " << Blob::FromBase64(string_t(raw)).size() << " bytes";
		} catch (std::exception &) {
			out << ", not valid base64";
		}
	}
	return out.str();
}

struct Sealed {
	std::vector<data_t> nonce;
	std::vector<data_t> cipher;
	std::vector<data_t> tag;
};

shared_ptr<EncryptionState> NewGcmState(EncryptionUtil &util, idx_t key_len) {
	return util.CreateEncryptionState(
	    make_uniq<EncryptionStateMetadata>(EncryptionTypes::GCM, key_len, EncryptionTypes::EncryptionVersion::V0_1));
}

//! AES-GCM seal with a fresh random nonce; `chunk` > 0 feeds Process in pieces of that size.
Sealed Seal(EncryptionUtil &util, const std::string &key, const std::vector<data_t> &plain, const std::string &aad,
            idx_t chunk = 0) {
	auto state = NewGcmState(util, key.size());
	EncryptionNonce nonce(EncryptionTypes::GCM, EncryptionTypes::EncryptionVersion::V0_1);
	state->GenerateRandomData(nonce.data(), nonce.size());
	Sealed out;
	out.nonce.assign(nonce.data(), nonce.data() + nonce.size());
	state->InitializeEncryption(nonce, const_data_ptr_cast(key.data()), const_data_ptr_cast(aad.data()), aad.size());
	out.cipher.resize(plain.size());
	if (chunk == 0) {
		state->Process(plain.data(), plain.size(), out.cipher.data(), out.cipher.size());
	} else {
		for (idx_t at = 0; at < plain.size(); at += chunk) {
			auto len = std::min<idx_t>(chunk, plain.size() - at);
			state->Process(plain.data() + at, len, out.cipher.data() + at, len);
		}
	}
	out.tag.resize(kTagLen);
	state->Finalize(out.cipher.data() + out.cipher.size(), 0, out.tag.data(), out.tag.size());
	return out;
}

//! Opens a sealed buffer; returns the error text, empty on success.
std::string Open(EncryptionUtil &util, const std::string &key, const Sealed &sealed, const std::string &aad,
                 std::vector<data_t> &plain) {
	try {
		auto state = NewGcmState(util, key.size());
		EncryptionNonce nonce(EncryptionTypes::GCM, EncryptionTypes::EncryptionVersion::V0_1);
		std::copy(sealed.nonce.begin(), sealed.nonce.end(), nonce.data());
		state->InitializeDecryption(nonce, const_data_ptr_cast(key.data()), const_data_ptr_cast(aad.data()),
		                            aad.size());
		plain.assign(sealed.cipher.size(), 0);
		state->Process(sealed.cipher.data(), sealed.cipher.size(), plain.data(), plain.size());
		auto tag = sealed.tag;
		state->Finalize(plain.data() + plain.size(), 0, tag.data(), tag.size());
		return "";
	} catch (std::exception &e) {
		auto text = std::string(e.what());
		return text.empty() ? "<exception, no text>" : text;
	}
}

idx_t CountNeedle(const std::vector<data_t> &bytes) {
	idx_t count = 0;
	auto it = bytes.begin();
	while (true) {
		it = std::search(it, bytes.end(), kNeedle.begin(), kNeedle.end());
		if (it == bytes.end()) {
			return count;
		}
		count++;
		it++;
	}
}

std::vector<data_t> ReadAll(const std::filesystem::path &path) {
	std::ifstream in(path, std::ios::binary);
	return std::vector<data_t>(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

//! Step 1: a fresh instance, before any extension: what GetEncryptionUtil hands out and whether it seals.
void ProbeWithoutHttpfs(bool autoload) {
	std::cout << "== 1. instance without LOAD httpfs, autoload_known_extensions=" << (autoload ? "true" : "false")
	          << std::endl;
	DBConfig db_config;
	db_config.SetOptionByName("autoload_known_extensions", Value::BOOLEAN(autoload));
	DuckDB db(nullptr, &db_config);
	auto &config = DBConfig::GetConfig(*db.instance);
	std::cout << "DBConfig::encryption_util: "
	          << TypeName(config.encryption_util.get())
	          << std::endl;
	std::string key(32, '\x01');
	std::vector<data_t> plain(4096, 0x41);
	for (bool read_only : {false, true}) {
		std::string label = std::string("GetEncryptionUtil(read_only=") + (read_only ? "true" : "false") + ")";
		try {
			auto util = db.instance->GetEncryptionUtil(read_only);
			std::cout << label << ": " << TypeName(util.get()) << std::endl;
			if (!util) {
				continue;
			}
			auto sealed = Seal(*util, key, plain, "");
			std::vector<data_t> back;
			auto err = Open(*util, key, sealed, "", back);
			std::cout << label << " GCM seal/open: " << (err.empty() && back == plain ? "round trip ok" : err)
			          << std::endl;
		} catch (std::exception &e) {
			std::cout << label << ": throws: " << e.what() << std::endl;
		}
	}
}

} // namespace

int main() {
	auto env_dir = std::getenv("QOD_SEARCH_PROBE_DIR");
	auto keep = env_dir && *env_dir;
	auto dir = keep ? std::filesystem::path(env_dir) / ("run-" + std::to_string(::getpid()))
	                : std::filesystem::temp_directory_path() / ("qod-search-enc-" + std::to_string(::getpid()));
	std::filesystem::remove_all(dir);
	std::filesystem::create_directories(dir);
	std::cout << "probe dir: " << dir.string() << std::endl;
	// The folder holds the catalog with every file key: removed on every exit unless kept on purpose.
	// Declared before the database, so it runs after the database has closed its files.
	struct RemoveOnExit {
		std::filesystem::path dir;
		bool keep;
		~RemoveOnExit() {
			if (!keep) {
				std::error_code ignored;
				std::filesystem::remove_all(dir, ignored);
			}
		}
	} remove_on_exit {dir, keep};

	ProbeWithoutHttpfs(false);
	ProbeWithoutHttpfs(true);

	std::cout << "== 2. encrypted DuckLake" << std::endl;
	DuckDB db(nullptr);
	{
		PlannerExtension planner;
		planner.post_bind_function = CapturePostBind;
		PlannerExtension::Register(DBConfig::GetConfig(*db.instance), planner);
	}
	Connection con(db);
	for (auto ext : {"ducklake", "httpfs"}) {
		if (con.Query(std::string("INSTALL ") + ext)->HasError() || con.Query(std::string("LOAD ") + ext)->HasError()) {
			std::cerr << ext << " unavailable: skipped" << std::endl;
			return 77;
		}
	}
	auto &config = DBConfig::GetConfig(*db.instance);
	std::cout << "after LOAD httpfs, DBConfig::encryption_util: "
	          << TypeName(config.encryption_util.get())
	          << std::endl;
	auto util = db.instance->GetEncryptionUtil(false);
	std::cout << "GetEncryptionUtil(read_only=false): "
	          << TypeName(util.get()) << std::endl;
	if (!util) {
		std::cerr << "no encryption util" << std::endl;
		return 1;
	}

	auto meta = (dir / "enc.ducklake").string();
	auto data = (dir / "data").string() + "/";
	if (Run(con, "ATTACH 'ducklake:" + meta + "' AS lake (DATA_PATH '" + data + "', ENCRYPTED)", "attach")->HasError()) {
		return 1;
	}
	Run(con, "CREATE TABLE lake.t AS SELECT i, '" + kNeedle + " ' || i AS s FROM range(10000) r(i)", "create");
	Run(con, "INSERT INTO lake.t SELECT i, '" + kNeedle + " ' || i FROM range(10000, 20000) r(i)", "second file");

	auto files_result = Run(con,
	                 "SELECT data_file_id, path, path_is_relative, encryption_key FROM "
	                 "__ducklake_metadata_lake.ducklake_data_file ORDER BY data_file_id",
	                 "catalog keys");
	if (files_result->HasError()) {
		return 1;
	}
	auto file_types = files_result->GetTypes();
	auto files = Rows(*files_result);
	if (files.size() < 2) {
		std::cout << "expected two data files, got " << files.size() << std::endl;
		return 1;
	}
	std::cout << "ducklake_data_file.encryption_key column type: " << file_types[3].ToString() << std::endl;
	std::map<std::string, Value> catalog_keys;
	for (idx_t row = 0; row < files.size(); row++) {
		auto path = files[row][1].ToString();
		auto key = files[row][3];
		catalog_keys[BaseName(path)] = key;
		std::cout << "data file " << files[row][0].ToString() << " (" << BaseName(path)
		          << ", relative=" << files[row][2].ToString() << "): key " << Describe(key) << std::endl;
	}
	auto first = catalog_keys.begin()->second;
	auto second = std::next(catalog_keys.begin())->second;
	Check(!first.IsNull() && !second.IsNull() && StringValue::Get(first) != StringValue::Get(second),
	      "each data file has its own key (the two keys differ)");
	{
		auto meta_result = Run(con,
		                     "SELECT key, value FROM __ducklake_metadata_lake.ducklake_metadata "
		                     "WHERE key IN ('encrypted', 'data_path') ORDER BY key",
		                     "ducklake_metadata");
		auto meta_rows = meta_result->HasError() ? std::vector<std::vector<Value>>() : Rows(*meta_result);
		for (auto &row : meta_rows) {
			auto key = row[0].ToString();
			std::cout << "ducklake_metadata " << key << " = "
			          << (key == "encrypted" ? row[1].ToString() : std::string("<a path>"))
			          << std::endl;
		}
	}
	{
		std::vector<std::filesystem::path> parquet;
		for (auto &entry : std::filesystem::recursive_directory_iterator(dir / "data")) {
			if (entry.is_regular_file()) {
				parquet.push_back(entry.path());
			}
		}
		idx_t hits = 0;
		for (auto &path : parquet) {
			hits += CountNeedle(ReadAll(path));
		}
		std::cout << "DuckLake data files on disk: " << parquet.size() << ", occurrences of '" << kNeedle
		          << "' in them: " << hits << std::endl;
		Check(hits == 0, "DuckLake's own encrypted Parquet files do not show the term");
	}

	{
		std::lock_guard<std::mutex> lock(capture_mutex);
		capture_on = true;
		capture_context = con.context.get();
	}
	Run(con, "SELECT count(*) FROM lake.t", "captured scan");
	{
		std::lock_guard<std::mutex> lock(capture_mutex);
		capture_on = false;
	}
	std::cout << "bind data extended_info option names:";
	for (auto &name : bind_option_names) {
		std::cout << " " << name;
	}
	std::cout << std::endl;
	std::cout << "bind data files with an encryption_key option: " << bind_keys.size() << std::endl;
	std::cout << "post_bind calls made while walking the bound plan's file list (nested queries): "
	          << hooks_during_walk.load() << " (on the client's own context: " << hooks_during_walk_same_context.load()
	          << ")" << std::endl;
	bool all_equal = !bind_keys.empty() && bind_keys.size() == catalog_keys.size();
	for (auto &entry : bind_keys) {
		auto it = catalog_keys.find(entry.first);
		bool found = it != catalog_keys.end();
		bool same_value = found && !entry.second.IsNull() && entry.second.type() == it->second.type() &&
		                  StringValue::Get(entry.second) == StringValue::Get(it->second);
		bool same_bytes = found && !entry.second.IsNull() && KeyBytes(entry.second) == KeyBytes(it->second);
		std::cout << "bind data key of " << entry.first << ": " << Describe(entry.second)
		          << "; equal to the catalog's value: " << (same_value ? "yes" : "no")
		          << "; same key bytes: " << (same_bytes ? "yes" : "no") << std::endl;
		all_equal &= same_bytes;
	}
	Check(all_equal, "the scan's bind data carries every visible file's key, equal to the catalog's");
	if (bind_keys.empty()) {
		return 1;
	}

	std::cout << "== 3. AES-GCM over a 1 MiB bundle with the bind data's key" << std::endl;
	auto file_key = KeyBytes(bind_keys.begin()->second);
	auto other_key = KeyBytes(std::next(bind_keys.begin())->second);
	std::cout << "AES key from the bind data: " << file_key.size() << " bytes (AES-" << file_key.size() * 8 << ")"
	          << std::endl;
	EncryptionNonce probe_nonce(EncryptionTypes::GCM, EncryptionTypes::EncryptionVersion::V0_1);
	std::cout << "EncryptionNonce(GCM, V0_1): size " << probe_nonce.size() << ", total_size "
	          << probe_nonce.total_size() << "; tag length used " << kTagLen << std::endl;

	std::vector<data_t> plain;
	plain.reserve(1 << 20);
	for (idx_t i = 0; plain.size() < (1 << 20); i++) {
		auto line = kNeedle + " posting " + std::to_string(i) + "\n";
		plain.insert(plain.end(), line.begin(), line.end());
	}
	plain.resize(1 << 20);
	auto plain_hits = CountNeedle(plain);
	std::cout << "plaintext: " << plain.size() << " bytes, " << plain_hits << " occurrences of '" << kNeedle << "'"
	          << std::endl;
	// An AAD would bind the bundle to what it indexes (index id, data file id, format). Probe it first.
	std::string aad = "qod-search-bundle/v1|index=1|data_file=1";
	try {
		auto with_aad = Seal(*util, file_key, plain, aad);
		std::vector<data_t> aad_back;
		auto aad_err = Open(*util, file_key, with_aad, aad, aad_back);
		std::cout << "GCM with a non-empty AAD: " << (aad_err.empty() && aad_back == plain ? "round trip ok" : aad_err)
		          << std::endl;
	} catch (std::exception &e) {
		std::cout << "GCM with a non-empty AAD: InitializeEncryption throws: " << e.what() << std::endl;
		aad.clear();
		std::cout << "(the checks below run with no AAD)" << std::endl;
	}

	auto sealed = Seal(*util, file_key, plain, aad);
	Check(sealed.cipher.size() == plain.size(), "ciphertext is the plaintext's length (GCM is a stream mode)");
	Check(std::any_of(sealed.tag.begin(), sealed.tag.end(), [](data_t b) { return b != 0; }),
	      "Finalize wrote a non-zero 16-byte tag");
	std::vector<data_t> back;
	auto err = Open(*util, file_key, sealed, aad, back);
	Check(err.empty() && back == plain, "decrypt with the right key and nonce gives the plaintext back" +
	                                        (err.empty() ? std::string() : ": " + err));

	{
		// Chunked Process, as a streaming writer would feed it, must equal one-shot under the same nonce.
		auto state = NewGcmState(*util, file_key.size());
		EncryptionNonce nonce(EncryptionTypes::GCM, EncryptionTypes::EncryptionVersion::V0_1);
		std::copy(sealed.nonce.begin(), sealed.nonce.end(), nonce.data());
		state->InitializeEncryption(nonce, const_data_ptr_cast(file_key.data()), const_data_ptr_cast(aad.data()),
		                            aad.size());
		std::vector<data_t> chunked(plain.size());
		for (idx_t at = 0; at < plain.size(); at += 65536 + 7) {
			auto len = std::min<idx_t>(65536 + 7, plain.size() - at);
			state->Process(plain.data() + at, len, chunked.data() + at, len);
		}
		std::vector<data_t> tag(kTagLen);
		state->Finalize(chunked.data() + chunked.size(), 0, tag.data(), tag.size());
		Check(chunked == sealed.cipher && tag == sealed.tag, "chunked Process (65543-byte pieces) equals one-shot");
	}
	{
		auto again = Seal(*util, file_key, plain, aad);
		Check(again.nonce != sealed.nonce && again.cipher != sealed.cipher,
		      "a second seal draws a fresh random nonce (GenerateRandomData)");
	}

	// The bundle on disk: magic, nonce, ciphertext, tag, written and read through DuckDB's FileSystem.
	auto bundle_path = (dir / "bundle-1-1.qsb").string();
	try {
		auto &fs = FileSystem::GetFileSystem(*db.instance);
		std::vector<data_t> bytes;
		const std::string magic("QODSB\x01\x00\x00", 8);
		bytes.insert(bytes.end(), magic.begin(), magic.end());
		bytes.insert(bytes.end(), sealed.nonce.begin(), sealed.nonce.end());
		bytes.insert(bytes.end(), sealed.cipher.begin(), sealed.cipher.end());
		bytes.insert(bytes.end(), sealed.tag.begin(), sealed.tag.end());
		{
			auto handle =
			    fs.OpenFile(bundle_path, FileOpenFlags::FILE_FLAGS_WRITE | FileOpenFlags::FILE_FLAGS_FILE_CREATE_NEW);
			// Sequential writes, as object stores take them (the opener's positional Write is undefined).
			handle->Write(bytes.data(), bytes.size());
			handle->Sync();
			handle->Close();
		}
		auto handle = fs.OpenFile(bundle_path, FileOpenFlags::FILE_FLAGS_READ);
		auto size = handle->GetFileSize();
		std::vector<data_t> read(size);
		handle->Read(read.data(), size, 0);
		std::cout << "bundle file: " << size << " bytes (8 magic + " << sealed.nonce.size() << " nonce + "
		          << sealed.cipher.size() << " ciphertext + " << kTagLen << " tag)" << std::endl;
		auto disk_hits = CountNeedle(ReadAll(bundle_path));
		std::cout << "occurrences of '" << kNeedle << "' in the bundle file: " << disk_hits << std::endl;
		Check(disk_hits == 0, "the encrypted bundle on disk does not show the term");
		Sealed from_disk;
		auto nonce_len = sealed.nonce.size();
		from_disk.nonce.assign(read.begin() + 8, read.begin() + 8 + nonce_len);
		from_disk.cipher.assign(read.begin() + 8 + nonce_len, read.end() - kTagLen);
		from_disk.tag.assign(read.end() - kTagLen, read.end());
		std::vector<data_t> in_memory;
		auto disk_err = Open(*util, file_key, from_disk, aad, in_memory);
		Check(disk_err.empty() && in_memory == plain, "the bundle read back through the FileSystem decrypts in memory" +
		                                                  (disk_err.empty() ? std::string() : ": " + disk_err));
	} catch (std::exception &e) {
		std::cout << "ERROR bundle file through the FileSystem: " << e.what() << std::endl;
		failures++;
	}

	// Tampering and wrong keys: each must fail at Finalize (the tag check), not hand back bytes.
	auto expect_refused = [&](const std::string &what, const std::string &key, const Sealed &input,
	                          const std::string &with_aad) {
		std::vector<data_t> out;
		auto failure = Open(*util, key, input, with_aad, out);
		std::cout << what << ": " << (failure.empty() ? "ACCEPTED" : "refused: " + failure) << std::endl;
		Check(!failure.empty(), what + " is refused");
	};
	{
		auto flipped = sealed;
		flipped.cipher[123456] ^= 0x01;
		expect_refused("one flipped ciphertext bit", file_key, flipped, aad);
	}
	{
		auto flipped = sealed;
		flipped.tag[0] ^= 0x80;
		expect_refused("one flipped tag bit", file_key, flipped, aad);
	}
	{
		auto flipped = sealed;
		flipped.nonce[0] ^= 0x01;
		expect_refused("one flipped nonce bit", file_key, flipped, aad);
	}
	expect_refused("the other data file's key", other_key, sealed, aad);
	{
		std::string random_key(file_key.size(), '\0');
		std::mt19937 rng(7);
		for (auto &c : random_key) {
			c = static_cast<char>(rng());
		}
		expect_refused("a random key", random_key, sealed, aad);
	}
	if (!aad.empty()) {
		expect_refused("another AAD (data_file=2)", file_key, sealed, "qod-search-bundle/v1|index=1|data_file=2");
	}
	{
		auto truncated = sealed;
		truncated.cipher.resize(truncated.cipher.size() - 1);
		expect_refused("a ciphertext one byte short", file_key, truncated, aad);
	}

	std::cout << "== 4. a per-bundle subkey (HMAC-SHA256 of the file key)" << std::endl;
	std::cout << "SupportsHmac(SHA256): " << (util->SupportsHmac(CryptoHashFunction::SHA256) ? "yes" : "no")
	          << std::endl;
	try {
		std::string info = "qod-search-bundle/v1|index=1|data_file=1";
		std::string subkey(32, '\0');
		util->Hmac(CryptoHashFunction::SHA256, const_data_ptr_cast(file_key.data()), file_key.size(),
		           const_data_ptr_cast(info.data()), info.size(), data_ptr_cast(&subkey[0]));
		Check(subkey != std::string(32, '\0') && subkey != file_key, "HMAC-SHA256 gives a 32-byte subkey");
		auto sub_sealed = Seal(*util, subkey, plain, aad, 4096);
		std::vector<data_t> sub_back;
		auto sub_err = Open(*util, subkey, sub_sealed, aad, sub_back);
		Check(sub_err.empty() && sub_back == plain, "AES-256-GCM with the subkey round trips");
		expect_refused("the file key itself on a subkey-sealed bundle", file_key, sub_sealed, aad);
		std::string other_info = "qod-search-bundle/v1|index=1|data_file=2";
		std::string other_subkey(32, '\0');
		util->Hmac(CryptoHashFunction::SHA256, const_data_ptr_cast(file_key.data()), file_key.size(),
		           const_data_ptr_cast(other_info.data()), other_info.size(), data_ptr_cast(&other_subkey[0]));
		expect_refused("the subkey of another (index, data file) label", other_subkey, sub_sealed, aad);
	} catch (std::exception &e) {
		std::cout << "HMAC: throws: " << e.what() << std::endl;
		failures++;
	}

	std::cout << "failures: " << failures << std::endl;
	return failures == 0 ? 0 : 1;
}
