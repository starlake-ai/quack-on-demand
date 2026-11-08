#pragma once

#include <string>

namespace qod {

//! "qod-spill.<marker>." or "qod-spill." when the marker is empty: the leaf's name before its 6 random characters.
std::string SpillLeafPrefix(const std::string &marker);

//! True for a manager marker a leaf name may carry: empty, or <digits>-<lowercase hex> (the spawn
//! scripts' <rest-port>-<boot id>).
bool IsSpillMarker(const std::string &marker);

//! True for a leaf name this node could have made: prefix plus 6 of [A-Za-z0-9].
bool IsSpillLeafName(const std::string &name);

//! Whether a pre-existing symbolic link in the parent path may be followed: it is owned by root or by the
//! node's own user (`self`). A link owned by anyone else could only have been planted in a shared folder.
bool TrustedLinkOwner(unsigned owner, unsigned self);

#ifndef _WIN32
//! Follows absolute `path` component by component, as the kernel resolves it, and judges every directory
//! and every symbolic link it passes through (each lstat), the folders above a link's target and the links
//! inside it included, with TrustedLinkOwner against `self`. Returns "" when all pass, else why the first
//! that does not: "<path> is a directory owned by another user (uid N): ..." (or a link, or a component
//! that cannot be read).
std::string ForeignOwnerOnPath(const std::string &path, unsigned self);
#endif

//! Creates every missing component of `parent` (mode 0700), then a fresh leaf under it, and returns the
//! leaf's path. On POSIX the leaf's real path is then judged by ForeignOwnerOnPath, and the leaf removed
//! when it fails. Throws std::runtime_error naming the failing path and the OS reason, and
//! std::invalid_argument for a marker IsSpillMarker refuses or a parent holding a line break.
std::string MakePerNodeSpillDir(const std::string &parent, const std::string &marker);

//! Appends `leaf` as a line of <node_dir>/spill.path. Throws std::runtime_error on failure.
void RecordSpillDir(const std::string &node_dir, const std::string &leaf);

} // namespace qod
