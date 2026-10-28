CREATE TYPE main.lvl AS ENUM ('a');
CREATE TABLE main.docs (level VARCHAR, body VARCHAR);
CREATE SECURE VIEW sv_piv AS SELECT * FROM (PIVOT main.docs ON level IN ('a'::main.lvl) USING count(*));
