#include "search_functions.hpp"

#include "crypto.hpp"
#include "node_hooks.hpp"
#include "search_fallback.hpp"
#include "search_lineage.hpp"
#include "search_segments.hpp"
#include "search_sink.hpp"
#include "search_snapshot.hpp"
#include "search_visibility.hpp"

#include "qod_search.h"

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/storage/buffer/buffer_handle.hpp"
#include "duckdb/storage/buffer_manager.hpp"
#include "duckdb/common/vector/unified_vector_format.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/common/string_util.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "duckdb/planner/extension_callback.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/common/error_data.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"
#include "duckdb/transaction/meta_transaction.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <mutex>
#include <optional>
#include <system_error>
#include <thread>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace qod {
namespace {

using namespace duckdb;

//! Test hook (UseVectorBitsetsForTest): the drain bitsets in a std::vector instead of the buffer
//! manager, for the Step 1 timing comparison only.
std::atomic<bool> g_vector_bitsets {false};

//! The drain's bitset: bit r of 64-bit words set when rowid r arrived, grown by doubling. Its words
//! come from DuckDB's buffer manager (AllocateTemporaryMemory, pinned for the bitset's life), so they
//! count against memory_limit and a drain past it fails the statement with DuckDB's out-of-memory
//! error instead of growing the process. A rowid at or above search::kMaxRowidExclusive is refused
//! before any allocation for it (Task 7a Step 1).
class RowidBitset {
public:
	explicit RowidBitset(ClientContext &context)
	    : buffer_manager(BufferManager::GetBufferManager(context)), use_vector(g_vector_bitsets.load()) {
	}

	//! Sets bit `rowid`; refuses a negative rowid and one at or above the cap.
	void Set(int64_t rowid) {
		if (rowid < 0) {
			throw InvalidInputException("QoD: search_internal: negative rowid");
		}
		if (static_cast<uint64_t>(rowid) >= search::kMaxRowidExclusive) {
			throw InvalidInputException(search::kRowidOutOfRange);
		}
		auto word = static_cast<size_t>(static_cast<uint64_t>(rowid) >> 6);
		if (word >= size) {
			// Doubling, clamped to the cap's own size (2^21 words, 16 MiB; review M1).
			Grow(std::min<size_t>(kMaxWords, std::max<size_t>({word + 1, size * 2, size_t(1024)})));
		}
		words[word] |= 1ull << (static_cast<uint64_t>(rowid) & 63);
	}

	//! ORs `other`'s bits into this one, growing it to the longer of the two.
	void Or(const RowidBitset &other) {
		if (other.size > size) {
			Grow(other.size);
		}
		for (size_t i = 0; i < other.size; i++) {
			words[i] |= other.words[i];
		}
	}

	size_t WordCount() const {
		return size;
	}
	const uint64_t *Words() const {
		return words;
	}

private:
	static constexpr size_t kMaxWords = static_cast<size_t>(search::kMaxRowidExclusive / 64);

	void Grow(size_t new_size) {
		if (use_vector) {
			vector_words.resize(new_size, 0);
			words = vector_words.data();
			size = new_size;
			return;
		}
		auto bytes = new_size * sizeof(uint64_t);
		auto block = buffer_manager.AllocateTemporaryMemory(MemoryTag::EXTENSION, bytes);
		auto pin = buffer_manager.Pin(block);
		auto fresh = reinterpret_cast<uint64_t *>(pin.GetDataMutable());
		if (size > 0) {
			std::memcpy(fresh, words, size * sizeof(uint64_t));
		}
		std::memset(fresh + size, 0, (new_size - size) * sizeof(uint64_t));
		// The old words are released only once the new ones hold them.
		handle = std::move(pin);
		block_handle = std::move(block);
		words = fresh;
		size = new_size;
	}

	BufferManager &buffer_manager;
	bool use_vector;
	std::vector<uint64_t> vector_words;
	shared_ptr<BlockHandle> block_handle;
	BufferHandle handle;
	uint64_t *words = nullptr;
	size_t size = 0;
};

// The drain of spec 7.6 / 7.4, without the search: every input rowid set in one bitset indexed by
// rowid, on one thread (MaxThreads() = 1, the prototype's route). Part 2 timing probe; the search
// functions of Task 8 drain the same way into per-segment bitsets. qod_search_drain_parallel runs the
// same per-row work in every local state with no thread bound: what a sink operator could reach, a
// timing reference only.

struct DrainBindData : public TableFunctionData {
	explicit DrainBindData(bool single_p) : single(single_p) {
	}
	bool single;
};

struct DrainGlobal : public GlobalTableFunctionState {
	explicit DrainGlobal(bool single_p) : single(single_p) {
	}
	idx_t MaxThreads() const override {
		return single ? 1 : GlobalTableFunctionState::MAX_THREADS;
	}
	bool single;
	std::atomic<idx_t> locals {0};
};

struct DrainLocal : public LocalTableFunctionState {
	explicit DrainLocal(ClientContext &context) : bits(context) {
	}
	RowidBitset bits; // bit r set when rowid r arrived
	idx_t rows = 0;
	int64_t max_rowid = -1;
	std::chrono::steady_clock::time_point first;
	bool started = false;
	bool done = false;
};

unique_ptr<FunctionData> DrainBind(ClientContext &, TableFunctionBindInput &input, vector<LogicalType> &types,
                                   vector<Identifier> &names, bool single) {
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		throw BinderException(std::string(single ? "qod_search_drain" : "qod_search_drain_parallel") +
		                      " takes one BIGINT input column");
	}
	types = {LogicalType::BIGINT, LogicalType::BIGINT, LogicalType::DOUBLE};
	names.emplace_back("rows");
	names.emplace_back("max_rowid");
	names.emplace_back("drain_ms");
	return make_uniq<DrainBindData>(single);
}

