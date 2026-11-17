#include "search_functions.hpp"

#include "crypto.hpp"
#include "describer.hpp"
#include "node_hooks.hpp"
#include "search_fallback.hpp"
#include "search_knobs.hpp"
#include "search_lineage.hpp"
#include "search_memory.hpp"
#include "search_records.hpp"
#include "search_rowmap.hpp"
#include "search_segments.hpp"
#include "search_sink.hpp"
#include "search_snapshot.hpp"
#include "search_visibility.hpp"

#include "qod_search.h"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/common/vector/flat_vector.hpp"
#include "duckdb/common/vector/unified_vector_format.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/planner/extension_callback.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"
#include "duckdb/transaction/meta_transaction.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <map>
#include <iostream>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <system_error>
#include <thread>
#include <vector>

namespace qod {
namespace {

using namespace duckdb;

//! The search's settings, read once, at registration (search_knobs.hpp): the per-query memory budget as
//! configured (0: a quarter of memory_limit, resolved per query by QueryBudget) and the largest k.
std::atomic<uint64_t> g_query_memory {search::kQueryMemoryKnob.fallback};
std::atomic<uint64_t> g_max_k {search::kMaxKKnob.fallback};

#ifdef QOD_NODE_TEST_HOOKS
//! The test hooks' state (the test programs' build only: qod-node records nothing and runs no hook).
std::atomic<bool> g_cancel_in_finish {false};
std::atomic<uint64_t> g_interrupt_between {0};
std::atomic<uint64_t> g_interrupt_between_loads {0};
std::atomic<uint64_t> g_record_lookups {0};
std::mutex g_hooks_lock;
std::function<void(bool)> g_resolve_observer;
std::function<void(ClientContext &, bool)> g_hook_observer;
std::function<void(ClientContext &)> g_call_observer;
SearchCallRecord g_last_call;
#endif

void ObserveResolve(bool begin) {
#ifdef QOD_NODE_TEST_HOOKS
	std::function<void(bool)> observer;
	{
		std::lock_guard<std::mutex> guard(g_hooks_lock);
		observer = g_resolve_observer;
	}
	if (observer) {
		observer(begin);
	}
#else
	(void)begin;
#endif
}

//! One query's memory budget (QOD_SEARCH_QUERY_MEMORY): the configured bytes, or, when 0 (the default), a quarter
//! of the database's memory_limit as it is at this query (SET memory_limit moves it from the next query on), so
//! one search never takes the whole node.
uint64_t QueryBudget(ClientContext &context) {
	auto configured = g_query_memory.load();
	if (configured != 0) {
		return configured;
	}
	return static_cast<uint64_t>(BufferManager::GetBufferManager(context).GetMaxMemory()) / 4;
}

//! A library error's text, freed.
std::string TakeError(char *err) {
	std::string text = err ? err : "search_internal: no message";
	qs_string_free(err);
	return text;
}

//! A refusal of the call's shape or of the index's state, not of the principal: an ordinary error
//! (INVALID_ARGUMENT), never booked as a denial (the plan's error table, review I2).
[[noreturn]] void SearchInvalid(const std::string &why) {
	throw InvalidInputException("QoD: " + why);
}

idx_t WordsFor(uint32_t docs) {
	return (static_cast<idx_t>(docs) + 63) / 64;
}

// ---- binding --------------------------------------------------------------------------------------

//! qod_search's and qod_search_matches' bind: the positional (table, columns, query), the named k
//! (qod_search), with_score (qod_search_matches) and query_json, then rule 6 on the query's text.
unique_ptr<FunctionData> SearchBindAny(ClientContext &context, TableFunctionBindInput &input,
                                       vector<LogicalType> &types, vector<Identifier> &names, bool matches) {
	const char *function = matches ? "qod_search_matches" : "qod_search";
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		SearchInvalid("search_input_not_governed: the input must have exactly one column, a BIGINT rowid");
	}
	// The values below are folded: rule 7 (constant arguments) is judged on the statement's text, in
	// post_bind (FindSearchCalls), since DuckDB moves the parsed arguments out while binding.
	// The TABLE argument holds no value; the scalar arguments follow it.
	auto &inputs = input.inputs;
	idx_t at = inputs.size() == 4 ? 1 : 0;
	if (inputs.size() - at != 3) {
		throw BinderException(std::string(function) + " takes (input, table, columns, query)");
	}
	auto &table = inputs[at];
	auto &columns = inputs[at + 1];
	auto &query = inputs[at + 2];
	if (table.IsNull() || columns.IsNull() || query.IsNull()) {
		SearchInvalid("search_invalid_argument: table, columns and query cannot be NULL");
	}
	auto data = make_uniq<search::SearchBindData>();
	data->matches = matches;
	data->table = table.GetValue<string>();
	auto resolved = search::ResolveSearchTable(context, data->table);
	auto &listed = ListValue::GetChildren(columns);
	if (listed.empty()) {
		SearchInvalid("search_columns_unsupported: columns names no column");
	}
	for (auto &value : listed) {
		if (value.IsNull()) {
			SearchInvalid("search_columns_unsupported: columns holds a NULL");
		}
		auto column = value.GetValue<string>();
		// The column as the table names it (DuckDB matches column names without case), as the describer reads
		// it; FindSearchCalls refuses a table or column that resolves to none.
		if (resolved) {
			auto spelled = search::TableColumnName(*resolved, column);
			if (!spelled.empty()) {
				column = spelled;
			}
		}
		data->columns.push_back(column);
	}
	data->query = query.GetValue<string>();
	// A call that names no k takes min(10, QOD_SEARCH_MAX_K) (LEAD RULING, Task 16 fix round 1); a named k above
	// the bound is refused below.
	auto max_k = static_cast<int64_t>(g_max_k.load());
	int64_t k = std::min<int64_t>(10, max_k);
	for (auto &named : input.named_parameters) {
		auto name = StringUtil::Lower(named.first.GetIdentifierName());
		if (name == "k") {
			// NULL: the call named no k (or named it NULL), so the default above holds.
			if (!named.second.IsNull()) {
				k = named.second.GetValue<int64_t>();
			}
		} else if (name == "query_json") {
			if (!named.second.IsNull()) {
				SearchInvalid("search_query_unsupported: query_json is Phase 3");
			}
		} else if (name == "with_score") {
			if (named.second.IsNull()) {
				SearchInvalid("search_invalid_argument: with_score cannot be NULL");
			}
			data->with_score = named.second.GetValue<bool>();
		}
	}
	if (!matches && (k < 1 || k > max_k)) {
		SearchInvalid("search_invalid_argument: k must be between 1 and " + std::to_string(max_k));
	}
	data->k = static_cast<uint32_t>(matches ? 1 : k);
	// Rule 6 and the query limits (spec 9), before any segment is read. Only the columns' names matter to
	// the check, so they stand with placeholder field ids here; the search checks again with the record's.
	std::vector<QsField> fields;
	for (size_t i = 0; i < data->columns.size(); i++) {
		fields.push_back(QsField {data->columns[i].c_str(), static_cast<int64_t>(i)});
	}
	char *err = nullptr;
	if (qs_check_query(data->query.c_str(), fields.data(), fields.size(), &err) != 0) {
		auto text = TakeError(err);
		if (StringUtil::StartsWith(text, "search_invalid_argument")) {
			// Two call columns equal but for ASCII case, or more than 32.
			SearchInvalid("search_columns_unsupported: " + text.substr(text.find(':') + 1));
		}
		SearchInvalid(text);
	}
	if (matches) {
		types = {LogicalType::BIGINT, LogicalType::FLOAT};
		names.emplace_back("rowid");
		names.emplace_back("score");
	} else {
		types = {LogicalType::BIGINT, LogicalType::FLOAT, LogicalType::INTEGER};
		names.emplace_back("rowid");
		names.emplace_back("score");
		names.emplace_back("rank");
	}
	return std::move(data);
}

unique_ptr<FunctionData> SearchBind(ClientContext &context, TableFunctionBindInput &input, vector<LogicalType> &types,
                                    vector<Identifier> &names) {
	return SearchBindAny(context, input, types, names, false);
}

unique_ptr<FunctionData> MatchesBind(ClientContext &context, TableFunctionBindInput &input,
                                     vector<LogicalType> &types, vector<Identifier> &names) {
	return SearchBindAny(context, input, types, names, true);
}

// ---- execution ------------------------------------------------------------------------------------

//! A local sink state: its allowed bitsets, one per segment, allocated from the buffer manager when a rowid first
//! lands in it, and the rowid map's hint (the scan reads file by file).
struct SearchLocal : public search::SinkLocal {
	std::vector<search::BufferWords> allowed;
	uint32_t hint = search::RowidMap::kNoHint;
	//! The bitset bytes this local state charged, and the segments it allocated a bitset for (Task 11 B1).
	uint64_t charged = 0;
	std::vector<uint32_t> touched;
	bool started = false;
	std::chrono::steady_clock::time_point first;
};

//! One segment of the call: an indexed data file's bundle or an in-memory fallback segment.
struct SearchSegment {
	std::shared_ptr<const search::OpenSegment> open;
	size_t file_index = 0;
	std::vector<uint64_t> live; // exactly WordsFor(num_docs) words
	size_t liveness_words = 0;  // FileLiveness::words.size() before the resize (0 for a fallback segment)
	bool fallback = false;      // an in-memory segment of the fallback
};

//! Runs `call` with a watcher thread that sets `cancel` once the client interrupts the query (the library
//! polls it, spec 9). The thread is joined before this returns, whatever `call` does.
int WithCancelWatcher(ClientContext &context, std::atomic<uint8_t> &cancel, const std::function<int()> &call) {
	std::mutex lock;
	std::condition_variable wake;
	bool done = false;
	std::thread watcher;
	try {
		watcher = std::thread([&] {
			std::unique_lock<std::mutex> guard(lock);
			while (!done) {
				if (context.IsInterrupted()) {
					cancel = 1;
				}
				wake.wait_for(guard, std::chrono::milliseconds(2));
			}
		});
	} catch (std::system_error &) {
		// The watcher thread could not start (review minor 8): an ordinary error, nothing searched.
		throw InvalidInputException("QoD: search_internal: the search could not start its cancel watcher");
	}
	int rc = 0;
	try {
		rc = call();
	} catch (...) {
		{
			std::lock_guard<std::mutex> guard(lock);
			done = true;
		}
		wake.notify_all();
		watcher.join();
		throw;
	}
	{
		std::lock_guard<std::mutex> guard(lock);
		done = true;
	}
	wake.notify_all();
	watcher.join();
	return rc;
}

//! What qod_search and qod_search_matches share (spec 7.1 steps 3 to 6): the factory loads the call's
//! segments and builds its rowid map; Consume maps each input rowid to its one live (segment, doc) and sets the
//! allowed bit; Merge ORs the local bitsets. Finish is each function's. Every allocation in proportion to the data
//! is charged to the call's QueryMemory before it is made (spec 9, Task 11).
class SearchExecution : public search::SinkProgram {
public:
	SearchExecution(ClientContext &context, const search::SearchBindData &bind)
	    : bind_(bind), buffers_(BufferManager::GetBufferManager(context)) {
		auto target = bind.target;
		if (!target || !target->attached || !target->view) {
			throw InvalidInputException("QoD: search_unbound: the search call was not bound to its target");
		}
		if (MetaTransaction::Get(context).global_transaction_id != target->transaction_id) {
			throw InvalidInputException("QoD: search_unbound: the search call was bound in another transaction");
		}
		// Review minor 6 and Task 14: the view was resolved at bind, with the transaction's own writes as of that
		// query. In a later query of a transaction that has modified the target's database (a plan executed again
		// without a rebind), a write since could have left it stale: refused. The statement that resolved it (a
		// write in the same statement included: its rows are not the statement's input) runs.
		auto &meta = MetaTransaction::Get(context);
		auto modified = meta.ModifiedDatabase();
		if (modified && target->table && modified.get() == &target->table->ParentCatalog().GetAttached() &&
		    meta.GetActiveQuery() != target->query_id) {
			throw InvalidInputException("QoD: search_unbound: the plan was not rebound after the transaction's write to " +
			                            target->table->ParentCatalog().GetName().GetIdentifierName());
		}
		target_ = target;
		memory_ = std::make_unique<search::QueryMemory>(QueryBudget(context), Allowed());
		for (auto &field : target->call_fields) {
			call_fields_.push_back(QsField {field.column.c_str(), field.field_id});
		}
		auto &view = *target->view;
		auto &record = target->record;
		// Spec 9 (Task 11): a live data file whose segment is missing or refused, for whatever reason, and the
		// inlined rows live at S, are indexed for this query into in-memory segments (search_fallback.hpp), up
		// to the cap; past it the search fails with search_index_behind, never a different answer (invariant 3).
		std::vector<search::DataFileRef> unindexed;
		for (size_t f = 0; f < view.files.size(); f++) {
			// Between segments (spec 9): a bundle may be read from storage here.
			if (context.IsInterrupted()) {
				throw InterruptException();
			}
#ifdef QOD_NODE_TEST_HOOKS
			if (g_interrupt_between_loads.load() > 0 && f == g_interrupt_between_loads.load()) {
				context.Interrupt();
			}
#endif
			// A copy: the view may be the liveness cache's, shared and never modified; LoadSegment sets the
			// copy's internal-column flags from the bundle.
			auto file = view.files[f];
			auto &liveness = view.liveness[f];
			if (liveness.live_count == 0) {
				continue;
			}
			// The transaction's own data file (Task 14): no bundle can exist for it, so none is looked up (and no miss
			// cached); its live rows go to the fallback, within both caps.
			if (file.transaction_local) {
				unindexed.push_back(std::move(file));
				continue;
			}
			auto path = search::SegmentPath(record.bundle_root, record.index_id, file.bundle_key);
			auto open = search::LoadSegment(context, path, record, file, *memory_);
			if (!open) {
				unindexed.push_back(std::move(file));
				continue;
			}
			SearchSegment segment;
			segment.open = open;
			segment.file_index = f;
			segment.liveness_words = liveness.WordCount();
			// Spec 7.5 (Task 10): the file's liveness at S as the segment's doc-id bitset, exactly
			// ceil(num_docs / 64) words, matched by (file index, position). A liveness past the bundle's rows
			// refuses the search.
			memory_->Charge(WordsFor(open->num_docs) * sizeof(uint64_t), "live bitsets");
			segment.live = search::LiveDocs(liveness, open->num_docs);
			segments_.push_back(std::move(segment));
		}
		bool inlined = false;
		for (auto &t : view.inlined_tables) {
			inlined = inlined || t.live_rows > 0;
		}
		if (!unindexed.empty() || inlined) {
			if (!target->table) {
				throw InvalidInputException("QoD: search_unbound: the search call was not bound to its target");
			}
			auto start = std::chrono::steady_clock::now();
			std::set<int64_t> required;
			for (auto &field : target->call_fields) {
				required.insert(field.field_id);
			}
			auto fallback = search::BuildFallbackSegments(
			    context, view, unindexed, record, required, search::MaxUnindexedRows(), search::MaxUnindexedSources(),
			    search::FallbackScan {*target->table, target->at_clause.get()}, *memory_, Allowed());
			for (auto &f : fallback) {
				SearchSegment segment;
				segment.open = f.open;
				segment.file_index = f.inlined ? SIZE_MAX : f.file_index;
				segment.fallback = true;
				// Every doc of a fallback segment is live at S (the scan returned only those).
				memory_->Charge(WordsFor(f.open->num_docs) * sizeof(uint64_t), "live bitsets");
				segment.live.assign(WordsFor(f.open->num_docs), ~uint64_t(0));
				if (f.open->num_docs % 64) {
					segment.live.back() = (uint64_t(1) << (f.open->num_docs % 64)) - 1;
				}
				fallback_docs_ += f.open->num_docs;
				fallback_bytes_ += f.bytes;
				segments_.push_back(std::move(segment));
			}
			fallback_segments_ = fallback.size();
			fallback_ms_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
		}
		// Ruling 10: rowids map to (segment, doc) through the segments' own rowid columns; nothing is sized by a
		// rowid's value.
		memory_->Charge(search::RowidMap::BytesFor(segments_.size()), "rowid maps");
		std::vector<std::shared_ptr<const search::OpenSegment>> opens;
		std::vector<const std::vector<uint64_t> *> lives;
		for (auto &segment : segments_) {
			opens.push_back(segment.open);
			lives.push_back(&segment.live);
		}
		map_ = std::make_unique<search::RowidMap>(opens, lives);
		allowed_.resize(segments_.size());
		NotePeak();
	}

