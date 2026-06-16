//package sqlancer.cockroachdb.oracle.FRT.Injector;
//
//import java.util.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
///**
// * FRT-safe CockroachDB FLOAT Function Injector
// *
// * 特性：
// * - 自动 CAST → FLOAT
// * - 自动 domain 修复（sqrt/log/acos等）
// * - 避免 NaN / NULL 爆炸
// * - 控制注入比例（避免全破坏）
// * - 保持较高函数覆盖率
// */
//public class CockroachFloatFunctionInjector {
//
//    private static final Random RANDOM = new Random();
//
//    /* ================= 配置（可调） ================= */
//
//    // 注入概率（0~100）
//    private static final int INJECT_PROB = 60;
//
//    // 是否启用“危险函数”（acos/log等）
//    private static final boolean ENABLE_AGGRESSIVE = true;
//
//    /* ================= Function Definition ================= */
//
//    private static class FloatFuncDef {
//        final String name;
//
//        FloatFuncDef(String name) {
//            this.name = name;
//        }
//    }
//
//    /* ================= Column occurrence ================= */
//
//    private static class ColumnOccurrence {
//        String column;
//        int start;
//        int end;
//    }
//
//    /* ================= SAFE FUNCTIONS ================= */
//
//    private static final List<FloatFuncDef> SAFE_FUNCS = Arrays.asList(
//            new FloatFuncDef("ABS"),
//            new FloatFuncDef("CEIL"),
//            new FloatFuncDef("CEILING"),
//            new FloatFuncDef("FLOOR"),
//            new FloatFuncDef("ROUND"),
//            new FloatFuncDef("TRUNC"),
//            new FloatFuncDef("SIGN"),
//
//            new FloatFuncDef("SIN"),
//            new FloatFuncDef("COS"),
//            new FloatFuncDef("TAN"),
//            new FloatFuncDef("SINH"),
//            new FloatFuncDef("COSH"),
//            new FloatFuncDef("TANH"),
//
//            new FloatFuncDef("ATAN"),
//            new FloatFuncDef("DEGREES"),
//            new FloatFuncDef("RADIANS"),
//
//            new FloatFuncDef("EXP")
//    );
//
//    /* ================= AGGRESSIVE FUNCTIONS ================= */
//
//    private static final List<FloatFuncDef> AGGRESSIVE_FUNCS = Arrays.asList(
//            new FloatFuncDef("SQRT"),
//            new FloatFuncDef("LN"),
//            new FloatFuncDef("LOG"),
//            new FloatFuncDef("ACOS"),
//            new FloatFuncDef("ASIN"),
//            new FloatFuncDef("ATANH"),
//            new FloatFuncDef("CBRT"),
//            new FloatFuncDef("POW"),
//            new FloatFuncDef("POWER"),
//            new FloatFuncDef("MOD")
//    );
//
//    /* ================= Public Entry ================= */
//
//    public static String inject(String sql) {
//        return inject(sql, 1);
//    }
//
//    public static String inject(String sql, int maxInjectCount) {
//
//        List<ColumnOccurrence> columns = findColumns(sql);
//
//        if (columns.isEmpty()) {
//            return sql;
//        }
//
//        Collections.shuffle(columns);
//
//        int injectCount = Math.max(1,
//                Math.min(maxInjectCount, columns.size()));
//
//        List<ColumnOccurrence> chosen =
//                columns.subList(0, injectCount);
//
//        chosen.sort((a, b) -> Integer.compare(b.start, a.start));
//
//        for (ColumnOccurrence c : chosen) {
//
//            // 控制注入强度（关键）
//            if (RANDOM.nextInt(100) > INJECT_PROB) {
//                continue;
//            }
//
//            FloatFuncDef func = pickFunction();
//
//            String replacement = buildCall(func, c.column);
//
//            sql = sql.substring(0, c.start)
//                    + replacement
//                    + sql.substring(c.end);
//        }
//
//        return sql;
//    }
//
//    /* ================= Function Picker ================= */
//
//    private static FloatFuncDef pickFunction() {
//
//        if (ENABLE_AGGRESSIVE && RANDOM.nextInt(100) < 40) {
//            return AGGRESSIVE_FUNCS.get(
//                    RANDOM.nextInt(AGGRESSIVE_FUNCS.size())
//            );
//        }
//
//        return SAFE_FUNCS.get(
//                RANDOM.nextInt(SAFE_FUNCS.size())
//        );
//    }
//
//    /* ================= Build Call ================= */
//
//    private static String buildCall(FloatFuncDef func, String col) {
//
//        // 统一 FLOAT 化（核心稳定性）
//        String x = "CAST((" + col + ") AS FLOAT)";
//
//        switch (func.name) {
//
//            /* ===== SAFE ===== */
//
//            case "ABS":
//            case "CEIL":
//            case "CEILING":
//            case "FLOOR":
//            case "ROUND":
//            case "TRUNC":
//            case "SIGN":
//            case "SIN":
//            case "COS":
//            case "TAN":
//            case "SINH":
//            case "COSH":
//            case "TANH":
//            case "ATAN":
//            case "DEGREES":
//            case "RADIANS":
//            case "EXP":
//                return func.name + "(" + x + ")";
//
//            /* ===== DOMAIN FIX ===== */
//
//            case "SQRT":
//                return "SQRT(ABS(" + x + "))";
//
//            case "LN":
//                return "LN(ABS(" + x + ") + 1)";
//
//            case "LOG":
//                return "LOG(10, ABS(" + x + ") + 1)";
//
//            case "ACOS":
//            case "ASIN":
//                return func.name +
//                        "(CASE WHEN " + x + " BETWEEN -1 AND 1 THEN " + x + " ELSE 0 END)";
//
//            case "ATANH":
//                return "ATANH(CASE WHEN ABS(" + x + ") < 1 THEN " + x + " ELSE 0 END)";
//
//            case "CBRT":
//                return "CBRT(" + x + ")";
//
//            case "POW":
//            case "POWER":
//                return func.name + "(" + x + ", 2)";
//
//            case "MOD":
//                return "MOD(" + x + ", 2)";
//
//        }
//
//        throw new AssertionError(func.name);
//    }
//
//    /* ================= Column finder ================= */
//
//    private static List<ColumnOccurrence> findColumns(String sql) {
//
//        List<ColumnOccurrence> result = new ArrayList<>();
//
//        Pattern colPattern = Pattern.compile(
//                "\\b[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z0-9_]+\\b",
//                Pattern.CASE_INSENSITIVE
//        );
//
//        Matcher m = colPattern.matcher(sql);
//
//        while (m.find()) {
//
//            String col = m.group();
//
//            if (isUnsafeColumn(col)) {
//                continue;
//            }
//
//            ColumnOccurrence c = new ColumnOccurrence();
//            c.column = col;
//            c.start = m.start();
//            c.end = m.end();
//
//            result.add(c);
//        }
//
//        return result;
//    }
//
//    /* ================= Unsafe filter ================= */
//
//    private static boolean isUnsafeColumn(String col) {
//
//        String lower = col.toLowerCase();
//
//        return lower.endsWith(".rowid")
//                || lower.contains("uuid")
//                || lower.contains("hash")
//                || lower.contains("random");
//    }
//}

