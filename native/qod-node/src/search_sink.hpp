#pragma once

// QoD Search (Phase 0 prototype, part 2 Task 7a, spec 7.6): the custom sink operator of qod-node's own
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

//! The largest rowid + 1 a drain probe's input may carry: 2^27 (a bitset of at most 16 MiB per local
//! state, its growth clamped there, 256 MiB for 16 local states; 24 MiB for one while it grows; the
//! 100M drain table's rowids are below 1e8 < 2^27). A value at or
//! above it fails the statement (kRowidOutOfRange) before any allocation for it. Probes only:
//! qod_search sizes nothing by an input value (Task 8), so a real table's rowids are not capped.
constexpr uint64_t kMaxRowidExclusive = uint64_t(1) << 27;
constexpr const char *kRowidOutOfRange = "QoD: search_internal: rowid out of range";

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
	//! concurrently on different locals; touches only `local`. A program that sizes memory by a
	//! rowid (the drain probes) refuses one outside [0, kMaxRowidExclusive) with kRowidOutOfRange.
	virtual void Consume(duckdb::ClientContext &context, SinkLocal &local, duckdb::Vector &rowids,
	                     duckdb::idx_t count) = 0;
	//! Once per local sink state, from Combine, under the global state's mutex.
	virtual void Merge(SinkLocal &local) = 0;
	//! Exactly once per execution, from Finalize, after every Merge (also with zero local states).
	virtual void Finish(duckdb::ClientContext &context) = 0;
	//! The number of output rows, after Finish, and row `i` of column `c` (the function's declared
	//! output columns, in order). The source state, not the program, holds the output cursor.
	virtual duckdb::idx_t OutputRows() const = 0;
	virtual duckdb::Value OutputValue(duckdb::idx_t row, duckdb::idx_t column) const = 0;
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

//! Test hooks (tests only). Counters are atomics bumped by the operator; the snapshot is a plain copy.
struct SinkCounters {
	duckdb::idx_t swaps, plans, combines, finalizes, locals, interrupt_checks, max_rows_between_checks;
};
SinkCounters SinkCountersForTest();
void ResetSinkCountersForTest();
void SkipSwapForTest(bool skip);
//! When n > 0, Consume calls context.Interrupt() once n rows have been consumed in the execution.
void InterruptAfterRowsForTest(duckdb::idx_t n);

} // namespace search
} // namespace qod
