# QoD Search Phase 0, part 2 harness

Runs the prototype and ParadeDB 0.26.6 on one host, by the method of the search spec, section 11.
Nothing here ships. Every stack is isolated (ports 27338 to 27901, scratch data); see the plan
docs/superpowers/plans/2026-11-14-search-phase0-prototype.md for the order of the steps.

| Step | Run |
|---|---|
| Corpus | `bench/search/corpus/gen_pages.sql` through the DuckDB CLI (Task 3) |
| QoD stack | `bench/search/stack/qod_up.sh`, `qod_down.sh` |
| ParadeDB stack | `bench/search/stack/paradedb_up.sh`, `paradedb_down.sh` |
| Loads | `qod_load.sql`, `paradedb_load.py`, `build_segments.py` |
| Cells | `run_paradedb.py`, `run_qod.py`, `report.py` (definitions in `cells.py`) |
| Drain | `drain.py`, `native/qod-node/tests/search_drain_bench.cpp` |

ParadeDB is used only as its public Docker image, configured from its public docs.
