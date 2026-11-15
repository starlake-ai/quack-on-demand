// Search Phase 0 part 2, Task 7a Step 2 (spec 7.6, route 2): a probe of the custom sink mechanism on
// DuckDB v2.0.0's release library, before any product code. Prints; the brief's expectations are
// judged by reading its `== ` lines.
//
// An in-memory DuckDB with qod-node's RegisterNodeAuthz (a recording PlanDecider that allows), a probe
// in-out table function qod_probe_sink(TABLE) -> (n BIGINT, locals BIGINT) whose function_info is a
// ProbeSinkInfo and whose in-out callbacks throw, a post_bind planner extension and an optimizer
// extension (pre_optimize_function and optimize_function), each appending its name to an event log.
// optimize_function swaps the LogicalGet whose function_info is a ProbeSinkInfo (by identity) for a
// LogicalProbeSink that owns the GET's bind data (moved) and copies its table index, column ids,
// projection ids and types; its CreatePlan plans the child and makes a ProbeSink physical operator,
// which owns every value it reads at execution. ProbeSink is a parallel sink and a single source: Sink
// counts rows into its local state, Combine adds them to the global state under a mutex, Finalize
// records that it ran after the last Combine, GetDataInternal emits one row (the count and the number
// of local states).
//
// Lines:
//   == order: <events of one statement>
//   == swap: replaced=<0|1> explain_has_probe_sink=<0|1>
//   == run threads=<t> runs=<n> rows_ok=<n> finalize_once=<n> finalize_after_all_combines=<n>
//          locals_gt1=<n> locals_histogram=<k:n,...> load_queries_so_far=<n> (the Q3 load's statements)
//   == bind: projected_ok=<0|1> join_ok=<0|1>
//   == prepared: swaps_after_two_executes=<n> finalizes=<n> rebind_swaps=<n> (client API prepare)
//   == prepared_untagged: swaps_after_two_executes=<n> finalizes=<n> (client API, no quack tag)
//   == prepared_plain_db: swaps_after_two_executes=<n> finalizes=<n> (client API, no qod-node)
//   == prepared_sql: swaps_after_two_executes=<n> finalizes=<n> probe_post_binds=<n> (SQL PREPARE)
// Unix only, label probe.

#include "authz.hpp"
#include "decide_client.hpp"
#include "node_hooks.hpp"
#include "test_handles.hpp"

#include "duckdb.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/execution/execution_context.hpp"
#include "duckdb/execution/physical_operator.hpp"
#include "duckdb/execution/physical_plan_generator.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/optimizer/optimizer_extension.hpp"
#include "duckdb/planner/operator/logical_extension_operator.hpp"
#include "duckdb/planner/operator/logical_get.hpp"
#include "duckdb/planner/operator_extension.hpp"
#include "duckdb/planner/planner_extension.hpp"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using namespace duckdb;

namespace {

int64_t now_ms = 1761200000000;

// ---- the event log of one statement on the measured connection ---------------------------------

std::mutex g_log_mutex;
std::vector<std::string> g_log;
ClientContext *g_measured = nullptr;

void Log(ClientContext &context, const std::string &event) {
	if (&context != g_measured) {
		return;
	}
	std::lock_guard<std::mutex> guard(g_log_mutex);
	g_log.push_back(event);
}

std::string TakeLog() {
	std::lock_guard<std::mutex> guard(g_log_mutex);
	std::string out;
	for (size_t i = 0; i < g_log.size(); i++) {
		out += (i == 0 ? "" : ",") + g_log[i];
	}
	g_log.clear();
	return out;
}

std::atomic<idx_t> g_swaps {0};
std::atomic<idx_t> g_finalizes {0};

// ---- the recording decider ---------------------------------------------------------------------

qod::DecideResult Recorder(const qod::DecideCall &call) {
	{
		std::lock_guard<std::mutex> guard(g_log_mutex);
		g_log.push_back("decider(" + call.kind + ")");
	}
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	return result;
}

struct FakeQuackSession : public ClientContextState {};

void TagAsQuackSession(Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, make_shared_ptr<FakeQuackSession>());
}

// ---- the probe table function ------------------------------------------------------------------

struct ProbeSinkInfo : public TableFunctionInfo {};

struct ProbeBindData : public TableFunctionData {};

