#include "init_policy.hpp"

#include "duckdb/common/string_util.hpp"
#include "duckdb/main/extension_helper.hpp"
#include "duckdb/parser/expression/constant_expression.hpp"
#include "duckdb/parser/parsed_data/load_info.hpp"
#include "duckdb/parser/parsed_data/pragma_info.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/statement/load_statement.hpp"
#include "duckdb/parser/statement/pragma_statement.hpp"
#include "duckdb/parser/statement/set_statement.hpp"
#include "init_policy_lists.hpp"
#include "init_runner.hpp"
#include "json_writer.hpp"
#include "screen_rules.hpp"
#include "sql_script.hpp"

#include <iostream>

namespace qod {
namespace {

using namespace duckdb;

template <class LIST>
bool Listed(const LIST &list, const std::string &name) {
	for (auto &entry : list) {
		if (name == entry) {
			return true;
		}
	}
	return false;
}

//! `line` without the blanks around it and a CR at its end.
std::string TrimLine(const std::string &line) {
	const char *blanks = " \t\r";
	auto first = line.find_first_not_of(blanks);
	if (first == std::string::npos) {
		return "";
	}
	return line.substr(first, line.find_last_not_of(blanks) - first + 1);
}

bool Blank(const std::string &text) {
	return text.find_first_not_of(" \t\r\n") == std::string::npos;
}

//! A begin marker line of `nonce`: kUntrustedBegin and the nonce, alone or followed by one space and a
//! lowercase label.
bool BeginLine(const std::string &line, const std::string &nonce, std::string &label) {
	if (nonce.empty()) {
		return false;
	}
	std::string begin = std::string(kUntrustedBegin) + " " + nonce;
	if (line == begin) {
		label.clear();
		return true;
	}
	if (line.size() <= begin.size() + 1 || line.compare(0, begin.size() + 1, begin + " ") != 0) {
		return false;
	}
	auto rest = line.substr(begin.size() + 1);
	for (auto c : rest) {
		if (c < 'a' || c > 'z') {
			return false;
		}
	}
	label = rest;
	return true;
}

//! A setting name fit for a reason: identifier characters only, else not shown.
std::string SettingName(const std::string &name) {
	if (name.empty() || name.size() > 64) {
		return "this setting";
	}
	for (auto c : name) {
		if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
			return "this setting";
		}
	}
	return name;
}

bool IsLiteral(const ParsedExpression &expression) {
	return expression.GetExpressionClass() == ExpressionClass::CONSTANT;
}

std::string JudgeSet(SQLStatement &statement) {
	auto &set = statement.Cast<SetStatement>();
	if (set.scope != SetScope::AUTOMATIC && set.scope != SetScope::GLOBAL) {
		return "SET and RESET take only the default or the GLOBAL scope in a tenant initSql";
	}
	auto name = StringUtil::Lower(set.name.GetIdentifierName());
	if (!Listed(kInitSettings, name) || IsReservedSessionSetting(name)) {
		return "SET and RESET of " + SettingName(name) + " are not on the tenant initSql allowlist";
	}
	if (set.set_type == SetType::SET) {
		auto &value = statement.Cast<SetVariableStatement>().value;
		if (!value || !IsLiteral(*value)) {
			return "the value of SET " + name + " must be a single literal";
		}
	}
	return "";
}

std::string JudgePragma(SQLStatement &statement) {
	auto &info = *statement.Cast<PragmaStatement>().info;
	if (!IsReadOnlyPragma(StringUtil::Lower(info.name.GetIdentifierName()))) {
		return "this PRAGMA is not one of the read-only listings a tenant initSql may run";
	}
	for (auto &parameter : info.parameters) {
		if (!parameter || !IsLiteral(*parameter)) {
			return "PRAGMA arguments must be literals";
		}
	}
	for (auto &parameter : info.named_parameters) {
		if (!parameter.second || !IsLiteral(*parameter.second)) {
			return "PRAGMA arguments must be literals";
		}
	}
	return "";
}

std::string JudgeLoad(SQLStatement &statement) {
	auto &info = *statement.Cast<LoadStatement>().info;
	auto kind = info.load_type;
	if (kind != LoadType::LOAD && kind != LoadType::INSTALL && kind != LoadType::FORCE_INSTALL) {
		return "extension repositories and LOAD ... AS are not allowed in a tenant initSql";
	}
	if (!info.repository.empty() || !info.version.empty() || !info.repository_url.empty() ||
	    !info.public_keys.empty()) {
		return "INSTALL and LOAD take no FROM, repository or VERSION in a tenant initSql";
	}
	if (!IsPlainExtensionName(info.filename)) {
		return "INSTALL and LOAD take a bare extension name in a tenant initSql, never a path or a URL";
	}
	if (!Listed(kInitExtensions, ExtensionHelper::ApplyExtensionAlias(info.filename))) {
		return "this extension is not on the tenant initSql allowlist";
	}
	return "";
}

} // namespace

