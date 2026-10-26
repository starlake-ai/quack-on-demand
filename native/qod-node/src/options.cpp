#include "options.hpp"

#include <stdexcept>

namespace qod {
namespace {

std::string Value(const std::vector<std::string> &args, size_t &i) {
	if (i + 1 >= args.size()) {
		throw std::invalid_argument(args[i] + " needs a value");
	}
	return args[++i];
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

NodeOptions ParseOptions(const std::vector<std::string> &args) {
	NodeOptions options;
	for (size_t i = 1; i < args.size(); i++) {
		const auto &arg = args[i];
		if (arg == "--build-info") {
			options.mode = NodeOptions::Mode::BUILD_INFO;
		} else if (arg == "--list-settings") {
			options.mode = NodeOptions::Mode::LIST_SETTINGS;
		} else if (arg == "--port") {
			options.port = static_cast<int>(Number(arg, Value(args, i), 1, 65535));
		} else if (arg == "--bind") {
			options.bind = Value(args, i);
		} else if (arg == "--secrets-dir") {
			options.secrets_dir = Value(args, i);
		} else if (arg == "--secrets-ephemeral") {
			options.secrets_ephemeral = true;
		} else if (arg == "--secret-directory") {
			options.secret_directory = Value(args, i);
		} else if (arg == "--node-id") {
			options.node_id = Value(args, i);
		} else if (arg == "--init") {
			options.init_files.push_back(Value(args, i));
		} else if (arg == "--parent-pid") {
			options.parent_pid = Number(arg, Value(args, i), 1, 0x7fffffffLL);
		} else if (arg == "--allow-community-extensions") {
			options.extensions.allow_community = true;
		} else if (arg == "--allow-unsigned-extensions") {
			options.extensions.allow_unsigned = true;
		} else if (arg == "--autoinstall-known-extensions") {
			options.extensions.autoinstall_known = true;
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
		if (options.node_id.empty()) {
			throw std::invalid_argument("--node-id is required");
		}
	}
	return options;
}

std::string Usage() {
	return "usage:\n"
	       "  qod-node --port <port> [--bind <address>] --secrets-dir <dir> [--secrets-ephemeral]\n"
	       "           --secret-directory <path> --node-id <id> [--init <file>|-]... [--parent-pid <pid>]\n"
	       "           [<extension flag>]...\n"
	       "  qod-node --build-info\n"
	       "  qod-node --list-settings [--init <file>|-]... [<extension flag>]...\n"
	       "extension flags (each off unless given):\n"
	       "  --allow-community-extensions --allow-unsigned-extensions --autoinstall-known-extensions\n";
}

} // namespace qod
