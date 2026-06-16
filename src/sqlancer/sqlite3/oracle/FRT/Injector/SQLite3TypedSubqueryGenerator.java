package sqlancer.sqlite3.oracle.FRT.Injector;

import sqlancer.Randomly;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;

public class SQLite3TypedSubqueryGenerator {

    public enum ExpectedType {
        NUMERIC,
        TEXT,
        BLOB,
        ANY
    }

    public static String generate(
            SQLite3GlobalState globalState,
            ExpectedType type) {

        // 20% 直接生成 NULL
        if (Randomly.getBooleanWithSmallProbability()) {
            return "(SELECT NULL)";
        }

        String q = SQLite3Visitor.asString(
                SQLite3RandomQuerySynthesizer.generate(globalState, 1)
        );

        String base = "(SELECT * FROM (" + q + ") LIMIT 1)";

        switch (type) {

            case NUMERIC:
                return "(SELECT CAST(" + base + " AS NUMERIC))";

            case TEXT:
                return "(SELECT CAST(" + base + " AS TEXT))";

            case BLOB:
                return "(SELECT CAST(" + base + " AS BLOB))";

            case ANY:
            default:
                return base;
        }
    }

    /* ============================================================
     * 根据函数名推导 expected type
     * ============================================================ */

    public static ExpectedType inferExpectedType(String func) {

        func = func.toUpperCase();

        /* ================= Math ================= */

        if (func.matches(
                "ACOS|ASIN|ATAN|ATAN2|ACOSH|ASINH|ATANH|CEIL|CEILING|COS|COSH|" +
                        "DEGREES|EXP|FLOOR|LN|LOG|LOG10|LOG2|MOD|PI|POW|POWER|" +
                        "RADIANS|SIN|SINH|SQRT|TAN|TANH|TRUNC"
        )) {
            return ExpectedType.NUMERIC;
        }

        /* ================= String ================= */

        if (func.matches(
                "LOWER|UPPER|LTRIM|RTRIM|TRIM|REPLACE|INSTR|SUBSTR|SUBSTRING|" +
                        "OCTET_LENGTH|HEX|UNHEX|CHAR|UNICODE|QUOTE"
        )) {
            return ExpectedType.TEXT;
        }

        /* ================= JSON ================= */

        if (func.startsWith("JSON")) {
            return ExpectedType.TEXT;
        }

        /* ================= Date/Time ================= */

        if (func.matches(
                "DATE|TIME|DATETIME|JULIANDAY|UNIXEPOCH|STRFTIME|TIMEDIFF"
        )) {
            return ExpectedType.TEXT;
        }

        /* ================= Aggregate ================= */

        if (func.matches(
                "AVG|COUNT|GROUP_CONCAT|STRING_AGG|MEDIAN|MIN|MAX|SUM|TOTAL"
        )) {
            return ExpectedType.ANY;
        }

        /* ================= Default ================= */

        return ExpectedType.ANY;
    }
}