unique_ptr<FunctionData> ProbeBind(ClientContext &, TableFunctionBindInput &input, vector<LogicalType> &types,
                                   vector<Identifier> &names) {
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		throw BinderException("qod_probe_sink takes one BIGINT input column");
	}
	types = {LogicalType::BIGINT, LogicalType::BIGINT};
	names.emplace_back("n");
	names.emplace_back("locals");
	return make_uniq<ProbeBindData>();
}

OperatorResultType ProbeInOut(ExecutionContext &, TableFunctionInput &, DataChunk &, DataChunk &) {
	throw InternalException("qod_probe_sink: not planned through the probe sink");
}

OperatorFinalizeResultType ProbeFinal(ExecutionContext &, TableFunctionInput &, DataChunk &) {
	throw InternalException("qod_probe_sink: not planned through the probe sink");
}

// ---- the physical operator ---------------------------------------------------------------------

struct ProbeGlobal : public GlobalSinkState {
	std::mutex mutex;
	idx_t rows = 0;
	std::atomic<idx_t> locals {0};
	idx_t combines = 0;
	idx_t finalizes = 0;
	bool finalize_after_all = false;
};

struct ProbeLocal : public LocalSinkState {
	idx_t rows = 0;
};

struct ProbeSource : public GlobalSourceState {
	bool done = false;
};

//! Per execution, what the run lines read (one statement at a time runs on the measured connection).
std::mutex g_exec_mutex;
idx_t g_last_rows = 0, g_last_locals = 0, g_last_finalizes = 0;
bool g_last_after_all = false;

class ProbeSink : public PhysicalOperator {
public:
	ProbeSink(PhysicalPlan &plan, vector<LogicalType> types, idx_t cardinality, unique_ptr<FunctionData> bind_p,
	          vector<ColumnIndex> column_ids_p, vector<ProjectionIndex> projection_ids_p)
	    : PhysicalOperator(plan, PhysicalOperatorType::EXTENSION, std::move(types), cardinality),
	      bind_data(std::move(bind_p)), column_ids(std::move(column_ids_p)),
	      projection_ids(std::move(projection_ids_p)) {
	}

	unique_ptr<FunctionData> bind_data;
	vector<ColumnIndex> column_ids;
	vector<ProjectionIndex> projection_ids;

	string GetName() const override {
		return "QOD_PROBE_SINK";
	}
	bool IsSink() const override {
		return true;
	}
	bool ParallelSink() const override {
		return true;
	}
	bool IsSource() const override {
		return true;
	}
	bool ParallelSource() const override {
		return false;
	}

	unique_ptr<GlobalSinkState> GetGlobalSinkState(ClientContext &) const override {
		return make_uniq<ProbeGlobal>();
	}
	unique_ptr<LocalSinkState> GetLocalSinkState(ExecutionContext &) const override {
		sink_state->Cast<ProbeGlobal>().locals++;
		return make_uniq<ProbeLocal>();
	}
	SinkResultType Sink(ExecutionContext &, DataChunk &chunk, OperatorSinkInput &input) const override {
		input.local_state.Cast<ProbeLocal>().rows += chunk.size();
		return SinkResultType::NEED_MORE_INPUT;
	}
	SinkCombineResultType Combine(ExecutionContext &, OperatorSinkCombineInput &input) const override {
		auto &global = input.global_state.Cast<ProbeGlobal>();
		std::lock_guard<std::mutex> guard(global.mutex);
		global.rows += input.local_state.Cast<ProbeLocal>().rows;
		global.combines++;
		return SinkCombineResultType::FINISHED;
	}
	SinkFinalizeType Finalize(Pipeline &, Event &, ClientContext &context,
	                          OperatorSinkFinalizeInput &input) const override {
		auto &global = input.global_state.Cast<ProbeGlobal>();
		std::lock_guard<std::mutex> guard(global.mutex);
		global.finalizes++;
		global.finalize_after_all = global.combines == global.locals.load();
		g_finalizes++;
		Log(context, "finalize");
		{
			std::lock_guard<std::mutex> exec(g_exec_mutex);
			g_last_rows = global.rows;
			g_last_locals = global.locals.load();
			g_last_finalizes = global.finalizes;
			g_last_after_all = global.finalize_after_all;
		}
		return SinkFinalizeType::READY;
	}
	unique_ptr<GlobalSourceState> GetGlobalSourceState(ClientContext &) const override {
		return make_uniq<ProbeSource>();
	}

protected:
	SourceResultType GetDataInternal(ExecutionContext &, DataChunk &chunk, OperatorSourceInput &input) const override {
		auto &source = input.global_state.Cast<ProbeSource>();
		if (source.done) {
			chunk.SetCardinality(0);
			return SourceResultType::FINISHED;
		}
		source.done = true;
		auto &global = sink_state->Cast<ProbeGlobal>();
		for (idx_t c = 0; c < chunk.ColumnCount(); c++) {
			auto at = projection_ids.empty() ? c : projection_ids[c].GetIndex();
			auto primary = column_ids[at].GetPrimaryIndex();
			auto value = primary == 0 ? static_cast<int64_t>(global.rows) : static_cast<int64_t>(global.locals.load());
			chunk.data[c].SetValue(0, Value::BIGINT(value));
		}
		chunk.SetCardinality(1);
		return SourceResultType::FINISHED;
	}
};

