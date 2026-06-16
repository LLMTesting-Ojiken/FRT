package sqlancer.sqlite3.oracle.FRT.Other;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.ast.SQLite3Constant;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.schema.SQLite3Schema;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

public class SQLite3FunctionConsistencyOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private static final long TIME_UNKNOWN_NS = -1L;
    private static final int checkEH = 1;


    public SQLite3FunctionConsistencyOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {
        globalState.getState().setStatements(new ArrayList<>());
        SQLite3RandomOneColQuerySynthesizer.GeneratedQuery originalSQLquery =
                SQLite3RandomOneColQuerySynthesizer.generate(globalState, 1);
        MutatedResult result = exec(originalSQLquery.getSelect().asString());
        if (result.ex != null) {
            return;
        }
        aggregation_function_check(originalSQLquery);
    }

    public void aggregation_function_check(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        runFunctionCheck(expr, "avg", this::avgMutatedSQL, null);
        runFunctionCheck(expr, "count(*)", this::countStarMutatedSQL, "0");
        runFunctionCheck(expr, "count", this::countXMutatedSQL, "0");
        runFunctionCheck(expr, "median", this::medianMutatedSQL, "null");
        runFunctionCheck(expr, "max", this::maxMutatedSQL, null);
        runFunctionCheck(expr, "min", this::minMutatedSQL, null);
        runFunctionCheck(expr, "sum", this::sumMutatedSQL, null);
        runFunctionCheck(expr, "total", this::totalMutatedSQL, "0.0");
        runFunctionCheck(expr, "group_concat", e -> stringConcatMutatedSQL(e, ","), null);

    }


    private void runFunctionCheck(
            SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr,
            String funcName,
            Function<SQLite3RandomOneColQuerySynthesizer.GeneratedQuery, String> mutatedSQLGenerator,
            String emptyValue) {

        aliasFirstColumnOnce(expr, "col"); // 只 alias 一次

        String clearExpr = expr.getSelect().asString();
        MutatedResult clearResult = exec(clearExpr);

        boolean emptyInput = clearResult.values.isEmpty();
        boolean allNull = clearResult.values.stream().allMatch(v -> v == null);

        String originalSQL;
        if ("count(*)".equals(funcName)) {
            // ❌ 不要加 (col)
            originalSQL = "SELECT count(*) FROM (" + clearExpr + ")";
        } else {
            originalSQL = "SELECT " + funcName + "(col) FROM (" + clearExpr + ")";
        }

        if ("median".equals(funcName)) {
            return;
        }
        String mutatedSQL;
        if ("count(*)".equals(funcName)) {
            // 保持行数，同时有变异意义
            // 空集返回 0，而不是 NULL
            mutatedSQL = "SELECT COALESCE(SUM(1), 0) AS col FROM (" + clearExpr + ")";
        } else {
            mutatedSQL = mutatedSQLGenerator.apply(expr);
            if ((emptyInput || allNull) && emptyValue != null) {
                mutatedSQL = "SELECT " + emptyValue + " AS col";
            }
        }

        MutatedResult originalResult = exec(originalSQL);
        MutatedResult mutatedResult = exec(mutatedSQL);

        checkConsistency(
                originalSQL,
                mutatedSQL,
                clearExpr,
                originalResult,
                mutatedResult,
                clearResult,
                checkEH
        );
    }

    /**
     * 只 alias 一次，避免 col AS col AS col
     */
    private void aliasFirstColumnOnce(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr, String alias) {
        List<SQLite3Expression> fetchColumns = expr.getSelect().getFetchColumns();
        if (fetchColumns.isEmpty()) return;

        SQLite3Expression first = fetchColumns.get(0);
        if (first instanceof SQLite3Expression.SQLite3Alias) return;

        SQLite3Expression aliasExpr = SQLite3Constant.createTextConstant(alias);
        SQLite3Expression.SQLite3Alias newColExpr =
                new SQLite3Expression.SQLite3Alias(first, aliasExpr);

        expr.getSelect().setFetchColumns(Collections.singletonList(newColExpr));
    }

    // ================= 变异 SQL 生成器 =================
    private String sumMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();
        switch (new Random().nextInt(3)) {
            case 0:
                // 使用 TOTAL() + CASE，保留 SUM 忽略 NULL 的行为
                return "SELECT CASE WHEN COUNT(col) = 0 THEN NULL ELSE total(CAST(col AS REAL)) END AS sum_col " +
                        "FROM (" + clearExpr + ")";
            case 1:
                // 使用 AVG * COUNT 非 NULL 值
                return "SELECT CASE WHEN COUNT(col) = 0 THEN NULL ELSE AVG(CAST(col AS REAL)) * COUNT(col) END AS sum_col " +
                        "FROM (" + clearExpr + ")";
            default:
                // 先过滤 NULL，再用 TOTAL()
                return "SELECT CASE WHEN COUNT(*) = 0 THEN NULL ELSE total(col_val) END AS sum_col " +
                        "FROM (SELECT CAST(col AS REAL) AS col_val FROM (" + clearExpr + ") WHERE col IS NOT NULL)";
        }
    }

    private String totalMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();
        switch (new Random().nextInt(4)) {
            case 0:
                return "SELECT SUM(CAST(col AS REAL)) AS total_col FROM (" + clearExpr + ")";
            case 1:
                return "SELECT SUM(COALESCE(CAST(col AS REAL),0)) AS total_col FROM (" + clearExpr + ")";
            case 2:
                return "SELECT SUM(CASE WHEN col IS NOT NULL THEN CAST(col AS REAL) ELSE 0 END) AS total_col FROM (" + clearExpr + ")";
            default:
                return "SELECT SUM(col_val) AS total_col FROM (SELECT CAST(col AS REAL) AS col_val FROM (" + clearExpr + ") WHERE col IS NOT NULL)";
        }
    }

    private String avgMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();
        switch (new Random().nextInt(4)) {
            case 0:
                return "SELECT total(CAST(col AS REAL))/count(col) AS avg_col FROM (" + clearExpr + ")";
            case 1:
                return "SELECT SUM(CAST(col AS REAL))/COUNT(col) AS avg_col FROM (" + clearExpr + ")";
            case 2:
                return "SELECT SUM(COALESCE(CAST(col AS REAL),0))/COUNT(CASE WHEN col IS NOT NULL THEN 1 ELSE NULL END) AS avg_col FROM (" + clearExpr + ")";
            default:
                return "SELECT SUM(CASE WHEN col IS NOT NULL THEN CAST(col AS REAL) ELSE 0 END) / COUNT(CASE WHEN col IS NOT NULL THEN 1 ELSE NULL END) AS avg_col FROM (" + clearExpr + ")";
        }
    }

    private String minMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();

        return "SELECT " +
                "(SELECT col " +
                " FROM (" + clearExpr + ") " +
                " WHERE col IS NOT NULL " +
                " ORDER BY col ASC " +
                " LIMIT 1) AS min_col";
    }

    private String maxMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();

        return "SELECT " +
                "(SELECT col " +
                " FROM (" + clearExpr + ") " +
                " WHERE col IS NOT NULL " +
                " ORDER BY col DESC " +
                " LIMIT 1) AS max_col";
    }

    private String medianMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();

        // 随机选择不同突变写法
        switch (new Random().nextInt(2)) {
            case 0:
                // 方案1：排序 + OFFSET + AVG，奇偶行都正确，使用 NUMERIC 保留精度
                return "WITH sorted AS (" +
                        "    SELECT CAST(col AS NUMERIC) AS col " +
                        "    FROM (" + clearExpr + ") " +
                        "    WHERE col IS NOT NULL " +
                        "    ORDER BY col ASC" +
                        ") " +
                        "SELECT AVG(col) AS median_col " +
                        "FROM (" +
                        "    SELECT col " +
                        "    FROM sorted " +
                        "    LIMIT CASE WHEN (SELECT COUNT(*) FROM sorted) % 2 = 0 THEN 2 ELSE 1 END " +
                        "    OFFSET (SELECT (COUNT(*) - 1)/2 FROM sorted)" +
                        ")";
            default:
                // 方案2：同样逻辑，但可以用于 SQLFuzzer 突变测试
                return "WITH sorted AS (" +
                        "    SELECT CAST(col AS NUMERIC) AS col " +
                        "    FROM (" + clearExpr + ") " +
                        "    WHERE col IS NOT NULL " +
                        "    ORDER BY col ASC" +
                        ") " +
                        "SELECT AVG(col) AS median_col " +
                        "FROM (" +
                        "    SELECT col " +
                        "    FROM sorted " +
                        "    LIMIT 2 - (SELECT COUNT(*) % 2 FROM sorted) " +
                        "    OFFSET (SELECT (COUNT(*) - 1)/2 FROM sorted)" +
                        ")";
        }
    }

    private String countStarMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();
        return "SELECT SUM(1) AS count_star FROM (" + clearExpr + ")";
    }

    private String countXMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr) {
        String clearExpr = expr.getSelect().asString();
        return "SELECT SUM(CASE WHEN col IS NOT NULL THEN 1 ELSE 0 END) AS count_x FROM (" + clearExpr + ")";
    }

    private String stringConcatMutatedSQL(SQLite3RandomOneColQuerySynthesizer.GeneratedQuery expr, String sep) {
        String clearExpr = expr.getSelect().asString();
        if (new Random().nextBoolean()) {
            return "SELECT group_concat(col, '" + sep + "') AS concat_col FROM (" + clearExpr + ")";
        } else {
            return "SELECT string_agg(col, '" + sep + "') AS concat_col FROM (" + clearExpr + ")";
        }
    }

    // ================== 核心方法保持不变 ==================
    private void checkConsistency(String baseSQL,
                                  String cascadeSQL,
                                  String clear_expr,
                                  MutatedResult rBase,
                                  MutatedResult rCascade,
                                  MutatedResult rclear,
                                  int checkEH) {

        // ================= 1️⃣ 直接忽略 SUM integer overflow =================
        if (baseSQL.toLowerCase().contains("sum(") &&
                rBase.ex != null &&
                rBase.ex.getMessage() != null &&
                rBase.ex.getMessage().toLowerCase().contains("integer overflow")) {
            // Original SQL 报 SUM integer overflow → 直接跳过后续检查
            return;
        }

        // ================= 2️⃣ 原来的 checkEH==0 异常处理 =================
        if (checkEH == 0) {
            if (rBase.ex != null || rCascade.ex != null || rclear.ex != null) return;
        }

        boolean exceptionInconsistent = false;

        // ================= 3️⃣ EH bug 判断 =================
        if (checkEH == 1) {
            boolean clearHasEx = rclear.ex != null;
            boolean baseHasEx = rBase.ex != null;
            boolean mutatedHasEx = rCascade.ex != null;

            exceptionInconsistent = (baseHasEx != (clearHasEx || mutatedHasEx)) &&
                    (clearHasEx == mutatedHasEx);
        }

        // ================= 4️⃣ 三者异常但非 EH bug =================
        if ((rBase.ex != null || rCascade.ex != null || rclear.ex != null) && !exceptionInconsistent) {
            return;
        }

        // ================= 5️⃣ Value 不一致检查 =================
        boolean valueInconsistent = false;
        if (!(checkEH == 1 && exceptionInconsistent)) {
            List<String> baseValues = rBase.values != null ? rBase.values : new ArrayList<>();
            List<String> cascadeValues = rCascade.values != null ? rCascade.values : new ArrayList<>();

            List<String> sortedBase = new ArrayList<>(baseValues);
            List<String> sortedCascade = new ArrayList<>(cascadeValues);

            Collections.sort(sortedBase, (a, b) -> a == null ? -1 : (b == null ? 1 : a.compareTo(b)));
            Collections.sort(sortedCascade, (a, b) -> a == null ? -1 : (b == null ? 1 : a.compareTo(b)));

            if (sortedBase.size() != sortedCascade.size()) {
                valueInconsistent = true;
            } else {
                for (int i = 0; i < sortedBase.size(); i++) {
                    if (!approximatelyEqual(sortedBase.get(i), sortedCascade.get(i))) {
                        valueInconsistent = true;
                        break;
                    }
                }
            }
        }

        // ================= 6️⃣ 性能检查 =================
        final long SLOW_THRESHOLD_NS = 200_000_000L;
        boolean performanceInconsistent =
                (rCascade.timeNs - rBase.timeNs) > SLOW_THRESHOLD_NS;

        // ================= 7️⃣ 综合判断 =================
        boolean inconsistent = exceptionInconsistent || valueInconsistent || performanceInconsistent;

        if (!inconsistent) return;

        // ================= 8️⃣ 输出信息 =================
        StringBuilder msg = new StringBuilder();
        msg.append("\n===== Function Consistency ORACLE INCONSISTENCY =====\n\n");

        msg.append("expr SQL:\n")
                .append(trim(clear_expr)).append("\n")
                .append(dumpMutated(rclear)).append("\n");

        msg.append("Original SQL:\n")
                .append(trim(baseSQL)).append("\n")
                .append(dumpMutated(rBase)).append("\n");

        msg.append("Mutated SQL:\n")
                .append(trim(cascadeSQL)).append("\n")
                .append(dumpMutated(rCascade)).append("\n");

        if (exceptionInconsistent) msg.append("!! Exception inconsistency detected\n");
        if (valueInconsistent) msg.append("!! Result value mismatch\n");
        if (performanceInconsistent) msg.append("!! Mutated query is significantly slower\n");

        msg.append("\n------ SCHEMA + SAMPLE DATA ------\n")
                .append(globalState.getSchema()).append("\n")
                .append(dumpAllTables(globalState)).append("\n");

        System.out.println(dumpLastXSQLStatements(globalState, 10));
        globalState.getState().setStatements(new ArrayList<>());

        throw new AssertionError(msg.toString());
    }
    private boolean approximatelyEqual(String aStr, String bStr) {
        if (aStr == null && bStr == null) return true;
        if (aStr == null || bStr == null) return false;

        try {
            BigDecimal a = new BigDecimal(aStr);
            BigDecimal b = new BigDecimal(bStr);

            BigDecimal MAX_DOUBLE = new BigDecimal(Double.MAX_VALUE);
            BigDecimal MIN_DOUBLE = MAX_DOUBLE.negate();
            if (a.compareTo(MAX_DOUBLE) >= 0 && b.compareTo(MAX_DOUBLE) >= 0) return true;
            if (a.compareTo(MIN_DOUBLE) <= 0 && b.compareTo(MIN_DOUBLE) <= 0) return true;

            BigDecimal diff = a.subtract(b).abs();
            BigDecimal maxAbs = a.abs().max(b.abs()).max(BigDecimal.ONE);
            BigDecimal epsilon = new BigDecimal("1e-12");
            return diff.compareTo(maxAbs.multiply(epsilon)) <= 0;

        } catch (NumberFormatException e) {
            return aStr.equals(bStr);
        }
    }

    private String dumpAllTables(SQLite3GlobalState state) {
        StringBuilder sb = new StringBuilder();
        SQLite3Schema schema = state.getSchema();
        for (SQLite3Schema.SQLite3Table table : schema.getDatabaseTables()) {
            if (table.isSystemTable()) continue;
            sb.append("Table: ").append(table.getName()).append("\n");

            try (sqlancer.common.query.SQLancerResultSet rs =
                         state.executeStatementAndGet(new SQLQueryAdapter(
                                 "SELECT * FROM " + table.getName() + " LIMIT 50;", false))) {
                int cols = rs.getColumnCount();
                sb.append("Columns: ");
                for (int i = 1; i <= cols; i++) {
                    sb.append(rs.getColumnName(i));
                    if (i < cols) sb.append(", ");
                }
                sb.append("\n");
                while (rs.next()) {
                    sb.append("  Row: ");
                    for (int i = 1; i <= cols; i++) {
                        String val = rs.getString(i);
                        if (val == null) val = "NULL";
                        sb.append(val);
                        if (i < cols) sb.append(" | ");
                    }
                    sb.append("\n");
                }
            } catch (Throwable e) {
                sb.append("  <error reading table>: ").append(e.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String dumpLastXSQLStatements(SQLite3GlobalState state, int X) {
        StringBuilder sb = new StringBuilder();
        List<sqlancer.common.query.Query<?>> statements = state.getState().getStatements();
        if (statements == null || statements.isEmpty()) {
            sb.append("<no statements>\n");
        } else {
            int size = statements.size();
            int start = Math.max(0, size - X);
            sb.append("====== LAST ").append(X).append(" SQL STATEMENTS ======\n");
            for (int i = start; i < size; i++) {
                sb.append((i + 1)).append(": ")
                        .append(statements.get(i).getQueryString()).append(";\n");
            }
        }
        return sb.toString();
    }

    private String dumpMutated(MutatedResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ex: ").append(r.ex == null ? "<null>"
                : (r.ex.getClass().getName() + ": " + r.ex.getMessage())).append("\n");
        sb.append("  result:\n");
        for (String val : r.values) {
            sb.append("    ").append(val).append("\n");
        }
        sb.append("  time(ms): ").append(r.timeNs / 1_000_000.0).append("\n");
        return sb.toString();
    }

    private String trim(String sql) {
        if (sql == null) return "";
        sql = sql.trim();
        if (sql.endsWith(";")) return sql.substring(0, sql.length() - 1);
        return sql;
    }

    private static class MutatedResult {
        Throwable ex;
        List<String> values = new ArrayList<>();
        long timeNs = TIME_UNKNOWN_NS;
    }

    private MutatedResult exec(String sql) {
        MutatedResult r = new MutatedResult();
        long t0 = System.nanoTime();
        try (sqlancer.common.query.SQLancerResultSet rs =
                     globalState.executeStatementAndGet(new SQLQueryAdapter(sql, false))) {
            if (rs != null) {
                while (rs.next()) {
                    String val = rs.getString(1);
                    r.values.add(val);
                }
            }
        } catch (Throwable e) {
            r.ex = unwrap(e);
        }
        r.timeNs = System.nanoTime() - t0;
        return r;
    }

    private Throwable unwrap(Throwable t) {
        if (t == null) return null;
        while (t.getCause() != null) t = t.getCause();
        return t;
    }
}