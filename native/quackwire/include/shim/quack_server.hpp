// Shim replacement for duckdb-quack's quack_server.hpp.
//
// The real upstream header transitively includes httplib.hpp via the in-tree
// HTTP server class. We do not link the HTTP server / client into the shim
// (see design doc §9.4), but quack_message.cpp does reference
// QuackServer::QUACK_VERSION at lines 153, 154, 159. Forward-declaring just
// that constant keeps the message-layer translation units compilable without
// dragging httplib.hpp into our build.
//
// This file lives at native/quackwire/include/shim/ and is placed earlier on
// the include path than the upstream src/include/ directory, so any
// `#include "quack_server.hpp"` in the three message-layer sources resolves
// here instead.
#pragma once

#include "duckdb/common/common.hpp"

namespace duckdb {
struct QuackServer {
  static constexpr idx_t QUACK_VERSION = 1;
};
}