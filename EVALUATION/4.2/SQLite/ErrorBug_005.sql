DROP TABLE IF EXISTS vt0;
CREATE TABLE vt0
(
    c0 TEXT
);
INSERT INTO vt0(c0)
VALUES (NULL),
       (NULL),
       (NULL),
       (NULL),
       (NULL),
       (NULL),
       (NULL),
       ('0.15779875942444'),
       ('');
SELECT DISTINCT CAST(vt0.c0 AS BLOB) COLLATE NOCASE
        FROM vt0
        WHERE (((((((((load_extension(vt0.c0, vt0.c0)) OR (CASE vt0.c0 WHEN vt0.c0 THEN vt0.c0 ELSE vt0.c0 END))) OR
                    (((vt0.c0) >= (vt0.c0))))) AND (((x'') ISNULL)))) OR (CHANGES())));
--Mutated SQL1 （add select (....) AS val）:  
SELECT (SELECT DISTINCT CAST(vt0.c0 AS BLOB) COLLATE NOCASE
        FROM vt0
        WHERE (((((((((load_extension(vt0.c0, vt0.c0)) OR (CASE vt0.c0 WHEN vt0.c0 THEN vt0.c0 ELSE vt0.c0 END))) OR
                    (((vt0.c0) >= (vt0.c0))))) AND (((x'') ISNULL)))) OR (CHANGES())))) AS val;
--Mutated SQL2 (similar to the example above, with (x'') replaced by(x'' \|\| ''):  
SELECT (SELECT DISTINCT CAST(vt0.c0 AS BLOB) COLLATE NOCASE
        FROM vt0
        WHERE (((((((((load_extension(vt0.c0, vt0.c0)) OR (CASE vt0.c0 WHEN vt0.c0 THEN vt0.c0 ELSE vt0.c0 END))) OR
                    (((vt0.c0) >= (vt0.c0))))) AND (((x'' || '') ISNULL)))) OR (CHANGES())))) AS val;