create table t0 (c0 text not null);
create table t1 (c0 text not null);

INSERT INTO t0 VALUES
('䥾5'),(']{C'),(''),('qN쁵'),('0.9488032433044199'),
('()'),(''),(''),('-1869711850'),('!\nO/6');

INSERT INTO t1 VALUES
('0.07463010593164399'),('-896583232'),('T9zsu'),
('^쒂'),('%7'),('-2115042882'),(' fN'),
('1508022163'),('-1289521155'),(''),('1871047467');

create view v0 as
SELECT
((-606989725) NOT LIKE t0.c0) AS cond,
t0.c0 AS t0_c0,
t1.c0 AS t1_c0
FROM t0 JOIN t1;

-- run multiple times
SELECT GROUP_CONCAT(t1.c0) FROM t0, v0, t1;