	std::unique_ptr<search::SinkLocal> NewLocal() override {
		auto local = std::unique_ptr<SearchLocal>(new SearchLocal());
		local->allowed.resize(segments_.size());
		return std::move(local);
	}

	void Consume(ClientContext &, search::SinkLocal &local_p, Vector &rowids, idx_t count) override {
		auto &local = static_cast<SearchLocal &>(local_p);
		if (count > 0 && !local.started) {
			local.first = std::chrono::steady_clock::now();
			local.started = true;
		}
		UnifiedVectorFormat format;
		rowids.ToUnifiedFormat(format);
		auto values = format.GetData<int64_t>();
		auto &map = *map_;
		for (idx_t i = 0; i < count; i++) {
			auto index = format.sel->get_index(i);
			if (!format.validity.RowIsValid(index)) {
				continue;
			}
			auto at = map.Locate(values[index], local.hint);
			auto &bits = local.allowed[at.first];
			if (bits.Empty()) {
				Allocate(local, at.first);
			}
			bits.Data()[at.second >> 6] |= uint64_t(1) << (at.second & 63);
		}
	}

	void Merge(search::SinkLocal &local_p) override {
		auto &local = static_cast<SearchLocal &>(local_p);
		local_bytes_.push_back(local.charged);
		local_segments_.push_back(local.touched);
		if (local.started && (!started_ || local.first < first_)) {
			first_ = local.first;
			started_ = true;
		}
		for (size_t s = 0; s < segments_.size(); s++) {
			auto &bits = local.allowed[s];
			if (bits.Empty()) {
				continue;
			}
			if (allowed_[s].Empty()) {
				allowed_[s] = std::move(bits);
				continue;
			}
			auto into = allowed_[s].Data();
			auto from = bits.Data();
			for (size_t w = 0; w < bits.Size(); w++) {
				into[w] |= from[w];
			}
			auto bytes = bits.Size() * sizeof(uint64_t);
			bits.Reset();
			memory_->Release(bytes);
		}
	}

protected:
	//! The library's arguments over the merged allowed bitsets and the live bitsets at S (7.5), with what the
	//! query's budget leaves; checks the client's interrupt between segments; records the call for the tests.
	QsSearchArgs Arguments(ClientContext &context, size_t out_cap) {
		auto drained = started_ ? std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - first_).count()
		                        : 0.0;
		segment_ptrs_.clear();
		live_ptrs_.clear();
		allowed_ptrs_.clear();
		live_words_.clear();
		allowed_words_.clear();
#ifdef QOD_NODE_TEST_HOOKS
		auto interrupt_after = g_interrupt_between.load();
#endif
		for (size_t s = 0; s < segments_.size(); s++) {
			// Between segments (spec 9).
			if (context.IsInterrupted()) {
				throw InterruptException();
			}
			auto &segment = segments_[s];
			if (allowed_[s].Empty()) {
				memory_->Charge(segment.live.size() * sizeof(uint64_t), "allowed bitsets");
				allowed_[s] = search::BufferWords(buffers_, segment.live.size(), *memory_);
			}
			segment_ptrs_.push_back(segment.open->segment);
			live_ptrs_.push_back(segment.live.data());
			live_words_.push_back(segment.live.size());
			allowed_ptrs_.push_back(allowed_[s].Data());
			allowed_words_.push_back(allowed_[s].Size());
#ifdef QOD_NODE_TEST_HOOKS
			if (interrupt_after > 0 && s + 1 == interrupt_after) {
				context.Interrupt();
			}
#endif
		}
		NotePeak();
		QsSearchArgs args {};
		args.segments = segment_ptrs_.data();
		args.n_segments = segment_ptrs_.size();
		args.live = live_ptrs_.data();
		args.live_words = live_words_.data();
		args.allowed = allowed_ptrs_.data();
		args.allowed_words = allowed_words_.data();
		args.population = target_->population;
		args.query = bind_.query.c_str();
		args.fields = call_fields_.data();
		args.n_fields = call_fields_.size();
		// k1 and b are fixed through Phase 1 (OWNER RULING 2026-11-16, D10).
		args.k1 = 1.2f;
		args.b = 0.75f;
		args.k = bind_.matches ? 0 : bind_.k;
		args.path = QS_PATH_EXHAUSTIVE;
		args.cancel = reinterpret_cast<const uint8_t *>(&cancel_);
		// The library's own allocations (its bitset copies, statistics, heap, the query's scorer state) are bounded
		// by what the query's budget leaves.
		args.memory_budget = memory_->Remaining();
#ifdef QOD_NODE_TEST_HOOKS
		RecordCall(args, out_cap, drained);
		if (g_cancel_in_finish.load()) {
			cancel_ = 1;
		}
#else
		(void)out_cap;
		(void)drained;
#endif
		return args;
	}

#ifdef QOD_NODE_TEST_HOOKS
	//! What the tests read of the call (LastSearchCallForTest).
	void RecordCall(const QsSearchArgs &args, size_t out_cap, double drained) {
		SearchCallRecord record;
		for (auto &segment : segments_) {
			record.num_docs.push_back(segment.open->num_docs);
			record.liveness_words.push_back(segment.liveness_words);
		}
		record.live_words = live_words_;
		record.allowed_words = allowed_words_;
		record.out_cap = out_cap;
		record.k = bind_.matches ? 0 : bind_.k;
		record.matches = bind_.matches;
		for (auto &field : target_->call_fields) {
			record.fields.emplace_back(field.column, field.field_id);
		}
		record.memory_budget = args.memory_budget;
		record.held_bytes = memory_->Held();
		record.memory_limit = memory_->Budget();
		record.memory_peak = peak_;
		record.population = target_->population;
		// A copy whose liveness words are its own (the record outlives any database: it is a process global, and a
		// cached view's block is its instance's buffer manager's, Task 12); view_source names the view itself.
		record.view_source = target_->view.get();
		if (target_->view && target_->view->liveness_block) {
			auto copy = std::make_shared<search::SnapshotView>(*target_->view);
			for (auto &live : copy->liveness) {
				live.words.assign(live.Words(), live.Words() + live.WordCount());
				live.block_words = nullptr;
				live.block_count = 0;
			}
			copy->liveness_block.reset();
			record.view = std::move(copy);
		} else {
			record.view = target_->view;
		}
		record.view_hit = target_->view_hit;
		record.fallback_segments = fallback_segments_;
		record.fallback_docs = fallback_docs_;
		record.fallback_bytes = fallback_bytes_;
		record.fallback_ms = fallback_ms_;
		record.local_bitset_bytes = local_bytes_;
		record.local_segments = local_segments_;
		record.drain_ms = drained;
		record.called = true;
		std::lock_guard<std::mutex> guard(g_hooks_lock);
		g_last_call = record;
	}
#endif

