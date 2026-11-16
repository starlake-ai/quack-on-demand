#pragma once

// QoD Search (Phase 0 part 2, Task 8): what a qod_search call is bound with, and the checks qod-node runs
// on it in post_bind, on the tree DuckDB bound (spec 4.2 rules 1, 2, 4, 7 and 9; the lineage check runs in
// post_bind only, OWNER RULING 2026-11-14, change 3). Built only with QOD_NODE_SEARCH=ON.

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
	//! The index record (6.1): its id (canonical UUID) and the folder its bundles live under.
	std::string index_id;
	std::string bundle_root;
	//! QS_POPULATION_TABLE or QS_POPULATION_ALLOWED (7.5); the verdict's (Task 9).
	int population = 0;
	//! The transaction the hook resolved the view in (MetaTransaction::global_transaction_id): the sink
	//! refuses to search with a view of another transaction (invariant 4, a prepared statement).
	duckdb::transaction_t transaction_id = 0;
	//! The target scan's resolved table entry and a copy of its AT clause (null without one): the fallback's
	//! scan (Task 11), since the LogicalGet is gone by execution. The entry is read only in the transaction
	//! above, which holds it (the sink checks the transaction before any use).
	duckdb::optional_ptr<duckdb::TableCatalogEntry> table;
	std::shared_ptr<duckdb::BoundAtClause> at_clause;
	//! The DuckLake field id of the indexed column on the entry the verdict judged (the table now), which the
	//! target's scan at S also holds (the hook refuses the call otherwise), and BundleSourceId of it (review C1).
	int64_t field_id = -1;
	std::string source_id;
};

//! qod_search's bind data. `table` is the argument as given, `column` the one indexed column, as given.
struct SearchBindData : public duckdb::TableFunctionData {
	std::string table;
	std::string column;
	std::string query;
	uint32_t k = 10;
	std::shared_ptr<SearchTarget> target = std::make_shared<SearchTarget>();

	duckdb::unique_ptr<duckdb::FunctionData> Copy() const override;
	bool Equals(const duckdb::FunctionData &other) const override;
};

//! The bind data of `get` when it is a qod_search call (matched by its bind data's type, never by name),
//! else null.
SearchBindData *SearchCallData(duckdb::LogicalGet &get);

//! One search call of a bound plan, as post_bind found it.
struct SearchCall {
	duckdb::LogicalGet *call;   // the qod_search GET
	duckdb::LogicalGet *target; // the target scan its input traces to (rule 1)
	duckdb::idx_t index;        // the call's position in a pre-order walk of the plan
	std::string table;          // resolved catalog.schema.table, lowercase
	std::vector<std::string> columns;
	bool projections_only; // the 7.3 fast-path condition (recorded, not used by the prototype)
};

//! The table a search call's `table` argument names, resolved as DuckDB resolves a table name on the
//! client's context (its search path), or null when it names no table (a view included).
duckdb::optional_ptr<duckdb::TableCatalogEntry> ResolveSearchTable(duckdb::ClientContext &context,
                                                                   const std::string &table);

//! `catalog.schema.table` of `table`, lowercase: the name descriptions, index ids and SearchCall carry.
std::string CanonicalTableName(duckdb::TableCatalogEntry &table);

//! `column` as `table` names it (DuckDB matches column names without case), or empty when it has none.
std::string TableColumnName(duckdb::TableCatalogEntry &table, const std::string &column);

//! Rules 1, 2, 4, 7 and 9 over the tree post_bind sees. Throws duckdb::PermissionException with
//! "QoD: search_input_not_governed: <why>", "QoD: search_input_wrong_table: <why>",
//! "QoD: search_argument_not_constant: <arg>" or "QoD: search_branch_unsupported".
//! Rule 7 (constant `table` and `columns`) and the parse half of rule 1 (no view on the input's FROM
//! path, since a view selecting `rowid AS rid` plans like the rewriter's wrap) read `statement_text`,
//! parsed again with DuckDB's Parser (an EXECUTE's prepared statement included): DuckDB moves the parsed
//! arguments out while binding. A plan holding a search call that the text does not show (no text, a
//! view or macro body calling it) is refused. Rule 4's refusals (AT (TIMESTAMP), the transaction's own
//! writes) are ResolveSnapshotView's, which the hook calls after this. `pool` is the handle's
//! `tenant/tenantDb/pool`, empty off a quack session.
std::vector<SearchCall> FindSearchCalls(duckdb::ClientContext &context, duckdb::LogicalOperator &plan,
                                        const std::string *statement_text, const std::string &pool);

//! The prototype's index id (no registry until Phase 1): the first 16 bytes of
//! SHA-256("qod-search-prototype|v1|" + table + "|" + column) with the RFC 9562 version 8 nibble
//! (byte 6 = (b & 0x0f) | 0x80) and variant bits (byte 8 = (b & 0x3f) | 0x80), as the canonical
//! lowercase hyphenated text of 36 characters (spec 6.2, revision 5).
std::string PrototypeIndexId(const std::string &table, const std::string &column);

//! The id a bundle records as its QsSource::index_id (and qs_segment_open checks): the index id bound to the
//! DuckLake field id of the indexed column, SHA-256("qod-search-bundle-field|v1|" + index_id + "|" + field_id)
//! as a version 8 UUID, as PrototypeIndexId. A bundle built for one field therefore reads as missing for
//! another, so after a rename (`body` renamed away and another column renamed `body`) an old bundle never
//! serves the new `body` (Task 11 review C1). The Phase 0 stand-in for Phase 1's index record pinning field
//! ids; the bundle's path (SegmentPath) is unchanged.
std::string BundleSourceId(const std::string &index_id, int64_t field_id);

//! <bundle_root>/.qod_search/<index_id>/<bundle_key>.seg (spec 6.2), `bundle_key` being Task 6's
//! DataFileRef::bundle_key (the lowercase hex SHA-256 of the data file's absolute path as the target
//! scan opens it, owner ruling 2026-11-14; no ruled call returns data_file_id). `bundle_root` comes from
//! the index's own record (6.1), carried by the verdict (Task 9), never from the node's dataPath (a
//! branch node's dataPath is its sibling prefix).
std::string SegmentPath(const std::string &bundle_root, const std::string &index_id, const std::string &bundle_key);

} // namespace qod::search