// ---- the logical operator ----------------------------------------------------------------------

class LogicalProbeSink : public LogicalExtensionOperator {
public:
	LogicalProbeSink(unique_ptr<FunctionData> bind_p, TableIndex table_index_p, vector<ColumnIndex> column_ids_p,
	                 vector<ProjectionIndex> projection_ids_p, vector<LogicalType> projected_types_p)
	    : bind_data(std::move(bind_p)), table_index(table_index_p), column_ids(std::move(column_ids_p)),
	      projection_ids(std::move(projection_ids_p)), projected_types(std::move(projected_types_p)) {
	}

	unique_ptr<FunctionData> bind_data;
	TableIndex table_index;
	vector<ColumnIndex> column_ids;
	vector<ProjectionIndex> projection_ids;
	vector<LogicalType> projected_types;

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
		return "QOD_PROBE_SINK";
	}
	string GetExtensionName() const override {
		return "qod_probe_sink";
	}
	void Serialize(Serializer &) const override {
		throw NotImplementedException("QoD: probe plans are not serialized");
	}
	PhysicalOperator &CreatePlan(ClientContext &, PhysicalPlanGenerator &planner) override {
		auto &child = planner.CreatePlan(*children[0]);
		auto &op = planner.Make<ProbeSink>(types, estimated_cardinality, std::move(bind_data), column_ids,
		                                   projection_ids);
		op.children.push_back(child);
		return op;
	}

protected:
	void ResolveTypes() override {
		types = projected_types;
	}
};

// ---- the extensions ----------------------------------------------------------------------------

void ProbePostBind(PlannerExtensionInput &input, BoundStatement &) {
	Log(input.context, "post_bind");
}

void ProbePreOptimize(OptimizerExtensionInput &input, unique_ptr<LogicalOperator> &) {
	Log(input.context, "pre_optimize");
}

void Swap(unique_ptr<LogicalOperator> &slot) {
	for (auto &child : slot->children) {
		Swap(child);
	}
	if (slot->type != LogicalOperatorType::LOGICAL_GET) {
		return;
	}
	auto &get = slot->Cast<LogicalGet>();
	if (!get.function.function_info || !dynamic_cast<ProbeSinkInfo *>(get.function.function_info.get())) {
		return;
	}
	get.ResolveOperatorTypes();
	if (get.children.size() != 1 || !get.projected_input.empty() || get.ordinality_idx.IsValid() ||
	    get.table_filters.HasFilters() || get.table_filters.HasMultiColumnFilters()) {
		throw InternalException("qod_probe_sink: unsupported plan shape");
	}
	for (auto &id : get.GetColumnIds()) {
		if (id.IsVirtualColumn() || id.GetPrimaryIndex() >= get.returned_types.size()) {
			throw InternalException("qod_probe_sink: unsupported plan shape");
		}
	}
	auto swapped = make_uniq<LogicalProbeSink>(std::move(get.bind_data), get.table_index, get.GetColumnIds(),
	                                           get.projection_ids, get.types);
	swapped->children = std::move(get.children);
	swapped->estimated_cardinality = get.estimated_cardinality;
	swapped->has_estimated_cardinality = get.has_estimated_cardinality;
	swapped->ResolveOperatorTypes();
	slot = std::move(swapped);
	g_swaps++;
}

void ProbeOptimize(OptimizerExtensionInput &input, unique_ptr<LogicalOperator> &plan) {
	Log(input.context, "optimize_function");
	Swap(plan);
}

class ProbeOperatorExtension : public OperatorExtension {
public:
	ProbeOperatorExtension() {
		Bind = [](ClientContext &, Binder &, OperatorExtensionInfo *, SQLStatement &) { return BoundStatement(); };
	}
	std::string GetName() override {
		return "qod_probe_sink";
	}
	unique_ptr<LogicalExtensionOperator> Deserialize(Deserializer &) override {
		throw NotImplementedException("QoD: probe plans are not serialized");
	}
};