	//! Throws a library failure: an interrupt for search_cancelled, else the library's text.
	[[noreturn]] void Failed(char *err) {
		auto text = TakeError(err);
		if (StringUtil::StartsWith(text, "search_cancelled")) {
#ifdef QOD_NODE_TEST_HOOKS
			{
				std::lock_guard<std::mutex> guard(g_hooks_lock);
				g_last_call.library_cancelled = true;
			}
#endif
			throw InterruptException();
		}
		if (StringUtil::StartsWith(text, "search_memory_exceeded")) {
			memory_->Exceeded(text.substr(text.find(':') + 1));
		}
		throw InvalidInputException("QoD: " + text);
	}

	//! The allowed population (7.5): its rows' sizes are not the principal's to read.
	bool Allowed() const {
		return target_ && target_->population == QS_POPULATION_ALLOWED;
	}

	void NotePeak() {
		peak_ = std::max(peak_, memory_->Held());
	}

	void CallObserver(ClientContext &context) {
#ifdef QOD_NODE_TEST_HOOKS
		std::function<void(ClientContext &)> observer;
		{
			std::lock_guard<std::mutex> guard(g_hooks_lock);
			observer = g_call_observer;
		}
		if (observer) {
			try {
				observer(context); // a test hook; it must not leave the watcher unjoined
			} catch (...) {
			}
		}
#else
		(void)context;
#endif
	}

