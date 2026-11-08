// Tests for the per-node spill folder helper (spill_dir.cpp): the leaf's name, its creation under a
// parent that may not exist yet, and the spill.path record the spawn script sweeps. Built with
// -DQOD_NODE_TESTS=ON, run by CTest.

#include "spill_dir.hpp"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>

namespace fs = std::filesystem;

namespace {

int failures = 0;

void Expect(bool ok, const std::string &what) {
	if (!ok) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

fs::path TempRoot() {
	auto root = fs::temp_directory_path() / ("spill-dir-test-" + std::to_string(std::random_device {}()));
	fs::create_directories(root);
	return root;
}

} // namespace

int main() {
	Expect(qod::SpillLeafPrefix("20900-ab12") == "qod-spill.20900-ab12.", "prefix with marker");
	Expect(qod::SpillLeafPrefix("") == "qod-spill.", "prefix without marker");
	Expect(qod::IsSpillLeafName("qod-spill.20900-ab12.Ab3dE9"), "leaf name with marker");
	Expect(qod::IsSpillLeafName("qod-spill.Ab3dE9"), "leaf name without marker");
	Expect(!qod::IsSpillLeafName("qod-spill.Ab3dE"), "five random characters");
	Expect(!qod::IsSpillLeafName("qod-node.Ab3dE9"), "another prefix");

	auto root = TempRoot();
	auto parent = (root / "fast" / "a" / "b").string();
	auto first = qod::MakePerNodeSpillDir(parent, "20900-ab12");
	auto second = qod::MakePerNodeSpillDir(parent, "20900-ab12");
	Expect(fs::is_directory(first), "the leaf exists");
	Expect(first != second, "two calls give two leaves");
	Expect(fs::path(first).parent_path() == fs::path(parent), "the leaf sits under the parent");
	Expect(qod::IsSpillLeafName(fs::path(first).filename().string()), "the leaf's name matches");
#ifndef _WIN32
	auto perms = fs::status(first).permissions() & fs::perms::all;
	Expect(perms == fs::perms::owner_all, "the leaf is 0700");
	auto created = fs::status(root / "fast").permissions() & fs::perms::all;
	Expect(created == fs::perms::owner_all, "a created parent is 0700");
#endif

	std::ofstream(root / "plain-file") << "x";
	auto blocked = (root / "plain-file" / "under").string();
	try {
		qod::MakePerNodeSpillDir(blocked, "");
		Expect(false, "a component that is a file must fail");
	} catch (std::runtime_error &e) {
		Expect(std::string(e.what()).find("plain-file") != std::string::npos, "the error names the path");
	}

	auto node_dir = root / "node";
	fs::create_directories(node_dir);
	std::ofstream(node_dir / "spill.path") << "/default/qod-node.abcdef";
	qod::RecordSpillDir(node_dir.string(), first);
	std::ifstream in(node_dir / "spill.path");
	std::string line1, line2;
	std::getline(in, line1);
	std::getline(in, line2);
	Expect(line1 == "/default/qod-node.abcdef", "the default line is kept");
	Expect(line2 == first, "the leaf is appended on its own line");

	fs::remove_all(root);
	if (failures == 0) {
		std::cout << "spill_dir: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
