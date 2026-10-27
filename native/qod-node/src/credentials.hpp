#pragma once

#include "decide_client.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace qod {

//! One of a user's own S3 credentials (decision 23), as the node installs it: the connection secret
//! UserSecretName(id), of type s3, scoped to `scope`, with every key an s3 secret takes set.
struct UserCredential {
	//! 32 lowercase hex digits.
	std::string id;
	//! s3://bucket/ or s3://bucket/prefix/.
	std::string scope;
	std::string key_id;
	std::string secret;
	//! Empty for none.
	std::string session_token;
	//! us-east-1 when the user set none.
	std::string region;
	//! host[:port]; empty for AWS's own.
	std::string endpoint;
	//! path or vhost: the user's, else vhost for AWS's own endpoint and path for any other.
	std::string url_style;
	bool use_ssl = true;
};

//! A user's credentials as a replica served them, for the digest `creds` a handle carried.
struct CredentialSet {
	std::string creds;
	std::vector<UserCredential> credentials;
};

//! The path every replica serves credentials on (CredentialsRoute, Task 18).
constexpr const char *kCredentialsPath = "/api/internal/authz/credentials";
//! How every user credential secret's name starts; the rest is the first 8 digits of its id.
constexpr const char *kUserSecretPrefix = "qod_uc_";
//! The most credentials one user holds (UserS3Credential.MaxPerUser).
constexpr size_t kMaxUserCredentials = 32;
//! The `creds` a handle carries while its replica cannot serve the principal's set
//! (UserS3Credentials.UnavailableDigest): no set has it, the replica never signs it, so a fetch for it
//! is answered 503 and refused, and the node never installs or keeps anything for it.
constexpr const char *kUnavailableCreds = "0000000000000000000000000000000000000000000000000000000000000000";
//! How long a fetched set is reused for its principal and digest.
constexpr int64_t kMaxCachedCredentialsMs = 5 * 60 * 1000;
//! How many fetched sets the node keeps.
constexpr size_t kMaxCachedCredentialSets = 1024;

//! qod_uc_ and the first 8 digits of `id`.
std::string UserSecretName(const std::string &id);

//! Verifies a replica's answer `text` (`<base64url(JSON)>.<hex HMAC-SHA256(node key, JSON bytes)>`, the
//! verdicts' format) for the digest `creds` the connection's handle carried, and returns what it
//! carries. Throws std::invalid_argument naming the first failed check: the format, the MAC, the version,
//! another digest, the kUnavailableCreds digest, the shape, more than kMaxUserCredentials, a malformed
//! credential, two credentials for one secret name. A message never holds a key, a secret or the
//! answer's text.
CredentialSet VerifyCredentials(const std::string &text, const std::string &node_key, const std::string &creds);

struct CredentialFetch {
	enum class Status { Ok, Refuse, Unavailable };

	Status status = Status::Unavailable;
	//! Why, when not Ok.
	std::string reason;
	CredentialSet set;
	//! The set came from the node's cache: no call was made.
	bool cached = false;

	//! "qod-node: S3 credentials refused: <reason>", or for Unavailable "qod-node: S3 credentials
	//! unavailable, retry: <reason>".
	std::string Message() const;
};

//! Fetches users' credential sets from the replica a handle names (decision 23), verifies them, and
//! keeps each for its principal and digest. The digest names the set (the ids and update times of its
//! credentials, every replace moving the time), so a set kept for it is the one the replica would serve.
//! Thread safe.
class CredentialClient {
public:
	CredentialClient(std::string node_id, std::string node_key, AuthzLimits limits, std::function<int64_t()> clock);

	//! The set `creds` (not empty) names for `principal_id`: from the cache when it was fetched less than
	//! kMaxCachedCredentialsMs ago, else from `replica` with the connection's current handle `handle`,
	//! then kept. Never throws: a transport failure, a timeout, a 503 or another unexpected status is
	//! Unavailable; a 401, a replica URL that is not plain http, or an answer that does not verify is a
	//! Refuse. Neither is kept.
	CredentialFetch Fetch(const std::string &replica, const std::string &handle, const std::string &principal_id,
	                      const std::string &creds);

	//! How many sets are kept.
	size_t CachedSets();

private:
	struct Kept {
		CredentialSet set;
		int64_t until = 0;
	};

	CredentialFetch Ask(const std::string &replica, const std::string &handle, const std::string &creds);

	std::string node_id_;
	std::string node_key_;
	AuthzLimits limits_;
	std::function<int64_t()> clock_;
	std::mutex lock_;
	std::unordered_map<std::string, Kept> kept_;
};

} // namespace qod
