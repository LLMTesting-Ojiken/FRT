package sqlancer.cockroachdb.oracle.FRT;

import sqlancer.cockroachdb.gen.CockroachDBFRTColumnFirstQuerySynthesizer;
import sqlancer.cockroachdb.gen.CockroachDBRandomQuerySynthesizer;


import sqlancer.cockroachdb.oracle.FRT.Injector.CockroachAggregateFunctionInjector;
import sqlancer.cockroachdb.oracle.FRT.Injector.CockroachFloatFunctionInjector;
import sqlancer.cockroachdb.oracle.FRT.Mutator.CockroachDBFloatFunctionMutators;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;

import sqlancer.cockroachdb.CockroachDBProvider;
import sqlancer.cockroachdb.CockroachDBVisitor;

import sqlancer.cockroachdb.oracle.FRT.Mutator.CockroachDBAggregateMutators;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class CockroachDBFRTOracle {

    /* ============================================================
     * ======================= Pipeline 主类 ========================
     * ============================================================ */

    public enum OjikenExpandMode {
        ORIGINAL_EXPR,
        RANDOM_SELECT
    }

    private final CockroachDBProvider.CockroachDBGlobalState globalState;
    private final FunctionFolder folder;
    private final FunctionMutatorEngine mutatorEngine;

    public CockroachDBFRTOracle(CockroachDBProvider.CockroachDBGlobalState globalState) {
        this(globalState, OjikenExpandMode.ORIGINAL_EXPR);
    }

    public CockroachDBFRTOracle(
            CockroachDBProvider.CockroachDBGlobalState globalState,
            OjikenExpandMode expandMode) {

        this.globalState = globalState;

        Set<String> functions = new HashSet<>(Arrays.asList(

                /* ================= AGG ================= */
                "ARRAY_AGG", "ARRAY_CAT_AGG",
                "AVG", "SUM", "SUM_INT",
                "COUNT", "COUNT_ROWS",
                "MIN", "MAX",
                "BOOL_AND", "BOOL_OR", "EVERY",
                "BIT_AND", "BIT_OR", "XOR_AGG",
                "STRING_AGG", "CONCAT_AGG",
                "JSON_AGG", "JSON_OBJECT_AGG",
                "JSONB_AGG", "JSONB_OBJECT_AGG",
                "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
                "VAR_SAMP", "VARIANCE",
                "CORR", "COVAR_POP", "COVAR_SAMP",
//                "SQRDIFF",
                "PERCENTILE_CONT", "PERCENTILE_DISC",

                /* ================= FLOAT (🔥新增) ================= */

                "ABS",
                "ACOS", "ACOSD",
                "ACOSH",
                "ASIN", "ASIND",
                "ASINH",
                "ATAN", "ATAND", "ATAN2", "ATAN2D",
                "ATANH",
                "CBRT",
                "CEIL", "CEILING",
                "COS", "COSD", "COSH",
                "COT", "COTD",
                "DEGREES",
                "DIV",
                "EXP",
                "FLOOR",
                "ISNAN",
                "LN", "LOG",
                "MOD",
                "POW", "POWER",
                "RADIANS",
                "ROUND",
                "SIGN",
                "SIN", "SIND", "SINH",
                "SQRT",
                "TAN", "TAND", "TANH",
                "TRUNC",

                "SETSEED"

        ));

        this.folder = new FunctionFolder(functions, globalState, expandMode);

        Map<String, FunctionMutator> mutators = new HashMap<>();

        CockroachDBAggregateMutators.register(mutators);
        CockroachDBFloatFunctionMutators.register(mutators);


        this.mutatorEngine = new FunctionMutatorEngine(mutators);
    }

    /* ============================================================
     * ======================= Pipeline 主执行 =====================
     * ============================================================ */


    public void runAndCollect(int totalIterations) {

        for (int i = 0; i < totalIterations; i++) {

            String originalSimpleSQL =
                    CockroachDBVisitor.asString(
                            CockroachDBFRTColumnFirstQuerySynthesizer.generateSelect(globalState, 2)
                    );

            int mode = new java.util.Random().nextInt(2);
            String originalSQL;

            switch (mode) {

                case 0:
                    originalSQL = CockroachAggregateFunctionInjector.inject(originalSimpleSQL);
                    break;

                case 1:
                    originalSQL = CockroachFloatFunctionInjector.inject(originalSimpleSQL);
                    break;

                default:
                    throw new AssertionError(mode);
            }

            String foldedSQL = folder.foldSQL(originalSQL);

            if (folder.getFoldedExprMap().isEmpty()) {
                continue;
            }

            String baselineSQL = folder.unfoldOjiken(foldedSQL);

            MutatedResult baselineResult = exec(baselineSQL);

            if (baselineResult.ex != null) {
                continue;
            }

            Map<String, String> mutatedFoldedSQLs =
                    mutatorEngine.generateMutatedSQL(
                            foldedSQL,
                            folder,
                            originalSQL);

            if (mutatedFoldedSQLs.isEmpty()) {
                continue;
            }

            List<String> failedTags = new ArrayList<>();
            List<String> failedSQLs = new ArrayList<>();
            List<MutatedResult> failedResults = new ArrayList<>();

            for (Map.Entry<String, String> e : mutatedFoldedSQLs.entrySet()) {

                String tag = e.getKey();
                String mutatedFolded = e.getValue();

                String finalSQL = folder.unfoldOjiken(mutatedFolded);

                MutatedResult mutatedResult = exec(finalSQL);

                if (mutatedResult.ex != null) {
                    continue;
                }

                if (isDifferent(baselineResult, mutatedResult)) {


                    failedTags.add(tag);
                    failedSQLs.add(finalSQL);
                    failedResults.add(mutatedResult);
                }
            }

            if (failedTags.isEmpty()) {
                continue;
            }

            reportIndustrialBug(
                    originalSQL,
                    baselineSQL,
                    baselineResult,
                    foldedSQL,
                    mutatedFoldedSQLs.size(),
                    failedTags,
                    failedSQLs,
                    failedResults
            );


        }
    }


    /* ============================================================
     * ===================== Function Mutator =====================
     * ============================================================ */

    public interface FunctionMutator {

        boolean shouldMutate(String sqlAfterFunction);

        String mutate(String arg);
    }

    static class FunctionMutatorEngine {

        private final Map<String, FunctionMutator> mutators;

        FunctionMutatorEngine(Map<String, FunctionMutator> mutators) {
            this.mutators = mutators;
        }

        Map<String, String> generateMutatedSQL(
                String foldedSQL,
                FunctionFolder folder,
                String originalSQL) {

            Map<String, String> result = new LinkedHashMap<>();

            for (Map.Entry<String, String> e : folder.getFoldedExprMap().entrySet()) {

                String ojiken = e.getKey();

                for (String func : mutators.keySet()) {

                    if (!ojiken.startsWith(func)) {
                        continue;
                    }

                    FunctionMutator mutator = mutators.get(func);

                    if (!mutator.shouldMutate(originalSQL)) {
                        continue;
                    }

                    String mutatedSQL =
                            foldedSQL.replace(
                                    func + "(" + ojiken + ")",
                                    mutator.mutate(ojiken)
                            );

                    result.put(func + "_EQ_" + ojiken, mutatedSQL);
                }
            }

            return result;
        }
    }


    /* ============================================================
     * ===================== Function Folder =====================
     * ============================================================ */

    private static class FunctionFolder {

        private final Set<String> functions;
        private final CockroachDBProvider.CockroachDBGlobalState globalState;
        private final OjikenExpandMode expandMode;

        private final LinkedHashMap<String, String> foldedExprMap =
                new LinkedHashMap<>();

        private int ojikenCounter = 0;

        FunctionFolder(
                Set<String> functions,
                CockroachDBProvider.CockroachDBGlobalState globalState,
                OjikenExpandMode expandMode) {

            this.functions = functions;
            this.globalState = globalState;
            this.expandMode = expandMode;
        }

        private boolean isInsideString(String s, int pos) {

            boolean inSingle = false;

            for (int i = 0; i < pos; i++) {

                char c = s.charAt(i);

                if (c == '\'') {

                    // 处理 '' 转义
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++; // skip escaped quote
                        continue;
                    }

                    inSingle = !inSingle;
                }
            }

            return inSingle;
        }

        Map<String, String> getFoldedExprMap() {
            return foldedExprMap;
        }

        String foldSQL(String sql) {

            foldedExprMap.clear();

            StringBuilder sb = new StringBuilder(sql);

            int pos = 0;

            while (pos < sb.length()) {

                Matcher matcher =
                        Pattern.compile("\\b([A-Z_]+)\\s*\\(",
                                        Pattern.CASE_INSENSITIVE)
                                .matcher(sb);

                if (!matcher.find(pos)) break;

                String func = matcher.group(1).toUpperCase();

                // 🚨 新增：跳过字符串内部
                if (isInsideString(sb.toString(), matcher.start())) {
                    pos = matcher.end();
                    continue;
                }

                if (!functions.contains(func)) {

                    pos = matcher.end();
                    continue;
                }

                int open = matcher.end() - 1;

                int close = findMatchingParen(sb, open);

                if (close < 0) break;

                String args = sb.substring(open + 1, close);

                String ojiken = func + "_Ojiken" + (ojikenCounter++);

                foldedExprMap.put(ojiken, args);

                sb.replace(open + 1, close, ojiken);

                pos = open + 1 + ojiken.length();
            }

            return sb.toString();
        }

        String unfoldOjiken(String sql) {

            String res = sql;

            for (Map.Entry<String, String> e : foldedExprMap.entrySet()) {

                String ojiken = e.getKey();
                String originalExpr = e.getValue();

                String replacement;

                if (expandMode == OjikenExpandMode.ORIGINAL_EXPR) {

                    replacement = "(" + originalExpr + ")";

                } else {

                    replacement = generateRandomScalarSelect();
                }

                res = res.replace(ojiken, replacement);
            }

            return res;
        }

        private String generateRandomScalarSelect() {

            try {

                String randomExpr =
                        CockroachDBVisitor.asString(
                                CockroachDBRandomQuerySynthesizer.generateSelect(globalState, 3)
                        );

                return "(" + randomExpr + ")";

            } catch (Throwable ignored) {

                return "(1)";
            }
        }

        private int findMatchingParen(CharSequence s, int open) {

            int depth = 0;

            for (int i = open; i < s.length(); i++) {

                char c = s.charAt(i);

                if (c == '(') depth++;

                else if (c == ')') {

                    depth--;

                    if (depth == 0) return i;
                }
            }

            return -1;
        }
    }

    /* ============================================================
     * ===================== Execution =====================
     * ============================================================ */

    private static class MutatedResult {

        Throwable ex;
        List<List<String>> columns = new ArrayList<>();

        @Override
        public String toString() {

            StringBuilder sb = new StringBuilder();

            if (ex != null) {
                sb.append("EXCEPTION: ")
                        .append(ex.getClass().getSimpleName());

                if (ex.getMessage() != null) {
                    sb.append(" - ").append(ex.getMessage());
                }

                return sb.toString();
            }

            if (columns == null || columns.isEmpty()) {
                return "<empty result>";
            }

            sb.append("Rows: ").append(columns.size()).append("\n");

            for (int i = 0; i < columns.size(); i++) {

                List<String> row = columns.get(i);

                sb.append("[")
                        .append(i)
                        .append("] ");

                for (int j = 0; j < row.size(); j++) {

                    String val = row.get(j);

                    if (val == null) {
                        sb.append("NULL");
                    } else {
                        sb.append(val);
                    }

                    if (j < row.size() - 1) {
                        sb.append(" | ");
                    }
                }

                sb.append("\n");
            }

            return sb.toString();
        }
    }

    private MutatedResult exec(String sql) {

        MutatedResult r = new MutatedResult();

        try (SQLancerResultSet rs =
                     globalState.executeStatementAndGet(
                             new SQLQueryAdapter(sql, false))) {

            if (rs != null) {

                int colCount = rs.getColumnCount();

                while (rs.next()) {

                    List<String> row = new ArrayList<>();

                    for (int i = 1; i <= colCount; i++) {

                        row.add(rs.getString(i));
                    }

                    r.columns.add(row);
                }
            }

        } catch (Throwable e) {

            r.ex = e;
        }

        return r;
    }

    /* ============================================================
     * ===================== Result Compare =====================
     * ============================================================ */
    private boolean isDifferent(MutatedResult r1, MutatedResult r2) {

        if (r1.ex != null || r2.ex != null) {

            if (r1.ex == null || r2.ex == null) {
                return true;
            }

            return !r1.ex.getClass().equals(r2.ex.getClass());
        }

        if (r1.columns.size() != r2.columns.size()) {
            return true;
        }

        List<List<String>> rows1 = new ArrayList<>();
        List<List<String>> rows2 = new ArrayList<>();

        for (List<String> r : r1.columns) {
            rows1.add(new ArrayList<>(r));
        }

        for (List<String> r : r2.columns) {
            rows2.add(new ArrayList<>(r));
        }

        Comparator<List<String>> rowComparator = (a, b) -> {

            String sa = normalizeRow(a);
            String sb = normalizeRow(b);

            return sa.compareTo(sb);
        };

        Collections.sort(rows1, rowComparator);
        Collections.sort(rows2, rowComparator);

        final double EPS = 1e-6;

        for (int i = 0; i < rows1.size(); i++) {

            List<String> row1 = rows1.get(i);
            List<String> row2 = rows2.get(i);

            if (row1.size() != row2.size()) {
                return true;
            }

            for (int j = 0; j < row1.size(); j++) {

                String v1 = row1.get(j);
                String v2 = row2.get(j);

                /* ================= ✅ JSON ARRAY SPECIAL HANDLING ================= */

                if (looksLikeJsonArray(v1) && looksLikeJsonArray(v2)) {

                    List<String> arr1 = normalizeJsonArray(v1);
                    List<String> arr2 = normalizeJsonArray(v2);

                    /* ✅ 关键：排序（解决顺序不稳定问题） */
                    Collections.sort(arr1);
                    Collections.sort(arr2);

                    if (arr1.size() != arr2.size()) {
                        return true;
                    }

                    for (int k = 0; k < arr1.size(); k++) {

                        String e1 = arr1.get(k);
                        String e2 = arr2.get(k);

                        if (e1 == null && e2 == null) {
                            continue;
                        }

                        if (e1 == null || e2 == null) {
                            return true;
                        }

                        if (!e1.equals(e2)) {
                            return true;
                        }
                    }

                    continue;
                }

                /* ================= 原逻辑 ================= */

                /* ================= NULL / NaN 统一处理 ================= */

                boolean isNull1 = (v1 == null);
                boolean isNull2 = (v2 == null);

                boolean isNaN1 = "NaN".equalsIgnoreCase(v1);
                boolean isNaN2 = "NaN".equalsIgnoreCase(v2);

                /* NULL == NULL */
                if (isNull1 && isNull2) {
                    continue;
                }

                /* NaN == NaN */
                if (isNaN1 && isNaN2) {
                    continue;
                }

                /* 🔥 NULL ≈ NaN（你想要的行为） */
                if ((isNull1 && isNaN2) || (isNull2 && isNaN1)) {
                    continue;
                }

                /* 其他 NULL 不等 */
                if (isNull1 || isNull2) {
                    return true;
                }

                try {

                    double d1 = Double.parseDouble(v1);
                    double d2 = Double.parseDouble(v2);

                    /* NaN */
                    if (Double.isNaN(d1) && Double.isNaN(d2)) {
                        continue;
                    }

                    /* Infinity */
                    if (Double.isInfinite(d1) || Double.isInfinite(d2)) {
                        if (d1 != d2) {
                            return true;
                        }
                        continue;
                    }

                    /* tolerance */
                    double diff = Math.abs(d1 - d2);

                    if (diff == 0) {
                        continue;
                    }

                    double norm = Math.max(
                            1.0,
                            Math.max(Math.abs(d1), Math.abs(d2))
                    );

                    if (diff > EPS * norm) {
                        return true;
                    }

                } catch (NumberFormatException e) {

                    if (!v1.equals(v2)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }


    /* ============================================================
     * ===================== BUG REPORT =====================
     * ============================================================ */

    private void reportIndustrialBug(
            String originalSQL,
            String baselineSQL,
            MutatedResult baselineResult,
            String foldedSQL,
            int totalMutations,
            List<String> failedTags,
            List<String> failedSQLs,
            List<MutatedResult> failedResults
    ) {

        StringBuilder report = new StringBuilder();

        report.append("\n========================================\n");
        report.append("CockroachDB Function Rewrite Inconsistency\n");
        report.append("========================================\n");

        report.append("\nOriginal SQL:\n").append(originalSQL).append("\n");

        report.append("\nBaseline SQL:\n").append(baselineSQL).append("\n");

        report.append("\nBaseline Result:\n");
        report.append(dumpMutated(baselineResult)).append("\n");

        report.append("\nFolded SQL:\n").append(foldedSQL).append("\n");

        report.append("\nMutation Stats:\n");
        report.append("Total Mutations: ").append(totalMutations).append("\n");
        report.append("Failed Mutations: ").append(failedTags.size()).append("\n");

        for (int i = 0; i < failedSQLs.size(); i++) {

            report.append("\n[").append(failedTags.get(i)).append("]\n");
            report.append(failedSQLs.get(i)).append("\n");
            report.append(dumpMutated(failedResults.get(i))).append("\n");
        }

        globalState.getState().setStatements(new ArrayList<>());

        throw new AssertionError(report.toString());
    }

    private String dumpMutated(MutatedResult r) {

        if (r.ex != null) {
            return "EXCEPTION: " + r.ex.getClass().getSimpleName();
        }

        if (r.columns.isEmpty()) {
            return "<empty>";
        }

        StringBuilder sb = new StringBuilder();

        for (List<String> row : r.columns) {
            sb.append(row).append("\n");
        }

        return sb.toString();
    }


    private boolean looksLikeJsonArray(String v) {
        return v != null && v.startsWith("[") && v.endsWith("]");
    }

    private List<String> normalizeJsonArray(String json) {

        String inner = json.substring(1, json.length() - 1).trim();

        if (inner.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = inner.split(",");

        List<String> result = new ArrayList<>();

        for (String p : parts) {
            result.add(p.trim());
        }

        return result;
    }

    private String normalizeRow(List<String> row) {

        List<String> normalized = new ArrayList<>();

        for (String v : row) {

            if (v == null) {
                normalized.add("NULL");
                continue;
            }

            String s = v.trim();

            // ⭐ 关键：如果是 JSON array → 排序内部元素
            if (s.startsWith("[") && s.endsWith("]")) {

                try {

                    String inner = s.substring(1, s.length() - 1).trim();

                    if (inner.isEmpty()) {
                        normalized.add("[]");
                        continue;
                    }

                    String[] parts = inner.split(",");

                    List<String> elems = new ArrayList<>();

                    for (String p : parts) {
                        elems.add(p.trim());
                    }

                    Collections.sort(elems);

                    normalized.add("[" + String.join(",", elems) + "]");

                } catch (Exception e) {
                    normalized.add(s); // fallback
                }

            } else {
                normalized.add(s);
            }
        }

        return String.join("|", normalized);
    }

}


