#include "search_path_probe.hpp"

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

namespace qod {
namespace search {

bool LocalPathNotFound(const std::string &path) {
#ifdef _WIN32
	if (path.empty()) {
		return false;
	}
	int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(), static_cast<int>(path.size()), nullptr, 0);
	if (size <= 0) {
		return false;
	}
	std::wstring wide(static_cast<size_t>(size), L'\0');
	MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(), static_cast<int>(path.size()), &wide[0], size);
	if (GetFileAttributesW(wide.c_str()) != INVALID_FILE_ATTRIBUTES) {
		return false;
	}
	auto error = GetLastError();
	return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND;
#else
	(void)path;
	return false;
#endif
}

} // namespace search
} // namespace qod
