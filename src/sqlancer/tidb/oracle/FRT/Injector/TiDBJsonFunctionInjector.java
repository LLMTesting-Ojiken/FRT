package sqlancer.tidb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TiDB JSON Function Injector (Full Version)
 */
public class TiDBJsonFunctionInjector {

    private static final Random RANDOM = new Random();

    /* ================= JSON Function Definition ================= */

    private static class JsonFuncDef {

        final String name;

        JsonFuncDef(String name) {
            this.name = name;
        }
    }

    /* ================= Column occurrence ================= */

    private static class ColumnOccurrence {

        String column;
        int start;
        int end;
        boolean insideFunc;
    }

    /* ================= JSON Functions (Core Coverage) ================= */

    private static final List<JsonFuncDef> JSON_FUNCS = Arrays.asList(

            /* Create */
            new JsonFuncDef("JSON_ARRAY"),
            new JsonFuncDef("JSON_OBJECT"),
            new JsonFuncDef("JSON_QUOTE"),

            /* Search */
            new JsonFuncDef("JSON_EXTRACT"),
            new JsonFuncDef("JSON_KEYS"),
            new JsonFuncDef("JSON_CONTAINS"),
            new JsonFuncDef("JSON_OVERLAPS"),

            /* Modify */
            new JsonFuncDef("JSON_SET"),
            new JsonFuncDef("JSON_INSERT"),
            new JsonFuncDef("JSON_REPLACE"),
            new JsonFuncDef("JSON_REMOVE"),
            new JsonFuncDef("JSON_ARRAY_APPEND"),
            new JsonFuncDef("JSON_ARRAY_INSERT"),
            new JsonFuncDef("JSON_MERGE_PATCH"),
            new JsonFuncDef("JSON_MERGE_PRESERVE"),

            /* Return */
            new JsonFuncDef("JSON_LENGTH"),
            new JsonFuncDef("JSON_TYPE"),
            new JsonFuncDef("JSON_VALID"),
            new JsonFuncDef("JSON_DEPTH"),

            /* Utility */
            new JsonFuncDef("JSON_UNQUOTE")
    );

    private static final Set<String> JSON_NAMES = new HashSet<>();

    static {
        for (JsonFuncDef f : JSON_FUNCS) {
            JSON_NAMES.add(f.name);
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

            JsonFuncDef func = JSON_FUNCS.get(
                    RANDOM.nextInt(JSON_FUNCS.size())
            );

            String replacement = buildJsonCall(func.name, c.column);

            sql = sql.substring(0, c.start)
                    + replacement
                    + sql.substring(c.end);
        }

        return sql;
    }

    /* ================= JSON Builder ================= */

    private static String buildJsonCall(String func, String col) {

        switch (func) {

            /* ===== Create ===== */

            case "JSON_ARRAY":
                return "JSON_ARRAY(" + col + ")";

            case "JSON_OBJECT":
                return "JSON_OBJECT('k', " + col + ")";

            case "JSON_QUOTE":
                return "JSON_QUOTE(" + col + ")";

            /* ===== Search ===== */

            case "JSON_EXTRACT":
                return "JSON_EXTRACT(" + wrapObj(col) + ", '$.k')";

            case "JSON_KEYS":
                return "JSON_KEYS(" + wrapObj(col) + ")";

            case "JSON_CONTAINS":
                return "JSON_CONTAINS(" + wrapArr(col) + ", " + wrapArr(col) + ")";

            case "JSON_OVERLAPS":
                return "JSON_OVERLAPS(" + wrapArr(col) + ", " + wrapArr(col) + ")";

            /* ===== Modify ===== */

            case "JSON_SET":
                return "JSON_SET(" + wrapObj(col) + ", '$.k', 1)";

            case "JSON_INSERT":
                return "JSON_INSERT(" + wrapObj(col) + ", '$.k', 1)";

            case "JSON_REPLACE":
                return "JSON_REPLACE(" + wrapObj(col) + ", '$.k', 1)";

            case "JSON_REMOVE":
                return "JSON_REMOVE(" + wrapObj(col) + ", '$.k')";

            case "JSON_ARRAY_APPEND":
                return "JSON_ARRAY_APPEND(" + wrapArr(col) + ", '$', 1)";

            case "JSON_ARRAY_INSERT":
                return "JSON_ARRAY_INSERT(" + wrapArr(col) + ", '$[0]', 1)";

            case "JSON_MERGE_PATCH":
                return "JSON_MERGE_PATCH(" + wrapObj(col) + ", " + wrapObj(col) + ")";

            case "JSON_MERGE_PRESERVE":
                return "JSON_MERGE_PRESERVE(" + wrapObj(col) + ", " + wrapObj(col) + ")";

            /* ===== Return ===== */

            case "JSON_LENGTH":
                return "JSON_LENGTH(" + wrapArr(col) + ")";

            case "JSON_TYPE":
                return "JSON_TYPE(" + wrapArr(col) + ")";

            case "JSON_VALID":
                return "JSON_VALID(" + col + ")";

            case "JSON_DEPTH":
                return "JSON_DEPTH(" + wrapArr(col) + ")";

            /* ===== Utility ===== */

            case "JSON_UNQUOTE":
                return "JSON_UNQUOTE(" + wrapArr(col) + ")";
        }

        throw new AssertionError("Unsupported JSON function: " + func);
    }

    /* ================= Helper Wrappers ================= */

    private static String wrapObj(String col) {
        return "JSON_OBJECT('k', " + col + ")";
    }

    private static String wrapArr(String col) {
        return "JSON_ARRAY(" + col + ")";
    }

    /* ================= Column Finder ================= */

    private static List<ColumnOccurrence> findColumns(String sql) {

        List<ColumnOccurrence> result = new ArrayList<>();

        Pattern colPattern = Pattern.compile(
                "\\bt\\d+\\.c\\d+\\b",
                Pattern.CASE_INSENSITIVE
        );

        Pattern funcPattern = Pattern.compile(
                "\\b(" + String.join("|", JSON_NAMES) + ")\\s*\\(",
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

            occ.insideFunc = isInsideFunction(sql, occ.start, funcPattern);

            if (!occ.insideFunc) {
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