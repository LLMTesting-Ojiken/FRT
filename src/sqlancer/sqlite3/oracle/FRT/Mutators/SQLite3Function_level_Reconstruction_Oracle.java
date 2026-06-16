package sqlancer.sqlite3.oracle.FRT.Mutators;

import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.oracle.FRT.Injector.*;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SQLite3Function_level_Reconstruction_Oracle {

    /* ============================================================
     * ======================= Pipeline 主类 ========================
     * ============================================================ */

    public enum OjikenExpandMode {
        ORIGINAL_EXPR, RANDOM_SELECT
    }

    private final SQLite3GlobalState globalState;
    private final FunctionFolder folder;
    private final FunctionMutatorEngine mutatorEngine;
    private final EquivalentRewriteEngine rewriteEngine;

    // Oracle 默认构造
    public SQLite3Function_level_Reconstruction_Oracle(SQLite3GlobalState globalState) {
        this(globalState, OjikenExpandMode.RANDOM_SELECT);
    }

    // Oracle 可指定 Fold/Random 模式
    public SQLite3Function_level_Reconstruction_Oracle(SQLite3GlobalState globalState, OjikenExpandMode expandMode) {
        this.globalState = globalState;

        // 1️⃣ 可变异函数集合（包含聚合、日期、数学、Core 函数）
        Set<String> functions = new HashSet<>(Arrays.asList(
                // 聚合函数
                "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG", "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
                // 日期函数
                "DATE", "TIME", "DATETIME", "JULIANDAY", "UNIXEPOCH", "STRFTIME", "TIMEDIFF",
                // 数学函数
                "ACOS", "ASIN", "ATAN", "ATAN2", "ACOSH", "ASINH", "ATANH", "CEIL", "CEILING", "COS", "COSH",
                "DEGREES", "EXP", "FLOOR", "LN", "LOG", "LOG10", "LOG2", "MOD", "PI", "POW", "POWER",
                "RADIANS", "SIN", "SINH", "SQRT", "TAN", "TANH", "TRUNC",
                // Core 函数
                "ABS", "COALESCE", "IFNULL", "CONCAT", "CONCAT_WS", "LOWER", "UPPER", "LTRIM",
                "RTRIM", "TRIM", "ROUND", "SIGN", "REPLACE", "INSTR", "SUBSTR", "SUBSTRING",
                "OCTET_LENGTH", "HEX", "UNHEX", "LIKELIHOOD", "LIKELY", "UNLIKELY", "CHAR",
                "UNICODE", "QUOTE", "NULLIF", "ZEROBLOB", "PRINTF", "FORMAT", "GLOB",
                // ================= JSON / JSONB 函数 =================
                "JSON", "JSONB", "JSON_ARRAY", "JSONB_ARRAY", "JSON_ARRAY_LENGTH", "JSON_ERROR_POSITION", "JSON_EXTRACT",
                "JSONB_EXTRACT", "JSON_INSERT", "JSON_REPLACE", "JSON_SET", "JSONB_INSERT", "JSONB_REPLACE", "JSONB_SET",
                "JSON_OBJECT", "JSONB_OBJECT", "JSON_PATCH", "JSONB_PATCH", "JSON_PRETTY", "JSON_REMOVE", "JSONB_REMOVE",
                "JSON_TYPE", "JSON_VALID", "JSON_QUOTE", "JSON_GROUP_ARRAY", "JSONB_GROUP_ARRAY", "JSON_GROUP_OBJECT",
                "JSONB_GROUP_OBJECT"
        ));
        this.folder = new FunctionFolder(functions, globalState, expandMode);

        // 2️⃣ 函数变异引擎注册
        Map<String, FunctionMutator> mutators = new HashMap<>();
        SQLite3AggregateMutators.register(mutators);
        SQLite3DateTimeMutators.register(mutators);//（注意 UNIXEPOCH 这个函数的问题）
        SQLite3MathMutators.register(mutators);
        SQLite3CoreFunctionMutators.register(mutators);//likely和abs也是存在问题的。
        SQLite3JsonMutators.register(mutators);

        // 3️⃣ 引擎实例化
        this.mutatorEngine = new FunctionMutatorEngine(mutators);
        this.rewriteEngine = new EquivalentRewriteEngine();
    }

    /* ============================================================
     * ======================= Pipeline 主执行 =====================
     * ============================================================ */

    public void runAndCollect(int totalIterations) {

        for (int i = 0; i < totalIterations; i++) {

            /* =========================================
             * 1 生成原始 SQL
             * ========================================= */

            String originalSimpleSQL;

            do {
                originalSimpleSQL = SQLite3Visitor.asString(
                        SQLite3RandomQuerySynthesizer.generate(globalState, Randomly.smallNumber() + 1)
                ) + ";";
            } while (originalSimpleSQL.contains("||")); // 避免列拼接污染结果


            /* =========================================
             * 2 注入函数
             * ========================================= */

            int choice = Randomly.fromOptions(0, 1, 2, 3, 4);

            String originalSQL;

            switch (choice) {
                case 0:
                    originalSQL = RandomAggFunctionInjector.injectSmartAggFunction(originalSimpleSQL, 1);
                    break;
                case 1:
                    originalSQL = RandomMathFunctionInjector.injectSmartMathFunction(originalSimpleSQL, 1);
                    break;
                case 2:
                    originalSQL = RandomDateTimeFunctionInjector.injectSmartDateTimeFunction(originalSimpleSQL, 1);
                    break;
                case 3:
                    originalSQL = RandomCoreFunctionInjector.injectRandomCoreFunction(originalSimpleSQL, 1);
                    break;
                case 4:
                    originalSQL = RandomJsonFunctionInjector.injectSmartJsonFunction(originalSimpleSQL, 1);
                    break;
                default:
                    originalSQL = originalSimpleSQL;
            }


            /* =========================================
             * 3 Fold SQL
             * ========================================= */

            String foldedSQL = folder.foldSQL(originalSQL);

            if (folder.getFoldedExprMap().isEmpty()) {
                continue;
            }


            /* =========================================
             * 4 Rewrite
             * ========================================= */

            foldedSQL = rewriteEngine.rewriteAll(foldedSQL);


            /* =========================================
             * 5 Baseline 执行
             * ========================================= */

            String baselineSQL = folder.unfoldOjiken(foldedSQL);

            if (containsNonDeterministicTime(baselineSQL)) {
                continue;
            }

            MutatedResult baselineResult = exec(baselineSQL);

            if (baselineResult.ex != null) {
                continue;
            }


            /* =========================================
             * 6 生成所有 Mutated SQL
             * ========================================= */

            Map<String, String> mutatedFoldedSQLs =
                    mutatorEngine.generateMutatedSQL(foldedSQL, folder, originalSQL);

            if (mutatedFoldedSQLs.isEmpty()) {
                continue;
            }


            /* =========================================
             * 7 执行 Mutations
             * ========================================= */

            List<String> failedTags = new ArrayList<>();
            List<String> failedSQLs = new ArrayList<>();
            List<MutatedResult> failedResults = new ArrayList<>();

            int testedMutations = 0;

            for (Map.Entry<String, String> e : mutatedFoldedSQLs.entrySet()) {

                String tag = e.getKey();
                String mutatedFolded = e.getValue();

                String finalMutated = folder.unfoldOjiken(mutatedFolded);

                if (containsNonDeterministicTime(finalMutated)) {
                    continue;
                }

                MutatedResult mutatedResult = exec(finalMutated);

                if (mutatedResult.ex != null) {
                    continue;
                }

                testedMutations++;

                if (isDifferent(baselineResult, mutatedResult)) {

                    failedTags.add(tag);
                    failedSQLs.add(finalMutated);
                    failedResults.add(mutatedResult);
                }
            }


            if (testedMutations == 0) {
                continue;
            }


            /* =========================================
             * 8 多重验证策略
             * ========================================= */

            int failCount = failedTags.size();

            // 必须 >=2 个 mutation 失败才认为可能是真 bug
            if (failCount < 1) {
                continue;
            }


            /* =========================================
             * 9 Window Retry (针对 Aggregate)
             * ========================================= */

            if (containsAggregateFunction(baselineSQL)) {

                String windowBaseline = sqlWithWindowFunctions(baselineSQL);
                MutatedResult windowBaselineResult = exec(windowBaseline);

                if (windowBaselineResult.ex != null) {
                    continue;
                }

                int windowFailCount = 0;

                for (int k = 0; k < failedSQLs.size(); k++) {

                    String windowMutated = sqlWithWindowFunctions(failedSQLs.get(k));

                    MutatedResult windowMutatedResult = exec(windowMutated);

                    if (windowMutatedResult.ex != null) {
                        continue;
                    }

                    if (isDifferent(windowBaselineResult, windowMutatedResult)) {
                        windowFailCount++;
                    }
                }

                if (windowFailCount < 2) {
                    continue;
                }
            }
            /* =========================================
             * 10 DISTINCT Retry
             * ========================================= */

            if (containsDistinct(baselineSQL)) {

                String noDistinctBaseline = removeDistinct(baselineSQL);
                MutatedResult noDistinctBaselineResult = exec(noDistinctBaseline);

                if (noDistinctBaselineResult.ex != null) {
                    continue;
                }

                int distinctFailCount = 0;

                for (int k = 0; k < failedSQLs.size(); k++) {

                    String noDistinctMutated = removeDistinct(failedSQLs.get(k));

                    MutatedResult noDistinctMutatedResult = exec(noDistinctMutated);

                    if (noDistinctMutatedResult.ex != null) {
                        continue;
                    }

                    if (isDifferent(noDistinctBaselineResult, noDistinctMutatedResult)) {
                        distinctFailCount++;
                    }
                }

                if (distinctFailCount < 2) {
                    continue;
                }
            }


            /* =========================================
             * 10 REPORT
             * ========================================= */

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

    private boolean containsDistinct(String sql) {
        return sql.toUpperCase(Locale.ROOT).contains("SELECT DISTINCT");
    }

    private String removeDistinct(String sql) {

        Pattern p = Pattern.compile(
                "(?i)SELECT\\s+DISTINCT\\s+",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = p.matcher(sql);

        if (m.find()) {
            return m.replaceFirst("SELECT ");
        }

        return sql;
    }

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
        report.append("SQLite3 Function Rewrite Inconsistency\n");
        report.append("========================================\n");

        report.append("\nOriginal SQL:\n");
        report.append(originalSQL).append("\n");

        report.append("\nBaseline SQL:\n");
        report.append(baselineSQL).append("\n");

        report.append("\nBaseline Result:\n");
//        report.append(baselineResult).append("\n");
        report.append(dumpMutated(baselineResult)).append("\n");

        report.append("\nFolded SQL:\n");
        report.append(foldedSQL).append("\n");

        report.append("\nMutation Stats:\n");
        report.append("Total Mutations: ").append(totalMutations).append("\n");
        report.append("Failed Mutations: ").append(failedTags.size()).append("\n");

        report.append("\nFailing Mutations:\n");

        for (int i = 0; i < failedSQLs.size(); i++) {

            report.append("\n[").append(failedTags.get(i)).append("]\n");
            report.append(failedSQLs.get(i)).append("\n");
            report.append(dumpMutated(failedResults.get(i))).append("\n");

        }

        report.append("\n========================================\n");
        globalState.getState().setStatements(new ArrayList<>());
        throw new AssertionError(report.toString());
    }

    private String dumpMutated(MutatedResult r) {

        if (r == null) {
            return "  <null MutatedResult>\n";
        }

        StringBuilder sb = new StringBuilder();

        // exception
        sb.append("  ex: ");
        if (r.ex == null) {
            sb.append("<null>");
        } else {
            sb.append(r.ex.getClass().getSimpleName())
                    .append(": ")
                    .append(r.ex.getMessage());
        }
        sb.append("\n");

        sb.append("  result:\n");

        if (r.columns == null || r.columns.isEmpty()) {
            sb.append("    <empty>\n");
            return sb.toString();
        }

        for (List<String> row : r.columns) {

            sb.append("    ");

            if (row == null || row.isEmpty()) {
                sb.append("<empty-row>");
            } else {

                for (int i = 0; i < row.size(); i++) {

                    String v = row.get(i);

                    if (v == null) {
                        sb.append("null");
                    } else {
                        sb.append(v);
                    }

                    if (i != row.size() - 1) {
                        sb.append(" | ");
                    }
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private boolean containsNonDeterministicTime(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.contains("'now'")
                || lower.contains("datetime('now'")
                || lower.contains("date('now'")
                || lower.contains("time('now'")
                || lower.contains("strftime('%s','now'");
    }

    /* ============================================================
     * ======================= Window / Aggregate ==================
     * ============================================================ */

    // 注入 OVER() 以尝试窗口函数
    String sqlWithWindowFunctions(String sql) {
        Pattern aggPattern = Pattern.compile(
                "\\b(SUM|AVG|COUNT|MIN|MAX|TOTAL|GROUP_CONCAT|STRING_AGG|MEDIAN)\\s*\\(",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = aggPattern.matcher(sql);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            int start = m.end();
            int end = RandomAggFunctionInjector.findMatchingParen(sql, start - 1);
            if (end >= 0) {
                m.appendReplacement(sb, sql.substring(m.start(), end + 1) + " OVER ()");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // 检查 SQL 中是否存在聚合函数
    private boolean containsAggregateFunction(String sql) {
        Set<String> aggregates = new HashSet<>(Arrays.asList(
                "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
                "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
                "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
        ));
        String upperSQL = sql.toUpperCase();
        for (String agg : aggregates) {
            if (upperSQL.contains(agg + "(")) return true;
        }
        return false;
    }

    /* ============================================================
     * ===================== Equivalent Rewrite =====================
     * ============================================================ */

    interface EquivalentRewriteRule {
        String rewrite(String sql);
    }

    static class EquivalentRewriteEngine {
        private final List<EquivalentRewriteRule> rules = new ArrayList<>();

        void addRule(EquivalentRewriteRule rule) {
            rules.add(rule);
        }

        String rewriteAll(String sql) {
            String res = sql;
            for (EquivalentRewriteRule r : rules) {
                res = r.rewrite(res);
            }
            return res;
        }
    }

    /* ============================================================
     * ===================== Function Folder =====================
     * ============================================================ */

    private static class FunctionFolder {
        private final Set<String> functions;
        private final SQLite3GlobalState globalState;
        private final OjikenExpandMode expandMode;
        private final LinkedHashMap<String, String> foldedExprMap = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> randomExprMap = new LinkedHashMap<>();
        private int ojikenCounter = 0;

        FunctionFolder(Set<String> functions,
                       SQLite3GlobalState globalState,
                       OjikenExpandMode expandMode) {
            this.functions = functions;
            this.globalState = globalState;
            this.expandMode = expandMode;
        }

        Map<String, String> getFoldedExprMap() {
            return foldedExprMap;
        }

        // 折叠 SQL，把聚合函数参数替换为 Ojiken 占位符
        String foldSQL(String sql) {
            foldedExprMap.clear();
            randomExprMap.clear();
            ojikenCounter = 0;

            StringBuilder sb = new StringBuilder(sql);
            int pos = 0;

            while (pos < sb.length()) {
                Matcher matcher = Pattern
                        .compile("\\b([A-Z_]+)\\s*\\(", Pattern.CASE_INSENSITIVE)
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
                String ojiken = func + "_Ojiken" + (char) ('A' + ojikenCounter++);
                foldedExprMap.put(ojiken, args);

                if (expandMode == OjikenExpandMode.RANDOM_SELECT) {
                    SQLite3TypedSubqueryGenerator.ExpectedType type =
                            SQLite3TypedSubqueryGenerator.inferExpectedType(func);

                    randomExprMap.put(
                            ojiken,
                            SQLite3TypedSubqueryGenerator.generate(globalState, type)
                    );
                }

                sb.replace(open + 1, close, ojiken);
                pos = open + 1 + ojiken.length();
            }
            return sb.toString();
        }

        // 展开 Ojiken 占位符
        String unfoldOjiken(String sql) {
            String res = sql;
            Object[] keys = foldedExprMap.keySet().toArray();
            for (int i = keys.length - 1; i >= 0; i--) {
                String k = (String) keys[i];
                String replacement =
                        (expandMode == OjikenExpandMode.RANDOM_SELECT)
                                ? randomExprMap.get(k)
                                : "(" + foldedExprMap.get(k) + ")";
                res = res.replaceAll(
                        "\\b" + Pattern.quote(k) + "\\b",
                        Matcher.quoteReplacement(replacement)
                );
            }
            return res;
        }

//        // 随机生成子查询占位
//        private String generateRandomScalarSelect() {
//            String q = SQLite3Visitor.asString(
//                    SQLite3RandomQuerySynthesizer.generate(globalState, 1)
//            );
//            return "(SELECT * FROM (" + q + ") LIMIT 1)";
//        }

//        private String generateRandomScalarSelect(String func) {
//
//            SQLite3TypedSubqueryGenerator.ExpectedType type =
//                    SQLite3TypedSubqueryGenerator.inferExpectedType(func);
//
//            return SQLite3TypedSubqueryGenerator.generate(
//                    globalState,
//                    type
//            );
//        }


        // 找到匹配括号
        private int findMatchingParen(CharSequence s, int open) {
            int depth = 0;
            boolean inSingle = false, inDouble = false;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble) {
                    inSingle = !inSingle;
                    continue;
                }
                if (c == '"' && !inSingle) {
                    inDouble = !inDouble;
                    continue;
                }
                if (inSingle || inDouble) continue;
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
     * ===================== Function Mutator =====================
     * ============================================================ */

    interface FunctionMutator {
        boolean shouldMutate(String sqlAfterFunction);

        String mutate(String arg);
    }

    static class FunctionMutatorEngine {
        private final Map<String, FunctionMutator> mutators;

        FunctionMutatorEngine(Map<String, FunctionMutator> mutators) {
            this.mutators = mutators;
        }

        // 根据 folded SQL 生成变异 SQL
        Map<String, String> generateMutatedSQL(
                String foldedSQL,
                FunctionFolder folder,
                String originalSQL) {

            Map<String, String> result = new LinkedHashMap<>();
            boolean originalHasOver = originalSQL.toUpperCase().contains("OVER");

            for (Map.Entry<String, String> e : folder.getFoldedExprMap().entrySet()) {
                String ojiken = e.getKey();
                for (String func : mutators.keySet()) {
                    if (!ojiken.startsWith(func)) continue;
                    if ((func.equals("AVG") || func.equals("COUNT")) && originalHasOver) continue;

                    FunctionMutator mutator = mutators.get(func);
                    if (!mutator.shouldMutate(
                            foldedSQL.substring(
                                    foldedSQL.indexOf(ojiken) + ojiken.length()
                            ))) {
                        continue;
                    }

                    String mutatedSQL =
                            foldedSQL.replace(func + "(" + ojiken + ")", mutator.mutate(ojiken));
                    result.put(func + "_EQ_" + ojiken, mutatedSQL);
                }
            }
            return result;
        }
    }

    /* ============================================================
     * ===================== Execution / Utils =====================
     * ============================================================ */

    // ===================== MutatedResult =====================
    private static class MutatedResult {
        Throwable ex;
        List<List<String>> columns = new ArrayList<>(); // 每行拆列存储
    }

    // ===================== 执行 SQL =====================
    private MutatedResult exec(String sql) {
        MutatedResult r = new MutatedResult();

        try (sqlancer.common.query.SQLancerResultSet rs =
                     globalState.executeStatementAndGet(new SQLQueryAdapter(sql, false))) {

            if (rs != null) {
                int colCount = rs.getColumnCount();
                while (rs.next()) {
                    List<String> rowCols = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i); // 只能用 getString
                        rowCols.add(val);
                    }
                    r.columns.add(rowCols);
                }
            }

        } catch (Throwable e) {
            r.ex = unwrap(e);
        }

        return r;
    }

    // ===================== 结果对比 =====================
    private boolean isDifferent(MutatedResult r1, MutatedResult r2) {
        // 1️⃣ 异常比较
        if (r1.ex != null || r2.ex != null) {
            if (r1.ex == null || r2.ex == null) return true;
            return !r1.ex.getClass().equals(r2.ex.getClass());
        }

        // 2️⃣ 行数不同
        if (r1.columns.size() != r2.columns.size()) return true;

        // 3️⃣ 逐行逐列比较
        for (int i = 0; i < r1.columns.size(); i++) {
            List<String> row1 = r1.columns.get(i);
            List<String> row2 = r2.columns.get(i);

            if (row1.size() != row2.size()) return true;

            for (int j = 0; j < row1.size(); j++) {
                String v1 = row1.get(j);
                String v2 = row2.get(j);

                // --- NULL 比较 ---
                if (v1 == null || v2 == null) {
                    if (v1 != v2) return true;
                    continue;
                }

                // --- 数值比较 ---
                Double d1 = parseDoubleSafe(v1);
                Double d2 = parseDoubleSafe(v2);
                if (d1 != null && d2 != null) {
                    if (!numericEquivalent(d1, d2)) return true;
                    continue;
                }

                // --- BLOB 不适用 getString，直接用字符串比较 ---
                if (!Objects.equals(v1, v2)) return true;
            }
        }

        return false;
    }

    // ===================== 数值 ε-等价 =====================
    private boolean numericEquivalent(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return false;
        if (Double.isInfinite(a) || Double.isInfinite(b)) return a == b;

        // 四舍五入到小数点后 6 位
        int scale = 6;
        double ra = roundToScale(a, scale);
        double rb = roundToScale(b, scale);
        if (Double.compare(ra, rb) == 0) return true;

        // 极小差值也认为相等
        double diff = Math.abs(a - b);
        double eps = 1e-7;
        return diff <= eps;
    }

    private double roundToScale(double x, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(x * factor) / factor;
    }

    // ===================== 安全解析数值 =====================
    private Double parseDoubleSafe(Object o) {
        if (o == null) return null;

        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (o instanceof byte[]) {
            try {
                return Double.parseDouble(new String((byte[]) o).trim());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // 解包异常
    private Throwable unwrap(Throwable t) {
        while (t != null && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

}

