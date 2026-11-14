// qod_search_core C ABI (Phase 0 link probe). Hand-written for the probe; cbindgen generates it in part 2.
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// Builds a RAM index of `docs` synthetic documents and returns the hits of one query (docs / 3,
// rounded up), or UINT64_MAX on an error.
uint64_t qod_search_probe(uint64_t docs);
#ifdef __cplusplus
}
#endif
