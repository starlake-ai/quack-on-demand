COPY (SELECT id FROM t) TO '@DIR@/out.parquet' (FORMAT parquet)
