CREATE TABLE main.secret (s VARCHAR);
CREATE TABLE main.pub (s VARCHAR);
CREATE VIEW main.pv_m AS SELECT s FROM main.pub;
CREATE MACRO main.tm() AS TABLE SELECT s FROM main.pv_m;
CREATE MACRO main.m() AS (SELECT max(s) FROM main.pv_m);
CREATE SECURE VIEW main.sv_m AS SELECT s, main.m() AS m FROM main.tm();
CREATE OR REPLACE VIEW main.pv_m AS SELECT s FROM main.secret;
