MERGE INTO t USING u ON t.id = u.id WHEN NOT MATCHED THEN INSERT VALUES (u.id, u.v, u.tags)
