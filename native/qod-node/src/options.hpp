#pragma once

#include <string>
#include <vector>

namespace qod {

//! The three extension settings qod-node fixes in the instance's config before the database opens
//! (spec decision 17): off unless the operator's flag turns one on. DuckDB refuses to turn either
//! allow_* setting on while a database runs, so nothing the node runs (the init SQL included) can.
struct ExtensionPolicy {
	//! allow_community_extensions (--allow-community-extensions).
	bool allow_community = false;
	//! allow_unsigned_extensions (--allow-unsigned-extensions).
	bool allow_unsigned = false;
	//! autoinstall_known_extensions (--autoinstall-known-extensions).
	bool autoinstall_known = false;
};

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
	//! This node's id ($QOD_NODE_ID): every session handle the node accepts must name it.
	std::string node_id;
	//! Init SQL files ("-": stdin, how the spawn scripts hand it over), run statement by statement,
	//! best effort.
	std::vector<std::string> init_files;
	//! Exit when this process ends (the spawn script's wrapper). 0: no parent to watch.
	long long parent_pid = 0;
	//! Applied before the database opens, in every mode that opens one.
	ExtensionPolicy extensions;
};

//! Parses the command line (UTF-8, the program name first; see Utf8Arguments). Throws
//! std::invalid_argument naming the problem; Usage() describes the options.
NodeOptions ParseOptions(const std::vector<std::string> &args);

std::string Usage();

} // namespace qod