void RegisterProbe(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_sink_probe");
	TableFunction probe("qod_probe_sink", {LogicalType::TABLE}, nullptr, ProbeBind);
	probe.in_out_function = ProbeInOut;
	probe.in_out_function_final = ProbeFinal;
	probe.function_info = make_shared_ptr<ProbeSinkInfo>();
	loader.RegisterFunction(probe);
	auto &config = DBConfig::GetConfig(db);
	PlannerExtension planner;
	planner.post_bind_function = ProbePostBind;
	PlannerExtension::Register(config, planner);
	OptimizerExtension optimizer;
	optimizer.pre_optimize_function = ProbePreOptimize;
	optimizer.optimize_function = ProbeOptimize;
	OptimizerExtension::Register(config, optimizer);
	OperatorExtension::Register(config, make_shared_ptr<ProbeOperatorExtension>());
}

// ---- helpers -----------------------------------------------------------------------------------

std::string FirstLine(const std::string &s) {
	auto nl = s.find('\n');
	return nl == std::string::npos ? s : s.substr(0, nl);
}

bool Exec(Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		std::cout << "   ERROR " << sql << ": " << FirstLine(result->GetError()) << std::endl;
		return false;
	}
	return true;
}

//! All values of the result as text, or the error prefixed "ERROR ".
std::string AllText(Connection &con, const std::string &sql, idx_t *rows = nullptr) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		return "ERROR " + FirstLine(result->GetError());
	}
	std::string out;
	idx_t count = 0;
	while (auto chunk = result->Fetch()) {
		if (chunk->size() == 0) {
			break;
		}
		for (idx_t r = 0; r < chunk->size(); r++, count++) {
			for (idx_t c = 0; c < chunk->ColumnCount(); c++) {
				out += chunk->GetValue(c, r).ToString() + "\t";
			}
			out += "\n";
		}
	}
	if (rows) {
		*rows = count;
	}
	return out;
}

bool Install(Connection &con, int64_t policy_version) {
	qod_test::MintSpec spec;
	spec.principal_id = "u-1";
	spec.exp = now_ms + 60000;
	spec.policy_version = policy_version;
	auto installed = con.Query(qod_test::Prelude(qod_test::MintWith(spec)));
	if (installed->HasError()) {
		std::cout << "   prelude refused: " << FirstLine(installed->GetError()) << std::endl;
		return false;
	}
	return true;
}

} // namespace

