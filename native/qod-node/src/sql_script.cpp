#include "sql_script.hpp"

#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/simplified_token.hpp"

namespace qod {
namespace {

std::string Trim(const std::string &text) {
	const char *blanks = " \t\r\n";
	auto first = text.find_first_not_of(blanks);
	if (first == std::string::npos) {
		return "";
	}
	auto last = text.find_last_not_of(blanks);
	return text.substr(first, last - first + 1);
}

void AddStatement(std::vector<std::string> &statements, const std::string &sql, size_t begin, size_t end) {
	auto statement = Trim(sql.substr(begin, end - begin));
	if (!statement.empty()) {
		statements.push_back(statement);
	}
}

} // namespace

std::vector<std::string> SplitStatements(const std::string &sql) {
	std::vector<std::string> statements;
	size_t begin = 0;
	for (auto &token : duckdb::Parser::Tokenize(sql)) {
		if (token.type == duckdb::SimplifiedTokenType::SIMPLIFIED_TOKEN_OPERATOR && token.start < sql.size() &&
		    sql[token.start] == ';') {
			AddStatement(statements, sql, begin, token.start);
			begin = token.start + 1;
		}
	}
	AddStatement(statements, sql, begin, sql.size());
	return statements;
}

std::string QuoteLiteral(const std::string &value) {
	std::string quoted = "'";
	for (auto c : value) {
		if (c == '\'') {
			quoted += "''";
		} else {
			quoted += c;
		}
	}
	quoted += "'";
	return quoted;
}

std::string ServeStatement(const std::string &bind, int port, const std::string &token) {
	return "SELECT listen_url FROM quack_serve(" + QuoteLiteral("quack:" + bind + ":" + std::to_string(port)) +
	       ", token := " + QuoteLiteral(token) + ", allow_other_hostname := true, disable_ssl := true)";
}

} // namespace qod
