-- Q13: fetch of the returned rows (join-back, spec 7.7) from a DuckLake table.
-- Generated: 10 and 100 rowids, by JOIN and by IN, title (32 B) and body (640 B), clustered rowids,
-- default and small row groups, small files, many files, a merged file. Each case runs 16 times with a
-- different random rowid set (k = 0 is the first run, k = 1..15 the warm ones the report takes p50 and
-- p95 from; IN cases use their own sets k + 16, so no shape reads rows another case just cached; p95 of 15 samples is the 14th value). Run from an empty directory with the DuckDB 2.0.0 CLI. For an S3-compatible store, replace the
-- lines between ATTACH-BEGIN and ATTACH-END by a CREATE SECRET scoped to the bucket and an ATTACH with
-- DATA_PATH 's3://<bucket>/...' (keep the keys out of this file). Timer resolution is 1 ms.
-- Size: change the one SET VARIABLE below (1000000, 10000000, 100000000).
LOAD ducklake;
-- as a node runs: no file or metadata cache between statements
SET enable_external_file_cache = false;
SET enable_http_metadata_cache = false;
SET VARIABLE n = 10000000;
-- ATTACH-BEGIN
ATTACH 'ducklake:q13.ducklake' AS lake (DATA_PATH 'q13data/');
-- ATTACH-END
.mode trash

.print == setup
CREATE TEMP TABLE hits AS
  SELECT sz, k, i, (hash(sz, k, i) % getvariable('n'))::BIGINT AS rowid
  FROM (VALUES (10), (100)) s(sz), range(32) kk(k), range(100) ii(i) WHERE i < sz;
-- clustered: sz consecutive rowids from a random start (inside one or two row groups)
CREATE TEMP TABLE hits_c AS
  SELECT 10 AS sz, k, i, ((hash(k, 777) % (getvariable('n') - 10)) + i)::BIGINT AS rowid
  FROM range(16) kk(k), range(10) ii(i);

-- ===== p: default row groups (122880), default file size
.print == build p
.timer on
CALL lake.set_option('parquet_row_group_size', 122880);
CALL lake.set_option('target_file_size', '512MB');
CREATE TABLE lake.p AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE in_title_10_p AS SELECT count(*), max(title) FROM lake.p WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE join_all3_10_p AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE join_title_100_p AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE in_title_100_p AS SELECT count(*), max(title) FROM lake.p WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p p ON p.rowid = h.rowid;
.print == case p join_title_10
.timer on
EXECUTE join_title_10_p(0);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(2);
EXECUTE join_title_10_p(3);
EXECUTE join_title_10_p(4);
EXECUTE join_title_10_p(5);
EXECUTE join_title_10_p(6);
EXECUTE join_title_10_p(7);
EXECUTE join_title_10_p(8);
EXECUTE join_title_10_p(9);
EXECUTE join_title_10_p(10);
EXECUTE join_title_10_p(11);
EXECUTE join_title_10_p(12);
EXECUTE join_title_10_p(13);
EXECUTE join_title_10_p(14);
EXECUTE join_title_10_p(15);
.timer off
.print == case p in_title_10
.timer on
EXECUTE in_title_10_p(16);
EXECUTE in_title_10_p(17);
EXECUTE in_title_10_p(18);
EXECUTE in_title_10_p(19);
EXECUTE in_title_10_p(20);
EXECUTE in_title_10_p(21);
EXECUTE in_title_10_p(22);
EXECUTE in_title_10_p(23);
EXECUTE in_title_10_p(24);
EXECUTE in_title_10_p(25);
EXECUTE in_title_10_p(26);
EXECUTE in_title_10_p(27);
EXECUTE in_title_10_p(28);
EXECUTE in_title_10_p(29);
EXECUTE in_title_10_p(30);
EXECUTE in_title_10_p(31);
.timer off
.print == case p join_body_10
.timer on
EXECUTE join_body_10_p(0);
EXECUTE join_body_10_p(1);
EXECUTE join_body_10_p(2);
EXECUTE join_body_10_p(3);
EXECUTE join_body_10_p(4);
EXECUTE join_body_10_p(5);
EXECUTE join_body_10_p(6);
EXECUTE join_body_10_p(7);
EXECUTE join_body_10_p(8);
EXECUTE join_body_10_p(9);
EXECUTE join_body_10_p(10);
EXECUTE join_body_10_p(11);
EXECUTE join_body_10_p(12);
EXECUTE join_body_10_p(13);
EXECUTE join_body_10_p(14);
EXECUTE join_body_10_p(15);
.timer off
.print == case p join_all3_10
.timer on
EXECUTE join_all3_10_p(0);
EXECUTE join_all3_10_p(1);
EXECUTE join_all3_10_p(2);
EXECUTE join_all3_10_p(3);
EXECUTE join_all3_10_p(4);
EXECUTE join_all3_10_p(5);
EXECUTE join_all3_10_p(6);
EXECUTE join_all3_10_p(7);
EXECUTE join_all3_10_p(8);
EXECUTE join_all3_10_p(9);
EXECUTE join_all3_10_p(10);
EXECUTE join_all3_10_p(11);
EXECUTE join_all3_10_p(12);
EXECUTE join_all3_10_p(13);
EXECUTE join_all3_10_p(14);
EXECUTE join_all3_10_p(15);
.timer off
.print == case p join_title_100
.timer on
EXECUTE join_title_100_p(0);
EXECUTE join_title_100_p(1);
EXECUTE join_title_100_p(2);
EXECUTE join_title_100_p(3);
EXECUTE join_title_100_p(4);
EXECUTE join_title_100_p(5);
EXECUTE join_title_100_p(6);
EXECUTE join_title_100_p(7);
EXECUTE join_title_100_p(8);
EXECUTE join_title_100_p(9);
EXECUTE join_title_100_p(10);
EXECUTE join_title_100_p(11);
EXECUTE join_title_100_p(12);
EXECUTE join_title_100_p(13);
EXECUTE join_title_100_p(14);
EXECUTE join_title_100_p(15);
.timer off
.print == case p in_title_100
.timer on
EXECUTE in_title_100_p(16);
EXECUTE in_title_100_p(17);
EXECUTE in_title_100_p(18);
EXECUTE in_title_100_p(19);
EXECUTE in_title_100_p(20);
EXECUTE in_title_100_p(21);
EXECUTE in_title_100_p(22);
EXECUTE in_title_100_p(23);
EXECUTE in_title_100_p(24);
EXECUTE in_title_100_p(25);
EXECUTE in_title_100_p(26);
EXECUTE in_title_100_p(27);
EXECUTE in_title_100_p(28);
EXECUTE in_title_100_p(29);
EXECUTE in_title_100_p(30);
EXECUTE in_title_100_p(31);
.timer off
.print == case p join_body_100
.timer on
EXECUTE join_body_100_p(0);
EXECUTE join_body_100_p(1);
EXECUTE join_body_100_p(2);
EXECUTE join_body_100_p(3);
EXECUTE join_body_100_p(4);
EXECUTE join_body_100_p(5);
EXECUTE join_body_100_p(6);
EXECUTE join_body_100_p(7);
EXECUTE join_body_100_p(8);
EXECUTE join_body_100_p(9);
EXECUTE join_body_100_p(10);
EXECUTE join_body_100_p(11);
EXECUTE join_body_100_p(12);
EXECUTE join_body_100_p(13);
EXECUTE join_body_100_p(14);
EXECUTE join_body_100_p(15);
.timer off
.print == case p join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p(0);
EXECUTE join_title_10_clustered_p(1);
EXECUTE join_title_10_clustered_p(2);
EXECUTE join_title_10_clustered_p(3);
EXECUTE join_title_10_clustered_p(4);
EXECUTE join_title_10_clustered_p(5);
EXECUTE join_title_10_clustered_p(6);
EXECUTE join_title_10_clustered_p(7);
EXECUTE join_title_10_clustered_p(8);
EXECUTE join_title_10_clustered_p(9);
EXECUTE join_title_10_clustered_p(10);
EXECUTE join_title_10_clustered_p(11);
EXECUTE join_title_10_clustered_p(12);
EXECUTE join_title_10_clustered_p(13);
EXECUTE join_title_10_clustered_p(14);
EXECUTE join_title_10_clustered_p(15);
.timer off
.print == case p join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p(0);
EXECUTE join_title_10_ordered_p(1);
EXECUTE join_title_10_ordered_p(2);
EXECUTE join_title_10_ordered_p(3);
EXECUTE join_title_10_ordered_p(4);
EXECUTE join_title_10_ordered_p(5);
EXECUTE join_title_10_ordered_p(6);
EXECUTE join_title_10_ordered_p(7);
EXECUTE join_title_10_ordered_p(8);
EXECUTE join_title_10_ordered_p(9);
EXECUTE join_title_10_ordered_p(10);
EXECUTE join_title_10_ordered_p(11);
EXECUTE join_title_10_ordered_p(12);
EXECUTE join_title_10_ordered_p(13);
EXECUTE join_title_10_ordered_p(14);
EXECUTE join_title_10_ordered_p(15);
.timer off
.print == case p join_title_10_same_set
.timer on
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
EXECUTE join_title_10_p(1);
.timer off
.print == plan p join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p(1);
.mode trash
.print == plan p in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p(1);
.mode trash
.print == plan p join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p(1);
.mode trash
.print == plan p join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p(1);
.mode trash
.print == plan p join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p(1);
.mode trash

-- ===== p_rg32k: row group 32768
.print == build p_rg32k
.timer on
CALL lake.set_option('parquet_row_group_size', 32768);
CREATE TABLE lake.p_rg32k AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p_rg32k
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rg32k');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rg32k' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rg32k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rg32k AS SELECT count(*), max(title) FROM lake.p_rg32k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rg32k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rg32k AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rg32k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rg32k AS SELECT count(*), max(title) FROM lake.p_rg32k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rg32k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rg32k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rg32k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rg32k p ON p.rowid = h.rowid;
.print == case p_rg32k join_title_10
.timer on
EXECUTE join_title_10_p_rg32k(0);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(2);
EXECUTE join_title_10_p_rg32k(3);
EXECUTE join_title_10_p_rg32k(4);
EXECUTE join_title_10_p_rg32k(5);
EXECUTE join_title_10_p_rg32k(6);
EXECUTE join_title_10_p_rg32k(7);
EXECUTE join_title_10_p_rg32k(8);
EXECUTE join_title_10_p_rg32k(9);
EXECUTE join_title_10_p_rg32k(10);
EXECUTE join_title_10_p_rg32k(11);
EXECUTE join_title_10_p_rg32k(12);
EXECUTE join_title_10_p_rg32k(13);
EXECUTE join_title_10_p_rg32k(14);
EXECUTE join_title_10_p_rg32k(15);
.timer off
.print == case p_rg32k in_title_10
.timer on
EXECUTE in_title_10_p_rg32k(16);
EXECUTE in_title_10_p_rg32k(17);
EXECUTE in_title_10_p_rg32k(18);
EXECUTE in_title_10_p_rg32k(19);
EXECUTE in_title_10_p_rg32k(20);
EXECUTE in_title_10_p_rg32k(21);
EXECUTE in_title_10_p_rg32k(22);
EXECUTE in_title_10_p_rg32k(23);
EXECUTE in_title_10_p_rg32k(24);
EXECUTE in_title_10_p_rg32k(25);
EXECUTE in_title_10_p_rg32k(26);
EXECUTE in_title_10_p_rg32k(27);
EXECUTE in_title_10_p_rg32k(28);
EXECUTE in_title_10_p_rg32k(29);
EXECUTE in_title_10_p_rg32k(30);
EXECUTE in_title_10_p_rg32k(31);
.timer off
.print == case p_rg32k join_body_10
.timer on
EXECUTE join_body_10_p_rg32k(0);
EXECUTE join_body_10_p_rg32k(1);
EXECUTE join_body_10_p_rg32k(2);
EXECUTE join_body_10_p_rg32k(3);
EXECUTE join_body_10_p_rg32k(4);
EXECUTE join_body_10_p_rg32k(5);
EXECUTE join_body_10_p_rg32k(6);
EXECUTE join_body_10_p_rg32k(7);
EXECUTE join_body_10_p_rg32k(8);
EXECUTE join_body_10_p_rg32k(9);
EXECUTE join_body_10_p_rg32k(10);
EXECUTE join_body_10_p_rg32k(11);
EXECUTE join_body_10_p_rg32k(12);
EXECUTE join_body_10_p_rg32k(13);
EXECUTE join_body_10_p_rg32k(14);
EXECUTE join_body_10_p_rg32k(15);
.timer off
.print == case p_rg32k join_all3_10
.timer on
EXECUTE join_all3_10_p_rg32k(0);
EXECUTE join_all3_10_p_rg32k(1);
EXECUTE join_all3_10_p_rg32k(2);
EXECUTE join_all3_10_p_rg32k(3);
EXECUTE join_all3_10_p_rg32k(4);
EXECUTE join_all3_10_p_rg32k(5);
EXECUTE join_all3_10_p_rg32k(6);
EXECUTE join_all3_10_p_rg32k(7);
EXECUTE join_all3_10_p_rg32k(8);
EXECUTE join_all3_10_p_rg32k(9);
EXECUTE join_all3_10_p_rg32k(10);
EXECUTE join_all3_10_p_rg32k(11);
EXECUTE join_all3_10_p_rg32k(12);
EXECUTE join_all3_10_p_rg32k(13);
EXECUTE join_all3_10_p_rg32k(14);
EXECUTE join_all3_10_p_rg32k(15);
.timer off
.print == case p_rg32k join_title_100
.timer on
EXECUTE join_title_100_p_rg32k(0);
EXECUTE join_title_100_p_rg32k(1);
EXECUTE join_title_100_p_rg32k(2);
EXECUTE join_title_100_p_rg32k(3);
EXECUTE join_title_100_p_rg32k(4);
EXECUTE join_title_100_p_rg32k(5);
EXECUTE join_title_100_p_rg32k(6);
EXECUTE join_title_100_p_rg32k(7);
EXECUTE join_title_100_p_rg32k(8);
EXECUTE join_title_100_p_rg32k(9);
EXECUTE join_title_100_p_rg32k(10);
EXECUTE join_title_100_p_rg32k(11);
EXECUTE join_title_100_p_rg32k(12);
EXECUTE join_title_100_p_rg32k(13);
EXECUTE join_title_100_p_rg32k(14);
EXECUTE join_title_100_p_rg32k(15);
.timer off
.print == case p_rg32k in_title_100
.timer on
EXECUTE in_title_100_p_rg32k(16);
EXECUTE in_title_100_p_rg32k(17);
EXECUTE in_title_100_p_rg32k(18);
EXECUTE in_title_100_p_rg32k(19);
EXECUTE in_title_100_p_rg32k(20);
EXECUTE in_title_100_p_rg32k(21);
EXECUTE in_title_100_p_rg32k(22);
EXECUTE in_title_100_p_rg32k(23);
EXECUTE in_title_100_p_rg32k(24);
EXECUTE in_title_100_p_rg32k(25);
EXECUTE in_title_100_p_rg32k(26);
EXECUTE in_title_100_p_rg32k(27);
EXECUTE in_title_100_p_rg32k(28);
EXECUTE in_title_100_p_rg32k(29);
EXECUTE in_title_100_p_rg32k(30);
EXECUTE in_title_100_p_rg32k(31);
.timer off
.print == case p_rg32k join_body_100
.timer on
EXECUTE join_body_100_p_rg32k(0);
EXECUTE join_body_100_p_rg32k(1);
EXECUTE join_body_100_p_rg32k(2);
EXECUTE join_body_100_p_rg32k(3);
EXECUTE join_body_100_p_rg32k(4);
EXECUTE join_body_100_p_rg32k(5);
EXECUTE join_body_100_p_rg32k(6);
EXECUTE join_body_100_p_rg32k(7);
EXECUTE join_body_100_p_rg32k(8);
EXECUTE join_body_100_p_rg32k(9);
EXECUTE join_body_100_p_rg32k(10);
EXECUTE join_body_100_p_rg32k(11);
EXECUTE join_body_100_p_rg32k(12);
EXECUTE join_body_100_p_rg32k(13);
EXECUTE join_body_100_p_rg32k(14);
EXECUTE join_body_100_p_rg32k(15);
.timer off
.print == case p_rg32k join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rg32k(0);
EXECUTE join_title_10_clustered_p_rg32k(1);
EXECUTE join_title_10_clustered_p_rg32k(2);
EXECUTE join_title_10_clustered_p_rg32k(3);
EXECUTE join_title_10_clustered_p_rg32k(4);
EXECUTE join_title_10_clustered_p_rg32k(5);
EXECUTE join_title_10_clustered_p_rg32k(6);
EXECUTE join_title_10_clustered_p_rg32k(7);
EXECUTE join_title_10_clustered_p_rg32k(8);
EXECUTE join_title_10_clustered_p_rg32k(9);
EXECUTE join_title_10_clustered_p_rg32k(10);
EXECUTE join_title_10_clustered_p_rg32k(11);
EXECUTE join_title_10_clustered_p_rg32k(12);
EXECUTE join_title_10_clustered_p_rg32k(13);
EXECUTE join_title_10_clustered_p_rg32k(14);
EXECUTE join_title_10_clustered_p_rg32k(15);
.timer off
.print == case p_rg32k join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rg32k(0);
EXECUTE join_title_10_ordered_p_rg32k(1);
EXECUTE join_title_10_ordered_p_rg32k(2);
EXECUTE join_title_10_ordered_p_rg32k(3);
EXECUTE join_title_10_ordered_p_rg32k(4);
EXECUTE join_title_10_ordered_p_rg32k(5);
EXECUTE join_title_10_ordered_p_rg32k(6);
EXECUTE join_title_10_ordered_p_rg32k(7);
EXECUTE join_title_10_ordered_p_rg32k(8);
EXECUTE join_title_10_ordered_p_rg32k(9);
EXECUTE join_title_10_ordered_p_rg32k(10);
EXECUTE join_title_10_ordered_p_rg32k(11);
EXECUTE join_title_10_ordered_p_rg32k(12);
EXECUTE join_title_10_ordered_p_rg32k(13);
EXECUTE join_title_10_ordered_p_rg32k(14);
EXECUTE join_title_10_ordered_p_rg32k(15);
.timer off
.print == case p_rg32k join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
EXECUTE join_title_10_p_rg32k(1);
.timer off
.print == plan p_rg32k join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rg32k(1);
.mode trash
.print == plan p_rg32k in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rg32k(1);
.mode trash
.print == plan p_rg32k join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rg32k(1);
.mode trash
.print == plan p_rg32k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg32k(1);
.mode trash
.print == plan p_rg32k join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rg32k(1);
.mode trash

