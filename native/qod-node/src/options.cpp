#include "options.hpp"

#include <stdexcept>

namespace qod {
namespace {

std::string Value(int argc, char **argv, int &i) {
	std::string name = argv[i];
	if (i + 1 >= argc) {
		throw std::invalid_argument(name + " needs a value");
	}
	return argv[++i];
}

long long Number(const std::string &name, const std::string &text, long long min, long long max) {
	size_t used = 0;
	long long value = 0;
	try {
		value = std::stoll(text, &used);
	} catch (std::exception &) {
		used = 0;
	}
	if (used != text.size() || text.empty() || value < min || value > max) {
		throw std::invalid_argument(name + " must be a number between " + std::to_string(min) + " and " +
		                            std::to_string(max) + ", got '" + text + "'");
	}
	return value;
}

} // namespace

NodeOptions ParseOptions(int argc, char **argv) {
	NodeOptions options;
	for (int i = 1; i < argc; i++) {
		std::string arg = argv[i];
		if (arg == "--build-info") {
			options.mode = NodeOptions::Mode::BUILD_INFO;
		} else if (arg == "--list-settings") {
			options.mode = NodeOptions::Mode::LIST_SETTINGS;
		} else if (arg == "--port") {
			options.port = static_cast<int>(Number(arg, Value(argc, argv, i), 1, 65535));
		} else if (arg == "--bind") {
			options.bind = Value(argc, argv, i);
		} else if (arg == "--secrets-dir") {
			options.secrets_dir = Value(argc, argv, i);
		} else if (arg == "--secrets-ephemeral") {
			options.secrets_ephemeral = true;
		} else if (arg == "--secret-directory") {
			options.secret_directory = Value(argc, argv, i);
		} else if (arg == "--init") {
			options.init_files.push_back(Value(argc, argv, i));
		} else if (arg == "--parent-pid") {
			options.parent_pid = Number(arg, Value(argc, argv, i), 1, 0x7fffffffLL);
		} else {
			throw std::invalid_argument("unknown option '" + arg + "'");
		}
	}
	if (options.mode == NodeOptions::Mode::SERVE) {
		if (options.port == 0) {
			throw std::invalid_argument("--port is required");
		}
		if (options.secrets_dir.empty()) {
			throw std::invalid_argument("--secrets-dir is required");
		}
		if (options.secret_directory.empty()) {
			throw std::invalid_argument("--secret-directory is required");
		}
	}
	return options;
}

std::string Usage() {
	return "usage:\n"
	       "  qod-node --port <port> [--bind <address>] --secrets-dir <dir> [--secrets-ephemeral]\n"
	       "           --secret-directory <path> [--init <file>|-]... [--parent-pid <pid>]\n"
	       "  qod-node --build-info\n"
	       "  qod-node --list-settings [--init <file>|-]...\n";
}

} // namespace qod
