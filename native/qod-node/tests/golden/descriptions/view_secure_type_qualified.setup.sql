CREATE SCHEMA rpt;
CREATE TYPE main.lvl AS ENUM ('a');
CREATE TABLE main.docs (level VARCHAR, body VARCHAR);
CREATE SECURE VIEW rpt.sv_lvl AS SELECT body FROM main.docs WHERE TRY_CAST(level AS main.lvl) IS NOT NULL;
CREATE TYPE rpt.lvl AS ENUM ('a', 'b');