-- ===== p_rg16k: row group 16384
.print == build p_rg16k
.timer on
CALL lake.set_option('parquet_row_group_size', 16384);
CREATE TABLE lake.p_rg16k AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p_rg16k
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rg16k');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rg16k' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rg16k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rg16k AS SELECT count(*), max(title) FROM lake.p_rg16k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rg16k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rg16k AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rg16k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rg16k AS SELECT count(*), max(title) FROM lake.p_rg16k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rg16k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rg16k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rg16k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rg16k p ON p.rowid = h.rowid;
.print == case p_rg16k join_title_10
.timer on
EXECUTE join_title_10_p_rg16k(0);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(2);
EXECUTE join_title_10_p_rg16k(3);
EXECUTE join_title_10_p_rg16k(4);
EXECUTE join_title_10_p_rg16k(5);
EXECUTE join_title_10_p_rg16k(6);
EXECUTE join_title_10_p_rg16k(7);
EXECUTE join_title_10_p_rg16k(8);
EXECUTE join_title_10_p_rg16k(9);
EXECUTE join_title_10_p_rg16k(10);
EXECUTE join_title_10_p_rg16k(11);
EXECUTE join_title_10_p_rg16k(12);
EXECUTE join_title_10_p_rg16k(13);
EXECUTE join_title_10_p_rg16k(14);
EXECUTE join_title_10_p_rg16k(15);
.timer off
.print == case p_rg16k in_title_10
.timer on
EXECUTE in_title_10_p_rg16k(16);
EXECUTE in_title_10_p_rg16k(17);
EXECUTE in_title_10_p_rg16k(18);
EXECUTE in_title_10_p_rg16k(19);
EXECUTE in_title_10_p_rg16k(20);
EXECUTE in_title_10_p_rg16k(21);
EXECUTE in_title_10_p_rg16k(22);
EXECUTE in_title_10_p_rg16k(23);
EXECUTE in_title_10_p_rg16k(24);
EXECUTE in_title_10_p_rg16k(25);
EXECUTE in_title_10_p_rg16k(26);
EXECUTE in_title_10_p_rg16k(27);
EXECUTE in_title_10_p_rg16k(28);
EXECUTE in_title_10_p_rg16k(29);
EXECUTE in_title_10_p_rg16k(30);
EXECUTE in_title_10_p_rg16k(31);
.timer off
.print == case p_rg16k join_body_10
.timer on
EXECUTE join_body_10_p_rg16k(0);
EXECUTE join_body_10_p_rg16k(1);
EXECUTE join_body_10_p_rg16k(2);
EXECUTE join_body_10_p_rg16k(3);
EXECUTE join_body_10_p_rg16k(4);
EXECUTE join_body_10_p_rg16k(5);
EXECUTE join_body_10_p_rg16k(6);
EXECUTE join_body_10_p_rg16k(7);
EXECUTE join_body_10_p_rg16k(8);
EXECUTE join_body_10_p_rg16k(9);
EXECUTE join_body_10_p_rg16k(10);
EXECUTE join_body_10_p_rg16k(11);
EXECUTE join_body_10_p_rg16k(12);
EXECUTE join_body_10_p_rg16k(13);
EXECUTE join_body_10_p_rg16k(14);
EXECUTE join_body_10_p_rg16k(15);
.timer off
.print == case p_rg16k join_all3_10
.timer on
EXECUTE join_all3_10_p_rg16k(0);
EXECUTE join_all3_10_p_rg16k(1);
EXECUTE join_all3_10_p_rg16k(2);
EXECUTE join_all3_10_p_rg16k(3);
EXECUTE join_all3_10_p_rg16k(4);
EXECUTE join_all3_10_p_rg16k(5);
EXECUTE join_all3_10_p_rg16k(6);
EXECUTE join_all3_10_p_rg16k(7);
EXECUTE join_all3_10_p_rg16k(8);
EXECUTE join_all3_10_p_rg16k(9);
EXECUTE join_all3_10_p_rg16k(10);
EXECUTE join_all3_10_p_rg16k(11);
EXECUTE join_all3_10_p_rg16k(12);
EXECUTE join_all3_10_p_rg16k(13);
EXECUTE join_all3_10_p_rg16k(14);
EXECUTE join_all3_10_p_rg16k(15);
.timer off
.print == case p_rg16k join_title_100
.timer on
EXECUTE join_title_100_p_rg16k(0);
EXECUTE join_title_100_p_rg16k(1);
EXECUTE join_title_100_p_rg16k(2);
EXECUTE join_title_100_p_rg16k(3);
EXECUTE join_title_100_p_rg16k(4);
EXECUTE join_title_100_p_rg16k(5);
EXECUTE join_title_100_p_rg16k(6);
EXECUTE join_title_100_p_rg16k(7);
EXECUTE join_title_100_p_rg16k(8);
EXECUTE join_title_100_p_rg16k(9);
EXECUTE join_title_100_p_rg16k(10);
EXECUTE join_title_100_p_rg16k(11);
EXECUTE join_title_100_p_rg16k(12);
EXECUTE join_title_100_p_rg16k(13);
EXECUTE join_title_100_p_rg16k(14);
EXECUTE join_title_100_p_rg16k(15);
.timer off
.print == case p_rg16k in_title_100
.timer on
EXECUTE in_title_100_p_rg16k(16);
EXECUTE in_title_100_p_rg16k(17);
EXECUTE in_title_100_p_rg16k(18);
EXECUTE in_title_100_p_rg16k(19);
EXECUTE in_title_100_p_rg16k(20);
EXECUTE in_title_100_p_rg16k(21);
EXECUTE in_title_100_p_rg16k(22);
EXECUTE in_title_100_p_rg16k(23);
EXECUTE in_title_100_p_rg16k(24);
EXECUTE in_title_100_p_rg16k(25);
EXECUTE in_title_100_p_rg16k(26);
EXECUTE in_title_100_p_rg16k(27);
EXECUTE in_title_100_p_rg16k(28);
EXECUTE in_title_100_p_rg16k(29);
EXECUTE in_title_100_p_rg16k(30);
EXECUTE in_title_100_p_rg16k(31);
.timer off
.print == case p_rg16k join_body_100
.timer on
EXECUTE join_body_100_p_rg16k(0);
EXECUTE join_body_100_p_rg16k(1);
EXECUTE join_body_100_p_rg16k(2);
EXECUTE join_body_100_p_rg16k(3);
EXECUTE join_body_100_p_rg16k(4);
EXECUTE join_body_100_p_rg16k(5);
EXECUTE join_body_100_p_rg16k(6);
EXECUTE join_body_100_p_rg16k(7);
EXECUTE join_body_100_p_rg16k(8);
EXECUTE join_body_100_p_rg16k(9);
EXECUTE join_body_100_p_rg16k(10);
EXECUTE join_body_100_p_rg16k(11);
EXECUTE join_body_100_p_rg16k(12);
EXECUTE join_body_100_p_rg16k(13);
EXECUTE join_body_100_p_rg16k(14);
EXECUTE join_body_100_p_rg16k(15);
.timer off
.print == case p_rg16k join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rg16k(0);
EXECUTE join_title_10_clustered_p_rg16k(1);
EXECUTE join_title_10_clustered_p_rg16k(2);
EXECUTE join_title_10_clustered_p_rg16k(3);
EXECUTE join_title_10_clustered_p_rg16k(4);
EXECUTE join_title_10_clustered_p_rg16k(5);
EXECUTE join_title_10_clustered_p_rg16k(6);
EXECUTE join_title_10_clustered_p_rg16k(7);
EXECUTE join_title_10_clustered_p_rg16k(8);
EXECUTE join_title_10_clustered_p_rg16k(9);
EXECUTE join_title_10_clustered_p_rg16k(10);
EXECUTE join_title_10_clustered_p_rg16k(11);
EXECUTE join_title_10_clustered_p_rg16k(12);
EXECUTE join_title_10_clustered_p_rg16k(13);
EXECUTE join_title_10_clustered_p_rg16k(14);
EXECUTE join_title_10_clustered_p_rg16k(15);
.timer off
.print == case p_rg16k join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rg16k(0);
EXECUTE join_title_10_ordered_p_rg16k(1);
EXECUTE join_title_10_ordered_p_rg16k(2);
EXECUTE join_title_10_ordered_p_rg16k(3);
EXECUTE join_title_10_ordered_p_rg16k(4);
EXECUTE join_title_10_ordered_p_rg16k(5);
EXECUTE join_title_10_ordered_p_rg16k(6);
EXECUTE join_title_10_ordered_p_rg16k(7);
EXECUTE join_title_10_ordered_p_rg16k(8);
EXECUTE join_title_10_ordered_p_rg16k(9);
EXECUTE join_title_10_ordered_p_rg16k(10);
EXECUTE join_title_10_ordered_p_rg16k(11);
EXECUTE join_title_10_ordered_p_rg16k(12);
EXECUTE join_title_10_ordered_p_rg16k(13);
EXECUTE join_title_10_ordered_p_rg16k(14);
EXECUTE join_title_10_ordered_p_rg16k(15);
.timer off
.print == case p_rg16k join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(1);
.timer off
.print == plan p_rg16k join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rg16k(1);
.mode trash
.print == plan p_rg16k in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rg16k(1);
.mode trash
.print == plan p_rg16k join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rg16k(1);
.mode trash
.print == plan p_rg16k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg16k(1);
.mode trash
.print == plan p_rg16k join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rg16k(1);
.mode trash

