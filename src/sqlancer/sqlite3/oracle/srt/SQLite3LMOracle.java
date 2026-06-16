package sqlancer.sqlite3.oracle.srt;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Expression.BetweenOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.InOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation.BinaryOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.BinaryComparisonOperation.BinaryComparisonOperator;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3ColumnName;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3PostfixUnaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3PostfixUnaryOperation.PostfixUnaryOperator;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.schema.SQLite3Schema;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite3LMOracle: 合并版逻辑短路 Oracle
 * - 随机嵌套 AND/OR 表达式生成
 * - 交换律一致性检测
 * - sleep 注入检测短路
 * - 时间统计、布尔化、异常匹配、仅在缺陷时抛 AssertionError
 */
public class SQLite3LMOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    private static final int MAX_TRIES = 50;
    private static final int MAX_LEAF_IN_LIST = 3;
    private static final long SLOW_THRESHOLD_NS = 1_000_000_000L;
    private static final long SLEEP_BLOB_BYTES = 5_000_000;
    private static final long TIME_UNKNOWN_NS = -1L;
    private final int lastXStatements = 10;

    public SQLite3LMOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

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

    @Override
    public void check() throws SQLException {
        String selectA = generateNestedWhereSelectWithChecks(1);
        String selectB = generateNonNullSelectExpr();

        MutatedResult baseA = exec(selectA);
        MutatedResult baseB = exec(selectB);

        String andAB = "SELECT ((" + trim(selectA) + ") AND (" + trim(selectB) + "))";
        String andBA = "SELECT ((" + trim(selectB) + ") AND (" + trim(selectA) + "))";
        String orAB = "SELECT ((" + trim(selectA) + ") OR (" + trim(selectB) + "))";
        String orBA = "SELECT ((" + trim(selectB) + ") OR (" + trim(selectA) + "))";

        MutatedResult andResAB = exec(andAB);
        MutatedResult andResBA = exec(andBA);
        MutatedResult orResAB = exec(orAB);
        MutatedResult orResBA = exec(orBA);

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

    // ------------------ 表达式生成 ------------------

    private String generateNestedWhereSelectWithChecks(int maxDepth) throws SQLException {
        List<SQLite3Table> tables = globalState.getSchema().getDatabaseTables();
        if (tables.isEmpty()) throw new SQLException("No tables available");

        SQLite3Table table = Randomly.fromList(tables);
        List<SQLite3Column> columns = table.getColumns();
        if (columns.isEmpty()) throw new SQLException("Table has no columns");

//        int numCols = Math.max(1, Randomly.smallNumber() + 1);
        int numCols = 1;
        StringBuilder fetchCols = new StringBuilder();
        for (int i = 0; i < numCols; i++) {
            SQLite3Column c = Randomly.fromList(columns);
            fetchCols.append(c.getName());
//            if (i < numCols - 1) fetchCols.append(", ");
        }

        SQLite3Expression whereExpr = generateNestedExpressionWithChecks(maxDepth);
        return "SELECT " + fetchCols + " FROM " + table.getName() +
                " WHERE " + SQLite3Visitor.asString(whereExpr);
    }

    private SQLite3Expression generateNestedExpressionWithChecks(int depth) throws SQLException {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            try {
                SQLite3Expression expr = generateNestedExpressionTry(depth);
                if (expr != null) return expr;
            } catch (AssertionError ae) {
                throw ae;
            } catch (Throwable ignored) {
            }
        }
        throw new SQLException("Failed to generate valid nested expression after " + MAX_TRIES + " attempts.");
    }

    private SQLite3Expression generateNestedExpressionTry(int depth) throws SQLException {
        if (depth <= 0) return generateBooleanLeaf();

        SQLite3Expression a = generateNestedExpressionTry(depth - 1);
        SQLite3Expression b = generateNestedExpressionTry(depth - 1);
        BinaryOperator op = Randomly.getBoolean() ? BinaryOperator.AND : BinaryOperator.OR;

        Sqlite3BinaryOperation expr1 = new Sqlite3BinaryOperation(a, b, op);
        Sqlite3BinaryOperation expr2 = new Sqlite3BinaryOperation(b, a, op);

        MutatedResult r1 = exec("SELECT (" + SQLite3Visitor.asString(expr1) + ") AS __tmp");
        MutatedResult r2 = exec("SELECT (" + SQLite3Visitor.asString(expr2) + ") AS __tmp");

        if (mismatch(r1, r2)) {
            throw new AssertionError("Logical short-circuit inconsistency: " +
                    SQLite3Visitor.asString(expr1) + " vs " + SQLite3Visitor.asString(expr2));
        }

        if (Randomly.getBoolean()) {
            SQLite3Expression c = generateNestedExpressionTry(depth - 1);
            Sqlite3BinaryOperation nextExpr = new Sqlite3BinaryOperation(c, expr1,
                    Randomly.getBoolean() ? BinaryOperator.AND : BinaryOperator.OR);
            Sqlite3BinaryOperation nextExprSwapped = new Sqlite3BinaryOperation(expr1, c, nextExpr.getOperator());

            MutatedResult r3 = exec("SELECT (" + SQLite3Visitor.asString(nextExpr) + ") AS __tmp");
            MutatedResult r4 = exec("SELECT (" + SQLite3Visitor.asString(nextExprSwapped) + ") AS __tmp");

            if (mismatch(r3, r4)) {
                throw new AssertionError("Nested logical inconsistency: " +
                        SQLite3Visitor.asString(nextExpr) + " vs " + SQLite3Visitor.asString(nextExprSwapped));
            }
            return nextExpr;
        }
        return expr1;
    }

    private boolean mismatch(MutatedResult r1, MutatedResult r2) {
        boolean exceptionMismatch = (r1.ex == null) != (r2.ex == null) ||
                (r1.ex != null && r2.ex != null && !r1.ex.getClass().equals(r2.ex.getClass()));
        boolean resultMismatch = (r1.ex == null && r2.ex == null && !equalsNullable(r1.value, r2.value));
        return exceptionMismatch || resultMismatch;
    }

    private SQLite3Expression generateBooleanLeaf() throws SQLException {
        int choice = (int) Randomly.getNotCachedInteger(0, 6);
        switch (choice) {
            case 0:
                return generateComparisonLeaf();
            case 1:
                return generateBetweenLeaf();
            case 2:
                return generateIsNullLeaf();
            case 3:
                return generateInListLeaf();
            case 4:
                return generateLikeLeaf();
            case 5:
                return generateExistsLeaf();
            default:
                SQLite3Expression inner = generateComparisonLeaf();
                return Randomly.getBoolean() ?
                        new SQLite3PostfixUnaryOperation(PostfixUnaryOperator.IS_TRUE, inner) :
                        new SQLite3PostfixUnaryOperation(PostfixUnaryOperator.IS_FALSE, inner);
        }
    }

    private SQLite3Expression generateComparisonLeaf() {
        return new BinaryComparisonOperation(genColumnOrLiteral(), genColumnOrLiteral(),
                BinaryComparisonOperator.getRandomOperator());
    }

    private SQLite3Expression generateBetweenLeaf() {
        return new BetweenOperation(genColumnOrLiteral(), Randomly.getBoolean(),
                genColumnOrLiteral(), genColumnOrLiteral());
    }

    private SQLite3Expression generateIsNullLeaf() {
        SQLite3Expression e = genColumnOrLiteral();
        return Randomly.getBoolean() ?
                new SQLite3PostfixUnaryOperation(PostfixUnaryOperator.ISNULL, e) :
                new SQLite3PostfixUnaryOperation(PostfixUnaryOperator.NOT_NULL, e);
    }

    private SQLite3Expression generateInListLeaf() {
        SQLite3Expression left = genColumnOrLiteral();
        List<SQLite3Expression> right = new ArrayList<>();
        int n = Math.max(1, Randomly.smallNumber() % (MAX_LEAF_IN_LIST + 1));
        for (int i = 0; i < n; i++) right.add(genColumnOrLiteral());
        return new InOperation(left, right);
    }

    private SQLite3Expression generateLikeLeaf() {
        return new BinaryComparisonOperation(genColumnOrLiteral(), genColumnOrLiteral(),
                BinaryComparisonOperator.LIKE);
    }

    private SQLite3Expression generateExistsLeaf() {
        List<SQLite3Table> tables = globalState.getSchema().getDatabaseTables();
        if (tables.isEmpty()) return generateComparisonLeaf();
        SQLite3Table t = Randomly.fromList(tables);
        SQLite3Expression subWhere;
        try {
            subWhere = generateNestedExpressionWithChecks(1);
        } catch (Throwable e) {
            subWhere = SQLite3ExpressionGenerator.getRandomLiteralValue(globalState);
        }
        String subq = "SELECT 1 FROM " + t.getName() + " WHERE " + SQLite3Visitor.asString(subWhere) + " LIMIT 1";
        return new SQLite3Expression.SQLite3Exist(SQLite3Expression.Subquery.create(subq), false);
    }

    private SQLite3Expression genColumnOrLiteral() {
        try {
            List<SQLite3Table> tables = globalState.getSchema().getDatabaseTables();
            if (!tables.isEmpty() && Randomly.getBoolean()) {
                SQLite3Table t = Randomly.fromList(tables);
                List<SQLite3Column> cols = t.getColumns();
                if (!cols.isEmpty()) return new SQLite3ColumnName(Randomly.fromList(cols), null);
            }
        } catch (Throwable ignored) {
        }
        return SQLite3ExpressionGenerator.getRandomLiteralValue(globalState);
    }
