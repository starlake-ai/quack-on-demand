#include "search_sink.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/execution/execution_context.hpp"
#include "duckdb/execution/physical_operator.hpp"
#include "duckdb/execution/physical_plan_generator.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/optimizer/optimizer_extension.hpp"
#include "duckdb/planner/operator/logical_extension_operator.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator_extension.hpp"
#include "duckdb/planner/table_filter_set.hpp"

#include <algorithm>
#include <atomic>
#include <mutex>

namespace qod {
namespace search {
namespace {

using namespace duckdb;

// ---- counters and test hooks -------------------------------------------------------------------

struct Counters {
	std::atomic<idx_t> swaps {0}, plans {0}, combines {0}, finalizes {0}, locals {0}, interrupt_checks {0},
	    max_rows_between_checks {0};
};
Counters g_counters;
std::atomic<bool> g_skip_swap {false};
std::atomic<idx_t> g_interrupt_after {0};

void RaiseTo(std::atomic<idx_t> &target, idx_t value) {
	auto current = target.load();
	while (value > current && !target.compare_exchange_weak(current, value)) {
	}
}

//! Rows between two interrupt checks of one local sink state: a check once at least this many rows
//! arrived in it since its last one, so with chunks of at most STANDARD_VECTOR_SIZE rows no thread
//! consumes more than 65,536 rows between checks. Counted per local state, not on the execution's
//! shared count: with 16 threads adding to a shared count, the thread that crosses a threshold can lag
//! the others, and the gap it records then exceeds 65,536 (S9 measured 69,632).
constexpr idx_t kCheckEvery = 65536 - STANDARD_VECTOR_SIZE;

// ---- execution states --------------------------------------------------------------------------

struct SinkGlobalState : public GlobalSinkState {
	std::unique_ptr<SinkProgram> program;
	std::mutex mutex;
	std::atomic<idx_t> rows_seen {0};
	std::atomic<bool> interrupted {false};
	std::atomic<bool> finished {false};
};

struct SinkLocalState : public LocalSinkState {
	std::unique_ptr<SinkLocal> local;
	idx_t rows_since_check = 0;
};

struct SinkSourceState : public GlobalSourceState {
	idx_t cursor = 0;
};

// ---- the physical operator ---------------------------------------------------------------------

class PhysicalQodSearchSink : public PhysicalOperator {
public:
	PhysicalQodSearchSink(PhysicalPlan &plan, vector<LogicalType> types, idx_t cardinality, SinkProgramFactory factory_p,
	                      unique_ptr<FunctionData> bind_data_p, vector<ColumnIndex> column_ids_p,
	                      vector<ProjectionIndex> projection_ids_p, string function_name_p)
	    : PhysicalOperator(plan, PhysicalOperatorType::EXTENSION, std::move(types), cardinality),
	      factory(std::move(factory_p)), bind_data(std::move(bind_data_p)), column_ids(std::move(column_ids_p)),
	      projection_ids(std::move(projection_ids_p)), function_name(std::move(function_name_p)) {
	}

	SinkProgramFactory factory;
	unique_ptr<FunctionData> bind_data;
	vector<ColumnIndex> column_ids;
	vector<ProjectionIndex> projection_ids;
	string function_name;

	string GetName() const override {
		return "QOD_SEARCH_SINK";
	}
	InsertionOrderPreservingMap<string> ParamsToString() const override {
		InsertionOrderPreservingMap<string> result;
		result["Function"] = function_name;
		return result;
	}

