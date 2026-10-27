#include "verdict.hpp"

#include "crypto.hpp"
#include "json.hpp"

#include <algorithm>
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

} // namespace

Verdict VerifyVerdict(const std::string &text, const std::string &node_key, const VerdictExpectation &expected,
                      int64_t now_ms) {
	auto dot = text.find('.');
	if (dot == std::string::npos || text.find('.', dot + 1) != std::string::npos) {
		throw std::invalid_argument("malformed verdict");
	}
	auto mac_hex = text.substr(dot + 1);
	if (mac_hex.size() != 64 || !IsLowerHex(mac_hex)) {
		throw std::invalid_argument("malformed verdict MAC");
	}
	std::string payload;
	try {
		payload = FromBase64Url(text.substr(0, dot));
	} catch (std::invalid_argument &) {
		throw std::invalid_argument("malformed verdict payload");
	}
	auto mac = HmacSha256(node_key, payload);
	if (!ConstantTimeEquals(FromHex(mac_hex), std::string(reinterpret_cast<const char *>(mac.data()), mac.size()))) {
		throw std::invalid_argument("verdict MAC does not verify");
	}

	Verdict verdict;
	auto no_extras = false;
	try {
		auto json = ParseJson(payload);
		if (json.Get("v", JsonValue::Kind::INTEGER).integer != 1) {
			throw std::invalid_argument("unsupported verdict version");
		}
		verdict.kind = json.Get("kind", JsonValue::Kind::STRING).string;
		auto &decision = json.Get("decision", JsonValue::Kind::STRING).string;
		if (decision != "allow" && decision != "refuse") {
			throw std::invalid_argument("unknown decision '" + decision + "'");
		}
		verdict.allow = decision == "allow";
		verdict.reason = json.Get("reason", JsonValue::Kind::STRING).string;
		verdict.retryable = json.Get("retryable", JsonValue::Kind::BOOLEAN).boolean;
		verdict.principal = json.Get("principal", JsonValue::Kind::STRING).string;
		verdict.policy_version = json.Get("policyVersion", JsonValue::Kind::INTEGER).integer;
		verdict.ctx = json.Get("ctx", JsonValue::Kind::STRING).string;
		verdict.fingerprint = json.Get("fingerprint", JsonValue::Kind::STRING).string;
		verdict.expires_at = json.Get("expiresAt", JsonValue::Kind::INTEGER).integer;
		no_extras = json.Get("rowFilters", JsonValue::Kind::ARRAY).array.empty() &&
		            json.Get("masks", JsonValue::Kind::ARRAY).array.empty();
	} catch (std::invalid_argument &error) {
		throw std::invalid_argument(std::string("malformed verdict: ") + error.what());
	}
	if (verdict.kind != expected.kind) {
		throw std::invalid_argument("verdict is for another kind of description");
	}
	if (verdict.fingerprint != expected.fingerprint) {
		throw std::invalid_argument("verdict is for another description");
	}
	if (verdict.principal != expected.principal) {
		throw std::invalid_argument("verdict is for another principal");
	}
	if (verdict.policy_version != expected.policy_version) {
		throw std::invalid_argument("verdict is for another policy version");
	}
	if (verdict.ctx != expected.ctx) {
		throw std::invalid_argument("verdict is for another session context");
	}
	if (verdict.kind != "screen" && verdict.expires_at <= now_ms) {
		throw std::invalid_argument("verdict expired");
	}
	if (!no_extras) {
		throw std::invalid_argument("verdict carries row filters or masks, which this node does not enforce");
	}
	return verdict;
}

VerdictCache::VerdictCache(size_t capacity) : capacity_(capacity) {
}

std::string VerdictCache::Key(const std::string &principal, int64_t policy_version, const std::string &ctx,
                              const std::string &fingerprint) {
	// The id is length-prefixed, so no id can run into the fields after it.
	return std::to_string(principal.size()) + ":" + principal + "|" + std::to_string(policy_version) + "|" + ctx +
	       "|" + fingerprint;
}

bool VerdictCache::Find(const std::string &principal, int64_t policy_version, const std::string &ctx,
                        const std::string &fingerprint, int64_t now_ms, Verdict &found) {
	std::lock_guard<std::mutex> guard(lock_);
	auto it = index_.find(Key(principal, policy_version, ctx, fingerprint));
	if (it == index_.end()) {
		return false;
	}
	if (it->second->until <= now_ms) {
		entries_.erase(it->second);
		index_.erase(it);
		return false;
	}
	entries_.splice(entries_.begin(), entries_, it->second);
	found = it->second->verdict;
	return true;
}

void VerdictCache::Put(const Verdict &verdict, int64_t now_ms) {
	if (capacity_ == 0 || verdict.kind != "plan" || (!verdict.allow && verdict.retryable) ||
	    verdict.expires_at <= now_ms) {
		return;
	}
	auto until = std::min(verdict.expires_at, now_ms + kMaxCachedVerdictMs);
	auto key = Key(verdict.principal, verdict.policy_version, verdict.ctx, verdict.fingerprint);
	std::lock_guard<std::mutex> guard(lock_);
	auto it = index_.find(key);
	if (it != index_.end()) {
		entries_.erase(it->second);
		index_.erase(it);
	}
	entries_.push_front(Entry {key, verdict, until});
	index_[key] = entries_.begin();
	while (entries_.size() > capacity_) {
		index_.erase(entries_.back().key);
		entries_.pop_back();
	}
}

size_t VerdictCache::Size() {
	std::lock_guard<std::mutex> guard(lock_);
	return entries_.size();
}

} // namespace qod