// ================== SQL 执行辅助 ==================

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

// ================== sleep 注入 ==================

    private String wrapWithSleepShortCircuit(String rightExpr) {
        long bytes = SLEEP_BLOB_BYTES;
        return "((" + rightExpr + ") AND (SELECT length(randomblob(" + bytes + "))) IS NOT NULL)";
    }

// ================== baseline + correctness 检查 ==================

    private void checkPairWithBaseline(
            String label,
            String baseAExpr, String baseBExpr,
            String sqlA, String sqlB,
            MutatedResult resA, MutatedResult resB,
            MutatedResult baseA, MutatedResult baseB,
            String sleepSqlA, String sleepSqlB,
            MutatedResult sleepA, MutatedResult sleepB) {

        boolean exceptionMismatch =
                (resA.ex == null && resB.ex != null) ||
                        (resA.ex != null && resB.ex == null) ||
                        (resA.ex != null && resB.ex != null
                                && !resA.ex.getClass().equals(resB.ex.getClass()));

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

        boolean sleepTriggeredA = (sleepA.timeNs - resA.timeNs) > SLOW_THRESHOLD_NS;
        boolean sleepTriggeredB = (sleepB.timeNs - resB.timeNs) > SLOW_THRESHOLD_NS;

        if (exceptionMismatch || resultMismatch || !resFromBaseline
                || sleepTriggeredA || sleepTriggeredB) {

            System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
            globalState.getState().setStatements(new ArrayList<>());

            StringBuilder msg = new StringBuilder();
            msg.append("\n===== LOGICAL SHORT-CIRCUIT INCONSISTENCY (" + label + ") =====\n\n");

            msg.append("baseA (SELECT): ").append(baseAExpr).append("\n")
                    .append(dumpMutated(baseA)).append("\n");
            msg.append("baseB (SELECT): ").append(baseBExpr).append("\n")
                    .append(dumpMutated(baseB)).append("\n");

            msg.append("Expected: ").append(expectedSQL).append("\n")
                    .append(dumpMutated(expectedResult)).append("\n");

            msg.append("SQL AB: ").append(sqlA).append("\n")
                    .append(dumpMutated(resA)).append("\n");
            msg.append("SQL BA: ").append(sqlB).append("\n")
                    .append(dumpMutated(resB)).append("\n");

            msg.append("SQL AB (sleep): ").append(sleepSqlA).append("\n")
                    .append(dumpMutated(sleepA)).append("\n");
            msg.append("SQL BA (sleep): ").append(sleepSqlB).append("\n")
                    .append(dumpMutated(sleepB)).append("\n");

            msg.append("------ REASONS ------\n");
            if (exceptionMismatch) msg.append(" - Exception mismatch.\n");
            if (resultMismatch) msg.append(" - Result mismatch.\n");
            if (!resFromBaseline) msg.append(" - Result does not match baseline truth value.\n");
            if (sleepTriggeredA) msg.append(" - Sleep triggered unexpectedly in A.\n");
            if (sleepTriggeredB) msg.append(" - Sleep triggered unexpectedly in B.\n");

            msg.append("\n------ SCHEMA + SAMPLE DATA ------\n")
                    .append(globalState.getSchema()).append("\n")
                    .append(dumpAllTables(globalState)).append("\n");

            throw new AssertionError(msg.toString());
        }
    }

