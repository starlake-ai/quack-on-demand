#pragma once

#include "handle.hpp"

#include "duckdb.hpp"
#include "duckdb/common/enums/expression_type.hpp"
#include "duckdb/common/enums/logical_operator_type.hpp"
#include "duckdb/planner/logical_operator.hpp"

#include <string>

namespace qod {

//! A bound plan's description v1: what the node asks the manager to decide on.
//! {"control":[..],"functions":[..],"objects":[..],"settings":[..],"statement":"<root>","v":1}
struct Described {
	//! The description's canonical JSON bytes (keys sorted by byte, order-free arrays sorted by
	//! their elements' canonical bytes, exact duplicates removed), as the node sends them.
	std::string canonical;
	//! Lowercase hex SHA-256 of `canonical`.
	std::string fingerprint;
};

//! What the describer does with one operator type or expression class. A value a DuckDB upgrade
//! adds lands on UNKNOWN, which is refused at runtime, and describer_test fails until it is
//! classified.
enum class Handling { DESCRIBED, REFUSED, UNKNOWN };

Handling OperatorHandling(duckdb::LogicalOperatorType type);
Handling ExpressionHandling(duckdb::ExpressionClass expression_class);

//! Settings no session may SET or RESET, at any scope: quack's two callbacks, and the optimizer
//! switches that would let a plan reach execution in another shape than the one described.
bool IsLockedSetting(const std::string &name);

//! Describes `plan`, bound on `context` for `principal`.
//!
//! `statement_text` is the SQL DuckDB parsed for this statement, or null when none is reachable:
//! decision 19 judges the two extension operators that name themselves neither by name nor by
//! extension (Postgres CREATE INDEX, COPY ... (FORMAT iceberg)) from it, and refuses them without it.
//!
//! Throws duckdb::PermissionException, message "QoD: " + reason, on a local refusal, whoever the
//! principal: an operator type, expression class or extension operator it does not know, a locked
//! setting, a nested schema, qod_session outside the prelude, inside a secure view a temporary
//! object or current_setting(), and a statement with a secure view on a session holding user
//! variables, temporary views or temporary macros (getvariable() folds to a constant at bind, and a
//! temporary macro or view leaves no trace once expanded). For a principal that is neither superuser
//! nor system, also a location the node cannot resolve.
Described Describe(duckdb::ClientContext &context, duckdb::LogicalOperator &plan, const Principal &principal,
                   const std::string *statement_text = nullptr);

//! `given` as a description carries it, rendered as the parse screen renders it (MakeLocation): an
//! s3:// URL naming a bucket unchanged, an absolute local path as ResolveLocalPath resolves it. Anything
//! MakeLocation refuses (a relative path, an empty, '.' or '..' segment, another scheme) is refused
//! ("QoD: <given> is outside the tenant's authorized locations") when `refuse_unresolved`, else kept
//! as given.
std::string DescribedLocation(const std::string &given, bool refuse_unresolved);

//! The path the one COPY ... FROM `statement_text` parses to names (under EXPLAIN or PREPARE), or
//! empty: not exactly one statement, not a COPY FROM, or a path that is not a string literal.
std::string CopyFromLiteralPath(duckdb::ClientContext &context, const std::string &statement_text);

} // namespace qod
