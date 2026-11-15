-- Reloads pages_1m with rowid = id (fix round 1 of Task 3). Same statements as qod_load.sql,
-- after a DROP; grants and row policies name the table and survive the drop.
DROP TABLE acme_lake.main.pages_1m;
CREATE TABLE acme_lake.main.pages_1m (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, f100 INTEGER);
CALL acme_lake.set_option('parquet_row_group_size', 16384, table_name => 'pages_1m');
SET threads = 1;
INSERT INTO acme_lake.main.pages_1m SELECT * FROM read_parquet('@P2@/corpus/pages_1m.parquet') ORDER BY id;
SET threads = 16;
