#include "search_snapshot.hpp"

// The named exception of spec section 13 (OWNER RULING 2026-11-14): see search_snapshot.hpp. The table
// function and scan driving below is ported from Task 1's probe (tests/search_source_probe_test.cpp,
// Drive, CallTableFunction, ScanLakeTable), which showed these reads run in the client's transaction
// on both catalog kinds (docs/search-PHASE0.md P1 points 3 to 5).

#include "duckdb/catalog/catalog.hpp"
#include "duckdb/catalog/catalog_entry/schema_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_catalog_entry.hpp"
#include "duckdb/catalog/catalog_entry/table_function_catalog_entry.hpp"
#include "duckdb/catalog/entry_lookup_info.hpp"
#include "duckdb/common/exception.hpp"
#include "duckdb/common/multi_file/multi_file_reader.hpp"
#include "duckdb/common/multi_file/multi_file_states.hpp"
#include "duckdb/common/open_file_info.hpp"
#include "duckdb/common/vector/unified_vector_format.hpp"
#include "duckdb/execution/execution_context.hpp"
#include "duckdb/function/table_function.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/parallel/thread_context.hpp"
#include "duckdb/parser/tableref/table_function_ref.hpp"
#include "duckdb/planner/binder.hpp"
#include "duckdb/planner/filter/expression_filter.hpp"
#include "duckdb/planner/table_filter_set.hpp"
#include "duckdb/planner/tableref/bound_at_clause.hpp"
#include "duckdb/transaction/meta_transaction.hpp"

#include "crypto.hpp"

#include <algorithm>
#include <bitset>
#include <chrono>
#include <cstring>
#include <functional>
#include <map>
#include <set>
#include <unordered_map>

