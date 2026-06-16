package sqlancer.sqlite3.oracle.FRT.Mutators;

import java.util.Map;
import java.util.Locale;

import sqlancer.Randomly;

public final class SQLite3DateTimeMutators {

    private SQLite3DateTimeMutators() {
    }

    public static void register(
            Map<String, SQLite3Function_level_Reconstruction_Oracle.FunctionMutator> mutators) {

        mutators.put("DATE", new DateMutator());
        mutators.put("TIME", new TimeMutator());
        mutators.put("DATETIME", new DateTimeMutator());
        mutators.put("JULIANDAY", new JulianDayMutator());
        mutators.put("UNIXEPOCH", new UnixEpochMutator());
        mutators.put("STRFTIME", new StrftimeMutator());
        mutators.put("TIMEDIFF", new TimeDiffMutator());
    }

    /* =========================
     *  Helpers
     * ========================= */

    private static boolean containsRiskyModifier(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        return u.contains("NOW")
                || u.contains("LOCALTIME")
                || u.contains("UTC")
                || u.contains("SUBSEC");
    }

    /* =========================
     *  date(x)
     * ========================= */

    static class DateMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();

            return Randomly.fromOptions(
                    // 官方等价
                    "DATE(" + x + ")",
                    "STRFTIME('%Y-%m-%d'," + x + ")",
                    "STRFTIME('%F'," + x + ")"
            );
        }
    }
    /* =========================
     *  time(x)
     * ========================= */

    static class TimeMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "STRFTIME('%H:%M:%S', " + arg + ")",
                    "STRFTIME('%T', " + arg + ")",

                    // 结构型等价
                    "SUBSTR(DATETIME(" + arg + "), 12, 8)"
            );
        }
    }

    /* =========================
     *  datetime(x)
     * ========================= */

    static class DateTimeMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "STRFTIME('%Y-%m-%d %H:%M:%S', " + arg + ")",
                    "STRFTIME('%F %T', " + arg + ")"
            );
        }
    }

    /* =========================
     *  julianday(x)
     * ========================= */

    static class JulianDayMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    // 数学等价
                    "(UNIXEPOCH(" + arg + ") / 86400.0 + 2440587.5)",

                    // strftime 等价
                    "CAST(STRFTIME('%J', " + arg + ") AS REAL)"
            );
        }
    }

    /* =========================
     *  unixepoch(x)
     * ========================= */

    static class UnixEpochMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    // 数学等价
                    "CAST((JULIANDAY(x) - 2440587.5) * 86400 AS INT)",

                    // strftime 等价
                    "CAST(STRFTIME('%s', " + arg + ") AS INT)"
            );
        }
    }

    /* =========================
     *  strftime(fmt, x)
     * ========================= */

    static class StrftimeMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);
            if (parts.length < 2) {
                return "STRFTIME(" + arg + ")";
            }

            String fmt = parts[0].trim();
            String x = parts[1].trim();

            // 根据格式串反推等价函数
            if (fmt.contains("%F") || (fmt.contains("%Y") && fmt.contains("%m") && fmt.contains("%d"))) {
                return "DATE(" + x + ")";
            }

            if (fmt.contains("%T") || (fmt.contains("%H") && fmt.contains("%M") && fmt.contains("%S"))) {
                return "TIME(" + x + ")";
            }

            if ((fmt.contains("%F") || fmt.contains("%Y"))
                    && (fmt.contains("%T") || fmt.contains("%H"))) {
                return "DATETIME(" + x + ")";
            }

            return "STRFTIME(" + arg + ")";
        }
    }

    /* =========================
     *  timediff(A,B)
     * ========================= */

    static class TimeDiffMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !containsRiskyModifier(sqlAfterFunction);
        }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);
            if (parts.length < 2) {
                return "TIMEDIFF(" + arg + ")";
            }

            String a = parts[0].trim();
            String b = parts[1].trim();

            return Randomly.fromOptions(
                    // 基于 julianday 的差值
                    "STRFTIME('%H:%M:%S', JULIANDAY(" + a + ") - JULIANDAY(" + b + "))",

                    // 另一种写法
                    "TIME(JULIANDAY(" + a + ") - JULIANDAY(" + b + "))"
            );
        }
    }
}