// Unit tests for locations: what the parse screen sends for a location, and what it refuses locally
// (relative paths, '.'/'..'/empty segments, other schemes, Windows path forms), with real directories
// and symbolic links. Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "locations.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

namespace fs = std::filesystem;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

#ifdef _WIN32
const std::string kSep = "\\";
//! How a path that is not absolute is refused here.
const std::string kNotAbsolute = "is not an absolute path with a drive letter";
#else
const std::string kSep = "/";
const std::string kNotAbsolute = "is a relative path";
#endif

void ExpectResolved(const std::string &path, const std::string &expected, const std::string &what) {
	std::string why;
	auto resolved = qod::ResolveLocalPath(path, &why);
	if (!resolved) {
		std::cerr << "FAIL: " << what << ": refused: " << why << std::endl;
		failures++;
	} else if (*resolved != expected) {
		std::cerr << "FAIL: " << what << ": \"" << *resolved << "\", expected \"" << expected << "\"" << std::endl;
		failures++;
	}
}

void ExpectRefused(const std::string &path, const std::string &needle, const std::string &what) {
	std::string why;
	auto resolved = qod::ResolveLocalPath(path, &why);
	if (resolved) {
		std::cerr << "FAIL: accepted: " << what << " as \"" << *resolved << "\"" << std::endl;
		failures++;
	} else if (why.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << why << "\" does not mention \"" << needle << "\"" << std::endl;
		failures++;
	}
}

void ExpectLocationRefused(const std::string &given, const std::string &needle, const std::string &what) {
	std::string why;
	auto location = qod::MakeLocation(given, "read", "read_csv", why);
	if (location) {
		std::cerr << "FAIL: accepted: " << what << std::endl;
		failures++;
	} else if (why.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << why << "\" does not mention \"" << needle << "\"" << std::endl;
		failures++;
	}
}

std::string Real(const fs::path &path) {
	return fs::canonical(path).u8string();
}

} // namespace

