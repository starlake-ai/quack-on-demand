#include "locations.hpp"

#include "json_writer.hpp"
#include "utf8_args.hpp"

#include <algorithm>
#include <filesystem>
#include <system_error>
#include <utility>

namespace qod {
namespace {

namespace fs = std::filesystem;

#ifdef _WIN32
const char kSeparator = '\\';
#else
const char kSeparator = '/';
#endif

bool IsSeparator(char c) {
#ifdef _WIN32
	return c == '\\' || c == '/';
#else
	return c == '/';
#endif
}

//! A path as UTF-8 text (the inverse of PathFromUtf8).
std::string Utf8(const fs::path &path) {
#if defined(__cpp_char8_t)
	auto text = path.u8string();
	return std::string(text.begin(), text.end());
#else
	return path.u8string();
#endif
}

std::string Lower(std::string text) {
	for (auto &c : text) {
		if (c >= 'A' && c <= 'Z') {
			c = static_cast<char>(c - 'A' + 'a');
		}
	}
	return text;
}

#ifdef _WIN32
//! CON, PRN, AUX, NUL, COM1-COM9, LPT1-LPT9, with or without an extension: Windows opens the device.
bool IsDeviceName(const std::string &segment) {
	auto stem = Lower(segment.substr(0, segment.find('.')));
	if (stem == "con" || stem == "prn" || stem == "aux" || stem == "nul") {
		return true;
	}
	return stem.size() == 4 && (stem.compare(0, 3, "com") == 0 || stem.compare(0, 3, "lpt") == 0) && stem[3] >= '1' &&
	       stem[3] <= '9';
}
#endif

//! Splits the absolute `path` into its root and segments; false, with `why`, when the lexical rules
//! refuse it.
bool Split(const std::string &path, std::string &root, std::vector<std::string> &segments, bool &trailing,
           std::string &why) {
	if (path.find('\0') != std::string::npos) {
		why = "holds a NUL character";
		return false;
	}
	size_t start = 0;
#ifdef _WIN32
	auto letter = path.empty() ? '\0' : path[0];
	if (path.size() < 3 || !((letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z')) || path[1] != ':' ||
	    !IsSeparator(path[2])) {
		why = "is not an absolute path with a drive letter";
		return false;
	}
	root = std::string(1, static_cast<char>(letter >= 'a' ? letter - 'a' + 'A' : letter)) + ":\\";
	start = 3;
#else
	if (path.empty() || path[0] != '/') {
		why = "is a relative path";
		return false;
	}
	root = "/";
	start = 1;
#endif
	trailing = path.size() > start && IsSeparator(path.back());
	auto end = trailing ? path.size() - 1 : path.size();
	segments.clear();
	if (end <= start) {
		return true;
	}
	auto pos = start;
	while (true) {
		auto next = pos;
		while (next < end && !IsSeparator(path[next])) {
			next++;
		}
		auto segment = path.substr(pos, next - pos);
		if (segment.empty() || segment == "." || segment == "..") {
			why = "has an empty, '.' or '..' segment";
			return false;
		}
#ifdef _WIN32
		if (segment.find(':') != std::string::npos) {
			why = "names an alternate data stream";
			return false;
		}
		if (segment.back() == '.' || segment.back() == ' ') {
			why = "has a segment ending in '.' or ' ', which Windows strips";
			return false;
		}
		if (IsDeviceName(segment)) {
			why = "names a Windows device";
			return false;
		}
#endif
		segments.push_back(segment);
		if (next >= end) {
			return true;
		}
		pos = next + 1;
	}
}

bool IsSchemeCharacter(char c) {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '+' || c == '-' ||
	       c == '.';
}

} // namespace

std::optional<std::string> ResolveLocalPath(const std::string &path, std::string *why) {
	std::string root;
	std::string reason;
	std::vector<std::string> segments;
	auto trailing = false;
	if (!Split(path, root, segments, trailing, reason)) {
		if (why) {
			*why = reason;
		}
		return std::nullopt;
	}
	// The deepest existing ancestor: an entry that exists as itself, a link that leads nowhere included,
	// so canonical() below refuses such a link rather than the rest joining past it.
	std::error_code error;
	auto existing = PathFromUtf8(root);
	size_t kept = 0;
	while (kept < segments.size()) {
		auto next = existing / PathFromUtf8(segments[kept]);
		auto status = fs::symlink_status(next, error);
		if (error || !fs::exists(status)) {
			break;
		}
		existing = next;
		kept++;
	}
	auto real = fs::canonical(existing, error);
	if (error) {
		if (why) {
			*why = "cannot be resolved: it passes through a symbolic link that leads nowhere or a folder this node "
			       "cannot read";
		}
		return std::nullopt;
	}
	auto resolved = Utf8(real);
#ifdef _WIN32
	if (resolved.compare(0, 4, "\\\\?\\") == 0) {
		resolved = resolved.substr(4);
	}
	if (resolved.size() < 3 || resolved[1] != ':') {
		if (why) {
			*why = "resolves outside a drive";
		}
		return std::nullopt;
	}
#endif
	for (auto i = kept; i < segments.size(); i++) {
		if (resolved.empty() || resolved.back() != kSeparator) {
			resolved += kSeparator;
		}
		resolved += segments[i];
	}
	if (trailing && (resolved.empty() || resolved.back() != kSeparator)) {
		resolved += kSeparator;
	}
	return resolved;
}

std::optional<Location> MakeLocation(const std::string &given, const std::string &access, const std::string &via,
                                     std::string &why) {
	auto scheme_end = given.find("://");
	if (scheme_end != std::string::npos && scheme_end > 0 &&
	    std::all_of(given.begin(), given.begin() + static_cast<std::ptrdiff_t>(scheme_end), IsSchemeCharacter)) {
		if (Lower(given.substr(0, scheme_end)) != "s3") {
			why = "uses a scheme QoD does not admit (only s3:// URLs and absolute local paths)";
			return std::nullopt;
		}
		auto rest = given.substr(scheme_end + 3);
		if (rest.empty() || rest[0] == '/') {
			why = "names no bucket";
			return std::nullopt;
		}
		// httpfs reads credentials (s3_secret_access_key, s3_session_token...) from an s3 URL's query
		// string: refused here, so none is ever described, sent to the manager or logged.
		if (rest.find_first_of("?#") != std::string::npos) {
			why = "carries a query string or a fragment, which QoD does not admit in an s3:// URL (httpfs reads "
			      "credentials from it)";
			return std::nullopt;
		}
		return Location {given, given, access, via};
	}
	auto resolved = ResolveLocalPath(given, &why);
	if (!resolved) {
		return std::nullopt;
	}
	return Location {given, *resolved, access, via};
}

std::string ScreenDescription(const std::vector<Location> &locations) {
	std::vector<std::pair<std::string, JsonValue>> rendered;
	for (auto &location : locations) {
		auto json = JsonObject({{"access", JsonString(location.access)},
		                        {"given", JsonString(location.given)},
		                        {"resolved", JsonString(location.resolved)},
		                        {"via", JsonString(location.via)}});
		rendered.emplace_back(CanonicalJson(json), json);
	}
	// std::string compares as unsigned bytes: the canonical byte order.
	std::sort(rendered.begin(), rendered.end(),
	          [](const std::pair<std::string, JsonValue> &a, const std::pair<std::string, JsonValue> &b) {
		          return a.first < b.first;
	          });
	std::vector<JsonValue> sorted;
	for (size_t i = 0; i < rendered.size(); i++) {
		if (i == 0 || rendered[i].first != rendered[i - 1].first) {
			sorted.push_back(rendered[i].second);
		}
	}
	return CanonicalJson(JsonObject({{"locations", JsonArray(std::move(sorted))}, {"v", JsonInt(1)}}));
}

int64_t SecretStorageOffset(const std::string &storage) {
	auto name = Lower(storage);
	if (name == "connection") {
		return 5;
	}
	if (name == "memory") {
		return 10;
	}
	if (name == "local_file") {
		return 20;
	}
	return 0;
}

std::optional<std::string> ShadowingScope(const std::string &url, const std::vector<std::string> &user_scopes,
                                          const std::vector<NodeSecretScope> &node_scopes) {
	// httpfs signs only S3 URLs with these secrets (decision 23 screens no other scheme).
	if (Lower(url.substr(0, 5)) != "s3://") {
		return std::nullopt;
	}
	// BaseSecret::MatchScore: the longest scope the URL starts with, an empty one matching at 0.
	auto score = [&url](const std::string &scope, int64_t offset) -> std::optional<int64_t> {
		if (!scope.empty() && url.compare(0, scope.size(), scope) != 0) {
			return std::nullopt;
		}
		return 100 * static_cast<int64_t>(scope.size()) - offset;
	};
	std::optional<int64_t> own;
	for (auto &scope : user_scopes) {
		auto matched = score(scope, kUserSecretStorageOffset);
		if (matched && (!own || *matched > *own)) {
			own = matched;
		}
	}
	for (auto &node : node_scopes) {
		auto matched = score(node.scope, node.storage_offset);
		if (matched && (!own || *matched >= *own)) {
			return node.scope;
		}
	}
	return std::nullopt;
}

} // namespace qod
