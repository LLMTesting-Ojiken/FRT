package sqlancer.sqlite3;

import java.util.Map;
import java.util.HashMap;
import sqlancer.sqlite3.ast.*;
import sqlancer.sqlite3.ast.SQLite3Expression.*;

public class SQLite3ToStringVisitorWithPositions extends SQLite3ToStringVisitor {

    // AST 节点 → SQL 区间映射
    public final Map<SQLite3Expression, int[]> nodePositions = new HashMap<>();

    // 核心方法：记录节点在 sb 中 start/end
    private void recordPos(SQLite3Expression node, Runnable visitChild) {
        int start = sb.length();
        visitChild.run();
        int end = sb.length();
        nodePositions.put(node, new int[]{start, end});
    }

    /* ----------------- 重写所有 visit 方法，包裹 recordPos ----------------- */

    @Override
    public void visit(BetweenOperation op) {
        recordPos(op, () -> super.visit(op));
    }

    @Override
    public void visit(SQLite3ColumnName c) {
        recordPos(c, () -> super.visit(c));
    }

    @Override
    public void visit(Function f) {
        recordPos(f, () -> super.visit(f));
    }

    @Override
    public void visit(SQLite3Select s, boolean inner) {
        recordPos(s, () -> super.visit(s, inner));
    }

    @Override
    public void visit(SQLite3Constant c) {
        recordPos(c, () -> super.visit(c));
    }

    @Override
    public void visit(Join join) {
        recordPos(join, () -> super.visit(join));
    }

    @Override
    public void visit(SQLite3OrderingTerm term) {
        recordPos(term, () -> super.visit(term));
    }

    @Override
    public void visit(CollateOperation op) {
        recordPos(op, () -> super.visit(op));
    }

    @Override
    public void visit(Cast cast) {
        recordPos(cast, () -> super.visit(cast));
    }

    @Override
    public void visit(InOperation op) {
        recordPos(op, () -> super.visit(op));
    }

    @Override
    public void visit(Subquery query) {
        recordPos(query, () -> super.visit(query));
    }

    @Override
    public void visit(SQLite3Exist exist) {
        recordPos(exist, () -> super.visit(exist));
    }

    @Override
    public void visit(SQLite3Aggregate aggr) {
        recordPos(aggr, () -> super.visit(aggr));
    }

    @Override
    public void visit(SQLite3Function func) {
        recordPos(func, () -> super.visit(func));
    }

    @Override
    public void visit(SQLite3Distinct distinct) {
        recordPos(distinct, () -> super.visit(distinct));
    }

    @Override
    public void visit(SQLite3Case.SQLite3CaseWithoutBaseExpression casExpr) {
        recordPos(casExpr, () -> super.visit(casExpr));
    }

    @Override
    public void visit(SQLite3Case.SQLite3CaseWithBaseExpression casExpr) {
        recordPos(casExpr, () -> super.visit(casExpr));
    }

    @Override
    public void visit(SQLite3WindowFunction func) {
        recordPos(func, () -> super.visit(func));
    }

    @Override
    public void visit(MatchOperation match) {
        recordPos(match, () -> super.visit(match));
    }

    @Override
    public void visit(SQLite3RowValueExpression rw) {
        recordPos(rw, () -> super.visit(rw));
    }

    @Override
    public void visit(SQLite3Text func) {
        recordPos(func, () -> super.visit(func));
    }

    @Override
    public void visit(SQLite3WindowFunctionExpression windowFunction) {
        recordPos(windowFunction, () -> super.visit(windowFunction));
    }

    @Override
    public void visit(SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm term) {
        recordPos(term, () -> super.visit(term));
    }

    @Override
    public void visit(SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecBetween between) {
        recordPos(between, () -> super.visit(between));
    }

    @Override
    public void visit(SQLite3TableReference tableReference) {
        recordPos(tableReference, () -> super.visit(tableReference));
    }

    @Override
    public void visit(SQLite3SetClause set) {
        recordPos(set, () -> super.visit(set));
    }

    @Override
    public void visit(SQLite3Alias alias) {
        recordPos(alias, () -> super.visit(alias));
    }

    @Override
    public void visit(SQLite3WithClause withClause) {
        recordPos(withClause, () -> super.visit(withClause));
    }

    @Override
    public void visit(SQLite3TableAndColumnRef tableAndColumnRef) {
        recordPos(tableAndColumnRef, () -> super.visit(tableAndColumnRef));
    }

    @Override
    public void visit(SQLite3Values values) {
        recordPos(values, () -> super.visit(values));
    }

    @Override
    public void visit(SQLite3ExpressionBag expr) {
        recordPos(expr, () -> super.visit(expr));
    }

    @Override
    public void visit(SQLite3Typeof expr) {
        recordPos(expr, () -> super.visit(expr));
    }

    @Override
    public void visit(SQLite3ResultMap tableSummary) {
        recordPos(tableSummary, () -> super.visit(tableSummary));
    }

    @Override
    public void visitSpecific(SQLite3Expression expr) {
        if (expr != null) {
            recordPos(expr, () -> super.visitSpecific(expr));
        }
    }

//    private void visit(SQLite3Expression... expressions) {
//        visit(Arrays.asList(expressions));
//    }

    /* ----------------- 根据 token 定位 AST 节点 ----------------- */
    public SQLite3Expression findNodeByToken(String sql, String token) {
        int pos = sql.indexOf(token);
        if (pos == -1) return null;
        SQLite3Expression candidate = null;
        int smallestRange = Integer.MAX_VALUE;
        for (Map.Entry<SQLite3Expression,int[]> entry : nodePositions.entrySet()) {
            int[] range = entry.getValue();
            if (pos >= range[0] && pos < range[1]) {
                int len = range[1] - range[0];
                if (len < smallestRange) {
                    smallestRange = len;
                    candidate = entry.getKey();
                }
            }
        }
        return candidate;
    }
}