int main() {
	auto base = fs::temp_directory_path() /
	            fs::u8path("qod-locations-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
	fs::create_directories(base / "allowed" / "sub");
	fs::create_directories(base / "outside");
	std::ofstream(base / "allowed" / "f.csv") << "a\n1\n";
	std::ofstream(base / "outside" / "secret.csv") << "s\n1\n";
	auto allowed = (base / "allowed").u8string();

	// ---- resolution: the real path of the deepest existing ancestor, the rest as given
	ExpectResolved(allowed + kSep + "f.csv", Real(base / "allowed" / "f.csv"), "an existing file");
	ExpectResolved(allowed + kSep + "new" + kSep + "out.parquet",
	               Real(base / "allowed") + kSep + "new" + kSep + "out.parquet", "a file to write in a new folder");
	ExpectResolved(allowed + kSep + "*.csv", Real(base / "allowed") + kSep + "*.csv", "a glob");
	ExpectResolved(allowed + kSep, Real(base / "allowed") + kSep, "one trailing separator is kept");

	// ---- lexical refusals, at use like at authorization
	ExpectRefused(allowed + kSep + "." + kSep + "f.csv", "empty, '.' or '..'", "a '.' segment");
	ExpectRefused(allowed + kSep + ".." + kSep + "outside" + kSep + "secret.csv", "empty, '.' or '..'",
	              "a '..' segment");
	ExpectRefused(allowed + kSep + kSep + "f.csv", "empty, '.' or '..'", "an empty segment");
	ExpectRefused(allowed + kSep + kSep, "empty, '.' or '..'", "two trailing separators");
	ExpectRefused("f.csv", kNotAbsolute, "a relative path");
	ExpectRefused("~/f.csv", kNotAbsolute, "a home-relative path");
	ExpectRefused("", kNotAbsolute, "an empty path");
	ExpectRefused(allowed + std::string("\0x", 2), "NUL", "a NUL character");

#ifndef _WIN32
	// ---- symbolic links: followed before the check, so none leads out of a folder unseen
	fs::create_directory_symlink(base / "outside", base / "allowed" / "escape");
	ExpectResolved(allowed + "/escape/x.csv", Real(base / "outside") + "/x.csv", "a linked folder resolves to its target");
	fs::create_symlink(base / "outside" / "secret.csv", base / "allowed" / "link.csv");
	ExpectResolved(allowed + "/link.csv", Real(base / "outside" / "secret.csv"), "a linked file resolves to its target");
	fs::create_symlink(base / "missing", base / "allowed" / "dangling");
	ExpectRefused(allowed + "/dangling", "cannot be resolved", "a link that leads nowhere");
	ExpectRefused(allowed + "/dangling/x.csv", "cannot be resolved", "a path through a link that leads nowhere");
	ExpectRefused("C:\\data\\x.csv", kNotAbsolute, "a Windows path on a POSIX node");
#else
	// ---- Windows forms
	ExpectRefused("C:\\data\\..\\x.csv", "empty, '.' or '..'", "a '..' segment");
	ExpectRefused("C:/data/./x.csv", "empty, '.' or '..'", "a '.' segment, forward slashes");
	ExpectRefused("C:x.csv", kNotAbsolute, "a drive-relative path");
	ExpectRefused("\\\\server\\share\\x.csv", kNotAbsolute, "a UNC path");
	ExpectRefused("\\\\?\\C:\\x.csv", kNotAbsolute, "a device path");
	ExpectRefused("/x.csv", kNotAbsolute, "a rooted path without a drive");
	ExpectRefused("C:\\data\\x.csv:stream", "alternate data stream", "an alternate data stream");
	ExpectRefused("C:\\data\\NUL.txt", "Windows device", "a device name");
	ExpectRefused("C:\\data\\x.", "ending in '.' or ' '", "a segment Windows would strip");
	auto forward = allowed;
	for (auto &c : forward) {
		if (c == '\\') {
			c = '/';
		}
	}
	ExpectResolved(forward + "/new/x.csv", Real(base / "allowed") + "\\new\\x.csv",
	               "forward slashes resolve to the platform separator");
#endif

	// ---- what the screen sends
	std::string why;
	auto s3 = qod::MakeLocation("s3://acme-etl/in/*.csv", "read", "read_csv", why);
	Expect(s3 && s3->resolved == "s3://acme-etl/in/*.csv" && s3->given == s3->resolved && s3->access == "read" &&
	           s3->via == "read_csv",
	       "an s3 URL as given");
	auto upper = qod::MakeLocation("S3://acme-etl/x.csv", "read", "read_csv", why);
	Expect(upper && upper->resolved == "S3://acme-etl/x.csv", "the s3 scheme in any case, sent as given");
	ExpectLocationRefused("s3://", "names no bucket", "an s3 URL without a bucket");
	ExpectLocationRefused("s3:///x.csv", "names no bucket", "an s3 URL with an empty bucket");
	// httpfs reads credentials from an s3 URL's query string: refused on the node, so none is ever sent.
	ExpectLocationRefused("s3://acme-etl/in/x.csv?s3_secret_access_key=qod-not-a-real-password", "query string",
	                      "an s3 URL with a query string");
	ExpectLocationRefused("s3://acme-etl/in/x.csv#f", "fragment", "an s3 URL with a fragment");
	for (auto other : {"https://example.com/x.csv", "http://example.com/x.csv", "gs://b/x.csv", "az://c/x.csv",
	                   "r2://b/x.csv", "hf://datasets/x/y.csv", "file:///etc/passwd"}) {
		ExpectLocationRefused(other, "uses a scheme QoD does not admit", other);
	}
	ExpectLocationRefused("x.csv", kNotAbsolute, "a relative path through MakeLocation");
	auto local = qod::MakeLocation(allowed + kSep + "f.csv", "write", "copy_to", why);
	Expect(local && local->resolved == Real(base / "allowed" / "f.csv") && local->access == "write",
	       "a local path resolved");

	// ---- decision 23: a node secret signs a URL over the user's own when it scopes more closely
	auto memory = qod::SecretStorageOffset("memory");
	Expect(qod::SecretStorageOffset("transaction") == 0 && qod::SecretStorageOffset("connection") == 5 &&
	           memory == 10 && qod::SecretStorageOffset("local_file") == 20 &&
	           qod::SecretStorageOffset("some_extension_storage") == 0,
	       "DuckDB's storage offsets, an unknown storage the strongest");
	std::vector<qod::NodeSecretScope> node_scopes {{"s3://acme-etl/iceberg/", memory},
	                                               {"s3://acme-etl/lake/", qod::SecretStorageOffset("local_file")}};
	auto shadow = [](const std::string &url, const std::vector<std::string> &user,
	                 const std::vector<qod::NodeSecretScope> &node) {
		return qod::ShadowingScope(url, user, node).value_or("<none>");
	};
	Expect(shadow("s3://acme-etl/iceberg/t/x.parquet", {"s3://acme-etl/"}, node_scopes) == "s3://acme-etl/iceberg/",
	       "a node secret inside the user's scope signs that subtree");
	Expect(shadow("s3://acme-etl/in/x.csv", {"s3://acme-etl/"}, node_scopes) == "<none>", "outside it, the user's own");
	Expect(shadow("s3://acme-etl/iceberg/x", {"s3://acme-etl/iceberg/"}, node_scopes) == "<none>",
	       "an equal length goes to the connection secret over memory storage");
	Expect(shadow("s3://acme-etl/lake/x", {"s3://acme-etl/lake/"}, node_scopes) == "<none>",
	       "and over local_file storage");
	Expect(shadow("s3://acme-etl/iceberg/x", {"s3://acme-etl/ice"}, node_scopes) == "s3://acme-etl/iceberg/",
	       "a node scope one character longer wins over every storage offset");
	Expect(shadow("s3://acme-etl/iceberg/x", {"s3://acme-etl/iceberg/"}, {{"s3://acme-etl/iceberg/", 0}}) ==
	           "s3://acme-etl/iceberg/",
	       "an equal length in transaction storage (offset 0) beats the connection secret");
	Expect(shadow("s3://acme-etl/iceberg/x", {"s3://acme-etl/iceberg/"}, {{"s3://acme-etl/iceberg/", 5}}) ==
	           "s3://acme-etl/iceberg/",
	       "an equal score is broken on names the screen does not compare: refused");
	Expect(shadow("s3://acme-etl/iceberg/x", {"s3://acme-etl/iceberg/t/"}, node_scopes) == "s3://acme-etl/iceberg/",
	       "a user scope that does not cover the URL does not count");
	std::vector<qod::NodeSecretScope> catch_all {{"", memory}};
	Expect(shadow("s3://other/x", {}, catch_all) == "", "a node secret matching every path signs what no user's does");
	Expect(shadow("s3://other/x", {"s3://other/"}, catch_all) == "<none>", "but never over a user's that matches");
	Expect(shadow("s3://other/x", {}, {}) == "<none>", "no node secret, nothing shadowed");
	Expect(shadow("/srv/qod-files/x.csv", {}, catch_all) == "<none>", "a local path is never signed");

	// ---- the screen description: canonical, sorted, without duplicates
	qod::Location read {"s3://b/a", "s3://b/a", "read", "read_csv"};
	qod::Location write {"s3://b/o", "s3://b/o", "write", "copy_to"};
	Expect(qod::ScreenDescription({write, read, read}) ==
	           "{\"locations\":[{\"access\":\"read\",\"given\":\"s3://b/a\",\"resolved\":\"s3://b/a\",\"via\":"
	           "\"read_csv\"},{\"access\":\"write\",\"given\":\"s3://b/o\",\"resolved\":\"s3://b/o\",\"via\":"
	           "\"copy_to\"}],\"v\":1}",
	       "the screen description v1");

	std::error_code ignored;
	fs::remove_all(base, ignored);
	if (failures == 0) {
		std::cout << "locations_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
