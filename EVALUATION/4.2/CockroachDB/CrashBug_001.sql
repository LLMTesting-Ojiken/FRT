drop table if exists t20;
create table t20
(
    c0 INTERVAL
);

alter table t20
    owner to root;

INSERT INTO t20 (c0) VALUES ('704875445 years 11 mons -1180445155 days 1726458 hours 54 mins 8.052617 secs');
INSERT INTO t20 (c0) VALUES ('1905131914 years 8 mons -32328906 days -974445 hours -9 mins -11.863545 secs');
INSERT INTO t20 (c0) VALUES ('-1238721666 years -5 mons -484592748 days 2403996 hours 54 mins 6.678352 secs');
INSERT INTO t20 (c0) VALUES ('-477373569 years -5 mons 1116995662 days -398661 hours -33 mins -38.653034 secs');
INSERT INTO t20 (c0) VALUES ('1451702191 years 7 mons 1805215495 days -947146 hours 0 mins -9.237811 secs');

SELECT SUM(t20.c0) FROM t20;