unique_ptr<FunctionData> DrainBindOne(ClientContext &context, TableFunctionBindInput &input,
                                      vector<LogicalType> &types, vector<Identifier> &names) {
	return DrainBind(context, input, types, names, true);
}

unique_ptr<FunctionData> DrainBindParallel(ClientContext &context, TableFunctionBindInput &input,
                                           vector<LogicalType> &types, vector<Identifier> &names) {
	return DrainBind(context, input, types, names, false);
}

unique_ptr<GlobalTableFunctionState> DrainInitGlobal(ClientContext &, TableFunctionInitInput &input) {
	return make_uniq<DrainGlobal>(input.bind_data->Cast<DrainBindData>().single);
}

unique_ptr<LocalTableFunctionState> DrainInitLocal(ExecutionContext &context, TableFunctionInitInput &,
                                                   GlobalTableFunctionState *global_p) {
	auto &global = global_p->Cast<DrainGlobal>();
	auto index = global.locals++;
	if (global.single && index >= 1) {
		// A UNION input runs the call in one pipeline per branch, each with a local state of its own,
		// whatever MaxThreads() says (review C1). The probe keeps its one-row contract and refuses it, with
		// an ordinary error: an internal one raised during execution would invalidate the database.
		throw ExecutorException("QoD: qod_search_drain reads its input in one pipeline, and this input (a UNION) "
		                        "runs in several: use qod_search_drain_sink");
	}
	return make_uniq<DrainLocal>(context.client);
}

OperatorResultType DrainInOut(ExecutionContext &, TableFunctionInput &data, DataChunk &input, DataChunk &output) {
	auto &local = data.local_state->Cast<DrainLocal>();
	auto count = input.size();
	if (count > 0 && !local.started) {
		local.first = std::chrono::steady_clock::now();
		local.started = true;
	}
	UnifiedVectorFormat format;
	input.data[0].ToUnifiedFormat(format);
	auto values = format.GetData<int64_t>();
	for (idx_t i = 0; i < count; i++) {
		auto index = format.sel->get_index(i);
		if (!format.validity.RowIsValid(index)) {
			continue;
		}
		auto rowid = values[index];
		local.bits.Set(rowid);
		local.rows++;
		local.max_rowid = std::max(local.max_rowid, rowid);
	}
	output.SetChildCardinality(0);
	return OperatorResultType::NEED_MORE_INPUT;
}

OperatorFinalizeResultType DrainFinal(ExecutionContext &, TableFunctionInput &data, DataChunk &output) {
	auto &local = data.local_state->Cast<DrainLocal>();
	if (local.done) {
		output.SetChildCardinality(0);
		return OperatorFinalizeResultType::FINISHED;
	}
	local.done = true;
	double ms = 0;
	if (local.started) {
		ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - local.first).count();
	}
	output.data[0].SetValue(0, Value::BIGINT(static_cast<int64_t>(local.rows)));
	output.data[1].SetValue(0, Value::BIGINT(local.max_rowid));
	output.data[2].SetValue(0, Value::DOUBLE(ms));
	output.SetChildCardinality(1);
	return OperatorFinalizeResultType::FINISHED;
}

// ---- qod_search_drain_sink: the same drain, executed by the search sink (Task 7a) ---------------

unique_ptr<FunctionData> DrainSinkBind(ClientContext &, TableFunctionBindInput &input, vector<LogicalType> &types,
                                       vector<Identifier> &names) {
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		throw BinderException("qod_search_drain_sink takes one BIGINT input column");
	}
	types = {LogicalType::BIGINT, LogicalType::BIGINT, LogicalType::DOUBLE, LogicalType::BIGINT};
	names.emplace_back("rows");
	names.emplace_back("max_rowid");
	names.emplace_back("drain_ms");
	names.emplace_back("locals");
	return make_uniq<DrainBindData>(false);
}

struct DrainSinkLocal : public search::SinkLocal {
	explicit DrainSinkLocal(ClientContext &context) : bits(context) {
	}
	RowidBitset bits;
	idx_t rows = 0;
	int64_t max_rowid = -1;
	bool started = false;
	std::chrono::steady_clock::time_point first;
};

//! Task 5's drain, its bitsets merged once per local state and Finish once per execution: `rows` the
//! non-null rowids consumed, `max_rowid` their largest (-1 for none), `drain_ms` from the first Consume
//! of any local state to the end of Finish, `locals` the number of local sink states.
class DrainSinkProgram : public search::SinkProgram {
public:
	explicit DrainSinkProgram(ClientContext &context_p) : context(context_p), bits(context_p) {
	}
	std::unique_ptr<search::SinkLocal> NewLocal() override {
		return std::unique_ptr<search::SinkLocal>(new DrainSinkLocal(context));
	}
	void Consume(ClientContext &, search::SinkLocal &local_p, Vector &rowids, idx_t count) override {
		auto &local = static_cast<DrainSinkLocal &>(local_p);
		if (count > 0 && !local.started) {
			local.first = std::chrono::steady_clock::now();
			local.started = true;
		}
		UnifiedVectorFormat format;
		rowids.ToUnifiedFormat(format);
		auto values = format.GetData<int64_t>();
		for (idx_t i = 0; i < count; i++) {
			auto index = format.sel->get_index(i);
			if (!format.validity.RowIsValid(index)) {
				continue;
			}
			auto rowid = values[index];
			local.bits.Set(rowid);
			local.rows++;
			local.max_rowid = std::max(local.max_rowid, rowid);
		}
	}
	void Merge(search::SinkLocal &local_p) override {
		auto &local = static_cast<DrainSinkLocal &>(local_p);
		bits.Or(local.bits);
		rows += local.rows;
		max_rowid = std::max(max_rowid, local.max_rowid);
		locals++;
		if (local.started && (!started || local.first < first)) {
			first = local.first;
			started = true;
		}
	}
	void Finish(ClientContext &) override {
		drain_ms = started ? std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - first).count()
		                   : 0;
	}
	idx_t OutputRows() const override {
		return 1;
	}
	Value OutputValue(idx_t, idx_t column) const override {
		switch (column) {
		case 0:
			return Value::BIGINT(static_cast<int64_t>(rows));
		case 1:
			return Value::BIGINT(max_rowid);
		case 2:
			return Value::DOUBLE(drain_ms);
		default:
			return Value::BIGINT(static_cast<int64_t>(locals));
		}
	}

