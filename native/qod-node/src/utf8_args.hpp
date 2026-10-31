#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace qod {

//! The command line as UTF-8 text, the program name first. On Windows the narrow argv is in the ANSI
//! code page, which cannot hold every path (a %TEMP% under a user name in another script than the
//! system locale's); the wide command line can. Elsewhere argv as given. Throws std::runtime_error if
//! the command line cannot be read.
std::vector<std::string> Utf8Arguments(int argc, char **argv);

//! A filesystem path from UTF-8 text: on Windows a narrow std::string names a path in the ANSI code
//! page instead.
std::filesystem::path PathFromUtf8(const std::string &utf8);

} // namespace qod
