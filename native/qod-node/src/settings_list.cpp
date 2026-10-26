#include "settings_list.hpp"

#include "json_writer.hpp"

#include <string>
#include <utility>
#include <vector>

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
	std::vector<std::string> elements;
	for (duckdb::idx_t i = 0; i < duckdb::DBConfig::GetOptionCount(); i++) {
		auto option = duckdb::DBConfig::GetOptionByIndex(i);
		std::string setters = std::string(option->set_global ? "g" : "-") + (option->set_local ? "l" : "-");
		elements.push_back(CanonicalJson(JsonObject({{"kind", JsonString("core")},
		                                            {"name", JsonString(option->name)},
		                                            {"scope", JsonString(ScopeTargetName(option->scope))},
		                                            {"setters", JsonString(setters)}})));
	}
	for (duckdb::idx_t i = 0; i < duckdb::DBConfig::GetAliasCount(); i++) {
		auto alias = duckdb::DBConfig::GetAliasByIndex(i);
		elements.push_back(CanonicalJson(JsonObject({{"kind", JsonString("alias")},
		                                            {"name", JsonString(alias->alias)},
		                                            {"target", JsonString(alias->setting_name)}})));
	}
	for (auto &entry : config.GetExtensionSettings()) {
		auto &option = entry.second;
		std::string setters = std::string(option.supports_global ? "g" : "-") + (option.supports_session ? "l" : "-");
		elements.push_back(CanonicalJson(JsonObject({{"kind", JsonString("extension")},
		                                            {"name", JsonString(entry.first.GetIdentifierName())},
		                                            {"scope", JsonString(SetScopeName(option.default_scope))},
		                                            {"setters", JsonString(setters)}})));
	}
	out << SortedJsonLines(std::move(elements));
}

} // namespace qod
