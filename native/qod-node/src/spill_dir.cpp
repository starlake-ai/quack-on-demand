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

std::string NotADirectory(const std::string &parent, const fs::path &at, const char *what) {
	return "cannot create the spill directory under " + parent + ": " + PathToUtf8(at) + " " + what;
}

#ifndef _WIN32

//! Makes `dir` with mode 0700 (mkdir's mode, so no window at the process umask). Returns true when this
//! call created it, false when something is already there; throws on any other failure.
bool MakeDirectory(const fs::path &dir) {
	if (::mkdir(dir.c_str(), 0700) == 0) {
		return true;
	}
	if (errno == EEXIST) {
		return false;
	}
	throw std::runtime_error("cannot create " + PathToUtf8(dir) + ": " + std::strerror(errno));
}

//! Accepts a component that is already there: a real directory, or a symbolic link owned by root (a
//! system's own /tmp or /var, which the operator did not make) that leads to one. Any other link, or a
//! file, is refused: a link planted by someone else must not carry the spill folder out of the path
//! the operator named. Never changes the component's mode.
void AcceptExisting(const std::string &parent, const fs::path &built) {
	struct stat link {};
	if (::lstat(built.c_str(), &link) != 0) {
		throw std::runtime_error("cannot examine " + PathToUtf8(built) + ": " + std::strerror(errno));
	}
	if (S_ISDIR(link.st_mode)) {
		return;
	}
	if (!S_ISLNK(link.st_mode)) {
		throw std::runtime_error(NotADirectory(parent, built, "exists and is not a directory"));
	}
	struct stat target {};
	if (link.st_uid != 0 || ::stat(built.c_str(), &target) != 0 || !S_ISDIR(target.st_mode)) {
		throw std::runtime_error(
		    NotADirectory(parent, built, "is a symbolic link not owned by root, or one that leads nowhere"));
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

void AcceptExisting(const std::string &parent, const fs::path &built) {
	std::error_code ec;
	auto status = fs::symlink_status(built, ec);
	if (ec || status.type() != fs::file_type::directory) {
		throw std::runtime_error(NotADirectory(parent, built, "exists and is not a directory"));
	}
}

#endif

} // namespace

std::string SpillLeafPrefix(const std::string &marker) {
	return marker.empty() ? "qod-spill." : "qod-spill." + marker + ".";
}

bool IsSpillLeafName(const std::string &name) {
	static const std::regex kLeaf(R"(^qod-spill\.([0-9]+-[0-9a-f]+\.)?[A-Za-z0-9]{6}$)");
	return std::regex_match(name, kLeaf);
}

std::string MakePerNodeSpillDir(const std::string &parent, const std::string &marker) {
	static const std::regex kMarker(R"(^[0-9]+-[0-9a-f]+$)");
	if (!marker.empty() && !std::regex_match(marker, kMarker)) {
		throw std::invalid_argument("the spill marker '" + marker + "' is not <port>-<hex>");
	}
	fs::path path = PathFromUtf8(parent);
	fs::path built;
	for (const auto &part : path) {
		built /= part;
		std::error_code ec;
		// Only a component this call made is restricted to its owner (it is MakeDirectory's 0700); one
		// that was there is the operator's and keeps its mode.
		if (fs::exists(fs::symlink_status(built, ec))) {
			AcceptExisting(parent, built);
			continue;
		}
		if (!MakeDirectory(built)) {
			// Created by someone between the check and the call: judge what is there now.
			AcceptExisting(parent, built);
		}
	}
	std::mt19937_64 rng(std::random_device {}());
	for (int attempt = 0; attempt < kAttempts; attempt++) {
		auto leaf = path / PathFromUtf8(SpillLeafPrefix(marker) + RandomSuffix(rng));
		if (MakeDirectory(leaf)) {
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
