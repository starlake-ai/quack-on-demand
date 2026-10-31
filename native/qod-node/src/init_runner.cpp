#include "init_runner.hpp"

#include "sql_script.hpp"
#include "utf8_args.hpp"

#include "duckdb/common/string_util.hpp"
#include "duckdb/parser/parser.hpp"
#include "duckdb/parser/statement/set_statement.hpp"

#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

namespace qod {

size_t RunBestEffort(duckdb::Connection &con, const std::string &sql, const std::string &label,
                     const std::function<void(const std::string &)> &before) {
	auto statements = SplitStatements(sql);
	size_t failed = 0;
	for (size_t i = 0; i < statements.size(); i++) {
		if (before) {
			before(statements[i]);
		}
		auto result = con.Query(statements[i]);
		if (result->HasError()) {
			failed++;
			std::cerr << "[qod-node] " << label << ": statement " << (i + 1) << " of " << statements.size()
			          << " failed: " << ErrorWithoutStatement(*result) << std::endl;
		}
	}
	std::cerr << "[qod-node] " << label << ": " << (statements.size() - failed) << " of " << statements.size()
	          << " statement(s) ran" << std::endl;
	return failed;
}

bool RestrictsNodeAccess(duckdb::ClientContext &context, const std::string &statement) {
	duckdb::Parser parser(context);
	try {
		parser.ParseQuery(statement);
	} catch (std::exception &) {
		return false;
	}
	for (auto &parsed : parser.statements) {
		if (parsed->type != duckdb::StatementType::SET_STATEMENT) {
			continue;
		}
		auto name = duckdb::StringUtil::Lower(parsed->Cast<duckdb::SetStatement>().name.GetIdentifierName());
		if (name == "disabled_filesystems" || name == "enable_external_access" || name == "lock_configuration" ||
		    name == "allowed_directories" || name == "allowed_paths") {
			return true;
		}
	}
	return false;
}

duckdb::unique_ptr<duckdb::QueryResult> RunStrict(duckdb::Connection &con, const std::string &statement,
                                                              const std::string &label) {
	auto result = con.Query(statement);
	if (result->HasError()) {
		throw std::runtime_error(label + " failed: " + ErrorWithoutStatement(*result));
	}
	return result;
}

std::string ErrorWithoutStatement(const duckdb::QueryResult &result) {
	auto &message = result.GetErrorObject().Message();
	// DuckDB appends the statement's text as "\n\nLINE <n>: <text>" and a caret line.
	auto excerpt = message.find("\n\nLINE ");
	return excerpt == std::string::npos ? message : message.substr(0, excerpt);
}

namespace {

//! `text` without one leading UTF-8 byte order mark. Windows PowerShell's Process.Start opens the child's
//! stdin with a StreamWriter in the console's input encoding and flushes it at once (AutoFlush), which
//! writes that encoding's preamble: under code page 65001 a BOM precedes the spawn script's bytes, and
//! the nonce line would no longer be the first line.
std::string WithoutByteOrderMark(std::string text) {
	if (text.compare(0, 3, "\xEF\xBB\xBF") == 0) {
		text.erase(0, 3);
	}
	return text;
}

} // namespace

std::string ReadAll(const std::string &path) {
	std::stringstream buffer;
	if (path == "-") {
#ifdef _WIN32
		// Bytes as written: text mode would turn CRLF into LF and stop at a Ctrl-Z.
		_setmode(_fileno(stdin), _O_BINARY);
#endif
		buffer << std::cin.rdbuf();
		if (std::cin.bad()) {
			throw std::runtime_error("cannot read the init SQL from stdin");
		}
		return WithoutByteOrderMark(buffer.str());
	}
	std::ifstream in(PathFromUtf8(path), std::ios::binary);
	if (!in) {
		throw std::runtime_error("cannot read init file " + path);
	}
	buffer << in.rdbuf();
	return WithoutByteOrderMark(buffer.str());
}

} // namespace qod
