CREATE SCHEMA reporting;
CREATE TABLE main.salaries (id INTEGER, pay INTEGER);
CREATE TABLE main.allowed_ids (id INTEGER);
CREATE SECURE VIEW reporting.sv_pay AS SELECT s.* FROM main.salaries s JOIN allowed_ids a USING (id);
