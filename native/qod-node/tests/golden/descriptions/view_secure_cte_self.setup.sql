CREATE TABLE main.orders (region VARCHAR, id INTEGER);
CREATE SECURE VIEW sv_ord AS WITH orders AS (SELECT * FROM orders WHERE region = 'EU') SELECT id FROM orders;
