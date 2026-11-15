-- Loads the part 2 tables into the QoD stack (superuser, through qod_load.sh: one line, one
-- statement, one qod sql call). @P2@ is replaced by the scratch path with sed. Row group
-- sizes: section 7.7's lever, 16,384 rows at 1M and 32,768 at 10M (recorded in
-- docs/search-PHASE0.md P3).
-- rowid = id: DuckLake's parallel insert writes row groups in completion order, whatever the
-- ORDER BY, so pages_1m and pages_10m are inserted under SET threads = 1 (global on the node,
-- so it holds across calls; qod_load.sh puts it back to 16 on every exit). pages_100m_drain
-- keeps the parallel insert: the drain does not need rowid = id.
CREATE TABLE acme_lake.main.pages_1m (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, f100 INTEGER);
CALL acme_lake.set_option('parquet_row_group_size', 16384, table_name => 'pages_1m');
SET threads = 1;
INSERT INTO acme_lake.main.pages_1m SELECT * FROM read_parquet('@P2@/corpus/pages_1m.parquet') ORDER BY id;
SET threads = 16;
CREATE TABLE acme_lake.main.pages_10m (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, f100 INTEGER);
CALL acme_lake.set_option('parquet_row_group_size', 32768, table_name => 'pages_10m');
SET threads = 1;
INSERT INTO acme_lake.main.pages_10m SELECT * FROM read_parquet('@P2@/corpus/pages_10m.parquet') ORDER BY id;
SET threads = 16;
CREATE TABLE acme_lake.main.pages_100m_drain AS SELECT * FROM read_parquet('@P2@/corpus/pages_100m_drain.parquet') ORDER BY id;
