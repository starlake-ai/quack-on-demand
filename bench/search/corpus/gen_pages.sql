-- QoD Search Phase 0 part 2 corpus (spec 11, "synthetic 1M Zipf corpus, generator committed").
-- Usage: env -u AI_AGENT duckdb -no-agent -cmd "SET VARIABLE n = 1000000; SET VARIABLE out = '<path>'; SET VARIABLE body = true" < gen_pages.sql
-- (-cmd runs before stdin is read; -c would run its command and exit without reading this file).
-- Doc id i has 20 + hash(i, 'len') % 180 words; word j is w<floor(u^4 * 50000)> with
-- u = (hash(i, j) % 2^32) / 2^32. Deterministic: the same n gives the same bytes of data, on
-- DuckDB v2.0.0 (the corpus rests on DuckDB's hash(), which is not stable across releases).
-- DuckDB 2.0 refuses the arrow lambda (x -> ...), so the lambda is written lambda j: ...
SET threads = 16;
COPY (
  SELECT i AS id,
         't' || i AS title,
         CASE WHEN getvariable('body') THEN
           array_to_string(list_transform(range(20 + (hash(i, 'len') % 180)::BIGINT),
             lambda j: 'w' || floor(pow((hash(i, j) % 4294967296)::DOUBLE / 4294967296.0, 4) * 50000)::BIGINT), ' ')
         ELSE '' END AS body,
         (i % 10)::INTEGER AS f10,
         (i % 100)::INTEGER AS f100
  FROM range(getvariable('n')) r(i)
  ORDER BY i
) TO (getvariable('out')) (FORMAT parquet, ROW_GROUP_SIZE 122880);
