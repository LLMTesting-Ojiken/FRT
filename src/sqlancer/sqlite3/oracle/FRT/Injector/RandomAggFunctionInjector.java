package sqlancer.sqlite3.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.*;

/**
 * RandomAggFunctionInjector
 *
 * 语义保证：
 * 1. 仅当 SELECT 中存在【混合聚合】时才注入
 * 2. 对「未处于聚合函数作用域内」的 column reference 进行包裹（数量受 maxInjectCount 限制）
 * 3. 注入函数仅来自 EXTRA_FUNCS
 * 4. 不处理 / 不修改 GROUP BY
 * 5. 支持 tN.cM、vtN.cM、vN.cM 等各种表前缀，避免字符串字面量内误匹配
 */
public class RandomAggFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= 聚合函数定义 ================= */
    private static final List<AggFuncDef> EXTRA_FUNCS = Arrays.asList(
            new AggFuncDef("MEDIAN", 1),
            new AggFuncDef("STRING_AGG", 2),
            new AggFuncDef("PERCENTILE", 2),
            new AggFuncDef("PERCENTILE_DISC", 2),
            new AggFuncDef("PERCENTILE_CONT", 2)
    );

    private static final Set<String> AGG_FUNCTIONS = new HashSet<>(Arrays.asList(
            "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
            "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
            "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
    ));

    private static class AggFuncDef {
        String name;
        int argCount;

        AggFuncDef(String name, int argCount) {
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

        // 遍历匹配列（跳过字符串字面量）
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

    /* ================= 生成额外参数 ================= */
    private static String generateExtraArg(AggFuncDef func) {
        switch (func.name) {
            case "STRING_AGG":
                return "'|'";
            case "PERCENTILE":
                return String.valueOf(RANDOM.nextDouble() * 100.0);
            case "PERCENTILE_DISC":
            case "PERCENTILE_CONT":
                return String.valueOf(RANDOM.nextDouble());
            default:
                return "NULL";
        }
    }

    /* ================= 核心注入逻辑（带注入数量限制） ================= */
    public static String injectSmartAggFunction(String sql, int maxInjectCount) {

        if (maxInjectCount <= 0) {
            return sql;
        }

        List<String> selectItems = extractSelectColumns(sql);
        if (selectItems.isEmpty()) return sql;

        boolean hasAgg = false;
        boolean hasNonAgg = false;

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
                if (c.insideAgg) {
                    hasAgg = true;
                } else {
                    hasNonAgg = true;
                    injectableColumns.add(c);
                }
            }
        }

        // 不是混合聚合，直接返回
        if (!hasAgg || !hasNonAgg) return sql;

        // 没有可注入列
        if (injectableColumns.isEmpty()) return sql;

        // 随机选择要注入的列
        Collections.shuffle(injectableColumns, RANDOM);
        int injectCount = Math.min(maxInjectCount, injectableColumns.size());
        List<ColumnOccurrence> toInject = injectableColumns.subList(0, injectCount);

        // 从后往前替换，避免 offset 失效
        toInject.sort((a, b) -> Integer.compare(b.start, a.start));

        for (ColumnOccurrence c : toInject) {
            AggFuncDef func = EXTRA_FUNCS.get(RANDOM.nextInt(EXTRA_FUNCS.size()));
            List<String> args = new ArrayList<>();
            args.add(c.column);

            if (func.argCount > 1) {
                args.add(generateExtraArg(func));
            }

            String replacement = func.name + "(" + String.join(", ", args) + ")";
            sql = sql.substring(0, c.start) + replacement + sql.substring(c.end);
        }

        return sql;
    }
}