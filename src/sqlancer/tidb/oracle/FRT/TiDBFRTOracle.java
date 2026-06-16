package sqlancer.tidb.oracle.FRT;

import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBSchema;
import sqlancer.tidb.ast.*;
import sqlancer.tidb.oracle.FRT.Injector.TiDBAggregateFunctionInjector;
import sqlancer.tidb.oracle.FRT.Injector.TiDBControlFlowFunctionInjector;
import sqlancer.tidb.oracle.FRT.Injector.TiDBJsonFunctionInjector;
import sqlancer.tidb.oracle.FRT.Mutator.TiDBAggregateMutators;
import sqlancer.tidb.visitor.TiDBVisitor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TiDBFRTOracle {

    /* ============================================================
     * ======================= Pipeline 主类 ========================
     * ============================================================ */

    public enum OjikenExpandMode {
        ORIGINAL_EXPR,
        RANDOM_SELECT
    }

    private final TiDBProvider.TiDBGlobalState globalState;
    private final FunctionFolder folder;
    private final FunctionMutatorEngine mutatorEngine;

    public TiDBFRTOracle(TiDBProvider.TiDBGlobalState globalState) {
        this(globalState, OjikenExpandMode.ORIGINAL_EXPR);
    }

    public TiDBFRTOracle(TiDBProvider.TiDBGlobalState globalState, OjikenExpandMode expandMode) {

        this.globalState = globalState;

        /* 只保留 TiDB 聚合函数 */

        Set<String> functions = new HashSet<>(Arrays.asList(

                "AVG",
                "COUNT",
//                "MAX",
//                "MIN",
                "SUM",

//                "GROUP_CONCAT",

                "JSON_ARRAYAGG",
                "JSON_OBJECTAGG",

                "STD",
                "STDDEV",
                "STDDEV_POP",
                "STDDEV_SAMP",

                "VAR_POP",
                "VAR_SAMP",
                "VARIANCE",

                "APPROX_PERCENTILE",
                "APPROX_COUNT_DISTINCT",
                "IF",
                "IFNULL",
                "NULLIF"
        ));

        this.folder = new FunctionFolder(functions, globalState, expandMode);

        /* Mutator 注册 */

        Map<String, FunctionMutator> mutators = new HashMap<>();

        TiDBAggregateMutators.register(mutators);

        this.mutatorEngine = new FunctionMutatorEngine(mutators);
    }

    /* ============================================================
     * ======================= Pipeline 主执行 =====================
     * ============================================================ */

    public void runAndCollect(int totalIterations) {

        for (int i = 0; i < totalIterations; i++) {

            /* 1 生成 SQL */

            String originalSimpleSQL =
                    TiDBVisitor.asString(
                            TiDBFRTOracle.generateRandomSelect(globalState)
                    );

            /* 2 注入 Aggregate */

//            String originalSQL =
//                    TiDBAggregateFunctionInjector.inject(
//                            originalSimpleSQL,
//                            1
//                    );

            String originalSQL;

            List<java.util.function.Function<String, String>> injectors =
                    java.util.Arrays.asList(
                            sql -> TiDBAggregateFunctionInjector.inject(sql, 1),
                            sql -> TiDBControlFlowFunctionInjector.inject(sql, 1),
                            sql -> TiDBJsonFunctionInjector.inject(sql, 1),
                            sql -> sql // 不注入（很重要，保留baseline形态）
                    );

//            originalSQL =
//                    Randomly.fromList(injectors).apply(originalSimpleSQL);

            originalSQL = originalSimpleSQL;
            for (java.util.function.Function<String, String> injector : injectors) {
                originalSQL = injector.apply(originalSQL);
            }
            /* 3 Fold */

            String foldedSQL = folder.foldSQL(originalSQL);

            if (folder.getFoldedExprMap().isEmpty()) {
                continue;
            }

            /* 4 Baseline */

            String baselineSQL = folder.unfoldOjiken(foldedSQL);

            MutatedResult baselineResult = exec(baselineSQL);

            if (baselineResult.ex != null) {
                continue;
            }

            /* 5 生成 Mutations */

            Map<String, String> mutatedFoldedSQLs =
                    mutatorEngine.generateMutatedSQL(
                            foldedSQL,
                            folder,
                            originalSQL
                    );

            if (mutatedFoldedSQLs.isEmpty()) {
                continue;
            }

            /* 6 执行 Mutations */

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

        private final TiDBProvider.TiDBGlobalState globalState;

        private final OjikenExpandMode expandMode;

        private final LinkedHashMap<String, String> foldedExprMap =
                new LinkedHashMap<>();

        private int ojikenCounter = 0;

        FunctionFolder(
                Set<String> functions,
                TiDBProvider.TiDBGlobalState globalState,
                OjikenExpandMode expandMode) {

            this.functions = functions;
            this.globalState = globalState;
            this.expandMode = expandMode;
        }

        Map<String, String> getFoldedExprMap() {
            return foldedExprMap;
        }

        private String generateRandomScalarSelect() {

            try {

                String randomExpr =
                        TiDBVisitor.asString(
                                TiDBFRTOracle.generateRandomSelect(globalState)
                        );

                return "(" + randomExpr + ")";

            } catch (Throwable ignored) {

                return "(1)";
            }
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

                if (!functions.contains(func)) {

                    pos = matcher.end();
                    continue;
                }

                int open = matcher.end() - 1;

                int close = findMatchingParen(sb, open);

                if (close < 0) break;

                String args = sb.substring(open + 1, close);

                String ojiken =
                        func + "_Ojiken" + (char) ('A' + ojikenCounter++);

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
    }

    private MutatedResult exec(String sql) {

        MutatedResult r = new MutatedResult();

        try (sqlancer.common.query.SQLancerResultSet rs =
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

                if (v1 == null && v2 == null) {
                    continue;
                }

                if (v1 == null || v2 == null) {
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
        report.append("TiDB Function Rewrite Inconsistency\n");
        report.append("========================================\n");

        report.append("\nOriginal SQL:\n").append(originalSQL).append("\n");

        report.append("\nBaseline SQL:\n").append(baselineSQL).append("\n");

        report.append("\nBaseline Result:\n");
        report.append(dumpMutated(baselineResult)).append("\n");

        report.append("\nFolded SQL:\n").append(foldedSQL).append("\n");

        report.append("\nMutation Stats:\n");
        report.append("Total Mutations: ").append(totalMutations).append("\n");
        report.append("Failed Mutations: ").append(failedTags.size()).append("\n");

        report.append("\nFailing Mutations:\n");

        for (int i = 0; i < failedSQLs.size(); i++) {

            report.append("\n[").append(failedTags.get(i)).append("]\n");

            report.append("SQL:\n");
            report.append(failedSQLs.get(i)).append("\n");

            report.append("Result:\n");
            report.append(dumpMutated(failedResults.get(i))).append("\n");
        }

        report.append("\n========================================\n");

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

    public static TiDBSelect generateRandomSelect(TiDBProvider.TiDBGlobalState state) {

        TiDBSchema.TiDBTables tables =
                state.getSchema().getRandomTableNonEmptyTables();

        TiDBExpressionGenerator gen =
                new TiDBExpressionGenerator(state)
                        .setColumns(tables.getColumns());

        TiDBSelect select = new TiDBSelect();

    /* =========================
       SELECT columns
       ========================= */

        List<TiDBExpression> fetchColumns = new ArrayList<>();

        fetchColumns.addAll(
                Randomly.nonEmptySubset(tables.getColumns())
                        .stream()
                        .map(c -> new TiDBColumnReference(c))
                        .collect(Collectors.toList())
        );

        select.setFetchColumns(fetchColumns);

    /* =========================
       FROM tables
       ========================= */

        List<TiDBExpression> tableList =
                tables.getTables()
                        .stream()
                        .map(t -> new TiDBTableReference(t))
                        .collect(Collectors.toList());

        select.setFromList(tableList);

    /* =========================
       JOIN
       ========================= */

        List<TiDBExpression> joins =
                TiDBJoin.getJoins(tableList, state)
                        .stream()
                        .collect(Collectors.toList());

        select.setJoinList(joins);

    /* =========================
       WHERE
       ========================= */

        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression());
        }

    /* =========================
       ORDER BY
       ========================= */

        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setOrderByClauses(gen.generateOrderBys());
        }

    /* =========================
       LIMIT
       ========================= */

        if (Randomly.getBoolean()) {
            select.setLimitClause(gen.generateExpression());
        }

    /* =========================
       OFFSET
       ========================= */

        if (Randomly.getBoolean()) {
            select.setOffsetClause(gen.generateExpression());
        }

        return select;
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
