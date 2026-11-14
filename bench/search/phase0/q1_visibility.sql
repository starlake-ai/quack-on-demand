-- Q1, Q6: DuckLake rowids, files, deletes, inlining, flush and merge, on DuckDB v2.0.0.
-- One statement per line: the CLI skips the rest of a line after a failing statement.
LOAD ducklake;
ATTACH 'ducklake:q1.ducklake' AS lake (DATA_PATH 'q1data/');
.print == options
FROM lake.options();
CREATE TABLE lake.t(id INTEGER, body VARCHAR);
.print == snapshot: large insert (expected: one data file)
INSERT INTO lake.t SELECT i, 'doc ' || i FROM range(5000) r(i);
.print == snapshot: small insert (expected: inlined)
INSERT INTO lake.t VALUES (5000, 'small a'), (5001, 'small b');
.print == snapshot: one-row update of a file row
UPDATE lake.t SET body = 'updated' WHERE id = 2;
.print == snapshot: one-row delete of a file row
DELETE FROM lake.t WHERE id = 3;
.print == snapshot: large update of file rows
UPDATE lake.t SET body = body || ' v2' WHERE id < 2000;
.print == snapshot: update of an inlined row
UPDATE lake.t SET body = 'small a2' WHERE id = 5000;
.print == snapshots
FROM lake.snapshots();
.print == data files
SELECT * FROM __ducklake_metadata_lake.ducklake_data_file ORDER BY data_file_id;
.print == delete files
SELECT * FROM __ducklake_metadata_lake.ducklake_delete_file ORDER BY delete_file_id;
.print == inlined tables
SELECT table_name FROM duckdb_tables() WHERE database_name LIKE '%ducklake_metadata%' AND table_name LIKE '%inlined%' ORDER BY 1;
.print == inlined deletes (table id 1)
FROM query_table('__ducklake_metadata_lake.ducklake_inlined_delete_1');
.print == inlined data (table id 1, schema version 1)
FROM query_table('__ducklake_metadata_lake.ducklake_inlined_data_1_1');
.print == physical columns of each data file
SELECT file_name, name, type FROM parquet_schema('q1data/**/*.parquet') ORDER BY file_name, name;
.print == internal rowid column per file (union by name: absent columns read NULL)
SELECT filename, count(*) AS n, count(_ducklake_internal_row_id) AS with_internal_rowid, min(_ducklake_internal_row_id) AS lo, max(_ducklake_internal_row_id) AS hi FROM read_parquet('q1data/**/*.parquet', filename = true, union_by_name = true) GROUP BY filename ORDER BY filename;
.print == per snapshot: rows, distinct rowids (a difference means a rowid live twice)
SELECT 1 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 1);
SELECT 2 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 2);
SELECT 3 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 3);
SELECT 4 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 4);
SELECT 5 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 5);
SELECT 6 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 6);
SELECT 7 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 7);
.print == rowid of the updated file row 2 across snapshots (expected: same rowid, new value)
SELECT 3 AS v, rowid, id, body FROM lake.t AT (VERSION => 3) WHERE id = 2;
SELECT 4 AS v, rowid, id, body FROM lake.t AT (VERSION => 4) WHERE id = 2;
.print == flush inlined data
CALL ducklake_flush_inlined_data('lake');
SELECT * FROM __ducklake_metadata_lake.ducklake_data_file ORDER BY data_file_id;
SELECT * FROM __ducklake_metadata_lake.ducklake_delete_file ORDER BY delete_file_id;
FROM query_table('__ducklake_metadata_lake.ducklake_inlined_delete_1');
.print == after flush: per snapshot again (time travel must give the same answers)
SELECT 4 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 4);
SELECT 7 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 7);
.print == merge adjacent files
CALL ducklake_merge_adjacent_files('lake');
SELECT data_file_id, path, begin_snapshot, end_snapshot, row_id_start, record_count FROM __ducklake_metadata_lake.ducklake_data_file ORDER BY data_file_id;
SELECT filename, count(*) AS n, count(_ducklake_internal_row_id) AS with_internal_rowid FROM read_parquet('q1data/**/*.parquet', filename = true, union_by_name = true) GROUP BY filename ORDER BY filename;
.print == current: rows, distinct rowids
SELECT count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t;
