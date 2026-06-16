package sqlancer.cockroachdb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CockroachDB JSONB Function Injector
 *
 * Features:
 * - Column-aware JSONB injection
 * - Avoid trivial JSON (NULL / '{}')
 * - Covers extract / set / insert / build
 * - Compatible with FRT pipeline
 */
public class CockroachJSONBFunctionInjector {

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
    }

    /* ================= JSONB Functions ================= */

    private static final List<JsonFuncDef> JSON_FUNCS = Arrays.asList(

            new JsonFuncDef("JSONB_SET"),
            new JsonFuncDef("JSONB_INSERT"),
            new JsonFuncDef("JSONB_EXTRACT_PATH"),
            new JsonFuncDef("JSONB_EXTRACT_PATH_TEXT"),
            new JsonFuncDef("JSONB_BUILD_OBJECT"),
            new JsonFuncDef("JSONB_BUILD_ARRAY"),
            new JsonFuncDef("JSONB_STRIP_NULLS"),
            new JsonFuncDef("JSONB_TYPEOF")
    );

    /* ================= Public Entry ================= */

    public static String inject(String sql) {
        return inject(sql, 1);
    }

    public static String inject(String sql, int maxInjectCount) {

        List<ColumnOccurrence> columns = findColumns(sql);

        if (columns.isEmpty()) {
            return injectIntoSelect(sql);
        }

        Collections.shuffle(columns);

        int injectCount = Math.max(1,
                Math.min(maxInjectCount, columns.size()));

        List<ColumnOccurrence> chosen =
                columns.subList(0, injectCount);

        chosen.sort((a, b) -> Integer.compare(b.start, a.start));

        for (ColumnOccurrence c : chosen) {

            JsonFuncDef func =
                    JSON_FUNCS.get(RANDOM.nextInt(JSON_FUNCS.size()));

            String replacement = buildJsonCall(func, c.column);

            sql = sql.substring(0, c.start)
                    + replacement
                    + sql.substring(c.end);
        }

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

        JsonFuncDef func =
                JSON_FUNCS.get(RANDOM.nextInt(JSON_FUNCS.size()));

        String expr = buildJsonCall(func, "1");

        return sql.replaceFirst(
                "(SELECT\\s+(ALL|DISTINCT)?\\s*)",
                m.group(1) + expr + ", "
        );
    }

    /* ================= Build JSON Call ================= */

    private static String buildJsonCall(JsonFuncDef func, String col) {

        String path = randomPath();
        String value = randomJsonValue();

        switch (func.name) {

            case "JSONB_SET":
                return "JSONB_SET(" + wrapJson(col) + ", " + path + ", " + value + ")";

            case "JSONB_INSERT":
                return "JSONB_INSERT(" + wrapJson(col) + ", " + path + ", " + value + ")";

            case "JSONB_EXTRACT_PATH":
                return "JSONB_EXTRACT_PATH(" + wrapJson(col) + ", 'k')";

            case "JSONB_EXTRACT_PATH_TEXT":
                return "JSONB_EXTRACT_PATH_TEXT(" + wrapJson(col) + ", 'k')";

            case "JSONB_BUILD_OBJECT":
                return "JSONB_BUILD_OBJECT('k', " + col + ")";

            case "JSONB_BUILD_ARRAY":
                return "JSONB_BUILD_ARRAY(" + col + ")";

            case "JSONB_STRIP_NULLS":
                return "JSONB_STRIP_NULLS(" + wrapJson(col) + ")";

            case "JSONB_TYPEOF":
                return "JSONB_TYPEOF(" + wrapJson(col) + ")";
        }

        throw new AssertionError(func.name);
    }

    /* ================= Helper: wrap to JSON ================= */

    private static String wrapJson(String col) {
        return "TO_JSONB(" + col + ")";
    }

    /* ================= Helper: random path ================= */

    private static String randomPath() {

        int depth = RANDOM.nextInt(2) + 1;

        StringBuilder sb = new StringBuilder("ARRAY[");

        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append(", ");
            sb.append("'k").append(RANDOM.nextInt(3)).append("'");
        }

        sb.append("]");

        return sb.toString();
    }

    /* ================= Helper: random JSON value ================= */

    private static String randomJsonValue() {

        switch (RANDOM.nextInt(4)) {

            case 0:
                return "TO_JSONB(1)";
            case 1:
                return "TO_JSONB('str')";
            case 2:
                return "TO_JSONB(true)";
            default:
                return "TO_JSONB(NULL)";
        }
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

        return lower.endsWith(".rowid")
                || lower.contains("uuid")
                || lower.contains("hash")
                || lower.contains("random");
    }
}