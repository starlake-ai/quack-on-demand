CREATE SECURE VIEW sv_inner_u AS SELECT id FROM u;
CREATE SECURE VIEW sv_outer_q AS SELECT id FROM main.sv_inner_u;