private:
	ClientContext &context;
	RowidBitset bits;
	idx_t rows = 0;
	int64_t max_rowid = -1;
	idx_t locals = 0;
	bool started = false;
	std::chrono::steady_clock::time_point first;
	double drain_ms = 0;
};

// ---- qod_search (Task 8): the prototype's search, executed by the search sink --------------------

//! Settings and test hooks of the search. The memory limit is read once, at registration.
std::atomic<uint64_t> g_query_memory {uint64_t(4) << 30};
std::atomic<bool> g_cancel_in_finish {false};
std::mutex g_hooks_lock;
std::string g_bundle_root;
std::function<void(bool)> g_resolve_observer;
std::function<void(ClientContext &)> g_call_observer;
SearchCallRecord g_last_call;

std::string BundleRoot() {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	return g_bundle_root;
}

void ObserveResolve(bool begin) {
	std::function<void(bool)> observer;
	{
		std::lock_guard<std::mutex> guard(g_hooks_lock);
		observer = g_resolve_observer;
	}
	if (observer) {
		observer(begin);
	}
}

uint64_t QueryMemoryFromEnvironment() {
	auto text = std::getenv("QOD_SEARCH_QUERY_MEMORY");
	if (!text || !*text) {
		return uint64_t(4) << 30;
	}
	char *end = nullptr;
	auto value = std::strtoull(text, &end, 10);
	if (!end || *end != '\0' || value == 0) {
		std::cerr << "[qod-node] QOD_SEARCH_QUERY_MEMORY is not a number of bytes; using 4 GiB" << std::endl;
		return uint64_t(4) << 30;
	}
	return value;
}

//! A library error's text, freed.
std::string TakeError(char *err) {
	std::string text = err ? err : "search_internal: no message";
	qs_string_free(err);
	return text;
}

[[noreturn]] void SearchRefused(const std::string &why) {
	throw PermissionException("QoD: " + why);
}

idx_t WordsFor(uint32_t docs) {
	return (static_cast<idx_t>(docs) + 63) / 64;
}

unique_ptr<FunctionData> SearchBind(ClientContext &context, TableFunctionBindInput &input, vector<LogicalType> &types,
                                    vector<Identifier> &names) {
	if (input.input_table_types.size() != 1 || input.input_table_types[0] != LogicalType::BIGINT) {
		SearchRefused("search_input_not_governed: the input must have exactly one column, a BIGINT rowid");
	}
	// The values below are folded: rule 7 (constant arguments) is judged on the statement's text, in
	// post_bind (FindSearchCalls), since DuckDB moves the parsed arguments out while binding.
	// The TABLE argument holds no value; the scalar arguments follow it.
	auto &inputs = input.inputs;
	idx_t at = inputs.size() == 4 ? 1 : 0;
	if (inputs.size() - at != 3) {
		throw BinderException("qod_search takes (input, table, columns, query)");
	}
	auto &table = inputs[at];
	auto &columns = inputs[at + 1];
	auto &query = inputs[at + 2];
	if (table.IsNull() || columns.IsNull() || query.IsNull()) {
		throw InvalidInputException("QoD: search_invalid_argument: table, columns and query cannot be NULL");
	}
	auto data = make_uniq<search::SearchBindData>();
	data->table = table.GetValue<string>();
	auto &listed = ListValue::GetChildren(columns);
	if (listed.size() != 1 || listed[0].IsNull()) {
		throw InvalidInputException("QoD: search_columns_unsupported: the prototype searches exactly one column");
	}
	data->column = listed[0].GetValue<string>();
	// The column as the table names it (DuckDB matches column names without case), as the describer and
	// the index id read it (review minor 1); FindSearchCalls refuses a table or column that resolves to none.
	if (auto resolved = search::ResolveSearchTable(context, data->table)) {
		auto column = search::TableColumnName(*resolved, data->column);
		if (!column.empty()) {
			data->column = column;
		}
	}
	data->query = query.GetValue<string>();
	int64_t k = 10;
	for (auto &named : input.named_parameters) {
		auto name = StringUtil::Lower(named.first.GetIdentifierName());
		if (name == "k") {
			if (named.second.IsNull()) {
				throw InvalidInputException("QoD: search_invalid_argument: k cannot be NULL");
			}
			k = named.second.GetValue<int64_t>();
		} else if (name == "query_json") {
			if (!named.second.IsNull()) {
				throw InvalidInputException("QoD: search_query_json_unsupported: the prototype takes no query_json");
			}
		}
	}
	if (k < 1 || k > 10000) {
		throw InvalidInputException("QoD: search_invalid_argument: k must be between 1 and 10000");
	}
	data->k = static_cast<uint32_t>(k);
	// Rule 6 and the query limits (spec 9), before any segment is read.
	char *err = nullptr;
	if (qs_check_query(data->query.c_str(), data->column.c_str(), &err) != 0) {
		auto text = TakeError(err);
		if (StringUtil::StartsWith(text, "search_field_not_allowed")) {
			SearchRefused(text);
		}
		throw InvalidInputException("QoD: " + text);
	}
	types = {LogicalType::BIGINT, LogicalType::FLOAT, LogicalType::INTEGER};
	names.emplace_back("rowid");
	names.emplace_back("score");
	names.emplace_back("rank");
	return std::move(data);
}

