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

#ifndef _WIN32
#include <sys/stat.h>
#endif

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
	auto parent = root / "fast" / "a" / "b";
#ifndef _WIN32
	// A permissive umask: the 0700 below is the call's own, not the process's.
	auto old_umask = ::umask(0);
#endif
	fs::path first = qod::MakePerNodeSpillDir(parent.string(), "20900-ab12");
	fs::path second = qod::MakePerNodeSpillDir(parent.string(), "20900-ab12");
	Expect(fs::is_directory(first), "the leaf exists");
	Expect(first != second, "two calls give two leaves");
	Expect(first.parent_path() == parent, "the leaf sits under the parent");
	Expect(qod::IsSpillLeafName(first.filename().string()), "the leaf's name matches");
#ifndef _WIN32
	auto perms = fs::status(first).permissions() & fs::perms::all;
	Expect(perms == fs::perms::owner_all, "the leaf is 0700");
	for (auto dir : {root / "fast", root / "fast" / "a", parent}) {
		auto created = fs::status(dir).permissions() & fs::perms::all;
		Expect(created == fs::perms::owner_all, "a created component is 0700: " + dir.string());
	}

	// A component that was already there keeps its mode.
	auto pre = root / "pre";
	fs::create_directories(pre);
	fs::permissions(pre, fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
	                         fs::perms::others_read | fs::perms::others_exec,
	                fs::perm_options::replace);
	auto under_pre = qod::MakePerNodeSpillDir((pre / "new").string(), "");
	auto pre_perms = fs::status(pre).permissions() & fs::perms::all;
	Expect(pre_perms == (fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
	                     fs::perms::others_read | fs::perms::others_exec),
	       "a pre-existing ancestor keeps its mode");
	auto new_perms = fs::status(pre / "new").permissions() & fs::perms::all;
	Expect(new_perms == fs::perms::owner_all, "a component created under it is 0700");
	// With no marker the leaf is qod-spill.XXXXXX.
	auto bare = fs::path(under_pre).filename().string();
	Expect(bare.size() == std::string("qod-spill.").size() + 6 && qod::IsSpillLeafName(bare) &&
	           bare.rfind("qod-spill.", 0) == 0,
	       "an empty marker gives qod-spill.XXXXXX: " + bare);

	// A symbolic link at a component is refused and its target is not touched.
	auto target = root / "target";
	fs::create_directories(target);
	fs::permissions(target, fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
	                            fs::perms::others_read | fs::perms::others_exec,
	                fs::perm_options::replace);
	fs::create_directory_symlink(target, parent / "x");
	try {
		qod::MakePerNodeSpillDir((parent / "x" / "y").string(), "");
		Expect(false, "a symbolic link component must be refused");
	} catch (std::runtime_error &e) {
		Expect(std::string(e.what()).find((parent / "x").string()) != std::string::npos,
		       "the error names the link");
	}
	auto target_perms = fs::status(target).permissions() & fs::perms::all;
	Expect(target_perms == (fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
	                        fs::perms::others_read | fs::perms::others_exec),
	       "the link's target keeps its mode");
	Expect(!fs::exists(target / "y"), "nothing is made behind the link");
	::umask(old_umask);
#endif

	for (std::string bad : {"20900/x", "ABC"}) {
		try {
			qod::MakePerNodeSpillDir((root / "marker").string(), bad);
			Expect(false, "marker '" + bad + "' must be refused");
		} catch (std::invalid_argument &e) {
			Expect(std::string(e.what()).find(bad) != std::string::npos, "the error names the marker");
		}
	}
	Expect(!fs::exists(root / "marker"), "a refused marker creates nothing");

	std::ofstream(root / "plain-file") << "x";
	auto blocked = root / "plain-file" / "under";
	try {
		qod::MakePerNodeSpillDir(blocked.string(), "");
		Expect(false, "a component that is a file must fail");
	} catch (std::runtime_error &e) {
		Expect(std::string(e.what()).find("plain-file") != std::string::npos, "the error names the path");
	}

	auto node_dir = root / "node";
	fs::create_directories(node_dir);
	std::ofstream(node_dir / "spill.path") << "/default/qod-node.abcdef";
	qod::RecordSpillDir(node_dir.string(), first.string());
	std::ifstream in(node_dir / "spill.path");
	std::string line1, line2;
	std::getline(in, line1);
	std::getline(in, line2);
	Expect(line1 == "/default/qod-node.abcdef", "the default line is kept");
	Expect(line2 == first.string(), "the leaf is appended on its own line");

	fs::remove_all(root);
	if (failures == 0) {
		std::cout << "spill_dir: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