int main() {
	DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, Recorder);
	RegisterProbe(*db.instance);

	Connection setup(db);
	if (!Exec(setup, "CREATE TABLE t AS SELECT i::BIGINT AS i FROM range(2000000) r(i)") ||
	    !Exec(setup, "CREATE TABLE big AS SELECT i::BIGINT AS i, (i * 7)::BIGINT AS j FROM range(20000000) r(i)")) {
		return 1;
	}

	Connection client(db);
	TagAsQuackSession(client);
	if (!Install(client, 1)) {
		return 1;
	}
	g_measured = client.context.get();
	const std::string input = "(SELECT rowid FROM t WHERE i % 10 = 0)";
	const std::string statement = "SELECT n, locals FROM qod_probe_sink(" + input + ")";

	// order
	TakeLog();
	auto order_result = AllText(client, statement);
	std::cout << "== order: " << TakeLog() << (order_result.rfind("ERROR", 0) == 0 ? "  " + order_result : "")
	          << std::endl;

	// swap and EXPLAIN
	auto swaps_before = g_swaps.load();
	// The JSON form prints the operator's GetName() as is (the text form renders it "Qod Probe Sink").
	auto explain = AllText(client, "EXPLAIN (FORMAT json) " + statement);
	auto replaced = g_swaps.load() > swaps_before ? 1 : 0;
	if (std::getenv("QOD_P2_T7A_SHOW_EXPLAIN")) {
		std::cout << explain << std::endl;
	}
	std::cout << "== swap: replaced=" << replaced
	          << " explain_has_probe_sink=" << (explain.find("QOD_PROBE_SINK") != std::string::npos ? 1 : 0)
	          << " explain_has_inout=" << (explain.find("TABLE_INOUT") != std::string::npos ? 1 : 0)
	          << (explain.rfind("ERROR", 0) == 0 ? "  " + explain : "") << std::endl;
	TakeLog();

	// runs under the Q3 load
	std::atomic<bool> stop {false};
	std::atomic<idx_t> load_queries {0};
	std::thread load([&] {
		Connection loader(db);
		while (!stop.load()) {
			if (!loader.Query("SELECT sum(hash(i, j)) FROM big")->HasError()) {
				load_queries++;
			}
		}
	});
	for (int threads : {8, 16}) {
		Exec(setup, "SET threads = " + std::to_string(threads));
		int runs = 120, rows_ok = 0, finalize_once = 0, after_all = 0, gt1 = 0;
		std::map<idx_t, int> histogram;
		for (int r = 0; r < runs; r++) {
			auto finalizes_before = g_finalizes.load();
			auto result = client.Query(statement);
			TakeLog();
			if (result->HasError()) {
				std::cout << "   run error: " << FirstLine(result->GetError()) << std::endl;
				continue;
			}
			auto chunk = result->Fetch();
			idx_t n = chunk && chunk->size() == 1 ? chunk->GetValue(0, 0).GetValue<int64_t>() : 0;
			std::lock_guard<std::mutex> exec(g_exec_mutex);
			rows_ok += (n == 200000 && g_last_rows == 200000) ? 1 : 0;
			finalize_once += (g_finalizes.load() - finalizes_before == 1 && g_last_finalizes == 1) ? 1 : 0;
			after_all += g_last_after_all ? 1 : 0;
			gt1 += g_last_locals > 1 ? 1 : 0;
			histogram[g_last_locals]++;
		}
		std::ostringstream h;
		bool first = true;
		for (auto &entry : histogram) {
			h << (first ? "" : ",") << entry.first << ":" << entry.second;
			first = false;
		}
		std::cout << "== run threads=" << threads << " runs=" << runs << " rows_ok=" << rows_ok
		          << " finalize_once=" << finalize_once << " finalize_after_all_combines=" << after_all
		          << " locals_gt1=" << gt1 << " locals_histogram=" << h.str()
		          << " load_queries_so_far=" << load_queries.load() << std::endl;
	}
	stop = true;
	load.join();
	Exec(setup, "SET threads = 16");

	// bind: one projected column, and a join on the first
	idx_t rows = 0;
	auto projected = AllText(client, "SELECT locals FROM qod_probe_sink(" + input + ")", &rows);
	bool projected_ok = rows == 1 && projected.rfind("ERROR", 0) != 0 && std::stoll(projected) >= 1;
	auto joined = AllText(client,
	                      "SELECT p.n FROM qod_probe_sink(" + input + ") p JOIN (SELECT 200000::BIGINT AS k) o ON p.n = o.k",
	                      &rows);
	bool join_ok = rows == 1 && joined == "200000\t\n";
	std::cout << "== bind: projected_ok=" << (projected_ok ? 1 : 0) << " join_ok=" << (join_ok ? 1 : 0)
	          << (projected_ok ? "" : "  projected=" + FirstLine(projected))
	          << (join_ok ? "" : "  joined=" + FirstLine(joined)) << std::endl;
	TakeLog();

	// prepared, through the client API (Connection::Prepare, PreparedStatement::Execute: the path quack's
	// PREPARE and EXECUTE messages take)
	{
		auto swaps0 = g_swaps.load();
		auto finalizes0 = g_finalizes.load();
		std::string errors;
		TakeLog();
		auto prepared = client.Prepare("SELECT n FROM qod_probe_sink(" + input + ")");
		if (prepared->HasError()) {
			errors += " prepare=" + FirstLine(prepared->GetError());
		}
		auto run = [&](const std::string &label) {
			if (prepared->HasError()) {
				return;
			}
			auto result = prepared->Execute();
			if (result->HasError()) {
				errors += " " + label + "=" + FirstLine(result->GetError());
				return;
			}
			auto chunk = result->Fetch();
			if (!chunk || chunk->size() != 1 || chunk->GetValue(0, 0).GetValue<int64_t>() != 200000) {
				errors += " " + label + "=wrong result";
			}
		};
		run("execute1");
		run("execute2");
		auto swaps_after_two = g_swaps.load() - swaps0;
		auto finalizes_after_two = g_finalizes.load() - finalizes0;
		auto log_two = TakeLog();
		if (!Install(client, 2)) {
			errors += " reinstall";
		}
		TakeLog();
		run("execute3");
		auto log_three = TakeLog();
		std::cout << "== prepared: swaps_after_two_executes=" << swaps_after_two << " finalizes=" << finalizes_after_two
		          << " rebind_swaps=" << (g_swaps.load() - swaps0)
		          << " finalizes_after_rebind=" << (g_finalizes.load() - finalizes0)
		          << (errors.empty() ? "" : "  errors:" + errors) << std::endl;
		std::cout << "   prepared events, prepare and two executes: " << log_two << std::endl;
		std::cout << "   prepared events, execute after the policy version moved: " << log_three << std::endl;
	}

	// prepared through the client API on a connection with no quack tag (one of the node's own): no
	// qod-node session state asks for a rebind, so DuckDB executes the prepared physical plan again.
	{
		Connection internal(db);
		auto swaps0 = g_swaps.load();
		auto finalizes0 = g_finalizes.load();
		std::string errors;
		auto prepared = internal.Prepare("SELECT n FROM qod_probe_sink(" + input + ")");
		if (prepared->HasError()) {
			errors += " prepare=" + FirstLine(prepared->GetError());
		} else {
			for (int i = 0; i < 2; i++) {
				auto result = prepared->Execute();
				if (result->HasError()) {
					errors += " execute=" + FirstLine(result->GetError());
					continue;
				}
				auto chunk = result->Fetch();
				if (!chunk || chunk->size() != 1 || chunk->GetValue(0, 0).GetValue<int64_t>() != 200000) {
					errors += " execute=wrong result";
				}
			}
		}
		std::cout << "== prepared_untagged: swaps_after_two_executes=" << (g_swaps.load() - swaps0)
		          << " finalizes=" << (g_finalizes.load() - finalizes0) << (errors.empty() ? "" : "  errors:" + errors)
		          << std::endl;
	}

	// prepared through the client API on a database without qod-node (the probe's extensions only): no
	// client state asks for a rebind, so DuckDB executes the same physical plan twice, reading the bind
	// data the physical operator owns twice.
	{
		DuckDB plain(nullptr);
		RegisterProbe(*plain.instance);
		Connection con(plain);
		Exec(con, "CREATE TABLE t AS SELECT i::BIGINT AS i FROM range(2000000) r(i)");
		auto swaps0 = g_swaps.load();
		auto finalizes0 = g_finalizes.load();
		std::string errors;
		auto prepared = con.Prepare("SELECT n FROM qod_probe_sink(" + input + ")");
		if (prepared->HasError()) {
			errors += " prepare=" + FirstLine(prepared->GetError());
		} else {
			for (int i = 0; i < 2; i++) {
				auto result = prepared->Execute();
				if (result->HasError()) {
					errors += " execute=" + FirstLine(result->GetError());
					continue;
				}
				auto chunk = result->Fetch();
				if (!chunk || chunk->size() != 1 || chunk->GetValue(0, 0).GetValue<int64_t>() != 200000) {
					errors += " execute=wrong result";
				}
			}
		}
		std::cout << "== prepared_plain_db: swaps_after_two_executes=" << (g_swaps.load() - swaps0)
		          << " finalizes=" << (g_finalizes.load() - finalizes0) << (errors.empty() ? "" : "  errors:" + errors)
		          << std::endl;
	}

	// prepared, through SQL PREPARE / EXECUTE: DuckDB 2.0 rebinds such a statement at every EXECUTE when
	// it reads a table (Optimizer::Optimize sets always_require_rebind for a LOGICAL_PREPARE whose plan
	// holds a GET), so here every bind swaps once.
	{
		auto swaps0 = g_swaps.load();
		auto finalizes0 = g_finalizes.load();
		std::string errors;
		TakeLog();
		if (!Exec(client, "PREPARE p AS SELECT n FROM qod_probe_sink(" + input + ")")) {
			errors += " prepare";
		}
		for (int i = 0; i < 2; i++) {
			auto text = AllText(client, "EXECUTE p");
			if (text != "200000\t\n") {
				errors += " execute" + std::to_string(i + 1) + "=" + FirstLine(text);
			}
		}
		auto log = TakeLog();
		size_t binds = 0;
		for (size_t at = log.find("post_bind"); at != std::string::npos; at = log.find("post_bind", at + 1)) {
			binds++;
		}
		std::cout << "== prepared_sql: swaps_after_two_executes=" << (g_swaps.load() - swaps0)
		          << " finalizes=" << (g_finalizes.load() - finalizes0) << " probe_post_binds=" << binds
		          << (errors.empty() ? "" : "  errors:" + errors) << std::endl;
		std::cout << "   prepared_sql events: " << log << std::endl;
	}
	return 0;
}
