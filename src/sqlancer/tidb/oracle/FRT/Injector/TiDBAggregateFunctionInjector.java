package sqlancer.tidb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TiDB Aggregate Function Injector
 * Only uses functions defined in TiDB documentation.
 */
public class TiDBAggregateFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= Aggregate Definition ================= */

    private static class AggFuncDef {

        final String name;
        final int argCount;

        AggFuncDef(String name, int argCount) {
            this.name = name;
            this.argCount = argCount;
        }
    }

    /* ================= Column occurrence ================= */

    private static class ColumnOccurrence {

        String column;
        int start;
        int end;
        boolean insideAgg;
    }

    /* ================= TiDB Aggregate Functions ================= */

    private static final List<AggFuncDef> TIDB_AGG_FUNCS = Arrays.asList(

            new AggFuncDef("AVG", 1),
            new AggFuncDef("COUNT", 1),
            new AggFuncDef("SUM", 1),
            new AggFuncDef("MIN", 1),
            new AggFuncDef("MAX", 1),

            new AggFuncDef("GROUP_CONCAT", 1),

            new AggFuncDef("VAR_POP", 1),
            new AggFuncDef("VAR_SAMP", 1),
            new AggFuncDef("VARIANCE", 1),

            new AggFuncDef("STDDEV_POP", 1),
            new AggFuncDef("STDDEV_SAMP", 1),
            new AggFuncDef("STDDEV", 1),
            new AggFuncDef("STD", 1),

            new AggFuncDef("JSON_ARRAYAGG", 1),
            new AggFuncDef("JSON_OBJECTAGG", 2),

            new AggFuncDef("APPROX_COUNT_DISTINCT", 1),
            new AggFuncDef("APPROX_PERCENTILE", 2)
    );

    private static final Set<String> AGG_NAMES = new HashSet<>();

    static {
        for (AggFuncDef f : TIDB_AGG_FUNCS) {
            AGG_NAMES.add(f.name);
        }
    }

    /* ================= Public Entry ================= */

    public static String inject(String sql) {
        return inject(sql, 1);
    }

    public static String inject(String sql, int maxInjectCount) {

        if (maxInjectCount <= 0) {
            return sql;
        }

        List<ColumnOccurrence> columns = findColumns(sql);

        if (columns.isEmpty()) {
            return sql;
        }

        Collections.shuffle(columns);

        int injectCount = Math.min(maxInjectCount, columns.size());

        List<ColumnOccurrence> chosen = columns.subList(0, injectCount);

        chosen.sort((a, b) -> Integer.compare(b.start, a.start));

        for (ColumnOccurrence c : chosen) {

            AggFuncDef func = TIDB_AGG_FUNCS.get(
                    RANDOM.nextInt(TIDB_AGG_FUNCS.size())
            );

            String replacement = buildAggCall(func, c.column);

            sql = sql.substring(0, c.start)
                    + replacement
                    + sql.substring(c.end);
        }

        return sql;
    }

    /* ================= Aggregate Builder ================= */

    private static String buildAggCall(AggFuncDef func, String col) {

        if (func.argCount == 1) {

            if ("COUNT".equals(func.name)) {

                if (RANDOM.nextBoolean()) {
                    return "COUNT(*)";
                }

                return "COUNT(" + col + ")";
            }

            if ("GROUP_CONCAT".equals(func.name)) {

                if (RANDOM.nextBoolean()) {
                    return "GROUP_CONCAT(" + col + ")";
                }

                return "GROUP_CONCAT(" + col + " SEPARATOR '|')";
            }

            if ("APPROX_COUNT_DISTINCT".equals(func.name)) {

                return "APPROX_COUNT_DISTINCT(" + col + ")";
            }

            return func.name + "(" + col + ")";
        }

        if (func.argCount == 2) {

            if ("JSON_OBJECTAGG".equals(func.name)) {

                String key = "'k" + RANDOM.nextInt(100) + "'";

                return "JSON_OBJECTAGG(" + key + ", " + col + ")";
            }

            if ("APPROX_PERCENTILE".equals(func.name)) {

                double p = RANDOM.nextDouble();

                return "APPROX_PERCENTILE(" + col + ", " + p + ")";
            }
        }

        throw new AssertionError("Unsupported aggregate: " + func.name);
    }

    /* ================= Column Finder ================= */

    private static List<ColumnOccurrence> findColumns(String sql) {

        List<ColumnOccurrence> result = new ArrayList<>();

        Pattern colPattern = Pattern.compile(
                "\\bt\\d+\\.c\\d+\\b",
                Pattern.CASE_INSENSITIVE
        );

        Pattern aggPattern = Pattern.compile(
                "\\b(" + String.join("|", AGG_NAMES) + ")\\s*\\(",
                Pattern.CASE_INSENSITIVE
        );

        Matcher colMatcher = colPattern.matcher(sql);

        while (colMatcher.find()) {

            int pos = colMatcher.start();

            if (isInsideString(sql, pos)) {
                continue;
            }

            ColumnOccurrence occ = new ColumnOccurrence();

            occ.column = colMatcher.group();
            occ.start = colMatcher.start();
            occ.end = colMatcher.end();

            occ.insideAgg = isInsideAggregate(sql, occ.start, aggPattern);

            if (!occ.insideAgg) {
                result.add(occ);
            }
        }

        return result;
    }

    private static boolean isInsideString(String sql, int pos) {

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < pos; i++) {

            char c = sql.charAt(i);

            if (c == '\'' && !inDouble) inSingle = !inSingle;

            if (c == '"' && !inSingle) inDouble = !inDouble;
        }

        return inSingle || inDouble;
    }

    /* ================= Nested Aggregate Check ================= */

    private static boolean isInsideAggregate(
            String sql,
            int pos,
            Pattern aggPattern) {

        Matcher m = aggPattern.matcher(sql);

        while (m.find()) {

            int open = sql.indexOf('(', m.start());

            if (open == -1) continue;

            int close = findMatchingParen(sql, open);

            if (close == -1) continue;

            if (pos > open && pos < close) {
                return true;
            }
        }

        return false;
    }

    /* ================= Parenthesis Matching ================= */

    private static int findMatchingParen(String s, int openPos) {

        int depth = 1;

        for (int i = openPos + 1; i < s.length(); i++) {

            char c = s.charAt(i);

            if (c == '(') depth++;

            else if (c == ')') depth--;

            if (depth == 0) return i;
        }

        return -1;
    }
}