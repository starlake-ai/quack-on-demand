CREATE TABLE main.secret (s VARCHAR);
CREATE TABLE main.pub (s VARCHAR);
CREATE VIEW main.pv_m AS SELECT s FROM main.pub;
CREATE MACRO main.m_ok() AS (SELECT max(s) FROM main.pub);
CREATE SECURE VIEW sv_mok AS SELECT main.m_ok() AS m;
