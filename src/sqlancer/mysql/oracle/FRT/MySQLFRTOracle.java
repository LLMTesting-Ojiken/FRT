package sqlancer.mysql.oracle.FRT;

import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.gen.MySQLRandomQuerySynthesizer;
import sqlancer.mysql.oracle.FRT.Injector.MySQLAggregateFunctionInjector;
import sqlancer.mysql.oracle.FRT.Mutator.MySQLAggregateMutators;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySQLFRTOracle {

    /* ============================================================
     * ======================= Pipeline 主类 ========================
     * ============================================================ */

    public enum OjikenExpandMode {
        ORIGINAL_EXPR,
        RANDOM_SELECT
    }

    private final MySQLGlobalState globalState;
    private final FunctionFolder folder;
    private final FunctionMutatorEngine mutatorEngine;

    public MySQLFRTOracle(MySQLGlobalState globalState) {
        this(globalState, OjikenExpandMode.ORIGINAL_EXPR);
    }

    public MySQLFRTOracle(MySQLGlobalState globalState, OjikenExpandMode expandMode) {

        this.globalState = globalState;

        /* 只保留 MySQL 聚合函数 */

        Set<String> functions = new HashSet<>(Arrays.asList(

//                "AVG",
                "COUNT",
                "MAX",
//                "MIN",
                "SUM",

                "BIT_AND",
                "BIT_OR",
                "BIT_XOR",

                "GROUP_CONCAT",

//                "JSON_ARRAYAGG",
//                "JSON_OBJECTAGG",

                "STD",
                "STDDEV",
                "STDDEV_POP",
                "STDDEV_SAMP",

                "VAR_POP",
                "VAR_SAMP",
                "VARIANCE"
        ));

        this.folder = new FunctionFolder(functions, globalState, expandMode);

        /* Mutator 注册 */

        Map<String, FunctionMutator> mutators = new HashMap<>();

        MySQLAggregateMutators.register(mutators);

        this.mutatorEngine = new FunctionMutatorEngine(mutators);
    }

    /* ============================================================
     * ======================= Pipeline 主执行 =====================
     * ============================================================ */

    public void runAndCollect(int totalIterations) {

        for (int i = 0; i < totalIterations; i++) {

            /* 1 生成 SQL */

            String originalSimpleSQL =
                    MySQLVisitor.asString(
                            MySQLRandomQuerySynthesizer.generate(
                                    globalState,
                                    Randomly.smallNumber() + 1
                            )
                    ) + ";";
            //跳过bitcount
            if (containsUnsafeFunction(originalSimpleSQL)) {
                continue;
            }

            /* 2 注入 Aggregate */

            String originalSQL =
                    MySQLAggregateFunctionInjector.inject(
                            originalSimpleSQL,
                            1
                    );

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

        private final MySQLGlobalState globalState;

        private final OjikenExpandMode expandMode;

        private final LinkedHashMap<String, String> foldedExprMap =
                new LinkedHashMap<>();

        private int ojikenCounter = 0;

        FunctionFolder(
                Set<String> functions,
                MySQLGlobalState globalState,
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
                        MySQLVisitor.asString(
                                MySQLRandomQuerySynthesizer.generate(
                                        globalState,
                                        Randomly.smallNumber() + 1
                                )
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

                    /* 原始表达式展开 */

                    replacement = "(" + originalExpr + ")";

                } else {

                    /* RANDOM SELECT 展开 */

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

    private boolean containsUnsafeFunction(String sql) {

        String upper = sql.toUpperCase();

        return upper.contains("BIT_COUNT")
                || upper.contains("LEAST")
                || upper.contains("GREATEST");
    }

    private boolean isDifferent(MutatedResult r1, MutatedResult r2) {

        /* ---------------- exception handling ---------------- */

        if (r1.ex != null || r2.ex != null) {

            if (r1.ex == null || r2.ex == null) {
                return true;
            }

            return !r1.ex.getClass().equals(r2.ex.getClass());
        }

        /* ---------------- size check ---------------- */

        if (r1.columns.size() != r2.columns.size()) {
            return true;
        }

        /* ---------------- normalize rows ---------------- */

        List<List<String>> rows1 = normalizeRows(r1.columns);
        List<List<String>> rows2 = normalizeRows(r2.columns);

        /* ---------------- sort rows ---------------- */

        Comparator<List<String>> rowComparator =
                Comparator.comparing(a -> String.join("|", a));

        Collections.sort(rows1, rowComparator);
        Collections.sort(rows2, rowComparator);

        /* ---------------- compare ---------------- */

        for (int i = 0; i < rows1.size(); i++) {

            List<String> row1 = rows1.get(i);
            List<String> row2 = rows2.get(i);

            if (row1.size() != row2.size()) {
                return true;
            }

            for (int j = 0; j < row1.size(); j++) {

                if (!valueEquals(row1.get(j), row2.get(j))) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<List<String>> normalizeRows(List<List<String>> rows) {

        List<List<String>> result = new ArrayList<>();

        for (List<String> row : rows) {

            List<String> newRow = new ArrayList<>();

            for (String v : row) {
                newRow.add(normalizeValue(v));
            }

            result.add(newRow);
        }

        return result;
    }

    private String normalizeValue(String v) {

        if (v == null) {
            return "NULL";
        }

        String s = v.trim();

        /* NULL 统一 */
        if (s.equalsIgnoreCase("null")) {
            return "NULL";
        }

        /* 去掉 JSON 数字字符串的引号 */
        if (s.matches("\"-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?\"")) {
            s = s.substring(1, s.length() - 1);
        }

        /* 去掉前导 0（包括小数） */
        if (s.matches("-?0+\\d+(\\.\\d+)?")) {
            s = s.replaceFirst("^(-?)0+(\\d)", "$1$2");
        }

        /* 特殊处理：0000.123 → 0.123 */
        if (s.matches("-?0+\\.\\d+")) {
            s = s.replaceFirst("^(-?)0+\\.", "$10.");
        }

        /* 科学计数法 / 浮点统一 */
        try {

            java.math.BigDecimal bd = new java.math.BigDecimal(s);

            bd = bd.stripTrailingZeros();

            return bd.toPlainString();

        } catch (Exception ignored) {
        }
        /* 简单 JSON normalize（可选） */
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.replaceAll("\"(-?\\d+(\\.\\d+)?)\"", "$1");
        }
        return s;
    }

    private boolean valueEquals(String v1, String v2) {

        if (Objects.equals(v1, v2)) {
            return true;
        }

        if ("NULL".equals(v1) && "NULL".equals(v2)) {
            return true;
        }

        try {

            java.math.BigDecimal b1 = new java.math.BigDecimal(v1);
            java.math.BigDecimal b2 = new java.math.BigDecimal(v2);

            /* 完全相等 */
            if (b1.compareTo(b2) == 0) {
                return true;
            }

            /* 允许浮点误差 */
            java.math.BigDecimal diff = b1.subtract(b2).abs();
            java.math.BigDecimal max = b1.abs().max(b2.abs());

            /* absolute tolerance */
            if (diff.compareTo(new java.math.BigDecimal("1e-6")) <= 0) {
                return true;
            }

            /* relative tolerance */
            if (max.compareTo(java.math.BigDecimal.ZERO) > 0) {

                java.math.BigDecimal rel =
                        diff.divide(max, java.math.MathContext.DECIMAL64);

                if (rel.compareTo(new java.math.BigDecimal("1e-6")) <= 0) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {

            /* fallback string compare */
            return Objects.equals(v1, v2);
        }
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
        report.append("MySQL Function Rewrite Inconsistency\n");
        report.append("========================================\n");

        /* ---------------- Original SQL ---------------- */

        report.append("\nOriginal SQL:\n");
        report.append(originalSQL).append("\n");

        /* ---------------- Baseline SQL ---------------- */

        report.append("\nBaseline SQL:\n");
        report.append(baselineSQL).append("\n");

        /* ---------------- Baseline Result ---------------- */

        report.append("\nBaseline Result:\n");
        report.append(dumpMutated(baselineResult)).append("\n");

        /* ---------------- Folded SQL ---------------- */

        report.append("\nFolded SQL:\n");
        report.append(foldedSQL).append("\n");

        /* ---------------- Mutation Stats ---------------- */

        report.append("\nMutation Stats:\n");
        report.append("Total Mutations: ").append(totalMutations).append("\n");
        report.append("Failed Mutations: ").append(failedTags.size()).append("\n");

        /* ---------------- Failed Mutations ---------------- */

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
}