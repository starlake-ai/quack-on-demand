#pragma once

// QoD Search (spec 7.6): the custom sink operator of qod-node's own
// that runs a search call's input drain in parallel and its Finalize once after every Combine. Built
// only with QOD_NODE_SEARCH=ON.
//
// A table function registered through RegisterSinkFunction is planned as an in-out function and bound,
// described and decided like any other call; qod-node's optimize_function (RegisterSearchSink), which
// DuckDB runs after every pre_optimize_function (qod-node's describe included) and after its own
// optimizers, then replaces exactly that LogicalGet with a LogicalQodSearchSink, which plans a
// PhysicalQodSearchSink: a parallel sink over the call's input, whose Finalize runs the call's program
// once, and a single source of its output rows. A registered call that reaches execution unswapped
// fails (its in-out callbacks throw). DuckDB 2.0 rebinds a prepared statement that reads a table at
// every EXECUTE, so the swap, like the verdict, happens once per bind (P7a).

#include "duckdb.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <cstdint>
#include <functional>
#include <memory>

namespace qod {
namespace search {

//! Per-thread state of one sink execution (a local sink state holds one).
struct SinkLocal {
	virtual ~SinkLocal() = default;
};

//! What a sink-executed call does with its input. One object per execution, owned by the operator's
//! global sink state; never thread-local state (Q3: a final can run on another thread).
class SinkProgram {
public:
	virtual ~SinkProgram() = default;
	//! Once per local sink state, on the thread that creates it.
	virtual std::unique_ptr<SinkLocal> NewLocal() = 0;
	//! Per input chunk: `rowids` is the call's one BIGINT input column, `count` rows. Called
	//! concurrently on different locals; touches only `local`. A program sizes nothing by a rowid's value
	//! (qod_search's maps each rowid to its segment and doc first, RowidMap, ruling 10).
	virtual void Consume(duckdb::ClientContext &context, SinkLocal &local, duckdb::Vector &rowids,
	                     duckdb::idx_t count) = 0;
	//! Once per local sink state, from Combine, under the global state's mutex.
	virtual void Merge(SinkLocal &local) = 0;
	//! Exactly once per execution, from Finalize, after every Merge (also with zero local states).
	virtual void Finish(duckdb::ClientContext &context) = 0;
	//! The output, after Finish, one chunk per call: `out` holds every column the function declares, in
	//! order, empty and initialized; the program fills up to STANDARD_VECTOR_SIZE rows and returns true, or
	//! returns false when it has no row left (out then empty). Called by the sink's source, one thread at a
	//! time (the source is not parallel). The default reads the materialized rows of OutputRows and
	//! OutputValue (qod_search's top k); a streaming program (qod_search_matches) overrides it. A failure
	//! throws, and fails the statement.
	virtual bool NextChunk(duckdb::ClientContext &context, duckdb::DataChunk &out);
	//! A materializing program's output: the number of rows, after Finish, and row `i` of column `c` (the
	//! function's declared output columns, in order). By default none.
	virtual duckdb::idx_t OutputRows() const {
		return 0;
	}
	virtual duckdb::Value OutputValue(duckdb::idx_t, duckdb::idx_t) const {
		return duckdb::Value();
	}

private:
	//! The default NextChunk's cursor over OutputRows.
	duckdb::idx_t output_cursor_ = 0;
};

//! Makes a program for one execution, from the bind data the operator owns.
using SinkProgramFactory =
    std::function<std::unique_ptr<SinkProgram>(duckdb::ClientContext &context, const duckdb::FunctionData &bind)>;

//! What marks a table function as sink-executed: held in its `function_info`, matched by identity
//! (dynamic_cast) by the swap, never by name.
struct SinkFunctionInfo : public duckdb::TableFunctionInfo {
	explicit SinkFunctionInfo(SinkProgramFactory factory_p) : factory(std::move(factory_p)) {
	}
	SinkProgramFactory factory;
};

//! Registers `function` through `loader` as sink-executed: sets its `in_out_function` and
//! `in_out_function_final` to callbacks that throw an ExecutorException (kNotThroughSink), never an
//! internal error, which would invalidate the database; sets its `function_info` to a
//! SinkFunctionInfo holding `factory`, then registers it. No global state.
void RegisterSinkFunction(duckdb::ExtensionLoader &loader, duckdb::TableFunction function,
                          SinkProgramFactory factory);

//! Registers the optimizer extension (optimize_function: the swap) and an OperatorExtension named
//! "qod_search_sink" (Bind returns an empty BoundStatement; Deserialize throws: these plans are never
//! serialized). Once per database.
void RegisterSearchSink(duckdb::DatabaseInstance &db);

constexpr const char *kNotThroughSink = "QoD: search_internal: not planned through the search sink";
//! The prefix of a shape refusal, followed by "<function> with <what>" (an InvalidInputException).
constexpr const char *kUnsupportedShape = "QoD: search_unsupported_plan: ";

// Test hooks: compiled only into the test programs' build of the node core (QOD_NODE_TEST_HOOKS, CMake's
// qod_node_core_hooks), never into qod-node.
#ifdef QOD_NODE_TEST_HOOKS
//! Test hooks (tests only). Counters are atomics bumped by the operator; the snapshot is a plain copy.
//! interrupts_answered: interrupt checks of the sink itself that threw DuckDB's InterruptException.
struct SinkCounters {
	duckdb::idx_t swaps, plans, combines, finalizes, locals, interrupt_checks, max_rows_between_checks,
	    interrupts_answered;
};
SinkCounters SinkCountersForTest();
void ResetSinkCountersForTest();
void SkipSwapForTest(bool skip);
//! When n > 0, the sink calls context.Interrupt() once n rows have been consumed in the execution, then
//! runs its own interrupt check in the same call. Only while n > 0 does the sink count the execution's
//! rows on a shared atomic.
void InterruptAfterRowsForTest(duckdb::idx_t n);

#endif // QOD_NODE_TEST_HOOKS

} // namespace search
} // namespace qod
