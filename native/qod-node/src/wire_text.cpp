#include "wire_text.hpp"

namespace qod {

bool IsLowerHex(const std::string &text) {
	for (auto c : text) {
		if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
			return false;
		}
	}
	return !text.empty();
}

std::string TrimBlanks(const std::string &text) {
	auto first = text.find_first_not_of(" \t\r\n");
	return first == std::string::npos ? "" : text.substr(first, text.find_last_not_of(" \t\r\n") - first + 1);
}

std::string QuotedBody(const std::string &body) {
	std::string text;
	for (auto c : body) {
		if (text.size() >= 200) {
			text += "...";
			break;
		}
		text += (c >= 0x20 && c < 0x7f) ? c : ' ';
	}
	auto first = text.find_first_not_of(' ');
	return first == std::string::npos ? "" : text.substr(first, text.find_last_not_of(' ') - first + 1);
}

} // namespace qod
