-- Reloads pages_10m with rowid = id (fix round 1 of Task 3). Same statements as qod_load.sql,
-- after a DROP; grants and row policies name the table and survive the drop.
DROP TABLE acme_lake.main.pages_10m;
CREATE TABLE acme_lake.main.pages_10m (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, f100 INTEGER);
CALL acme_lake.set_option('parquet_row_group_size', 32768, table_name => 'pages_10m');
SET threads = 1;
INSERT INTO acme_lake.main.pages_10m SELECT * FROM read_parquet('@P2@/corpus/pages_10m.parquet') ORDER BY id;
SET threads = 16;