	//! Gives back the merged allowed bitsets (a matches iterator copied them at its open).
	void ReleaseAllowed() {
		for (auto &bits : allowed_) {
			auto bytes = bits.Size() * sizeof(uint64_t);
			bits.Reset();
			memory_->Release(bytes);
		}
	}

	const search::SearchBindData &bind_;
	BufferManager &buffers_;
	std::shared_ptr<search::SearchTarget> target_;
	std::unique_ptr<search::QueryMemory> memory_;
	std::vector<SearchSegment> segments_;
	std::vector<search::BufferWords> allowed_;
	//! The library's cancel byte: it must outlive a matches iterator (qod_search_matches).
	std::atomic<uint8_t> cancel_ {0};

private:
	//! A local state's first rowid in `segment`: its bitset, charged before it is taken from the buffer manager.
	void Allocate(SearchLocal &local, uint32_t segment) {
		auto words = segments_[segment].live.size();
		auto bytes = words * sizeof(uint64_t);
		memory_->Charge(bytes, "allowed bitsets");
		local.allowed[segment] = search::BufferWords(buffers_, words, *memory_);
		local.charged += bytes;
		local.touched.push_back(segment);
	}

	std::vector<QsField> call_fields_;
	std::unique_ptr<search::RowidMap> map_;
	size_t fallback_segments_ = 0;
	uint64_t fallback_docs_ = 0;
	uint64_t fallback_bytes_ = 0;
	double fallback_ms_ = 0;
	uint64_t peak_ = 0;
	//! Per local state, from Merge (under the sink's mutex).
	std::vector<uint64_t> local_bytes_;
	std::vector<std::vector<uint32_t>> local_segments_;
	bool started_ = false;
	std::chrono::steady_clock::time_point first_;
	//! The arrays QsSearchArgs points into (Arguments).
	std::vector<const QsSegment *> segment_ptrs_;
	std::vector<const uint64_t *> live_ptrs_;
	std::vector<const uint64_t *> allowed_ptrs_;
	std::vector<size_t> live_words_;
	std::vector<size_t> allowed_words_;
};

//! qod_search: one qs_search in Finish, its top k materialized (the sink's default NextChunk reads them).
class SearchProgram : public SearchExecution {
public:
	using SearchExecution::SearchExecution;

	void Finish(ClientContext &context) override {
		auto started = std::chrono::steady_clock::now();
		memory_->Charge(uint64_t(bind_.k) * sizeof(QsHit), "hits");
		hits_.assign(bind_.k, QsHit {});
		auto args = Arguments(context, hits_.size());
		size_t n = 0;
		char *err = nullptr;
		auto rc = WithCancelWatcher(context, cancel_, [&]() {
			CallObserver(context);
			return qs_search(&args, hits_.data(), hits_.size(), &n, &err);
		});
#ifdef QOD_NODE_TEST_HOOKS
		{
			std::lock_guard<std::mutex> guard(g_hooks_lock);
			g_last_call.finish_ms =
			    std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
		}
#else
		(void)started;
#endif
		if (rc != 0) {
			hits_.clear();
			Failed(err);
		}
		hits_.resize(n);
	}

	idx_t OutputRows() const override {
		return hits_.size();
	}

	Value OutputValue(idx_t row, idx_t column) const override {
		auto &hit = hits_[row];
		switch (column) {
		case 0:
			return Value::BIGINT(hit.rowid);
		case 1:
			return Value::FLOAT(hit.score);
		default:
			return Value::INTEGER(static_cast<int32_t>(row + 1));
		}
	}

private:
	std::vector<QsHit> hits_;
};

//! qod_search_matches: qs_matches_open in Finish, then the sink's source pulls qs_matches_next, one chunk of
//! up to STANDARD_VECTOR_SIZE rows per call. The iterator copied the bitsets at its open; the segments and the
//! cancel byte stay this program's until it is freed. One watcher thread for the iterator's life (started
//! after the open, stopped when the matches end, fail or the program goes) copies the client's interrupt into
//! the cancel byte, so a long qs_matches_next is cancelled too (review M3: no thread per call).
//! Invariant 8, at statement level (lead ruling 2026-11-17, review I1 (a)): rows of earlier chunks have gone
//! downstream when a later call fails; the statement then fails with the library's error, so no result of it
//! is complete or reads as success.
class MatchesProgram : public SearchExecution {
public:
	using SearchExecution::SearchExecution;

	~MatchesProgram() override {
		StopWatcher();
		if (matches_) {
			qs_matches_free(matches_);
		}
	}

	void Finish(ClientContext &context) override {
		memory_->Charge(STANDARD_VECTOR_SIZE * sizeof(QsHit), "hits");
		auto args = Arguments(context, STANDARD_VECTOR_SIZE);
		char *err = nullptr;
		QsMatches *opened = nullptr;
		auto rc = WithCancelWatcher(context, cancel_, [&]() {
			CallObserver(context);
			opened = qs_matches_open(&args, bind_.with_score ? 1 : 0, &err);
			return opened ? 0 : 1;
		});
		if (rc != 0) {
			Failed(err);
		}
		matches_ = opened;
		// The iterator holds copies of the bitsets: these are released now.
		ReleaseAllowed();
		hits_.resize(STANDARD_VECTOR_SIZE);
		StartWatcher(context);
	}

