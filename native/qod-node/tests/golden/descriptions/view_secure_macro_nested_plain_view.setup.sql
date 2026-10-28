CREATE TABLE main.secret (s VARCHAR);
CREATE TABLE main.pub (s VARCHAR);
CREATE VIEW main.pv_m AS SELECT s FROM main.pub;
CREATE MACRO main.m_inner() AS (SELECT max(s) FROM main.pv_m);
CREATE MACRO main.m_outer() AS main.m_inner();
CREATE SECURE VIEW sv_mn AS SELECT main.m_outer() AS m;
