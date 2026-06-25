DROP TABLE IF EXISTS t0;
CREATE TABLE t0
(
c1 REAL
);
INSERT INTO t0 (c1)
VALUES (1),
(3),
(5),
(7),
(9);
SELECT percentile_cont(c1, 0.5) FROM t0;