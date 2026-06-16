//package sqlancer.sqlite3.oracle.Fuc;
//
//import sqlancer.Randomly;
//import sqlancer.common.query.SQLQueryAdapter;
//import sqlancer.sqlite3.SQLite3GlobalState;
//import sqlancer.sqlite3.SQLite3Visitor;
//import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
//
//import java.util.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//public class SQLite3Function_level_Reconstruction_Oracle {
//
//    /* ============================================================
//     * ======================= Pipeline 主类 ========================
//     * ============================================================ */
//
//    public enum OjikenExpandMode {
//        ORIGINAL_EXPR, RANDOM_SELECT
//    }
//
//    private final SQLite3GlobalState globalState;
//    private final FunctionFolder folder;
//    private final FunctionMutatorEngine mutatorEngine;
//    private final EquivalentRewriteEngine rewriteEngine;
//
//    // Oracle 默认构造
//    public SQLite3Function_level_Reconstruction_Oracle(SQLite3GlobalState globalState) {
//        this(globalState, OjikenExpandMode.ORIGINAL_EXPR);
//    }
//
//    // Oracle 可指定 Fold/Random 模式
//    public SQLite3Function_level_Reconstruction_Oracle(SQLite3GlobalState globalState, OjikenExpandMode expandMode) {
//        this.globalState = globalState;
//
//        // 1️⃣ 可变异函数集合（包含聚合、日期、数学、Core 函数）
//        Set<String> functions = new HashSet<>(Arrays.asList(
//                // 聚合函数
//                "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG", "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
//                // 日期函数
//                "DATE", "TIME", "DATETIME", "JULIANDAY", "UNIXEPOCH", "STRFTIME", "TIMEDIFF",
//                // 数学函数
//                "ACOS", "ASIN", "ATAN", "ATAN2", "ACOSH", "ASINH", "ATANH", "CEIL", "CEILING", "COS", "COSH",
//                "DEGREES", "EXP", "FLOOR", "LN", "LOG", "LOG10", "LOG2", "MOD", "PI", "POW", "POWER",
//                "RADIANS", "SIN", "SINH", "SQRT", "TAN", "TANH", "TRUNC",
//                // Core 函数
//                "ABS", "COALESCE", "IFNULL", "CONCAT", "CONCAT_WS", "LOWER", "UPPER", "LTRIM",
//                "RTRIM", "TRIM", "ROUND", "SIGN", "REPLACE", "INSTR", "SUBSTR", "SUBSTRING",
//                "OCTET_LENGTH", "HEX", "UNHEX", "LIKELIHOOD", "LIKELY", "UNLIKELY", "CHAR",
//                "UNICODE", "QUOTE", "NULLIF", "ZEROBLOB", "PRINTF", "FORMAT", "GLOB",
//                // ================= JSON / JSONB 函数 =================
//                "JSON", "JSONB", "JSON_ARRAY", "JSONB_ARRAY", "JSON_ARRAY_LENGTH", "JSON_ERROR_POSITION", "JSON_EXTRACT",
//                "JSONB_EXTRACT", "JSON_INSERT", "JSON_REPLACE", "JSON_SET", "JSONB_INSERT", "JSONB_REPLACE", "JSONB_SET",
//                "JSON_OBJECT", "JSONB_OBJECT", "JSON_PATCH", "JSONB_PATCH", "JSON_PRETTY", "JSON_REMOVE", "JSONB_REMOVE",
//                "JSON_TYPE", "JSON_VALID", "JSON_QUOTE", "JSON_GROUP_ARRAY", "JSONB_GROUP_ARRAY", "JSON_GROUP_OBJECT",
//                "JSONB_GROUP_OBJECT"
//        ));
//        this.folder = new FunctionFolder(functions, globalState, expandMode);
//
//        // 2️⃣ 函数变异引擎注册
//        Map<String, FunctionMutator> mutators = new HashMap<>();
////        SQLite3AggregateMutators.register(mutators);
////        SQLite3DateTimeMutators.register(mutators);）//（注意 UNIXEPOCH 这个函数的问题）
//        SQLite3MathMutators.register(mutators);
////        SQLite3CoreFunctionMutators.register(mutators);//hex函数被我关闭了，likely和abs也是。
////        SQLite3JsonMutators.register(mutators);
//
//        // 3️⃣ 引擎实例化
//        this.mutatorEngine = new FunctionMutatorEngine(mutators);
//        this.rewriteEngine = new EquivalentRewriteEngine();
//    }
//
//    /* ============================================================
//     * ======================= Pipeline 主执行 =====================
//     * ============================================================ */
//
//    public void runAndCollect(int totalIterations) {
////
//        for (int i = 0; i < totalIterations; i++) {
//            // 生成原始 SQL
//            String original_simple_SQL = SQLite3Visitor.asString(
//                    SQLite3RandomQuerySynthesizer.generate(globalState, Randomly.smallNumber() + 1)
//            ) + ";";
//
//            // ===== 随机选择一种注入策略 =====
//            int choice = Randomly.fromOptions(0, 1, 2, 3, 4);
//
//            String originalSQL;
//
//            switch (choice) {
//                case 0:
//                    // Agg
//                    originalSQL = RandomAggFunctionInjector.injectSmartAggFunction(original_simple_SQL, 1);
//                    break;
//                case 1:
//                    // Math
//                    originalSQL = RandomMathFunctionInjector.injectSmartMathFunction(original_simple_SQL, 1);
//                    break;
//                case 2:
//                    // DateTime
//                    originalSQL = RandomDateTimeFunctionInjector.injectSmartDateTimeFunction(original_simple_SQL, 1);
//                    break;
//                case 3:
//                    // Core
//                    originalSQL = RandomCoreFunctionInjector.injectRandomCoreFunction(original_simple_SQL, 1);
//                    break;
//                case 4:
//                    // Json
//                    originalSQL = RandomJsonFunctionInjector.injectSmartJsonFunction(original_simple_SQL, 1);
//                    break;
//                default:
//                    originalSQL = original_simple_SQL;
//            }
//
//            // 可选观察函数替换
//            detectFunctionReplacement(originalSQL, originalSQL, originalSQL, "DATE", 0);
//
//            // 折叠 SQL
//            String foldedSQL = folder.foldSQL(originalSQL);
//            if (folder.getFoldedExprMap().isEmpty()) continue;
//
//            // 等价重写
//            foldedSQL = rewriteEngine.rewriteAll(foldedSQL);
//
//            // baseline 执行
//            String baselineSQL = folder.unfoldOjiken(foldedSQL);
//            MutatedResult baselineResult = exec(baselineSQL);
//            if (baselineResult.ex != null) continue;
//
//            // 生成 Mutated SQL
//            Map<String, String> mutatedFoldedSQLs =
//                    mutatorEngine.generateMutatedSQL(foldedSQL, folder, originalSQL);
//
//            // 对比结果
//            for (Map.Entry<String, String> e : mutatedFoldedSQLs.entrySet()) {
//                String tag = e.getKey();
//                String mutatedFolded = e.getValue();
//                String finalMutated = folder.unfoldOjiken(mutatedFolded);
//                MutatedResult mutatedResult = exec(finalMutated);
//                if (mutatedResult.ex != null) continue;
//
//                detectFunctionReplacement(baselineSQL, foldedSQL, finalMutated, "DATE", 0);
//
//                if (isDifferent(baselineResult, mutatedResult)) {
//                    // 尝试 Window 化
//                    if (containsAggregateFunction(baselineSQL)) {
//                        baselineSQL = sqlWithWindowFunctions(baselineSQL);
//                        baselineResult = exec(baselineSQL);
//                        finalMutated = sqlWithWindowFunctions(finalMutated);
//                        mutatedResult = exec(finalMutated);
//
//                        if (isDifferent(baselineResult, mutatedResult)) {
//                            System.out.println(baselineResult);
//                            System.out.println(mutatedResult);
//                            reportInconsistency(
//                                    tag, originalSQL, baselineSQL, baselineResult,
//                                    foldedSQL, mutatedFolded, finalMutated, mutatedResult
//                            );
//                        }
//                    } else {
//                        if (containsNonDeterministicTime(originalSQL)
//                                || containsNonDeterministicTime(baselineSQL)
//                                || containsNonDeterministicTime(finalMutated)) {
//                            // skip this case, do not report inconsistency
//                            return;
//                        }
//                        System.out.println(baselineResult);
//                        System.out.println(mutatedResult);
//                        reportInconsistency(
//                                tag, originalSQL, baselineSQL, baselineResult,
//                                foldedSQL, mutatedFolded, finalMutated, mutatedResult
//                        );
//                    }
//                }
//            }
//        }
//    }
//
//    private boolean containsNonDeterministicTime(String sql) {
//        String lower = sql.toLowerCase(Locale.ROOT);
//        return lower.contains("'now'")
//                || lower.contains("datetime('now'")
//                || lower.contains("date('now'")
//                || lower.contains("time('now'")
//                || lower.contains("strftime('%s','now'");
//    }
//
//    /* ============================================================
//     * ======================= Window / Aggregate ==================
//     * ============================================================ */
//
//    // 注入 OVER() 以尝试窗口函数
//    String sqlWithWindowFunctions(String sql) {
//        Pattern aggPattern = Pattern.compile(
//                "\\b(SUM|AVG|COUNT|MIN|MAX|TOTAL|GROUP_CONCAT|STRING_AGG|MEDIAN)\\s*\\(",
//                Pattern.CASE_INSENSITIVE
//        );
//
//        Matcher m = aggPattern.matcher(sql);
//        StringBuffer sb = new StringBuffer();
//
//        while (m.find()) {
//            int start = m.end();
//            int end = RandomAggFunctionInjector.findMatchingParen(sql, start - 1);
//            if (end >= 0) {
//                m.appendReplacement(sb, sql.substring(m.start(), end + 1) + " OVER ()");
//            }
//        }
//        m.appendTail(sb);
//        return sb.toString();
//    }
//
//    // 检查 SQL 中是否存在聚合函数
//    private boolean containsAggregateFunction(String sql) {
//        Set<String> aggregates = new HashSet<>(Arrays.asList(
//                "AVG", "COUNT", "GROUP_CONCAT", "STRING_AGG",
//                "MEDIAN", "MIN", "MAX", "SUM", "TOTAL",
//                "PERCENTILE", "PERCENTILE_DISC", "PERCENTILE_CONT"
//        ));
//        String upperSQL = sql.toUpperCase();
//        for (String agg : aggregates) {
//            if (upperSQL.contains(agg + "(")) return true;
//        }
//        return false;
//    }
//
//    // 输出函数不一致报告
//    private void reportInconsistency(
//            String tag,
//            String originalSQL,
//            String baselineSQL,
//            MutatedResult baselineResult,
//            String foldedSQL,
//            String mutatedFolded,
//            String finalMutated,
//            MutatedResult mutatedResult) {
//
//        StringBuilder msg = new StringBuilder();
//        msg.append("\n===== Function Consistency ORACLE INCONSISTENCY =====\n\n");
//        msg.append("Baseline SQL:\n")
//                .append(trim(baselineSQL))
//                .append("\n")
//                .append(dumpMutated(baselineResult))
//                .append("\n");
//
//        msg.append("Mutated SQL [")
//                .append(tag)
//                .append("]:\n")
//                .append(trim(finalMutated))
//                .append("\n\n")
//                .append(dumpMutated(mutatedResult))
//                .append("\n");
//
//        globalState.getState().setStatements(new ArrayList<>());
//        throw new AssertionError(msg.toString());
//    }
//
//    // 可选的函数替换观察
//    private void detectFunctionReplacement(
//            String originalSQL,
//            String foldedSQL,
//            String mutatedFoldedSQL,
//            String functionName,
//            int tag) {
//
//        if (originalSQL == null || mutatedFoldedSQL == null) {
//            return;
//        }
//
//        String fn = functionName.toUpperCase() + "(";
//
//        String orig = originalSQL.toUpperCase();
//        String mutated = mutatedFoldedSQL.toUpperCase();
//
//        // 1. 原来必须包含这个函数
//        if (!orig.contains(fn)) {
//            return;
//        }
//
//        // 2. 变异后：这个函数调用消失 或 发生结构性变化
//        boolean replaced = !mutated.contains(fn);
//
//        // 3. 如果函数还在，但参数结构变了（可选增强）
//        if (!replaced) {
//            String origArgs = extractFirstArgs(originalSQL, functionName);
//            String mutatedArgs = extractFirstArgs(mutatedFoldedSQL, functionName);
//            if (origArgs != null && mutatedArgs != null
//                    && !origArgs.equals(mutatedArgs)) {
//                replaced = true;
//            }
//        }
//
//        if (!replaced) {
//            return;
//        }
//
//        if (tag == 1) {
//            System.out.println("==== FUNCTION REPLACEMENT OBSERVER ====");
//            System.out.println("Function      : " + functionName);
//            System.out.println("Original SQL  :");
//            System.out.println(trim(originalSQL));
//            System.out.println();
//
//            System.out.println("Before Mutation (Folded SQL):");
//            System.out.println(trim(foldedSQL));
//            System.out.println();
//
//            System.out.println("After Mutation (Mutated Folded SQL):");
//            System.out.println(trim(mutatedFoldedSQL));
//            System.out.println();
//        }
//    }
//
//    private String extractFirstArgs(String sql, String functionName) {
//        Pattern p = Pattern.compile(
//                "\\b" + Pattern.quote(functionName) + "\\s*\\(([^()]*)\\)",
//                Pattern.CASE_INSENSITIVE
//        );
//        Matcher m = p.matcher(sql);
//        if (m.find()) {
//            return m.group(1).trim();
//        }
//        return null;
//    }
//    /* ============================================================
//     * ===================== Equivalent Rewrite =====================
//     * ============================================================ */
//
//    interface EquivalentRewriteRule {
//        String rewrite(String sql);
//    }
//
//    static class EquivalentRewriteEngine {
//        private final List<EquivalentRewriteRule> rules = new ArrayList<>();
//
//        void addRule(EquivalentRewriteRule rule) {
//            rules.add(rule);
//        }
//
//        String rewriteAll(String sql) {
//            String res = sql;
//            for (EquivalentRewriteRule r : rules) {
//                res = r.rewrite(res);
//            }
//            return res;
//        }
//    }
//
//    /* ============================================================
//     * ===================== Function Folder =====================
//     * ============================================================ */
//
//    private static class FunctionFolder {
//        private final Set<String> functions;
//        private final SQLite3GlobalState globalState;
//        private final OjikenExpandMode expandMode;
//        private final LinkedHashMap<String, String> foldedExprMap = new LinkedHashMap<>();
//        private final LinkedHashMap<String, String> randomExprMap = new LinkedHashMap<>();
//        private int ojikenCounter = 0;
//
//        FunctionFolder(Set<String> functions,
//                       SQLite3GlobalState globalState,
//                       OjikenExpandMode expandMode) {
//            this.functions = functions;
//            this.globalState = globalState;
//            this.expandMode = expandMode;
//        }
//
//        Map<String, String> getFoldedExprMap() {
//            return foldedExprMap;
//        }
//
//        // 折叠 SQL，把聚合函数参数替换为 Ojiken 占位符
//        String foldSQL(String sql) {
//            foldedExprMap.clear();
//            randomExprMap.clear();
//            ojikenCounter = 0;
//
//            StringBuilder sb = new StringBuilder(sql);
//            int pos = 0;
//
//            while (pos < sb.length()) {
//                Matcher matcher = Pattern
//                        .compile("\\b([A-Z_]+)\\s*\\(", Pattern.CASE_INSENSITIVE)
//                        .matcher(sb);
//                if (!matcher.find(pos)) break;
//
//                String func = matcher.group(1).toUpperCase();
//                if (!functions.contains(func)) {
//                    pos = matcher.end();
//                    continue;
//                }
//
//                int open = matcher.end() - 1;
//                int close = findMatchingParen(sb, open);
//                if (close < 0) break;
//
//                String args = sb.substring(open + 1, close);
//                String ojiken = func + "_Ojiken" + (char) ('A' + ojikenCounter++);
//                foldedExprMap.put(ojiken, args);
//
//                if (expandMode == OjikenExpandMode.RANDOM_SELECT) {
//                    randomExprMap.put(ojiken, generateRandomScalarSelect());
//                }
//
//                sb.replace(open + 1, close, ojiken);
//                pos = open + 1 + ojiken.length();
//            }
//            return sb.toString();
//        }
//
//        // 展开 Ojiken 占位符
//        String unfoldOjiken(String sql) {
//            String res = sql;
//            Object[] keys = foldedExprMap.keySet().toArray();
//            for (int i = keys.length - 1; i >= 0; i--) {
//                String k = (String) keys[i];
//                String replacement =
//                        (expandMode == OjikenExpandMode.RANDOM_SELECT)
//                                ? randomExprMap.get(k)
//                                : "(" + foldedExprMap.get(k) + ")";
//                res = res.replaceAll(
//                        "\\b" + Pattern.quote(k) + "\\b",
//                        Matcher.quoteReplacement(replacement)
//                );
//            }
//            return res;
//        }
//
//        // 随机生成子查询占位
//        private String generateRandomScalarSelect() {
//            String q = SQLite3Visitor.asString(
//                    SQLite3RandomQuerySynthesizer.generate(globalState, 1)
//            );
//            return "(SELECT * FROM (" + q + ") LIMIT 1)";
//        }
//
//        // 找到匹配括号
//        private int findMatchingParen(CharSequence s, int open) {
//            int depth = 0;
//            boolean inSingle = false, inDouble = false;
//            for (int i = open; i < s.length(); i++) {
//                char c = s.charAt(i);
//                if (c == '\'' && !inDouble) {
//                    inSingle = !inSingle;
//                    continue;
//                }
//                if (c == '"' && !inSingle) {
//                    inDouble = !inDouble;
//                    continue;
//                }
//                if (inSingle || inDouble) continue;
//                if (c == '(') depth++;
//                else if (c == ')') {
//                    depth--;
//                    if (depth == 0) return i;
//                }
//            }
//            return -1;
//        }
//    }
//
//    /* ============================================================
//     * ===================== Function Mutator =====================
//     * ============================================================ */
//
//    interface FunctionMutator {
//        boolean shouldMutate(String sqlAfterFunction);
//
//        String mutate(String arg);
//    }
//
//    static class FunctionMutatorEngine {
//        private final Map<String, FunctionMutator> mutators;
//
//        FunctionMutatorEngine(Map<String, FunctionMutator> mutators) {
//            this.mutators = mutators;
//        }
//
//        // 根据 folded SQL 生成变异 SQL
//        Map<String, String> generateMutatedSQL(
//                String foldedSQL,
//                FunctionFolder folder,
//                String originalSQL) {
//
//            Map<String, String> result = new LinkedHashMap<>();
//            boolean originalHasOver = originalSQL.toUpperCase().contains("OVER");
//
//            for (Map.Entry<String, String> e : folder.getFoldedExprMap().entrySet()) {
//                String ojiken = e.getKey();
//                for (String func : mutators.keySet()) {
//                    if (!ojiken.startsWith(func)) continue;
//                    if ((func.equals("AVG") || func.equals("COUNT")) && originalHasOver) continue;
//
//                    FunctionMutator mutator = mutators.get(func);
//                    if (!mutator.shouldMutate(
//                            foldedSQL.substring(
//                                    foldedSQL.indexOf(ojiken) + ojiken.length()
//                            ))) {
//                        continue;
//                    }
//
//                    String mutatedSQL =
//                            foldedSQL.replace(func + "(" + ojiken + ")", mutator.mutate(ojiken));
//                    result.put(func + "_EQ_" + ojiken, mutatedSQL);
//                }
//            }
//            return result;
//        }
//    }
//
//    /* ============================================================
//     * ===================== Execution / Utils =====================
//     * ============================================================ */
//
//    private static class MutatedResult {
//        Throwable ex;
//        List<String> values = new ArrayList<>();
//        long timeNs = -1;
//    }
//
//    // 执行 SQL 并收集结果
//    private MutatedResult exec(String sql) {
//        MutatedResult r = new MutatedResult();
//        long t0 = System.nanoTime();
//        try (sqlancer.common.query.SQLancerResultSet rs =
//                     globalState.executeStatementAndGet(new SQLQueryAdapter(sql, false))) {
//
//            if (rs != null) {
//                int colCount = rs.getColumnCount();
//                while (rs.next()) {
//                    StringBuilder row = new StringBuilder();
//                    for (int i = 1; i <= colCount; i++) {
//                        if (i > 1) row.append(" | ");
//                        row.append(rs.getString(i));
//                    }
//                    r.values.add(row.toString());
//                }
//            }
//
//        } catch (Throwable e) {
//            r.ex = unwrap(e);
//        }
//        r.timeNs = System.nanoTime() - t0;
//        return r;
//    }
//
//    // 对比两个结果是否不同
//// 对比两个结果是否不同（支持整数与浮点等价）
//// 对比两个结果是否不同（终极安全版）
//    private boolean isDifferent(MutatedResult r1, MutatedResult r2) {
//        // ========== 1. 异常比较 ==========
//        if (r1.ex != null || r2.ex != null) {
//            if (r1.ex == null || r2.ex == null) return true;
//            return !r1.ex.getClass().equals(r2.ex.getClass());
//        }
//
//        // ========== 2. 行数不同 ==========
//        if (r1.values.size() != r2.values.size()) return true;
//
//        // ========== 3. 逐行逐列比较 ==========
//        for (int i = 0; i < r1.values.size(); i++) {
//            Object v1 = r1.values.get(i); // baseline
//            Object v2 = r2.values.get(i); // mutated
//
//            // --- NULL ---
//            if (v1 == null || v2 == null) {
//                if (v1 != v2) return true;
//                continue;
//            }
//
//            // ========= baseline 主导的数值语义比较 =========
//            Double d1 = parseDoubleSafe(v1);
//            if (d1 != null) { // baseline 是数值语义
//                Double d2 = parseDoubleSafe(v2);
//                if (d2 == null) return true; // mutated 无法转成数值 → 认为不等价
//                if (!numericEquivalent(d1, d2)) return true;
//                continue; // 数值等价 → 忽略类型
//            }
//
//            // --- BLOB ---
//            if (v1 instanceof byte[] && v2 instanceof byte[]) {
//                if (!Arrays.equals((byte[]) v1, (byte[]) v2)) return true;
//                continue;
//            }
//
//            // --- 其他（TEXT / BOOL / etc.）---
//            if (!Objects.equals(v1, v2)) return true;
//        }
//
//        return false;
//    }
//
//    private boolean numericEquivalent(double a, double b) {
//        if (Double.isNaN(a) || Double.isNaN(b)) return false;
//        if (Double.isInfinite(a) || Double.isInfinite(b)) return a == b;
//
//        int scale = 6;
//        double ra = roundToScale(a, scale);
//        double rb = roundToScale(b, scale);
//        if (Double.compare(ra, rb) == 0) return true;
//
//        double diff = Math.abs(a - b);
//        double eps = 1e-7; // 比你这个例子的 1.4e-8 稍大
//        return diff <= eps;
//    }
//
//    private double roundToScale(double x, int scale) {
//        double factor = Math.pow(10, scale);
//        return Math.round(x * factor) / factor;
//    }
//
//    private Double parseDoubleSafe(Object o) {
//        if (o == null) return null;
//
//        if (o instanceof Number) {
//            return ((Number) o).doubleValue();
//        }
//
//        if (o instanceof byte[]) {
//            try {
//                return Double.parseDouble(new String((byte[]) o).trim());
//            } catch (Exception e) {
//                return null;
//            }
//        }
//
//        if (o instanceof String) {
//            try {
//                return Double.parseDouble(((String) o).trim());
//            } catch (NumberFormatException e) {
//                return null;
//            }
//        }
//
//        return null;
//    }
//
//    // 解包异常
//    private Throwable unwrap(Throwable t) {
//        while (t != null && t.getCause() != null) {
//            t = t.getCause();
//        }
//        return t;
//    }
//
//    // 去除末尾空格和分号
//    private String trim(String sql) {
//        if (sql == null) return "";
//        sql = sql.trim();
//        if (sql.endsWith(";")) {
//            sql = sql.substring(0, sql.length() - 1);
//        }
//        return sql;
//    }
//
//    // 输出 MutatedResult
//    private String dumpMutated(MutatedResult r) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("  ex: ")
//                .append(r.ex == null
//                        ? "<null>"
//                        : (r.ex.getClass().getName() + ": " + r.ex.getMessage()))
//                .append("\n");
//        sb.append("  result:\n");
//        for (String val : r.values) {
//            sb.append("    ").append(val).append("\n");
//        }
//        sb.append("  time(ms): ").append(r.timeNs / 1_000_000.0).append("\n");
//        return sb.toString();
//    }
//}
//
