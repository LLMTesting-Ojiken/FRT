drop table if exists t1;
create table t1
(
c1 decimal unsigned null
);

INSERT INTO t1 (c1) VALUES (2140366325);
INSERT INTO t1 (c1) VALUES (null);

SELECT MAX(
IF(c1 IS NOT NULL, JSON_OBJECT('k', c1), 0)
)
FROM t1;