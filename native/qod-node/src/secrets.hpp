#pragma once

#include <string>

namespace qod {

//! Reads key from the node's secrets directory (one file per key, as NodeSecretFiles writes them).
//! A trailing newline is dropped. Throws std::runtime_error naming the key if the file is missing or
//! empty; the value never appears in an error.
std::string ReadSecret(const std::string &dir, const std::string &key);

//! This node's 32-byte key, from QOD_NODE_AUTHZ_KEY (64 lowercase hex digits; the manager derives it
//! from its node-key secret and the node id). Every session handle the node accepts is signed with
//! it. Throws std::runtime_error naming the key, never its value, when missing or malformed.
std::string ReadNodeKey(const std::string &dir);

//! Removes the secrets directory when it is ephemeral (the local and fleet launchers' private
//! per-node directory). A Kubernetes Secret volume is not ephemeral and is left alone.
void DisposeSecrets(const std::string &dir, bool ephemeral);

} // namespace qod
