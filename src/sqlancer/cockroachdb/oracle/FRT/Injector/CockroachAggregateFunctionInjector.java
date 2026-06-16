

package sqlancer.cockroachdb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Improved CockroachDB Aggregate Function Injector
 * - Avoids system / non-semantic columns (e.g., rowid)
 * - Basic semantic-aware filtering
 * - Always injects at least one aggregate
 * - Avoids GROUP BY errors via subquery wrapping
 */
public class CockroachAggregateFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= Aggregate Function Definition ================= */

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
    }

    /* ================= Aggregate Functions ================= */

    private static final List<AggFuncDef> AGG_FUNCS = Arrays.asList(

            new AggFuncDef("ARRAY_AGG", 1),
            new AggFuncDef("ARRAY_CAT_AGG", 1),
            new AggFuncDef("AVG", 1),
            new AggFuncDef("BIT_AND", 1),
            new AggFuncDef("BIT_OR", 1),
            new AggFuncDef("BOOL_AND", 1),
            new AggFuncDef("BOOL_OR", 1),
            new AggFuncDef("CONCAT_AGG", 1),

            new AggFuncDef("CORR", 2),
            new AggFuncDef("COUNT", 1),
            new AggFuncDef("COUNT_ROWS", 0),
            new AggFuncDef("COVAR_POP", 2),
            new AggFuncDef("COVAR_SAMP", 2),

            new AggFuncDef("EVERY", 1),

            new AggFuncDef("JSON_AGG", 1),
            new AggFuncDef("JSON_OBJECT_AGG", 2),
            new AggFuncDef("JSONB_AGG", 1),
            new AggFuncDef("JSONB_OBJECT_AGG", 2),

            new AggFuncDef("MAX", 1),
            new AggFuncDef("MIN", 1),

            new AggFuncDef("PERCENTILE_CONT", 2),
            new AggFuncDef("PERCENTILE_DISC", 2),

            new AggFuncDef("SQRDIFF", 1),

            new AggFuncDef("STDDEV", 1),
            new AggFuncDef("STDDEV_POP", 1),
            new AggFuncDef("STDDEV_SAMP", 1),

            new AggFuncDef("STRING_AGG", 2),

            new AggFuncDef("SUM", 1),
            new AggFuncDef("SUM_INT", 1),

            new AggFuncDef("VAR_POP", 1),
            new AggFuncDef("VAR_SAMP", 1),
            new AggFuncDef("VARIANCE", 1),

            new AggFuncDef("XOR_AGG", 1)
    );

    /* ================= Public Entry ================= */

    public static String inject(String sql) {
        return inject(sql, 1);
    }

    public static String inject(String sql, int maxInjectCount) {

        List<ColumnOccurrence> columns = findColumns(sql);

        /* ========= fallback：没有列 → 强制插入 ========= */
        if (columns.isEmpty()) {
            sql = injectIntoSelect(sql);
        } else {

            Collections.shuffle(columns);

            int injectCount = Math.max(1,
                    Math.min(maxInjectCount, columns.size()));

            List<ColumnOccurrence> chosen =
                    columns.subList(0, injectCount);

            chosen.sort((a, b) -> Integer.compare(b.start, a.start));

            for (ColumnOccurrence c : chosen) {

                AggFuncDef func =
                        AGG_FUNCS.get(RANDOM.nextInt(AGG_FUNCS.size()));

                String replacement = buildAggCall(func, c.column);

                sql = sql.substring(0, c.start)
                        + replacement
                        + sql.substring(c.end);
            }
        }

        /* ========= 防止 GROUP BY 报错 ========= */
        sql = wrapIfNeeded(sql);

        return sql;
    }

    /* ================= Inject into SELECT ================= */

    private static String injectIntoSelect(String sql) {

        Matcher m = Pattern.compile(
                "(SELECT\\s+(ALL|DISTINCT)?\\s*)",
                Pattern.CASE_INSENSITIVE
        ).matcher(sql);

        if (!m.find()) {
            return sql;
        }

        AggFuncDef func =
                AGG_FUNCS.get(RANDOM.nextInt(AGG_FUNCS.size()));

        String expr;

        if (func.argCount == 0) {
            expr = func.name + "()";
        } else {
            expr = func.name + "(1)";
        }

        return sql.replaceFirst(
                "(SELECT\\s+(ALL|DISTINCT)?\\s*)",
                m.group(1) + expr + ", "
        );
    }

    /* ================= Build Aggregate ================= */

    private static String buildAggCall(AggFuncDef func, String col) {

        switch (func.name) {

            case "COUNT":
                return RANDOM.nextBoolean()
                        ? "COUNT(*)"
                        : "COUNT(" + col + ")";

            case "COUNT_ROWS":
                return "COUNT_ROWS()";

            case "STRING_AGG":
                return "STRING_AGG(" + col + ", ',')";

            case "JSON_OBJECT_AGG":
            case "JSONB_OBJECT_AGG":
                return func.name + "('k', " + col + ")";

            case "CORR":
            case "COVAR_POP":
            case "COVAR_SAMP":
                return func.name + "(" + col + ", " + col + ")";

            case "PERCENTILE_CONT":
            case "PERCENTILE_DISC":
                return func.name + "(" + col + ", 0.5)";

            default:
                if (func.argCount == 1) {
                    return func.name + "(" + col + ")";
                }
                if (func.argCount == 2) {
                    return func.name + "(" + col + ", " + col + ")";
                }
        }

        throw new AssertionError(func.name);
    }

    /* ================= Column finder ================= */

    private static List<ColumnOccurrence> findColumns(String sql) {

        List<ColumnOccurrence> result = new ArrayList<>();

        Pattern colPattern = Pattern.compile(
                "\\b[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z0-9_]+\\b",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = colPattern.matcher(sql);

        while (m.find()) {

            String col = m.group();

            /* ❌ 过滤不安全列 */
            if (isUnsafeColumn(col)) {
                continue;
            }

            ColumnOccurrence c = new ColumnOccurrence();
            c.column = col;
            c.start = m.start();
            c.end = m.end();

            result.add(c);
        }

        return result;
    }

    /* ================= Unsafe column filter ================= */

    private static boolean isUnsafeColumn(String col) {

        String lower = col.toLowerCase();

        return lower.endsWith(".rowid")   // 核心：避免 rowid
                || lower.contains("uuid")
                || lower.contains("hash")
                || lower.contains("random");
    }

    /* ================= Wrap to avoid GROUP BY ================= */

    private static String wrapIfNeeded(String sql) {

        String upper = sql.toUpperCase();

        if (upper.contains("COUNT(") ||
                upper.contains("SUM(") ||
                upper.contains("AVG(") ||
                upper.contains("MIN(") ||
                upper.contains("MAX(")) {

            return "SELECT * FROM (" + sql + ") AS subq";
        }

        return sql;
    }
}