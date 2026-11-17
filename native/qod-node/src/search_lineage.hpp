#pragma once

// QoD Search (spec 4.1, 4.2): what a qod_search or qod_search_matches call is bound with, and the checks qod-node runs
// on it in post_bind, on the tree DuckDB bound (spec 4.2 rules 1, 2, 4, 7 and 9; the lineage check runs in
// post_bind only, OWNER RULING 2026-11-14, change 3). Built only with QOD_NODE_SEARCH=ON.

#include "search_records.hpp"
#include "search_snapshot.hpp"

#include "duckdb.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/transaction/transaction.hpp"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace qod::search {

//! What the post_bind hook resolved for one search call, in the client's transaction: the snapshot
//! view of its target, the index record (6.1) and the statistics population. Shared by every copy of
//! the call's bind data (Copy()), so the hook fills the one the sink later reads.
struct SearchTarget {
	bool attached = false;
	//! The view at S, from CachedSnapshotView (Task 10): shared with the liveness cache when it came from
	//! there, so never modified.
	std::shared_ptr<const SnapshotView> view;
	//! The view came from the liveness cache (3c).
	bool view_hit = false;
	//! The index record (6.1), from IndexRecordFor(the target's catalog, its DuckLake table id), never from
	//! the call's arguments or the verdict: its id, table id, bundle root, fields and MAC key.
	IndexRecord record;
	//! The call's `columns`, each under the name the table gives it now, bound to its DuckLake field id: a
	//! subset of the record's fields, in the call's order (QsSearchArgs.fields).
	std::vector<IndexField> call_fields;
	//! QS_POPULATION_TABLE or QS_POPULATION_ALLOWED (7.5): the verdict's on a quack session, else the table's.
	int population = 0;
	//! The transaction the hook resolved the view in (MetaTransaction::global_transaction_id): the sink
	//! refuses to search with a view of another transaction (invariant 4, a prepared statement).
	duckdb::transaction_t transaction_id = 0;
	//! The query of that transaction the view was resolved in (MetaTransaction::GetActiveQuery, Task 14): the view
	//! holds the transaction's own writes as of that query, so the sink refuses it in a later query of a transaction
	//! that has modified the target's database (a plan executed again without a rebind, after a write).
	duckdb::transaction_t query_id = 0;
	//! The target scan's resolved table entry and a copy of its AT clause (null without one): the fallback's
	//! scan (Task 11), since the LogicalGet is gone by execution. The entry is read only in the transaction
	//! above, which holds it (the sink checks the transaction before any use).
	duckdb::optional_ptr<duckdb::TableCatalogEntry> table;
	std::shared_ptr<duckdb::BoundAtClause> at_clause;
};

//! The bind data of qod_search and qod_search_matches. `table` is the argument as given, `columns` the
//! call's columns as the table names them (resolved at bind, matched without case).
struct SearchBindData : public duckdb::TableFunctionData {
	//! qod_search_matches (every match, streamed) rather than qod_search (the top k).
	bool matches = false;
	std::string table;
	std::vector<std::string> columns;
	std::string query;
	uint32_t k = 10;
	//! qod_search_matches: scores asked for (else every score is NULL).
	bool with_score = false;
	std::shared_ptr<SearchTarget> target = std::make_shared<SearchTarget>();

	duckdb::unique_ptr<duckdb::FunctionData> Copy() const override;
	bool Equals(const duckdb::FunctionData &other) const override;
};

//! The bind data of `get` when it is a qod_search or qod_search_matches call (matched by its bind data's
//! type, never by name), else null.
SearchBindData *SearchCallData(duckdb::LogicalGet &get);

//! One search call of a bound plan, as post_bind found it.
struct SearchCall {
	duckdb::LogicalGet *call;   // the qod_search or qod_search_matches GET
	duckdb::LogicalGet *target; // the target scan its input traces to (rule 1)
	duckdb::idx_t index;        // the call's position in a pre-order walk of the plan
	std::string table;          // resolved catalog.schema.table, lowercase
	std::vector<std::string> columns;
	bool projections_only; // the 7.3 fast-path condition (recorded, not used yet)
};

//! The table a search call's `table` argument names, resolved as DuckDB resolves a table name on the
//! client's context (its search path), or null when it names no table (a view included).
duckdb::optional_ptr<duckdb::TableCatalogEntry> ResolveSearchTable(duckdb::ClientContext &context,
                                                                   const std::string &table);

//! `catalog.schema.table` of `table`, lowercase: the name descriptions and SearchCall carry.
std::string CanonicalTableName(duckdb::TableCatalogEntry &table);

//! `column` as `table` names it (DuckDB matches column names without case), or empty when it has none.
std::string TableColumnName(duckdb::TableCatalogEntry &table, const std::string &column);

//! Rules 1, 2, 4, 7 and 9 over the tree post_bind sees. Throws duckdb::InvalidInputException with
//! "QoD: search_input_not_governed: <why>", "QoD: search_input_wrong_table: <why>",
//! "QoD: search_argument_not_constant: <arg>" or "QoD: search_branch_unsupported" (shape refusals, never
//! booked as denials).
//! Rule 7 (constant `table` and `columns`) and the parse half of rule 1 (no view on the input's FROM
//! path, since a view selecting `rowid AS rid` plans like the rewriter's wrap) read `statement_text`,
//! parsed again with DuckDB's Parser (an EXECUTE's prepared statement included): DuckDB moves the parsed
//! arguments out while binding. A plan holding a search call that the text does not show (no text, a
//! view or macro body calling it) is refused. Rule 4's refusals (AT (TIMESTAMP), the transaction's own
//! writes) are ResolveSnapshotView's, which the hook calls after this. `pool` is the handle's
//! `tenant/tenantDb/pool`, empty off a quack session.
std::vector<SearchCall> FindSearchCalls(duckdb::ClientContext &context, duckdb::LogicalOperator &plan,
                                        const std::string *statement_text, const std::string &pool);

//! Rule 1's text half for a plan holding a qod_search_build_segment call (which FindSearchCalls does not
//! find): refuses a search function (qod_search, qod_search_matches, qod_search_build_segment, qod_knn,
//! qod_hybrid) inside a query() literal of `statement_text`, at any depth, and through a view, macro or table
//! macro body (JudgeSearchBodies): InvalidInputException "QoD: search_input_not_governed: ...". No text is
//! refused as for a search call. FindSearchCalls applies the same query() rule to search calls.
void JudgeSearchText(duckdb::ClientContext &context, duckdb::LogicalOperator &plan, const std::string *statement_text);

//! <bundle_root>/.qod_search/<index_id>/<bundle_key>.seg (spec 6.2), `bundle_key` being Task 6's
//! DataFileRef::bundle_key (the lowercase hex SHA-256 of the data file's absolute path as the target
//! scan opens it, owner ruling 2026-11-14; no ruled call returns data_file_id). `bundle_root` comes from
//! the index's own record (6.1), carried by the verdict (Task 9), never from the node's dataPath (a
//! branch node's dataPath is its sibling prefix).
std::string SegmentPath(const std::string &bundle_root, const std::string &index_id, const std::string &bundle_key);

} // namespace qod::search