namespace qod::search {
namespace {

using namespace duckdb;

[[noreturn]] void Unavailable(const std::string &why) {
	throw InvalidInputException("search_snapshot_unavailable: " + why);
}

std::string Str(const Identifier &id) {
	return id.GetIdentifierName();
}

//! Rows a driven scan returned, by column name.
struct Rows {
	std::vector<std::string> names;
	std::vector<std::vector<Value>> rows;
	int Col(const std::string &name) const {
		for (size_t i = 0; i < names.size(); i++) {
			if (StringUtil::CIEquals(names[i], name)) {
				return static_cast<int>(i);
			}
		}
		return -1;
	}
	int Need(const std::string &name, const std::string &what) const {
		auto c = Col(name);
		if (c < 0) {
			Unavailable(what + " returned no column " + name);
		}
		return c;
	}
};

//! Drives a bound table function on the client's context, on this thread: init_global, init_local,
//! then the scan callback until it returns an empty chunk, each chunk handed to `sink`. No statement,
//! no Query: nothing takes the client context's lock (P1 point 1).
template <class F>
void DriveChunks(ClientContext &context, const F &function, FunctionData *bind_data, const vector<LogicalType> &types,
                 vector<column_t> column_ids, optional_ptr<TableFilterSet> filters,
                 const std::function<void(DataChunk &)> &sink) {
	if (!function.function) {
		Unavailable("the function has no scan callback");
	}
	vector<idx_t> projection_ids;
	TableFunctionInitInput init_input(bind_data, column_ids, projection_ids, filters);
	unique_ptr<GlobalTableFunctionState> global;
	if (function.init_global) {
		global = function.init_global(context, init_input);
	}
	ThreadContext thread(context);
	ExecutionContext exec(context, thread, nullptr);
	unique_ptr<LocalTableFunctionState> local;
	if (function.init_local) {
		local = function.init_local(exec, init_input, global.get());
	}
	DataChunk chunk;
	chunk.Initialize(Allocator::Get(context), types);
	TableFunctionInput input(bind_data, local.get(), global.get());
	while (true) {
		chunk.Reset();
		function.function(context, input, chunk);
		if (chunk.size() == 0) {
			break;
		}
		sink(chunk);
	}
}

//! DriveChunks, every cell as a Value: for the small reads (a snapshot id, a file listing, a delete
//! file's positions).
template <class F>
Rows Drive(ClientContext &context, const F &function, FunctionData *bind_data, const vector<LogicalType> &types,
           const std::vector<std::string> &names, vector<column_t> column_ids) {
	Rows out;
	out.names = names;
	DriveChunks(context, function, bind_data, types, std::move(column_ids), nullptr, [&](DataChunk &chunk) {
		for (idx_t row = 0; row < chunk.size(); row++) {
			std::vector<Value> values;
			values.reserve(chunk.ColumnCount());
			for (idx_t col = 0; col < chunk.ColumnCount(); col++) {
				values.push_back(chunk.GetValue(col, row));
			}
			out.rows.push_back(std::move(values));
		}
	});
	return out;
}

//! A bound system-catalog table function: its bind data and what it returns.
struct BoundCall {
	unique_ptr<BoundTableFunction> function;
	unique_ptr<FunctionData> bind_data;
	vector<LogicalType> types;
	std::vector<std::string> names;
};

//! Binds `system.main.<name>` (a fixed name: no macro or function a session creates can shadow it) as
//! Binder::BindTableFunctionInternal does, with the given arguments.
BoundCall BindSystemTableFunction(ClientContext &context, const std::string &name, vector<Value> args,
                                  named_argument_map_t named) {
	auto &entry = Catalog::GetEntry<TableFunctionCatalogEntry>(
	    context, QualifiedName(Identifier("system"), Identifier("main"), Identifier(name)));
	vector<LogicalType> arg_types;
	for (auto &a : args) {
		arg_types.push_back(a.type());
	}
	auto definition = entry.functions.GetFunctionByArguments(context, arg_types);
	BoundCall call;
	call.function = make_uniq<BoundTableFunction>(definition);
	auto &bound = *call.function;
	bound.GetSignature().FillNamedDefaults(context, named);
	bound.SetCallArguments(args, named);
	if (!bound.bind) {
		Unavailable(name + " has no bind callback");
	}
	vector<LogicalType> input_types;
	vector<Identifier> input_names;
	TableFunctionRef ref;
	ref.alias = Identifier(name);
	auto binder = Binder::CreateBinder(context);
	TableFunctionBindInput bind_input(args, named, input_types, input_names, bound.function_info.get(), binder.get(),
	                                  bound, ref);
	vector<Identifier> return_names;
	call.bind_data = bound.bind(context, bind_input, call.types, return_names);
	for (auto &n : return_names) {
		call.names.push_back(Str(n));
	}
	return call;
}

Rows CallSystemTableFunction(ClientContext &context, const std::string &name, vector<Value> args,
                             named_argument_map_t named) {
	auto call = BindSystemTableFunction(context, name, std::move(args), std::move(named));
	vector<column_t> ids;
	for (idx_t i = 0; i < call.types.size(); i++) {
		ids.push_back(i);
	}
	return Drive(context, *call.function, call.bind_data.get(), call.types, call.names, ids);
}

int64_t AsInt(const Value &v) {
	return v.IsNull() ? -1 : v.GetValue<int64_t>();
}

std::string Quote(const std::string &text) {
	return "'" + text + "'";
}

std::string Option(const OpenFileInfo &file, const std::string &key, bool &found) {
	found = false;
	if (!file.extended_info) {
		return "";
	}
	auto it = file.extended_info->options.find(key);
	if (it == file.extended_info->options.end()) {
		return "";
	}
	found = true;
	return it->second.IsNull() ? "" : it->second.ToString();
}

const Value *OptionValue(const OpenFileInfo &file, const std::string &key) {
	if (!file.extended_info) {
		return nullptr;
	}
	auto it = file.extended_info->options.find(key);
	return it == file.extended_info->options.end() ? nullptr : &it->second;
}

bool OptionTrue(const OpenFileInfo &file, const std::string &key) {
	auto v = OptionValue(file, key);
	return v && !v->IsNull() && v->GetValue<bool>();
}

std::string BlobBytes(const Value &v) {
	if (v.IsNull()) {
		return "";
	}
	if (v.type().id() == LogicalTypeId::BLOB) {
		auto &s = StringValue::Get(v);
		return std::string(s.data(), s.size());
	}
	return v.ToString();
}

//! The target's own DuckLake scan, bound from its resolved catalog entry (GetScanFunction with the
//! lookup it was resolved with, AT clause included) and driven with the virtual columns filename and
//! file_row_number (and rowid when asked), in that order, each chunk handed to `sink`. `only_file`,
//! when set, is pushed as a filter on `filename`: DuckLake's reader still opens every file's footer and
//! delete file, but returns no row of another file (P6).
void ScanTarget(ClientContext &context, TableCatalogEntry &table, optional_ptr<BoundAtClause> at_clause,
                const std::string *only_file, bool with_rowid, const std::function<void(DataChunk &)> &sink) {
	QualifiedName qualified(Identifier(table.ParentCatalog().GetName()), Identifier(table.ParentSchema().name),
	                        Identifier(table.name));
	EntryLookupInfo lookup(CatalogType::TABLE_ENTRY, qualified, at_clause, QueryErrorContext());
	unique_ptr<FunctionData> bind_data;
	auto function = table.GetScanFunction(context, bind_data, lookup);
	if (!function.get_virtual_columns) {
		Unavailable("the target's scan offers no virtual columns");
	}
	std::vector<std::string> wanted = {"filename", "file_row_number"};
	if (with_rowid) {
		wanted.push_back("rowid");
	}
	// (rowid's column id is COLUMN_IDENTIFIER_ROW_ID, which reads as INVALID_INDEX: found is kept apart)
	vector<column_t> column_ids(wanted.size(), 0);
	vector<LogicalType> types(wanted.size());
	std::vector<bool> found(wanted.size(), false);
	for (auto &entry : function.get_virtual_columns(context, bind_data.get())) {
		auto at = std::find(wanted.begin(), wanted.end(), Str(entry.second.name)) - wanted.begin();
		if (at < static_cast<long>(wanted.size())) {
			column_ids[at] = entry.first;
			types[at] = entry.second.type;
			found[at] = true;
		}
	}
	for (bool f : found) {
		if (!f) {
			Unavailable("the target's scan does not offer filename, file_row_number and rowid");
		}
	}
	TableFilterSet filters;
	if (only_file) {
		filters.PushFilter(ProjectionIndex(0),
		                   ExpressionFilter::CreateComparisonFilter(ExpressionType::COMPARE_EQUAL, Value(*only_file)));
	}
	DriveChunks(context, function, bind_data.get(), types, column_ids,
	            only_file ? optional_ptr<TableFilterSet>(&filters) : nullptr, sink);
}

void SetRange(std::vector<uint64_t> &words, int64_t lo, int64_t hi) { // [lo, hi)
	auto need = static_cast<size_t>((hi + 63) >> 6);
	if (words.size() < need) {
		words.resize(std::max(need, words.size() * 2), 0);
	}
	while (lo < hi && (lo & 63)) {
		words[lo >> 6] |= 1ull << (lo & 63);
		lo++;
	}
	while (hi - lo >= 64) {
		words[lo >> 6] = ~0ull;
		lo += 64;
	}
	while (lo < hi) {
		words[lo >> 6] |= 1ull << (lo & 63);
		lo++;
	}
}

//! The re-throw of ResolveSnapshotView's and ReadDeletePositions' failures: their own refusals as they
//! are, an interrupt as it is, anything else (a missing file, a binder error of a driven call) as
//! search_snapshot_unavailable with its first line.
[[noreturn]] void Rethrow(std::exception &e) {
	ErrorData data(e);
	auto &message = data.RawMessage();
	if (data.Type() == ExceptionType::INTERRUPT ||
	    (data.Type() == ExceptionType::INVALID_INPUT && (message.rfind("search_snapshot_unavailable: ", 0) == 0 ||
	                                                     message.rfind("search_uncommitted_writes: ", 0) == 0))) {
		throw;
	}
	Unavailable(data.Message().substr(0, data.Message().find('\n')));
}

} // namespace

std::vector<std::pair<int64_t, int64_t>> HiddenPositions(const FileLiveness &live, int64_t record_count) {
	std::vector<std::pair<int64_t, int64_t>> runs;
	int64_t start = -1;
	for (int64_t p = 0; p < record_count; p++) {
		bool hidden = !live.IsLive(p);
		if (hidden && start < 0) {
			start = p;
		} else if (!hidden && start >= 0) {
			runs.emplace_back(start, p);
			start = -1;
		}
	}
	if (start >= 0) {
		runs.emplace_back(start, record_count);
	}
	return runs;
}

int64_t FileIndexForScanName(const SnapshotView &view, const std::string &filename) {
	for (size_t i = 0; i < view.files.size(); i++) {
		if (view.files[i].path == filename) {
			return static_cast<int64_t>(i);
		}
	}
	for (auto &t : view.inlined_tables) {
		if (t.table_name == filename) {
			return -1;
		}
	}
	Unavailable("the target scan returned a file the listing at S does not name");
}

std::vector<TargetRow> ScanTargetRows(ClientContext &context, LogicalGet &target, const std::string *only_file) {
	auto table = target.GetTable();
	if (!table || table->ParentCatalog().GetCatalogType() != "ducklake") {
		Unavailable("the target is not a DuckLake table");
	}
	std::vector<TargetRow> out;
	ScanTarget(context, *table, target.at_clause.get(), only_file, true, [&](DataChunk &chunk) {
		UnifiedVectorFormat names, positions, rowids;
		chunk.data[0].ToUnifiedFormat(names);
		chunk.data[1].ToUnifiedFormat(positions);
		chunk.data[2].ToUnifiedFormat(rowids);
		auto name_data = names.GetData<string_t>();
		auto pos_data = positions.GetData<int64_t>();
		auto rowid_data = rowids.GetData<int64_t>();
		for (idx_t i = 0; i < chunk.size(); i++) {
			auto n = names.sel->get_index(i), p = positions.sel->get_index(i), r = rowids.sel->get_index(i);
			if (!names.validity.RowIsValid(n) || !positions.validity.RowIsValid(p)) {
				Unavailable("the target scan returned a row with no file or position");
			}
			out.push_back(TargetRow {name_data[n].GetString(), pos_data[p],
			                         rowids.validity.RowIsValid(r) ? rowid_data[r] : int64_t(-1)});
		}
	});
	return out;
}

std::vector<std::pair<int64_t, int64_t>> ReadDeletePositions(ClientContext &context, const SnapshotView &view,
                                                             const DeleteFileRef &file, std::vector<std::string> *reads) {
	try {
		bool listed = false;
		for (auto &d : view.delete_files) {
			if (d.path == file.path && d.data_file_path == file.data_file_path) {
				listed = true;
				break;
			}
		}
		if (!listed) {
			Unavailable("a delete file the listing at S did not name");
		}
		if (!file.encryption_key.empty()) {
			// parquet_scan takes a key only by a name registered for the whole database; DuckLake hands its
			// own scan the key per file, which no ruled call does (P6).
			Unavailable("an encrypted delete file (parquet_scan takes no per-file key)");
		}
		if (reads) {
			reads->push_back("parquet_scan(" + Quote(file.path) + ")");
		}
		auto call = BindSystemTableFunction(context, "parquet_scan", {Value(file.path)}, {});
		int pos = -1, snap = -1;
		for (size_t i = 0; i < call.names.size(); i++) {
			if (call.names[i] == "pos") {
				pos = static_cast<int>(i);
			} else if (call.names[i] == "_ducklake_internal_snapshot_id") {
				snap = static_cast<int>(i);
			}
		}
		if (pos < 0) {
			Unavailable("parquet_scan of a delete file returned no column pos");
		}
		vector<column_t> ids = {static_cast<column_t>(pos)};
		vector<LogicalType> types = {LogicalType::BIGINT};
		if (snap >= 0) {
			ids.push_back(static_cast<column_t>(snap));
			types.push_back(LogicalType::BIGINT);
		}
		if (call.types[pos].id() != LogicalTypeId::BIGINT || (snap >= 0 && call.types[snap].id() != LogicalTypeId::BIGINT)) {
			Unavailable("a delete file's pos or snapshot column is not BIGINT");
		}
		std::vector<std::pair<int64_t, int64_t>> out;
		DriveChunks(context, *call.function, call.bind_data.get(), types, ids, nullptr, [&](DataChunk &chunk) {
			UnifiedVectorFormat p, sn;
			chunk.data[0].ToUnifiedFormat(p);
			auto pd = p.GetData<int64_t>();
			const int64_t *sd = nullptr;
			if (snap >= 0) {
				chunk.data[1].ToUnifiedFormat(sn);
				sd = sn.GetData<int64_t>();
			}
			for (idx_t i = 0; i < chunk.size(); i++) {
				auto pi = p.sel->get_index(i);
				int64_t s = -1;
				if (sd) {
					auto si = sn.sel->get_index(i);
					s = sn.validity.RowIsValid(si) ? sd[si] : -1;
				}
				out.emplace_back(p.validity.RowIsValid(pi) ? pd[pi] : -1, s);
			}
		});
		return out;
	} catch (std::exception &e) {
		Rethrow(e);
	}
}

SnapshotView ResolveSnapshotView(ClientContext &context, LogicalGet &target) {
	try {
		auto table = target.GetTable();
		if (!table) {
			Unavailable("the target is not a table scan");
		}
		auto &catalog = table->ParentCatalog();
		if (catalog.GetCatalogType() != "ducklake") {
			Unavailable("the target is not a DuckLake table");
		}
		// OWNER RULING 2026-11-14: no search in a transaction that modified the target's database (its
		// own inserts and deletes alike: the scan would apply some of them, the listing none).
		auto modified = MetaTransaction::Get(context).ModifiedDatabase();
		if (modified && modified.get() == &catalog.GetAttached()) {
			throw InvalidInputException("search_uncommitted_writes: the transaction has written to " +
			                            Str(catalog.GetName()) + "; search reads committed data only, so "
			                            "commit or roll back first");
		}
		auto multi = dynamic_cast<MultiFileBindData *>(target.bind_data.get());
		if (!multi || !multi->file_list) {
			Unavailable("the target scan holds no file list");
		}
		SnapshotView view;
		view.catalog_alias = Str(catalog.GetName());
		view.schema_name = Str(table->ParentSchema().name);
		view.table_name = Str(table->name);
		view.table_oid = table->oid;
		auto &reads = view.reads;

		// S: the transaction's snapshot (fixed at its first lake access, which the target's own bind may
		// have been), or the version an AT clause names.
		if (target.at_clause) {
			auto unit = StringUtil::Lower(Str(target.at_clause->Unit()));
			if (unit == "version") {
				view.snapshot_id = target.at_clause->GetValue().DefaultCastAs(LogicalType::BIGINT).GetValue<int64_t>();
			} else {
				// OWNER RULING 2026-11-14: refused for Phase 0.
				Unavailable("AT (" + StringUtil::Upper(unit) +
				            " => ...) is not supported by search in Phase 0; use AT (VERSION => n)");
			}
		} else {
			reads.push_back("ducklake_current_snapshot(" + Quote(view.catalog_alias) + ")");
			auto rows = CallSystemTableFunction(context, "ducklake_current_snapshot", {Value(view.catalog_alias)}, {});
			if (rows.rows.size() != 1) {
				Unavailable("ducklake_current_snapshot returned no row");
			}
			view.snapshot_id = AsInt(rows.rows[0][0]);
		}

		// The target scan's own file list (built by the client's statement, in its transaction).
		struct Listed {
			const OpenFileInfo *info;
			bool matched = false;
		};
		auto all = multi->file_list->GetAllFiles();
		std::map<std::string, Listed> data_by_path;
		for (auto &file : all) {
			if (OptionTrue(file, "transaction_local_data")) {
				throw InvalidInputException("search_uncommitted_writes: the transaction holds uncommitted rows of " +
				                            view.table_name);
			}
			if (OptionTrue(file, "inlined_data")) {
				bool found = false;
				auto name = Option(file, "table_name", found);
				if (!found || name.empty()) {
					Unavailable("an inlined entry of the file list has no table name");
				}
				InlinedTable t;
				t.table_name = name;
				auto sv = OptionValue(file, "schema_version");
				t.schema_version = sv ? AsInt(*sv) : -1;
				view.inlined_tables.push_back(t);
				// ducklake_inlined_data_<table_id>_<schema_version>
				auto last = name.rfind('_');
				auto prev = last == std::string::npos ? std::string::npos : name.rfind('_', last - 1);
				if (prev != std::string::npos) {
					try {
						view.table_id = std::stoll(name.substr(prev + 1, last - prev - 1));
					} catch (std::exception &) {
					}
				}
				continue;
			}
			auto snapshot = OptionValue(file, "snapshot_id");
			if (!snapshot || snapshot->IsNull()) {
				// A committed data file always carries its snapshot; the transaction's own files do not.
				throw InvalidInputException("search_uncommitted_writes: the transaction holds uncommitted files of " +
				                            view.table_name);
			}
			if (data_by_path.count(file.path)) {
				Unavailable("the target scan's file list names a data file twice");
			}
			data_by_path[file.path] = Listed {&file};
		}

		// ducklake_list_files at S: the data files and their delete files, by path.
		named_argument_map_t named;
		named[Identifier("schema")] = Value(view.schema_name);
		named[Identifier("snapshot_version")] = Value::BIGINT(view.snapshot_id);
		reads.push_back("ducklake_list_files(" + Quote(view.catalog_alias) + ", " + Quote(view.table_name) +
		                ", schema => " + Quote(view.schema_name) + ", snapshot_version => " +
		                std::to_string(view.snapshot_id) + ")");
		auto listing = CallSystemTableFunction(context, "ducklake_list_files",
		                                       {Value(view.catalog_alias), Value(view.table_name)}, std::move(named));
		auto c_df = listing.Need("data_file", "ducklake_list_files");
		auto c_dfs = listing.Need("data_file_size_bytes", "ducklake_list_files");
		auto c_dff = listing.Need("data_file_footer_size", "ducklake_list_files");
		auto c_dfk = listing.Need("data_file_encryption_key", "ducklake_list_files");
		auto c_del = listing.Need("delete_file", "ducklake_list_files");
		auto c_dels = listing.Need("delete_file_size_bytes", "ducklake_list_files");
		auto c_delf = listing.Need("delete_file_footer_size", "ducklake_list_files");
		auto c_delk = listing.Need("delete_file_encryption_key", "ducklake_list_files");
		for (auto &row : listing.rows) {
			auto path = row[c_df].ToString();
			auto it = data_by_path.find(path);
			if (it == data_by_path.end()) {
				// The listing and the target's own scan read one catalog at one snapshot in one transaction,
				// so they agree; when they do not, nothing here guesses (P6).
				Unavailable("a data file the listing at S names is not in the target scan's file list");
			}
			if (it->second.matched) {
				Unavailable("the listing at S names a data file twice");
			}
			it->second.matched = true;
			auto &info = *it->second.info;
			DataFileRef ref;
			ref.path = path;
			ref.bundle_key = qod::Hex(qod::Sha256(path));
			auto rs = OptionValue(info, "row_id_start");
			ref.row_id_start = rs ? AsInt(*rs) : -1;
			ref.begin_snapshot = AsInt(*OptionValue(info, "snapshot_id"));
			auto fs = OptionValue(info, "file_size");
			ref.file_size = fs && !fs->IsNull() ? fs->GetValue<uint64_t>() : 0;
			auto ff = OptionValue(info, "footer_size");
			ref.footer_size = ff && !ff->IsNull() ? ff->GetValue<uint64_t>() : 0;
			ref.has_deletes = OptionTrue(info, "has_deletes");
			auto key = OptionValue(info, "encryption_key");
			ref.encryption_key = key ? BlobBytes(*key) : "";
			if (static_cast<uint64_t>(AsInt(row[c_dfs])) != ref.file_size ||
			    static_cast<uint64_t>(AsInt(row[c_dff])) != ref.footer_size ||
			    BlobBytes(row[c_dfk]) != ref.encryption_key) {
				Unavailable("the listing at S and the target scan disagree on a data file's size, footer or key");
			}
			if (!row[c_del].IsNull()) {
				DeleteFileRef del;
				del.file_index = view.files.size();
				del.data_file_path = path;
				del.path = row[c_del].ToString();
				del.file_size = static_cast<uint64_t>(AsInt(row[c_dels]));
				del.footer_size = row[c_delf].IsNull() ? 0 : static_cast<uint64_t>(AsInt(row[c_delf]));
				del.encryption_key = BlobBytes(row[c_delk]);
				view.delete_files.push_back(del);
			}
			view.files.push_back(std::move(ref));
		}
		for (auto &entry : data_by_path) {
			if (!entry.second.matched) {
				Unavailable("a data file of the target scan's file list is not in the listing at S");
			}
		}

		// Liveness: the positions the target's own scan returns at S, per file, marked by runs into one
		// bitset per file (keyed by its index in `files`). DuckLake applies every deletion source inside
		// its scan, so this is 6.3's liveness whatever the representation. A file name the listing does
		// not name fails closed.
		view.liveness.resize(view.files.size());
		reads.push_back("ducklake_scan(" + Quote(view.catalog_alias) + "." + Quote(view.schema_name) + "." +
		                Quote(view.table_name) +
		                (target.at_clause ? ", AT VERSION " + std::to_string(view.snapshot_id) : "") +
		                ", columns filename, file_row_number)");
		std::unordered_map<std::string, int64_t> index_of;
		for (size_t i = 0; i < view.files.size(); i++) {
			index_of[view.files[i].path] = static_cast<int64_t>(i);
		}
		std::unordered_map<std::string, size_t> inlined_of;
		for (size_t i = 0; i < view.inlined_tables.size(); i++) {
			inlined_of[view.inlined_tables[i].table_name] = i;
		}
		std::string last_name;
		bool have_last = false;
		int64_t last_file = -1;
		size_t last_inlined = 0;
		auto scan_start = std::chrono::steady_clock::now();
		ScanTarget(context, *table, target.at_clause.get(), nullptr, false, [&](DataChunk &chunk) {
			UnifiedVectorFormat names, positions;
			chunk.data[0].ToUnifiedFormat(names);
			chunk.data[1].ToUnifiedFormat(positions);
			auto name_data = names.GetData<string_t>();
			auto pos_data = positions.GetData<int64_t>();
			idx_t i = 0;
			while (i < chunk.size()) {
				auto n = names.sel->get_index(i);
				if (!names.validity.RowIsValid(n)) {
					Unavailable("the target scan returned a row with no file");
				}
				auto &name = name_data[n];
				if (!have_last || name.GetSize() != last_name.size() ||
				    memcmp(name.GetData(), last_name.data(), last_name.size()) != 0) {
					last_name = name.GetString();
					have_last = true;
					auto f = index_of.find(last_name);
					if (f != index_of.end()) {
						last_file = f->second;
					} else {
						auto t = inlined_of.find(last_name);
						if (t == inlined_of.end()) {
							Unavailable("the target scan returned a file the listing at S does not name");
						}
						last_file = -1;
						last_inlined = t->second;
					}
				}
				// the run of rows of this chunk with the same file
				idx_t j = i + 1;
				while (j < chunk.size()) {
					auto nj = names.sel->get_index(j);
					if (nj != n && !(names.validity.RowIsValid(nj) && name_data[nj] == name)) {
						break;
					}
					j++;
				}
				if (last_file < 0) {
					view.inlined_tables[last_inlined].live_rows += static_cast<int64_t>(j - i);
				} else {
					auto &live = view.liveness[static_cast<size_t>(last_file)];
					idx_t k = i;
					while (k < j) {
						// a run of consecutive positions
						auto pk = positions.sel->get_index(k);
						if (!positions.validity.RowIsValid(pk) || pos_data[pk] < 0) {
							Unavailable("the target scan returned a row with no position");
						}
						int64_t first = pos_data[pk];
						idx_t m = k + 1;
						while (m < j) {
							auto pm = positions.sel->get_index(m);
							if (!positions.validity.RowIsValid(pm) ||
							    pos_data[pm] != first + static_cast<int64_t>(m - k)) {
								break;
							}
							m++;
						}
						auto stop = first + static_cast<int64_t>(m - k);
						SetRange(live.words, first, stop);
						live.live_count += static_cast<int64_t>(m - k);
						live.end = std::max(live.end, stop);
						k = m;
					}
				}
				i = j;
			}
		});
		view.target_scan_ms =
		    std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - scan_start).count();
		for (auto &live : view.liveness) {
			live.words.resize(static_cast<size_t>((live.end + 63) >> 6));
			int64_t bits = 0;
			for (auto w : live.words) {
				bits += static_cast<int64_t>(std::bitset<64>(w).count());
			}
			if (bits != live.live_count) {
				Unavailable("the target scan returned a position twice");
			}
		}
		return view;
	} catch (std::exception &e) {
		Rethrow(e);
	}
}

} // namespace qod::search
