CREATE TYPE main.lvl AS ENUM ('a');
CREATE TABLE main.docs (level VARCHAR, body VARCHAR);
CREATE SECURE VIEW main.sv_enum AS SELECT body FROM main.docs WHERE TRY_CAST(level AS main.lvl) IS NOT NULL;
DROP VIEW main.sv_enum;
DROP TYPE main.lvl;
CREATE TYPE main.lvl AS ENUM ('a', 'b');
CREATE SECURE VIEW main.sv_enum AS SELECT body FROM main.docs WHERE TRY_CAST(level AS main.lvl) IS NOT NULL;
