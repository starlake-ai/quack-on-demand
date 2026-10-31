SELECT row_number() OVER (ORDER BY id) AS n FROM t
