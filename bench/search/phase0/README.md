# Search Phase 0 probes

Exploration code for the Phase 0 questions of the search spec. Nothing here ships.
Run the DuckDB CLI as `env -u AI_AGENT <duckdb> -no-agent` and keep scratch data outside the repository.

| Probe | Question | Run |
|---|---|---|
| q1_visibility.sql | Q1, Q6 | `cd <scratch>/q1 && duckdb -no-agent < q1_visibility.sql` |
| q3_plans.sql | Q3 (plan shapes) | `cd <scratch>/q3 && duckdb -no-agent < q3_plans.sql` |
| native/qod-node/tests/search_probe_test.cpp | Q3 (execution), Q11 | `ctest --test-dir native/qod-node/build -R search_probe -V` |
| native/qod-search + search_link_test.cpp | Q2 | `cmake ... -DQOD_NODE_SEARCH=ON` then `ctest -R search_link` |
| tantivy-probe | Q5, Q8, Q10 | `cargo test --release`, `cargo run --release --example allowed_stats` |
| Phase0RewriterProbeSpec | Q4, Q14 | `sbt "testOnly *Phase0RewriterProbeSpec"` |
| q12_floor.py + q12_setup.sql | Q12 | see the script header |
| q13_fetch.sql | Q13 | `cd <scratch>/q13 && duckdb -no-agent < q13_fetch.sql` |