//! A local sink state's allowed bitsets, one per segment, allocated when a rowid first lands in it.
struct SearchLocal : public search::SinkLocal {
	std::vector<std::vector<uint64_t>> allowed;
};

//! One indexed data file of the call.
struct SearchSegment {
	std::shared_ptr<const search::OpenSegment> open;
	size_t file_index = 0;
	std::vector<uint64_t> live; // exactly WordsFor(num_docs) words
	size_t liveness_words = 0;  // FileLiveness::words.size() before the resize (0 for a fallback segment)
	bool fallback = false;      // an in-memory segment of the fallback (Task 11)
};

//! An affine segment's rowids [start, end).
struct RowidRange {
	int64_t start;
	int64_t end;
	size_t segment;
};

//! The search of one execution (spec 7.1 steps 3 to 7): the factory loads the call's segments and its
//! rowid map; Consume maps each input rowid to its one live (segment, doc) and sets the allowed bit;
//! Finish runs qs_search once over the merged allowed bitsets and the live bitsets at S.
class SearchProgram : public search::SinkProgram {
public:
	SearchProgram(ClientContext &context, const search::SearchBindData &bind) : bind_(bind) {
		auto target = bind.target;
		if (!target || !target->attached) {
			throw InvalidInputException("QoD: search_unbound: the search call was not bound to its target");
		}
		if (MetaTransaction::Get(context).global_transaction_id != target->transaction_id) {
			throw InvalidInputException("QoD: search_unbound: the search call was bound in another transaction");
		}
		// Review minor 6: the view was resolved at bind; a write to the lake since (an EXECUTE after a write in the
		// same transaction, were it not rebound) would leave it stale.
		auto modified = MetaTransaction::Get(context).ModifiedDatabase();
		if (modified && target->table && modified.get() == &target->table->ParentCatalog().GetAttached()) {
			throw InvalidInputException("QoD: search_uncommitted_writes: the transaction has written to " +
			                            target->table->ParentCatalog().GetName().GetIdentifierName() +
			                            "; search reads committed data only, so commit or roll back first");
		}
		target_ = target;
		if (!target->view) {
			throw InvalidInputException("QoD: search_unbound: the search call was not bound to its target");
		}
		auto &view = *target->view;
		// Spec 9 (Task 11): a live data file whose segment is missing or refused, and the inlined rows live at
		// S, are indexed for this query into in-memory segments (search_fallback.hpp), up to the cap; past it
		// the search fails with search_index_behind, never a different answer (invariant 3).
		std::vector<search::DataFileRef> unindexed;
		for (size_t f = 0; f < view.files.size(); f++) {
			// A copy: the view may be the liveness cache's, shared and never modified; LoadSegment sets the
			// copy's internal-column flags from the bundle.
			auto file = view.files[f];
			auto &liveness = view.liveness[f];
			if (liveness.live_count == 0) {
				continue;
			}
			auto path = search::SegmentPath(target->bundle_root, target->index_id, file.bundle_key);
			auto open = search::LoadSegment(context, path, target->source_id, file);
			if (!open) {
				// Unindexed (logged once with its path): its live rows go to the fallback.
				unindexed.push_back(std::move(file));
				continue;
			}
			SearchSegment segment;
			segment.open = open;
			segment.file_index = f;
			segment.liveness_words = liveness.words.size();
			// Spec 7.5 (Task 10): the file's liveness at S as the segment's doc-id bitset, exactly
			// ceil(num_docs / 64) words (Task 6's words end at the highest live position), matched by
			// (file index, position). A liveness past the bundle's rows refuses the search.
			segment.live = search::LiveDocs(liveness, open->num_docs);
			held_bytes_ += segment.live.size() * sizeof(uint64_t);
			AddSegment(std::move(segment));
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
			auto limit = g_query_memory.load();
			auto held = held_bytes_.load();
			if (held >= limit) {
				throw InvalidInputException("QoD: search_memory_exceeded: the search holds " + std::to_string(held) +
				                            " bytes of bitsets, past its budget of " + std::to_string(limit) + " bytes");
			}
			auto fallback = search::BuildFallbackSegments(
			    context, view, unindexed, target->field_id, search::MaxUnindexedRows(), search::MaxUnindexedSources(),
			    search::FallbackScan {*target->table, target->at_clause.get()}, target->source_id, limit - held);
			for (auto &f : fallback) {
				SearchSegment segment;
				segment.open = f.open;
				segment.file_index = f.inlined ? SIZE_MAX : f.file_index;
				segment.fallback = true;
				// Every doc of a fallback segment is live at S (the scan returned only those).
				segment.live.assign(WordsFor(f.open->num_docs), ~uint64_t(0));
				if (f.open->num_docs % 64) {
					segment.live.back() = (uint64_t(1) << (f.open->num_docs % 64)) - 1;
				}
				held_bytes_ += f.bytes + segment.live.size() * sizeof(uint64_t);
				fallback_docs_ += f.open->num_docs;
				fallback_bytes_ += f.bytes;
				AddSegment(std::move(segment));
			}
			fallback_segments_ = fallback.size();
			fallback_ms_ = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
			if (held_bytes_.load() >= limit) {
				throw InvalidInputException("QoD: search_memory_exceeded: the search holds " +
				                            std::to_string(held_bytes_.load()) + " bytes, past its budget of " +
				                            std::to_string(limit) + " bytes");
			}
		}
		std::sort(ranges_.begin(), ranges_.end(),
		          [](const RowidRange &a, const RowidRange &b) { return a.start < b.start; });
		max_end_.resize(ranges_.size());
		for (size_t i = 0; i < ranges_.size(); i++) {
			max_end_[i] = i == 0 ? ranges_[i].end : std::max(max_end_[i - 1], ranges_[i].end);
		}
		allowed_.resize(segments_.size());
	}

	std::unique_ptr<search::SinkLocal> NewLocal() override {
		auto local = std::unique_ptr<SearchLocal>(new SearchLocal());
		local->allowed.resize(segments_.size());
		return std::move(local);
	}

