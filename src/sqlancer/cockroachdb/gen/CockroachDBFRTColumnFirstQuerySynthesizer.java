//
//
//package sqlancer.cockroachdb.gen;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.stream.Collectors;
//
//import sqlancer.Randomly;
//import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
//import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
//import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
//import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTables;
//import sqlancer.cockroachdb.CockroachDBVisitor;
//import sqlancer.cockroachdb.ast.*;
//import sqlancer.cockroachdb.oracle.tlp.CockroachDBTLPBase;
//import sqlancer.common.query.SQLQueryAdapter;
//
//public final class CockroachDBFRTColumnFirstQuerySynthesizer {
//
//    private CockroachDBFRTColumnFirstQuerySynthesizer() {
//    }
//
//    public static SQLQueryAdapter generate(CockroachDBGlobalState globalState, int nrColumns) {
//        return new SQLQueryAdapter(
//                CockroachDBVisitor.asString(generateSelect(globalState, nrColumns))
//        );
//    }
//
//    public static CockroachDBSelect generateSelect(
//            CockroachDBGlobalState globalState,
//            int nrColumns) {
//
//        CockroachDBTables targetTables =
//                globalState.getSchema().getRandomTableNonEmptyTables();
//
//        CockroachDBExpressionGenerator gen =
//                new CockroachDBExpressionGenerator(globalState)
//                        .setColumns(targetTables.getColumns());
//
//        CockroachDBSelect select = new CockroachDBSelect();
//        select.setDistinct(Randomly.getBoolean());
//
//        // =========================
//        // 1. Column Pool
//        // =========================
//        List<CockroachDBExpression> columnPool = new ArrayList<>();
//
//        for (CockroachDBColumn c : targetTables.getColumns()) {
//            columnPool.add(new CockroachDBColumnReference(c));
//        }
//
//        List<CockroachDBExpression> fetchColumns = new ArrayList<>();
//        boolean hasColumn = false;
//
//        // =========================
//        // 2. SELECT（Column-first + 函数增强）
//        // =========================
//        for (int i = 0; i < nrColumns; i++) {
//
//            CockroachDBExpression expr;
//
//            boolean chooseColumn =
//                    (!hasColumn) ||
//                            Randomly.getBoolean() ||
//                            Randomly.getBoolean();
//
//            if (chooseColumn && !columnPool.isEmpty()) {
//
//                expr = columnPool.get(
//                        Randomly.smallNumber() % columnPool.size()
//                );
//                hasColumn = true;
//
//            } else {
//
//                expr = gen.generateExpression(
//                        CockroachDBDataType.getRandom().get()
//                );
//
//                // 🚨 过滤纯常量
//                String s = CockroachDBVisitor.asString(expr).toUpperCase();
//
//                if (!s.contains("T0.") && !s.contains("T1.") && !columnPool.isEmpty()) {
//                    expr = columnPool.get(
//                            Randomly.smallNumber() % columnPool.size()
//                    );
//                }
//            }
//
//            // ⭐⭐⭐ 关键增强：函数包裹（只加这一行）
////            expr = maybeWrapWithFunctions(expr, gen);
//
//            fetchColumns.add(expr);
//        }
//
//        // =========================
//        // 3. 至少一个列
//        // =========================
//        if (!hasColumn && !columnPool.isEmpty()) {
//            fetchColumns.set(0,
//                    columnPool.get(Randomly.smallNumber() % columnPool.size()));
//        }
//
//        select.setFetchColumns(fetchColumns);
//
//        // =========================
//        // 4. FROM
//        // =========================
//        List<CockroachDBExpression> tableList =
//                targetTables.getTables().stream()
//                        .map(CockroachDBTableReference::new)
//                        .collect(Collectors.toList());
//
//        if (Randomly.getBoolean()) {
//            select.setJoinList(
//                    CockroachDBTLPBase.getJoins(tableList, globalState)
//            );
//        }
//
//        select.setFromList(tableList);
//
//        // =========================
//        // 5. WHERE
//        // =========================
//        if (Randomly.getBoolean() && !columnPool.isEmpty()) {
//
//            CockroachDBExpression where =
//                    gen.generateExpression(CockroachDBDataType.BOOL.get());
//
//            String s = CockroachDBVisitor.asString(where).toUpperCase();
//
//            if (!s.contains("FALSE") && !s.contains("NULL") && !s.contains("IS NAN")) {
//                select.setWhereClause(where);
//            }
//        }
//
//        // =========================
//        // 6. GROUP BY
//        // =========================
//        if (Randomly.getBoolean() && !columnPool.isEmpty()) {
//
//            List<CockroachDBExpression> groupBy = new ArrayList<>();
//
//            int n = Randomly.smallNumber() + 1;
//
//            for (int i = 0; i < n; i++) {
//
//                CockroachDBExpression col =
//                        columnPool.get(Randomly.smallNumber() % columnPool.size());
//
//                String s = CockroachDBVisitor.asString(col).toUpperCase();
//
//                if (s.contains("INTERVAL")
//                        || s.contains("TIME ")
//                        || s.contains("TIMESTAMP")
//                        || s.contains("NULL")) {
//                    continue;
//                }
//
//                groupBy.add(col);
//            }
//
//            if (!groupBy.isEmpty()) {
//                select.setGroupByExpressions(groupBy);
//            }
//        }
//
//        // =========================
//        // 7. HAVING
//        // =========================
//        if (Randomly.getBoolean()) {
//
//            CockroachDBExpression having =
//                    gen.generateHavingClause();
//
//            String s = CockroachDBVisitor.asString(having).toUpperCase();
//
//            if (!s.contains("FALSE")
//                    && !s.contains("NULL")
//                    && !s.contains("BOOL_AND(FALSE)")
//                    && !s.contains("BOOL_AND(TRUE)")) {
//
//                select.setHavingClause(having);
//            }
//        }
//
//        // =========================
//        // 8. ORDER BY
//        // =========================
//        if (Randomly.getBoolean()) {
//            select.setOrderByClauses(gen.getOrderingTerms());
//        }
//
//        // =========================
//        // 9. LIMIT / OFFSET
//        // =========================
//        if (Randomly.getBoolean()) {
//            select.setLimitClause(
//                    gen.generateConstant(CockroachDBDataType.INT.get())
//            );
//        }
//
//        if (Randomly.getBoolean()) {
//            select.setOffsetClause(
//                    gen.generateConstant(CockroachDBDataType.INT.get())
//            );
//        }
//
//        return select;
//    }
//
//}