	// sink
	bool IsSink() const override {
		return true;
	}
	bool ParallelSink() const override {
		return true;
	}
	bool SinkOrderDependent() const override {
		return false;
	}
	unique_ptr<GlobalSinkState> GetGlobalSinkState(ClientContext &context) const override {
		auto state = make_uniq<SinkGlobalState>();
		state->program = factory(context, *bind_data);
		if (!state->program) {
			throw ExecutorException("QoD: search_internal: no sink program");
		}
		return std::move(state);
	}
	unique_ptr<LocalSinkState> GetLocalSinkState(ExecutionContext &) const override {
		auto &global = sink_state->Cast<SinkGlobalState>();
		auto state = make_uniq<SinkLocalState>();
		state->local = global.program->NewLocal();
		g_counters.locals++;
		return std::move(state);
	}
	SinkResultType Sink(ExecutionContext &context, DataChunk &chunk, OperatorSinkInput &input) const override {
		auto &global = input.global_state.Cast<SinkGlobalState>();
		auto &local = input.local_state.Cast<SinkLocalState>();
		global.program->Consume(context.client, *local.local, chunk.data[0], chunk.size());
		auto after = global.rows_seen.fetch_add(chunk.size()) + chunk.size();
		auto hook = g_interrupt_after.load();
		if (hook > 0 && after >= hook && !global.interrupted.exchange(true)) {
			context.client.Interrupt();
		}
		local.rows_since_check += chunk.size();
		if (local.rows_since_check >= kCheckEvery) {
			g_counters.interrupt_checks++;
			RaiseTo(g_counters.max_rows_between_checks, local.rows_since_check);
			local.rows_since_check = 0;
			context.client.InterruptCheck();
		}
		return SinkResultType::NEED_MORE_INPUT;
	}
	SinkCombineResultType Combine(ExecutionContext &, OperatorSinkCombineInput &input) const override {
		auto &global = input.global_state.Cast<SinkGlobalState>();
		auto &local = input.local_state.Cast<SinkLocalState>();
		std::lock_guard<std::mutex> guard(global.mutex);
		global.program->Merge(*local.local);
		g_counters.combines++;
		return SinkCombineResultType::FINISHED;
	}
	SinkFinalizeType Finalize(Pipeline &, Event &, ClientContext &context,
	                          OperatorSinkFinalizeInput &input) const override {
		context.InterruptCheck();
		auto &global = input.global_state.Cast<SinkGlobalState>();
		if (global.finished.exchange(true)) {
			// Never seen (S3): an ordinary error, as an internal one would invalidate the whole database.
			throw ExecutorException("QoD: search_internal: finalize ran twice");
		}
		std::lock_guard<std::mutex> guard(global.mutex);
		global.program->Finish(context);
		g_counters.finalizes++;
		return SinkFinalizeType::READY;
	}

	// source
	bool IsSource() const override {
		return true;
	}
	bool ParallelSource() const override {
		return false;
	}
	unique_ptr<GlobalSourceState> GetGlobalSourceState(ClientContext &) const override {
		return make_uniq<SinkSourceState>();
	}

protected:
	SourceResultType GetDataInternal(ExecutionContext &, DataChunk &chunk, OperatorSourceInput &input) const override {
		auto &source = input.global_state.Cast<SinkSourceState>();
		auto &program = *sink_state->Cast<SinkGlobalState>().program;
		auto total = program.OutputRows();
		idx_t count = 0;
		while (source.cursor < total && count < STANDARD_VECTOR_SIZE) {
			for (idx_t c = 0; c < chunk.ColumnCount(); c++) {
				auto at = projection_ids.empty() ? c : projection_ids[c].GetIndex();
				chunk.data[c].SetValue(count, program.OutputValue(source.cursor, column_ids[at].GetPrimaryIndex()));
			}
			source.cursor++;
			count++;
		}
		chunk.SetCardinality(count);
		return source.cursor < total ? SourceResultType::HAVE_MORE_OUTPUT : SourceResultType::FINISHED;
	}
};

// ---- the logical operator ----------------------------------------------------------------------

class LogicalQodSearchSink : public LogicalExtensionOperator {
public:
	LogicalQodSearchSink(unique_ptr<FunctionData> bind_data_p, TableIndex table_index_p,
	                     vector<ColumnIndex> column_ids_p, vector<ProjectionIndex> projection_ids_p,
	                     vector<LogicalType> returned_types_p, vector<LogicalType> projected_types_p,
	                     vector<LogicalType> input_types_p, SinkProgramFactory factory_p, string function_name_p)
	    : bind_data(std::move(bind_data_p)), table_index(table_index_p), column_ids(std::move(column_ids_p)),
	      projection_ids(std::move(projection_ids_p)), returned_types(std::move(returned_types_p)),
	      projected_types(std::move(projected_types_p)), input_types(std::move(input_types_p)),
	      factory(std::move(factory_p)), function_name(std::move(function_name_p)) {
	}