// ================== SQLite 布尔规则实现 ==================

    private Boolean sqliteBooleanValue(String expr) {
        if (expr == null) {
            return null; // SQLite: NULL stays NULL
        }

        expr = expr.trim();
        if (expr.isEmpty()) {
            return false; // Empty string → NUMERIC 0 → false
        }

        // Handle TRUE / FALSE literals (SQLite 3.23+)
        String lower = expr.toLowerCase();
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }

        double numericValue;

        // 1) Try integer literal (including hex)
        if (expr.matches("0[xX][0-9a-fA-F]+")) {
            try {
                numericValue = Long.parseLong(expr.substring(2), 16);
                return numericValue != 0.0; // no need to continue parsing
            } catch (Throwable ignored) {
            }
        }

        // 2) Try pure integer / real number
        try {
            numericValue = Double.parseDouble(expr);
            return numericValue != 0.0;
        } catch (Throwable ignored) {
        }

        // 3) Try NUMERIC affinity prefix parsing:
        //    SQLite extracts leading numeric part like "1english" -> 1, "0abc" -> 0
        StringBuilder numericPrefix = new StringBuilder();
        boolean seenDigit = false;
        boolean seenDecimal = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);

            if (Character.isDigit(c)) {
                numericPrefix.append(c);
                seenDigit = true;
            } else if (c == '.' && !seenDecimal) {
                numericPrefix.append(c);
                seenDecimal = true;
            } else if ((c == '+' || c == '-') && numericPrefix.length() == 0) {
                // sign allowed only at start
                numericPrefix.append(c);
            } else {
                break; // stop at first invalid char
            }
        }

        if (seenDigit) {
            try {
                numericValue = Double.parseDouble(numericPrefix.toString());
                return numericValue != 0.0;
            } catch (Throwable ignored) {
            }
        }

        // 4) Cannot parse as numeric → NUMERIC 0 → false
        return false;
    }

// ================== dump 方法 ==================

    private String dumpMutated(MutatedResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ex: ").append(r.ex == null ? "<null>"
                : (r.ex.getClass().getName() + ": " + r.ex.getMessage())).append("\n");
        sb.append("  result: ").append(r.value).append("\n");
        sb.append("  time(ms): ").append(r.timeNs / 1_000_000.0).append("\n");
        return sb.toString();
    }

    private String dumpAllTables(SQLite3GlobalState state) {
        StringBuilder sb = new StringBuilder();
        SQLite3Schema schema = state.getSchema();
        for (SQLite3Table table : schema.getDatabaseTables()) {
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

    private boolean equalsNullable(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private Throwable unwrap(Throwable t) {
        if (t == null) return null;
        while (t.getCause() != null) t = t.getCause();
        return t;
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
}