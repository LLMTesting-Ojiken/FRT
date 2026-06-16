package sqlancer.sqlite3.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.*;

/**
 * RandomJsonFunctionInjector
 *
 * 语义保证：
 * 1. 仅对 SELECT 中【未处于聚合函数作用域内】的 column reference 注入
 * 2. 不处理 / 不修改 GROUP BY
 * 3. 注入函数仅来自 SQLite scalar JSON functions
 * 4. 支持 tN.cM、vtN.cM、vN.cM 等各种表前缀
 * 5. 跳过字符串字面量
 * 6. 仅做包裹，不改变 SELECT 列数量
 * 7. 每次最多注入 maxInjectCount 个 column
 */
public class RandomJsonFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= 聚合函数集合（仅用于识别作用域） ================= */
    private static final Set<String> AGG_FUNCTIONS = new HashSet<String>(Arrays.asList(
            "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
            "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
            "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
    ));

    /* ================= JSON Function 定义 ================= */
    private static class JsonFuncDef {
        String name;

        JsonFuncDef(String name, int argCount) {
            this.name = name;
        }
    }

    /* ================= SQLite scalar JSON functions ================= */
    private static final List<JsonFuncDef> JSON_FUNCS = new ArrayList<JsonFuncDef>();

    static {
        JSON_FUNCS.add(new JsonFuncDef("json", 1));
        JSON_FUNCS.add(new JsonFuncDef("jsonb", 1));
        JSON_FUNCS.add(new JsonFuncDef("json_pretty", 1));
        JSON_FUNCS.add(new JsonFuncDef("json_error_position", 1));

        JSON_FUNCS.add(new JsonFuncDef("json_extract", 2));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_extract", 2));

        JSON_FUNCS.add(new JsonFuncDef("json_set", 3));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_set", 3));
        JSON_FUNCS.add(new JsonFuncDef("json_replace", 3));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_replace", 3));

        JSON_FUNCS.add(new JsonFuncDef("json_remove", 2));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_remove", 2));

        JSON_FUNCS.add(new JsonFuncDef("json_patch", 2));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_patch", 2));

        JSON_FUNCS.add(new JsonFuncDef("json_object", 2));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_object", 2));

        JSON_FUNCS.add(new JsonFuncDef("json_array", 1));
        JSON_FUNCS.add(new JsonFuncDef("jsonb_array", 1));
    }

    /* ================= Column occurrence ================= */
    private static class ColumnOccurrence {
        String column; // t0.c0
        int start;
        int end;
        boolean insideAgg;
    }

    /* ================= SELECT 列提取 ================= */
    public static List<String> extractSelectColumns(String sql) {
        List<String> columns = new ArrayList<String>();

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
        List<ColumnOccurrence> result = new ArrayList<ColumnOccurrence>();

        Pattern aggCall = Pattern.compile("\\b(" + String.join("|", AGG_FUNCTIONS) + ")\\s*\\(",
                Pattern.CASE_INSENSITIVE);

        Pattern colRef = Pattern.compile("\\b[a-zA-Z]+\\d*\\.c\\d+\\b",
                Pattern.CASE_INSENSITIVE);

        Matcher aggMatcher = aggCall.matcher(expr);
        List<int[]> aggIntervals = new ArrayList<int[]>();

        while (aggMatcher.find()) {
            int start = aggMatcher.start();
            int openPar = expr.indexOf('(', start);
            if (openPar == -1) continue;

            int closePar = findMatchingParen(expr, openPar);
            if (closePar == -1) continue;

            aggIntervals.add(new int[]{openPar + 1, closePar});
        }

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

    /* ================= 构造 JSON 调用 ================= */
    private static String buildCall(JsonFuncDef f, String col) {
        String jcol = "json(" + col + ")";

        if (f.name.equals("json")) return "json(" + col + ")";
        if (f.name.equals("jsonb")) return "jsonb(" + jcol + ")";
        if (f.name.equals("json_pretty")) return "json_pretty(" + jcol + ")";
        if (f.name.equals("json_error_position")) return "json_error_position(" + jcol + ")";

        if (f.name.equals("json_extract")) return "json_extract(" + jcol + ", '$')";
        if (f.name.equals("jsonb_extract")) return "jsonb_extract(" + jcol + ", '$')";

        if (f.name.equals("json_set")) return "json_set(" + jcol + ", '$', " + jcol + ")";
        if (f.name.equals("jsonb_set")) return "jsonb_set(" + jcol + ", '$', " + jcol + ")";
        if (f.name.equals("json_replace")) return "json_replace(" + jcol + ", '$', " + jcol + ")";
        if (f.name.equals("jsonb_replace")) return "jsonb_replace(" + jcol + ", '$', " + jcol + ")";

        if (f.name.equals("json_remove")) return "json_remove(" + jcol + ", '$')";
        if (f.name.equals("jsonb_remove")) return "jsonb_remove(" + jcol + ", '$')";

        if (f.name.equals("json_patch")) return "json_patch(" + jcol + ", " + jcol + ")";
        if (f.name.equals("jsonb_patch")) return "jsonb_patch(" + jcol + ", " + jcol + ")";

        if (f.name.equals("json_object")) return "json_object('k', " + jcol + ")";
        if (f.name.equals("jsonb_object")) return "jsonb_object('k', " + jcol + ")";

        if (f.name.equals("json_array")) return "json_array(" + jcol + ")";
        if (f.name.equals("jsonb_array")) return "jsonb_array(" + jcol + ")";

        return col;
    }

    /* ================= 核心注入逻辑 ================= */
    public static String injectSmartJsonFunction(String sql, int maxInjectCount) {

        if (maxInjectCount <= 0) return sql;

        List<String> selectItems = extractSelectColumns(sql);
        if (selectItems.isEmpty()) return sql;

        List<ColumnOccurrence> injectableColumns = new ArrayList<ColumnOccurrence>();

        int selectStart = sql.toUpperCase().indexOf("SELECT") + 6;
        int cursor = selectStart;

        for (String item : selectItems) {
            int itemPos = sql.indexOf(item, cursor);
            if (itemPos == -1) itemPos = cursor;
            cursor = itemPos + item.length();

            List<ColumnOccurrence> cols = findColumnsWithAggScope(item, itemPos);
            for (ColumnOccurrence c : cols) {
                if (!c.insideAgg) {
                    injectableColumns.add(c);
                }
            }
        }

        if (injectableColumns.isEmpty()) return sql;

        Collections.shuffle(injectableColumns, RANDOM);
        int injectCount = Math.min(maxInjectCount, injectableColumns.size());
        List<ColumnOccurrence> toInject = injectableColumns.subList(0, injectCount);

        toInject.sort(new Comparator<ColumnOccurrence>() {
            public int compare(ColumnOccurrence a, ColumnOccurrence b) {
                return b.start - a.start;
            }
        });

        for (ColumnOccurrence c : toInject) {
            JsonFuncDef func = JSON_FUNCS.get(RANDOM.nextInt(JSON_FUNCS.size()));
            String replacement = buildCall(func, c.column);
            sql = sql.substring(0, c.start) + replacement + sql.substring(c.end);
        }

        return sql;
    }
}