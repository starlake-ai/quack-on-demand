#include "node_config.hpp"

namespace qod {

void ApplyExtensionPolicy(duckdb::DBConfig &config, const ExtensionPolicy &policy) {
	config.SetOptionByName("allow_community_extensions", duckdb::Value::BOOLEAN(policy.allow_community));
	config.SetOptionByName("allow_unsigned_extensions", duckdb::Value::BOOLEAN(policy.allow_unsigned));
	config.SetOptionByName("autoinstall_known_extensions", duckdb::Value::BOOLEAN(policy.autoinstall_known));
}

} // namespace qod