	bool NextChunk(ClientContext &context, DataChunk &out) override {
		if (done_ || !matches_) {
			out.SetCardinalityUnsafe(0);
			return false;
		}
		ApplyTestHooks();
		idx_t count = 0;
		while (count < STANDARD_VECTOR_SIZE) {
			if (context.IsInterrupted()) {
				Done();
				throw InterruptException();
			}
			size_t n = 0;
			char *err = nullptr;
			// A test hook asks for 0 hits: the library's own argument refusal, a real failure of the call.
			auto cap = fail_now_ ? size_t(0) : STANDARD_VECTOR_SIZE - count;
			auto rc = qs_matches_next(matches_, hits_.data() + count, cap, &n, &err);
			if (rc != 0) {
				// Invariant 8: never a partial answer. The statement fails with the library's error.
				Done();
				Failed(err);
			}
			if (n == 0) {
				Done();
				break;
			}
			count += n;
		}
		if (count > 0) {
			auto rowids = FlatVector::Writer<int64_t>(out.data[0], count);
			auto scores = FlatVector::Writer<float>(out.data[1], count);
			for (idx_t i = 0; i < count; i++) {
				rowids.WriteValue(hits_[i].rowid);
				if (bind_.with_score) {
					scores.WriteValue(hits_[i].score);
				} else {
					scores.WriteNull();
				}
			}
		}
		out.SetCardinalityUnsafe(count);
		chunks_++;
		return count > 0;
	}

private:
	void StartWatcher(ClientContext &context) {
		try {
			watcher_ = std::thread([this, &context] {
				std::unique_lock<std::mutex> guard(watch_lock_);
				while (!watch_done_) {
					if (context.IsInterrupted()) {
						cancel_ = 1;
					}
					watch_wake_.wait_for(guard, std::chrono::milliseconds(2));
				}
			});
		} catch (std::system_error &) {
			throw InvalidInputException("QoD: search_internal: the search could not start its cancel watcher");
		}
	}

	void StopWatcher() {
		{
			std::lock_guard<std::mutex> guard(watch_lock_);
			watch_done_ = true;
		}
		watch_wake_.notify_all();
		if (watcher_.joinable()) {
			watcher_.join();
		}
	}

	//! The matches ended (all read, failed or interrupted): the watcher stops and the iterator is freed.
	void Done() {
		done_ = true;
		StopWatcher();
		if (matches_) {
			qs_matches_free(matches_);
			matches_ = nullptr;
		}
	}

	void ApplyTestHooks();

	QsMatches *matches_ = nullptr;
	bool done_ = false;
	std::vector<QsHit> hits_;
	idx_t chunks_ = 0;
	bool fail_now_ = false;
	std::thread watcher_;
	std::mutex watch_lock_;
	std::condition_variable watch_wake_;
	bool watch_done_ = false;
};

//! Test hooks of qod_search_matches: after this many chunks went out, the next call fails (the library's
//! refusal of a zero capacity) or is cancelled (the cancel byte set, the library answers search_cancelled).
#ifdef QOD_NODE_TEST_HOOKS
std::atomic<int64_t> g_fail_matches_after {-1};
std::atomic<int64_t> g_cancel_matches_after {-1};
#endif

void MatchesProgram::ApplyTestHooks() {
#ifdef QOD_NODE_TEST_HOOKS
	auto fail_after = g_fail_matches_after.load();
	fail_now_ = fail_after >= 0 && static_cast<int64_t>(chunks_) >= fail_after;
	auto cancel_after = g_cancel_matches_after.load();
	if (cancel_after >= 0 && static_cast<int64_t>(chunks_) >= cancel_after) {
		cancel_ = 1;
	}
#endif
}

//! qod_search_build_segment's bind data (its functions below; the hook recognizes a build call by it).
struct BuildBindData : public TableFunctionData {
	search::IndexRecord record;
	std::string path;
	std::string path_key;
	std::string bundle_path;
	uint64_t file_size = 0;
	uint64_t footer_size = 0;
};

// ---- the post_bind hook ----------------------------------------------------------------------------

//! Whether a query runs on a connection, so its text is reachable (ClientContext::GetCurrentQuery
//! dereferences the active query unchecked, and BindStatement plans without one). Made on every
//! connection when it opens (SearchTextCallback).
class SearchTextState : public ClientContextState {
public:
	static constexpr const char *kKey = "qod_search_text";
	void QueryBegin(ClientContext &) override {
		active = true;
	}
	void QueryEnd(ClientContext &, optional_ptr<ErrorData>) override {
		active = false;
	}
	std::atomic<bool> active {false};
};

class SearchTextCallback : public ExtensionCallback {
public:
	void OnConnectionOpened(ClientContext &context) override {
		context.registered_state->GetOrCreate<SearchTextState>(SearchTextState::kKey);
	}
};

std::optional<std::string> StatementText(ClientContext &context) {
	auto state = context.registered_state->Get<SearchTextState>(SearchTextState::kKey);
	if (!state || !state->active) {
		return std::nullopt;
	}
	return context.GetCurrentQuery();
}

//! Rule 4's refusal that needs no read: AT (TIMESTAMP) on the target scan (ruling 1). ResolveSnapshotView refuses
//! it too. A transaction that wrote to the target's lake is searched with its own writes (Task 14).
void RefuseSnapshotShapes(ClientContext &, LogicalGet &target) {
	if (target.at_clause) {
		auto unit = StringUtil::Lower(target.at_clause->Unit().GetIdentifierName());
		if (unit == "timestamp") {
			SearchInvalid("search_at_timestamp_unsupported: AT (TIMESTAMP => ...) is not supported by search; use AT "
			              "(VERSION => n)");
		}
	}
}

