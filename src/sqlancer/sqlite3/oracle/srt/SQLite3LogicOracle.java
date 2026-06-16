package sqlancer.sqlite3.oracle.srt;

import org.sqlite.SQLiteException;
import sqlancer.IgnoreMeException;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.Query;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.schema.SQLite3Schema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle for SQLite3 logical short-circuit testing.
 * Supports AND/OR short-circuit checks and sleep injection
 * for detecting unexpected evaluation.
 */
public class SQLite3LogicOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private final int lastXStatements = 10;

    private static final int MAX_TRIES = 50;
    private static final long SLOW_THRESHOLD_NS = 1_000_000_000L;
    private static final long SLEEP_BLOB_BYTES = 5_000_000;
    private static final long TIME_UNKNOWN_NS = -1L;
    private static final long TIME_EPSILON_NS = 5_000_000L;

    public SQLite3LogicOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {
        String selectA = generateNonNullSelectExpr();
        String selectB = generateNonNullSelectExpr();

        MutatedResult baseA = exec(selectA);
        MutatedResult baseB = exec(selectB);

        // —— 构造 AND/OR SQL ——
        String andAB = "SELECT ((" + trim(selectA) + ") AND (" + trim(selectB) + "))";
        String andBA = "SELECT ((" + trim(selectB) + ") AND (" + trim(selectA) + "))";
        String orAB = "SELECT ((" + trim(selectA) + ") OR (" + trim(selectB) + "))";
        String orBA = "SELECT ((" + trim(selectB) + ") OR (" + trim(selectA) + "))";

        // —— 执行原始 SQL ——
        MutatedResult andResAB = exec(andAB);
        MutatedResult andResBA = exec(andBA);
        MutatedResult orResAB = exec(orAB);
        MutatedResult orResBA = exec(orBA);

        // —— 构造 sleep 短路 SQL ——
        String andABSleep = "SELECT ((" + trim(selectA) + ") AND ("
                + wrapWithSleepShortCircuit(trim(selectB)) + "))";
        String andBASleep = "SELECT ((" + trim(selectB) + ") AND ("
                + wrapWithSleepShortCircuit(trim(selectA)) + "))";
        String orABSleep = "SELECT ((" + trim(selectA) + ") OR ("
                + wrapWithSleepShortCircuit(trim(selectB)) + "))";
        String orBASleep = "SELECT ((" + trim(selectB) + ") OR ("
                + wrapWithSleepShortCircuit(trim(selectA)) + "))";

        MutatedResult andResABSleep = exec(andABSleep);
        MutatedResult andResBASleep = exec(andBASleep);
        MutatedResult orResABSleep = exec(orABSleep);
        MutatedResult orResBASleep = exec(orBASleep);

        // —— 检查逻辑一致性 ——
        checkPairWithBaseline(
                "AND",
                trim(selectA), trim(selectB),
                andAB, andBA,
                andResAB, andResBA,
                baseA, baseB,
                andABSleep, andBASleep,
                andResABSleep, andResBASleep
        );

        checkPairWithBaseline(
                "OR",
                trim(selectA), trim(selectB),
                orAB, orBA,
                orResAB, orResBA,
                baseA, baseB,
                orABSleep, orBASleep,
                orResABSleep, orResBASleep
        );
    }

    // ================== 工具方法 ==================

    private String generateNonNullSelectExpr() throws SQLException {
        for (int i = 0; i < MAX_TRIES; i++) {
            SQLite3Expression expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);
            String select = SQLite3Visitor.asString(expr);
            try (sqlancer.common.query.SQLancerResultSet rs =
                         globalState.executeStatementAndGet(new SQLQueryAdapter(select, false))) {
                // 测试是否可以执行
            } catch (Throwable ignored) {
                continue;
            }
            return select;
        }
        throw new SQLException("Failed to generate non-erroring expression after " + MAX_TRIES + " tries.");
    }

    private MutatedResult exec(String sql) {
        MutatedResult r = new MutatedResult();
        long t0 = System.nanoTime();
        try {
            r.value = executeAndGetSingleValue(sql);
        } catch (Throwable e) {
            r.ex = unwrap(e);
        }
        r.timeNs = System.nanoTime() - t0;
        return r;
    }

    private String executeAndGetSingleValue(String sql) {
        try (sqlancer.common.query.SQLancerResultSet rs =
                     globalState.executeStatementAndGet(new SQLQueryAdapter(sql, false))) {
            if (rs == null || !rs.next()) return null;
            return rs.getString(1);
        } catch (Throwable e) {
            throw new IgnoreMeException();
        }
    }

    private String wrapWithSleepShortCircuit(String rightExpr) {
        long bytes = SLEEP_BLOB_BYTES;
        return "((" + rightExpr + ") AND (SELECT length(randomblob(" + bytes + "))) IS NOT NULL)";
    }

    // ================== 核心方法 ==================

    private void checkPairWithBaseline(
            String label,
            String baseAExpr, String baseBExpr,
            String sqlA, String sqlB,
            MutatedResult resA, MutatedResult resB,
            MutatedResult baseA, MutatedResult baseB,
            String sleepSqlA, String sleepSqlB,
            MutatedResult sleepA, MutatedResult sleepB) {

        if ((containsDistinctOffset(baseAExpr) && baseA.value == null) ||
                (containsDistinctOffset(baseBExpr) && baseB.value == null)) {
            return;
        }

        boolean exceptionMismatch =
                (resA.ex == null && resB.ex != null) ||
                        (resA.ex != null && resB.ex == null) ||
                        (resA.ex != null && resB.ex != null
                                && !resA.ex.getClass().equals(resB.ex.getClass()));

        // —— 使用 SQLite CAST → NUMERIC → 布尔规则生成 Expected SQL —— //
        Boolean boolA = sqliteBooleanValue(baseA.value);
        Boolean boolB = sqliteBooleanValue(baseB.value);

        String strA = boolA == null ? "NULL" : (boolA ? "1" : "0");
        String strB = boolB == null ? "NULL" : (boolB ? "1" : "0");

        String expectedSQL = "SELECT (" + strA + " " + label + " " + strB + ")";
        MutatedResult expectedResult = exec(expectedSQL);

        boolean resultMismatch = false;
        if (resA.ex == null && resB.ex == null) {
            if (!equalsNullable(resA.value, expectedResult.value)) resultMismatch = true;
            if (!equalsNullable(resB.value, expectedResult.value)) resultMismatch = true;
        }

        boolean resFromBaseline = false;
        if (resA.ex != null) {
            if ((baseA.ex != null && baseA.ex.getClass().equals(resA.ex.getClass()))
                    || (baseB.ex != null && baseB.ex.getClass().equals(resA.ex.getClass()))) {
                resFromBaseline = true;
            }
        } else {
            if (equalsNullable(resA.value, expectedResult.value)) resFromBaseline = true;
        }

        long minBaseTime = TIME_UNKNOWN_NS;
        if (baseA.timeNs > 0 && baseB.timeNs > 0) {
            minBaseTime = Math.min(baseA.timeNs, baseB.timeNs);
        } else if (baseA.timeNs > 0) {
            minBaseTime = baseA.timeNs;
        } else if (baseB.timeNs > 0) {
            minBaseTime = baseB.timeNs;
        }

        boolean timeViolation = false;
        if (minBaseTime > 0) {
            if (resA.timeNs + TIME_EPSILON_NS < minBaseTime
                    || resB.timeNs + TIME_EPSILON_NS < minBaseTime) {
                timeViolation = true;
            }
        }

        boolean slowDiff = Math.abs(resA.timeNs - resB.timeNs) > SLOW_THRESHOLD_NS;
        boolean sleepTriggeredA = (sleepA.timeNs - resA.timeNs) > SLOW_THRESHOLD_NS;
        boolean sleepTriggeredB = (sleepB.timeNs - resB.timeNs) > SLOW_THRESHOLD_NS;

        if (exceptionMismatch || resultMismatch || !resFromBaseline || timeViolation
                || slowDiff || sleepTriggeredA || sleepTriggeredB) {

            System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
            globalState.getState().setStatements(new ArrayList<>());

            StringBuilder msg = new StringBuilder();
            msg.append("\n===== LOGICAL SHORT-CIRCUIT INCONSISTENCY (" + label + ") =====\n\n");
            msg.append("baseA (SELECT): ").append(baseAExpr).append("\n")
                    .append(dumpMutated(baseA)).append("\n\n");
            msg.append("baseB (SELECT): ").append(baseBExpr).append("\n")
                    .append(dumpMutated(baseB)).append("\n\n");
            msg.append("Expected (SELECT): ").append(expectedSQL).append("\n")
                    .append(dumpMutated(expectedResult)).append("\n\n");
            msg.append("SQL AB: ").append(sqlA).append("\n")
                    .append(dumpMutated(resA)).append("\n\n");
            msg.append("SQL BA: ").append(sqlB).append("\n")
                    .append(dumpMutated(resB)).append("\n\n");
            msg.append("SQL AB (sleep): ").append(sleepSqlA).append("\n")
                    .append(dumpMutated(sleepA)).append("\n\n");
            msg.append("SQL BA (sleep): ").append(sleepSqlB).append("\n")
                    .append(dumpMutated(sleepB)).append("\n\n");

            msg.append("------ REASONS ------\n");
            if (exceptionMismatch) msg.append(" - Exception mismatch between swapped variants.\n");
            if (resultMismatch) msg.append(" - Result mismatch between swapped variants.\n");
            if (!resFromBaseline) msg.append(" - Mutated result does not match expected value.\n");
            if (timeViolation) msg.append(" - Mutated query is faster than baseline.\n");
            if (slowDiff) msg.append(" - Swapped pair timing differs too much.\n");
            if (sleepTriggeredA) msg.append(" - Sleep triggered unexpectedly in A.\n");
            if (sleepTriggeredB) msg.append(" - Sleep triggered unexpectedly in B.\n");

            msg.append("\n------ SCHEMA + SAMPLE DATA ------\n")
                    .append(globalState.getSchema()).append("\n")
                    .append(dumpAllTables(globalState)).append("\n");

            throw new AssertionError(msg.toString());
        }
    }

    private Boolean sqliteBooleanValue(String expr) {
        if (expr == null) return null;
        expr = expr.trim();
        if (expr.isEmpty()) return false;

        double numericValue = 0.0;
        boolean parsed = false;

        if (expr.matches("0[xX][0-9a-fA-F]+")) {
            try {
                numericValue = Long.parseLong(expr.substring(2), 16);
                parsed = true;
            } catch (NumberFormatException ignored) {}
        }

        if (!parsed) {
            int i = 0;
            boolean hasDigit = false;
            boolean hasDot = false;
            while (i < expr.length()) {
                char c = expr.charAt(i);
                if (Character.isDigit(c)) hasDigit = true;
                else if (c == '.' && !hasDot) hasDot = true;
                else if (i == 0 && (c == '+' || c == '-')) {}
                else break;
                i++;
            }
            if (hasDigit) {
                try {
                    numericValue = Double.parseDouble(expr.substring(0, i));
                    parsed = true;
                } catch (NumberFormatException ignored) {}
            }
        }

        if (!parsed) {
            if (expr.equalsIgnoreCase("inf") || expr.equalsIgnoreCase("infinity")) {
                numericValue = Double.POSITIVE_INFINITY;
                parsed = true;
            } else if (expr.equalsIgnoreCase("-inf") || expr.equalsIgnoreCase("-infinity")) {
                numericValue = Double.NEGATIVE_INFINITY;
                parsed = true;
            }
        }

        if (!parsed) numericValue = 0.0;
        if (Double.isNaN(numericValue)) return false;
        return numericValue != 0.0;
    }

    // ================== 其他工具方法 ==================

    private boolean containsDistinctOffset(String sql) {
        if (sql == null) return false;
        String s = sql.toUpperCase();
        return s.contains("DISTINCT") && s.contains("OFFSET");
    }

    private boolean equalsNullable(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return normalizeEmptyToZero(a).equals(normalizeEmptyToZero(b));
    }

    private String normalizeEmptyToZero(String s) {
        if (s == null) return null;
        return s.isEmpty() ? "0" : s;
    }

    private Throwable unwrap(Throwable t) {
        if (t == null) return null;
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    private String trim(String sql) {
        if (sql == null) return "";
        sql = sql.trim();
        if (sql.endsWith(";")) return sql.substring(0, sql.length() - 1);
        return sql;
    }

    private static class MutatedResult {
        Throwable ex;
        String value;
        long timeNs = TIME_UNKNOWN_NS;
    }

    // ================== dump 方法 ==================

    private String dumpMutated(MutatedResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ex: ").append(r.ex == null ? "<null>"
                : (r.ex.getClass().getName() + ": " + r.ex.getMessage())).append("\n");
        sb.append("  result: ").append(r.value).append("\n");
        sb.append("  time(ms): ").append(r.timeNs / 1_000_000.0).append("\n");
        if (r.ex != null) sb.append("  ex-dump: ").append(dumpException(r.ex)).append("\n");
        return sb.toString();
    }

    private String dumpException(Throwable t) {
        if (t == null) return "<null>";
        return "Class: " + t.getClass().getName() + "\n" +
                "Message: " + t.getMessage() + "\n" +
                "SQLiteCode: " +
                (t instanceof SQLiteException ? ((SQLiteException) t).getResultCode().code : -1);
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
                    if (i != cols) sb.append(", ");
                }
                sb.append("\n");
                while (rs.next()) {
                    sb.append("  Row: ");
                    for (int i = 1; i <= cols; i++) {
                        String val = rs.getString(i);
                        if (val == null) val = "NULL(∅)";
                        else if (val.isEmpty()) val = "\"\"(empty)";
                        else val = "\"" + val.replace("\"", "\\\"") + "\"";
                        sb.append(val);
                        if (i != cols) sb.append(" | ");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                sb.append("  <error reading table>: ").append(e.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String dumpLastXSQLStatements(SQLite3GlobalState state, int X) {
        StringBuilder sb = new StringBuilder();
        List<Query<?>> statements = state.getState().getStatements();
        if (statements == null || statements.isEmpty()) sb.append("<no statements>\n");
        else {
            int size = statements.size();
            int start = Math.max(0, size - X);
            sb.append("====== LAST ").append(X).append(" SQL STATEMENTS ======\n");
            for (int i = start; i < size; i++) {
                sb.append(i + 1).append(": ")
                        .append(statements.get(i).getQueryString()).append(";\n");
            }
        }
        return sb.toString();
    }
}