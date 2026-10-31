CREATE TABLE m1.main.x (id INTEGER);
CREATE VIEW main.pv_m1 AS SELECT id FROM m1.main.x;
CREATE SECURE VIEW sv_pv AS SELECT id FROM main.pv_m1;
