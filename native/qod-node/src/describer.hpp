#pragma once

#include "handle.hpp"

#include "duckdb.hpp"
#include "duckdb/catalog/catalog_search_path.hpp"
#include "duckdb/common/enums/expression_type.hpp"
#include "duckdb/common/enums/logical_operator_type.hpp"
#include "duckdb/main/client_context_state.hpp"
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

//! The catalog set paths (USE, SET schema, SET search_path) a connection had when qod_session
//! installed its principal (CONTRACT Addendum 4 item 11): a statement with a secure view is refused
//! while the connection's set paths differ from them. Task 10 records them (RecordSessionPaths); a
//! connection with none recorded is held to the session default, no set path or the database it
//! connected to with its default schema.
class SessionPaths : public duckdb::ClientContextState {
public:
	static constexpr const char *kKey = "qod_session_paths";

	duckdb::vector<duckdb::CatalogSearchEntry> set_paths;
};

//! Records the set paths `context` has now as its SessionPaths.
void RecordSessionPaths(duckdb::ClientContext &context);

//! Describes `plan`, bound on `context` for `principal`.
//!
//! The bound plan holds only what DuckDB left to execute. The slots DuckDB evaluates while binding (SET
//! values, table-function arguments, EXECUTE parameters, AT clauses, foldable LIMIT and OFFSET, PIVOT
//! IN lists, COLUMNS(), type expressions, unnest depth, CREATE VIEW bodies, ALTER ADD COLUMN DEFAULT
//! and ALTER COLUMN TYPE USING) hold a constant by post_bind: the functions they called have run and
//! appear nowhere here. The parse screen owns them (CONTRACT Addendum 4 item 9).
//!
//! `statement_text` is the SQL DuckDB parsed for this statement, or null when none is reachable:
//! decision 19 judges the two extension operators that name themselves neither by name nor by
//! extension (Postgres CREATE INDEX, COPY ... (FORMAT iceberg)) from it, and refuses them without it.
//!
//! Throws duckdb::PermissionException, message "QoD: " + reason, on a local refusal, whoever the
//! principal: an operator type, expression class or extension operator it does not know, a locked
//! setting, a nested schema, qod_session outside the prelude; inside a secure view a temporary
//! object, current_setting(), a time-travel (AT) read of the view, or an object or function of a
//! catalog other than the view's that the view's definition does not name; and a statement with a
//! secure view on a session holding user variables, temporary entries other than tables and
//! sequences, a changed setting other than schema and search_path, or set paths other than its
//! SessionPaths (getvariable() folds to a constant at bind, and a temporary macro, view or type, a
//! setting or a search path steers a body that runs with its owner's rights without a trace in the
//! plan). For a principal that is neither superuser nor system, also a location the node cannot
//! resolve. Anything else that goes wrong while describing is refused as "QoD: statement could not be
//! described", without DuckDB's message.
Described Describe(duckdb::ClientContext &context, duckdb::LogicalOperator &plan, const Principal &principal,
                   const std::string *statement_text = nullptr);

//! `given` as a description carries it, rendered as the parse screen renders it (MakeLocation): an
//! s3:// URL naming a bucket unchanged, an absolute local path as ResolveLocalPath resolves it. Anything
//! MakeLocation refuses (a relative path, an empty, '.' or '..' segment, another scheme) is refused
//! ("QoD: <given> is outside the tenant's authorized locations") when `refuse_unresolved`, else kept
//! as given, a URL of another scheme without its userinfo, query string or fragment (RedactedUrl).
std::string DescribedLocation(const std::string &given, bool refuse_unresolved);

//! `text` without the userinfo (`user:password@`), the query string and the fragment when it is a URL
//! of a scheme other than s3 (where a SAS token or a password travels); anything else unchanged.
std::string RedactedUrl(const std::string &text);

//! The path the one COPY ... FROM `statement_text` parses to names (under EXPLAIN or PREPARE), or
//! empty: not exactly one statement, not a COPY FROM, or a path that is not a string literal.
std::string CopyFromLiteralPath(duckdb::ClientContext &context, const std::string &statement_text);

} // namespace qod
