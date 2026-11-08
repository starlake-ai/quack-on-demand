#include "spill_dir.hpp"

#include "utf8_args.hpp"

#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <random>
#include <regex>
#include <stdexcept>
#include <system_error>

#ifndef _WIN32
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif

namespace fs = std::filesystem;

namespace qod {

namespace {

constexpr int kRandomChars = 6;
constexpr int kAttempts = 64;

std::string RandomSuffix(std::mt19937_64 &rng) {
	static const char kAlphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	std::uniform_int_distribution<size_t> pick(0, sizeof(kAlphabet) - 2);
	std::string out;
	for (int i = 0; i < kRandomChars; i++) {
		out.push_back(kAlphabet[pick(rng)]);
	}
	return out;
}

std::string NotADirectory(const std::string &parent, const fs::path &at, const std::string &what) {
	return "cannot create the spill directory under " + parent + ": " + PathToUtf8(at) + " " + what;
}

#ifndef _WIN32

//! Makes `dir` with mode 0700 (mkdir's mode, so no window at the process umask). Returns true when this
//! call created it, false when something is already there; throws on any other failure.
bool MakeDirectory(const fs::path &dir) {
	if (::mkdir(dir.c_str(), 0700) == 0) {
		return true;
	}
	int err = errno;
	if (err == EEXIST) {
		return false;
	}
	throw std::runtime_error("cannot create " + PathToUtf8(dir) + ": " + std::strerror(err));
}

std::string OwnedByAnother(const std::string &parent, const fs::path &at, const std::string &what, unsigned owner) {
	return NotADirectory(parent, at,
	                     "is " + what + " owned by another user (uid " + std::to_string(owner) +
	                         "): only root or the node's own user may own it");
}

//! Accepts a component that is already there when root or this user owns it (a system's /tmp, an
//! operator's own folder): a real directory, or, when `followLinks`, a symbolic link that leads to a
//! directory the same two own (an operator's /fastdisk -> /mnt/ssd). A component owned by anyone else is
//! refused, directories included, because its owner could swap the components this call creates; no
//! writable-by-others test is made, so a Kubernetes emptyDir (0777, root's) still works. A component that
//! appeared just after our mkdir got EEXIST is never followed as a link, whoever owns it. Never changes a
//! component's mode.
void AcceptExisting(const std::string &parent, const fs::path &built, bool followLinks) {
	const unsigned self = ::geteuid();
	struct stat link {};
	if (::lstat(built.c_str(), &link) != 0) {
		int err = errno;
		throw std::runtime_error(NotADirectory(parent, built, std::string("cannot be read: ") + std::strerror(err)));
	}
	if (S_ISLNK(link.st_mode)) {
		if (!followLinks) {
			throw std::runtime_error(
			    NotADirectory(parent, built, "appeared as a symbolic link while it was being created"));
		}
		if (!TrustedLinkOwner(link.st_uid, self)) {
			throw std::runtime_error(OwnedByAnother(parent, built, "a symbolic link", link.st_uid));
		}
		struct stat target {};
		if (::stat(built.c_str(), &target) != 0) {
			int err = errno;
			throw std::runtime_error(NotADirectory(
			    parent, built, std::string("is a symbolic link that cannot be read through: ") + std::strerror(err)));
		}
		if (!S_ISDIR(target.st_mode)) {
			throw std::runtime_error(
			    NotADirectory(parent, built, "is a symbolic link to something that is not a directory"));
		}
		if (!TrustedLinkOwner(target.st_uid, self)) {
			std::error_code ignored;
			auto leads_to = PathToUtf8(fs::read_symlink(built, ignored));
			throw std::runtime_error(
			    OwnedByAnother(parent, built, "a symbolic link to " + leads_to + ", a directory", target.st_uid));
		}
		return;
	}
	if (!S_ISDIR(link.st_mode)) {
		throw std::runtime_error(NotADirectory(parent, built, "exists and is not a directory"));
	}
	if (!TrustedLinkOwner(link.st_uid, self)) {
		throw std::runtime_error(OwnedByAnother(parent, built, "a directory", link.st_uid));
	}
}

#else

bool MakeDirectory(const fs::path &dir) {
	std::error_code ec;
	if (fs::create_directory(dir, ec)) {
		return true;
	}
	if (ec) {
		throw std::runtime_error("cannot create " + PathToUtf8(dir) + ": " + ec.message());
	}
	return false;
}

//! Windows has no owner test here (the PowerShell twin checks the leaf's owner), so a symbolic link or a
//! junction among the components is refused, not followed: one planted by another user would carry the spill
//! folder elsewhere and nothing here could tell. Only a real directory is accepted (symlink_status does not
//! follow, and reports a junction as its own type). Every filesystem call takes an error_code, so nothing
//! but std::runtime_error leaves.
void AcceptExisting(const std::string &parent, const fs::path &built, bool) {
	std::error_code ec;
	auto status = fs::symlink_status(built, ec);
	if (ec) {
		throw std::runtime_error(NotADirectory(parent, built, "cannot be read: " + ec.message()));
	}
	if (status.type() != fs::file_type::directory) {
		throw std::runtime_error(NotADirectory(
		    parent, built, "exists and is not a directory (a symbolic link or junction is not followed here)"));
	}
}

#endif

} // namespace

bool TrustedLinkOwner(unsigned owner, unsigned self) {
	return owner == 0 || owner == self;
}

#ifndef _WIN32

namespace {

//! As many links as Linux follows in one lookup (MAXSYMLINKS): past it the path is a loop.
constexpr int kMaxLinks = 40;

//! Resolves absolute `path` into `real` as the kernel would, judging each component it lstats. Returns ""
//! or the first refusal (see ForeignOwnerOnPath). `links` counts the links followed so far.
std::string ResolveJudged(const fs::path &path, unsigned self, int &links, fs::path &real) {
	fs::path built = path.root_path();
	for (const auto &part : path.relative_path()) {
		if (part.empty() || part == ".") {
			continue;
		}
		if (part == "..") {
			// `built` is real here, so its parent is the folder ".." names.
			built = built.parent_path();
			continue;
		}
		auto next = built / part;
		struct stat st {};
		if (::lstat(next.c_str(), &st) != 0) {
			int err = errno;
			return PathToUtf8(next) + " cannot be read: " + std::strerror(err);
		}
		bool link = S_ISLNK(st.st_mode);
		if (!TrustedLinkOwner(st.st_uid, self)) {
			return PathToUtf8(next) + " is " + (link ? "a symbolic link" : "a directory") +
			       " owned by another user (uid " + std::to_string(st.st_uid) +
			       "): only root or the node's own user may own it";
		}
		if (!link) {
			built = next;
			continue;
		}
		if (++links > kMaxLinks) {
			return PathToUtf8(next) + " leads through too many symbolic links";
		}
		std::error_code ec;
		auto target = fs::read_symlink(next, ec);
		if (ec) {
			return PathToUtf8(next) + " cannot be read: " + ec.message();
		}
		fs::path resolved;
		auto refused = ResolveJudged(target.is_absolute() ? target : built / target, self, links, resolved);
		if (!refused.empty()) {
			return refused;
		}
		built = resolved;
	}
	real = built;
	return "";
}

} // namespace

std::string ForeignOwnerOnPath(const std::string &path, unsigned self) {
	int links = 0;
	fs::path real;
	std::error_code ec;
	// A relative path (DuckDB's temp_directory may be one) resolves from the working directory.
	auto absolute = fs::absolute(PathFromUtf8(path), ec);
	if (ec) {
		return path + " cannot be made absolute: " + ec.message();
	}
	return ResolveJudged(absolute, self, links, real);
}

#endif

std::string SpillLeafPrefix(const std::string &marker) {
	return marker.empty() ? "qod-spill." : "qod-spill." + marker + ".";
}

bool IsSpillMarker(const std::string &marker) {
	static const std::regex kMarker(R"(^[0-9]+-[0-9a-f]+$)");
	return marker.empty() || std::regex_match(marker, kMarker);
}

bool IsSpillLeafName(const std::string &name) {
	static const std::regex kLeaf(R"(^qod-spill\.([0-9]+-[0-9a-f]+\.)?[A-Za-z0-9]{6}$)");
	return std::regex_match(name, kLeaf);
}

std::string MakePerNodeSpillDir(const std::string &parent, const std::string &marker) {
	// spill.path is one path per line, so a path with a line break could not be recorded as itself (the
	// spawn script would read its pieces as paths of their own). The value is not quoted back.
	if (parent.find_first_of("\r\n") != std::string::npos) {
		throw std::invalid_argument("the spill directory's parent holds a line break");
	}
	if (!IsSpillMarker(marker)) {
		throw std::invalid_argument("the spill marker '" + marker + "' is not <port>-<hex>");
	}
	fs::path path = PathFromUtf8(parent);
	// The root ("/", or "C:\\" on Windows) is where the walk starts, not a component to examine or make: on
	// Windows iterating the whole path yields the drive "C:" first, which names the drive's current
	// directory, not its root.
	fs::path built = path.root_path();
	for (const auto &part : path.relative_path()) {
		if (part.empty()) {
			continue;
		}
		built /= part;
		std::error_code ec;
		// Only a component this call made is restricted to its owner (it is MakeDirectory's 0700); one
		// that was there is the operator's and keeps its mode.
		if (fs::exists(fs::symlink_status(built, ec))) {
			AcceptExisting(parent, built, true);
			continue;
		}
		if (!MakeDirectory(built)) {
			// Created by someone between the check and the call: judge what is there now.
			AcceptExisting(parent, built, false);
		}
	}
	std::mt19937_64 rng(std::random_device {}());
	for (int attempt = 0; attempt < kAttempts; attempt++) {
		auto leaf = path / PathFromUtf8(SpillLeafPrefix(marker) + RandomSuffix(rng));
		if (MakeDirectory(leaf)) {
#ifndef _WIN32
			// The walk above judged the path as written, a followed link and its target: the folders above
			// that target, and any link inside it, are judged here on the leaf's real path (an operator's
			// /fast -> /mnt/ssd/qod with /mnt/ssd another user's). The leaf goes again when one fails.
			auto refused = ForeignOwnerOnPath(PathToUtf8(leaf), ::geteuid());
			if (!refused.empty()) {
				::rmdir(leaf.c_str());
				throw std::runtime_error("cannot create the spill directory under " + parent + ": " + refused);
			}
#endif
			return PathToUtf8(leaf);
		}
	}
	throw std::runtime_error("cannot create a spill directory under " + parent + ": no free name");
}

void RecordSpillDir(const std::string &node_dir, const std::string &leaf) {
	std::ofstream out(PathFromUtf8(node_dir) / "spill.path", std::ios::app);
	out << "\n" << leaf;
	out.close();
	if (!out) {
		throw std::runtime_error("cannot record the spill directory in " + node_dir);
	}
}

} // namespace qod