package sqlancer.cockroachdb.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTables;
import sqlancer.cockroachdb.CockroachDBVisitor;
import sqlancer.cockroachdb.ast.*;
import sqlancer.cockroachdb.oracle.tlp.CockroachDBTLPBase;
import sqlancer.common.query.SQLQueryAdapter;

public final class CockroachDBFRTColumnFirstQuerySynthesizer {

    private CockroachDBFRTColumnFirstQuerySynthesizer() {
    }

    public static SQLQueryAdapter generate(CockroachDBGlobalState globalState, int nrColumns) {
        return new SQLQueryAdapter(
                CockroachDBVisitor.asString(generateSelect(globalState, nrColumns))
        );
    }

    public static CockroachDBSelect generateSelect(
            CockroachDBGlobalState globalState,
            int nrColumns) {

        CockroachDBTables targetTables =
                globalState.getSchema().getRandomTableNonEmptyTables();

        CockroachDBExpressionGenerator gen =
                new CockroachDBExpressionGenerator(globalState)
                        .setColumns(targetTables.getColumns());

        CockroachDBSelect select = new CockroachDBSelect();
        select.setDistinct(Randomly.getBoolean());

        // =========================
        // 1. Column Pool
        // =========================
        List<CockroachDBExpression> columnPool = new ArrayList<>();

        for (CockroachDBColumn c : targetTables.getColumns()) {
            columnPool.add(new CockroachDBColumnReference(c));
        }

        List<CockroachDBExpression> fetchColumns = new ArrayList<>();
        boolean hasColumn = false;

        // =========================
        // 2. SELECT（ONLY place where functions are allowed）
        // =========================
        for (int i = 0; i < nrColumns; i++) {

            CockroachDBExpression expr;

            boolean chooseColumn =
                    (!hasColumn)
                            || Randomly.getBoolean()
                            || Randomly.getBoolean();

            if (chooseColumn && !columnPool.isEmpty()) {

                expr = columnPool.get(
                        Randomly.smallNumber() % columnPool.size()
                );
                hasColumn = true;

            } else {

                expr = gen.generateExpression(
                        CockroachDBDataType.getRandom().get()
                );

                // 过滤纯常量（尽量保证 column-first）
                String s = CockroachDBVisitor.asString(expr).toUpperCase();

                if (!s.contains("T0.") && !s.contains("T1.") && !columnPool.isEmpty()) {
                    expr = columnPool.get(
                            Randomly.smallNumber() % columnPool.size()
                    );
                }

                // 🔒 关键约束：禁止 SELECT 以外结构污染（防止 generator 泄漏函数）
                if (containsFunction(expr) && columnPool.size() > 0) {
                    expr = columnPool.get(
                            Randomly.smallNumber() % columnPool.size()
                    );
                }
            }

            // ⭐ SELECT-only function injection（唯一入口）
            if (Randomly.getBoolean() && Randomly.getBoolean()) {
                expr = wrapWithScalarFunction(expr, gen, targetTables);
            }

            fetchColumns.add(expr);
        }

        // =========================
        // 3. ensure at least one column
        // =========================
        if (!hasColumn && !columnPool.isEmpty()) {
            fetchColumns.set(0,
                    columnPool.get(Randomly.smallNumber() % columnPool.size()));
        }

        select.setFetchColumns(fetchColumns);

        // =========================
        // 4. FROM (NO FUNCTIONS ALLOWED)
        // =========================
        List<CockroachDBExpression> tableList =
                targetTables.getTables().stream()
                        .map(CockroachDBTableReference::new)
                        .collect(Collectors.toList());

        if (Randomly.getBoolean()) {
            select.setJoinList(
                    CockroachDBTLPBase.getJoins(tableList, globalState)
            );
        }

        select.setFromList(tableList);

        // =========================
        // 5. WHERE (STRICT: no function leakage)
        // =========================
        if (Randomly.getBoolean() && !columnPool.isEmpty()) {

            CockroachDBExpression where =
                    gen.generateExpression(CockroachDBDataType.BOOL.get());

            String s = CockroachDBVisitor.asString(where).toUpperCase();

            if (isSafePredicate(s)) {
                select.setWhereClause(where);
            }
        }

        // =========================
        // 6. GROUP BY (columns only)
        // =========================
        if (Randomly.getBoolean() && !columnPool.isEmpty()) {

            List<CockroachDBExpression> groupBy = new ArrayList<>();

            int n = Randomly.smallNumber() + 1;

            for (int i = 0; i < n; i++) {

                CockroachDBExpression col =
                        columnPool.get(Randomly.smallNumber() % columnPool.size());

                String s = CockroachDBVisitor.asString(col).toUpperCase();

                if (s.contains("INTERVAL")
                        || s.contains("TIME ")
                        || s.contains("TIMESTAMP")
                        || s.contains("NULL")
                        || containsFunction(col)) {
                    continue;
                }

                groupBy.add(col);
            }

            if (!groupBy.isEmpty()) {
                select.setGroupByExpressions(groupBy);
            }
        }

        // =========================
        // 7. HAVING (no function leakage)
        // =========================
        if (Randomly.getBoolean()) {

            CockroachDBExpression having =
                    gen.generateHavingClause();

            String s = CockroachDBVisitor.asString(having).toUpperCase();

            if (isSafePredicate(s)) {
                select.setHavingClause(having);
            }
        }

        // =========================
        // 8. ORDER BY (strict safety)
        // =========================
        if (Randomly.getBoolean()) {
            select.setOrderByClauses(gen.getOrderingTerms());
        }

        // =========================
        // 9. LIMIT / OFFSET
        // =========================
        if (Randomly.getBoolean()) {
            select.setLimitClause(
                    gen.generateConstant(CockroachDBDataType.INT.get())
            );
        }
//
//        if (Randomly.getBoolean()) {
//            select.setOffsetClause(
//                    gen.generateConstant(CockroachDBDataType.INT.get())
//            );
//        }

        return select;
    }

    // =========================
    // SELECT-only function wrapper
    // =========================
    private static CockroachDBExpression wrapWithScalarFunction(
            CockroachDBExpression expr,
            CockroachDBExpressionGenerator gen,
            CockroachDBTables tables) {

        try {
            CockroachDBExpression wrapped =
                    gen.generateExpression(
                            CockroachDBDataType.getRandom().get()
                    );

            return wrapped != null ? wrapped : expr;

        } catch (Throwable t) {
            return expr;
        }
    }

    // =========================
    // Safety checks
    // =========================
    private static boolean containsFunction(CockroachDBExpression expr) {
        String s = CockroachDBVisitor.asString(expr);
        return s.matches(".*[A-Z_]+\\s*\\(.*\\).*");
    }

    private static boolean isSafePredicate(String s) {
        return !s.contains("FALSE")
                && !s.contains("NULL")
                && !s.contains("IS NAN")
                && !containsFunctionString(s);
    }

    private static boolean containsFunctionString(String s) {
        return s.matches(".*[A-Z_]+\\s*\\(.*\\).*");
    }
}