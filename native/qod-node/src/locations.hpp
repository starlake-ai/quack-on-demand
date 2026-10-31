#pragma once

#include <cstdint>
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

//! One scope of one of the node's own secrets, with the tie-break offset of the storage that holds it.
struct NodeSecretScope {
	//! Empty for a secret that matches every path (an empty SCOPE, or none at all).
	std::string scope;
	int64_t storage_offset = 0;
};

//! The tie-break offset DuckDB gives the secret storage `storage` (SecretStorage::*_STORAGE_OFFSET,
//! secret_storage.hpp at v2.0.0): transaction 0, connection 5, memory 10, local_file 20. A storage
//! it does not know (an extension's) gets 0, the strongest, so the shadow check fails closed.
int64_t SecretStorageOffset(const std::string &storage);

//! The user's S3 credentials are connection secrets (offset 5).
constexpr int64_t kUserSecretStorageOffset = 5;

//! The scope of a node secret DuckDB would sign the s3:// URL `url` with over the user's own (decision
//! 23), or nullopt when none would. DuckDB scores a secret 100 * (length of its longest scope `url`
//! starts with, 0 for an empty scope) - its storage's offset, takes the highest score, and breaks an
//! equal score on the secret name (SecretStorage::SelectBestMatch, SecretManager::LookupSecret). The
//! user's score is the best of `user_scopes` at kUserSecretStorageOffset, none when none matches. A
//! node scope shadows when its score is at least the user's: an equal score would be broken on names
//! the screen does not compare, so it fails closed. With the built-in storages that means a longer
//! scope (memory, local_file), or an equally long one in transaction storage.
std::optional<std::string> ShadowingScope(const std::string &url, const std::vector<std::string> &user_scopes,
                                          const std::vector<NodeSecretScope> &node_scopes);

} // namespace qod