-- ===== p_rg8k: row group 8192
.print == build p_rg8k
.timer on
CALL lake.set_option('parquet_row_group_size', 8192);
CREATE TABLE lake.p_rg8k AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p_rg8k
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rg8k');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rg8k' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rg8k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rg8k AS SELECT count(*), max(title) FROM lake.p_rg8k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rg8k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rg8k AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rg8k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rg8k AS SELECT count(*), max(title) FROM lake.p_rg8k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rg8k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rg8k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rg8k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rg8k p ON p.rowid = h.rowid;
.print == case p_rg8k join_title_10
.timer on
EXECUTE join_title_10_p_rg8k(0);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(2);
EXECUTE join_title_10_p_rg8k(3);
EXECUTE join_title_10_p_rg8k(4);
EXECUTE join_title_10_p_rg8k(5);
EXECUTE join_title_10_p_rg8k(6);
EXECUTE join_title_10_p_rg8k(7);
EXECUTE join_title_10_p_rg8k(8);
EXECUTE join_title_10_p_rg8k(9);
EXECUTE join_title_10_p_rg8k(10);
EXECUTE join_title_10_p_rg8k(11);
EXECUTE join_title_10_p_rg8k(12);
EXECUTE join_title_10_p_rg8k(13);
EXECUTE join_title_10_p_rg8k(14);
EXECUTE join_title_10_p_rg8k(15);
.timer off
.print == case p_rg8k in_title_10
.timer on
EXECUTE in_title_10_p_rg8k(16);
EXECUTE in_title_10_p_rg8k(17);
EXECUTE in_title_10_p_rg8k(18);
EXECUTE in_title_10_p_rg8k(19);
EXECUTE in_title_10_p_rg8k(20);
EXECUTE in_title_10_p_rg8k(21);
EXECUTE in_title_10_p_rg8k(22);
EXECUTE in_title_10_p_rg8k(23);
EXECUTE in_title_10_p_rg8k(24);
EXECUTE in_title_10_p_rg8k(25);
EXECUTE in_title_10_p_rg8k(26);
EXECUTE in_title_10_p_rg8k(27);
EXECUTE in_title_10_p_rg8k(28);
EXECUTE in_title_10_p_rg8k(29);
EXECUTE in_title_10_p_rg8k(30);
EXECUTE in_title_10_p_rg8k(31);
.timer off
.print == case p_rg8k join_body_10
.timer on
EXECUTE join_body_10_p_rg8k(0);
EXECUTE join_body_10_p_rg8k(1);
EXECUTE join_body_10_p_rg8k(2);
EXECUTE join_body_10_p_rg8k(3);
EXECUTE join_body_10_p_rg8k(4);
EXECUTE join_body_10_p_rg8k(5);
EXECUTE join_body_10_p_rg8k(6);
EXECUTE join_body_10_p_rg8k(7);
EXECUTE join_body_10_p_rg8k(8);
EXECUTE join_body_10_p_rg8k(9);
EXECUTE join_body_10_p_rg8k(10);
EXECUTE join_body_10_p_rg8k(11);
EXECUTE join_body_10_p_rg8k(12);
EXECUTE join_body_10_p_rg8k(13);
EXECUTE join_body_10_p_rg8k(14);
EXECUTE join_body_10_p_rg8k(15);
.timer off
.print == case p_rg8k join_all3_10
.timer on
EXECUTE join_all3_10_p_rg8k(0);
EXECUTE join_all3_10_p_rg8k(1);
EXECUTE join_all3_10_p_rg8k(2);
EXECUTE join_all3_10_p_rg8k(3);
EXECUTE join_all3_10_p_rg8k(4);
EXECUTE join_all3_10_p_rg8k(5);
EXECUTE join_all3_10_p_rg8k(6);
EXECUTE join_all3_10_p_rg8k(7);
EXECUTE join_all3_10_p_rg8k(8);
EXECUTE join_all3_10_p_rg8k(9);
EXECUTE join_all3_10_p_rg8k(10);
EXECUTE join_all3_10_p_rg8k(11);
EXECUTE join_all3_10_p_rg8k(12);
EXECUTE join_all3_10_p_rg8k(13);
EXECUTE join_all3_10_p_rg8k(14);
EXECUTE join_all3_10_p_rg8k(15);
.timer off
.print == case p_rg8k join_title_100
.timer on
EXECUTE join_title_100_p_rg8k(0);
EXECUTE join_title_100_p_rg8k(1);
EXECUTE join_title_100_p_rg8k(2);
EXECUTE join_title_100_p_rg8k(3);
EXECUTE join_title_100_p_rg8k(4);
EXECUTE join_title_100_p_rg8k(5);
EXECUTE join_title_100_p_rg8k(6);
EXECUTE join_title_100_p_rg8k(7);
EXECUTE join_title_100_p_rg8k(8);
EXECUTE join_title_100_p_rg8k(9);
EXECUTE join_title_100_p_rg8k(10);
EXECUTE join_title_100_p_rg8k(11);
EXECUTE join_title_100_p_rg8k(12);
EXECUTE join_title_100_p_rg8k(13);
EXECUTE join_title_100_p_rg8k(14);
EXECUTE join_title_100_p_rg8k(15);
.timer off
.print == case p_rg8k in_title_100
.timer on
EXECUTE in_title_100_p_rg8k(16);
EXECUTE in_title_100_p_rg8k(17);
EXECUTE in_title_100_p_rg8k(18);
EXECUTE in_title_100_p_rg8k(19);
EXECUTE in_title_100_p_rg8k(20);
EXECUTE in_title_100_p_rg8k(21);
EXECUTE in_title_100_p_rg8k(22);
EXECUTE in_title_100_p_rg8k(23);
EXECUTE in_title_100_p_rg8k(24);
EXECUTE in_title_100_p_rg8k(25);
EXECUTE in_title_100_p_rg8k(26);
EXECUTE in_title_100_p_rg8k(27);
EXECUTE in_title_100_p_rg8k(28);
EXECUTE in_title_100_p_rg8k(29);
EXECUTE in_title_100_p_rg8k(30);
EXECUTE in_title_100_p_rg8k(31);
.timer off
.print == case p_rg8k join_body_100
.timer on
EXECUTE join_body_100_p_rg8k(0);
EXECUTE join_body_100_p_rg8k(1);
EXECUTE join_body_100_p_rg8k(2);
EXECUTE join_body_100_p_rg8k(3);
EXECUTE join_body_100_p_rg8k(4);
EXECUTE join_body_100_p_rg8k(5);
EXECUTE join_body_100_p_rg8k(6);
EXECUTE join_body_100_p_rg8k(7);
EXECUTE join_body_100_p_rg8k(8);
EXECUTE join_body_100_p_rg8k(9);
EXECUTE join_body_100_p_rg8k(10);
EXECUTE join_body_100_p_rg8k(11);
EXECUTE join_body_100_p_rg8k(12);
EXECUTE join_body_100_p_rg8k(13);
EXECUTE join_body_100_p_rg8k(14);
EXECUTE join_body_100_p_rg8k(15);
.timer off
.print == case p_rg8k join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rg8k(0);
EXECUTE join_title_10_clustered_p_rg8k(1);
EXECUTE join_title_10_clustered_p_rg8k(2);
EXECUTE join_title_10_clustered_p_rg8k(3);
EXECUTE join_title_10_clustered_p_rg8k(4);
EXECUTE join_title_10_clustered_p_rg8k(5);
EXECUTE join_title_10_clustered_p_rg8k(6);
EXECUTE join_title_10_clustered_p_rg8k(7);
EXECUTE join_title_10_clustered_p_rg8k(8);
EXECUTE join_title_10_clustered_p_rg8k(9);
EXECUTE join_title_10_clustered_p_rg8k(10);
EXECUTE join_title_10_clustered_p_rg8k(11);
EXECUTE join_title_10_clustered_p_rg8k(12);
EXECUTE join_title_10_clustered_p_rg8k(13);
EXECUTE join_title_10_clustered_p_rg8k(14);
EXECUTE join_title_10_clustered_p_rg8k(15);
.timer off
.print == case p_rg8k join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rg8k(0);
EXECUTE join_title_10_ordered_p_rg8k(1);
EXECUTE join_title_10_ordered_p_rg8k(2);
EXECUTE join_title_10_ordered_p_rg8k(3);
EXECUTE join_title_10_ordered_p_rg8k(4);
EXECUTE join_title_10_ordered_p_rg8k(5);
EXECUTE join_title_10_ordered_p_rg8k(6);
EXECUTE join_title_10_ordered_p_rg8k(7);
EXECUTE join_title_10_ordered_p_rg8k(8);
EXECUTE join_title_10_ordered_p_rg8k(9);
EXECUTE join_title_10_ordered_p_rg8k(10);
EXECUTE join_title_10_ordered_p_rg8k(11);
EXECUTE join_title_10_ordered_p_rg8k(12);
EXECUTE join_title_10_ordered_p_rg8k(13);
EXECUTE join_title_10_ordered_p_rg8k(14);
EXECUTE join_title_10_ordered_p_rg8k(15);
.timer off
.print == case p_rg8k join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
EXECUTE join_title_10_p_rg8k(1);
.timer off
.print == plan p_rg8k join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rg8k(1);
.mode trash
.print == plan p_rg8k in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rg8k(1);
.mode trash
.print == plan p_rg8k join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rg8k(1);
.mode trash
.print == plan p_rg8k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg8k(1);
.mode trash
.print == plan p_rg8k join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rg8k(1);
.mode trash

-- ===== p_rg4k: row group 4096
.print == build p_rg4k
.timer on
CALL lake.set_option('parquet_row_group_size', 4096);
CREATE TABLE lake.p_rg4k AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p_rg4k
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rg4k');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rg4k' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rg4k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rg4k AS SELECT count(*), max(title) FROM lake.p_rg4k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rg4k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rg4k AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rg4k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rg4k AS SELECT count(*), max(title) FROM lake.p_rg4k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rg4k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rg4k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rg4k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rg4k p ON p.rowid = h.rowid;
.print == case p_rg4k join_title_10
.timer on
EXECUTE join_title_10_p_rg4k(0);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(2);
EXECUTE join_title_10_p_rg4k(3);
EXECUTE join_title_10_p_rg4k(4);
EXECUTE join_title_10_p_rg4k(5);
EXECUTE join_title_10_p_rg4k(6);
EXECUTE join_title_10_p_rg4k(7);
EXECUTE join_title_10_p_rg4k(8);
EXECUTE join_title_10_p_rg4k(9);
EXECUTE join_title_10_p_rg4k(10);
EXECUTE join_title_10_p_rg4k(11);
EXECUTE join_title_10_p_rg4k(12);
EXECUTE join_title_10_p_rg4k(13);
EXECUTE join_title_10_p_rg4k(14);
EXECUTE join_title_10_p_rg4k(15);
.timer off
.print == case p_rg4k in_title_10
.timer on
EXECUTE in_title_10_p_rg4k(16);
EXECUTE in_title_10_p_rg4k(17);
EXECUTE in_title_10_p_rg4k(18);
EXECUTE in_title_10_p_rg4k(19);
EXECUTE in_title_10_p_rg4k(20);
EXECUTE in_title_10_p_rg4k(21);
EXECUTE in_title_10_p_rg4k(22);
EXECUTE in_title_10_p_rg4k(23);
EXECUTE in_title_10_p_rg4k(24);
EXECUTE in_title_10_p_rg4k(25);
EXECUTE in_title_10_p_rg4k(26);
EXECUTE in_title_10_p_rg4k(27);
EXECUTE in_title_10_p_rg4k(28);
EXECUTE in_title_10_p_rg4k(29);
EXECUTE in_title_10_p_rg4k(30);
EXECUTE in_title_10_p_rg4k(31);
.timer off
.print == case p_rg4k join_body_10
.timer on
EXECUTE join_body_10_p_rg4k(0);
EXECUTE join_body_10_p_rg4k(1);
EXECUTE join_body_10_p_rg4k(2);
EXECUTE join_body_10_p_rg4k(3);
EXECUTE join_body_10_p_rg4k(4);
EXECUTE join_body_10_p_rg4k(5);
EXECUTE join_body_10_p_rg4k(6);
EXECUTE join_body_10_p_rg4k(7);
EXECUTE join_body_10_p_rg4k(8);
EXECUTE join_body_10_p_rg4k(9);
EXECUTE join_body_10_p_rg4k(10);
EXECUTE join_body_10_p_rg4k(11);
EXECUTE join_body_10_p_rg4k(12);
EXECUTE join_body_10_p_rg4k(13);
EXECUTE join_body_10_p_rg4k(14);
EXECUTE join_body_10_p_rg4k(15);
.timer off
.print == case p_rg4k join_all3_10
.timer on
EXECUTE join_all3_10_p_rg4k(0);
EXECUTE join_all3_10_p_rg4k(1);
EXECUTE join_all3_10_p_rg4k(2);
EXECUTE join_all3_10_p_rg4k(3);
EXECUTE join_all3_10_p_rg4k(4);
EXECUTE join_all3_10_p_rg4k(5);
EXECUTE join_all3_10_p_rg4k(6);
EXECUTE join_all3_10_p_rg4k(7);
EXECUTE join_all3_10_p_rg4k(8);
EXECUTE join_all3_10_p_rg4k(9);
EXECUTE join_all3_10_p_rg4k(10);
EXECUTE join_all3_10_p_rg4k(11);
EXECUTE join_all3_10_p_rg4k(12);
EXECUTE join_all3_10_p_rg4k(13);
EXECUTE join_all3_10_p_rg4k(14);
EXECUTE join_all3_10_p_rg4k(15);
.timer off
.print == case p_rg4k join_title_100
.timer on
EXECUTE join_title_100_p_rg4k(0);
EXECUTE join_title_100_p_rg4k(1);
EXECUTE join_title_100_p_rg4k(2);
EXECUTE join_title_100_p_rg4k(3);
EXECUTE join_title_100_p_rg4k(4);
EXECUTE join_title_100_p_rg4k(5);
EXECUTE join_title_100_p_rg4k(6);
EXECUTE join_title_100_p_rg4k(7);
EXECUTE join_title_100_p_rg4k(8);
EXECUTE join_title_100_p_rg4k(9);
EXECUTE join_title_100_p_rg4k(10);
EXECUTE join_title_100_p_rg4k(11);
EXECUTE join_title_100_p_rg4k(12);
EXECUTE join_title_100_p_rg4k(13);
EXECUTE join_title_100_p_rg4k(14);
EXECUTE join_title_100_p_rg4k(15);
.timer off
.print == case p_rg4k in_title_100
.timer on
EXECUTE in_title_100_p_rg4k(16);
EXECUTE in_title_100_p_rg4k(17);
EXECUTE in_title_100_p_rg4k(18);
EXECUTE in_title_100_p_rg4k(19);
EXECUTE in_title_100_p_rg4k(20);
EXECUTE in_title_100_p_rg4k(21);
EXECUTE in_title_100_p_rg4k(22);
EXECUTE in_title_100_p_rg4k(23);
EXECUTE in_title_100_p_rg4k(24);
EXECUTE in_title_100_p_rg4k(25);
EXECUTE in_title_100_p_rg4k(26);
EXECUTE in_title_100_p_rg4k(27);
EXECUTE in_title_100_p_rg4k(28);
EXECUTE in_title_100_p_rg4k(29);
EXECUTE in_title_100_p_rg4k(30);
EXECUTE in_title_100_p_rg4k(31);
.timer off
.print == case p_rg4k join_body_100
.timer on
EXECUTE join_body_100_p_rg4k(0);
EXECUTE join_body_100_p_rg4k(1);
EXECUTE join_body_100_p_rg4k(2);
EXECUTE join_body_100_p_rg4k(3);
EXECUTE join_body_100_p_rg4k(4);
EXECUTE join_body_100_p_rg4k(5);
EXECUTE join_body_100_p_rg4k(6);
EXECUTE join_body_100_p_rg4k(7);
EXECUTE join_body_100_p_rg4k(8);
EXECUTE join_body_100_p_rg4k(9);
EXECUTE join_body_100_p_rg4k(10);
EXECUTE join_body_100_p_rg4k(11);
EXECUTE join_body_100_p_rg4k(12);
EXECUTE join_body_100_p_rg4k(13);
EXECUTE join_body_100_p_rg4k(14);
EXECUTE join_body_100_p_rg4k(15);
.timer off
.print == case p_rg4k join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rg4k(0);
EXECUTE join_title_10_clustered_p_rg4k(1);
EXECUTE join_title_10_clustered_p_rg4k(2);
EXECUTE join_title_10_clustered_p_rg4k(3);
EXECUTE join_title_10_clustered_p_rg4k(4);
EXECUTE join_title_10_clustered_p_rg4k(5);
EXECUTE join_title_10_clustered_p_rg4k(6);
EXECUTE join_title_10_clustered_p_rg4k(7);
EXECUTE join_title_10_clustered_p_rg4k(8);
EXECUTE join_title_10_clustered_p_rg4k(9);
EXECUTE join_title_10_clustered_p_rg4k(10);
EXECUTE join_title_10_clustered_p_rg4k(11);
EXECUTE join_title_10_clustered_p_rg4k(12);
EXECUTE join_title_10_clustered_p_rg4k(13);
EXECUTE join_title_10_clustered_p_rg4k(14);
EXECUTE join_title_10_clustered_p_rg4k(15);
.timer off
.print == case p_rg4k join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rg4k(0);
EXECUTE join_title_10_ordered_p_rg4k(1);
EXECUTE join_title_10_ordered_p_rg4k(2);
EXECUTE join_title_10_ordered_p_rg4k(3);
EXECUTE join_title_10_ordered_p_rg4k(4);
EXECUTE join_title_10_ordered_p_rg4k(5);
EXECUTE join_title_10_ordered_p_rg4k(6);
EXECUTE join_title_10_ordered_p_rg4k(7);
EXECUTE join_title_10_ordered_p_rg4k(8);
EXECUTE join_title_10_ordered_p_rg4k(9);
EXECUTE join_title_10_ordered_p_rg4k(10);
EXECUTE join_title_10_ordered_p_rg4k(11);
EXECUTE join_title_10_ordered_p_rg4k(12);
EXECUTE join_title_10_ordered_p_rg4k(13);
EXECUTE join_title_10_ordered_p_rg4k(14);
EXECUTE join_title_10_ordered_p_rg4k(15);
.timer off
.print == case p_rg4k join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
EXECUTE join_title_10_p_rg4k(1);
.timer off
.print == plan p_rg4k join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rg4k(1);
.mode trash
.print == plan p_rg4k in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rg4k(1);
.mode trash
.print == plan p_rg4k join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rg4k(1);
.mode trash
.print == plan p_rg4k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg4k(1);
.mode trash
.print == plan p_rg4k join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rg4k(1);
.mode trash

