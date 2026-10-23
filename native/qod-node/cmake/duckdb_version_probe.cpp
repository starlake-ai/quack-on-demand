// Configure-time probe (CMakeLists.txt try_run): prints the version and source
// id of the libduckdb qod-node is about to link, so the build marker names the
// library the binary was really built against rather than what a caller claims.
#include "duckdb/main/database.hpp"

#include <cstdio>

int main() {
  std::printf("%s;%s\n", duckdb::DuckDB::LibraryVersion(), duckdb::DuckDB::SourceID());
  return 0;
}