//! Runs after qod-node's own post_bind (registered after it), so only on a plan the verdict admitted:
//! the lineage check, then each call's index record, columns, population and snapshot view (7.1 steps 1
//! and 2). A PREPARE's plan is checked but not resolved: DuckDB 2.0 rebinds a statement that reads a table
//! at every EXECUTE, and the sink refuses a call resolved in another transaction (invariant 4).
void SearchPostBindCalls(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!statement.plan) {
		return;
	}
	auto &context = input.context;
	Installed installed;
	std::string pool;
	bool installed_ok = SessionSnapshot(context, 0, installed);
	if (installed_ok) {
		pool = installed.pool;
	}
	auto text = StatementText(context);
	// A build call is no search call: its text is judged on its own (rule 1, a query() or a view body).
	std::function<bool(LogicalOperator &)> holds_build = [&](LogicalOperator &op) {
		if (op.type == LogicalOperatorType::LOGICAL_GET) {
			auto &get = op.Cast<LogicalGet>();
			if (get.bind_data && dynamic_cast<BuildBindData *>(get.bind_data.get())) {
				return true;
			}
		}
		for (auto &child : op.children) {
			if (child && holds_build(*child)) {
				return true;
			}
		}
		return false;
	};
	if (holds_build(*statement.plan)) {
		search::JudgeSearchText(context, *statement.plan, text ? &*text : nullptr);
	}
	auto calls = search::FindSearchCalls(context, *statement.plan, text ? &*text : nullptr, pool);
	if (calls.empty()) {
		return;
	}
	// The PREPARE wrapper's own pass (DuckDB binds the inner statement first, as a plan of its own, which
	// is resolved like any plan: once for nothing, review minor 6): the wrapper's child is that inner plan,
	// so its targets are reset, and the sink refuses one never resolved again (search_unbound).
	auto preparing = statement.plan->type == LogicalOperatorType::LOGICAL_PREPARE;
	// Rule 8: on a quack session a tenant principal's call takes its population from the verdict the
	// statement runs under, never a default. The verdict's entries are numbered as the describer numbered the
	// description's `searches`; a count that differs from the calls found here (a call inside a subquery
	// expression, which the lineage walk does not enter) refuses the statement. Superusers and the system
	// principal, who never call the decider, and a connection off a quack session (the node's own connections,
	// in-process tests) search the table population.
	auto quack = context.registered_state->Get<ClientContextState>(kQuackSessionKey) != nullptr;
	auto from_verdict = quack && !(installed_ok && (installed.principal.superuser || installed.principal.system));
	std::vector<SearchEntry> entries;
	if (from_verdict && !preparing) {
		if (!StatementSearchEntries(context, entries) || entries.size() != calls.size()) {
			SearchInvalid("search_population_missing: the statement's verdict carries no statistics population for "
			              "each of its search calls");
		}
	}
	for (auto &call : calls) {
		auto &data = *search::SearchCallData(*call.call);
		auto target = std::make_shared<search::SearchTarget>();
		data.target = target;
		if (preparing) {
			continue;
		}
		RefuseSnapshotShapes(context, *call.target);
		auto current = search::ResolveSearchTable(context, call.table);
		auto scanned = call.target->GetTable();
		auto refuse_unresolved = [&]() {
			if (!current || !scanned) {
				SearchInvalid("search_index_unavailable: no index on " + call.table);
			}
		};
		// Each call column by its name now (the verdict judged it so) to its DuckLake field id: a field of the
		// record (a renamed column is searched under its new name, review M9) and a column of the table at the
		// snapshot the input reads, never the column holding that name at S (review C1).
		std::vector<search::IndexField> call_fields;
		auto check_record = [&](const std::optional<search::IndexRecord> &found) {
			if (!found || found->state != "ready") {
				SearchInvalid("search_index_unavailable: no index on " + call.table);
			}
			call_fields.clear();
			for (auto &column : call.columns) {
				auto field_id = search::CurrentFieldId(context, *current, column);
				bool indexed = false;
				for (auto &field : found->fields) {
					indexed = indexed || (field_id >= 0 && field.field_id == field_id);
				}
				if (!indexed) {
					SearchInvalid("search_columns_unsupported: " + column + " of " + call.table + " is not indexed");
				}
				if (!search::HasFieldId(call.target->bind_data.get(), field_id)) {
					SearchInvalid("search_columns_unsupported: " + column + " of " + call.table +
					              " is not a column of the table at the snapshot the input reads");
				}
				call_fields.push_back(search::IndexField {column, field_id});
			}
		};
		// The target's DuckLake table id at `at`: the version live at S under the target entry's name, which must be
		// the target scan's own entry there (DuckLake keeps one oid per table id). The record pins it (ruling 21): a
		// table dropped and recreated under the name has another id, and no record; another session's drop and
		// recreate after S does not move it. A catalog that cannot be read is retryable (review I3).
		auto table_id_at = [&](int64_t at) {
			int64_t id = -1;
			auto read = DuckLakeTableIdAt(context, *scanned, at, id);
			if (read == DuckLakeIdRead::Unreadable) {
				throw PermissionException(search::kRecordUnavailable);
			}
			if (read != DuckLakeIdRead::Found) {
				SearchInvalid("search_index_unavailable: no index on " + call.table);
			}
			return id;
		};
		auto record_of = [&](int64_t id) {
#ifdef QOD_NODE_TEST_HOOKS
			g_record_lookups++;
#endif
			return search::IndexRecordFor(context, current->ParentCatalog().GetName().GetIdentifierName(), id);
		};
		auto resolve = [&](bool &hit) {
			std::shared_ptr<const search::SnapshotView> resolved;
			ObserveResolve(true);
			try {
				resolved = search::CachedSnapshotView(context, *call.target, hit);
			} catch (...) {
				ObserveResolve(false);
				throw;
			}
			ObserveResolve(false);
			return resolved;
		};
		std::shared_ptr<const search::SnapshotView> view;
		bool view_hit = false;
		int64_t table_id = -1;
		std::optional<search::IndexRecord> record;
		if (!call.target->at_clause) {
			// A read at the current snapshot (Task 12, carried from Task 7's review): S is the transaction's own
			// (ducklake_current_snapshot), so the index on the table and its columns are checked before any read at
			// S, and a call on an unindexed table, a disabled index or an unindexed column is refused before it pays
			// a liveness resolve. The table id read at that S is the one the view's S needs (checked below).
			refuse_unresolved();
			auto early_at = search::CurrentSnapshotId(context, *call.target);
			table_id = table_id_at(early_at);
			record = record_of(table_id);
			check_record(record);
			view = resolve(view_hit);
			if (view->snapshot_id != early_at) {
				auto at_s = table_id_at(view->snapshot_id);
				if (at_s != table_id) {
					table_id = at_s;
					record = record_of(table_id);
					check_record(record);
				}
			}
		} else {
			// An AT read (review M6): the table at that snapshot may not be the table now (dropped and recreated
			// since), so the view at S comes first and the table id is read at S (security review m1).
			view = resolve(view_hit);
			refuse_unresolved();
			table_id = table_id_at(view->snapshot_id);
			record = record_of(table_id);
			check_record(record);
		}
		int population = QS_POPULATION_TABLE;
		if (from_verdict) {
			const SearchEntry *entry = nullptr;
			for (auto &e : entries) {
				if (e.call == call.index) {
					entry = &e;
				}
			}
			if (!entry) {
				SearchInvalid("search_population_missing: the statement's verdict carries no statistics population for "
				              "its search call on " +
				              call.table);
			}
			population = entry->population == "allowed" ? QS_POPULATION_ALLOWED : QS_POPULATION_TABLE;
		}
		target->view = std::move(view);
		target->view_hit = view_hit;
		target->table = call.target->GetTable();
		if (call.target->at_clause) {
			target->at_clause = std::make_shared<BoundAtClause>(*call.target->at_clause);
		}
		target->record = std::move(*record);
		target->call_fields = std::move(call_fields);
		target->population = population;
		target->transaction_id = MetaTransaction::Get(context).global_transaction_id;
		target->query_id = MetaTransaction::Get(context).GetActiveQuery();
		target->attached = true;
	}
}

void ObserveHook(ClientContext &context, bool begin) {
#ifdef QOD_NODE_TEST_HOOKS
	std::function<void(ClientContext &, bool)> observer;
	{
		std::lock_guard<std::mutex> guard(g_hooks_lock);
		observer = g_hook_observer;
	}
	if (observer) {
		observer(context, begin);
	}
#else
	(void)context;
	(void)begin;
#endif
}

//! The hook DuckDB calls: SearchPostBindCalls, bracketed for the tests (SearchHookObserverForTest). It holds no lock
//! of its own: the statements DuckLake binds on its own context while the hook expands the target's file list
//! (MultiFileList::GetAllFiles) run every post_bind, this one included, re-entrantly on the same thread (7.1 step 1).
void SearchPostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	ObserveHook(input.context, true);
	try {
		SearchPostBindCalls(input, statement);
	} catch (...) {
		ObserveHook(input.context, false);
		throw;
	}
	ObserveHook(input.context, false);
}

// ---- qod_search_build_segment: one data file's bundle ---------------------------------------------------

struct BuildGlobal : public GlobalTableFunctionState {
	idx_t MaxThreads() const override {
		return 1;
	}
	std::atomic<idx_t> locals {0};
};

struct BuildLocal : public LocalTableFunctionState {
	~BuildLocal() override {
		if (builder) {
			// Consumes and frees a builder that never finished.
			qs_builder_finish(builder, nullptr, nullptr, nullptr, nullptr);
		}
	}
	QsBuilder *builder = nullptr;
	int64_t next = 0;
	bool done = false;
	std::atomic<uint8_t> cancel {0};
	std::vector<QsText> texts;
};

//! qod_search_build_segment's refusal to every principal but the system's, as qod_authz_stats's.
constexpr const char *kBuildRefused = "QoD: function qod_search_build_segment is superuser-only";

