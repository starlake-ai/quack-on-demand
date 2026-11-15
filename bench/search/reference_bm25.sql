-- Reference BM25 top 10 of one gate cell, in DuckDB on the part 2 corpus Parquet (plan Task 4
-- Step 5; Task 12 reuses the allowed form). Tantivy's own formula with k1 1.2, b 0.75:
--   score(d) = sum over query terms t with tf(t, d) > 0 of
--              idf(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avgdl)),
--   idf(t)   = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5)).
-- Tokens are the body split on single spaces (the corpus holds only `w<n>` words joined by one
-- space). avgdl is always the mean exact token count (Tantivy: total_num_tokens / total_num_docs).
-- dl, set by `dlmode`: 'exact' (the reference proper: the exact token count, unquantized) or
-- 'quantized' (diagnostic only: Tantivy's fieldnorm, the largest FIELD_NORMS_TABLE value <= the
-- exact count, tantivy 0.27.0 src/fieldnorm/code.rs, MIT; exact up to 40, the table below covers
-- the corpus' 20..199 words).
-- Two population forms, set by `pop`:
--   'table'   : N, avgdl and df over every doc of the table (spec 7.5, the `where` cells);
--   'allowed' : N, avgdl and df over the docs that pass the filter only (the policy cells).
-- Either way, only the docs that pass the filter are scored and ranked; ties break by id.
-- Usage:
--   env -u AI_AGENT duckdb -no-agent -json -cmd "SET VARIABLE src = '<parquet>';
--     SET VARIABLE terms = ['w1', 'w10']; SET VARIABLE fcol = 'f10'; SET VARIABLE pop = 'table';
--     SET VARIABLE dlmode = 'exact'" < reference_bm25.sql
-- fcol: 'none' (100%), 'f10' (f10 = 0, 10%), 'f100' (f100 = 0, 1%). Optional: SET VARIABLE nrows = <n>
-- (rows returned, default 10).
WITH docs AS (
  SELECT id, string_split(body, ' ') AS toks,
         CASE getvariable('fcol') WHEN 'f10' THEN f10 = 0 WHEN 'f100' THEN f100 = 0
              WHEN 'none' THEN true END AS pass
  FROM read_parquet(getvariable('src'))
),
pop AS (
  SELECT * FROM docs WHERE getvariable('pop') = 'table' OR (getvariable('pop') = 'allowed' AND pass)
),
stats AS (SELECT count(*)::DOUBLE AS n, avg(len(toks))::DOUBLE AS avgdl FROM pop),
q AS (SELECT DISTINCT unnest(getvariable('terms')::VARCHAR[]) AS term),
tf AS (
  SELECT d.id, d.pass, q.term,
         CASE getvariable('dlmode')
           WHEN 'exact' THEN len(d.toks)
           WHEN 'quantized' THEN list_max(list_filter(
             [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
              24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 42, 44, 46, 48, 50,
              52, 54, 56, 60, 64, 68, 72, 76, 80, 84, 88, 96, 104, 112, 120, 128, 136, 144, 152, 168,
              184, 200], lambda v: v <= len(d.toks)))
         END::DOUBLE AS dl,
         len(list_filter(d.toks, lambda t: t = q.term))::DOUBLE AS tf
  FROM pop d, q
),
df AS (SELECT term, count(*) FILTER (WHERE tf > 0)::DOUBLE AS df FROM tf GROUP BY term),
scored AS (
  SELECT tf.id,
         sum(ln(1 + (s.n - df.df + 0.5) / (df.df + 0.5))
             * tf.tf * (1.2 + 1) / (tf.tf + 1.2 * (1 - 0.75 + 0.75 * tf.dl / s.avgdl))) AS score
  FROM tf JOIN df USING (term), stats s
  WHERE tf.pass AND tf.tf > 0
  GROUP BY tf.id
)
SELECT row_number() OVER (ORDER BY score DESC, id) AS rank, id, score
FROM scored ORDER BY score DESC, id LIMIT coalesce(getvariable('nrows'), 10);
