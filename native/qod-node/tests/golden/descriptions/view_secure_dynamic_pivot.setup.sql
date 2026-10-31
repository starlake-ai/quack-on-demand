INSERT INTO main.t VALUES (1, 'a', NULL);
PIVOT main.t ON v USING count(*);
