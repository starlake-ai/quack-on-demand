#include "node_config.hpp"

#include "duckdb/common/string_util.hpp"
#include "duckdb/planner/extension_callback.hpp"

namespace qod {
namespace {

class S3EnvironmentGuard : public duckdb::ExtensionCallback {
public:
	void OnExtensionLoaded(duckdb::DatabaseInstance &db, const std::string &name) override {
		if (duckdb::StringUtil::CIEquals(name, "httpfs")) {
			ResetS3EnvironmentDefaults(db);
		} else if (duckdb::StringUtil::CIEquals(name, "aws")) {
			duckdb::DBConfig::GetConfig(db).SetOptionByName("aws_default_credentials", duckdb::Value::BOOLEAN(false));
		}
	}
};

} // namespace

const char *const kS3EnvironmentSettings[] = {"s3_region",     "s3_access_key_id", "s3_secret_access_key",
                                              "s3_session_token", "s3_endpoint",   "s3_use_ssl",
                                              "s3_kms_key_id", "s3_requester_pays", nullptr};

void ApplyExtensionPolicy(duckdb::DBConfig &config, const ExtensionPolicy &policy) {
	config.SetOptionByName("allow_community_extensions", duckdb::Value::BOOLEAN(policy.allow_community));
	config.SetOptionByName("allow_unsigned_extensions", duckdb::Value::BOOLEAN(policy.allow_unsigned));
	config.SetOptionByName("autoinstall_known_extensions", duckdb::Value::BOOLEAN(policy.autoinstall_known));
}

void ApplyCachePolicy(duckdb::DBConfig &config) {
	config.SetOptionByName("enable_external_file_cache", duckdb::Value::BOOLEAN(false));
	config.SetOptionByName("enable_http_metadata_cache", duckdb::Value::BOOLEAN(false));
}

void ApplyOptimizerPolicy(duckdb::DBConfig &config) {
	config.SetOptionByName("disabled_optimizers", duckdb::Value(kDisabledOptimizer));
}

std::string WithDisabledOptimizer(const std::string &current) {
	for (auto &name : duckdb::StringUtil::Split(current, ',')) {
		if (duckdb::StringUtil::Lower(duckdb::StringUtil::Replace(name, " ", "")) == kDisabledOptimizer) {
			return "";
		}
	}
	auto trimmed = current;
	duckdb::StringUtil::Trim(trimmed);
	return trimmed.empty() ? kDisabledOptimizer : trimmed + "," + kDisabledOptimizer;
}

void ResetS3EnvironmentDefaults(duckdb::DatabaseInstance &db) {
	auto &config = duckdb::DBConfig::GetConfig(db);
	for (auto name = kS3EnvironmentSettings; *name; name++) {
		duckdb::ExtensionOption option;
		if (config.TryGetExtensionOption(duckdb::Identifier(std::string(*name)), option)) {
			config.ResetOption(option);
		}
	}
}

void RegisterS3EnvironmentGuard(duckdb::DBConfig &config) {
	duckdb::ExtensionCallback::Register(config, duckdb::make_shared_ptr<S3EnvironmentGuard>());
}

} // namespace qod
