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
#include <unistd.h>
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
	Expect(qod::IsSpillMarker(""), "no marker");
	Expect(qod::IsSpillMarker("20900-ab12"), "a marker");
	Expect(!qod::IsSpillMarker("20900-AB12"), "uppercase hex");
	Expect(!qod::IsSpillMarker("20900"), "no boot id");
	Expect(!qod::IsSpillMarker("x-ab12"), "a port that is not digits");
	Expect(!qod::IsSpillMarker("20900-ab12/.."), "a marker holding a path");
	Expect(qod::IsSpillLeafName("qod-spill.20900-ab12.Ab3dE9"), "leaf name with marker");
	Expect(qod::IsSpillLeafName("qod-spill.Ab3dE9"), "leaf name without marker");
	Expect(!qod::IsSpillLeafName("qod-spill.Ab3dE"), "five random characters");
	Expect(!qod::IsSpillLeafName("qod-node.Ab3dE9"), "another prefix");

	// Only root and the node's own user own a link that may be followed.
	Expect(qod::TrustedLinkOwner(0, 501), "root's link is followed");
	Expect(qod::TrustedLinkOwner(501, 501), "the node user's link is followed");
	Expect(!qod::TrustedLinkOwner(502, 501), "another user's link is refused");
	Expect(!qod::TrustedLinkOwner(1, 0) , "a third user's link is refused even for a root node");

	auto root = TempRoot();
	std::ofstream(root / "plain-file") << "x";
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

	// A symbolic link of this user's own (an operator's /fastdisk -> /mnt/ssd) is followed, the target
	// keeps its mode and the leaf lands under it.
	auto target = root / "target";
	fs::create_directories(target);
	const auto kOpen = fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec |
	                   fs::perms::others_read | fs::perms::others_exec;
	fs::permissions(target, kOpen, fs::perm_options::replace);
	fs::create_directory_symlink(target, parent / "x");
	auto through = fs::path(qod::MakePerNodeSpillDir((parent / "x" / "y").string(), ""));
	Expect(through.parent_path() == parent / "x" / "y" && fs::is_directory(target / "y" / through.filename()),
	       "the leaf lands under the link's target");
	Expect((fs::status(target).permissions() & fs::perms::all) == kOpen, "the link's target keeps its mode");

	// A link leading to a file, or nowhere, is refused naming the link.
	fs::create_symlink(root / "plain-file", parent / "to-file");
	fs::create_symlink(root / "nowhere", parent / "dangling");
	for (auto name : {"to-file", "dangling"}) {
		try {
			qod::MakePerNodeSpillDir((parent / name / "y").string(), "");
			Expect(false, std::string("link ") + name + " must be refused");
		} catch (std::runtime_error &e) {
			Expect(std::string(e.what()).find((parent / name).string()) != std::string::npos,
			       std::string("the error names the link ") + name);
		}
	}
	// Components owned by another user are refused, directories as well as links. Needs root to chown.
	if (::geteuid() == 0) {
		auto foreign_dir = root / "foreign-dir";
		fs::create_directories(foreign_dir);
		auto foreign_link = root / "foreign-link";
		fs::create_directory_symlink(target, foreign_link);
		Expect(::chown(foreign_dir.c_str(), 1, 1) == 0 && ::lchown(foreign_link.c_str(), 1, 1) == 0,
		       "chown to uid 1");
		// A root-owned link to a directory that another user owns.
		auto foreign_target = root / "foreign-target";
		fs::create_directories(foreign_target);
		auto link_to_foreign = root / "link-to-foreign";
		fs::create_directory_symlink(foreign_target, link_to_foreign);
		Expect(::chown(foreign_target.c_str(), 1, 1) == 0, "chown the target to uid 1");
		auto spill_entries = [&](const fs::path &dir) {
			int count = 0;
			std::error_code ec;
			for (fs::recursive_directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
				count += it->path().filename().string().rfind("qod-spill.", 0) == 0 ? 1 : 0;
			}
			return count;
		};
		for (auto dir : {foreign_dir, foreign_link, link_to_foreign}) {
			auto before = spill_entries(root) + spill_entries(foreign_target);
			try {
				qod::MakePerNodeSpillDir((dir / "y").string(), "");
				Expect(false, "a component owned by another user must be refused: " + dir.string());
			} catch (std::runtime_error &e) {
				std::string what = e.what();
				Expect(what.find(dir.string()) != std::string::npos &&
				           what.find("owned by another user") != std::string::npos,
				       "the error names the component and its owner: " + what);
				if (dir == link_to_foreign) {
					Expect(what.find(foreign_target.string()) != std::string::npos,
					       "the error names the link's target: " + what);
				}
			}
			Expect(spill_entries(root) + spill_entries(foreign_target) == before,
			       "no qod-spill entry is made behind " + dir.string());
		}
		Expect(!fs::exists(foreign_dir / "y") && !fs::exists(foreign_target / "y"), "no parent is made behind them");
		Expect((fs::status(target).permissions() & fs::perms::all) == kOpen, "the targets keep their mode");
	} else {
		std::cout << "spill_dir: skipped the foreign-owner case (needs root to chown)" << std::endl;
	}
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

	// spill.path is line-based: a parent holding a line break is refused before anything is created,
	// and the error quotes nothing of the value.
	for (std::string bad : {"\n", "\r", "\r\n"}) {
		auto parent = (root / ("broken" + bad + "secretpart")).string();
		try {
			qod::MakePerNodeSpillDir(parent, "");
			Expect(false, "a parent holding a line break must be refused");
		} catch (std::invalid_argument &e) {
			std::string what = e.what();
			Expect(what.find("holds a line break") != std::string::npos, "the error says why");
			Expect(what.find("secretpart") == std::string::npos && what.find("broken") == std::string::npos,
			       "the error names nothing of the value");
		}
		Expect(!fs::exists(root / "broken"), "a refused parent creates nothing");
	}

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
