#pragma once

#include "duckdb/main/database.hpp"

namespace qod {

//! Spike instrumentation for the parse screen. Registers qod_screen(connection_id, sql), meant to be
//! installed as quack's authorization function: it logs what quack passes for every PREPARE and
//! refuses any statement containing the marker qod_spike_refuse, so the spike can see whether a
//! refusal and its message reach the client.
void RegisterScreenProbe(duckdb::DatabaseInstance &db);

} // namespace qod