	unique_ptr<FunctionData> bind_data;
	TableIndex table_index;
	vector<ColumnIndex> column_ids;
	vector<ProjectionIndex> projection_ids;
	vector<LogicalType> returned_types;
	vector<LogicalType> projected_types;
	vector<LogicalType> input_types;
	SinkProgramFactory factory;
	string function_name;

	vector<ColumnBinding> GetColumnBindings() override {
		vector<ColumnBinding> result;
		if (projection_ids.empty()) {
			for (idx_t i = 0; i < column_ids.size(); i++) {
				result.emplace_back(table_index, ProjectionIndex(i));
			}
		} else {
			for (auto &id : projection_ids) {
				result.emplace_back(table_index, id);
			}
		}
		return result;
	}
	vector<TableIndex> GetTableIndex() const override {
		return {table_index};
	}
	string GetName() const override {
		return "QOD_SEARCH_SINK";
	}
	InsertionOrderPreservingMap<string> ParamsToString() const override {
		InsertionOrderPreservingMap<string> result;
		result["Function"] = function_name;
		return result;
	}
	string GetExtensionName() const override {
		return "qod_search_sink";
	}
	void Serialize(Serializer &) const override {
		throw NotImplementedException("QoD: search plans are not serialized");
	}
	PhysicalOperator &CreatePlan(ClientContext &, PhysicalPlanGenerator &planner) override {
		if (!bind_data || children.size() != 1) {
			throw ExecutorException("QoD: search_internal: the search sink was planned twice or without its input");
		}
		auto &child = planner.CreatePlan(*children[0]);
		if (child.types != input_types) {
			throw InvalidInputException(std::string(kUnsupportedShape) + function_name +
			                            " with an input whose column types changed in planning");
		}
		auto &op = planner.Make<PhysicalQodSearchSink>(types, estimated_cardinality, factory, std::move(bind_data),
		                                               column_ids, projection_ids, function_name);
		op.children.push_back(child);
		g_counters.plans++;
		return op;
	}

protected:
	void ResolveTypes() override {
		types = projected_types;
	}
};

// ---- the swap ----------------------------------------------------------------------------------

optional_ptr<SinkFunctionInfo> SinkInfoOf(LogicalOperator &op) {
	if (op.type != LogicalOperatorType::LOGICAL_GET) {
		return nullptr;
	}
	auto &get = op.Cast<LogicalGet>();
	if (!get.function.function_info) {
		return nullptr;
	}
	return dynamic_cast<SinkFunctionInfo *>(get.function.function_info.get());
}

//! The one shape the sink reproduces exactly: one input, no projected input, no ordinality, no filter,
//! every column a real output column. Empty when it holds, else what differs, for the refusal's text.
std::string UnsupportedShape(LogicalGet &get) {
	if (!get.projected_input.empty()) {
		return "a LATERAL or correlated input";
	}
	if (get.ordinality_idx.IsValid() || get.source_ordinality != OrdinalityType::WITHOUT_ORDINALITY) {
		return "WITH ORDINALITY";
	}
	if (get.children.size() != 1 || get.input_table_types.size() != 1) {
		return "an input other than one subquery of one column";
	}
	if (get.table_filters.HasFilters() || get.table_filters.HasMultiColumnFilters() ||
	    (get.dynamic_filters && get.dynamic_filters->HasFilters())) {
		return "a filter pushed into the call";
	}
	for (auto &id : get.GetColumnIds()) {
		if (!id.HasPrimaryIndex() || id.IsVirtualColumn() || id.HasChildren() || id.IsPushdownExtract() ||
		    id.GetPrimaryIndex() >= get.returned_types.size()) {
			return "a column the call does not output";
		}
	}
	for (auto &projection : get.projection_ids) {
		if (projection.GetIndex() >= get.GetColumnIds().size()) {
			return "a column the call does not output";
		}
	}
	return "";
}

void Swap(unique_ptr<LogicalOperator> &slot) {
	auto info = SinkInfoOf(*slot);
	if (info && !g_skip_swap.load()) {
		auto &get = slot->Cast<LogicalGet>();
		// The column ids as planning would leave them (an empty list gets the GET's first column).
		get.ResolveOperatorTypes();
		auto shape = UnsupportedShape(get);
		if (!shape.empty()) {
			// A user's query, not an assertion: an ordinary error, no stack trace.
			throw InvalidInputException(std::string(kUnsupportedShape) + get.function.GetName().GetIdentifierName() +
			                            " with " + shape);
		}
		auto swapped = make_uniq<LogicalQodSearchSink>(
		    std::move(get.bind_data), get.table_index, get.GetColumnIds(), get.projection_ids, get.returned_types,
		    get.types, get.input_table_types, info->factory, get.function.GetName().GetIdentifierName());
		swapped->children = std::move(get.children);
		swapped->estimated_cardinality = get.estimated_cardinality;
		swapped->has_estimated_cardinality = get.has_estimated_cardinality;
		swapped->ResolveOperatorTypes();
		slot = std::move(swapped);
		g_counters.swaps++;
	}
	for (auto &child : slot->children) {
		Swap(child);
	}
}

void OptimizeSwap(OptimizerExtensionInput &, unique_ptr<LogicalOperator> &plan) {
	Swap(plan);
}

class SearchSinkOperatorExtension : public OperatorExtension {
public:
	SearchSinkOperatorExtension() {
		Bind = [](ClientContext &, Binder &, OperatorExtensionInfo *, SQLStatement &) { return BoundStatement(); };
	}
	std::string GetName() override {
		return "qod_search_sink";
	}
	unique_ptr<LogicalExtensionOperator> Deserialize(Deserializer &) override {
		throw NotImplementedException("QoD: search plans are not serialized");
	}
};

// An unswapped call fails, with an ordinary error: DuckDB 2.0 invalidates the whole database on an
// internal error raised during execution (ClientContext::FailQueryInternal), which would stop the node
// for every tenant. No search path raises one (CTest search_no_internal_exception).
OperatorResultType NotThroughSink(ExecutionContext &, TableFunctionInput &, DataChunk &, DataChunk &) {
	throw ExecutorException(kNotThroughSink);
}

OperatorFinalizeResultType NotThroughSinkFinal(ExecutionContext &, TableFunctionInput &, DataChunk &) {
	throw ExecutorException(kNotThroughSink);
}

} // namespace

void RegisterSinkFunction(ExtensionLoader &loader, TableFunction function, SinkProgramFactory factory) {
	function.in_out_function = NotThroughSink;
	function.in_out_function_final = NotThroughSinkFinal;
	function.function_info = make_shared_ptr<SinkFunctionInfo>(std::move(factory));
	loader.RegisterFunction(function);
}

void RegisterSearchSink(DatabaseInstance &db) {
	auto &config = DBConfig::GetConfig(db);
	OptimizerExtension optimizer;
	optimizer.optimize_function = OptimizeSwap;
	OptimizerExtension::Register(config, optimizer);
	OperatorExtension::Register(config, make_shared_ptr<SearchSinkOperatorExtension>());
}

SinkCounters SinkCountersForTest() {
	return SinkCounters {g_counters.swaps.load(),     g_counters.plans.load(),
	                     g_counters.combines.load(),  g_counters.finalizes.load(),
	                     g_counters.locals.load(),    g_counters.interrupt_checks.load(),
	                     g_counters.max_rows_between_checks.load()};
}

void ResetSinkCountersForTest() {
	g_counters.swaps = 0;
	g_counters.plans = 0;
	g_counters.combines = 0;
	g_counters.finalizes = 0;
	g_counters.locals = 0;
	g_counters.interrupt_checks = 0;
	g_counters.max_rows_between_checks = 0;
}

void SkipSwapForTest(bool skip) {
	g_skip_swap = skip;
}

void InterruptAfterRowsForTest(idx_t n) {
	g_interrupt_after = n;
}

} // namespace search
} // namespace qod
