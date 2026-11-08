#pragma once

#include <string>

namespace qod {

//! "qod-spill.<marker>." or "qod-spill." when the marker is empty: the leaf's name before its 6 random characters.
std::string SpillLeafPrefix(const std::string &marker);

//! True for a leaf name this node could have made: prefix plus 6 of [A-Za-z0-9].
bool IsSpillLeafName(const std::string &name);

//! Creates every missing component of `parent` (mode 0700), then a fresh leaf under it, and returns the
//! leaf's path. Throws std::runtime_error naming the failing path and the OS reason.
std::string MakePerNodeSpillDir(const std::string &parent, const std::string &marker);

//! Appends `leaf` as a line of <node_dir>/spill.path. Throws std::runtime_error on failure.
void RecordSpillDir(const std::string &node_dir, const std::string &leaf);

} // namespace qod
