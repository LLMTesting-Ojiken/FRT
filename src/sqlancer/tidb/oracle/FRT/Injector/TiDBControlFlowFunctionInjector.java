package sqlancer.tidb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TiDB Control Flow Function Injector
 */
public class TiDBControlFlowFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= Function Definition ================= */

    private static class FuncDef {

        final String name;

        FuncDef(String name) {
            this.name = name;
        }
    }

    /* ================= Column occurrence ================= */

    private static class ColumnOccurrence {

        String column;
        int start;
        int end;
        boolean insideControl;
    }

    /* ================= Supported Functions ================= */

    private static final List<FuncDef> CONTROL_FUNCS = Arrays.asList(

            new FuncDef("IF"),
            new FuncDef("IFNULL"),
            new FuncDef("NULLIF"),
            new FuncDef("CASE")
    );

    private static final Set<String> FUNC_NAMES = new HashSet<>();

    static {
        for (FuncDef f : CONTROL_FUNCS) {
            FUNC_NAMES.add(f.name);
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

            FuncDef func = CONTROL_FUNCS.get(
                    RANDOM.nextInt(CONTROL_FUNCS.size())
            );

            String replacement = buildCall(func, c.column);

            sql = sql.substring(0, c.start)
                    + replacement
                    + sql.substring(c.end);
        }

        /* ✅ 关键：统一包一层，保证和 Aggregate 能组合 */
        sql = "SELECT * FROM (" + sql + ") t";

        return sql;
    }

    /* ================= Function Builder ================= */

    private static String buildCall(FuncDef func, String col) {

        if ("IF".equals(func.name)) {

            String cond = randomCondition(col);
            String alt = randomConstant();

            return "IF(" + cond + ", " + col + ", " + alt + ")";
        }

        if ("IFNULL".equals(func.name)) {

            String alt = randomConstant();

            return "IFNULL(" + col + ", " + alt + ")";
        }

        if ("NULLIF".equals(func.name)) {

            String other = randomConstant();

            return "NULLIF(" + col + ", " + other + ")";
        }

        if ("CASE".equals(func.name)) {

            String cond = randomCondition(col);
            String alt = randomConstant();

            return "CASE WHEN " + cond
                    + " THEN " + col
                    + " ELSE " + alt
                    + " END";
        }

        throw new AssertionError("Unsupported control function: " + func.name);
    }

    /* ================= Helpers ================= */

    private static String randomCondition(String col) {

        switch (RANDOM.nextInt(4)) {
            case 0:
                return col + " > 0";
            case 1:
                return col + " < 0";
            case 2:
                return col + " IS NULL";
            default:
                return col + " IS NOT NULL";
        }
    }

    /* ⚠️ 修复：避免类型冲突 */
    private static String randomConstant() {

        switch (RANDOM.nextInt(3)) {
            case 0:
                return "0";
            case 1:
                return "1";
            default:
                return "NULL";
        }
    }

    /* ================= Column Finder ================= */

    private static List<ColumnOccurrence> findColumns(String sql) {

        List<ColumnOccurrence> result = new ArrayList<>();

        Pattern colPattern = Pattern.compile(
                "\\bt\\d+\\.c\\d+\\b",
                Pattern.CASE_INSENSITIVE
        );

        Pattern funcPattern = Pattern.compile(
                "\\b(" + String.join("|", FUNC_NAMES) + ")\\s*\\(",
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

            occ.insideControl = isInsideFunction(sql, occ.start, funcPattern);

            if (!occ.insideControl) {
                result.add(occ);
            }
        }

        return result;
    }

    /* ================= Nested Function Check ================= */

    private static boolean isInsideFunction(
            String sql,
            int pos,
            Pattern funcPattern) {

        Matcher m = funcPattern.matcher(sql);

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

    /* ================= String Guard ================= */

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