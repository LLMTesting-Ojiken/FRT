package sqlancer.sqlite3.oracle.FRT.Other;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.ast.*;
import sqlancer.sqlite3.ast.SQLite3Select.SelectType;
import sqlancer.sqlite3.gen.SQLite3Common;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.schema.SQLite3Schema;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Tables;

public final class SQLite3RandomOneColQuerySynthesizer {

    private SQLite3RandomOneColQuerySynthesizer() {
    }

    /**
     * 封装生成的 SELECT，外部可访问列、表、WHERE
     */
    public static class GeneratedQuery {
        private final SQLite3Select select;
        private final List<SQLite3Expression> columns;
        private final List<SQLite3Table> tables;
        private final SQLite3Expression whereClause;

        public GeneratedQuery(SQLite3Select select) {
            this.select = select;
            this.columns = select.getFetchColumns();
            this.tables = getTablesFromRefs(select.getFromList());
            this.whereClause = select.getWhereClause();
        }

        public SQLite3Select getSelect() {
            return select;
        }

        public List<SQLite3Expression> getColumns() {
            return columns;
        }

        public List<SQLite3Table> getTables() {
            return tables;
        }

        public SQLite3Expression getWhereClause() {
            return whereClause;
        }

        public boolean hasWhere() {
            return whereClause != null;
        }
    }

    /**
     * 从 FROM 表达式列表提取表对象
     */
    private static List<SQLite3Table> getTablesFromRefs(List<SQLite3Expression> tableRefs) {
        List<SQLite3Table> tables = new ArrayList<>();
        for (SQLite3Expression expr : tableRefs) {
            if (expr instanceof SQLite3Expression.SQLite3TableReference) {
                tables.add(((SQLite3Expression.SQLite3TableReference) expr).getTable());
            }
        }
        return tables;
    }

    /**
     * 生成随机 SELECT 并封装为 GeneratedQuery
     */
    public static GeneratedQuery generate(SQLite3GlobalState globalState, int size) {
        Randomly r = globalState.getRandomly();
        SQLite3Schema s = globalState.getSchema();
        SQLite3Tables targetTables = s.getRandomTableNonEmptyTables();

        List<SQLite3Expression> expressions = new ArrayList<>();
        SQLite3ExpressionGenerator gen = new SQLite3ExpressionGenerator(globalState)
                .setColumns(targetTables.getColumns());
        SQLite3ExpressionGenerator whereClauseGen = new SQLite3ExpressionGenerator(globalState)
                .setColumns(targetTables.getColumns());
        SQLite3ExpressionGenerator aggregateGen = new SQLite3ExpressionGenerator(globalState)
                .setColumns(targetTables.getColumns())
                .allowAggregateFunctions();

        // SELECT
        SQLite3Select select = new SQLite3Select();
        select.setSelectType(Randomly.fromOptions(SelectType.values()));

        for (int i = 0; i < size; i++) {
            if (Randomly.getBooleanWithRatherLowProbability()) {
                // 保留窗口函数逻辑
                SQLite3Expression baseWindowFunction;
                boolean normalAggregateFunction = Randomly.getBoolean();
                if (!normalAggregateFunction) {
                    baseWindowFunction = SQLite3WindowFunction.getRandom(targetTables.getColumns(), globalState);
                } else {
                    baseWindowFunction = gen.getAggregateFunction(true);
                    assert baseWindowFunction != null;
                }
                SQLite3WindowFunctionExpression windowFunction = new SQLite3WindowFunctionExpression(baseWindowFunction);
                if (Randomly.getBooleanWithRatherLowProbability() && normalAggregateFunction) {
                    windowFunction.setFilterClause(gen.generateExpression());
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    windowFunction.setOrderBy(gen.generateOrderBys());
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    windowFunction.setPartitionBy(gen.getRandomExpressions(Randomly.smallNumber()));
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    windowFunction.setFrameSpecKind(SQLite3WindowFunctionExpression.SQLite3FrameSpecKind.getRandom());
                    SQLite3Expression windowFunctionTerm;
                    if (Randomly.getBoolean()) {
                        windowFunctionTerm = new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(
                                Randomly.fromOptions(
                                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.UNBOUNDED_PRECEDING,
                                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.CURRENT_ROW));
                    } else if (Randomly.getBoolean()) {
                        windowFunctionTerm = new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(gen.generateExpression(),
                                SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.EXPR_PRECEDING);
                    } else {
                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm left = getTerm(true, gen);
                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm right = getTerm(false, gen);
                        windowFunctionTerm = new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecBetween(left, right);
                    }
                    windowFunction.setFrameSpec(windowFunctionTerm);
                    if (Randomly.getBoolean()) {
                        windowFunction.setExclude(SQLite3WindowFunctionExpression.SQLite3FrameSpecExclude.getRandom());
                    }
                }
                expressions.add(windowFunction);
            } else {
                expressions.add(aggregateGen.generateExpression());
            }
        }
        select.setFetchColumns(expressions);

        List<SQLite3Table> tables = targetTables.getTables();
        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setJoinClauses(gen.getRandomJoinClauses(tables));
        }
        select.setFromList(SQLite3Common.getTableRefs(tables, s));

        // WHERE
        if (Randomly.getBoolean()) {
            select.setWhereClause(whereClauseGen.generateExpression());
        }

        // GROUP BY / HAVING / ORDER BY / LIMIT / OFFSET 保留原逻辑
        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setGroupByClause(gen.getRandomExpressions(Randomly.smallNumber() + 1));
            if (Randomly.getBoolean()) {
                select.setHavingClause(aggregateGen.generateExpression());
            }
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setOrderByClauses(gen.generateOrderBys());
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setLimitClause(SQLite3Constant.createIntConstant(r.getInteger()));
            if (Randomly.getBoolean()) {
                select.setOffsetClause(SQLite3Constant.createIntConstant(r.getInteger()));
            }
        }

        return new GeneratedQuery(select);
    }

    private static SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm getTerm(boolean isLeftTerm, SQLite3ExpressionGenerator gen) {
        if (Randomly.getBoolean()) {
            SQLite3Expression expr = gen.generateExpression();
            SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind kind = Randomly.fromOptions(
                    SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.EXPR_FOLLOWING,
                    SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.EXPR_PRECEDING);
            return new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(expr, kind);
        } else if (Randomly.getBoolean()) {
            return new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.CURRENT_ROW);
        } else {
            if (isLeftTerm) {
                return new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(
                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.UNBOUNDED_PRECEDING);
            } else {
                return new SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm(
                        SQLite3WindowFunctionExpression.SQLite3WindowFunctionFrameSpecTerm.SQLite3WindowFunctionFrameSpecTermKind.UNBOUNDED_FOLLOWING);
            }
        }
    }

}