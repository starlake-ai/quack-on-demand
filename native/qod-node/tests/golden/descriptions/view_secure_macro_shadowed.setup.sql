CREATE SCHEMA rpt;
CREATE SECURE VIEW rpt.sv_up AS SELECT upper(v) AS uv FROM main.t;
CREATE MACRO rpt.upper(a) AS lower(a);
