// handle_tool: prints a session handle signed with a node key, for qod-node's process tests (built
// with -DQOD_NODE_TESTS=ON only; never shipped). The manager mints the production ones
// (NodeHandles.scala).
//
//   handle_tool <node key, 64 hex digits> <node id> <principal id> <lifetime ms>

#include "crypto.hpp"

#include <chrono>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>

int main(int argc, char **argv) {
	if (argc != 5) {
		std::cerr << "usage: handle_tool <node key hex> <node id> <principal id> <lifetime ms>" << std::endl;
		return 2;
	}
	std::string key;
	try {
		key = qod::FromHex(argv[1]);
	} catch (std::invalid_argument &error) {
		std::cerr << "handle_tool: " << error.what() << std::endl;
		return 2;
	}
	std::string node = argv[2];
	std::string principal = argv[3];
	auto lifetime = std::stoll(argv[4]);
	auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
	               std::chrono::system_clock::now().time_since_epoch())
	               .count();
	std::random_device random;
	std::string nonce_bytes;
	for (int i = 0; i < 16; i++) {
		nonce_bytes += char(random() & 0xff);
	}
	auto nonce = qod::Hex(reinterpret_cast<const uint8_t *>(nonce_bytes.data()), nonce_bytes.size());
	auto payload = "{\"v\":1,\"node\":\"" + node + "\",\"replica\":\"http://127.0.0.1:0\",\"principal\":{\"id\":\"" +
	               principal + "\",\"name\":\"" + principal +
	               "\",\"tenant\":null,\"superuser\":false,\"system\":false},\"pool\":\"test/test_db/test\"," +
	               "\"policyVersion\":0,\"ctx\":\"" + std::string(64, '0') +
	               "\",\"creds\":\"\",\"path\":\"\",\"nonce\":\"" + nonce +
	               "\",\"exp\":" + std::to_string(now + lifetime) + "}";
	std::cout << qod::ToBase64Url(payload) << "." << qod::Hex(qod::HmacSha256(key, payload)) << std::endl;
	return 0;
}
