#include "json_writer.hpp"

#include <stdexcept>
#include <utility>

namespace qod {
namespace {

//! The length of the valid UTF-8 sequence (RFC 3629) starting at text[i], or 0 when there is none: a
//! bad lead byte, a truncated sequence, an overlong form, a surrogate or a code point past U+10FFFF.
size_t Utf8SequenceLength(const std::string &text, size_t i) {
	auto lead = static_cast<unsigned char>(text[i]);
	size_t length = 0;
	uint32_t code = 0;
	if (lead < 0x80) {
		return 1;
	} else if (lead >= 0xc2 && lead <= 0xdf) {
		length = 2;
		code = lead & 0x1f;
	} else if (lead >= 0xe0 && lead <= 0xef) {
		length = 3;
		code = lead & 0x0f;
	} else if (lead >= 0xf0 && lead <= 0xf4) {
		length = 4;
		code = lead & 0x07;
	} else {
		return 0;
	}
	if (i + length > text.size()) {
		return 0;
	}
	for (size_t k = 1; k < length; k++) {
		auto next = static_cast<unsigned char>(text[i + k]);
		if ((next & 0xc0) != 0x80) {
			return 0;
		}
		code = (code << 6) | (next & 0x3f);
	}
	bool overlong = (length == 3 && code < 0x800) || (length == 4 && code < 0x10000);
	bool surrogate = code >= 0xd800 && code <= 0xdfff;
	if (overlong || surrogate || code > 0x10ffff) {
		return 0;
	}
	return length;
}

void WriteString(const std::string &text, std::string &out) {
	static const char *kHex = "0123456789abcdef";
	out += '"';
	size_t i = 0;
	while (i < text.size()) {
		auto c = static_cast<unsigned char>(text[i]);
		if (c == '"') {
			out += "\\\"";
		} else if (c == '\\') {
			out += "\\\\";
		} else if (c == '\b') {
			out += "\\b";
		} else if (c == '\f') {
			out += "\\f";
		} else if (c == '\n') {
			out += "\\n";
		} else if (c == '\r') {
			out += "\\r";
		} else if (c == '\t') {
			out += "\\t";
		} else if (c < 0x20) {
			out += "\\u00";
			out += kHex[c >> 4];
			out += kHex[c & 0x0f];
		} else {
			auto length = Utf8SequenceLength(text, i);
			if (length == 0) {
				throw std::invalid_argument("a string that is not valid UTF-8, at byte " + std::to_string(i));
			}
			out.append(text, i, length);
			i += length;
			continue;
		}
		i++;
	}
	out += '"';
}

void Write(const JsonValue &value, std::string &out) {
	switch (value.kind) {
	case JsonValue::Kind::NUL:
		out += "null";
		break;
	case JsonValue::Kind::BOOLEAN:
		out += value.boolean ? "true" : "false";
		break;
	case JsonValue::Kind::INTEGER:
		out += std::to_string(value.integer);
		break;
	case JsonValue::Kind::STRING:
		WriteString(value.string, out);
		break;
	case JsonValue::Kind::ARRAY: {
		out += '[';
		bool first = true;
		for (auto &item : value.array) {
			if (!first) {
				out += ',';
			}
			first = false;
			Write(item, out);
		}
		out += ']';
		break;
	}
	case JsonValue::Kind::OBJECT: {
		out += '{';
		bool first = true;
		for (auto &member : value.object) {
			if (!first) {
				out += ',';
			}
			first = false;
			WriteString(member.first, out);
			out += ':';
			Write(member.second, out);
		}
		out += '}';
		break;
	}
	}
}

} // namespace

JsonValue JsonString(std::string value) {
	JsonValue json;
	json.kind = JsonValue::Kind::STRING;
	json.string = std::move(value);
	return json;
}

JsonValue JsonInt(int64_t value) {
	JsonValue json;
	json.kind = JsonValue::Kind::INTEGER;
	json.integer = value;
	return json;
}

JsonValue JsonBool(bool value) {
	JsonValue json;
	json.kind = JsonValue::Kind::BOOLEAN;
	json.boolean = value;
	return json;
}

JsonValue JsonNull() {
	return JsonValue();
}

JsonValue JsonArray(std::vector<JsonValue> items) {
	JsonValue json;
	json.kind = JsonValue::Kind::ARRAY;
	json.array = std::move(items);
	return json;
}

JsonValue JsonObject(std::map<std::string, JsonValue> members) {
	JsonValue json;
	json.kind = JsonValue::Kind::OBJECT;
	json.object = std::move(members);
	return json;
}

std::string CanonicalJson(const JsonValue &value) {
	std::string out;
	Write(value, out);
	return out;
}

} // namespace qod
