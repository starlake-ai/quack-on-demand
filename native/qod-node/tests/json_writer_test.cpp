// Tests for the canonical JSON writer: the golden pairs (shared with the manager's writer), the
// escaping and key order rules, the builders, and the refusal of strings that are not UTF-8. Built
// with -DQOD_NODE_TESTS=ON, run by CTest with the golden file's path.

#include "json.hpp"
#include "json_writer.hpp"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {

int failures = 0;

void Expect(bool ok, const std::string &what) {
	if (!ok) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

void ExpectRendered(const qod::JsonValue &value, const std::string &expected, const std::string &what) {
	try {
		auto rendered = qod::CanonicalJson(value);
		Expect(rendered == expected, what + ": got " + rendered + ", expected " + expected);
	} catch (std::exception &error) {
		Expect(false, what + ": threw " + error.what());
	}
}

void ExpectRefused(const std::string &bytes, const std::string &what) {
	try {
		qod::CanonicalJson(qod::JsonString(bytes));
		Expect(false, what + ": accepted");
	} catch (std::invalid_argument &error) {
		Expect(std::string(error.what()).find("not valid UTF-8") != std::string::npos,
		       what + ": unexpected message " + error.what());
	}
}

int Golden(const std::string &path) {
	std::ifstream in(path, std::ios::binary);
	if (!in) {
		std::cerr << "FAIL: cannot read " << path << std::endl;
		return 1;
	}
	std::string line;
	std::string pending;
	int pairs = 0;
	while (std::getline(in, line)) {
		if (line.rfind("in: ", 0) == 0) {
			pending = line.substr(4);
		} else if (line.rfind("out: ", 0) == 0) {
			ExpectRendered(qod::ParseJson(pending), line.substr(5), "golden " + pending);
			pairs++;
		}
	}
	Expect(pairs == 7, "golden: expected 7 pairs, read " + std::to_string(pairs));
	return 0;
}

} // namespace

int main(int argc, char **argv) {
	if (argc != 2) {
		std::cerr << "usage: json_writer_test <canonical-json-v1.txt>" << std::endl;
		return 2;
	}
	if (Golden(argv[1]) != 0) {
		return 1;
	}

	// Builders give the kinds the reader gives.
	ExpectRendered(qod::JsonNull(), "null", "null");
	ExpectRendered(qod::JsonBool(true), "true", "true");
	ExpectRendered(qod::JsonInt(std::numeric_limits<int64_t>::min()), "-9223372036854775808", "int64 min");
	ExpectRendered(qod::JsonInt(std::numeric_limits<int64_t>::max()), "9223372036854775807", "int64 max");
	ExpectRendered(qod::JsonArray({qod::JsonString("b"), qod::JsonString("a")}), "[\"b\",\"a\"]",
	               "arrays keep the producer's order");
	ExpectRendered(qod::JsonObject({{"b", qod::JsonInt(1)}, {"B", qod::JsonInt(2)}, {"a", qod::JsonNull()}}),
	               "{\"B\":2,\"a\":null,\"b\":1}", "keys in byte order");
	ExpectRendered(qod::JsonObject({{"o", qod::JsonObject({{"k", qod::JsonArray({})}})}}), "{\"o\":{\"k\":[]}}",
	               "nested");
	// Every control character but the six short escapes is \u00xx in lowercase hex; DEL (0x7f) is
	// not a control character in RFC 8259 and stays literal.
	ExpectRendered(qod::JsonString(std::string("\x00\x1b\x7f", 3)), std::string("\"\\u0000\\u001b\x7f\""),
	               "control characters");
	ExpectRendered(qod::JsonObject({{"a\"b", qod::JsonInt(1)}}), "{\"a\\\"b\":1}", "keys are escaped too");

	// A string that is not valid UTF-8 is refused, never passed through.
	ExpectRefused("\xc3", "truncated two-byte sequence");
	ExpectRefused("\xc0\x80", "overlong NUL");
	ExpectRefused("\xe0\x80\xaf", "overlong three-byte form");
	ExpectRefused("\xed\xa0\x80", "UTF-16 surrogate");
	ExpectRefused("\xf4\x90\x80\x80", "past U+10FFFF");
	ExpectRefused("\x80", "stray continuation byte");
	ExpectRefused("ok\xff", "invalid lead byte after valid text");
	// The nearest valid neighbours are accepted.
	ExpectRendered(qod::JsonString("\xc3\xa9"), "\"\xc3\xa9\"", "two-byte sequence");
	ExpectRendered(qod::JsonString("\xf4\x8f\xbf\xbf"), "\"\xf4\x8f\xbf\xbf\"", "U+10FFFF");

	// Rendering is a fixed point of parsing.
	auto canonical = qod::CanonicalJson(qod::ParseJson("{\"z\":[1,{\"b\":\"\\u00e9\",\"a\":true}]}"));
	Expect(qod::CanonicalJson(qod::ParseJson(canonical)) == canonical, "render(parse(render(x))) == render(x)");

	if (failures == 0) {
		std::cout << "json_writer_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
