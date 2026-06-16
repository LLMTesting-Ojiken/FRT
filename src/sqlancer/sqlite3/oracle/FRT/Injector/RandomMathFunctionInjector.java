package sqlancer.sqlite3.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.*;

/**
 * RandomMathFunctionInjector
 * <p>
 * 语义保证：
 * 1. 仅对 SELECT 中【未处于聚合函数作用域内】的 column reference 注入
 * 2. 不处理 / 不修改 GROUP BY
 * 3. 注入函数仅来自 SQLite math functions (ENABLE_MATH_FUNCTIONS)
 * 4. 支持 tN.cM、vtN.cM、vN.cM 等各种表前缀
 * 5. 跳过字符串字面量
 * 6. 仅做包裹，不改变 SELECT 列数量
 * 7. 每次最多注入 maxInjectCount 个 column
 */
public class RandomMathFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= 聚合函数集合（仅用于识别作用域） ================= */
    private static final Set<String> AGG_FUNCTIONS = new HashSet<>(Arrays.asList(
            "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
            "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
            "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
    ));

    /* ================= SQLite Math Functions ================= */
    private static final List<MathFuncDef> MATH_FUNCS = Arrays.asList(
            new MathFuncDef("ACOS", 1),
            new MathFuncDef("ACOSH", 1),
            new MathFuncDef("ASIN", 1),
            new MathFuncDef("ASINH", 1),
            new MathFuncDef("ATAN", 1),
            new MathFuncDef("ATAN2", 2),
            new MathFuncDef("ATANH", 1),
            new MathFuncDef("CEIL", 1),
            new MathFuncDef("CEILING", 1),
            new MathFuncDef("COS", 1),
            new MathFuncDef("COSH", 1),
            new MathFuncDef("DEGREES", 1),
            new MathFuncDef("EXP", 1),
            new MathFuncDef("FLOOR", 1),
            new MathFuncDef("LN", 1),
            new MathFuncDef("LOG", 1),
            new MathFuncDef("LOG", 2),
            new MathFuncDef("LOG10", 1),
            new MathFuncDef("LOG2", 1),
            new MathFuncDef("MOD", 2),
            new MathFuncDef("PI", 0),
            new MathFuncDef("POW", 2),
            new MathFuncDef("POWER", 2),
            new MathFuncDef("RADIANS", 1),
            new MathFuncDef("SIN", 1),
            new MathFuncDef("SINH", 1),
            new MathFuncDef("SQRT", 1),
            new MathFuncDef("TAN", 1),
            new MathFuncDef("TANH", 1),
            new MathFuncDef("TRUNC", 1)
    );

    private static class MathFuncDef {
        String name;
        int argCount;

        MathFuncDef(String name, int argCount) {
            this.name = name;
            this.argCount = argCount;
        }
    }

    /* ================= Column occurrence ================= */
    private static class ColumnOccurrence {
        String column; // t0.c0
        int start;     // SQL 中起始位置
        int end;       // SQL 中结束位置
        boolean insideAgg;
    }

    /* ================= SELECT 列提取 ================= */
    public static List<String> extractSelectColumns(String sql) {
        List<String> columns = new ArrayList<>();

        Pattern p = Pattern.compile("(?i)^SELECT\\s+(.*?)\\s+FROM\\s", Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        if (!m.find()) {
            return columns;
        }

        String clause = m.group(1);

        int depth = 0;
        boolean inSingle = false, inDouble = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);

            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;

            if (!inSingle && !inDouble) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }

            if (c == ',' && depth == 0 && !inSingle && !inDouble) {
                columns.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }

        if (sb.length() > 0) {
            columns.add(sb.toString().trim());
        }

        return columns;
    }

    /* ================= 查找列及聚合函数作用域 ================= */
    private static List<ColumnOccurrence> findColumnsWithAggScope(String expr, int baseOffset) {
        List<ColumnOccurrence> result = new ArrayList<>();

        Pattern aggCall = Pattern.compile("\\b(" + String.join("|", AGG_FUNCTIONS) + ")\\s*\\(",
                Pattern.CASE_INSENSITIVE);

        Pattern colRef = Pattern.compile("\\b[a-zA-Z]+\\d*\\.c\\d+\\b",
                Pattern.CASE_INSENSITIVE);

        // 找聚合函数内部区间
        Matcher aggMatcher = aggCall.matcher(expr);
        List<int[]> aggIntervals = new ArrayList<>();

        while (aggMatcher.find()) {
            int start = aggMatcher.start();
            int openPar = expr.indexOf('(', start);
            if (openPar == -1) continue;

            int closePar = findMatchingParen(expr, openPar);
            if (closePar == -1) continue;

            aggIntervals.add(new int[]{openPar + 1, closePar});
        }

        // 遍历匹配列，跳过字符串字面量
        for (Matcher colMatcher = colRef.matcher(expr); colMatcher.find(); ) {
            int colStart = colMatcher.start();
            int colEnd = colMatcher.end();

            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < colStart; i++) {
                char c = expr.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                else if (c == '"' && !inSingle) inDouble = !inDouble;
            }
            if (inSingle || inDouble) continue;

            ColumnOccurrence occ = new ColumnOccurrence();
            occ.column = expr.substring(colStart, colEnd);
            occ.start = baseOffset + colStart;
            occ.end = baseOffset + colEnd;
            occ.insideAgg = false;

            for (int[] interval : aggIntervals) {
                if (colStart >= interval[0] && colEnd <= interval[1]) {
                    occ.insideAgg = true;
                    break;
                }
            }

            result.add(occ);
        }

        return result;
    }

    public static int findMatchingParen(String s, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0) return i;
        }
        return -1;
    }

    /* ================= 生成额外参数（用于二元函数） ================= */
    private static String generateExtraArg(MathFuncDef func) {
        switch (func.name) {
            case "POW":
            case "POWER":
                return RANDOM.nextBoolean() ? "-1" : "2";
            case "LOG":
                return RANDOM.nextBoolean() ? "-1" : "10";
            case "MOD":
                return RANDOM.nextBoolean() ? "0" : "2";
            case "ATAN2":
                return RANDOM.nextBoolean() ? "0" : "1";
            default:
                return String.valueOf(RANDOM.nextInt(5) - 2); // [-2,2]
        }
    }

    /* ================= 核心注入逻辑（带注入数量限制） ================= */
    public static String injectSmartMathFunction(String sql, int maxInjectCount) {

        if (maxInjectCount <= 0) {
            return sql;
        }

        List<String> selectItems = extractSelectColumns(sql);
        if (selectItems.isEmpty()) return sql;

        List<ColumnOccurrence> allColumns = new ArrayList<>();
        List<ColumnOccurrence> injectableColumns = new ArrayList<>();

        int selectStart = sql.toUpperCase().indexOf("SELECT") + 6;
        int cursor = selectStart;

        for (String item : selectItems) {
            int itemPos = sql.indexOf(item, cursor);
            if (itemPos == -1) itemPos = cursor;
            cursor = itemPos + item.length();

            List<ColumnOccurrence> cols = findColumnsWithAggScope(item, itemPos);
            for (ColumnOccurrence c : cols) {
                allColumns.add(c);
                if (!c.insideAgg) {
                    injectableColumns.add(c);
                }
            }
        }

        // 没有可注入列
        if (injectableColumns.isEmpty()) return sql;

        // 随机选择要注入的列
        Collections.shuffle(injectableColumns, RANDOM);
        int injectCount = Math.min(maxInjectCount, injectableColumns.size());
        List<ColumnOccurrence> toInject = injectableColumns.subList(0, injectCount);

        // 从后往前替换
        toInject.sort((a, b) -> Integer.compare(b.start, a.start));

        for (ColumnOccurrence c : toInject) {
            MathFuncDef func = MATH_FUNCS.get(RANDOM.nextInt(MATH_FUNCS.size()));

            String replacement;
            if (func.argCount == 0) {
                replacement = func.name + "()"; // PI()
            } else if (func.argCount == 1) {
                // ✅ CAST 作用在 column 上
                replacement = func.name + "(CAST(" + c.column + " AS NUMERIC))";
            } else {
                // 二元函数：第一个参数是 CAST(column AS NUMERIC)
                replacement = func.name + "(CAST(" + c.column + " AS NUMERIC), "
                        + generateExtraArg(func) + ")";
            }

            sql = sql.substring(0, c.start) + replacement + sql.substring(c.end);
        }

        return sql;
    }
}