#include "crypto.hpp"

#include <stdexcept>

namespace qod {
namespace {

const uint32_t kRound[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

uint32_t Rotr(uint32_t x, int n) {
	return (x >> n) | (x << (32 - n));
}

void Compress(uint32_t state[8], const uint8_t block[64]) {
	uint32_t w[64];
	for (int i = 0; i < 16; i++) {
		w[i] = (uint32_t(block[4 * i]) << 24) | (uint32_t(block[4 * i + 1]) << 16) | (uint32_t(block[4 * i + 2]) << 8) |
		       uint32_t(block[4 * i + 3]);
	}
	for (int i = 16; i < 64; i++) {
		uint32_t s0 = Rotr(w[i - 15], 7) ^ Rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
		uint32_t s1 = Rotr(w[i - 2], 17) ^ Rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
		w[i] = w[i - 16] + s0 + w[i - 7] + s1;
	}
	uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
	uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
	for (int i = 0; i < 64; i++) {
		uint32_t s1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
		uint32_t ch = (e & f) ^ (~e & g);
		uint32_t t1 = h + s1 + ch + kRound[i] + w[i];
		uint32_t s0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
		uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
		uint32_t t2 = s0 + maj;
		h = g;
		g = f;
		f = e;
		e = d + t1;
		d = c;
		c = b;
		b = a;
		a = t1 + t2;
	}
	state[0] += a;
	state[1] += b;
	state[2] += c;
	state[3] += d;
	state[4] += e;
	state[5] += f;
	state[6] += g;
	state[7] += h;
}

int HexValue(char c) {
	if (c >= '0' && c <= '9') {
		return c - '0';
	}
	if (c >= 'a' && c <= 'f') {
		return c - 'a' + 10;
	}
	if (c >= 'A' && c <= 'F') {
		return c - 'A' + 10;
	}
	return -1;
}

int Base64UrlValue(char c) {
	if (c >= 'A' && c <= 'Z') {
		return c - 'A';
	}
	if (c >= 'a' && c <= 'z') {
		return c - 'a' + 26;
	}
	if (c >= '0' && c <= '9') {
		return c - '0' + 52;
	}
	if (c == '-') {
		return 62;
	}
	if (c == '_') {
		return 63;
	}
	return -1;
}

} // namespace

Digest Sha256(const std::string &data) {
	uint32_t state[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
	                     0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
	auto bytes = reinterpret_cast<const uint8_t *>(data.data());
	size_t length = data.size();
	size_t full = length / 64;
	for (size_t i = 0; i < full; i++) {
		Compress(state, bytes + 64 * i);
	}
	// The tail, the 0x80 marker, zero padding and the 64-bit big-endian bit length: one or two blocks.
	uint8_t tail[128] = {0};
	size_t rest = length - 64 * full;
	for (size_t i = 0; i < rest; i++) {
		tail[i] = bytes[64 * full + i];
	}
	tail[rest] = 0x80;
	size_t tail_length = rest + 1 + 8 <= 64 ? 64 : 128;
	uint64_t bits = uint64_t(length) * 8;
	for (int i = 0; i < 8; i++) {
		tail[tail_length - 1 - i] = uint8_t(bits >> (8 * i));
	}
	Compress(state, tail);
	if (tail_length == 128) {
		Compress(state, tail + 64);
	}
	Digest digest;
	for (int i = 0; i < 8; i++) {
		digest[4 * i] = uint8_t(state[i] >> 24);
		digest[4 * i + 1] = uint8_t(state[i] >> 16);
		digest[4 * i + 2] = uint8_t(state[i] >> 8);
		digest[4 * i + 3] = uint8_t(state[i]);
	}
	return digest;
}

Digest HmacSha256(const std::string &key, const std::string &data) {
	std::string block_key = key;
	if (block_key.size() > 64) {
		auto hashed = Sha256(block_key);
		block_key.assign(reinterpret_cast<const char *>(hashed.data()), hashed.size());
	}
	block_key.resize(64, '\0');
	std::string inner(64, '\0');
	std::string outer(64, '\0');
	for (size_t i = 0; i < 64; i++) {
		inner[i] = char(uint8_t(block_key[i]) ^ 0x36);
		outer[i] = char(uint8_t(block_key[i]) ^ 0x5c);
	}
	auto inner_digest = Sha256(inner + data);
	return Sha256(outer + std::string(reinterpret_cast<const char *>(inner_digest.data()), inner_digest.size()));
}

std::string Hex(const uint8_t *bytes, size_t length) {
	static const char digits[] = "0123456789abcdef";
	std::string hex;
	hex.reserve(2 * length);
	for (size_t i = 0; i < length; i++) {
		hex += digits[bytes[i] >> 4];
		hex += digits[bytes[i] & 0x0f];
	}
	return hex;
}

std::string Hex(const Digest &digest) {
	return Hex(digest.data(), digest.size());
}

std::string FromHex(const std::string &hex) {
	if (hex.size() % 2 != 0) {
		throw std::invalid_argument("hex text has an odd length");
	}
	std::string bytes;
	bytes.reserve(hex.size() / 2);
	for (size_t i = 0; i < hex.size(); i += 2) {
		int hi = HexValue(hex[i]);
		int lo = HexValue(hex[i + 1]);
		if (hi < 0 || lo < 0) {
			throw std::invalid_argument("hex text holds a non-hex character");
		}
		bytes += char((hi << 4) | lo);
	}
	return bytes;
}

bool ConstantTimeEquals(const std::string &a, const std::string &b) {
	if (a.size() != b.size()) {
		return false;
	}
	unsigned char diff = 0;
	for (size_t i = 0; i < a.size(); i++) {
		diff |= (unsigned char)(a[i] ^ b[i]);
	}
	return diff == 0;
}

std::string FromBase64Url(const std::string &text) {
	std::string bytes;
	uint32_t buffer = 0;
	int bits = 0;
	size_t end = text.size();
	while (end > 0 && text[end - 1] == '=') {
		end--;
	}
	for (size_t i = 0; i < end; i++) {
		int value = Base64UrlValue(text[i]);
		if (value < 0) {
			throw std::invalid_argument("base64url text holds an invalid character");
		}
		buffer = (buffer << 6) | uint32_t(value);
		bits += 6;
		if (bits >= 8) {
			bits -= 8;
			bytes += char((buffer >> bits) & 0xff);
		}
	}
	// What is left must be fewer than 8 bits, all zero (a canonical encoding).
	if (bits >= 6 || (buffer & ((1u << bits) - 1)) != 0) {
		throw std::invalid_argument("base64url text is not canonically padded");
	}
	return bytes;
}

std::string ToBase64Url(const std::string &bytes) {
	static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
	std::string text;
	uint32_t buffer = 0;
	int bits = 0;
	for (auto c : bytes) {
		buffer = (buffer << 8) | uint8_t(c);
		bits += 8;
		while (bits >= 6) {
			bits -= 6;
			text += alphabet[(buffer >> bits) & 0x3f];
		}
	}
	if (bits > 0) {
		text += alphabet[(buffer << (6 - bits)) & 0x3f];
	}
	return text;
}

} // namespace qod