std::vector<InitSection> SplitInitSections(const std::string &sql, const std::string &nonce) {
	std::vector<InitSection> sections;
	InitSection current {true, "", ""};
	auto close = [&]() {
		if (!Blank(current.sql)) {
			sections.push_back(current);
		}
	};
	size_t start = 0;
	while (start <= sql.size()) {
		auto end = sql.find('\n', start);
		auto stop = end == std::string::npos ? sql.size() : end;
		auto line = sql.substr(start, stop - start);
		auto trimmed = TrimLine(line);
		std::string label;
		if (BeginLine(trimmed, nonce, label)) {
			// A begin line inside an untrusted section keeps it untrusted (and its label).
			if (current.trusted) {
				close();
				current = InitSection {false, label, ""};
			}
		} else if (!nonce.empty() && trimmed == std::string(kUntrustedEnd) + " " + nonce) {
			// An end line outside an untrusted section closes nothing.
			if (!current.trusted) {
				close();
				current = InitSection {true, "", ""};
			}
		} else {
			current.sql += line;
			if (end != std::string::npos) {
				current.sql += '\n';
			}
		}
		if (end == std::string::npos) {
			break;
		}
		start = end + 1;
	}
	close();
	return sections;
}

std::string JudgeInitStatement(ClientContext &context, const std::string &statement,
                               unique_ptr<SQLStatement> &parsed) {
	parsed.reset();
	Parser parser(context);
	try {
		parser.ParseQuery(statement);
	} catch (std::exception &) {
		// Not DuckDB's message: a parser error quotes the text near the error, and init SQL may carry
		// credentials.
		return "the statement does not parse";
	}
	if (parser.statements.empty()) {
		return "";
	}
	if (parser.statements.size() > 1) {
		return "the text holds more than one statement";
	}
	auto &candidate = *parser.statements[0];
	std::string reason;
	switch (candidate.type) {
	case StatementType::SET_STATEMENT:
		reason = JudgeSet(candidate);
		break;
	case StatementType::PRAGMA_STATEMENT:
		reason = JudgePragma(candidate);
		break;
	case StatementType::LOAD_STATEMENT:
		reason = JudgeLoad(candidate);
		break;
	default:
		reason = StatementTypeToString(candidate.type) +
		         " is not allowed in a tenant initSql (only SET, RESET, read-only PRAGMAs, INSTALL and LOAD)";
	}
	if (reason.empty()) {
		parsed = std::move(parser.statements[0]);
	}
	return reason;
}

InitVerdict CheckInit(ClientContext &context, const std::string &sql) {
	InitVerdict verdict;
	auto statements = SplitStatements(sql);
	verdict.statements = statements.size();
	for (size_t i = 0; i < statements.size(); i++) {
		unique_ptr<SQLStatement> parsed;
		auto reason = JudgeInitStatement(context, statements[i], parsed);
		if (!reason.empty()) {
			verdict.refused.emplace_back(i + 1, reason);
		}
	}
	return verdict;
}

std::string InitVerdictJson(const InitVerdict &verdict) {
	std::vector<JsonValue> refused;
	for (auto &entry : verdict.refused) {
		refused.push_back(JsonObject({{"reason", JsonString(entry.second)},
		                              {"statement", JsonInt(static_cast<int64_t>(entry.first))}}));
	}
	return CanonicalJson(JsonObject({{"refused", JsonArray(std::move(refused))},
	                                 {"statements", JsonInt(static_cast<int64_t>(verdict.statements))}}));
}

size_t RunUntrusted(Connection &con, const std::string &sql, const std::string &label) {
	auto statements = SplitStatements(sql);
	size_t skipped = 0;
	for (size_t i = 0; i < statements.size(); i++) {
		unique_ptr<SQLStatement> parsed;
		auto reason = JudgeInitStatement(*con.context, statements[i], parsed);
		if (!reason.empty()) {
			skipped++;
			std::cerr << "[qod-node] " << label << ": statement " << (i + 1) << " of " << statements.size()
			          << " skipped: " << reason << std::endl;
			continue;
		}
		if (!parsed) {
			continue;
		}
		// The statement judged, not the text parsed again.
		auto result = con.Query(std::move(parsed));
		if (result->HasError()) {
			skipped++;
			std::cerr << "[qod-node] " << label << ": statement " << (i + 1) << " of " << statements.size()
			          << " failed: " << ErrorWithoutStatement(*result) << std::endl;
		}
	}
	std::cerr << "[qod-node] " << label << ": " << (statements.size() - skipped) << " of " << statements.size()
	          << " statement(s) ran under the tenant initSql allowlist" << std::endl;
	return skipped;
}

size_t RunInit(Connection &con, const std::string &sql, const std::string &nonce) {
	size_t skipped = 0;
	for (auto &section : SplitInitSections(sql, nonce)) {
		if (section.trusted) {
			skipped += RunBestEffort(con, section.sql, "init");
		} else {
			skipped += RunUntrusted(con, section.sql,
			                        "tenant " + (section.label.empty() ? std::string("") : section.label + " ") +
			                            "initSql");
		}
	}
	return skipped;
}

} // namespace qod
