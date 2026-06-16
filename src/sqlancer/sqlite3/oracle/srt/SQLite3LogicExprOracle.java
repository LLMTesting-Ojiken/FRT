package sqlancer.sqlite3.oracle.srt;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
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
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;
import sqlancer.sqlite3.SQLite3GlobalState;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 精简版 SQLite3LogicExprOracle：
 * - 生成 AND/OR 链式布尔表达式
 * - 在创建 (a op b) 时检查 (b op a) 是否结果一致
 * - 仅在异常时抛 AssertionError，正常不打印
 */
public class SQLite3LogicExprOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private static final int MAX_LEAF_IN_LIST = 3;
    private static final int MAX_TRIES = 50;

    public SQLite3LogicExprOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {
        generateNestedWhereSelectWithChecks(3);
//        String selectA = generateNestedWhereSelectWithChecks(3);
//        String selectB = generateNestedWhereSelectWithChecks(3);

        // 仅打印最终 SELECT 字符串，调试用

    }

    // ------------------ 生成完整 SELECT ------------------

    private String generateNestedWhereSelectWithChecks(int maxDepth) throws SQLException {
        List<SQLite3Table> tables = globalState.getSchema().getDatabaseTables();
        if (tables.isEmpty()) throw new SQLException("No tables available");

        SQLite3Table table = Randomly.fromList(tables);
        List<SQLite3Column> columns = table.getColumns();
        if (columns.isEmpty()) throw new SQLException("Table has no columns");

        int numCols = Math.max(1, Randomly.smallNumber() + 1);
        StringBuilder fetchCols = new StringBuilder();
        for (int i = 0; i < numCols; i++) {
            SQLite3Column c = Randomly.fromList(columns);
            fetchCols.append(c.getName());
            if (i < numCols - 1) fetchCols.append(", ");
        }

        SQLite3Expression whereExpr = generateNestedExpressionWithChecks(maxDepth);

        return "SELECT " + fetchCols + " FROM " + table.getName() +
                " WHERE " + SQLite3Visitor.asString(whereExpr);
    }

    // ------------------ 核心：嵌套表达式生成与检查 ------------------

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
            throw new AssertionError("Logical short-circuit inconsistency detected: " +
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

    // ------------------ 布尔叶子表达式 ------------------

    private SQLite3Expression generateBooleanLeaf() throws SQLException {
        int choice = (int) Randomly.getNotCachedInteger(0, 6);
        switch (choice) {
            case 0: return generateComparisonLeaf();
            case 1: return generateBetweenLeaf();
            case 2: return generateIsNullLeaf();
            case 3: return generateInListLeaf();
            case 4: return generateLikeLeaf();
            case 5: return generateExistsLeaf();
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
        } catch (Throwable ignored) {}
        return SQLite3ExpressionGenerator.getRandomLiteralValue(globalState);
    }

    // ------------------ SQL 执行辅助 ------------------

    private MutatedResult exec(String sql) {
        MutatedResult r = new MutatedResult();
        try {
            r.value = executeAndGetSingleValue(sql);
        } catch (Throwable e) {
            r.ex = unwrap(e);
        }
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

    private Throwable unwrap(Throwable t) {
        if (t == null) return null;
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private boolean equalsNullable(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.isEmpty() ? b.isEmpty() : a.equals(b);
    }

    private static class MutatedResult {
        Throwable ex;
        String value;
    }
}