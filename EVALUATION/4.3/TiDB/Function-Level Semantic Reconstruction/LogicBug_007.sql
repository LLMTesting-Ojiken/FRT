drop table if exists t1;
create table t1
(
c0 tinyblob not null
);

INSERT INTO t1 (c0) VALUES (0x31343936393730313331);

SELECT SUM((IF(t1.c0 IS NULL, t1.c0, 0)))
FROM t1;