// Unit tests for crypto: SHA-256 (FIPS 180-4 examples), HMAC-SHA256 (RFC 4231), hex and base64url.
// Built with -DQOD_NODE_TESTS=ON and run by CTest.

#include "crypto.hpp"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

template <class F>
void ExpectThrows(F f, const std::string &what) {
	try {
		f();
		std::cerr << "FAIL: no exception: " << what << std::endl;
		failures++;
	} catch (std::invalid_argument &) {
	}
}

} // namespace

int main() {
	using qod::Hex;
	Expect(Hex(qod::Sha256("")) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "sha256 empty");
	Expect(Hex(qod::Sha256("abc")) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256 abc");
	Expect(Hex(qod::Sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")) ==
	           "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
	       "sha256 two blocks");
	Expect(Hex(qod::Sha256(std::string(1000000, 'a'))) ==
	           "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
	       "sha256 a million a");
	// 55 and 56 bytes: the length field fits the last block, then it does not.
	Expect(Hex(qod::Sha256(std::string(55, 'a'))) ==
	           "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
	       "sha256 55 bytes");
	Expect(Hex(qod::Sha256(std::string(56, 'a'))) ==
	           "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
	       "sha256 56 bytes");

	// RFC 4231 test cases 1, 2, 3 and 6 (a key longer than the block).
	Expect(Hex(qod::HmacSha256(std::string(20, '\x0b'), "Hi There")) ==
	           "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
	       "hmac case 1");
	Expect(Hex(qod::HmacSha256("Jefe", "what do ya want for nothing?")) ==
	           "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
	       "hmac case 2");
	Expect(Hex(qod::HmacSha256(std::string(20, '\xaa'), std::string(50, '\xdd'))) ==
	           "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
	       "hmac case 3");
	Expect(Hex(qod::HmacSha256(std::string(131, '\xaa'), "Test Using Larger Than Block-Size Key - Hash Key First")) ==
	           "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
	       "hmac case 6");

	Expect(qod::FromHex("00ff10Ab") == std::string("\x00\xff\x10\xab", 4), "fromhex");
	ExpectThrows([] { qod::FromHex("abc"); }, "fromhex odd");
	ExpectThrows([] { qod::FromHex("zz"); }, "fromhex non-hex");

	Expect(qod::ConstantTimeEquals("abc", "abc"), "equals");
	Expect(!qod::ConstantTimeEquals("abc", "abd"), "differs");
	Expect(!qod::ConstantTimeEquals("abc", "ab"), "length differs");

	Expect(qod::FromBase64Url("") == "", "b64 empty");
	Expect(qod::FromBase64Url("Zg") == "f", "b64 f");
	Expect(qod::FromBase64Url("Zm8") == "fo", "b64 fo");
	Expect(qod::FromBase64Url("Zm9v") == "foo", "b64 foo");
	Expect(qod::FromBase64Url("Zm9vYg==") == "foob", "b64 padded");
	Expect(qod::FromBase64Url("-_8") == std::string("\xfb\xff", 2), "b64 url alphabet");
	ExpectThrows([] { qod::FromBase64Url("Zm9v+g"); }, "b64 standard alphabet");
	ExpectThrows([] { qod::FromBase64Url("Z"); }, "b64 one char");
	ExpectThrows([] { qod::FromBase64Url("Zh"); }, "b64 non-canonical");

	if (failures == 0) {
		std::cout << "crypto_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
