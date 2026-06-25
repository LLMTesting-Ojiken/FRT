drop table if exists t0,t1;
create table t0
(
c0 text null
);

create table t1
(
c0 text null
);
INSERT INTO database0.t0 (c0)
VALUES ('-2012790846');

INSERT INTO database0.t1 (c0)
VALUES ('TE'~{v');
INSERT INTO database0.t1 (c0)
VALUES ('/');
INSERT INTO database0.t1 (c0)
VALUES ('');
INSERT INTO database0.t1 (c0)
VALUES ('!gA');
INSERT INTO database0.t1 (c0)
VALUES ('Gt*J');
INSERT INTO database0.t1 (c0)
VALUES ('0.3573013800560694');

SELECT (SUM((t0.c0) * (t0.c0)) - SUM((t0.c0)) * SUM((t0.c0)) / COUNT((t0.c0))) / NULLIF(COUNT((t0.c0)), 0)
FROM t1
STRAIGHT_JOIN t0;