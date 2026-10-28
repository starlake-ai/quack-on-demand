CREATE SCHEMA rpt;
CREATE SECURE VIEW rpt.sv_seq AS SELECT nextval('main.seq1') AS n;
CREATE SEQUENCE rpt.seq1;
