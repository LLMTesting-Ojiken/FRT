package sqlancer.sqlite3.oracle.srt;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.Query;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.gen.dml.SQLite3DeleteGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3InsertGenerator;
import sqlancer.sqlite3.gen.dml.SQLite3UpdateGenerator;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.schema.SQLite3Schema;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class SQLite3TransactionUIDOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;
    private static final long TIME_UNKNOWN_NS = -1L;

    public SQLite3TransactionUIDOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    // =========================
    // ====== ORACLE CORE ======
    // =========================

    @Override
    public void check() throws Exception {

        // 0. 生成随机 SELECT
        String baseSQL = SQLite3Visitor.asString(
                SQLite3RandomQuerySynthesizer.generate(
                        globalState, Randomly.smallNumber() + 1
                )
        );

        String[] skipFunctions = { "CHANGES()", "LAST_INSERT_ROWID()" /* 后续可直接在这里加其他函数 */ };
        for (String func : skipFunctions) {
            if (baseSQL != null && baseSQL.toUpperCase().contains(func.toUpperCase())) {
                // 记录被跳过的 SQL，可选
                // globalState.getState().addIgnoredSQL(baseSQL);
                return; // 直接跳过
            }
        }

        // 1. 执行前结果
        MutatedResult beforeResult = exec(baseSQL);

        // 2. 数据库快照（事务前）
        DatabaseSnapshot beforeSnapshot = snapshotDatabase();

        // 3. 显式事务 + savepoint（SQLancer 风格）
        execStmt("BEGIN IMMEDIATE;");


        try {
            Random rand = new Random();
            int choice = rand.nextInt(3);
            switch (choice) {
                case 0:
                    execStmt(SQLite3DeleteGenerator.deleteContent(globalState).getQueryString());
                    break;
                case 1:
                    execStmt(SQLite3UpdateGenerator.updateRow(globalState).getQueryString());
                    break;
                case 2:
                    execStmt(SQLite3InsertGenerator.insertRow(globalState).getQueryString());
                    break;
            }
        } catch (Throwable es) {
//            return;
            // 如果第一次失败，会循环再次尝试
        } finally {
            try {
                execStmt("ROLLBACK;"); // 无论成功或失败都回滚事务
            } catch (Throwable e) {
                throw new IgnoreMeException();
            }
        }
        // 6. 回滚后结果
        MutatedResult afterResult = exec(baseSQL);

        // 7. 数据库快照（回滚后）
        DatabaseSnapshot afterSnapshot = snapshotDatabase();

        // =========================
        // ====== CONSISTENCY ======
        // =========================

        boolean exceptionInconsistent =
                !sameException(beforeResult.ex, afterResult.ex);

        boolean valueInconsistent =
                !Objects.equals(beforeResult.value, afterResult.value);

        boolean stateInconsistent =
                !beforeSnapshot.equals(afterSnapshot);

        if (!exceptionInconsistent && !valueInconsistent && !stateInconsistent) {
            return;
        }

        // =========================
        // ====== REPORT ===========
        // =========================



        StringBuilder msg = new StringBuilder();
        msg.append("\n===== TRANSACTION UID ORACLE FAILURE =====\n\n");

        msg.append("Base SQL:\n")
                .append(trim(baseSQL)).append("\n")
                .append(dumpResult("Before", beforeResult)).append("\n");

        msg.append("After SQL:\n")
                .append(trim(baseSQL)).append("\n")
                .append(dumpResult("After", afterResult)).append("\n");

// 详细显示异常不一致
        if (exceptionInconsistent) {
            msg.append("!! Exception inconsistency detected\n");
            msg.append("   Before exception: ")
                    .append(beforeResult.ex == null ? "<null>" : beforeResult.ex.getClass().getName() + ": " + beforeResult.ex.getMessage())
                    .append("\n");
            msg.append("   After exception:  ")
                    .append(afterResult.ex == null ? "<null>" : afterResult.ex.getClass().getName() + ": " + afterResult.ex.getMessage())
                    .append("\n");
        }

// 详细显示值不一致
        if (valueInconsistent) {
            msg.append("!! Result value mismatch\n");
            msg.append("   Before value: ").append(beforeResult.value).append("\n");
            msg.append("   After value:  ").append(afterResult.value).append("\n");
        }

// 详细显示数据库状态不一致
        if (stateInconsistent) {
            msg.append("!! Database state mismatch after rollback\n");
            msg.append("   Before snapshot:\n")
                    .append(beforeSnapshot.data).append("\n");
            msg.append("   After snapshot:\n")
                    .append(afterSnapshot.data).append("\n");
        }

// 打印 schema 快照
        msg.append("\n------ SCHEMA (BEFORE) ------\n")
                .append(beforeSnapshot.schema).append("\n")
                .append(beforeSnapshot.data).append("\n");


        msg.append("\n------ SCHEMA (AFTER) ------\n")
                .append(afterSnapshot.schema).append("\n")
                .append(beforeSnapshot.data).append("\n");


// 打印最近 SQL
        msg.append(dumpLastXSQLStatements(10));

// 清空 SQL 记录
        globalState.getState().setStatements(new ArrayList<>());

// 抛出带详细信息的 AssertionError
        throw new AssertionError(msg.toString());
    }

    // =========================
    // ====== SNAPSHOT =========
    // =========================

    private DatabaseSnapshot snapshotDatabase() {
        return new DatabaseSnapshot(
                globalState.getSchema().toString(),
                dumpAllTables()
        );
    }

    private static class DatabaseSnapshot {
        final String schema;
        final String data;

        DatabaseSnapshot(String schema, String data) {
            this.schema = schema;
            this.data = data;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DatabaseSnapshot)) {
                return false;
            }
            DatabaseSnapshot other = (DatabaseSnapshot) o;
            return Objects.equals(schema, other.schema)
                    && Objects.equals(data, other.data);
        }
    }

    // =========================
    // ====== EXECUTION ========
    // =========================

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

    private void execStmt(String sql) {
        try {
            globalState.executeStatement(new SQLQueryAdapter(sql, false));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private String executeAndGetSingleValue(String sql) {
        try (SQLancerResultSet rs = globalState.executeStatementAndGet(
                new SQLQueryAdapter(sql, false))) {
            if (rs == null || !rs.next()) {
                return null;
            }
            return rs.getString(1);
        } catch (Throwable e) {
            throw new IgnoreMeException();
        }
    }

    // =========================
    // ====== UTILITIES ========
    // =========================

    private boolean sameException(Throwable a, Throwable b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.getClass().equals(b.getClass());
    }

    private Throwable unwrap(Throwable t) {
        if (t == null) {
            return null;
        }
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private String dumpResult(String tag, MutatedResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(tag).append(" ex: ")
                .append(r.ex == null ? "<null>" : r.ex.getClass().getName()).append("\n");
        sb.append("  ").append(tag).append(" result: ")
                .append(r.value).append("\n");
        sb.append("  ").append(tag).append(" time(ms): ")
                .append(r.timeNs / 1_000_000.0).append("\n");
        return sb.toString();
    }

    private String dumpLastXSQLStatements(int X) {
        StringBuilder sb = new StringBuilder();
        List<Query<?>> stmts = globalState.getState().getStatements();
        if (stmts == null || stmts.isEmpty()) {
            sb.append("<no statements>\n");
            return sb.toString();
        }
        int start = Math.max(0, stmts.size() - X);
        sb.append("====== LAST ").append(X).append(" SQL STATEMENTS ======\n");
        for (int i = start; i < stmts.size(); i++) {
            sb.append(i + 1).append(": ")
                    .append(stmts.get(i).getQueryString()).append(";\n");
        }
        return sb.toString();
    }

    private String dumpAllTables() {
        StringBuilder sb = new StringBuilder();
        SQLite3Schema schema = globalState.getSchema();
        for (SQLite3Schema.SQLite3Table table : schema.getDatabaseTables()) {
            if (table.isSystemTable()) {
                continue;
            }
            sb.append("Table: ").append(table.getName()).append("\n");
            try (SQLancerResultSet rs = globalState.executeStatementAndGet(
                    new SQLQueryAdapter("SELECT * FROM " + table.getName(), false))) {

                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();

                sb.append("Columns: ");
                for (int i = 1; i <= cols; i++) {
                    sb.append(meta.getColumnName(i));
                    if (i < cols) {
                        sb.append(", ");
                    }
                }
                sb.append("\n");

                while (rs.next()) {
                    sb.append("  Row: ");
                    for (int i = 1; i <= cols; i++) {
                        String v = rs.getString(i);
                        sb.append(v == null ? "NULL" : v);
                        if (i < cols) {
                            sb.append(" | ");
                        }
                    }
                    sb.append("\n");
                }
            } catch (Throwable e) {
                sb.append("  <error reading table>\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String trim(String sql) {
        if (sql == null) {
            return "";
        }
        sql = sql.trim();
        if (sql.endsWith(";")) {
            return sql.substring(0, sql.length() - 1);
        }
        return sql;
    }

    // =========================
    // ====== DATA CLASS =======
    // =========================

    private static class MutatedResult {
        Throwable ex;
        String value;
        long timeNs = TIME_UNKNOWN_NS;
    }
}