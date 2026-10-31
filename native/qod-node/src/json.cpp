#include "json.hpp"

#include <limits>
#include <stdexcept>

namespace qod {
namespace {

class Reader {
public:
	explicit Reader(const std::string &text) : text_(text) {
	}

	JsonValue Document() {
		auto value = Value(0);
		SkipBlanks();
		if (pos_ != text_.size()) {
			Fail("trailing characters");
		}
		return value;
	}

private:
	const std::string &text_;
	size_t pos_ = 0;

	[[noreturn]] void Fail(const std::string &why) const {
		throw std::invalid_argument("invalid JSON at offset " + std::to_string(pos_) + ": " + why);
	}

	void SkipBlanks() {
		while (pos_ < text_.size() &&
		       (text_[pos_] == ' ' || text_[pos_] == '\t' || text_[pos_] == '\n' || text_[pos_] == '\r')) {
			pos_++;
		}
	}

	char Peek() const {
		return pos_ < text_.size() ? text_[pos_] : '\0';
	}

	void Expect(char c) {
		if (Peek() != c) {
			Fail(std::string("expected '") + c + "'");
		}
		pos_++;
	}

	void Literal(const char *word) {
		for (const char *p = word; *p; p++) {
			Expect(*p);
		}
	}

	JsonValue Value(int depth) {
		if (depth > 32) {
			Fail("nesting too deep");
		}
		SkipBlanks();
		JsonValue value;
		switch (Peek()) {
		case '{':
			value.kind = JsonValue::Kind::OBJECT;
			Object(value, depth);
			break;
		case '[':
			value.kind = JsonValue::Kind::ARRAY;
			Array(value, depth);
			break;
		case '"':
			value.kind = JsonValue::Kind::STRING;
			value.string = String();
			break;
		case 't':
			Literal("true");
			value.kind = JsonValue::Kind::BOOLEAN;
			value.boolean = true;
			break;
		case 'f':
			Literal("false");
			value.kind = JsonValue::Kind::BOOLEAN;
			break;
		case 'n':
			Literal("null");
			break;
		default:
			value.kind = JsonValue::Kind::INTEGER;
			value.integer = Integer();
			break;
		}
		return value;
	}

	void Object(JsonValue &value, int depth) {
		Expect('{');
		SkipBlanks();
		if (Peek() == '}') {
			pos_++;
			return;
		}
		while (true) {
			SkipBlanks();
			auto key = String();
			SkipBlanks();
			Expect(':');
			auto member = Value(depth + 1);
			if (!value.object.emplace(key, std::move(member)).second) {
				Fail("duplicate key \"" + key + "\"");
			}
			SkipBlanks();
			if (Peek() == ',') {
				pos_++;
				continue;
			}
			Expect('}');
			return;
		}
	}

	void Array(JsonValue &value, int depth) {
		Expect('[');
		SkipBlanks();
		if (Peek() == ']') {
			pos_++;
			return;
		}
		while (true) {
			value.array.push_back(Value(depth + 1));
			SkipBlanks();
			if (Peek() == ',') {
				pos_++;
				continue;
			}
			Expect(']');
			return;
		}
	}

	int64_t Integer() {
		bool negative = false;
		if (Peek() == '-') {
			negative = true;
			pos_++;
		}
		if (Peek() < '0' || Peek() > '9') {
			Fail("expected a value");
		}
		if (Peek() == '0' && pos_ + 1 < text_.size() && text_[pos_ + 1] >= '0' && text_[pos_ + 1] <= '9') {
			Fail("a number with a leading zero");
		}
		uint64_t magnitude = 0;
		while (Peek() >= '0' && Peek() <= '9') {
			uint64_t digit = uint64_t(Peek() - '0');
			if (magnitude > (uint64_t(std::numeric_limits<int64_t>::max()) - digit) / 10) {
				Fail("a number out of range");
			}
			magnitude = magnitude * 10 + digit;
			pos_++;
		}
		if (Peek() == '.' || Peek() == 'e' || Peek() == 'E') {
			Fail("a number that is not an integer");
		}
		return negative ? -int64_t(magnitude) : int64_t(magnitude);
	}

