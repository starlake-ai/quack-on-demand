#include "handle.hpp"

#include "crypto.hpp"
#include "json.hpp"
#include "wire_text.hpp"

#include <iterator>
#include <stdexcept>

namespace qod {

Handle VerifyHandle(const std::string &text, const std::string &node_key, const std::string &node_id,
                    int64_t now_ms) {
	auto dot = text.find('.');
	if (dot == std::string::npos || text.find('.', dot + 1) != std::string::npos) {
		throw std::invalid_argument("malformed handle");
	}
	auto mac_hex = text.substr(dot + 1);
	if (mac_hex.size() != 64 || !IsLowerHex(mac_hex)) {
		throw std::invalid_argument("malformed handle MAC");
	}
	std::string payload;
	try {
		payload = FromBase64Url(text.substr(0, dot));
	} catch (std::invalid_argument &) {
		throw std::invalid_argument("malformed handle payload");
	}
	auto expected = HmacSha256(node_key, payload);
	if (!ConstantTimeEquals(FromHex(mac_hex), std::string(reinterpret_cast<const char *>(expected.data()), 32))) {
		throw std::invalid_argument("handle MAC does not verify");
	}

	JsonValue json;
	Handle handle;
	try {
		json = ParseJson(payload);
		if (json.Get("v", JsonValue::Kind::INTEGER).integer != kHandleVersion) {
			throw std::invalid_argument("unsupported handle version");
		}
		handle.node = json.Get("node", JsonValue::Kind::STRING).string;
		handle.replica = json.Get("replica", JsonValue::Kind::STRING).string;
		handle.pool = json.Get("pool", JsonValue::Kind::STRING).string;
		handle.policy_version = json.Get("policyVersion", JsonValue::Kind::INTEGER).integer;
		handle.nonce = json.Get("nonce", JsonValue::Kind::STRING).string;
		handle.ctx = json.Get("ctx", JsonValue::Kind::STRING).string;
		handle.creds = json.Get("creds", JsonValue::Kind::STRING).string;
		handle.path = json.Get("path", JsonValue::Kind::STRING).string;
		handle.exp = json.Get("exp", JsonValue::Kind::INTEGER).integer;
		auto &principal = json.Get("principal", JsonValue::Kind::OBJECT);
		handle.principal.id = principal.Get("id", JsonValue::Kind::STRING).string;
		handle.principal.name = principal.Get("name", JsonValue::Kind::STRING).string;
		auto tenant = principal.GetOptional("tenant", JsonValue::Kind::STRING);
		handle.principal.tenant = tenant ? tenant->string : "";
		handle.principal.superuser = principal.Get("superuser", JsonValue::Kind::BOOLEAN).boolean;
		handle.principal.system = principal.Get("system", JsonValue::Kind::BOOLEAN).boolean;
	} catch (std::invalid_argument &error) {
		throw std::invalid_argument(std::string("malformed handle: ") + error.what());
	}
	if (handle.policy_version < 0 || handle.nonce.size() != 32 || !IsLowerHex(handle.nonce) ||
	    handle.principal.id.empty()) {
		throw std::invalid_argument("malformed handle: bad policyVersion, nonce or principal id");
	}
	if (handle.ctx.size() != 64 || !IsLowerHex(handle.ctx)) {
		throw std::invalid_argument("malformed handle: bad ctx");
	}
	if (!handle.creds.empty() && (handle.creds.size() != 64 || !IsLowerHex(handle.creds))) {
		throw std::invalid_argument("malformed handle: bad creds");
	}
	if (handle.node != node_id) {
		throw std::invalid_argument("handle is for another node");
	}
	if (handle.exp <= now_ms) {
		throw std::invalid_argument("handle expired");
	}
	if (handle.exp - now_ms > kMaxHandleLifetimeMs) {
		throw std::invalid_argument("handle is valid too long");
	}
	return handle;
}

void NonceLedger::Sweep(int64_t now_ms) {
	if (now_ms < next_sweep_ms_) {
		return;
	}
	for (auto it = used_.begin(); it != used_.end();) {
		it = it->second <= now_ms ? used_.erase(it) : std::next(it);
	}
	next_sweep_ms_ = now_ms + 10 * 1000;
}

bool NonceLedger::Consume(const std::string &nonce, int64_t exp, int64_t now_ms) {
	std::lock_guard<std::mutex> guard(lock_);
	Sweep(now_ms);
	auto found = used_.find(nonce);
	if (found != used_.end() && found->second > now_ms) {
		return false;
	}
	used_[nonce] = exp;
	return true;
}

bool NonceLedger::Used(const std::string &nonce, int64_t now_ms) {
	std::lock_guard<std::mutex> guard(lock_);
	Sweep(now_ms);
	auto found = used_.find(nonce);
	return found != used_.end() && found->second > now_ms;
}

} // namespace qod