unique_ptr<FunctionData> BuildBind(ClientContext &context, TableFunctionBindInput &input, vector<LogicalType> &types,
                                   vector<Identifier> &names) {
	// The system principal only (spec 13), on a quack session, as qod_authz_stats: refused on any other
	// connection, an internal one or a superuser's included. Expiry is post_bind's to check.
	Installed installed;
	if (context.registered_state->Get<ClientContextState>(kQuackSessionKey) == nullptr ||
	    !SessionSnapshot(context, 0, installed) || !installed.principal.system) {
		throw PermissionException(kBuildRefused);
	}
	auto &inputs = input.inputs;
	idx_t at = inputs.size() == 5 ? 1 : 0;
	if (inputs.size() - at != 4) {
		throw BinderException("qod_search_build_segment takes (input, table, path, file_size, footer_size)");
	}
	for (idx_t i = at; i < inputs.size(); i++) {
		if (inputs[i].IsNull()) {
			SearchInvalid("search_invalid_argument: qod_search_build_segment takes no NULL argument");
		}
	}
	auto name = inputs[at].GetValue<string>();
	auto table = search::ResolveSearchTable(context, name);
	if (!table || table->ParentCatalog().GetCatalogType() != "ducklake") {
		SearchInvalid("search_invalid_argument: qod_search_build_segment: " + name + " names no DuckLake table");
	}
	// Change 64: no plaintext bundle of an encrypted lake, whoever calls, until Phase 2 seals bundles: refused
	// here, before any row of the input is read. A catalog whose encryption cannot be read is refused too.
	auto encrypted = DuckLakeEncrypted(context, table->ParentCatalog());
	if (encrypted < 0) {
		// Fail closed, retryable: a catalog whose encryption cannot be read builds nothing (review M1, I3).
		throw PermissionException("QoD: authorization unavailable, retry: the encryption of " +
		                          search::CanonicalTableName(*table) + "'s DuckLake could not be read");
	}
	if (encrypted != 0) {
		SearchInvalid("search_encrypted_unsupported: " + search::CanonicalTableName(*table) +
		              " is in an encrypted DuckLake, whose bundles Phase 2 seals; no bundle is built for it");
	}
	int64_t table_id = -1;
	auto read = DuckLakeTableIdAt(context, *table, -1, table_id);
	if (read == DuckLakeIdRead::Unreadable) {
		throw PermissionException(search::kRecordUnavailable);
	}
	auto record = read == DuckLakeIdRead::Found
	                  ? search::IndexRecordFor(context, table->ParentCatalog().GetName().GetIdentifierName(), table_id)
	                  : std::nullopt;
	if (!record || record->state != "ready") {
		SearchInvalid("search_index_unavailable: no index on " + search::CanonicalTableName(*table));
	}
	auto &in = input.input_table_types;
	if (in.size() < 3 || in[0] != LogicalType::BIGINT || in[1] != LogicalType::BIGINT || in[2] != LogicalType::BIGINT) {
		SearchInvalid("search_invalid_argument: qod_search_build_segment takes (pos BIGINT, row_id BIGINT, snapshot_id "
		              "BIGINT, <one VARCHAR per index field>) rows");
	}
	if (in.size() - 3 != record->fields.size()) {
		SearchInvalid("search_invalid_argument: qod_search_build_segment's input carries " +
		              std::to_string(in.size() - 3) + " text columns, and the index has " +
		              std::to_string(record->fields.size()) + " fields");
	}
	for (idx_t i = 3; i < in.size(); i++) {
		if (in[i] != LogicalType::VARCHAR) {
			SearchInvalid("search_invalid_argument: qod_search_build_segment's text columns are VARCHAR");
		}
	}
	auto data = make_uniq<BuildBindData>();
	data->record = std::move(*record);
	data->path = inputs[at + 1].GetValue<string>();
	data->path_key = Hex(Sha256(data->path));
	data->bundle_path = search::SegmentPath(data->record.bundle_root, data->record.index_id, data->path_key);
	data->file_size = inputs[at + 2].GetValue<uint64_t>();
	data->footer_size = inputs[at + 3].GetValue<uint64_t>();
	types = {LogicalType::VARCHAR, LogicalType::BIGINT, LogicalType::BIGINT};
	names.emplace_back("path");
	names.emplace_back("docs");
	names.emplace_back("bytes");
	return std::move(data);
}

unique_ptr<GlobalTableFunctionState> BuildInitGlobal(ClientContext &, TableFunctionInitInput &) {
	return make_uniq<BuildGlobal>();
}

unique_ptr<LocalTableFunctionState> BuildInitLocal(ExecutionContext &context, TableFunctionInitInput &input,
                                                   GlobalTableFunctionState *global_p) {
	auto &global = global_p->Cast<BuildGlobal>();
	if (global.locals++ >= 1) {
		throw ExecutorException("QoD: qod_search_build_segment reads its input in one pipeline, and this input runs in "
		                        "several");
	}
	auto &data = input.bind_data->Cast<BuildBindData>();
	auto local = make_uniq<BuildLocal>();
	search::DataFileRef file;
	file.path = data.path;
	file.bundle_key = data.path_key;
	file.file_size = data.file_size;
	file.footer_size = data.footer_size;
	std::vector<QsField> fields;
	auto source = search::BundleSource(data.record, file, fields);
	source.row_id_start = -1;
	char *err = nullptr;
	local->builder = qs_builder_new(&source, reinterpret_cast<const uint8_t *>(&local->cancel), QueryBudget(context.client), &err);
	if (!local->builder) {
		throw InvalidInputException("QoD: " + TakeError(err));
	}
	local->texts.resize(data.record.fields.size());
	return std::move(local);
}

OperatorResultType BuildInOut(ExecutionContext &context, TableFunctionInput &data, DataChunk &input, DataChunk &output) {
	auto &local = data.local_state->Cast<BuildLocal>();
	if (context.client.IsInterrupted()) {
		local.cancel = 1;
	}
	auto count = input.size();
	auto n_texts = local.texts.size();
	UnifiedVectorFormat pos, rowid, snapshot;
	std::vector<UnifiedVectorFormat> text(n_texts);
	input.data[0].ToUnifiedFormat(pos);
	input.data[1].ToUnifiedFormat(rowid);
	input.data[2].ToUnifiedFormat(snapshot);
	for (size_t f = 0; f < n_texts; f++) {
		input.data[3 + f].ToUnifiedFormat(text[f]);
	}
	auto positions = pos.GetData<int64_t>();
	auto rowids = rowid.GetData<int64_t>();
	auto snapshots = snapshot.GetData<int64_t>();
	for (idx_t i = 0; i < count; i++) {
		auto p = pos.sel->get_index(i);
		auto r = rowid.sel->get_index(i);
		auto s = snapshot.sel->get_index(i);
		if (!pos.validity.RowIsValid(p) || positions[p] != local.next) {
			throw InvalidInputException("QoD: search_build_out_of_order: position " +
			                            (pos.validity.RowIsValid(p) ? std::to_string(positions[p]) : std::string("NULL")) +
			                            " arrived where " + std::to_string(local.next) + " was due");
		}
		if (!rowid.validity.RowIsValid(r)) {
			throw InvalidInputException("QoD: search_build: a NULL row_id");
		}
		auto snapshot_id = snapshot.validity.RowIsValid(s) ? snapshots[s] : int64_t(-1);
		for (size_t f = 0; f < n_texts; f++) {
			auto t = text[f].sel->get_index(i);
			if (text[f].validity.RowIsValid(t)) {
				auto &value = text[f].GetData<string_t>()[t];
				local.texts[f] = value.GetSize() ? QsText {value.GetData(), value.GetSize()} : QsText {nullptr, 0};
			} else {
				local.texts[f] = QsText {nullptr, 0};
			}
		}
		char *err = nullptr;
		if (qs_builder_add(local.builder, static_cast<uint64_t>(local.next), rowids[r], snapshot_id, local.texts.data(),
		                   n_texts, &err) != 0) {
			throw InvalidInputException("QoD: " + TakeError(err));
		}
		local.next++;
	}
	output.SetChildCardinality(0);
	return OperatorResultType::NEED_MORE_INPUT;
}