-- ===== p_rg2k: row group 2048
.print == build p_rg2k
.timer on
CALL lake.set_option('parquet_row_group_size', 2048);
CREATE TABLE lake.p_rg2k AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
.timer off
.print == layout p_rg2k
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rg2k');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rg2k' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rg2k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rg2k AS SELECT count(*), max(title) FROM lake.p_rg2k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rg2k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rg2k AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rg2k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rg2k AS SELECT count(*), max(title) FROM lake.p_rg2k WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rg2k AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rg2k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rg2k AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rg2k p ON p.rowid = h.rowid;
.print == case p_rg2k join_title_10
.timer on
EXECUTE join_title_10_p_rg2k(0);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(2);
EXECUTE join_title_10_p_rg2k(3);
EXECUTE join_title_10_p_rg2k(4);
EXECUTE join_title_10_p_rg2k(5);
EXECUTE join_title_10_p_rg2k(6);
EXECUTE join_title_10_p_rg2k(7);
EXECUTE join_title_10_p_rg2k(8);
EXECUTE join_title_10_p_rg2k(9);
EXECUTE join_title_10_p_rg2k(10);
EXECUTE join_title_10_p_rg2k(11);
EXECUTE join_title_10_p_rg2k(12);
EXECUTE join_title_10_p_rg2k(13);
EXECUTE join_title_10_p_rg2k(14);
EXECUTE join_title_10_p_rg2k(15);
.timer off
.print == case p_rg2k in_title_10
.timer on
EXECUTE in_title_10_p_rg2k(16);
EXECUTE in_title_10_p_rg2k(17);
EXECUTE in_title_10_p_rg2k(18);
EXECUTE in_title_10_p_rg2k(19);
EXECUTE in_title_10_p_rg2k(20);
EXECUTE in_title_10_p_rg2k(21);
EXECUTE in_title_10_p_rg2k(22);
EXECUTE in_title_10_p_rg2k(23);
EXECUTE in_title_10_p_rg2k(24);
EXECUTE in_title_10_p_rg2k(25);
EXECUTE in_title_10_p_rg2k(26);
EXECUTE in_title_10_p_rg2k(27);
EXECUTE in_title_10_p_rg2k(28);
EXECUTE in_title_10_p_rg2k(29);
EXECUTE in_title_10_p_rg2k(30);
EXECUTE in_title_10_p_rg2k(31);
.timer off
.print == case p_rg2k join_body_10
.timer on
EXECUTE join_body_10_p_rg2k(0);
EXECUTE join_body_10_p_rg2k(1);
EXECUTE join_body_10_p_rg2k(2);
EXECUTE join_body_10_p_rg2k(3);
EXECUTE join_body_10_p_rg2k(4);
EXECUTE join_body_10_p_rg2k(5);
EXECUTE join_body_10_p_rg2k(6);
EXECUTE join_body_10_p_rg2k(7);
EXECUTE join_body_10_p_rg2k(8);
EXECUTE join_body_10_p_rg2k(9);
EXECUTE join_body_10_p_rg2k(10);
EXECUTE join_body_10_p_rg2k(11);
EXECUTE join_body_10_p_rg2k(12);
EXECUTE join_body_10_p_rg2k(13);
EXECUTE join_body_10_p_rg2k(14);
EXECUTE join_body_10_p_rg2k(15);
.timer off
.print == case p_rg2k join_all3_10
.timer on
EXECUTE join_all3_10_p_rg2k(0);
EXECUTE join_all3_10_p_rg2k(1);
EXECUTE join_all3_10_p_rg2k(2);
EXECUTE join_all3_10_p_rg2k(3);
EXECUTE join_all3_10_p_rg2k(4);
EXECUTE join_all3_10_p_rg2k(5);
EXECUTE join_all3_10_p_rg2k(6);
EXECUTE join_all3_10_p_rg2k(7);
EXECUTE join_all3_10_p_rg2k(8);
EXECUTE join_all3_10_p_rg2k(9);
EXECUTE join_all3_10_p_rg2k(10);
EXECUTE join_all3_10_p_rg2k(11);
EXECUTE join_all3_10_p_rg2k(12);
EXECUTE join_all3_10_p_rg2k(13);
EXECUTE join_all3_10_p_rg2k(14);
EXECUTE join_all3_10_p_rg2k(15);
.timer off
.print == case p_rg2k join_title_100
.timer on
EXECUTE join_title_100_p_rg2k(0);
EXECUTE join_title_100_p_rg2k(1);
EXECUTE join_title_100_p_rg2k(2);
EXECUTE join_title_100_p_rg2k(3);
EXECUTE join_title_100_p_rg2k(4);
EXECUTE join_title_100_p_rg2k(5);
EXECUTE join_title_100_p_rg2k(6);
EXECUTE join_title_100_p_rg2k(7);
EXECUTE join_title_100_p_rg2k(8);
EXECUTE join_title_100_p_rg2k(9);
EXECUTE join_title_100_p_rg2k(10);
EXECUTE join_title_100_p_rg2k(11);
EXECUTE join_title_100_p_rg2k(12);
EXECUTE join_title_100_p_rg2k(13);
EXECUTE join_title_100_p_rg2k(14);
EXECUTE join_title_100_p_rg2k(15);
.timer off
.print == case p_rg2k in_title_100
.timer on
EXECUTE in_title_100_p_rg2k(16);
EXECUTE in_title_100_p_rg2k(17);
EXECUTE in_title_100_p_rg2k(18);
EXECUTE in_title_100_p_rg2k(19);
EXECUTE in_title_100_p_rg2k(20);
EXECUTE in_title_100_p_rg2k(21);
EXECUTE in_title_100_p_rg2k(22);
EXECUTE in_title_100_p_rg2k(23);
EXECUTE in_title_100_p_rg2k(24);
EXECUTE in_title_100_p_rg2k(25);
EXECUTE in_title_100_p_rg2k(26);
EXECUTE in_title_100_p_rg2k(27);
EXECUTE in_title_100_p_rg2k(28);
EXECUTE in_title_100_p_rg2k(29);
EXECUTE in_title_100_p_rg2k(30);
EXECUTE in_title_100_p_rg2k(31);
.timer off
.print == case p_rg2k join_body_100
.timer on
EXECUTE join_body_100_p_rg2k(0);
EXECUTE join_body_100_p_rg2k(1);
EXECUTE join_body_100_p_rg2k(2);
EXECUTE join_body_100_p_rg2k(3);
EXECUTE join_body_100_p_rg2k(4);
EXECUTE join_body_100_p_rg2k(5);
EXECUTE join_body_100_p_rg2k(6);
EXECUTE join_body_100_p_rg2k(7);
EXECUTE join_body_100_p_rg2k(8);
EXECUTE join_body_100_p_rg2k(9);
EXECUTE join_body_100_p_rg2k(10);
EXECUTE join_body_100_p_rg2k(11);
EXECUTE join_body_100_p_rg2k(12);
EXECUTE join_body_100_p_rg2k(13);
EXECUTE join_body_100_p_rg2k(14);
EXECUTE join_body_100_p_rg2k(15);
.timer off
.print == case p_rg2k join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rg2k(0);
EXECUTE join_title_10_clustered_p_rg2k(1);
EXECUTE join_title_10_clustered_p_rg2k(2);
EXECUTE join_title_10_clustered_p_rg2k(3);
EXECUTE join_title_10_clustered_p_rg2k(4);
EXECUTE join_title_10_clustered_p_rg2k(5);
EXECUTE join_title_10_clustered_p_rg2k(6);
EXECUTE join_title_10_clustered_p_rg2k(7);
EXECUTE join_title_10_clustered_p_rg2k(8);
EXECUTE join_title_10_clustered_p_rg2k(9);
EXECUTE join_title_10_clustered_p_rg2k(10);
EXECUTE join_title_10_clustered_p_rg2k(11);
EXECUTE join_title_10_clustered_p_rg2k(12);
EXECUTE join_title_10_clustered_p_rg2k(13);
EXECUTE join_title_10_clustered_p_rg2k(14);
EXECUTE join_title_10_clustered_p_rg2k(15);
.timer off
.print == case p_rg2k join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rg2k(0);
EXECUTE join_title_10_ordered_p_rg2k(1);
EXECUTE join_title_10_ordered_p_rg2k(2);
EXECUTE join_title_10_ordered_p_rg2k(3);
EXECUTE join_title_10_ordered_p_rg2k(4);
EXECUTE join_title_10_ordered_p_rg2k(5);
EXECUTE join_title_10_ordered_p_rg2k(6);
EXECUTE join_title_10_ordered_p_rg2k(7);
EXECUTE join_title_10_ordered_p_rg2k(8);
EXECUTE join_title_10_ordered_p_rg2k(9);
EXECUTE join_title_10_ordered_p_rg2k(10);
EXECUTE join_title_10_ordered_p_rg2k(11);
EXECUTE join_title_10_ordered_p_rg2k(12);
EXECUTE join_title_10_ordered_p_rg2k(13);
EXECUTE join_title_10_ordered_p_rg2k(14);
EXECUTE join_title_10_ordered_p_rg2k(15);
.timer off
.print == case p_rg2k join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
EXECUTE join_title_10_p_rg2k(1);
.timer off
.print == plan p_rg2k join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rg2k(1);
.mode trash
.print == plan p_rg2k in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rg2k(1);
.mode trash
.print == plan p_rg2k join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rg2k(1);
.mode trash
.print == plan p_rg2k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg2k(1);
.mode trash
.print == plan p_rg2k join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rg2k(1);
.mode trash

