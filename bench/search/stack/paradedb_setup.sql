-- ParadeDB settings of the part 2 benchmark (spec 11's recorded settings, docs/search-PHASE0.md P3).
-- Each memory and parallel value is the larger of the plan's and the image's own first-run
-- auto-tuning (PDB_TUNE) on this 16-CPU, 62.7 GiB VM, so ParadeDB is never below its own tuning:
-- shared_buffers 16GB (image 16046MB), work_mem 160MB (plan 64MB), maintenance_work_mem 8GB
-- (image 2048MB), effective_cache_size 48138MB (plan 40GB), workers 80 / 88 / 8 / 8.
ALTER SYSTEM SET ssl = on;
-- A list, not one quoted string: ALTER SYSTEM would store 'pg_search,pg_stat_statements' as one
-- library name and the server would refuse to start. The image's own default also preloads
-- pg_cron, which the benchmark does not use.
ALTER SYSTEM SET shared_preload_libraries = 'pg_search', 'pg_stat_statements';
ALTER SYSTEM SET pg_stat_statements.track_planning = on;
ALTER SYSTEM SET pg_stat_statements.track = 'top';
ALTER SYSTEM SET shared_buffers = '16GB';
ALTER SYSTEM SET work_mem = '160MB';
ALTER SYSTEM SET maintenance_work_mem = '8GB';
ALTER SYSTEM SET effective_cache_size = '48138MB';
-- ParadeDB's tuning page (https://www.paradedb.com/docs/operate/performance-tuning/overview):
-- max_parallel_workers "CPUs * 5", max_worker_processes "max_parallel_workers + 8",
-- max_parallel_workers_per_gather "CPUs / 2", max_parallel_maintenance_workers "CPUs / 2, at most 8";
-- shared_buffers "25% of RAM, capped at 16GB". 16 CPUs: 80, 88, 8, 8.
ALTER SYSTEM SET max_worker_processes = 88;
ALTER SYSTEM SET max_parallel_workers = 80;
ALTER SYSTEM SET max_parallel_workers_per_gather = 8;
ALTER SYSTEM SET max_parallel_maintenance_workers = 8;
CREATE EXTENSION IF NOT EXISTS pg_search;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
