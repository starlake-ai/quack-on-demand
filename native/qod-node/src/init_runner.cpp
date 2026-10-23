#include "init_runner.hpp"

#include "sql_script.hpp"

#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace qod {

size_t RunBestEffort(duckdb::Connection &con, const std::string &sql, const std::string &label) {
	auto statements = SplitStatements(sql);
	size_t failed = 0;
	for (size_t i = 0; i < statements.size(); i++) {
		auto result = con.Query(statements[i]);
		if (result->HasError()) {
			failed++;
			std::cerr << "[qod-node] " << label << ": statement " << (i + 1) << " of " << statements.size()
			          << " failed: " << result->GetError() << std::endl;
		}
	}
	std::cerr << "[qod-node] " << label << ": " << (statements.size() - failed) << " of " << statements.size()
	          << " statement(s) ran" << std::endl;
	return failed;
}

duckdb::unique_ptr<duckdb::QueryResult> RunStrict(duckdb::Connection &con, const std::string &statement,
                                                              const std::string &label) {
	auto result = con.Query(statement);
	if (result->HasError()) {
		throw std::runtime_error(label + " failed: " + result->GetError());
	}
	return result;
}

std::string ReadAll(const std::string &path) {
	std::ifstream in(path, std::ios::binary);
	if (!in) {
		throw std::runtime_error("cannot read init file " + path);
	}
	std::stringstream buffer;
	buffer << in.rdbuf();
	return buffer.str();
}

} // namespace qod
