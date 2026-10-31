CREATE TABLE main.secret (s VARCHAR);
CREATE TABLE main.pub (s VARCHAR);
CREATE VIEW main.pv_in AS SELECT s FROM main.pub;
CREATE SECURE VIEW main.sv_in AS SELECT s FROM main.pv_in;
CREATE OR REPLACE VIEW main.pv_in AS SELECT s FROM main.secret;
