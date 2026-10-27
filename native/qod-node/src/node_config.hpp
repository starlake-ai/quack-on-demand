#pragma once

#include "duckdb/main/config.hpp"
#include "duckdb/main/database.hpp"
#include "options.hpp"

namespace qod {

//! Writes allow_community_extensions, allow_unsigned_extensions and autoinstall_known_extensions
//! into `config` from `policy`, all three always (DuckDB's own defaults turn community extensions and
//! autoinstall on). Call it before the database opens: only then does DuckDB accept turning an
//! allow_* setting on.
void ApplyExtensionPolicy(duckdb::DBConfig &config, const ExtensionPolicy &policy);

//! Writes enable_external_file_cache and enable_http_metadata_cache false into `config` (spec
//! decision 23): both caches serve every connection of the node by path, so a session could read
//! bytes another user's keys fetched. Call it before the database opens.
void ApplyCachePolicy(duckdb::DBConfig &config);

//! The settings httpfs fills from the environment (AWS_*, DUCKDB_S3_*) when it loads, null-terminated.
extern const char *const kS3EnvironmentSettings[];

//! Resets each of kS3EnvironmentSettings to httpfs's own default (none for the identity, region,
//! endpoint and KMS key; true for s3_use_ssl, false for s3_requester_pays), so what the node's
//! environment carries neither signs a request nor reaches current_setting(). A setting httpfs has
//! not registered is skipped.
void ResetS3EnvironmentDefaults(duckdb::DatabaseInstance &db);

//! Registers on `config`, before the database opens, the callback that runs
//! ResetS3EnvironmentDefaults each time httpfs loads (the init SQL's LOAD, an autoload, a superuser's
//! later LOAD) and turns aws_default_credentials off each time the aws extension loads: that
//! extension would otherwise sign every otherwise unmatched s3:// URL with the instance's credential
//! chain.
void RegisterS3EnvironmentGuard(duckdb::DBConfig &config);

} // namespace qod
