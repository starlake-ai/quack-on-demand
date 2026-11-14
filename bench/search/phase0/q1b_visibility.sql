-- Q1b: delete-file snapshot ids, positions, inlined delete row_id meaning, and a real merge. DuckDB v2.0.0.
-- One statement per line.
LOAD ducklake;
ATTACH 'ducklake:q1b.ducklake' AS lake (DATA_PATH 'q1bdata/');
CREATE TABLE lake.t(id INTEGER, body VARCHAR);
INSERT INTO lake.t SELECT i, 'doc ' || i FROM range(5000) r(i);
INSERT INTO lake.t VALUES (5000, 'small a'), (5001, 'small b');
UPDATE lake.t SET body = 'updated' WHERE id = 2;
DELETE FROM lake.t WHERE id = 3;
UPDATE lake.t SET body = body || ' v2' WHERE id < 2000;
UPDATE lake.t SET body = 'small a2' WHERE id = 5000;
CALL ducklake_flush_inlined_data('lake');
.print == catalog files after flush (same sequence as q1)
SELECT 'data' k, data_file_id id, begin_snapshot, end_snapshot, row_id_start, record_count, partial_max, path FROM __ducklake_metadata_lake.ducklake_data_file ORDER BY 2;
SELECT 'delete' k, delete_file_id id, begin_snapshot, end_snapshot, data_file_id, delete_count, partial_max, path FROM __ducklake_metadata_lake.ducklake_delete_file ORDER BY 2;
.print == delete files on disk: per-row snapshot id and count (all files incl. orphans)
SELECT regexp_extract(filename, '[^/]*$') f, _ducklake_internal_snapshot_id AS snap, count(*) n, min(pos) lo, max(pos) hi FROM read_parquet('q1bdata/**/*-delete.parquet', filename = true, union_by_name = true) GROUP BY ALL ORDER BY f, snap;
.print == delete file columns
SELECT DISTINCT name FROM parquet_schema('q1bdata/**/*-delete.parquet') ORDER BY 1;
.print == data file 3 (row_id_start): rowid range and order
SELECT min(_ducklake_internal_row_id) lo, max(_ducklake_internal_row_id) hi, count(*) n, count(DISTINCT _ducklake_internal_row_id) d, bool_and(_ducklake_internal_row_id = lag_r + 1) AS sorted_dense FROM (SELECT _ducklake_internal_row_id, lag(_ducklake_internal_row_id) OVER (ORDER BY file_row_number) lag_r FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE filename NOT LIKE '%-delete.parquet' AND _ducklake_internal_row_id IS NOT NULL AND filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE data_file_id = 3));
SELECT file_row_number AS pos, _ducklake_internal_row_id AS r FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE data_file_id = 3) AND file_row_number < 6;
.print == data file 3: where is rowid 2, and how many adjacent pairs descend
SELECT file_row_number AS pos_of_rowid_2 FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE _ducklake_internal_row_id = 2 AND filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE data_file_id = 3);
SELECT count(*) FILTER (WHERE r < prev) AS descending_pairs FROM (SELECT _ducklake_internal_row_id r, lag(_ducklake_internal_row_id) OVER (ORDER BY file_row_number) prev FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE data_file_id = 3));
.print == flushed data file 6: position of each row, rowid, snapshot id
SELECT file_row_number AS pos, _ducklake_internal_row_id AS r, _ducklake_internal_snapshot_id AS snap, id, body FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE data_file_id = 6) ORDER BY pos;
.print == delete file of data file 6: positions deleted
SELECT pos, _ducklake_internal_snapshot_id AS snap FROM read_parquet('q1bdata/**/*-delete.parquet', filename = true, union_by_name = true) WHERE filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_delete_file WHERE data_file_id = 6) ORDER BY pos;
.print == time travel after flush: n, distinct rowid per snapshot
SELECT 1 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 1);
SELECT 2 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 2);
SELECT 3 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 3);
SELECT 4 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 4);
SELECT 5 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 5);
SELECT 6 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 6);
SELECT 7 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 7);
SELECT 8 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.t AT (VERSION => 8);
.print == does data file 0 delete at snapshot 3 (before the one-row delete at 5 and before 4)? rows of file 0 hidden at v4, v5, v6
SELECT 4 v, count(*) FROM lake.t AT (VERSION => 4) WHERE id IN (2,3);
SELECT 5 v, count(*) FROM lake.t AT (VERSION => 5) WHERE id IN (2,3);
SELECT 6 v, count(*) FROM lake.t AT (VERSION => 6) WHERE id IN (2,3);
.print == Q: is ducklake_inlined_delete.row_id a position or a rowid? file with row_id_start <> 0
CREATE TABLE lake.u(id INTEGER, body VARCHAR);
INSERT INTO lake.u VALUES (0, 'a'), (1, 'b'), (2, 'c');
INSERT INTO lake.u SELECT i, 'doc ' || i FROM range(100, 5100) r(i);
DELETE FROM lake.u WHERE id = 4000;
SELECT data_file_id, table_id, begin_snapshot, row_id_start, record_count FROM __ducklake_metadata_lake.ducklake_data_file WHERE table_id = 2 ORDER BY 1;
SELECT rowid AS rowid_of_id_4000 FROM lake.u AT (VERSION => 11) WHERE id = 4000;
SELECT * FROM query_table('__ducklake_metadata_lake.ducklake_inlined_delete_2');
SELECT file_row_number AS pos_of_id_4000, _ducklake_internal_row_id AS internal FROM read_parquet('q1bdata/**/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE id = 4000 AND filename LIKE (SELECT '%' || path FROM __ducklake_metadata_lake.ducklake_data_file WHERE table_id = 2 AND row_id_start <> 0 ORDER BY data_file_id LIMIT 1);
SELECT count(*) AS n_now, count(DISTINCT rowid) d_now FROM lake.u;
SELECT count(*) AS visible_4000 FROM lake.u WHERE id = 4000;
.print == merge: many small files with inlining off
CALL lake.set_option('data_inlining_row_limit', 0);
CREATE TABLE lake.m(id INTEGER, body VARCHAR);
INSERT INTO lake.m VALUES (1, 'a');
INSERT INTO lake.m VALUES (2, 'b');
INSERT INTO lake.m VALUES (3, 'c');
INSERT INTO lake.m VALUES (4, 'd');
UPDATE lake.m SET body = 'b2' WHERE id = 2;
DELETE FROM lake.m WHERE id = 3;
FROM lake.snapshots() WHERE snapshot_id > 13;
SELECT data_file_id, begin_snapshot, end_snapshot, row_id_start, record_count, partial_max, path FROM __ducklake_metadata_lake.ducklake_data_file WHERE table_id = 3 ORDER BY 1;
SELECT delete_file_id, begin_snapshot, data_file_id, delete_count, partial_max FROM __ducklake_metadata_lake.ducklake_delete_file WHERE table_id = 3 ORDER BY 1;
.print == m before merge, per snapshot
SELECT 15 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 15);
SELECT 17 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 17);
SELECT 19 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 19);
SELECT 14 AS v, 'before' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 14);
SELECT 16 AS v, 'before' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 16);
SELECT 18 AS v, 'before' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 18);
CALL ducklake_merge_adjacent_files('lake');
.print == after merge
FROM lake.snapshots() WHERE snapshot_id > 20;
SELECT data_file_id, begin_snapshot, end_snapshot, row_id_start, record_count, partial_max, path FROM __ducklake_metadata_lake.ducklake_data_file WHERE table_id = 3 ORDER BY 1;
SELECT delete_file_id, begin_snapshot, end_snapshot, data_file_id, delete_count, partial_max FROM __ducklake_metadata_lake.ducklake_delete_file WHERE table_id = 3 ORDER BY 1;
SELECT regexp_extract(filename, '[^/]*$') f, file_row_number pos, _ducklake_internal_row_id AS r, _ducklake_internal_snapshot_id AS snap, id, body FROM read_parquet('q1bdata/main/m/*.parquet', file_row_number = true, filename = true, union_by_name = true) WHERE filename NOT LIKE '%-delete.parquet' ORDER BY f, pos;
SELECT 15 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 15);
SELECT 17 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 17);
SELECT 19 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 19);
SELECT 20 AS v, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 20);
SELECT rowid, id, body FROM lake.m AT (VERSION => 20) ORDER BY id;
SELECT count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m;
SELECT 14 AS v, 'after' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 14);
SELECT 16 AS v, 'after' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 16);
SELECT 18 AS v, 'after' AS phase, count(*) AS n, count(DISTINCT rowid) AS d FROM lake.m AT (VERSION => 18);
