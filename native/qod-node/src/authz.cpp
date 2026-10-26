#include "authz.hpp"

#include <chrono>
#include <cstring>
#include <iterator>
#include <stdexcept>

namespace qod {
namespace {

bool IsHandleCharacter(char c) {
	return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ||
	       c == '.';
}

const std::string kRefused = kHandleRefused;

} // namespace

int64_t SystemNowMs() {
	return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
	    .count();
}

std::string PreludeHandle(const std::string &sql) {
	auto prefix = std::strlen(kPreludePrefix);
	auto suffix = std::strlen(kPreludeSuffix);
	if (sql.size() <= prefix + suffix || sql.compare(0, prefix, kPreludePrefix) != 0 ||
	    sql.compare(sql.size() - suffix, suffix, kPreludeSuffix) != 0) {
		return "";
	}
	auto handle = sql.substr(prefix, sql.size() - prefix - suffix);
	for (auto c : handle) {
		if (!IsHandleCharacter(c)) {
			return "";
		}
	}
	return handle;
}

std::string RefreshRefusal(const Installed *current, const Handle &next) {
	if (!current) {
		return "";
	}
	if (next.principal != current->principal) {
		return "it names another principal than the one this connection runs for";
	}
	if (next.policy_version < current->policy_version) {
		return "it carries an older policy version than this connection's";
	}
	return "";
}

NodeAuthz::NodeAuthz(std::string node_id, std::string node_key, Clock clock)
    : node_id_(std::move(node_id)), node_key_(std::move(node_key)), clock_(std::move(clock)) {
}

Handle NodeAuthz::Verify(const std::string &text) const {
	return VerifyHandle(text, node_key_, node_id_, Now());
}

bool NodeAuthz::Consume(const Handle &handle) {
	return ledger_.Consume(handle.nonce, handle.exp, Now());
}

void NodeAuthz::Sweep(int64_t now_ms) {
	if (now_ms < next_sweep_ms_) {
		return;
	}
	for (auto it = connections_.begin(); it != connections_.end();) {
		it = it->second.exp <= now_ms ? connections_.erase(it) : std::next(it);
	}
	next_sweep_ms_ = now_ms + 10 * 1000;
}

std::string NodeAuthz::Screen(const std::string &connection_id, const std::string &sql) {
	auto now = Now();
	auto text = PreludeHandle(sql);
	Handle handle;
	if (!text.empty()) {
		try {
			handle = Verify(text);
		} catch (std::invalid_argument &error) {
			return kRefused + error.what();
		}
		if (ledger_.Used(handle.nonce, now)) {
			return kRefused + "it was already used";
		}
	}
	std::lock_guard<std::mutex> guard(lock_);
	Sweep(now);
	auto found = connections_.find(connection_id);
	const Installed *current = found != connections_.end() && found->second.exp > now ? &found->second : nullptr;
	if (text.empty()) {
		return current ? "" : kNoPrincipal;
	}
	auto refusal = RefreshRefusal(current, handle);
	if (!refusal.empty()) {
		return kRefused + refusal;
	}
	connections_[connection_id] = Installed {handle.principal, handle.policy_version, handle.exp, handle.nonce, handle.ctx};
	return "";
}

} // namespace qod
