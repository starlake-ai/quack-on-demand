#include "secrets.hpp"

#include "crypto.hpp"
#include "utf8_args.hpp"

#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <system_error>

namespace qod {

std::string ReadSecret(const std::string &dir, const std::string &key) {
	auto path = PathFromUtf8(dir) / key;
	std::ifstream in(path, std::ios::binary);
	if (!in) {
		throw std::runtime_error("the secrets directory has no " + key);
	}
	std::stringstream buffer;
	buffer << in.rdbuf();
	auto value = buffer.str();
	while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) {
		value.pop_back();
	}
	if (value.empty()) {
		throw std::runtime_error("the secrets directory's " + key + " is empty");
	}
	return value;
}

std::string ReadNodeKey(const std::string &dir) {
	auto hex = ReadSecret(dir, "QOD_NODE_AUTHZ_KEY");
	bool lower_hex = hex.size() == 64;
	for (auto c : hex) {
		lower_hex = lower_hex && ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
	}
	if (!lower_hex) {
		throw std::runtime_error("the secrets directory's QOD_NODE_AUTHZ_KEY is not 64 lowercase hex digits");
	}
	return FromHex(hex);
}

std::string ReadInitNonce(const std::string &dir) {
	auto nonce = ReadSecret(dir, "QOD_INIT_NONCE");
	bool lower_hex = nonce.size() >= 32 && nonce.size() <= 128;
	for (auto c : nonce) {
		lower_hex = lower_hex && ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
	}
	if (!lower_hex) {
		throw std::runtime_error("the secrets directory's QOD_INIT_NONCE is not 32 to 128 lowercase hex digits");
	}
	return nonce;
}

void DisposeSecrets(const std::string &dir, bool ephemeral) {
	if (!ephemeral || dir.empty()) {
		return;
	}
	std::error_code ignored;
	std::filesystem::remove_all(PathFromUtf8(dir), ignored);
}

} // namespace qod
