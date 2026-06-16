package sqlancer.sqlite3.oracle.srt;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.Query;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.schema.SQLite3Schema;

import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.util.*;

public class SQLite3ChangeOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private final Random random = new Random();
    // 设置输出最近报错SQL的数量，X可自行修改
    private final int lastXStatements = 10;

    public SQLite3ChangeOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {

        SQLite3Expression expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);
        String originalSql = SQLite3Visitor.asString(expr) + ";";

        Throwable originalCause = null;
        String originalResult = null;
        boolean originalIsNull = false;

        try {
            originalResult = executeAndGetSingleValue(originalSql);
            originalIsNull = (originalResult == null);
        } catch (Throwable t) {
            originalCause = unwrap(t);
        }

        String[] mutatedSqls = generateAllMutatedSQL(expr, originalCause);

        List<String> mutatedResults = new ArrayList<>();

        for (String mutatedSql : mutatedSqls) {

            String mutatedResult = null;
            Throwable mutatedCause = null;

            try {
                mutatedResult = executeAndGetSingleValue(mutatedSql);
            } catch (Throwable t) {
                mutatedCause = unwrap(t);
            }

            if (originalCause != null) {
                if (mutatedCause != null) continue;

                System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
                globalState.getState().setStatements(new ArrayList<>());
                throw new AssertionError(buildReport(originalSql, mutatedSql, originalCause, null));
            }

            if (mutatedCause != null) {
                System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
                globalState.getState().setStatements(new ArrayList<>());
                throw new AssertionError(buildReport(originalSql, mutatedSql, null, mutatedCause));
            }

            mutatedResults.add(mutatedResult);

            if (!originalIsNull) {
                boolean mismatch = false;

                if (originalResult != null && mutatedResult != null) {
                    try {
                        double origNum = Double.parseDouble(originalResult);
                        double mutNum = Double.parseDouble(mutatedResult);
                        if (Double.compare(origNum, mutNum) != 0)
                            mismatch = true;
                    } catch (NumberFormatException e) {
                        if (!originalResult.equals(mutatedResult))
                            mismatch = true;
                    }
                } else if (originalResult != null || mutatedResult != null) {
                    mismatch = true;
                }

                if (mismatch) {
                    System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
                    globalState.getState().setStatements(new ArrayList<>());

                    throw new AssertionError(
                            "[VALUE MISMATCH]\nOriginal result: " + originalResult +
                                    "\nMutated result: " + mutatedResult +
                                    "\n" + buildReport(originalSql, mutatedSql, null, null)
                    );
                }
            }
        }

        if (originalIsNull) {
            Set<String> nonNullResults = new HashSet<>();
            for (String res : mutatedResults)
                if (res != null) nonNullResults.add(res);

            if (nonNullResults.size() > 1) {
                System.out.println(dumpLastXSQLStatements(globalState, lastXStatements));
                globalState.getState().setStatements(new ArrayList<>());

                throw new AssertionError(
                        "[NULL MISMATCH]\nOriginal result NULL, mutated results inconsistent: " +
                                mutatedResults + "\n" + buildReport(originalSql, String.join("\n", mutatedSqls), null, null)
                );
            }
        }
    }

    private String[] generateAllMutatedSQL(SQLite3Expression expr, Throwable originalCause) {

        String innerSql = SQLite3Visitor.asString(expr);

        String safeInnerSql = originalCause != null
                ? "(" + innerSql + ")"
                : "(SELECT (" + innerSql + ") AS val LIMIT 1)";

        ArrayList<String> mutated = new ArrayList<>();
        Map<String, String> replacementMap = new HashMap<>();

        int mutationCount = 19;

        for (int i = 0; i < mutationCount; i++) {
            String nestedSql = safeInnerSql;

            String replacement = replacementMap.computeIfAbsent(
                    nestedSql,
                    k -> generateReplacementWithResult()
            );

            int choice = random.nextInt(19);

            switch (choice) {
                case 0:
                    nestedSql = "COALESCE(" + nestedSql + ", " + replacement + ")";
                    break;
                case 1:
                    nestedSql = "IFNULL(" + nestedSql + ", " + replacement + ")";
                    break;
                case 2:
                    nestedSql = "COALESCE(NULLIF(" + nestedSql + ", " + replacement + "), " + replacement + ")";
                    break;
                case 3:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NULL THEN " + replacement + " ELSE " + nestedSql + " END";
                    break;
                case 4:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NOT NULL THEN " + nestedSql + " ELSE " + replacement + " END";
                    break;
                case 5:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NULL THEN " + replacement + " ELSE " + nestedSql + " END";
                    break;
                case 6:
                    nestedSql = "IFNULL(NULLIF(" + nestedSql + ", " + replacement + "), " + replacement + ")";
                    break;
                case 7:
                    nestedSql = "COALESCE(" + nestedSql + ", " + replacement + ", " + replacement + ")";
                    break;
                case 8:
                    nestedSql = "COALESCE(NULLIF(" + nestedSql + ", " + replacement + "), " + replacement + ", " + replacement + ")";
                    break;
                case 9:
                    nestedSql = "COALESCE(IFNULL(" + nestedSql + ", " + replacement + "), " + replacement + ")";
                    break;
                case 10:
                    nestedSql = "IFNULL(COALESCE(" + nestedSql + ", " + replacement + "), " + replacement + ")";
                    break;
                case 11:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NULL THEN COALESCE(" + replacement + ", " + replacement + ") ELSE " + nestedSql + " END";
                    break;
                case 12:
                    nestedSql = "COALESCE(CASE WHEN " + nestedSql + " IS NULL THEN " + replacement + " ELSE " + nestedSql + " END, " + replacement + ")";
                    break;
                case 13:
                    nestedSql = "COALESCE(IFNULL(NULLIF(" + nestedSql + ", " + replacement + "), " + replacement + "), " + replacement + ")";
                    break;
                case 14:
                    nestedSql = "IFNULL(COALESCE(NULLIF(" + nestedSql + ", " + replacement + "), " + replacement + "), " + replacement + ")";
                    break;
                case 15:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NULL THEN IFNULL(" + replacement + ", " + replacement + ") ELSE " + nestedSql + " END";
                    break;
                case 16:
                    nestedSql = "CASE WHEN " + nestedSql + " IS NULL THEN COALESCE(" + replacement + ", " + replacement + ") ELSE " + nestedSql + " END";
                    break;
                case 17:
                    nestedSql = "COALESCE(CASE WHEN " + nestedSql + " IS NOT NULL THEN " + nestedSql + " ELSE " + replacement + " END, " + replacement + ")";
                    break;
                case 18:
                    nestedSql = "IFNULL(CASE WHEN " + nestedSql + " IS NULL THEN " + replacement + " ELSE " + nestedSql + " END, " + replacement + ")";
                    break;
            }

            mutated.add("SELECT " + nestedSql + ";");
        }

        return mutated.toArray(new String[0]);
    }

    private String generateReplacementWithResult() {

        if (random.nextBoolean()) {
            SQLite3Expression expr = SQLite3ExpressionGenerator.getRandomLiteralValue(globalState);
            String constant = SQLite3Visitor.asString(expr);
            return constant != null ? constant : "NULL";
        } else {
            SQLite3Expression expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);
            String sqlExpr = SQLite3Visitor.asString(expr);
            String wrapped = "(SELECT (" + sqlExpr + ") AS val LIMIT 1)";
            try {
                String result = executeAndGetSingleValue(wrapped);
                return result != null ? result : "NULL";
            } catch (Throwable e) {
                return "NULL";
            }
        }
    }

    private String executeAndGetSingleValue(String sql) throws SQLException {
        try (sqlancer.common.query.SQLancerResultSet rs =
                     globalState.executeStatementAndGet(new SQLQueryAdapter(sql, false))) {

            if (rs == null || !rs.next()) return null;
            return rs.getString(1);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    private Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    private String buildReport(String originalSql, String complexSql,
                               Throwable orig, Throwable comp) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ORIGINAL SQL ==========\n").append(originalSql).append("\n\n");
        sb.append("====== ORIGINAL EXCEPTION ======\n").append(dumpException(orig)).append("\n\n");
        sb.append("========== MUTATED SQL ==========\n").append(complexSql).append("\n\n");
        sb.append("====== MUTATED EXCEPTION ==========\n").append(dumpException(comp)).append("\n\n");
        sb.append("====== DATABASE SCHEMA ==========\n").append(globalState.getSchema()).append("\n\n");
        sb.append("====== DATABASE CONTENT ==========\n").append(dumpAllTables(globalState)).append("\n");
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

    /**
     * 只输出最接近报错的X条SQL
     */
    private String dumpLastXSQLStatements(SQLite3GlobalState state, int X) {
        StringBuilder sb = new StringBuilder();
        List<Query<?>> statements = state.getState().getStatements();
        if (statements == null || statements.isEmpty()) {
            sb.append("<no statements>\n");
        } else {
            int size = statements.size();
            int start = Math.max(0, size - X);
            sb.append("====== LAST ").append(X).append(" SQL STATEMENTS ======\n");
            for (int i = start; i < size; i++) {
                sb.append(i + 1).append(": ").append(statements.get(i).getQueryString()).append(";\n");
            }
        }
        return sb.toString();
    }
}