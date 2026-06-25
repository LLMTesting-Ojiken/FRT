drop table if exists t0;
create table t0
(
c0 tinyblob null
);

INSERT INTO t0 (c0) VALUES (0x383836323331303239);
INSERT INTO t0 (c0) VALUES (null);

-- baseline {}
SELECT MIN(IF(c0 IS NULL, JSON_OBJECT(), 1)) FROM t0;

-- rewrite 1
SELECT MIN(
CASE
WHEN IF(c0 IS NULL, JSON_OBJECT(), 1) IS NULL
THEN NULL
ELSE IF(c0 IS NULL, JSON_OBJECT(), 1)
END
) FROM t0;