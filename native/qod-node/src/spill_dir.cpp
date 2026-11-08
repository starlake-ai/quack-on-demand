#include "spill_dir.hpp"

#include "utf8_args.hpp"

#include <filesystem>
#include <fstream>
#include <random>
#include <regex>
#include <stdexcept>
#include <system_error>

namespace fs = std::filesystem;

namespace qod {

namespace {

constexpr int kRandomChars = 6;
constexpr int kAttempts = 64;

//! A path as UTF-8 text (the inverse of PathFromUtf8).
std::string Utf8(const fs::path &path) {
#if defined(__cpp_char8_t)
	auto text = path.u8string();
	return std::string(text.begin(), text.end());
#else
	return path.u8string();
#endif
}

void MakeOwnerOnly(const fs::path &dir) {
	std::error_code ec;
	fs::permissions(dir, fs::perms::owner_all, fs::perm_options::replace, ec);
	if (ec) {
		throw std::runtime_error("cannot restrict " + Utf8(dir) + " to its owner: " + ec.message());
	}
}

std::string RandomSuffix(std::mt19937_64 &rng) {
	static const char kAlphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	std::uniform_int_distribution<size_t> pick(0, sizeof(kAlphabet) - 2);
	std::string out;
	for (int i = 0; i < kRandomChars; i++) {
		out.push_back(kAlphabet[pick(rng)]);
	}
	return out;
}

} // namespace

std::string SpillLeafPrefix(const std::string &marker) {
	return marker.empty() ? "qod-spill." : "qod-spill." + marker + ".";
}

bool IsSpillLeafName(const std::string &name) {
	static const std::regex kLeaf(R"(^qod-spill\.([0-9]+-[0-9a-f]+\.)?[A-Za-z0-9]{6}$)");
	return std::regex_match(name, kLeaf);
}

std::string MakePerNodeSpillDir(const std::string &parent, const std::string &marker) {
	fs::path path = PathFromUtf8(parent);
	fs::path built;
	for (const auto &part : path) {
		built /= part;
		std::error_code ec;
		if (fs::is_directory(built, ec)) {
			continue;
		}
		if (fs::exists(built, ec)) {
			throw std::runtime_error("cannot create the spill directory under " + parent + ": " + Utf8(built) +
			                         " exists and is not a directory");
		}
		if (!fs::create_directory(built, ec) && ec) {
			throw std::runtime_error("cannot create " + Utf8(built) + ": " + ec.message());
		}
		MakeOwnerOnly(built);
	}
	std::mt19937_64 rng(std::random_device {}());
	for (int attempt = 0; attempt < kAttempts; attempt++) {
		auto leaf = path / PathFromUtf8(SpillLeafPrefix(marker) + RandomSuffix(rng));
		std::error_code ec;
		if (fs::create_directory(leaf, ec)) {
			MakeOwnerOnly(leaf);
			return Utf8(leaf);
		}
		if (ec) {
			throw std::runtime_error("cannot create a spill directory under " + parent + ": " + ec.message());
		}
	}
	throw std::runtime_error("cannot create a spill directory under " + parent + ": no free name");
}

void RecordSpillDir(const std::string &node_dir, const std::string &leaf) {
	std::ofstream out(PathFromUtf8(node_dir) / "spill.path", std::ios::app);
	out << "\n" << leaf;
	if (!out) {
		throw std::runtime_error("cannot record the spill directory in " + node_dir);
	}
}

} // namespace qod
