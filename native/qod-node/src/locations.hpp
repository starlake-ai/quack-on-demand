#pragma once

#include <optional>
#include <string>
#include <vector>

namespace qod {

//! A file or object location a statement names, as the parse screen sends it in a screen description.
struct Location {
	//! As the statement spells it.
	std::string given;
	//! An s3:// URL unchanged; a local path as ResolveLocalPath resolves it.
	std::string resolved;
	//! "read" or "write".
	std::string access;
	//! The reader function's name, or copy_from / copy_to.
	std::string via;
};

//! The real location of the absolute local path `path` (decision 15), or nullopt with `why` (the
//! refusal's text after the path) when the screen refuses it: not absolute (POSIX `/...`; Windows a
//! drive letter, `:`, a separator), an empty, '.' or '..' segment (one trailing separator is allowed),
//! a NUL, on Windows a segment holding ':' (an alternate stream), ending in '.' or ' ', or naming a
//! device. Otherwise the real path of its deepest existing ancestor, every symbolic link on the way
//! followed, joined with the rest as given with the platform separator; a trailing separator is kept.
//! A link that leads nowhere, or an ancestor this node cannot resolve, is refused.
std::optional<std::string> ResolveLocalPath(const std::string &path, std::string *why = nullptr);

//! `given` as the screen sends it, or nullopt with `why`: an s3:// URL (scheme in any case) that names
//! a bucket, unchanged; an absolute local path, resolved; any other scheme refused.
std::optional<Location> MakeLocation(const std::string &given, const std::string &access, const std::string &via,
                                     std::string &why);

//! The screen description v1, {"locations":[...],"v":1}, as canonical JSON: the locations sorted by
//! their canonical bytes, duplicates dropped.
std::string ScreenDescription(const std::vector<Location> &locations);

//! The scope of a node S3 secret DuckDB would sign `url` with over the user's own (decision 23): one of
//! `node_scopes` that `url` starts with and that is longer than every one of `user_scopes` that `url`
//! starts with (DuckDB picks the longest scope, and a connection secret wins a tie). Empty when none
//! does; an empty scope never does.
std::string ShadowingScope(const std::string &url, const std::vector<std::string> &user_scopes,
                           const std::vector<std::string> &node_scopes);

} // namespace qod
