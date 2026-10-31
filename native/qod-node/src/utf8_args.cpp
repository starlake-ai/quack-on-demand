// Kept apart from the DuckDB sources so windows.h and its macros never meet DuckDB's headers.
#include "utf8_args.hpp"

#include <stdexcept>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <shellapi.h>
#endif

namespace qod {

#ifdef _WIN32
namespace {

std::string ToUtf8(const wchar_t *text) {
	int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
	if (size <= 0) {
		throw std::runtime_error("cannot convert a command-line argument to UTF-8");
	}
	std::string utf8(static_cast<size_t>(size), '\0');
	WideCharToMultiByte(CP_UTF8, 0, text, -1, &utf8[0], size, nullptr, nullptr);
	utf8.resize(static_cast<size_t>(size) - 1); // the terminator
	return utf8;
}

} // namespace
#endif

std::vector<std::string> Utf8Arguments(int argc, char **argv) {
#ifdef _WIN32
	(void)argc;
	(void)argv;
	int count = 0;
	LPWSTR *wide = CommandLineToArgvW(GetCommandLineW(), &count);
	if (!wide) {
		throw std::runtime_error("cannot read the command line");
	}
	std::vector<std::string> args;
	try {
		for (int i = 0; i < count; i++) {
			args.push_back(ToUtf8(wide[i]));
		}
	} catch (...) {
		LocalFree(wide);
		throw;
	}
	LocalFree(wide);
	return args;
#else
	return std::vector<std::string>(argv, argv + argc);
#endif
}

std::filesystem::path PathFromUtf8(const std::string &utf8) {
#if defined(__cpp_char8_t)
	return std::filesystem::path(std::u8string(utf8.begin(), utf8.end()));
#else
	return std::filesystem::u8path(utf8);
#endif
}

} // namespace qod