-- ===== p_f64: default row groups, target file size 64MB
.print == build p_f64
.timer on
CALL lake.set_option('parquet_row_group_size', 122880);
CALL lake.set_option('target_file_size', '64MB');
CREATE TABLE lake.p_f64 AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
CALL lake.set_option('target_file_size', '512MB');
.timer off
.print == layout p_f64
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_f64');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_f64' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_f64 AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE in_title_10_p_f64 AS SELECT count(*), max(title) FROM lake.p_f64 WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_f64 AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_f64 AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE join_title_100_p_f64 AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE in_title_100_p_f64 AS SELECT count(*), max(title) FROM lake.p_f64 WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_f64 AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_f64 AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_f64 AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_f64 p ON p.rowid = h.rowid;
.print == case p_f64 join_title_10
.timer on
EXECUTE join_title_10_p_f64(0);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(2);
EXECUTE join_title_10_p_f64(3);
EXECUTE join_title_10_p_f64(4);
EXECUTE join_title_10_p_f64(5);
EXECUTE join_title_10_p_f64(6);
EXECUTE join_title_10_p_f64(7);
EXECUTE join_title_10_p_f64(8);
EXECUTE join_title_10_p_f64(9);
EXECUTE join_title_10_p_f64(10);
EXECUTE join_title_10_p_f64(11);
EXECUTE join_title_10_p_f64(12);
EXECUTE join_title_10_p_f64(13);
EXECUTE join_title_10_p_f64(14);
EXECUTE join_title_10_p_f64(15);
.timer off
.print == case p_f64 in_title_10
.timer on
EXECUTE in_title_10_p_f64(16);
EXECUTE in_title_10_p_f64(17);
EXECUTE in_title_10_p_f64(18);
EXECUTE in_title_10_p_f64(19);
EXECUTE in_title_10_p_f64(20);
EXECUTE in_title_10_p_f64(21);
EXECUTE in_title_10_p_f64(22);
EXECUTE in_title_10_p_f64(23);
EXECUTE in_title_10_p_f64(24);
EXECUTE in_title_10_p_f64(25);
EXECUTE in_title_10_p_f64(26);
EXECUTE in_title_10_p_f64(27);
EXECUTE in_title_10_p_f64(28);
EXECUTE in_title_10_p_f64(29);
EXECUTE in_title_10_p_f64(30);
EXECUTE in_title_10_p_f64(31);
.timer off
.print == case p_f64 join_body_10
.timer on
EXECUTE join_body_10_p_f64(0);
EXECUTE join_body_10_p_f64(1);
EXECUTE join_body_10_p_f64(2);
EXECUTE join_body_10_p_f64(3);
EXECUTE join_body_10_p_f64(4);
EXECUTE join_body_10_p_f64(5);
EXECUTE join_body_10_p_f64(6);
EXECUTE join_body_10_p_f64(7);
EXECUTE join_body_10_p_f64(8);
EXECUTE join_body_10_p_f64(9);
EXECUTE join_body_10_p_f64(10);
EXECUTE join_body_10_p_f64(11);
EXECUTE join_body_10_p_f64(12);
EXECUTE join_body_10_p_f64(13);
EXECUTE join_body_10_p_f64(14);
EXECUTE join_body_10_p_f64(15);
.timer off
.print == case p_f64 join_all3_10
.timer on
EXECUTE join_all3_10_p_f64(0);
EXECUTE join_all3_10_p_f64(1);
EXECUTE join_all3_10_p_f64(2);
EXECUTE join_all3_10_p_f64(3);
EXECUTE join_all3_10_p_f64(4);
EXECUTE join_all3_10_p_f64(5);
EXECUTE join_all3_10_p_f64(6);
EXECUTE join_all3_10_p_f64(7);
EXECUTE join_all3_10_p_f64(8);
EXECUTE join_all3_10_p_f64(9);
EXECUTE join_all3_10_p_f64(10);
EXECUTE join_all3_10_p_f64(11);
EXECUTE join_all3_10_p_f64(12);
EXECUTE join_all3_10_p_f64(13);
EXECUTE join_all3_10_p_f64(14);
EXECUTE join_all3_10_p_f64(15);
.timer off
.print == case p_f64 join_title_100
.timer on
EXECUTE join_title_100_p_f64(0);
EXECUTE join_title_100_p_f64(1);
EXECUTE join_title_100_p_f64(2);
EXECUTE join_title_100_p_f64(3);
EXECUTE join_title_100_p_f64(4);
EXECUTE join_title_100_p_f64(5);
EXECUTE join_title_100_p_f64(6);
EXECUTE join_title_100_p_f64(7);
EXECUTE join_title_100_p_f64(8);
EXECUTE join_title_100_p_f64(9);
EXECUTE join_title_100_p_f64(10);
EXECUTE join_title_100_p_f64(11);
EXECUTE join_title_100_p_f64(12);
EXECUTE join_title_100_p_f64(13);
EXECUTE join_title_100_p_f64(14);
EXECUTE join_title_100_p_f64(15);
.timer off
.print == case p_f64 in_title_100
.timer on
EXECUTE in_title_100_p_f64(16);
EXECUTE in_title_100_p_f64(17);
EXECUTE in_title_100_p_f64(18);
EXECUTE in_title_100_p_f64(19);
EXECUTE in_title_100_p_f64(20);
EXECUTE in_title_100_p_f64(21);
EXECUTE in_title_100_p_f64(22);
EXECUTE in_title_100_p_f64(23);
EXECUTE in_title_100_p_f64(24);
EXECUTE in_title_100_p_f64(25);
EXECUTE in_title_100_p_f64(26);
EXECUTE in_title_100_p_f64(27);
EXECUTE in_title_100_p_f64(28);
EXECUTE in_title_100_p_f64(29);
EXECUTE in_title_100_p_f64(30);
EXECUTE in_title_100_p_f64(31);
.timer off
.print == case p_f64 join_body_100
.timer on
EXECUTE join_body_100_p_f64(0);
EXECUTE join_body_100_p_f64(1);
EXECUTE join_body_100_p_f64(2);
EXECUTE join_body_100_p_f64(3);
EXECUTE join_body_100_p_f64(4);
EXECUTE join_body_100_p_f64(5);
EXECUTE join_body_100_p_f64(6);
EXECUTE join_body_100_p_f64(7);
EXECUTE join_body_100_p_f64(8);
EXECUTE join_body_100_p_f64(9);
EXECUTE join_body_100_p_f64(10);
EXECUTE join_body_100_p_f64(11);
EXECUTE join_body_100_p_f64(12);
EXECUTE join_body_100_p_f64(13);
EXECUTE join_body_100_p_f64(14);
EXECUTE join_body_100_p_f64(15);
.timer off
.print == case p_f64 join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_f64(0);
EXECUTE join_title_10_clustered_p_f64(1);
EXECUTE join_title_10_clustered_p_f64(2);
EXECUTE join_title_10_clustered_p_f64(3);
EXECUTE join_title_10_clustered_p_f64(4);
EXECUTE join_title_10_clustered_p_f64(5);
EXECUTE join_title_10_clustered_p_f64(6);
EXECUTE join_title_10_clustered_p_f64(7);
EXECUTE join_title_10_clustered_p_f64(8);
EXECUTE join_title_10_clustered_p_f64(9);
EXECUTE join_title_10_clustered_p_f64(10);
EXECUTE join_title_10_clustered_p_f64(11);
EXECUTE join_title_10_clustered_p_f64(12);
EXECUTE join_title_10_clustered_p_f64(13);
EXECUTE join_title_10_clustered_p_f64(14);
EXECUTE join_title_10_clustered_p_f64(15);
.timer off
.print == case p_f64 join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_f64(0);
EXECUTE join_title_10_ordered_p_f64(1);
EXECUTE join_title_10_ordered_p_f64(2);
EXECUTE join_title_10_ordered_p_f64(3);
EXECUTE join_title_10_ordered_p_f64(4);
EXECUTE join_title_10_ordered_p_f64(5);
EXECUTE join_title_10_ordered_p_f64(6);
EXECUTE join_title_10_ordered_p_f64(7);
EXECUTE join_title_10_ordered_p_f64(8);
EXECUTE join_title_10_ordered_p_f64(9);
EXECUTE join_title_10_ordered_p_f64(10);
EXECUTE join_title_10_ordered_p_f64(11);
EXECUTE join_title_10_ordered_p_f64(12);
EXECUTE join_title_10_ordered_p_f64(13);
EXECUTE join_title_10_ordered_p_f64(14);
EXECUTE join_title_10_ordered_p_f64(15);
.timer off
.print == case p_f64 join_title_10_same_set
.timer on
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
EXECUTE join_title_10_p_f64(1);
.timer off
.print == plan p_f64 join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_f64(1);
.mode trash
.print == plan p_f64 in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_f64(1);
.mode trash
.print == plan p_f64 join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_f64(1);
.mode trash
.print == plan p_f64 join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_f64(1);
.mode trash
.print == plan p_f64 join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_f64(1);
.mode trash

