#include "screen_probe.hpp"

#include "duckdb/common/exception.hpp"
#include "duckdb/function/scalar_function.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <iostream>
#include <mutex>

using namespace duckdb;

namespace qod {
namespace {

constexpr const char *RefuseMarker = "qod_spike_refuse";

std::mutex output_lock;

void Screen(DataChunk &args, ExpressionState &state, Vector &result) {
	for (idx_t row = 0; row < args.size(); row++) {
		auto connection_id = args.GetValue(0, row).ToString();
		auto sql = args.GetValue(1, row).ToString();
		{
			std::lock_guard<std::mutex> guard(output_lock);
			std::cerr << "[screen] connection=" << connection_id << " sql=" << sql.substr(0, 120) << std::endl;
		}
		if (sql.find(RefuseMarker) != string::npos) {
			throw PermissionException("qod spike: refused by the parse screen");
		}
		result.SetValue(row, Value::BOOLEAN(true));
	}
}

} // namespace

void RegisterScreenProbe(DatabaseInstance &db) {
	ExtensionLoader loader(db, "qod_spike");
	loader.RegisterFunction(
	    ScalarFunction("qod_screen", {LogicalType::ANY, LogicalType::VARCHAR}, LogicalType::BOOLEAN, Screen));
}

} // namespace qod
