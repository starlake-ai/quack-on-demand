#include "credentials.hpp"

#include "crypto.hpp"
#include "http_post.hpp"
#include "json.hpp"
#include "json_writer.hpp"

#include <iostream>
#include <set>
#include <stdexcept>

namespace qod {
namespace {

bool IsLowerHex(const std::string &text) {
	for (auto c : text) {
		if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
			return false;
		}
	}
	return !text.empty();
}

//! s3://<bucket>/ or s3://<bucket>/<prefix>/, printable ASCII without blanks: what Task 18's
//! UserS3CredentialRules.normalizeScope stores.
bool IsS3Scope(const std::string &scope) {
	if (scope.size() < 7 || scope.compare(0, 5, "s3://") != 0 || scope[5] == '/' || scope.back() != '/') {
		return false;
	}
	for (auto c : scope) {
		if (c <= 0x20 || c >= 0x7f) {
			return false;
		}
	}
	return true;
}

//! A response body as a refusal may quote it: printable ASCII, at most 200 characters, trimmed.
std::string Quoted(const std::string &body) {
	std::string text;
	for (auto c : body) {
		if (text.size() >= 200) {
			text += "...";
			break;
		}
		text += (c >= 0x20 && c < 0x7f) ? c : ' ';
	}
	auto first = text.find_first_not_of(' ');
	return first == std::string::npos ? "" : text.substr(first, text.find_last_not_of(' ') - first + 1);
}

std::string TrimBlanks(const std::string &text) {
	auto first = text.find_first_not_of(" \t\r\n");
	return first == std::string::npos ? "" : text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}

//! The optional string member `key` of `entry`; empty when null.
std::string OptionalString(const JsonValue &entry, const char *key) {
	auto value = entry.GetOptional(key, JsonValue::Kind::STRING);
	return value ? value->string : "";
}

//! The members of one credential as sent; a missing or mistyped member throws (JsonValue::Get).
UserCredential ReadCredential(const JsonValue &entry) {
	UserCredential credential;
	credential.id = entry.Get("id", JsonValue::Kind::STRING).string;
	credential.scope = entry.Get("scope", JsonValue::Kind::STRING).string;
	credential.key_id = entry.Get("keyId", JsonValue::Kind::STRING).string;
	credential.secret = entry.Get("secret", JsonValue::Kind::STRING).string;
	credential.session_token = OptionalString(entry, "sessionToken");
	credential.region = OptionalString(entry, "region");
	credential.endpoint = OptionalString(entry, "endpoint");
	credential.url_style = OptionalString(entry, "urlStyle");
	credential.use_ssl = entry.Get("useSsl", JsonValue::Kind::BOOLEAN).boolean;
	return credential;
}

//! Checks `credential` and fills its defaults.
void Settle(UserCredential &credential) {
	if (credential.id.size() != 32 || !IsLowerHex(credential.id)) {
		throw std::invalid_argument("malformed credential id");
	}
	if (!IsS3Scope(credential.scope)) {
		throw std::invalid_argument("credential scope is not an s3:// prefix");
	}
	if (credential.key_id.empty()) {
		throw std::invalid_argument("credential has no key id");
	}
	if (credential.secret.empty()) {
		throw std::invalid_argument("credential has no secret");
	}
	if (!credential.url_style.empty() && credential.url_style != "path" && credential.url_style != "vhost") {
		throw std::invalid_argument("credential URL style is not path or vhost");
	}
	if (credential.region.empty()) {
		credential.region = "us-east-1";
	}
	if (credential.url_style.empty()) {
		credential.url_style = credential.endpoint.empty() ? "vhost" : "path";
	}
}

CredentialFetch Refused(std::string reason) {
	CredentialFetch fetch;
	fetch.status = CredentialFetch::Status::Refuse;
	fetch.reason = std::move(reason);
	return fetch;
}

CredentialFetch Unavailable(std::string reason) {
	CredentialFetch fetch;
	fetch.status = CredentialFetch::Status::Unavailable;
	fetch.reason = std::move(reason);
	return fetch;
}

} // namespace

std::string UserSecretName(const std::string &id) {
	return kUserSecretPrefix + id.substr(0, 8);
}