package sqlancer.cockroachdb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CockroachFloatFunctionInjector {

    private static final Random RANDOM = new Random();

    private static class FloatFunc {
        final String name;
        final int arity;

        FloatFunc(String name, int arity) {
            this.name = name;
            this.arity = arity;
        }
    }

    private static class ColumnOccurrence {
        String column;
        int start;
        int end;
    }

    /* ================= FULL FUNCTION SET ================= */

    private static final List<FloatFunc> FUNCS = Arrays.asList(

            new FloatFunc("ABS",1),

            new FloatFunc("ACOS",1),
            new FloatFunc("ACOSD",1),
            new FloatFunc("ACOSH",1),

            new FloatFunc("ASIN",1),
            new FloatFunc("ASIND",1),
            new FloatFunc("ASINH",1),

            new FloatFunc("ATAN",1),
            new FloatFunc("ATAND",1),
            new FloatFunc("ATANH",1),

            new FloatFunc("ATAN2",2),
            new FloatFunc("ATAN2D",2),

            new FloatFunc("COS",1),
            new FloatFunc("COSD",1),
            new FloatFunc("COSH",1),

            new FloatFunc("SIN",1),
            new FloatFunc("SIND",1),
            new FloatFunc("SINH",1),

            new FloatFunc("TAN",1),
            new FloatFunc("TAND",1),
            new FloatFunc("TANH",1),

            new FloatFunc("COT",1),
            new FloatFunc("COTD",1),

            new FloatFunc("CEIL",1),
            new FloatFunc("CEILING",1),
            new FloatFunc("FLOOR",1),

            new FloatFunc("ROUND",1),
            new FloatFunc("TRUNC",1),

            new FloatFunc("SIGN",1),

            new FloatFunc("EXP",1),
            new FloatFunc("LN",1),

            new FloatFunc("LOG",1),   // overloaded

            new FloatFunc("MOD",2),
            new FloatFunc("DIV",2),

            new FloatFunc("POW",2),
            new FloatFunc("POWER",2),

            new FloatFunc("DEGREES",1),
            new FloatFunc("RADIANS",1),

            new FloatFunc("SQRT",1),
            new FloatFunc("CBRT",1),

            new FloatFunc("ISNAN",1),

            new FloatFunc("PI",0)
    );

    /* ================= PUBLIC ================= */

    public static String inject(String sql) {
        return inject(sql, 1);
    }

    public static String inject(String sql, int max) {

        List<ColumnOccurrence> cols = findColumns(sql);

        if (cols.isEmpty()) return sql;

        Collections.shuffle(cols);

        int n = Math.min(max, cols.size());

        List<ColumnOccurrence> chosen = cols.subList(0, n);

        chosen.sort((a,b) -> Integer.compare(b.start, a.start));

        for (ColumnOccurrence c : chosen) {

            FloatFunc f = FUNCS.get(RANDOM.nextInt(FUNCS.size()));

            String replacement = build(f, c.column);

            sql = sql.substring(0, c.start)
                    + replacement
                    + sql.substring(c.end);
        }

        return sql;
    }

    /* ================= BUILDER ================= */

    private static String build(FloatFunc f, String col) {

        switch (f.arity) {

            case 0:
                return f.name + "()";

            case 1:
                return f.name + "(" + safe(col) + ")";

            case 2:
                return buildBinary(f.name, col);

            default:
                return f.name + "(" + col + ")";
        }
    }

    private static String buildBinary(String f, String col) {

        String other = "2";

        switch (f) {

            case "LOG":
                return "LOG(10, " + safe(col) + ")";

            case "ATAN2":
                return "ATAN2(" + safe(col) + "," + other + ")";

            case "ATAN2D":
                return "ATAN2D(" + safe(col) + "," + other + ")";

            case "MOD":
                return "MOD(" + safe(col) + "," + other + ")";

            case "DIV":
                return "DIV(" + safe(col) + "," + other + ")";

            case "POW":
            case "POWER":
                return f + "(" + safe(col) + "," + other + ")";
        }

        return f + "(" + safe(col) + "," + other + ")";
    }

    /* ================= SAFETY ================= */

    private static String safe(String col) {

        String c = col.toLowerCase();

        if (c.contains("timestamp")
                || c.contains("interval")
                || c.contains("text")) {
            return "0.1";
        }

        return col;
    }

    /* ================= COLUMN FINDER ================= */

    private static List<ColumnOccurrence> findColumns(String sql) {

        List<ColumnOccurrence> out = new ArrayList<>();

        Pattern p = Pattern.compile(
                "\\b[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z0-9_]+\\b"
        );

        Matcher m = p.matcher(sql);

        while (m.find()) {

            ColumnOccurrence c = new ColumnOccurrence();
            c.column = m.group();
            c.start = m.start();
            c.end = m.end();

            if (isUnsafe(c.column)) continue;

            out.add(c);
        }

        return out;
    }

    private static boolean isUnsafe(String col) {

        String l = col.toLowerCase();

        return l.contains("rowid")
                || l.contains("uuid")
                || l.contains("hash");
    }
}