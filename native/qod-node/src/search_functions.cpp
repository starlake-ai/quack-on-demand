#include "search_functions.hpp"

#include "search_sink.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/storage/buffer/buffer_handle.hpp"
#include "duckdb/storage/buffer_manager.hpp"
#include "duckdb/common/vector/unified_vector_format.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
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
			Grow(std::max<size_t>({word + 1, size * 2, size_t(1024)}));
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
		throw InternalException("QoD: search_internal: more than one local state");
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
}

} // namespace qod