	static void AppendUtf8(std::string &out, uint32_t code) {
		if (code < 0x80) {
			out += char(code);
		} else if (code < 0x800) {
			out += char(0xc0 | (code >> 6));
			out += char(0x80 | (code & 0x3f));
		} else if (code < 0x10000) {
			out += char(0xe0 | (code >> 12));
			out += char(0x80 | ((code >> 6) & 0x3f));
			out += char(0x80 | (code & 0x3f));
		} else {
			out += char(0xf0 | (code >> 18));
			out += char(0x80 | ((code >> 12) & 0x3f));
			out += char(0x80 | ((code >> 6) & 0x3f));
			out += char(0x80 | (code & 0x3f));
		}
	}

	uint32_t Hex4() {
		uint32_t code = 0;
		for (int i = 0; i < 4; i++) {
			char c = Peek();
			int digit = c >= '0' && c <= '9'   ? c - '0'
			            : c >= 'a' && c <= 'f' ? c - 'a' + 10
			            : c >= 'A' && c <= 'F' ? c - 'A' + 10
			                                   : -1;
			if (digit < 0) {
				Fail("a bad \\u escape");
			}
			code = code * 16 + uint32_t(digit);
			pos_++;
		}
		return code;
	}

	std::string String() {
		Expect('"');
		std::string out;
		while (true) {
			if (pos_ >= text_.size()) {
				Fail("an unterminated string");
			}
			char c = text_[pos_++];
			if (c == '"') {
				return out;
			}
			if (static_cast<unsigned char>(c) < 0x20) {
				Fail("a control character in a string");
			}
			if (c != '\\') {
				out += c;
				continue;
			}
			char escape = Peek();
			pos_++;
			switch (escape) {
			case '"':
				out += '"';
				break;
			case '\\':
				out += '\\';
				break;
			case '/':
				out += '/';
				break;
			case 'b':
				out += '\b';
				break;
			case 'f':
				out += '\f';
				break;
			case 'n':
				out += '\n';
				break;
			case 'r':
				out += '\r';
				break;
			case 't':
				out += '\t';
				break;
			case 'u': {
				uint32_t code = Hex4();
				if (code >= 0xd800 && code <= 0xdbff) {
					if (Peek() != '\\' || pos_ + 1 >= text_.size() || text_[pos_ + 1] != 'u') {
						Fail("an unpaired surrogate");
					}
					pos_ += 2;
					uint32_t low = Hex4();
					if (low < 0xdc00 || low > 0xdfff) {
						Fail("an unpaired surrogate");
					}
					code = 0x10000 + ((code - 0xd800) << 10) + (low - 0xdc00);
				} else if (code >= 0xdc00 && code <= 0xdfff) {
					Fail("an unpaired surrogate");
				}
				AppendUtf8(out, code);
				break;
			}
			default:
				Fail("a bad escape");
			}
		}
	}
};

const char *KindName(JsonValue::Kind kind) {
	switch (kind) {
	case JsonValue::Kind::NUL:
		return "null";
	case JsonValue::Kind::BOOLEAN:
		return "a boolean";
	case JsonValue::Kind::INTEGER:
		return "an integer";
	case JsonValue::Kind::STRING:
		return "a string";
	case JsonValue::Kind::ARRAY:
		return "an array";
	case JsonValue::Kind::OBJECT:
		return "an object";
	}
	return "?";
}

} // namespace

const JsonValue &JsonValue::Get(const std::string &key, Kind expected) const {
	auto found = GetOptional(key, expected);
	if (!found) {
		throw std::invalid_argument("\"" + key + "\" is missing or null");
	}
	return *found;
}

const JsonValue *JsonValue::GetOptional(const std::string &key, Kind expected) const {
	if (kind != Kind::OBJECT) {
		throw std::invalid_argument("not an object");
	}
	auto it = object.find(key);
	if (it == object.end() || it->second.kind == Kind::NUL) {
		return nullptr;
	}
	if (it->second.kind != expected) {
		throw std::invalid_argument("\"" + key + "\" is not " + KindName(expected));
	}
	return &it->second;
}

JsonValue ParseJson(const std::string &text) {
	return Reader(text).Document();
}

} // namespace qod
