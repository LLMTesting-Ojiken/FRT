package sqlancer.sqlite3.oracle.srt;

/*
 * =============================================================
 *  SQLite3 Cascade Oracle
 *
 *  目的：
 *  - 构造 Base SQL 与其 Cascade（投影 + 选择拆分）等价变换
 *  - 执行两者并进行一致性校验（结果 / 异常 / 性能）
 *
 *  核心思想：
 *  - Base SQL：单层 SELECT + WHERE
 *  - Cascade SQL：
 *      * 内层：列别名化 + 噪声列
 *      * 外层：逐层拆分 WHERE (AND-chain)
 *  - Oracle：比较 Base vs Cascade 是否等价
 *
 *  注意：
 *  - 当前实现【不做 purity 检查】，可能故意制造不等价，用于发现优化器问题
 *  - 噪声列用于增加 AST / 规划路径压力，不参与最终输出
 * =============================================================
 */

import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.Sqlite3BinaryOperation.BinaryOperator;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.schema.SQLite3Schema;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SQLite3CascadeOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private static final int MAX_TRIES = 50;
    private static final long TIME_UNKNOWN_NS = -1L;
    private static final int checkEH = 0;

    private SQLite3Table lastBaseTable;
    private List<SQLite3Column> lastAllCol;
    private String lastBaseCol;

    private String lastBaseSQL;
    private SQLite3Expression whereExpr;

    private final List<SQLite3Expression> whereAndList = new ArrayList<>();

    public SQLite3CascadeOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
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

    @Override
    public void check() throws SQLException {
        globalState.getState().setStatements(new ArrayList<>());

        lastBaseSQL = generateBaseSelect(1);

        String cascadeSQL = generateNestedProjectionCascade(1);
        String cascadeSQL2 = generateNestedProjectionCascade(1);
        String cascadeSQL3 = generateNestedProjectionCascade(1);

        MutatedResult baseResult = exec(lastBaseSQL);
        MutatedResult cascadeResult = exec(cascadeSQL);
        MutatedResult cascadeResult2 = exec(cascadeSQL2);
        MutatedResult cascadeResult3 = exec(cascadeSQL3);

        checkConsistency(lastBaseSQL, cascadeSQL, baseResult, cascadeResult, checkEH);
        checkConsistency(lastBaseSQL, cascadeSQL2, baseResult, cascadeResult2, checkEH);
        checkConsistency(lastBaseSQL, cascadeSQL3, baseResult, cascadeResult3, checkEH);
    }

    private String trim(String sql) {
        if (sql == null) return "";
        sql = sql.trim();
        if (sql.endsWith(";")) return sql.substring(0, sql.length() - 1);
        return sql;
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

    private void checkConsistency(String baseSQL,
                                  String cascadeSQL,
                                  MutatedResult rBase,
                                  MutatedResult rCascade,
                                  int checkEH) {

        if (checkEH == 0) {
            if (rBase.ex != null || rCascade.ex != null) return;
        }

        boolean exceptionInconsistent = false;
        if (checkEH == 1) {
            boolean baseHasEx = rBase.ex != null;
            boolean cascadeHasEx = rCascade.ex != null;
            exceptionInconsistent = baseHasEx != cascadeHasEx ||
                    (baseHasEx && !rBase.ex.getClass().equals(rCascade.ex.getClass()));
        }

        boolean valueInconsistent = false;
        if (!(checkEH == 1 && exceptionInconsistent)) {
            List<String> baseValues = rBase.values != null ? rBase.values : new ArrayList<>();
            List<String> cascadeValues = rCascade.values != null ? rCascade.values : new ArrayList<>();

            List<String> sortedBase = new ArrayList<>(baseValues);
            List<String> sortedCascade = new ArrayList<>(cascadeValues);

            Collections.sort(sortedBase, (a, b) -> a == null ? -1 : (b == null ? 1 : a.compareTo(b)));
            Collections.sort(sortedCascade, (a, b) -> a == null ? -1 : (b == null ? 1 : a.compareTo(b)));

            valueInconsistent = !sortedBase.equals(sortedCascade);
        }

        final long SLOW_THRESHOLD_NS = 200_000_000L;
        boolean performanceInconsistent =
                (rCascade.timeNs - rBase.timeNs) > SLOW_THRESHOLD_NS;

        boolean inconsistent = exceptionInconsistent || valueInconsistent || performanceInconsistent;

        if (!inconsistent) return;

        StringBuilder msg = new StringBuilder();
        msg.append("\n===== CASCADE ORACLE INCONSISTENCY =====\n\n");

        msg.append("Base SQL:\n")
                .append(trim(baseSQL)).append("\n")
                .append(dumpMutated(rBase)).append("\n");

        msg.append("Cascade SQL:\n")
                .append(trim(cascadeSQL)).append("\n")
                .append(dumpMutated(rCascade)).append("\n");

        if (exceptionInconsistent) msg.append("!! Exception inconsistency detected\n");
        if (valueInconsistent) msg.append("!! Result value mismatch\n");
        if (performanceInconsistent) msg.append("!! Cascade query is significantly slower\n");

        msg.append("\n------ SCHEMA + SAMPLE DATA ------\n")
                .append(globalState.getSchema()).append("\n")
                .append(dumpAllTables(globalState)).append("\n");

        System.out.println(dumpLastXSQLStatements(globalState, 10));
        globalState.getState().setStatements(new ArrayList<>());

        throw new AssertionError(msg.toString());
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

    private String generateNestedProjectionCascade(int depth) throws SQLException {
        if (lastBaseSQL == null || lastAllCol == null || lastBaseTable == null) {
            throw new SQLException("Base SQL not initialized");
        }

        StringBuilder innerSelect = new StringBuilder();
        for (int i = 0; i < lastAllCol.size(); i++) {
            if (i > 0) innerSelect.append(", ");
            SQLite3Column c = lastAllCol.get(i);
            innerSelect.append(lastBaseTable.getName()).append(".").append(c.getName())
                    .append(" AS ").append(c.getName());
        }

        int innerNoiseCols = 3 + Randomly.smallNumber() % 7;
        for (int i = 0; i < innerNoiseCols; i++) {
            innerSelect.append(", ").append(generateNoiseExpr()).append(" AS _inner_").append(i);
        }

        String currentSQL = "SELECT " + innerSelect + " FROM " + lastBaseTable.getName();
        List<SQLite3Expression> remainingExprs = new ArrayList<>(whereAndList);

        for (int level = 0; level < depth; level++) {
            List<String> noiseExprs = new ArrayList<>();
            int noiseCols = 3 + Randomly.smallNumber() % 7;
            for (int i = 0; i < noiseCols; i++) noiseExprs.add(generateNoiseExpr());

            List<SQLite3Expression> chosenExprs = new ArrayList<>();
            int pickCount = Math.min(1 + Randomly.smallNumber() % 2, remainingExprs.size());
            for (int i = 0; i < pickCount; i++) {
                SQLite3Expression e = Randomly.fromList(remainingExprs);
                remainingExprs.remove(e);
                chosenExprs.add(e);
            }

            StringBuilder selectList = new StringBuilder();
            for (int i = 0; i < lastAllCol.size(); i++) {
                if (i > 0) selectList.append(", ");
                selectList.append(lastAllCol.get(i).getName());
            }
            for (int i = 0; i < noiseExprs.size(); i++) {
                selectList.append(", ").append(noiseExprs.get(i))
                        .append(" AS _s").append(level).append("_").append(i);
            }

            String whereStr = chosenExprs.isEmpty() ? "" : " WHERE " + buildAndChain(chosenExprs);
            currentSQL = "SELECT " + selectList + " FROM (" + currentSQL + ")" + whereStr;
        }

        if (!remainingExprs.isEmpty()) {
            currentSQL = "SELECT " + lastBaseCol + " FROM (" + currentSQL + ") WHERE "
                    + buildAndChain(remainingExprs);
        } else {
            currentSQL = "SELECT " + lastBaseCol + " FROM (" + currentSQL + ")";
        }

        return currentSQL;
    }

    private String buildAndChain(List<SQLite3Expression> exprList) {
        if (exprList.isEmpty()) return "1=1";
        StringBuilder sb = new StringBuilder(replaceTableCol(exprList.get(0)));
        for (int i = 1; i < exprList.size(); i++) {
            sb.append(" AND ").append(replaceTableCol(exprList.get(i)));
        }
        return sb.toString();
    }

    private String replaceTableCol(SQLite3Expression expr) {
        String exprStr = SQLite3Visitor.asString(expr);
        for (SQLite3Column c : lastAllCol) {
            exprStr = exprStr.replace(lastBaseTable.getName() + "." + c.getName(), c.getName());
        }
        return exprStr;
    }

    private String generateNoiseExpr() {
        SQLite3ExpressionGenerator gen =
                new SQLite3ExpressionGenerator(globalState)
                        .setColumns(Collections.emptyList())
                        .deterministicOnly();

        int choice = Randomly.smallNumber() % 8;
        SQLite3Expression expr;

        switch (choice) {
            case 0:
                expr = SQLite3ExpressionGenerator.getRandomLiteralValue(globalState);
                break;
            case 1:
                expr = new SQLite3Expression.Cast(
                        new SQLite3Expression.TypeLiteral(
                                SQLite3Expression.TypeLiteral.Type.values()[
                                        Randomly.smallNumber() % SQLite3Expression.TypeLiteral.Type.values().length
                                        ]),
                        SQLite3ExpressionGenerator.getRandomLiteralValue(globalState));
                break;
            case 2:
                expr = new SQLite3Expression.Function(
                        "TYPEOF",
                        new SQLite3Expression[]{SQLite3ExpressionGenerator.getRandomLiteralValue(globalState)});
                break;
            case 3:
                expr = gen.getRandomExpression(2);
                break;
            case 4:
                expr = gen.getRandomExpression(3);
                break;
            case 5:
                expr = new SQLite3Expression.CollateOperation(
                        SQLite3ExpressionGenerator.getRandomLiteralValue(globalState),
                        SQLite3Column.SQLite3CollateSequence.random());
                break;
            default:
                expr = new SQLite3Expression.Cast(
                        new SQLite3Expression.TypeLiteral(SQLite3Expression.TypeLiteral.Type.INTEGER),
                        new SQLite3Expression.Function(
                                "ABS",
                                new SQLite3Expression[]{SQLite3ExpressionGenerator.getRandomLiteralValue(globalState)}));
                break;
        }
        return SQLite3Visitor.asString(expr);
    }

    private String generateBaseSelect(int nums) throws SQLException {
        List<SQLite3Table> tables = globalState.getSchema().getDatabaseTables();
        if (tables.isEmpty()) throw new SQLException("No tables available");

        lastBaseTable = Randomly.fromList(tables);
        lastAllCol = lastBaseTable.getColumns();
        if (lastAllCol.isEmpty()) throw new SQLException("Table has no columns");

        SQLite3Column c = Randomly.fromList(lastAllCol);
        lastBaseCol = c.getName();

        SQLite3ExpressionGenerator gen =
                new SQLite3ExpressionGenerator(globalState).setColumns(lastAllCol);

        whereExpr = genNestedExprWithChecks(nums, gen);

        return "SELECT " + lastBaseCol +
                " FROM " + lastBaseTable.getName() +
                " WHERE " + SQLite3Visitor.asString(whereExpr);
    }

    private SQLite3Expression genNestedExprWithChecks(int depth,
                                                      SQLite3ExpressionGenerator gen) throws SQLException {
        for (int i = 0; i < MAX_TRIES; i++) {
            whereAndList.clear();
            List<SQLite3Expression> exprList = new ArrayList<>();

            try {
                int numExpr = 2 + Randomly.smallNumber() % 2;
                for (int j = 0; j < numExpr; j++) {
                    SQLite3Expression e = null;
                    try {
                        e = genNestedExpr(depth, gen);
                    } catch (Throwable t) {
                        System.err.println("Warning: failed to generate sub-expression: " + t.getMessage());
                    }
                    if (e != null) {
                        exprList.add(e);
                        whereAndList.add(e);
                    }
                }

                if (exprList.isEmpty()) continue;

                SQLite3Expression combined = exprList.get(0);
                for (int j = 1; j < exprList.size(); j++) {
                    combined = new Sqlite3BinaryOperation(combined, exprList.get(j), BinaryOperator.AND);
                }
                return combined;

            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        throw new SQLException("Failed to generate expression after " + MAX_TRIES + " tries");
    }

    private SQLite3Expression genNestedExpr(int depth,
                                            SQLite3ExpressionGenerator gen) {
        if (depth <= 0) return gen.generateExpression();

        SQLite3Expression a = genNestedExpr(depth - 1, gen);
        SQLite3Expression b = genNestedExpr(depth - 1, gen);

        BinaryOperator op = (Randomly.smallNumber() % 5 != 0)
                ? BinaryOperator.AND
                : BinaryOperator.OR;

        return new Sqlite3BinaryOperation(a, b, op);
    }
}