	void Consume(ClientContext &, search::SinkLocal &local_p, Vector &rowids, idx_t count) override {
		auto &local = static_cast<SearchLocal &>(local_p);
		UnifiedVectorFormat format;
		rowids.ToUnifiedFormat(format);
		auto values = format.GetData<int64_t>();
		for (idx_t i = 0; i < count; i++) {
			auto index = format.sel->get_index(i);
			if (!format.validity.RowIsValid(index)) {
				continue;
			}
			size_t segment;
			uint32_t doc;
			Locate(values[index], segment, doc);
			auto &bits = local.allowed[segment];
			if (bits.empty()) {
				auto bytes = segments_[segment].live.size() * sizeof(uint64_t);
				auto held = held_bytes_.fetch_add(bytes) + bytes;
				if (held >= g_query_memory.load()) {
					throw InvalidInputException("QoD: search_memory_exceeded: the search's bitsets need " + std::to_string(held) +
					                            " bytes, past its budget of " + std::to_string(g_query_memory.load()) +
					                            " bytes");
				}
				bits.assign(segments_[segment].live.size(), 0);
			}
			bits[doc >> 6] |= uint64_t(1) << (doc & 63);
		}
	}

	void Merge(search::SinkLocal &local_p) override {
		auto &local = static_cast<SearchLocal &>(local_p);
		for (size_t s = 0; s < segments_.size(); s++) {
			auto &bits = local.allowed[s];
			if (bits.empty()) {
				continue;
			}
			if (allowed_[s].empty()) {
				allowed_[s] = std::move(bits);
				continue;
			}
			for (size_t w = 0; w < bits.size(); w++) {
				allowed_[s][w] |= bits[w];
			}
			held_bytes_ -= bits.size() * sizeof(uint64_t);
			std::vector<uint64_t>().swap(bits);
		}
	}