OperatorFinalizeResultType BuildFinal(ExecutionContext &context, TableFunctionInput &data, DataChunk &output) {
	auto &local = data.local_state->Cast<BuildLocal>();
	if (local.done) {
		output.SetChildCardinality(0);
		return OperatorFinalizeResultType::FINISHED;
	}
	local.done = true;
	auto &bind = data.bind_data->Cast<BuildBindData>();
	uint8_t *bytes = nullptr;
	size_t len = 0;
	uint64_t docs = 0;
	char *err = nullptr;
	auto builder = local.builder;
	local.builder = nullptr;
	if (qs_builder_finish(builder, &bytes, &len, &docs, &err) != 0) {
		throw InvalidInputException("QoD: " + TakeError(err));
	}
	try {
		search::WriteBundle(context.client, bind.bundle_path, bytes, len);
	} catch (...) {
		qs_bytes_free(bytes, len);
		throw;
	}
	qs_bytes_free(bytes, len);
	// D8: the bundle is published, so this node's next search opens it instead of a remembered miss.
	search::ForgetMissingBundle(context.client, bind.record.index_id, bind.path_key, bind.file_size, bind.footer_size);
	output.data[0].SetValue(0, Value(bind.bundle_path));
	output.data[1].SetValue(0, Value::BIGINT(static_cast<int64_t>(docs)));
	output.data[2].SetValue(0, Value::BIGINT(static_cast<int64_t>(len)));
	output.SetChildCardinality(1);
	return OperatorFinalizeResultType::FINISHED;
}

} // namespace

void RegisterSearchFunctions(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_node");
	search::RegisterSearchSink(db);

	// Every knob, read here once (search_knobs.hpp): a node registers once, at its start.
	g_query_memory = search::ReadKnob(search::kQueryMemoryKnob);
	g_max_k = search::ReadKnob(search::kMaxKKnob);
	search::ReadFallbackCapsFromEnvironment();
	search::ReadSegmentSettingsFromEnvironment();
	search::ReadLivenessSettingsFromEnvironment();
	TableFunction search("qod_search",
	                     {LogicalType::TABLE, LogicalType::VARCHAR, LogicalType::LIST(LogicalType::VARCHAR),
	                      LogicalType::VARCHAR},
	                     nullptr, SearchBind);
	// k's declared default is NULL, not 10: DuckDB fills a keyword-only default into the named parameters, so a NULL
	// is how the bind tells a call that names no k (min(10, QOD_SEARCH_MAX_K)) from one that names 10.
	search.GetSignature().AddKeywordOnly(Identifier("k"), LogicalType::INTEGER, Value(LogicalType::INTEGER));
	search.GetSignature().AddKeywordOnly(Identifier("query_json"), LogicalType::VARCHAR, Value(LogicalType::VARCHAR));
	search::RegisterSinkFunction(loader, search, [](ClientContext &context, const FunctionData &bind) {
		return std::unique_ptr<search::SinkProgram>(new SearchProgram(context, bind.Cast<search::SearchBindData>()));
	});
	TableFunction matches("qod_search_matches",
	                      {LogicalType::TABLE, LogicalType::VARCHAR, LogicalType::LIST(LogicalType::VARCHAR),
	                       LogicalType::VARCHAR},
	                      nullptr, MatchesBind);
	matches.GetSignature().AddKeywordOnly(Identifier("query_json"), LogicalType::VARCHAR, Value(LogicalType::VARCHAR));
	matches.GetSignature().AddKeywordOnly(Identifier("with_score"), LogicalType::BOOLEAN, Value::BOOLEAN(false));
	search::RegisterSinkFunction(loader, matches, [](ClientContext &context, const FunctionData &bind) {
		return std::unique_ptr<search::SinkProgram>(new MatchesProgram(context, bind.Cast<search::SearchBindData>()));
	});
	TableFunction build("qod_search_build_segment",
	                    {LogicalType::TABLE, LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::UBIGINT,
	                     LogicalType::UBIGINT},
	                    nullptr, BuildBind, BuildInitGlobal, BuildInitLocal);
	build.in_out_function = BuildInOut;
	build.in_out_function_final = BuildFinal;
	loader.RegisterFunction(build);
	ExtensionCallback::Register(DBConfig::GetConfig(db), make_shared_ptr<SearchTextCallback>());
	PlannerExtension planner;
	planner.post_bind_function = SearchPostBind;
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

void RegisterSearchFunctions(DatabaseInstance &db, std::shared_ptr<search::RecordClient> records) {
	RegisterSearchFunctions(db);
	search::InstallRecordFetch(std::move(records));
}

#ifdef QOD_NODE_TEST_HOOKS
void SetSearchMemoryForTest(uint64_t bytes) {
	g_query_memory = bytes;
}

std::map<std::string, uint64_t> SearchKnobsForTest() {
	auto segments = search::SegmentSettingsForTest();
	auto liveness = search::LivenessBoundsForTest();
	return {{search::kMaxUnindexedRowsKnob.name, search::MaxUnindexedRows()},
	        {search::kMaxUnindexedSourcesKnob.name, search::MaxUnindexedSources()},
	        {search::kQueryMemoryKnob.name, g_query_memory.load()},
	        {search::kMaxKKnob.name, g_max_k.load()},
	        {search::kLivenessCacheEntriesKnob.name, liveness.first},
	        {search::kLivenessCacheBytesKnob.name, liveness.second},
	        {search::kSegmentCacheBytesKnob.name, segments.cache_bytes},
	        {search::kMissTtlSecKnob.name, segments.miss_ttl_sec},
	        {search::kMaxBundleBytesKnob.name, segments.max_bundle_bytes}};
}

void CancelInFinishForTest(bool cancel) {
	g_cancel_in_finish = cancel;
}

SearchCallRecord LastSearchCallForTest() {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	return g_last_call;
}

void ResetLastSearchCallForTest() {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	g_last_call = SearchCallRecord();
}

void SearchCallObserverForTest(std::function<void(ClientContext &)> observer) {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	g_call_observer = std::move(observer);
}

void ResolveObserverForTest(std::function<void(bool)> observer) {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	g_resolve_observer = std::move(observer);
}

void SearchHookObserverForTest(std::function<void(ClientContext &, bool)> observer) {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	g_hook_observer = std::move(observer);
}

uint64_t TakeSearchLocksForTest(DatabaseInstance &db) {
	uint64_t taken = 0;
	{
		std::lock_guard<std::mutex> guard(g_hooks_lock);
		taken++;
	}
	taken += search::TakeLivenessLocksForTest(db);
	taken += search::TakeSegmentLocksForTest(db);
	taken += search::TakeRecordLocksForTest(db);
	taken += search::TakeFallbackLocksForTest();
	return taken;
}

uint64_t RecordLookupsForTest() {
	return g_record_lookups.load();
}

void FailMatchesAfterChunksForTest(int64_t chunks) {
	g_fail_matches_after = chunks;
}

void InterruptBetweenSegmentsForTest(uint64_t n) {
	g_interrupt_between = n;
}

void InterruptBetweenSegmentLoadsForTest(uint64_t n) {
	g_interrupt_between_loads = n;
}

void CancelMatchesAfterChunksForTest(int64_t chunks) {
	g_cancel_matches_after = chunks;
}
#endif // QOD_NODE_TEST_HOOKS

} // namespace qod
