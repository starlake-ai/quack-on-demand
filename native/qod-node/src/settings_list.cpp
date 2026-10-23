#include "settings_list.hpp"

namespace qod {
namespace {

const char *ScopeTargetName(duckdb::SettingScopeTarget target) {
	switch (target) {
	case duckdb::SettingScopeTarget::GLOBAL_ONLY:
		return "global_only";
	case duckdb::SettingScopeTarget::LOCAL_ONLY:
		return "local_only";
	case duckdb::SettingScopeTarget::GLOBAL_DEFAULT:
		return "global_default";
	case duckdb::SettingScopeTarget::LOCAL_DEFAULT:
		return "local_default";
	default:
		return "none";
	}
}

const char *SetScopeName(duckdb::SetScope scope) {
	switch (scope) {
	case duckdb::SetScope::AUTOMATIC:
		return "automatic";
	case duckdb::SetScope::LOCAL:
		return "local";
	case duckdb::SetScope::SESSION:
		return "session";
	case duckdb::SetScope::GLOBAL:
		return "global";
	default:
		return "variable";
	}
}

} // namespace

void ListSettings(duckdb::DBConfig &config, std::ostream &out) {
	for (duckdb::idx_t i = 0; i < duckdb::DBConfig::GetOptionCount(); i++) {
		auto option = duckdb::DBConfig::GetOptionByIndex(i);
		std::string setters = std::string(option->set_global ? "g" : "-") + (option->set_local ? "l" : "-");
		out << "core\t" << option->name << "\t" << ScopeTargetName(option->scope) << "\t" << setters << "\n";
	}
	for (auto &entry : config.GetExtensionSettings()) {
		out << "extension\t" << entry.first.GetIdentifierName() << "\t" << SetScopeName(entry.second.default_scope)
		    << "\n";
	}
}

} // namespace qod