	void Finish(ClientContext &context) override {
		std::vector<const QsSegment *> segments;
		std::vector<const uint64_t *> live;
		std::vector<const uint64_t *> allowed;
		std::vector<size_t> live_words;
		std::vector<size_t> allowed_words;
		SearchCallRecord record;
		for (size_t s = 0; s < segments_.size(); s++) {
			auto &segment = segments_[s];
			if (allowed_[s].empty()) {
				allowed_[s].assign(segment.live.size(), 0);
				held_bytes_ += allowed_[s].size() * sizeof(uint64_t);
			}
			segments.push_back(segment.open->segment);
			live.push_back(segment.live.data());
			live_words.push_back(segment.live.size());
			allowed.push_back(allowed_[s].data());
			allowed_words.push_back(allowed_[s].size());
			record.num_docs.push_back(segment.open->num_docs);
			record.liveness_words.push_back(segment.liveness_words);
		}
		auto limit = g_query_memory.load();
		uint64_t held = held_bytes_.load();
		if (held >= limit) {
			throw InvalidInputException("QoD: search_memory_exceeded: the search holds " + std::to_string(held) +
			                            " bytes of bitsets, past its budget of " + std::to_string(limit) + " bytes");
		}
		auto budget = limit - held;
		hits_.assign(bind_.k, QsHit {});
		record.live_words = live_words;
		record.allowed_words = allowed_words;
		record.out_cap = hits_.size();
		record.k = bind_.k;
		record.memory_budget = budget;
		record.held_bytes = held;
		record.population = target_->population;
		record.view = target_->view;
		record.view_hit = target_->view_hit;
		record.fallback_segments = fallback_segments_;
		record.fallback_docs = fallback_docs_;
		record.fallback_bytes = fallback_bytes_;
		record.fallback_ms = fallback_ms_;
		record.called = true;
		{
			std::lock_guard<std::mutex> guard(g_hooks_lock);
			g_last_call = record;
		}
		std::atomic<uint8_t> cancel {0};
		if (g_cancel_in_finish.load()) {
			cancel = 1;
		}
		size_t n = 0;
		char *err = nullptr;
		int rc = 0;
		std::function<void(ClientContext &)> observer;
		{
			std::lock_guard<std::mutex> guard(g_hooks_lock);
			observer = g_call_observer;
		}
		try {
			// The library polls `cancel` (spec 9); a watcher sets it once the client interrupts the query.
			std::mutex lock;
			std::condition_variable wake;
			bool done = false;
			std::thread watcher([&] {
				std::unique_lock<std::mutex> guard(lock);
				while (!done) {
					if (context.IsInterrupted()) {
						cancel = 1;
					}
					wake.wait_for(guard, std::chrono::milliseconds(2));
				}
			});
			if (observer) {
				try {
					observer(context); // a test hook; it must not leave the watcher unjoined
				} catch (...) {
				}
			}
			rc = qs_search(segments.data(), segments.size(), live.data(), live_words.data(), allowed.data(),
			               allowed_words.data(), target_->population, bind_.query.c_str(), bind_.column.c_str(), 1.2f,
			               0.75f, bind_.k, reinterpret_cast<const uint8_t *>(&cancel), budget, hits_.data(),
			               hits_.size(), &n, &err);
			{
				std::lock_guard<std::mutex> guard(lock);
				done = true;
			}
			wake.notify_all();
			watcher.join();
		} catch (std::system_error &) {
			// The watcher thread could not start (review minor 8): an ordinary error, nothing searched.
			throw InvalidInputException("QoD: search_internal: the search could not start its cancel watcher");
		}
		if (rc != 0) {
			hits_.clear();
			auto text = TakeError(err);
			if (StringUtil::StartsWith(text, "search_cancelled")) {
				{
					std::lock_guard<std::mutex> guard(g_hooks_lock);
					g_last_call.library_cancelled = true;
				}
				throw InterruptException();
			}
			throw InvalidInputException("QoD: " + text);
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
	void AddSegment(SearchSegment segment) {
		if (segment.open->explicit_rowids) {
			explicit_.push_back(segments_.size());
		} else {
			ranges_.push_back(RowidRange {segment.open->first_rowid, segment.open->first_rowid + segment.open->num_docs,
			                              segments_.size()});
		}
		segments_.push_back(std::move(segment));
	}

	//! The one (segment, doc) live at S that holds `rowid` (7.1 step 3, change 27: ranges neither
	//! disjoint nor sorted). Two live candidates: search_rowid_ambiguous (Q1 says it never happens);
	//! none: a disagreement between two reads at S, search_snapshot_unavailable.
	void Locate(int64_t rowid, size_t &segment, uint32_t &doc) const {
		int live = 0;
		auto consider = [&](size_t s, uint32_t d) {
			auto &bits = segments_[s].live;
			if ((bits[d >> 6] >> (d & 63)) & 1) {
				if (++live == 1) {
					segment = s;
					doc = d;
				}
			}
		};
		auto end = std::upper_bound(ranges_.begin(), ranges_.end(), rowid,
		                            [](int64_t value, const RowidRange &range) { return value < range.start; });
		for (auto i = static_cast<int64_t>(end - ranges_.begin()) - 1; i >= 0 && max_end_[i] > rowid; i--) {
			auto &range = ranges_[i];
			if (rowid < range.end) {
				consider(range.segment, static_cast<uint32_t>(rowid - range.start));
			}
		}
		for (auto s : explicit_) {
			auto &pairs = segments_[s].open->rowid_docs;
			auto first = std::lower_bound(pairs.begin(), pairs.end(), std::make_pair(rowid, uint32_t(0)));
			for (auto it = first; it != pairs.end() && it->first == rowid; ++it) {
				consider(s, it->second);
			}
		}
		if (live > 1) {
			throw InvalidInputException("QoD: search_rowid_ambiguous: rowid " + std::to_string(rowid) +
			                            " is live in more than one doc");
		}
		if (live == 0) {
			// Every doc live at S is in a bundle or a fallback segment, and the input traces to the target scan at
			// S (rule 1), so this is a disagreement between two reads at S: refused, never skipped.
			throw InvalidInputException("QoD: search_snapshot_unavailable: rowid " + std::to_string(rowid) +
			                            " of the input is live in no segment");
		}
	}

	const search::SearchBindData &bind_;
	std::shared_ptr<search::SearchTarget> target_;
	std::vector<SearchSegment> segments_;
	std::vector<RowidRange> ranges_;
	std::vector<int64_t> max_end_;
	std::vector<size_t> explicit_;
	std::vector<std::vector<uint64_t>> allowed_;
	std::atomic<uint64_t> held_bytes_ {0};
	std::vector<QsHit> hits_;
	size_t fallback_segments_ = 0;
	uint64_t fallback_docs_ = 0;
	uint64_t fallback_bytes_ = 0;
	double fallback_ms_ = 0;
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

//! Runs after qod-node's own post_bind (registered after it), so only on a plan the verdict admitted:
//! the lineage check, then each call's snapshot view, index record and population (7.1 steps 1 and 2).
//! A PREPARE's plan is checked but not resolved: DuckDB 2.0 rebinds a statement that reads a table at
//! every EXECUTE, and the sink refuses a call resolved in another transaction (invariant 4).
void SearchPostBind(PlannerExtensionInput &input, BoundStatement &statement) {
	if (!statement.plan) {
		return;
	}
	auto &context = input.context;
	Installed installed;
	std::string pool;
	if (SessionSnapshot(context, 0, installed)) {
		pool = installed.pool;
	}
	auto text = StatementText(context);
	auto calls = search::FindSearchCalls(context, *statement.plan, text ? &*text : nullptr, pool);
	if (calls.empty()) {
		return;
	}
	// The PREPARE wrapper's own pass (DuckDB binds the inner statement first, as a plan of its own, which
	// is resolved like any plan: once for nothing, review minor 6): the wrapper's child is that inner plan,
	// so its targets are reset, and the sink refuses one never resolved again (search_unbound).
	auto preparing = statement.plan->type == LogicalOperatorType::LOGICAL_PREPARE;
	// Rule 8: on a quack session every call takes its population and index record from the verdict the
	// statement runs under, superusers' and the system principal's included (their record-only decide
	// call), never a default. The verdict's entries are numbered as the describer numbered the
	// description's `searches`; a count that differs from the calls found here (a call inside a subquery
	// expression, which the lineage walk does not enter) refuses the statement. Off a quack session (the
	// node's own connections, in-process tests) the prototype stand-in remains: SetPrototypeBundleRoot
	// and the table population.
	auto quack = context.registered_state->Get<ClientContextState>(kQuackSessionKey) != nullptr;
	std::vector<SearchEntry> entries;
	if (quack && !preparing) {
		if (!StatementSearchEntries(context, entries) || entries.size() != calls.size()) {
			SearchRefused("search_population_missing: the statement's verdict carries no statistics population "
			              "for each of its search calls");
		}
	}
	for (auto &call : calls) {
		auto &data = *search::SearchCallData(*call.call);
		auto target = std::make_shared<search::SearchTarget>();
		data.target = target;
		if (preparing) {
			continue;
		}
		auto index_id = search::PrototypeIndexId(call.table, call.columns[0]);
		std::string root;
		int population = QS_POPULATION_TABLE;
		if (quack) {
			const SearchEntry *entry = nullptr;
			for (auto &e : entries) {
				if (e.call == call.index) {
					entry = &e;
				}
			}
			if (!entry) {
				SearchRefused("search_population_missing: the statement's verdict carries no statistics population "
				              "for its search call on " +
				              call.table);
			}
			// The prototype has no registry: the record's id is the one derived from the call's own table and
			// column, so a record of another call never serves this one.
			if (entry->index_id != index_id) {
				SearchRefused("search_index_unavailable: the verdict's index record for " + call.table + " (" +
				              call.columns[0] + ") names another index");
			}
			root = entry->bundle_root;
			population = entry->population == "allowed" ? QS_POPULATION_ALLOWED : QS_POPULATION_TABLE;
		} else {
			root = BundleRoot();
		}
		if (root.empty()) {
			SearchRefused("search_index_unavailable: no index record for " + call.table + " (" + call.columns[0] + ")");
		}
		// Review C1: the verdict judged the column by its name now (the describer resolves `table` and `columns`
		// on the current entry). The search reads, in the fallback and in a bundle, the column of the same
		// DuckLake field id, never the column holding that name at S: after `body` is renamed away and another
		// column renamed `body`, AT (VERSION => n) would otherwise read the old `body`. A field the snapshot read
		// does not hold (added since) is refused.
		auto current = search::ResolveSearchTable(context, call.table);
		auto field_id = current ? search::CurrentFieldId(context, *current, call.columns[0]) : int64_t(-1);
		if (field_id < 0) {
			throw InvalidInputException("QoD: search_columns_unsupported: " + call.columns[0] + " of " + call.table +
			                            " has no DuckLake field id");
		}
		if (!search::HasFieldId(call.target->bind_data.get(), field_id)) {
			throw InvalidInputException("QoD: search_columns_unsupported: " + call.columns[0] + " of " + call.table +
			                            " is not a column of the table at the snapshot the input reads");
		}
		ObserveResolve(true);
		try {
			target->view = search::CachedSnapshotView(context, *call.target, target->view_hit);
		} catch (...) {
			ObserveResolve(false);
			throw;
		}
		ObserveResolve(false);
		target->table = call.target->GetTable();
		if (call.target->at_clause) {
			target->at_clause = std::make_shared<BoundAtClause>(*call.target->at_clause);
		}
		target->index_id = index_id;
		target->field_id = field_id;
		target->source_id = search::BundleSourceId(index_id, field_id);
		target->bundle_root = root;
		target->population = population;
		target->transaction_id = MetaTransaction::Get(context).global_transaction_id;
		target->attached = true;
	}
}

// ---- qod_search_build_segment (Task 8): one data file's bundle --------------------------------------

struct BuildBindData : public TableFunctionData {
	std::string index_id;
	std::string source_id; // BundleSourceId: the index id bound to the column's field id
	std::string path;
	std::string path_key;
	std::string bundle_path;
	uint64_t file_size = 0;
	uint64_t footer_size = 0;
};

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
	auto &in = input.input_table_types;
	if (in.size() != 4 || in[0] != LogicalType::BIGINT || in[1] != LogicalType::BIGINT || in[2] != LogicalType::BIGINT ||
	    in[3] != LogicalType::VARCHAR) {
		throw BinderException("qod_search_build_segment takes (pos BIGINT, row_id BIGINT, snapshot_id BIGINT, text "
		                      "VARCHAR) rows");
	}
	auto &inputs = input.inputs;
	idx_t at = inputs.size() == 7 ? 1 : 0;
	if (inputs.size() - at != 6) {
		throw BinderException("qod_search_build_segment takes (input, table, column, bundle_root, path, file_size, "
		                      "footer_size)");
	}
	for (idx_t i = at; i < inputs.size(); i++) {
		if (inputs[i].IsNull()) {
			throw InvalidInputException("qod_search_build_segment: no argument can be NULL");
		}
	}
	auto table = search::ResolveSearchTable(context, inputs[at].GetValue<string>());
	if (!table) {
		throw InvalidInputException("qod_search_build_segment: " + inputs[at].GetValue<string>() + " names no table");
	}
	auto column = search::TableColumnName(*table, inputs[at + 1].GetValue<string>());
	if (column.empty()) {
		throw InvalidInputException("qod_search_build_segment: no such column");
	}
	auto data = make_uniq<BuildBindData>();
	data->index_id = search::PrototypeIndexId(search::CanonicalTableName(*table), column);
	// The bundle is bound to the column's DuckLake field id now (BundleSourceId, review C1).
	auto field_id = search::CurrentFieldId(context, *table, column);
	if (field_id < 0) {
		throw InvalidInputException("QoD: search_columns_unsupported: qod_search_build_segment: the column has no "
		                            "DuckLake field id");
	}
	data->source_id = search::BundleSourceId(data->index_id, field_id);
	data->path = inputs[at + 3].GetValue<string>();
	data->path_key = Hex(Sha256(data->path));
	data->bundle_path = search::SegmentPath(inputs[at + 2].GetValue<string>(), data->index_id, data->path_key);
	data->file_size = inputs[at + 4].GetValue<uint64_t>();
	data->footer_size = inputs[at + 5].GetValue<uint64_t>();
	types = {LogicalType::VARCHAR, LogicalType::BIGINT, LogicalType::BIGINT};
	names.emplace_back("path");
	names.emplace_back("docs");
	names.emplace_back("bytes");
	return std::move(data);
}

unique_ptr<GlobalTableFunctionState> BuildInitGlobal(ClientContext &, TableFunctionInitInput &) {
	return make_uniq<BuildGlobal>();
}

unique_ptr<LocalTableFunctionState> BuildInitLocal(ExecutionContext &, TableFunctionInitInput &input,
                                                   GlobalTableFunctionState *global_p) {
	auto &global = global_p->Cast<BuildGlobal>();
	if (global.locals++ >= 1) {
		throw ExecutorException("QoD: qod_search_build_segment reads its input in one pipeline, and this input runs in "
		                        "several");
	}
	auto &data = input.bind_data->Cast<BuildBindData>();
	auto local = make_uniq<BuildLocal>();
	QsSource source;
	source.index_id = data.source_id.c_str();
	source.path_key = data.path_key.c_str();
	source.path = data.path.c_str();
	source.file_size = data.file_size;
	source.footer_size = data.footer_size;
	source.record_count = -1;
	source.row_id_start = -1;
	source.has_internal_row_id = -1;
	source.has_internal_snapshot = -1;
	char *err = nullptr;
	local->builder = qs_builder_new(&source, reinterpret_cast<const uint8_t *>(&local->cancel), g_query_memory.load(), &err);
	if (!local->builder) {
		throw InvalidInputException("QoD: " + TakeError(err));
	}
	return std::move(local);
}

OperatorResultType BuildInOut(ExecutionContext &context, TableFunctionInput &data, DataChunk &input, DataChunk &output) {
	auto &local = data.local_state->Cast<BuildLocal>();
	if (context.client.IsInterrupted()) {
		local.cancel = 1;
	}
	UnifiedVectorFormat pos, rowid, snapshot, text;
	auto count = input.size();
	input.data[0].ToUnifiedFormat(pos);
	input.data[1].ToUnifiedFormat(rowid);
	input.data[2].ToUnifiedFormat(snapshot);
	input.data[3].ToUnifiedFormat(text);
	auto positions = pos.GetData<int64_t>();
	auto rowids = rowid.GetData<int64_t>();
	auto snapshots = snapshot.GetData<int64_t>();
	auto texts = text.GetData<string_t>();
	for (idx_t i = 0; i < count; i++) {
		auto p = pos.sel->get_index(i);
		auto r = rowid.sel->get_index(i);
		auto s = snapshot.sel->get_index(i);
		auto t = text.sel->get_index(i);
		if (!pos.validity.RowIsValid(p) || positions[p] != local.next) {
			throw InvalidInputException("QoD: search_build_out_of_order: position " +
			                            (pos.validity.RowIsValid(p) ? std::to_string(positions[p]) : std::string("NULL")) +
			                            " arrived where " + std::to_string(local.next) + " was due");
		}
		if (!rowid.validity.RowIsValid(r)) {
			throw InvalidInputException("QoD: search_build: a NULL row_id");
		}
		auto snapshot_id = snapshot.validity.RowIsValid(s) ? snapshots[s] : int64_t(-1);
		const char *bytes = nullptr;
		size_t len = 0;
		if (text.validity.RowIsValid(t)) {
			bytes = texts[t].GetData();
			len = texts[t].GetSize();
		}
		char *err = nullptr;
		if (qs_builder_add(local.builder, static_cast<uint64_t>(local.next), rowids[r], snapshot_id, bytes, len, &err) !=
		    0) {
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
	output.data[0].SetValue(0, Value(bind.bundle_path));
	output.data[1].SetValue(0, Value::BIGINT(static_cast<int64_t>(docs)));
	output.data[2].SetValue(0, Value::BIGINT(static_cast<int64_t>(len)));
	output.SetChildCardinality(1);
	return OperatorFinalizeResultType::FINISHED;
}

TableFunction DrainFunction(const char *name, table_function_bind_t bind) {
	TableFunction function(name, {LogicalType::TABLE}, nullptr, bind, DrainInitGlobal, DrainInitLocal);
	function.in_out_function = DrainInOut;
	function.in_out_function_final = DrainFinal;
	return function;
}

} // namespace

void UseVectorBitsetsForTest(bool use) {
	g_vector_bitsets = use;
}

void RegisterSearchFunctions(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_node");
	loader.RegisterFunction(DrainFunction("qod_search_drain", DrainBindOne));
	loader.RegisterFunction(DrainFunction("qod_search_drain_parallel", DrainBindParallel));
	TableFunction sink("qod_search_drain_sink", {LogicalType::TABLE}, nullptr, DrainSinkBind);
	search::RegisterSinkFunction(loader, sink, [](ClientContext &context, const FunctionData &) {
		return std::unique_ptr<search::SinkProgram>(new DrainSinkProgram(context));
	});
	search::RegisterSearchSink(db);

	g_query_memory = QueryMemoryFromEnvironment();
	search::ReadFallbackCapsFromEnvironment();
	TableFunction search("qod_search",
	                     {LogicalType::TABLE, LogicalType::VARCHAR, LogicalType::LIST(LogicalType::VARCHAR),
	                      LogicalType::VARCHAR},
	                     nullptr, SearchBind);
	search.GetSignature().AddKeywordOnly(Identifier("k"), LogicalType::INTEGER, Value::INTEGER(10));
	search.GetSignature().AddKeywordOnly(Identifier("query_json"), LogicalType::VARCHAR, Value(LogicalType::VARCHAR));
	search::RegisterSinkFunction(loader, search, [](ClientContext &context, const FunctionData &bind) {
		return std::unique_ptr<search::SinkProgram>(
		    new SearchProgram(context, bind.Cast<search::SearchBindData>()));
	});
	TableFunction build("qod_search_build_segment",
	                    {LogicalType::TABLE, LogicalType::VARCHAR, LogicalType::VARCHAR, LogicalType::VARCHAR,
	                     LogicalType::VARCHAR, LogicalType::UBIGINT, LogicalType::UBIGINT},
	                    nullptr, BuildBind, BuildInitGlobal, BuildInitLocal);
	build.in_out_function = BuildInOut;
	build.in_out_function_final = BuildFinal;
	loader.RegisterFunction(build);
	ExtensionCallback::Register(DBConfig::GetConfig(db), make_shared_ptr<SearchTextCallback>());
	PlannerExtension planner;
	planner.post_bind_function = SearchPostBind;
	PlannerExtension::Register(DBConfig::GetConfig(db), planner);
}

void SetPrototypeBundleRoot(const std::string &root) {
	std::lock_guard<std::mutex> guard(g_hooks_lock);
	g_bundle_root = root;
}

void SetSearchMemoryForTest(uint64_t bytes) {
	g_query_memory = bytes;
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

} // namespace qod
