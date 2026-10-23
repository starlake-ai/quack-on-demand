#pragma once

#include <string>
#include <vector>

namespace qod {

//! What qod-node was asked to do, from its command line.
struct NodeOptions {
	enum class Mode { SERVE, BUILD_INFO, LIST_SETTINGS };

	Mode mode = Mode::SERVE;
	//! quack_serve's port and bind address.
	int port = 0;
	std::string bind = "0.0.0.0";
	//! The node's secrets directory ($QOD_SECRETS_DIR); qod-node reads its own keys there.
	std::string secrets_dir;
	//! Remove the secrets directory once read (local and fleet launchers create a private one per
	//! node; a Kubernetes Secret volume is read-only and stays).
	bool secrets_ephemeral = false;
	//! DuckDB's secret_directory, set before anything else runs.
	std::string secret_directory;
	//! Init SQL files (FIFOs from the spawn scripts), run statement by statement, best effort.
	std::vector<std::string> init_files;
	//! Exit when this process ends (the spawn script's wrapper). 0: no parent to watch.
	long long parent_pid = 0;
};

//! Parses argv. Throws std::invalid_argument naming the problem; Usage() describes the options.
NodeOptions ParseOptions(int argc, char **argv);

std::string Usage();

} // namespace qod