-- ===== p_ins: 20 INSERT batches (20 files), row_id_start set per file
.print == build p_ins
.timer on
CALL lake.set_option('parquet_row_group_size', 122880);
CREATE TABLE lake.p_ins AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(0) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(0*(getvariable('n')//20), 1*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(1*(getvariable('n')//20), 2*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(2*(getvariable('n')//20), 3*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(3*(getvariable('n')//20), 4*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(4*(getvariable('n')//20), 5*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(5*(getvariable('n')//20), 6*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(6*(getvariable('n')//20), 7*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(7*(getvariable('n')//20), 8*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(8*(getvariable('n')//20), 9*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(9*(getvariable('n')//20), 10*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(10*(getvariable('n')//20), 11*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(11*(getvariable('n')//20), 12*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(12*(getvariable('n')//20), 13*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(13*(getvariable('n')//20), 14*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(14*(getvariable('n')//20), 15*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(15*(getvariable('n')//20), 16*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(16*(getvariable('n')//20), 17*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(17*(getvariable('n')//20), 18*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(18*(getvariable('n')//20), 19*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_ins SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(19*(getvariable('n')//20), 20*(getvariable('n')//20)) r(i);
.timer off
.print == layout p_ins
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_ins');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_ins' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_ins AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE in_title_10_p_ins AS SELECT count(*), max(title) FROM lake.p_ins WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_ins AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_ins AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE join_title_100_p_ins AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE in_title_100_p_ins AS SELECT count(*), max(title) FROM lake.p_ins WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_ins AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_ins AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_ins p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_ins AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_ins p ON p.rowid = h.rowid;
.print == case p_ins join_title_10
.timer on
EXECUTE join_title_10_p_ins(0);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(2);
EXECUTE join_title_10_p_ins(3);
EXECUTE join_title_10_p_ins(4);
EXECUTE join_title_10_p_ins(5);
EXECUTE join_title_10_p_ins(6);
EXECUTE join_title_10_p_ins(7);
EXECUTE join_title_10_p_ins(8);
EXECUTE join_title_10_p_ins(9);
EXECUTE join_title_10_p_ins(10);
EXECUTE join_title_10_p_ins(11);
EXECUTE join_title_10_p_ins(12);
EXECUTE join_title_10_p_ins(13);
EXECUTE join_title_10_p_ins(14);
EXECUTE join_title_10_p_ins(15);
.timer off
.print == case p_ins in_title_10
.timer on
EXECUTE in_title_10_p_ins(16);
EXECUTE in_title_10_p_ins(17);
EXECUTE in_title_10_p_ins(18);
EXECUTE in_title_10_p_ins(19);
EXECUTE in_title_10_p_ins(20);
EXECUTE in_title_10_p_ins(21);
EXECUTE in_title_10_p_ins(22);
EXECUTE in_title_10_p_ins(23);
EXECUTE in_title_10_p_ins(24);
EXECUTE in_title_10_p_ins(25);
EXECUTE in_title_10_p_ins(26);
EXECUTE in_title_10_p_ins(27);
EXECUTE in_title_10_p_ins(28);
EXECUTE in_title_10_p_ins(29);
EXECUTE in_title_10_p_ins(30);
EXECUTE in_title_10_p_ins(31);
.timer off
.print == case p_ins join_body_10
.timer on
EXECUTE join_body_10_p_ins(0);
EXECUTE join_body_10_p_ins(1);
EXECUTE join_body_10_p_ins(2);
EXECUTE join_body_10_p_ins(3);
EXECUTE join_body_10_p_ins(4);
EXECUTE join_body_10_p_ins(5);
EXECUTE join_body_10_p_ins(6);
EXECUTE join_body_10_p_ins(7);
EXECUTE join_body_10_p_ins(8);
EXECUTE join_body_10_p_ins(9);
EXECUTE join_body_10_p_ins(10);
EXECUTE join_body_10_p_ins(11);
EXECUTE join_body_10_p_ins(12);
EXECUTE join_body_10_p_ins(13);
EXECUTE join_body_10_p_ins(14);
EXECUTE join_body_10_p_ins(15);
.timer off
.print == case p_ins join_all3_10
.timer on
EXECUTE join_all3_10_p_ins(0);
EXECUTE join_all3_10_p_ins(1);
EXECUTE join_all3_10_p_ins(2);
EXECUTE join_all3_10_p_ins(3);
EXECUTE join_all3_10_p_ins(4);
EXECUTE join_all3_10_p_ins(5);
EXECUTE join_all3_10_p_ins(6);
EXECUTE join_all3_10_p_ins(7);
EXECUTE join_all3_10_p_ins(8);
EXECUTE join_all3_10_p_ins(9);
EXECUTE join_all3_10_p_ins(10);
EXECUTE join_all3_10_p_ins(11);
EXECUTE join_all3_10_p_ins(12);
EXECUTE join_all3_10_p_ins(13);
EXECUTE join_all3_10_p_ins(14);
EXECUTE join_all3_10_p_ins(15);
.timer off
.print == case p_ins join_title_100
.timer on
EXECUTE join_title_100_p_ins(0);
EXECUTE join_title_100_p_ins(1);
EXECUTE join_title_100_p_ins(2);
EXECUTE join_title_100_p_ins(3);
EXECUTE join_title_100_p_ins(4);
EXECUTE join_title_100_p_ins(5);
EXECUTE join_title_100_p_ins(6);
EXECUTE join_title_100_p_ins(7);
EXECUTE join_title_100_p_ins(8);
EXECUTE join_title_100_p_ins(9);
EXECUTE join_title_100_p_ins(10);
EXECUTE join_title_100_p_ins(11);
EXECUTE join_title_100_p_ins(12);
EXECUTE join_title_100_p_ins(13);
EXECUTE join_title_100_p_ins(14);
EXECUTE join_title_100_p_ins(15);
.timer off
.print == case p_ins in_title_100
.timer on
EXECUTE in_title_100_p_ins(16);
EXECUTE in_title_100_p_ins(17);
EXECUTE in_title_100_p_ins(18);
EXECUTE in_title_100_p_ins(19);
EXECUTE in_title_100_p_ins(20);
EXECUTE in_title_100_p_ins(21);
EXECUTE in_title_100_p_ins(22);
EXECUTE in_title_100_p_ins(23);
EXECUTE in_title_100_p_ins(24);
EXECUTE in_title_100_p_ins(25);
EXECUTE in_title_100_p_ins(26);
EXECUTE in_title_100_p_ins(27);
EXECUTE in_title_100_p_ins(28);
EXECUTE in_title_100_p_ins(29);
EXECUTE in_title_100_p_ins(30);
EXECUTE in_title_100_p_ins(31);
.timer off
.print == case p_ins join_body_100
.timer on
EXECUTE join_body_100_p_ins(0);
EXECUTE join_body_100_p_ins(1);
EXECUTE join_body_100_p_ins(2);
EXECUTE join_body_100_p_ins(3);
EXECUTE join_body_100_p_ins(4);
EXECUTE join_body_100_p_ins(5);
EXECUTE join_body_100_p_ins(6);
EXECUTE join_body_100_p_ins(7);
EXECUTE join_body_100_p_ins(8);
EXECUTE join_body_100_p_ins(9);
EXECUTE join_body_100_p_ins(10);
EXECUTE join_body_100_p_ins(11);
EXECUTE join_body_100_p_ins(12);
EXECUTE join_body_100_p_ins(13);
EXECUTE join_body_100_p_ins(14);
EXECUTE join_body_100_p_ins(15);
.timer off
.print == case p_ins join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_ins(0);
EXECUTE join_title_10_clustered_p_ins(1);
EXECUTE join_title_10_clustered_p_ins(2);
EXECUTE join_title_10_clustered_p_ins(3);
EXECUTE join_title_10_clustered_p_ins(4);
EXECUTE join_title_10_clustered_p_ins(5);
EXECUTE join_title_10_clustered_p_ins(6);
EXECUTE join_title_10_clustered_p_ins(7);
EXECUTE join_title_10_clustered_p_ins(8);
EXECUTE join_title_10_clustered_p_ins(9);
EXECUTE join_title_10_clustered_p_ins(10);
EXECUTE join_title_10_clustered_p_ins(11);
EXECUTE join_title_10_clustered_p_ins(12);
EXECUTE join_title_10_clustered_p_ins(13);
EXECUTE join_title_10_clustered_p_ins(14);
EXECUTE join_title_10_clustered_p_ins(15);
.timer off
.print == case p_ins join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_ins(0);
EXECUTE join_title_10_ordered_p_ins(1);
EXECUTE join_title_10_ordered_p_ins(2);
EXECUTE join_title_10_ordered_p_ins(3);
EXECUTE join_title_10_ordered_p_ins(4);
EXECUTE join_title_10_ordered_p_ins(5);
EXECUTE join_title_10_ordered_p_ins(6);
EXECUTE join_title_10_ordered_p_ins(7);
EXECUTE join_title_10_ordered_p_ins(8);
EXECUTE join_title_10_ordered_p_ins(9);
EXECUTE join_title_10_ordered_p_ins(10);
EXECUTE join_title_10_ordered_p_ins(11);
EXECUTE join_title_10_ordered_p_ins(12);
EXECUTE join_title_10_ordered_p_ins(13);
EXECUTE join_title_10_ordered_p_ins(14);
EXECUTE join_title_10_ordered_p_ins(15);
.timer off
.print == case p_ins join_title_10_same_set
.timer on
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
EXECUTE join_title_10_p_ins(1);
.timer off
.print == plan p_ins join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_ins(1);
.mode trash
.print == plan p_ins in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_ins(1);
.mode trash
.print == plan p_ins join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_ins(1);
.mode trash
.print == plan p_ins join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_ins(1);
.mode trash
.print == plan p_ins join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_ins(1);
.mode trash

-- ===== p_merged: 20 INSERT batches then ducklake_merge_adjacent_files (row ids embedded)
.print == build p_merged
.timer on
CALL lake.set_option('parquet_row_group_size', 122880);
CREATE TABLE lake.p_merged AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(0) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(0*(getvariable('n')//20), 1*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(1*(getvariable('n')//20), 2*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(2*(getvariable('n')//20), 3*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(3*(getvariable('n')//20), 4*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(4*(getvariable('n')//20), 5*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(5*(getvariable('n')//20), 6*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(6*(getvariable('n')//20), 7*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(7*(getvariable('n')//20), 8*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(8*(getvariable('n')//20), 9*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(9*(getvariable('n')//20), 10*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(10*(getvariable('n')//20), 11*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(11*(getvariable('n')//20), 12*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(12*(getvariable('n')//20), 13*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(13*(getvariable('n')//20), 14*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(14*(getvariable('n')//20), 15*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(15*(getvariable('n')//20), 16*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(16*(getvariable('n')//20), 17*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(17*(getvariable('n')//20), 18*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(18*(getvariable('n')//20), 19*(getvariable('n')//20)) r(i);
INSERT INTO lake.p_merged SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(19*(getvariable('n')//20), 20*(getvariable('n')//20)) r(i);
CALL ducklake_merge_adjacent_files('lake', 'p_merged');
.timer off
.print == layout p_merged
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_merged');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_merged' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_merged AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE in_title_10_p_merged AS SELECT count(*), max(title) FROM lake.p_merged WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_merged AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_merged AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE join_title_100_p_merged AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE in_title_100_p_merged AS SELECT count(*), max(title) FROM lake.p_merged WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_merged AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_merged AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_merged p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_merged AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_merged p ON p.rowid = h.rowid;
.print == case p_merged join_title_10
.timer on
EXECUTE join_title_10_p_merged(0);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(2);
EXECUTE join_title_10_p_merged(3);
EXECUTE join_title_10_p_merged(4);
EXECUTE join_title_10_p_merged(5);
EXECUTE join_title_10_p_merged(6);
EXECUTE join_title_10_p_merged(7);
EXECUTE join_title_10_p_merged(8);
EXECUTE join_title_10_p_merged(9);
EXECUTE join_title_10_p_merged(10);
EXECUTE join_title_10_p_merged(11);
EXECUTE join_title_10_p_merged(12);
EXECUTE join_title_10_p_merged(13);
EXECUTE join_title_10_p_merged(14);
EXECUTE join_title_10_p_merged(15);
.timer off
.print == case p_merged in_title_10
.timer on
EXECUTE in_title_10_p_merged(16);
EXECUTE in_title_10_p_merged(17);
EXECUTE in_title_10_p_merged(18);
EXECUTE in_title_10_p_merged(19);
EXECUTE in_title_10_p_merged(20);
EXECUTE in_title_10_p_merged(21);
EXECUTE in_title_10_p_merged(22);
EXECUTE in_title_10_p_merged(23);
EXECUTE in_title_10_p_merged(24);
EXECUTE in_title_10_p_merged(25);
EXECUTE in_title_10_p_merged(26);
EXECUTE in_title_10_p_merged(27);
EXECUTE in_title_10_p_merged(28);
EXECUTE in_title_10_p_merged(29);
EXECUTE in_title_10_p_merged(30);
EXECUTE in_title_10_p_merged(31);
.timer off
.print == case p_merged join_body_10
.timer on
EXECUTE join_body_10_p_merged(0);
EXECUTE join_body_10_p_merged(1);
EXECUTE join_body_10_p_merged(2);
EXECUTE join_body_10_p_merged(3);
EXECUTE join_body_10_p_merged(4);
EXECUTE join_body_10_p_merged(5);
EXECUTE join_body_10_p_merged(6);
EXECUTE join_body_10_p_merged(7);
EXECUTE join_body_10_p_merged(8);
EXECUTE join_body_10_p_merged(9);
EXECUTE join_body_10_p_merged(10);
EXECUTE join_body_10_p_merged(11);
EXECUTE join_body_10_p_merged(12);
EXECUTE join_body_10_p_merged(13);
EXECUTE join_body_10_p_merged(14);
EXECUTE join_body_10_p_merged(15);
.timer off
.print == case p_merged join_all3_10
.timer on
EXECUTE join_all3_10_p_merged(0);
EXECUTE join_all3_10_p_merged(1);
EXECUTE join_all3_10_p_merged(2);
EXECUTE join_all3_10_p_merged(3);
EXECUTE join_all3_10_p_merged(4);
EXECUTE join_all3_10_p_merged(5);
EXECUTE join_all3_10_p_merged(6);
EXECUTE join_all3_10_p_merged(7);
EXECUTE join_all3_10_p_merged(8);
EXECUTE join_all3_10_p_merged(9);
EXECUTE join_all3_10_p_merged(10);
EXECUTE join_all3_10_p_merged(11);
EXECUTE join_all3_10_p_merged(12);
EXECUTE join_all3_10_p_merged(13);
EXECUTE join_all3_10_p_merged(14);
EXECUTE join_all3_10_p_merged(15);
.timer off
.print == case p_merged join_title_100
.timer on
EXECUTE join_title_100_p_merged(0);
EXECUTE join_title_100_p_merged(1);
EXECUTE join_title_100_p_merged(2);
EXECUTE join_title_100_p_merged(3);
EXECUTE join_title_100_p_merged(4);
EXECUTE join_title_100_p_merged(5);
EXECUTE join_title_100_p_merged(6);
EXECUTE join_title_100_p_merged(7);
EXECUTE join_title_100_p_merged(8);
EXECUTE join_title_100_p_merged(9);
EXECUTE join_title_100_p_merged(10);
EXECUTE join_title_100_p_merged(11);
EXECUTE join_title_100_p_merged(12);
EXECUTE join_title_100_p_merged(13);
EXECUTE join_title_100_p_merged(14);
EXECUTE join_title_100_p_merged(15);
.timer off
.print == case p_merged in_title_100
.timer on
EXECUTE in_title_100_p_merged(16);
EXECUTE in_title_100_p_merged(17);
EXECUTE in_title_100_p_merged(18);
EXECUTE in_title_100_p_merged(19);
EXECUTE in_title_100_p_merged(20);
EXECUTE in_title_100_p_merged(21);
EXECUTE in_title_100_p_merged(22);
EXECUTE in_title_100_p_merged(23);
EXECUTE in_title_100_p_merged(24);
EXECUTE in_title_100_p_merged(25);
EXECUTE in_title_100_p_merged(26);
EXECUTE in_title_100_p_merged(27);
EXECUTE in_title_100_p_merged(28);
EXECUTE in_title_100_p_merged(29);
EXECUTE in_title_100_p_merged(30);
EXECUTE in_title_100_p_merged(31);
.timer off
.print == case p_merged join_body_100
.timer on
EXECUTE join_body_100_p_merged(0);
EXECUTE join_body_100_p_merged(1);
EXECUTE join_body_100_p_merged(2);
EXECUTE join_body_100_p_merged(3);
EXECUTE join_body_100_p_merged(4);
EXECUTE join_body_100_p_merged(5);
EXECUTE join_body_100_p_merged(6);
EXECUTE join_body_100_p_merged(7);
EXECUTE join_body_100_p_merged(8);
EXECUTE join_body_100_p_merged(9);
EXECUTE join_body_100_p_merged(10);
EXECUTE join_body_100_p_merged(11);
EXECUTE join_body_100_p_merged(12);
EXECUTE join_body_100_p_merged(13);
EXECUTE join_body_100_p_merged(14);
EXECUTE join_body_100_p_merged(15);
.timer off
.print == case p_merged join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_merged(0);
EXECUTE join_title_10_clustered_p_merged(1);
EXECUTE join_title_10_clustered_p_merged(2);
EXECUTE join_title_10_clustered_p_merged(3);
EXECUTE join_title_10_clustered_p_merged(4);
EXECUTE join_title_10_clustered_p_merged(5);
EXECUTE join_title_10_clustered_p_merged(6);
EXECUTE join_title_10_clustered_p_merged(7);
EXECUTE join_title_10_clustered_p_merged(8);
EXECUTE join_title_10_clustered_p_merged(9);
EXECUTE join_title_10_clustered_p_merged(10);
EXECUTE join_title_10_clustered_p_merged(11);
EXECUTE join_title_10_clustered_p_merged(12);
EXECUTE join_title_10_clustered_p_merged(13);
EXECUTE join_title_10_clustered_p_merged(14);
EXECUTE join_title_10_clustered_p_merged(15);
.timer off
.print == case p_merged join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_merged(0);
EXECUTE join_title_10_ordered_p_merged(1);
EXECUTE join_title_10_ordered_p_merged(2);
EXECUTE join_title_10_ordered_p_merged(3);
EXECUTE join_title_10_ordered_p_merged(4);
EXECUTE join_title_10_ordered_p_merged(5);
EXECUTE join_title_10_ordered_p_merged(6);
EXECUTE join_title_10_ordered_p_merged(7);
EXECUTE join_title_10_ordered_p_merged(8);
EXECUTE join_title_10_ordered_p_merged(9);
EXECUTE join_title_10_ordered_p_merged(10);
EXECUTE join_title_10_ordered_p_merged(11);
EXECUTE join_title_10_ordered_p_merged(12);
EXECUTE join_title_10_ordered_p_merged(13);
EXECUTE join_title_10_ordered_p_merged(14);
EXECUTE join_title_10_ordered_p_merged(15);
.timer off
.print == case p_merged join_title_10_same_set
.timer on
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
EXECUTE join_title_10_p_merged(1);
.timer off
.print == plan p_merged join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_merged(1);
.mode trash
.print == plan p_merged in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_merged(1);
.mode trash
.print == plan p_merged join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_merged(1);
.mode trash
.print == plan p_merged join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_merged(1);
.mode trash
.print == plan p_merged join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_merged(1);
.mode trash

-- ===== p_rewritten: 1% deleted, ducklake_rewrite_data_files, merged: row_id_start NULL
.print == build p_rewritten
.timer on
CALL lake.set_option('parquet_row_group_size', 122880);
CREATE TABLE lake.p_rewritten AS SELECT i AS id, repeat(md5(i::VARCHAR), 20) AS body, md5(i::VARCHAR) AS title FROM range(getvariable('n')) r(i);
DELETE FROM lake.p_rewritten WHERE id % 100 = 0;
CALL ducklake_rewrite_data_files('lake', 'p_rewritten', delete_threshold => 0.001);
CALL ducklake_merge_adjacent_files('lake', 'p_rewritten');
.timer off
.print == layout p_rewritten
.mode duckbox
SELECT count(*) AS files, sum(data_file_size_bytes) AS bytes FROM ducklake_list_files('lake', 'p_rewritten');
SELECT count(*) AS files_with_row_id_start FROM __ducklake_metadata_lake.ducklake_data_file f JOIN __ducklake_metadata_lake.ducklake_table t USING (table_id) WHERE t.table_name = 'p_rewritten' AND f.end_snapshot IS NULL AND f.row_id_start IS NOT NULL;
.mode trash
PREPARE join_title_10_p_rewritten AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE in_title_10_p_rewritten AS SELECT count(*), max(title) FROM lake.p_rewritten WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 10 AND k = $1);
PREPARE join_body_10_p_rewritten AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE join_all3_10_p_rewritten AS SELECT count(*), max(p.id), max(length(p.body)), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE join_title_100_p_rewritten AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE in_title_100_p_rewritten AS SELECT count(*), max(title) FROM lake.p_rewritten WHERE rowid IN (SELECT rowid FROM hits WHERE sz = 100 AND k = $1);
PREPARE join_body_100_p_rewritten AS SELECT count(*), max(length(p.body)) FROM (SELECT rowid FROM hits WHERE sz = 100 AND k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE join_title_10_clustered_p_rewritten AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits_c WHERE k = $1) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
PREPARE join_title_10_ordered_p_rewritten AS SELECT count(*), max(p.title) FROM (SELECT rowid FROM hits WHERE sz = 10 AND k = $1 ORDER BY rowid) h JOIN lake.p_rewritten p ON p.rowid = h.rowid;
.print == case p_rewritten join_title_10
.timer on
EXECUTE join_title_10_p_rewritten(0);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(2);
EXECUTE join_title_10_p_rewritten(3);
EXECUTE join_title_10_p_rewritten(4);
EXECUTE join_title_10_p_rewritten(5);
EXECUTE join_title_10_p_rewritten(6);
EXECUTE join_title_10_p_rewritten(7);
EXECUTE join_title_10_p_rewritten(8);
EXECUTE join_title_10_p_rewritten(9);
EXECUTE join_title_10_p_rewritten(10);
EXECUTE join_title_10_p_rewritten(11);
EXECUTE join_title_10_p_rewritten(12);
EXECUTE join_title_10_p_rewritten(13);
EXECUTE join_title_10_p_rewritten(14);
EXECUTE join_title_10_p_rewritten(15);
.timer off
.print == case p_rewritten in_title_10
.timer on
EXECUTE in_title_10_p_rewritten(16);
EXECUTE in_title_10_p_rewritten(17);
EXECUTE in_title_10_p_rewritten(18);
EXECUTE in_title_10_p_rewritten(19);
EXECUTE in_title_10_p_rewritten(20);
EXECUTE in_title_10_p_rewritten(21);
EXECUTE in_title_10_p_rewritten(22);
EXECUTE in_title_10_p_rewritten(23);
EXECUTE in_title_10_p_rewritten(24);
EXECUTE in_title_10_p_rewritten(25);
EXECUTE in_title_10_p_rewritten(26);
EXECUTE in_title_10_p_rewritten(27);
EXECUTE in_title_10_p_rewritten(28);
EXECUTE in_title_10_p_rewritten(29);
EXECUTE in_title_10_p_rewritten(30);
EXECUTE in_title_10_p_rewritten(31);
.timer off
.print == case p_rewritten join_body_10
.timer on
EXECUTE join_body_10_p_rewritten(0);
EXECUTE join_body_10_p_rewritten(1);
EXECUTE join_body_10_p_rewritten(2);
EXECUTE join_body_10_p_rewritten(3);
EXECUTE join_body_10_p_rewritten(4);
EXECUTE join_body_10_p_rewritten(5);
EXECUTE join_body_10_p_rewritten(6);
EXECUTE join_body_10_p_rewritten(7);
EXECUTE join_body_10_p_rewritten(8);
EXECUTE join_body_10_p_rewritten(9);
EXECUTE join_body_10_p_rewritten(10);
EXECUTE join_body_10_p_rewritten(11);
EXECUTE join_body_10_p_rewritten(12);
EXECUTE join_body_10_p_rewritten(13);
EXECUTE join_body_10_p_rewritten(14);
EXECUTE join_body_10_p_rewritten(15);
.timer off
.print == case p_rewritten join_all3_10
.timer on
EXECUTE join_all3_10_p_rewritten(0);
EXECUTE join_all3_10_p_rewritten(1);
EXECUTE join_all3_10_p_rewritten(2);
EXECUTE join_all3_10_p_rewritten(3);
EXECUTE join_all3_10_p_rewritten(4);
EXECUTE join_all3_10_p_rewritten(5);
EXECUTE join_all3_10_p_rewritten(6);
EXECUTE join_all3_10_p_rewritten(7);
EXECUTE join_all3_10_p_rewritten(8);
EXECUTE join_all3_10_p_rewritten(9);
EXECUTE join_all3_10_p_rewritten(10);
EXECUTE join_all3_10_p_rewritten(11);
EXECUTE join_all3_10_p_rewritten(12);
EXECUTE join_all3_10_p_rewritten(13);
EXECUTE join_all3_10_p_rewritten(14);
EXECUTE join_all3_10_p_rewritten(15);
.timer off
.print == case p_rewritten join_title_100
.timer on
EXECUTE join_title_100_p_rewritten(0);
EXECUTE join_title_100_p_rewritten(1);
EXECUTE join_title_100_p_rewritten(2);
EXECUTE join_title_100_p_rewritten(3);
EXECUTE join_title_100_p_rewritten(4);
EXECUTE join_title_100_p_rewritten(5);
EXECUTE join_title_100_p_rewritten(6);
EXECUTE join_title_100_p_rewritten(7);
EXECUTE join_title_100_p_rewritten(8);
EXECUTE join_title_100_p_rewritten(9);
EXECUTE join_title_100_p_rewritten(10);
EXECUTE join_title_100_p_rewritten(11);
EXECUTE join_title_100_p_rewritten(12);
EXECUTE join_title_100_p_rewritten(13);
EXECUTE join_title_100_p_rewritten(14);
EXECUTE join_title_100_p_rewritten(15);
.timer off
.print == case p_rewritten in_title_100
.timer on
EXECUTE in_title_100_p_rewritten(16);
EXECUTE in_title_100_p_rewritten(17);
EXECUTE in_title_100_p_rewritten(18);
EXECUTE in_title_100_p_rewritten(19);
EXECUTE in_title_100_p_rewritten(20);
EXECUTE in_title_100_p_rewritten(21);
EXECUTE in_title_100_p_rewritten(22);
EXECUTE in_title_100_p_rewritten(23);
EXECUTE in_title_100_p_rewritten(24);
EXECUTE in_title_100_p_rewritten(25);
EXECUTE in_title_100_p_rewritten(26);
EXECUTE in_title_100_p_rewritten(27);
EXECUTE in_title_100_p_rewritten(28);
EXECUTE in_title_100_p_rewritten(29);
EXECUTE in_title_100_p_rewritten(30);
EXECUTE in_title_100_p_rewritten(31);
.timer off
.print == case p_rewritten join_body_100
.timer on
EXECUTE join_body_100_p_rewritten(0);
EXECUTE join_body_100_p_rewritten(1);
EXECUTE join_body_100_p_rewritten(2);
EXECUTE join_body_100_p_rewritten(3);
EXECUTE join_body_100_p_rewritten(4);
EXECUTE join_body_100_p_rewritten(5);
EXECUTE join_body_100_p_rewritten(6);
EXECUTE join_body_100_p_rewritten(7);
EXECUTE join_body_100_p_rewritten(8);
EXECUTE join_body_100_p_rewritten(9);
EXECUTE join_body_100_p_rewritten(10);
EXECUTE join_body_100_p_rewritten(11);
EXECUTE join_body_100_p_rewritten(12);
EXECUTE join_body_100_p_rewritten(13);
EXECUTE join_body_100_p_rewritten(14);
EXECUTE join_body_100_p_rewritten(15);
.timer off
.print == case p_rewritten join_title_10_clustered
.timer on
EXECUTE join_title_10_clustered_p_rewritten(0);
EXECUTE join_title_10_clustered_p_rewritten(1);
EXECUTE join_title_10_clustered_p_rewritten(2);
EXECUTE join_title_10_clustered_p_rewritten(3);
EXECUTE join_title_10_clustered_p_rewritten(4);
EXECUTE join_title_10_clustered_p_rewritten(5);
EXECUTE join_title_10_clustered_p_rewritten(6);
EXECUTE join_title_10_clustered_p_rewritten(7);
EXECUTE join_title_10_clustered_p_rewritten(8);
EXECUTE join_title_10_clustered_p_rewritten(9);
EXECUTE join_title_10_clustered_p_rewritten(10);
EXECUTE join_title_10_clustered_p_rewritten(11);
EXECUTE join_title_10_clustered_p_rewritten(12);
EXECUTE join_title_10_clustered_p_rewritten(13);
EXECUTE join_title_10_clustered_p_rewritten(14);
EXECUTE join_title_10_clustered_p_rewritten(15);
.timer off
.print == case p_rewritten join_title_10_ordered
.timer on
EXECUTE join_title_10_ordered_p_rewritten(0);
EXECUTE join_title_10_ordered_p_rewritten(1);
EXECUTE join_title_10_ordered_p_rewritten(2);
EXECUTE join_title_10_ordered_p_rewritten(3);
EXECUTE join_title_10_ordered_p_rewritten(4);
EXECUTE join_title_10_ordered_p_rewritten(5);
EXECUTE join_title_10_ordered_p_rewritten(6);
EXECUTE join_title_10_ordered_p_rewritten(7);
EXECUTE join_title_10_ordered_p_rewritten(8);
EXECUTE join_title_10_ordered_p_rewritten(9);
EXECUTE join_title_10_ordered_p_rewritten(10);
EXECUTE join_title_10_ordered_p_rewritten(11);
EXECUTE join_title_10_ordered_p_rewritten(12);
EXECUTE join_title_10_ordered_p_rewritten(13);
EXECUTE join_title_10_ordered_p_rewritten(14);
EXECUTE join_title_10_ordered_p_rewritten(15);
.timer off
.print == case p_rewritten join_title_10_same_set
.timer on
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
EXECUTE join_title_10_p_rewritten(1);
.timer off
.print == plan p_rewritten join_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_p_rewritten(1);
.mode trash
.print == plan p_rewritten in_title_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE in_title_10_p_rewritten(1);
.mode trash
.print == plan p_rewritten join_body_10
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_body_10_p_rewritten(1);
.mode trash
.print == plan p_rewritten join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rewritten(1);
.mode trash
.print == plan p_rewritten join_title_10_clustered
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_10_clustered_p_rewritten(1);
.mode trash

-- ===== lever: threads, on p_rg16k
SET threads = 1;
.print == case threads1 join_title_10
.timer on
EXECUTE join_title_10_p_rg16k(0);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(2);
EXECUTE join_title_10_p_rg16k(3);
EXECUTE join_title_10_p_rg16k(4);
EXECUTE join_title_10_p_rg16k(5);
EXECUTE join_title_10_p_rg16k(6);
EXECUTE join_title_10_p_rg16k(7);
EXECUTE join_title_10_p_rg16k(8);
EXECUTE join_title_10_p_rg16k(9);
EXECUTE join_title_10_p_rg16k(10);
EXECUTE join_title_10_p_rg16k(11);
EXECUTE join_title_10_p_rg16k(12);
EXECUTE join_title_10_p_rg16k(13);
EXECUTE join_title_10_p_rg16k(14);
EXECUTE join_title_10_p_rg16k(15);
.timer off
.print == case threads1 join_title_100
.timer on
EXECUTE join_title_100_p_rg16k(0);
EXECUTE join_title_100_p_rg16k(1);
EXECUTE join_title_100_p_rg16k(2);
EXECUTE join_title_100_p_rg16k(3);
EXECUTE join_title_100_p_rg16k(4);
EXECUTE join_title_100_p_rg16k(5);
EXECUTE join_title_100_p_rg16k(6);
EXECUTE join_title_100_p_rg16k(7);
EXECUTE join_title_100_p_rg16k(8);
EXECUTE join_title_100_p_rg16k(9);
EXECUTE join_title_100_p_rg16k(10);
EXECUTE join_title_100_p_rg16k(11);
EXECUTE join_title_100_p_rg16k(12);
EXECUTE join_title_100_p_rg16k(13);
EXECUTE join_title_100_p_rg16k(14);
EXECUTE join_title_100_p_rg16k(15);
.timer off
.print == case threads1 join_body_100
.timer on
EXECUTE join_body_100_p_rg16k(0);
EXECUTE join_body_100_p_rg16k(1);
EXECUTE join_body_100_p_rg16k(2);
EXECUTE join_body_100_p_rg16k(3);
EXECUTE join_body_100_p_rg16k(4);
EXECUTE join_body_100_p_rg16k(5);
EXECUTE join_body_100_p_rg16k(6);
EXECUTE join_body_100_p_rg16k(7);
EXECUTE join_body_100_p_rg16k(8);
EXECUTE join_body_100_p_rg16k(9);
EXECUTE join_body_100_p_rg16k(10);
EXECUTE join_body_100_p_rg16k(11);
EXECUTE join_body_100_p_rg16k(12);
EXECUTE join_body_100_p_rg16k(13);
EXECUTE join_body_100_p_rg16k(14);
EXECUTE join_body_100_p_rg16k(15);
.timer off
SET threads = 4;
.print == case threads4 join_title_10
.timer on
EXECUTE join_title_10_p_rg16k(0);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(2);
EXECUTE join_title_10_p_rg16k(3);
EXECUTE join_title_10_p_rg16k(4);
EXECUTE join_title_10_p_rg16k(5);
EXECUTE join_title_10_p_rg16k(6);
EXECUTE join_title_10_p_rg16k(7);
EXECUTE join_title_10_p_rg16k(8);
EXECUTE join_title_10_p_rg16k(9);
EXECUTE join_title_10_p_rg16k(10);
EXECUTE join_title_10_p_rg16k(11);
EXECUTE join_title_10_p_rg16k(12);
EXECUTE join_title_10_p_rg16k(13);
EXECUTE join_title_10_p_rg16k(14);
EXECUTE join_title_10_p_rg16k(15);
.timer off
.print == case threads4 join_title_100
.timer on
EXECUTE join_title_100_p_rg16k(0);
EXECUTE join_title_100_p_rg16k(1);
EXECUTE join_title_100_p_rg16k(2);
EXECUTE join_title_100_p_rg16k(3);
EXECUTE join_title_100_p_rg16k(4);
EXECUTE join_title_100_p_rg16k(5);
EXECUTE join_title_100_p_rg16k(6);
EXECUTE join_title_100_p_rg16k(7);
EXECUTE join_title_100_p_rg16k(8);
EXECUTE join_title_100_p_rg16k(9);
EXECUTE join_title_100_p_rg16k(10);
EXECUTE join_title_100_p_rg16k(11);
EXECUTE join_title_100_p_rg16k(12);
EXECUTE join_title_100_p_rg16k(13);
EXECUTE join_title_100_p_rg16k(14);
EXECUTE join_title_100_p_rg16k(15);
.timer off
.print == case threads4 join_body_100
.timer on
EXECUTE join_body_100_p_rg16k(0);
EXECUTE join_body_100_p_rg16k(1);
EXECUTE join_body_100_p_rg16k(2);
EXECUTE join_body_100_p_rg16k(3);
EXECUTE join_body_100_p_rg16k(4);
EXECUTE join_body_100_p_rg16k(5);
EXECUTE join_body_100_p_rg16k(6);
EXECUTE join_body_100_p_rg16k(7);
EXECUTE join_body_100_p_rg16k(8);
EXECUTE join_body_100_p_rg16k(9);
EXECUTE join_body_100_p_rg16k(10);
EXECUTE join_body_100_p_rg16k(11);
EXECUTE join_body_100_p_rg16k(12);
EXECUTE join_body_100_p_rg16k(13);
EXECUTE join_body_100_p_rg16k(14);
EXECUTE join_body_100_p_rg16k(15);
.timer off
SET threads = 8;
.print == case threads8 join_title_10
.timer on
EXECUTE join_title_10_p_rg16k(0);
EXECUTE join_title_10_p_rg16k(1);
EXECUTE join_title_10_p_rg16k(2);
EXECUTE join_title_10_p_rg16k(3);
EXECUTE join_title_10_p_rg16k(4);
EXECUTE join_title_10_p_rg16k(5);
EXECUTE join_title_10_p_rg16k(6);
EXECUTE join_title_10_p_rg16k(7);
EXECUTE join_title_10_p_rg16k(8);
EXECUTE join_title_10_p_rg16k(9);
EXECUTE join_title_10_p_rg16k(10);
EXECUTE join_title_10_p_rg16k(11);
EXECUTE join_title_10_p_rg16k(12);
EXECUTE join_title_10_p_rg16k(13);
EXECUTE join_title_10_p_rg16k(14);
EXECUTE join_title_10_p_rg16k(15);
.timer off
.print == case threads8 join_title_100
.timer on
EXECUTE join_title_100_p_rg16k(0);
EXECUTE join_title_100_p_rg16k(1);
EXECUTE join_title_100_p_rg16k(2);
EXECUTE join_title_100_p_rg16k(3);
EXECUTE join_title_100_p_rg16k(4);
EXECUTE join_title_100_p_rg16k(5);
EXECUTE join_title_100_p_rg16k(6);
EXECUTE join_title_100_p_rg16k(7);
EXECUTE join_title_100_p_rg16k(8);
EXECUTE join_title_100_p_rg16k(9);
EXECUTE join_title_100_p_rg16k(10);
EXECUTE join_title_100_p_rg16k(11);
EXECUTE join_title_100_p_rg16k(12);
EXECUTE join_title_100_p_rg16k(13);
EXECUTE join_title_100_p_rg16k(14);
EXECUTE join_title_100_p_rg16k(15);
.timer off
.print == case threads8 join_body_100
.timer on
EXECUTE join_body_100_p_rg16k(0);
EXECUTE join_body_100_p_rg16k(1);
EXECUTE join_body_100_p_rg16k(2);
EXECUTE join_body_100_p_rg16k(3);
EXECUTE join_body_100_p_rg16k(4);
EXECUTE join_body_100_p_rg16k(5);
EXECUTE join_body_100_p_rg16k(6);
EXECUTE join_body_100_p_rg16k(7);
EXECUTE join_body_100_p_rg16k(8);
EXECUTE join_body_100_p_rg16k(9);
EXECUTE join_body_100_p_rg16k(10);
EXECUTE join_body_100_p_rg16k(11);
EXECUTE join_body_100_p_rg16k(12);
EXECUTE join_body_100_p_rg16k(13);
EXECUTE join_body_100_p_rg16k(14);
EXECUTE join_body_100_p_rg16k(15);
.timer off
RESET threads;

-- ===== lever: dynamic_or_filter_threshold (default 50): a join pushes at most that many build values as an IN list
SET dynamic_or_filter_threshold = 200;
.print == case thr200 p join_title_100
.timer on
EXECUTE join_title_100_p(0);
EXECUTE join_title_100_p(1);
EXECUTE join_title_100_p(2);
EXECUTE join_title_100_p(3);
EXECUTE join_title_100_p(4);
EXECUTE join_title_100_p(5);
EXECUTE join_title_100_p(6);
EXECUTE join_title_100_p(7);
EXECUTE join_title_100_p(8);
EXECUTE join_title_100_p(9);
EXECUTE join_title_100_p(10);
EXECUTE join_title_100_p(11);
EXECUTE join_title_100_p(12);
EXECUTE join_title_100_p(13);
EXECUTE join_title_100_p(14);
EXECUTE join_title_100_p(15);
.timer off
.print == case thr200 p in_title_100
.timer on
EXECUTE in_title_100_p(16);
EXECUTE in_title_100_p(17);
EXECUTE in_title_100_p(18);
EXECUTE in_title_100_p(19);
EXECUTE in_title_100_p(20);
EXECUTE in_title_100_p(21);
EXECUTE in_title_100_p(22);
EXECUTE in_title_100_p(23);
EXECUTE in_title_100_p(24);
EXECUTE in_title_100_p(25);
EXECUTE in_title_100_p(26);
EXECUTE in_title_100_p(27);
EXECUTE in_title_100_p(28);
EXECUTE in_title_100_p(29);
EXECUTE in_title_100_p(30);
EXECUTE in_title_100_p(31);
.timer off
.print == case thr200 p join_body_100
.timer on
EXECUTE join_body_100_p(0);
EXECUTE join_body_100_p(1);
EXECUTE join_body_100_p(2);
EXECUTE join_body_100_p(3);
EXECUTE join_body_100_p(4);
EXECUTE join_body_100_p(5);
EXECUTE join_body_100_p(6);
EXECUTE join_body_100_p(7);
EXECUTE join_body_100_p(8);
EXECUTE join_body_100_p(9);
EXECUTE join_body_100_p(10);
EXECUTE join_body_100_p(11);
EXECUTE join_body_100_p(12);
EXECUTE join_body_100_p(13);
EXECUTE join_body_100_p(14);
EXECUTE join_body_100_p(15);
.timer off
.print == plan thr200 p join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p(1);
.mode trash
.print == case thr200 p_rg16k join_title_100
.timer on
EXECUTE join_title_100_p_rg16k(0);
EXECUTE join_title_100_p_rg16k(1);
EXECUTE join_title_100_p_rg16k(2);
EXECUTE join_title_100_p_rg16k(3);
EXECUTE join_title_100_p_rg16k(4);
EXECUTE join_title_100_p_rg16k(5);
EXECUTE join_title_100_p_rg16k(6);
EXECUTE join_title_100_p_rg16k(7);
EXECUTE join_title_100_p_rg16k(8);
EXECUTE join_title_100_p_rg16k(9);
EXECUTE join_title_100_p_rg16k(10);
EXECUTE join_title_100_p_rg16k(11);
EXECUTE join_title_100_p_rg16k(12);
EXECUTE join_title_100_p_rg16k(13);
EXECUTE join_title_100_p_rg16k(14);
EXECUTE join_title_100_p_rg16k(15);
.timer off
.print == case thr200 p_rg16k in_title_100
.timer on
EXECUTE in_title_100_p_rg16k(16);
EXECUTE in_title_100_p_rg16k(17);
EXECUTE in_title_100_p_rg16k(18);
EXECUTE in_title_100_p_rg16k(19);
EXECUTE in_title_100_p_rg16k(20);
EXECUTE in_title_100_p_rg16k(21);
EXECUTE in_title_100_p_rg16k(22);
EXECUTE in_title_100_p_rg16k(23);
EXECUTE in_title_100_p_rg16k(24);
EXECUTE in_title_100_p_rg16k(25);
EXECUTE in_title_100_p_rg16k(26);
EXECUTE in_title_100_p_rg16k(27);
EXECUTE in_title_100_p_rg16k(28);
EXECUTE in_title_100_p_rg16k(29);
EXECUTE in_title_100_p_rg16k(30);
EXECUTE in_title_100_p_rg16k(31);
.timer off
.print == case thr200 p_rg16k join_body_100
.timer on
EXECUTE join_body_100_p_rg16k(0);
EXECUTE join_body_100_p_rg16k(1);
EXECUTE join_body_100_p_rg16k(2);
EXECUTE join_body_100_p_rg16k(3);
EXECUTE join_body_100_p_rg16k(4);
EXECUTE join_body_100_p_rg16k(5);
EXECUTE join_body_100_p_rg16k(6);
EXECUTE join_body_100_p_rg16k(7);
EXECUTE join_body_100_p_rg16k(8);
EXECUTE join_body_100_p_rg16k(9);
EXECUTE join_body_100_p_rg16k(10);
EXECUTE join_body_100_p_rg16k(11);
EXECUTE join_body_100_p_rg16k(12);
EXECUTE join_body_100_p_rg16k(13);
EXECUTE join_body_100_p_rg16k(14);
EXECUTE join_body_100_p_rg16k(15);
.timer off
.print == plan thr200 p_rg16k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg16k(1);
.mode trash
.print == case thr200 p_rg8k join_title_100
.timer on
EXECUTE join_title_100_p_rg8k(0);
EXECUTE join_title_100_p_rg8k(1);
EXECUTE join_title_100_p_rg8k(2);
EXECUTE join_title_100_p_rg8k(3);
EXECUTE join_title_100_p_rg8k(4);
EXECUTE join_title_100_p_rg8k(5);
EXECUTE join_title_100_p_rg8k(6);
EXECUTE join_title_100_p_rg8k(7);
EXECUTE join_title_100_p_rg8k(8);
EXECUTE join_title_100_p_rg8k(9);
EXECUTE join_title_100_p_rg8k(10);
EXECUTE join_title_100_p_rg8k(11);
EXECUTE join_title_100_p_rg8k(12);
EXECUTE join_title_100_p_rg8k(13);
EXECUTE join_title_100_p_rg8k(14);
EXECUTE join_title_100_p_rg8k(15);
.timer off
.print == case thr200 p_rg8k in_title_100
.timer on
EXECUTE in_title_100_p_rg8k(16);
EXECUTE in_title_100_p_rg8k(17);
EXECUTE in_title_100_p_rg8k(18);
EXECUTE in_title_100_p_rg8k(19);
EXECUTE in_title_100_p_rg8k(20);
EXECUTE in_title_100_p_rg8k(21);
EXECUTE in_title_100_p_rg8k(22);
EXECUTE in_title_100_p_rg8k(23);
EXECUTE in_title_100_p_rg8k(24);
EXECUTE in_title_100_p_rg8k(25);
EXECUTE in_title_100_p_rg8k(26);
EXECUTE in_title_100_p_rg8k(27);
EXECUTE in_title_100_p_rg8k(28);
EXECUTE in_title_100_p_rg8k(29);
EXECUTE in_title_100_p_rg8k(30);
EXECUTE in_title_100_p_rg8k(31);
.timer off
.print == case thr200 p_rg8k join_body_100
.timer on
EXECUTE join_body_100_p_rg8k(0);
EXECUTE join_body_100_p_rg8k(1);
EXECUTE join_body_100_p_rg8k(2);
EXECUTE join_body_100_p_rg8k(3);
EXECUTE join_body_100_p_rg8k(4);
EXECUTE join_body_100_p_rg8k(5);
EXECUTE join_body_100_p_rg8k(6);
EXECUTE join_body_100_p_rg8k(7);
EXECUTE join_body_100_p_rg8k(8);
EXECUTE join_body_100_p_rg8k(9);
EXECUTE join_body_100_p_rg8k(10);
EXECUTE join_body_100_p_rg8k(11);
EXECUTE join_body_100_p_rg8k(12);
EXECUTE join_body_100_p_rg8k(13);
EXECUTE join_body_100_p_rg8k(14);
EXECUTE join_body_100_p_rg8k(15);
.timer off
.print == plan thr200 p_rg8k join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rg8k(1);
.mode trash
.print == case thr200 p_merged join_title_100
.timer on
EXECUTE join_title_100_p_merged(0);
EXECUTE join_title_100_p_merged(1);
EXECUTE join_title_100_p_merged(2);
EXECUTE join_title_100_p_merged(3);
EXECUTE join_title_100_p_merged(4);
EXECUTE join_title_100_p_merged(5);
EXECUTE join_title_100_p_merged(6);
EXECUTE join_title_100_p_merged(7);
EXECUTE join_title_100_p_merged(8);
EXECUTE join_title_100_p_merged(9);
EXECUTE join_title_100_p_merged(10);
EXECUTE join_title_100_p_merged(11);
EXECUTE join_title_100_p_merged(12);
EXECUTE join_title_100_p_merged(13);
EXECUTE join_title_100_p_merged(14);
EXECUTE join_title_100_p_merged(15);
.timer off
.print == case thr200 p_merged in_title_100
.timer on
EXECUTE in_title_100_p_merged(16);
EXECUTE in_title_100_p_merged(17);
EXECUTE in_title_100_p_merged(18);
EXECUTE in_title_100_p_merged(19);
EXECUTE in_title_100_p_merged(20);
EXECUTE in_title_100_p_merged(21);
EXECUTE in_title_100_p_merged(22);
EXECUTE in_title_100_p_merged(23);
EXECUTE in_title_100_p_merged(24);
EXECUTE in_title_100_p_merged(25);
EXECUTE in_title_100_p_merged(26);
EXECUTE in_title_100_p_merged(27);
EXECUTE in_title_100_p_merged(28);
EXECUTE in_title_100_p_merged(29);
EXECUTE in_title_100_p_merged(30);
EXECUTE in_title_100_p_merged(31);
.timer off
.print == case thr200 p_merged join_body_100
.timer on
EXECUTE join_body_100_p_merged(0);
EXECUTE join_body_100_p_merged(1);
EXECUTE join_body_100_p_merged(2);
EXECUTE join_body_100_p_merged(3);
EXECUTE join_body_100_p_merged(4);
EXECUTE join_body_100_p_merged(5);
EXECUTE join_body_100_p_merged(6);
EXECUTE join_body_100_p_merged(7);
EXECUTE join_body_100_p_merged(8);
EXECUTE join_body_100_p_merged(9);
EXECUTE join_body_100_p_merged(10);
EXECUTE join_body_100_p_merged(11);
EXECUTE join_body_100_p_merged(12);
EXECUTE join_body_100_p_merged(13);
EXECUTE join_body_100_p_merged(14);
EXECUTE join_body_100_p_merged(15);
.timer off
.print == plan thr200 p_merged join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_merged(1);
.mode trash
.print == case thr200 p_rewritten join_title_100
.timer on
EXECUTE join_title_100_p_rewritten(0);
EXECUTE join_title_100_p_rewritten(1);
EXECUTE join_title_100_p_rewritten(2);
EXECUTE join_title_100_p_rewritten(3);
EXECUTE join_title_100_p_rewritten(4);
EXECUTE join_title_100_p_rewritten(5);
EXECUTE join_title_100_p_rewritten(6);
EXECUTE join_title_100_p_rewritten(7);
EXECUTE join_title_100_p_rewritten(8);
EXECUTE join_title_100_p_rewritten(9);
EXECUTE join_title_100_p_rewritten(10);
EXECUTE join_title_100_p_rewritten(11);
EXECUTE join_title_100_p_rewritten(12);
EXECUTE join_title_100_p_rewritten(13);
EXECUTE join_title_100_p_rewritten(14);
EXECUTE join_title_100_p_rewritten(15);
.timer off
.print == case thr200 p_rewritten in_title_100
.timer on
EXECUTE in_title_100_p_rewritten(16);
EXECUTE in_title_100_p_rewritten(17);
EXECUTE in_title_100_p_rewritten(18);
EXECUTE in_title_100_p_rewritten(19);
EXECUTE in_title_100_p_rewritten(20);
EXECUTE in_title_100_p_rewritten(21);
EXECUTE in_title_100_p_rewritten(22);
EXECUTE in_title_100_p_rewritten(23);
EXECUTE in_title_100_p_rewritten(24);
EXECUTE in_title_100_p_rewritten(25);
EXECUTE in_title_100_p_rewritten(26);
EXECUTE in_title_100_p_rewritten(27);
EXECUTE in_title_100_p_rewritten(28);
EXECUTE in_title_100_p_rewritten(29);
EXECUTE in_title_100_p_rewritten(30);
EXECUTE in_title_100_p_rewritten(31);
.timer off
.print == case thr200 p_rewritten join_body_100
.timer on
EXECUTE join_body_100_p_rewritten(0);
EXECUTE join_body_100_p_rewritten(1);
EXECUTE join_body_100_p_rewritten(2);
EXECUTE join_body_100_p_rewritten(3);
EXECUTE join_body_100_p_rewritten(4);
EXECUTE join_body_100_p_rewritten(5);
EXECUTE join_body_100_p_rewritten(6);
EXECUTE join_body_100_p_rewritten(7);
EXECUTE join_body_100_p_rewritten(8);
EXECUTE join_body_100_p_rewritten(9);
EXECUTE join_body_100_p_rewritten(10);
EXECUTE join_body_100_p_rewritten(11);
EXECUTE join_body_100_p_rewritten(12);
EXECUTE join_body_100_p_rewritten(13);
EXECUTE join_body_100_p_rewritten(14);
EXECUTE join_body_100_p_rewritten(15);
.timer off
.print == plan thr200 p_rewritten join_title_100
.mode duckbox
EXPLAIN ANALYZE EXECUTE join_title_100_p_rewritten(1);
.mode trash
RESET dynamic_or_filter_threshold;
