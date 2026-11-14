-- Q12 setup (SQL admin dialect, run as the superuser through FlightSQL).
-- The DuckDB catalog of `qod serve --tenant acme --name lake` is acme_lake (the
-- tenant-db's composed name), not lake: checked with
--   SELECT database_name FROM duckdb_databases()
CREATE TABLE acme_lake.main.pages AS SELECT i AS id, 't' || i AS title, 'b' || i AS body, i % 7 AS cat FROM range(1000000) r(i);
CREATE ROLE reader;
GRANT SELECT ON acme_lake.main.pages TO ROLE reader;
CREATE ROLE reader_rls;
GRANT SELECT ON acme_lake.main.pages TO ROLE reader_rls;
CREATE ROW POLICY ON acme_lake.main.pages FOR ROLE reader_rls USING (cat = 3);
CREATE USER plain PASSWORD 'q12-plain' ROLES reader GROUPS qod_all_pools;
CREATE USER policy PASSWORD 'q12-policy' ROLES reader_rls GROUPS qod_all_pools;
