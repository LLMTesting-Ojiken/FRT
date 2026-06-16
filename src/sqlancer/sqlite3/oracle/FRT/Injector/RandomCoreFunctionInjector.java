package sqlancer.sqlite3.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.*;

/**
 * RandomCoreFunctionInjector (Safe Version, Java8)
 *
 * 语义保证：
 * 1. 仅对 SELECT 中【未处于聚合函数作用域内】的 column reference 注入
 * 2. 注入函数仅来自 SQLite core scalar functions
 * 3. 支持 tN.cM、vtN.cM、vN.cM 等各种表前缀
 * 4. 跳过字符串字面量
 * 5. 不处理 / 不修改 GROUP BY
 * 6. 注入数量由 maxInjectCount 控制
 * 7. 参数生成遵守 SQLite 文档语义（尽量避免 runtime error）
 */
public class RandomCoreFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= 聚合函数集合（仅用于识别作用域） ================= */
    private static final Set<String> AGG_FUNCTIONS = new HashSet<>(Arrays.asList(
            "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
            "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
            "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
    ));

    /* ================= Core Function 定义 ================= */
    private static final List<CoreFuncDef> CORE_FUNCS = Arrays.asList(
            new CoreFuncDef("CONCAT", 1),
            new CoreFuncDef("CONCAT_WS", 2),
            new CoreFuncDef("FORMAT", 2),
            new CoreFuncDef("IF", 3),
            new CoreFuncDef("IIF", 3),
            new CoreFuncDef("OCTET_LENGTH", 1),
            new CoreFuncDef("RANDOM", 0),
            new CoreFuncDef("RANDOMBLOB", 1),
            new CoreFuncDef("REPLACE", 3),
            new CoreFuncDef("SIGN", 1),
            new CoreFuncDef("SOUNDEX", 1),
            new CoreFuncDef("SQLITE_OFFSET", 1),
            new CoreFuncDef("SUBSTRING", 2),
            new CoreFuncDef("UNHEX", 1),
            new CoreFuncDef("UNISTR", 1),
            new CoreFuncDef("UNISTR_QUOTE", 1),
            new CoreFuncDef("ZEROBLOB", 1)
    );

    /* ================= 每个函数的安全参数模板（Java8 版） ================= */
    private static final Map<String, List<String>> SAFE_EXTRA_ARGS = new HashMap<>();

    static {
        SAFE_EXTRA_ARGS.put("CONCAT", Arrays.asList("'x'"));
        SAFE_EXTRA_ARGS.put("CONCAT_WS", Arrays.asList("','"));
        SAFE_EXTRA_ARGS.put("FORMAT", Arrays.asList("'%s'"));
        SAFE_EXTRA_ARGS.put("IF", Arrays.asList("1", "0"));
        SAFE_EXTRA_ARGS.put("IIF", Arrays.asList("1", "0"));
        SAFE_EXTRA_ARGS.put("OCTET_LENGTH", Collections.<String>emptyList());
        SAFE_EXTRA_ARGS.put("RANDOM", Collections.<String>emptyList());
        SAFE_EXTRA_ARGS.put("RANDOMBLOB", Arrays.asList("1"));
        SAFE_EXTRA_ARGS.put("REPLACE", Arrays.asList("'a'", "'b'"));
        SAFE_EXTRA_ARGS.put("SIGN", Collections.<String>emptyList());
        SAFE_EXTRA_ARGS.put("SOUNDEX", Collections.<String>emptyList());
        SAFE_EXTRA_ARGS.put("SQLITE_OFFSET", Arrays.asList("1"));
        SAFE_EXTRA_ARGS.put("SUBSTRING", Arrays.asList("1"));
        SAFE_EXTRA_ARGS.put("UNHEX", Collections.<String>emptyList());
        SAFE_EXTRA_ARGS.put("UNISTR", Arrays.asList("'\\u0061'"));
        SAFE_EXTRA_ARGS.put("UNISTR_QUOTE", Arrays.asList("'\\u0061'"));
        SAFE_EXTRA_ARGS.put("ZEROBLOB", Arrays.asList("1"));
    }

    private static class CoreFuncDef {
        String name;
        int argCount;

        CoreFuncDef(String name, int argCount) {
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

    /* ================= 核心注入逻辑 ================= */
    public static String injectRandomCoreFunction(String sql, int maxInjectCount) {

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
            @Override
            public int compare(ColumnOccurrence a, ColumnOccurrence b) {
                return Integer.compare(b.start, a.start);
            }
        });

        for (ColumnOccurrence c : toInject) {
            CoreFuncDef func = CORE_FUNCS.get(RANDOM.nextInt(CORE_FUNCS.size()));

            List<String> args = new ArrayList<String>();
            args.add(c.column);

            List<String> extras = SAFE_EXTRA_ARGS.get(func.name);
            if (extras != null) {
                args.addAll(extras);
            }

            while (args.size() < func.argCount) {
                args.add("1");
            }

            String replacement = func.name + "(" + String.join(", ", args) + ")";
            sql = sql.substring(0, c.start) + replacement + sql.substring(c.end);
        }

        return sql;
    }
}