CredentialSet VerifyCredentials(const std::string &text, const std::string &node_key, const std::string &creds) {
	auto dot = text.find('.');
	if (dot == std::string::npos || text.find('.', dot + 1) != std::string::npos) {
		throw std::invalid_argument("malformed answer");
	}
	auto mac_hex = text.substr(dot + 1);
	if (mac_hex.size() != 64 || !IsLowerHex(mac_hex)) {
		throw std::invalid_argument("malformed answer MAC");
	}
	std::string bytes;
	try {
		bytes = FromBase64Url(text.substr(0, dot));
	} catch (std::invalid_argument &) {
		throw std::invalid_argument("malformed answer");
	}
	auto expected = HmacSha256(node_key, bytes);
	if (!ConstantTimeEquals(FromHex(mac_hex), std::string(reinterpret_cast<const char *>(expected.data()), 32))) {
		throw std::invalid_argument("answer MAC does not verify");
	}
	JsonValue json;
	try {
		json = ParseJson(bytes);
	} catch (std::invalid_argument &) {
		// Never the parser's message: it may quote the text, which holds secrets.
		throw std::invalid_argument("malformed answer");
	}
	CredentialSet set;
	const std::vector<JsonValue> *entries = nullptr;
	try {
		if (json.Get("v", JsonValue::Kind::INTEGER).integer != 1) {
			throw std::invalid_argument("unsupported answer version");
		}
		set.creds = json.Get("creds", JsonValue::Kind::STRING).string;
		entries = &json.Get("credentials", JsonValue::Kind::ARRAY).array;
	} catch (std::invalid_argument &error) {
		std::string what = error.what();
		throw std::invalid_argument(what == "unsupported answer version" ? what : "malformed answer: " + what);
	}
	if (set.creds != creds) {
		throw std::invalid_argument("answer is for another credential set");
	}
	if (set.creds == kUnavailableCreds) {
		throw std::invalid_argument("answer is for no credential set");
	}
	if (entries->size() > kMaxUserCredentials) {
		throw std::invalid_argument("answer holds more than 32 credentials");
	}
	for (auto &entry : *entries) {
		try {
			set.credentials.push_back(ReadCredential(entry));
		} catch (std::invalid_argument &error) {
			throw std::invalid_argument(std::string("malformed answer: ") + error.what());
		}
	}
	std::set<std::string> names;
	for (auto &credential : set.credentials) {
		Settle(credential);
		if (!names.insert(UserSecretName(credential.id)).second) {
			throw std::invalid_argument("two credentials share a secret name");
		}
	}
	return set;
}

std::string CredentialFetch::Message() const {
	if (status == Status::Unavailable) {
		return "qod-node: S3 credentials unavailable, retry: " + reason;
	}
	return "qod-node: S3 credentials refused: " + reason;
}

CredentialClient::CredentialClient(std::string node_id, std::string node_key, AuthzLimits limits,
                                   std::function<int64_t()> clock)
    : node_id_(std::move(node_id)), node_key_(std::move(node_key)), limits_(limits), clock_(std::move(clock)) {
}

CredentialFetch CredentialClient::Fetch(const std::string &replica, const std::string &handle,
                                        const std::string &principal_id, const std::string &creds) {
	auto key = principal_id + "\n" + creds;
	auto now = clock_();
	{
		std::lock_guard<std::mutex> guard(lock_);
		auto found = kept_.find(key);
		if (found != kept_.end()) {
			if (found->second.until > now) {
				CredentialFetch hit;
				hit.status = CredentialFetch::Status::Ok;
				hit.set = found->second.set;
				hit.cached = true;
				return hit;
			}
			kept_.erase(found);
		}
	}
	auto fetch = Ask(replica, handle, creds);
	if (fetch.status == CredentialFetch::Status::Ok) {
		std::lock_guard<std::mutex> guard(lock_);
		if (kept_.size() >= kMaxCachedCredentialSets) {
			// The set whose time is up first goes: a scan of at most kMaxCachedCredentialSets entries.
			auto first = kept_.begin();
			for (auto it = kept_.begin(); it != kept_.end(); ++it) {
				if (it->second.until < first->second.until) {
					first = it;
				}
			}
			kept_.erase(first);
		}
		kept_[key] = Kept {fetch.set, now + kMaxCachedCredentialsMs};
	}
	return fetch;
}

size_t CredentialClient::CachedSets() {
	std::lock_guard<std::mutex> guard(lock_);
	return kept_.size();
}

CredentialFetch CredentialClient::Ask(const std::string &replica, const std::string &handle,
                                      const std::string &creds) {
	HttpUrl url;
	try {
		url = ParseHttpUrl(replica);
	} catch (std::invalid_argument &error) {
		return Refused(std::string("the handle's replica cannot be called (") + error.what() + ")");
	}
	auto body = CanonicalJson(JsonObject({{"handle", JsonString(handle)}}));
	HttpReply reply;
	try {
		reply = HttpPost(url, url.path + kCredentialsPath,
		                 {{"Content-Type", "application/json"},
		                  {"X-QoD-Node", node_id_},
		                  {"X-QoD-Signature", Hex(HmacSha256(node_key_, body))}},
		                 body, limits_.decide_timeout_ms);
	} catch (std::exception &error) {
		std::cerr << "[qod-node] credentials call to " << url.authority << " failed: " << error.what() << std::endl;
		return Unavailable("the replica could not be reached");
	}
	if (reply.status == 401) {
		return Refused(Quoted(reply.body));
	}
	if (reply.status == 503) {
		auto said = Quoted(reply.body);
		return Unavailable(said.empty() ? "the replica could not answer" : said);
	}
	if (reply.status != 200) {
		std::cerr << "[qod-node] credentials call to " << url.authority << " answered HTTP " << reply.status
		          << std::endl;
		return Unavailable("the replica could not answer");
	}
	try {
		CredentialFetch fetch;
		fetch.status = CredentialFetch::Status::Ok;
		fetch.set = VerifyCredentials(TrimBlanks(reply.body), node_key_, creds);
		return fetch;
	} catch (std::invalid_argument &error) {
		return Refused(std::string("the answer does not verify: ") + error.what());
	}
}

} // namespace qod
