-- Q3: the unoptimized logical plan (what post_bind sees) of every input shape of spec 4.2.
-- Check: grep -c "^== " q3.out must equal the number of .print headers (30); plans carry no titles, the first plan of each case is the unoptimized one.
-- Run: cd <scratch>/q3 && rm -rf q3.ducklake* q3data && env -u AI_AGENT duckdb -no-agent < q3_plans.sql 2>&1 | sed "s/\x1b\[[0-9;]*m//g" > q3.out
LOAD ducklake;
ATTACH 'ducklake:q3.ducklake' AS lake (DATA_PATH 'q3data/');
CREATE TABLE lake.pages AS SELECT i AS id, 'doc ' || i AS body, CASE WHEN i % 10 = 0 THEN 'fr' ELSE 'en' END AS lang, i % 7 AS cat FROM range(20000) r(i);
CREATE TABLE lake.other AS SELECT i AS id FROM range(100) r(i);
CREATE VIEW lake.v_pages AS SELECT * FROM lake.pages;
CREATE VIEW lake.v_rid AS SELECT rowid AS rid, * FROM lake.pages;
SET explain_output = 'all';
.print == A1 bare scan (fast path)
EXPLAIN SELECT rowid FROM lake.pages;
.print == A2 user filter
EXPLAIN SELECT rowid FROM lake.pages WHERE lang = 'fr';
.print == A3 row-policy wrap of spec 4.2 rule 3, plus a user filter
EXPLAIN SELECT rowid FROM (SELECT *, rowid FROM lake.pages WHERE cat = 3) pages WHERE lang = 'fr';
.print == A4 IN (uncorrelated subquery)
EXPLAIN SELECT rowid FROM lake.pages WHERE id IN (SELECT id FROM lake.other);
.print == A5 NOT IN
EXPLAIN SELECT rowid FROM lake.pages WHERE id NOT IN (SELECT id FROM lake.other);
.print == A6 EXISTS, correlated
EXPLAIN SELECT rowid FROM lake.pages p WHERE EXISTS (SELECT 1 FROM lake.other o WHERE o.id = p.id);
.print == A7 scalar subquery, uncorrelated
EXPLAIN SELECT rowid FROM lake.pages WHERE id > (SELECT max(id) FROM lake.other);
.print == A8 scalar subquery, correlated
EXPLAIN SELECT rowid FROM lake.pages p WHERE p.cat = (SELECT min(o.id) FROM lake.other o WHERE o.id = p.cat);
.print == A9 policy predicate holding a subquery, inside the wrap
EXPLAIN SELECT rowid FROM (SELECT *, rowid FROM lake.pages WHERE id IN (SELECT id FROM lake.other)) pages;
.print == R1 view (must be refused)
EXPLAIN SELECT rowid FROM lake.v_pages;
.print == R2 CTE (refused)
EXPLAIN WITH x AS (SELECT *, rowid FROM lake.pages) SELECT rowid FROM x;
.print == R3 join in FROM (refused)
EXPLAIN SELECT p.rowid FROM lake.pages p JOIN lake.other o ON o.id = p.id;
.print == R4 union (refused)
EXPLAIN SELECT rowid FROM lake.pages UNION ALL SELECT rowid FROM lake.other;
.print == R5 expression over rowid (refused)
EXPLAIN SELECT rowid + 1 AS rowid FROM lake.pages;
.print == R6 LIMIT (refused)
EXPLAIN SELECT rowid FROM lake.pages LIMIT 10;
.print == R7 AT clause (admitted; snapshot from the scan)
EXPLAIN SELECT rowid FROM lake.pages AT (VERSION => 1);
-- Extras beyond the brief
.print == R1b view that exposes rowid (refused)
EXPLAIN SELECT rid FROM lake.v_rid;
.print == R5b expression over rowid under another alias (refused)
EXPLAIN SELECT rowid + 1 AS r FROM lake.pages;
.print == R7b AT TIMESTAMP clause
EXPLAIN SELECT rowid FROM lake.pages AT (TIMESTAMP => now());
.print == X1 policy wrap with a correlated EXISTS in the policy
EXPLAIN SELECT rowid FROM (SELECT *, rowid FROM lake.pages p WHERE EXISTS (SELECT 1 FROM lake.other o WHERE o.id = p.id)) pages;
.print == X2 outer join in FROM (refused)
EXPLAIN SELECT p.rowid FROM lake.pages p LEFT JOIN lake.other o ON o.id = p.id;
.print == X3 DISTINCT (refused)
EXPLAIN SELECT DISTINCT rowid FROM lake.pages;
.print == X4b VALUES (refused)
EXPLAIN SELECT col0 FROM (VALUES (1),(2));
.print == X4 range (refused)
EXPLAIN SELECT range FROM range(5);
.print == X5 scan of another table
EXPLAIN SELECT rowid FROM lake.other;
.print == X6 ANY/ALL comparison
EXPLAIN SELECT rowid FROM lake.pages WHERE id > ANY (SELECT id FROM lake.other);
.print == X7 uncorrelated EXISTS
EXPLAIN SELECT rowid FROM lake.pages WHERE EXISTS (SELECT 1 FROM lake.other);
.print == X8 NOT EXISTS correlated
EXPLAIN SELECT rowid FROM lake.pages p WHERE NOT EXISTS (SELECT 1 FROM lake.other o WHERE o.id = p.id);
.print == X9 correlated IN
EXPLAIN SELECT rowid FROM lake.pages p WHERE p.id IN (SELECT o.id FROM lake.other o WHERE o.id < p.cat);
.print == X10 subquery in the select list (non-filter position)
EXPLAIN SELECT (SELECT max(id) FROM lake.other WHERE id < p.cat) AS m, rowid FROM lake.pages p;
