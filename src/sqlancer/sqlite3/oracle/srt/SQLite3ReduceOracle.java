package sqlancer.sqlite3.oracle.srt;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.schema.SQLite3Schema;
import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.util.ArrayList;

/**
 * ============================================
 * SQLite3 Reduce Oracle（简化版）
 * ============================================
 * <p>
 * 本类承担以下最核心的职责：
 * <p>
 * 1. 随机生成 ORIGINAL SQL（表达式级）
 * 2. 执行 ORIGINAL SQL，捕获异常（如 JSON error、misuse、datatype mismatch 等）
 * 3. 由用户提供 COMPLEX SQL（你会替换掉 complexSql）
 * 4. 执行 COMPLEX SQL，捕获异常
 * 5. 比较两段 SQL 的异常是否一致
 * - 类名一致？
 * - message 文本一致？
 * - SQLite result code 一致？
 * 6. 不一致 → 抛出 AssertionError，并打印调试信息（schema、数据、两段 SQL）
 *
 */
public class SQLite3ReduceOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    public SQLite3ReduceOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {

        // ================================================================
        // 1. 生成一个随机表达式，并转成 ORIGINAL SQL
        // ================================================================
        SQLite3Expression expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);
        String originalSql = SQLite3Visitor.asString(expr) + ";";

        Throwable originalCause = null;
        Throwable complexCause = null;

        // ================================================================
        // 2. 执行 ORIGINAL SQL，尝试触发异常
        // ================================================================
        try {
            executeSilent(originalSql);

        } catch (Throwable t) {
            // unwrap: 提取最底层异常，避免包装层影响
            originalCause = unwrap(t);

            // ================================================================
            // 3. 用户自己填写 complex SQL 的逻辑
            // ================================================================
            String complexSql = "reduced sql"; // ⚠️ 你会替换这里

            // ================================================================
            // 4. 执行 COMPLEX SQL
            // ================================================================
            try {
                executeSilent(complexSql);

            } catch (Throwable t2) {
                complexCause = unwrap(t2);
            }

            // ================================================================
            // 5. 比较异常是否一致（类名 + message + result code）
            // ================================================================
            if (isMismatch(originalCause, complexCause)) {
                // String reducedSQL = "reduced sql"; // ⚠️ 你会替换这里
                // 输出前清空当前状态记录
                globalState.getState().setStatements(new ArrayList<>());

                throw new AssertionError(buildReport(
                        originalSql, complexSql,
                        originalCause, complexCause
                ));
            }
        }
    }

    /* ============================================================
       执行 SQL，不打印日志
       ============================================================ */
    private void executeSilent(String sql) throws Exception {
        globalState.executeStatement(new SQLQueryAdapter(sql, false));
    }

    /* ============================================================
       判断两个异常是否一致（核心 Oracle）
       ============================================================ */
    private boolean isMismatch(Throwable a, Throwable b) {

        // 一个空一个非空 → 不一致
        if (a == null ^ b == null) return true;
        if (a == null) return false;

        // 类名是否一样？
        if (!a.getClass().equals(b.getClass())) return true;

        // message 是否一致？
        if (!safeMessage(a).equals(safeMessage(b))) return true;

        // SQLite error code 是否一致？
        return getSQLiteCode(a) != getSQLiteCode(b);
    }

    /* 提取可安全打印的 message */
    private String safeMessage(Throwable t) {
        return t == null ? "<null>" : t.getMessage();
    }

    /* 提取 SQLite 错误代码（如 JSON 错误、datatype mismatch） */
    private int getSQLiteCode(Throwable t) {
        if (t instanceof SQLiteException) {
            return ((SQLiteException) t).getResultCode().code;
        }
        return -1;
    }

    /* 将异常往下走，取最底层 cause */
    private Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /* ============================================================
       构建调试报告（schema + 数据 + 2 段 SQL）
       ============================================================ */
    private String buildReport(String originalSql, String complexSql,
                               Throwable orig, Throwable comp) {

        StringBuilder sb = new StringBuilder();

        sb.append("\n========== ORIGINAL SQL ==========\n")
                .append(originalSql).append("\n\n");

        sb.append("====== ORIGINAL EXCEPTION ======\n")
                .append(dumpException(orig)).append("\n\n");

        sb.append("========== COMPLEX SQL ==========\n")
                .append(complexSql).append("\n\n");

        sb.append("====== COMPLEX EXCEPTION ======\n")
                .append(dumpException(comp)).append("\n\n");

        sb.append("====== DATABASE SCHEMA ======\n")
                .append(globalState.getSchema()).append("\n\n");

        sb.append("====== DATABASE CONTENT ======\n")
                .append(dumpAllTables(globalState)).append("\n");

        return sb.toString();
    }

    /* 详细异常信息（类名 + message + SQLiteCode） */
    private String dumpException(Throwable t) {
        if (t == null) return "<null>";

        return "Class: " + t.getClass().getName() + "\n" +
                "Message: " + t.getMessage() + "\n" +
                "SQLiteCode: " + getSQLiteCode(t);
    }

    /* ============================================================
       表内容 dump（最多 50 行）
       ============================================================ */
    private String dumpAllTables(SQLite3GlobalState state) {
        StringBuilder sb = new StringBuilder();
        SQLite3Schema schema = state.getSchema();
        for (SQLite3Schema.SQLite3Table table : schema.getDatabaseTables()) {
            if (table.isSystemTable()) continue;
            sb.append("Table: ").append(table.getName()).append("\n");
            try (sqlancer.common.query.SQLancerResultSet rs =
                         state.executeStatementAndGet(
                                 new SQLQueryAdapter("SELECT * FROM " + table.getName() + " LIMIT 50;", false))) {
                if (rs == null) {
                    sb.append("  <no result>\n\n");
                    continue;
                }
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
                        sb.append(val == null ? "NULL" : val);
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
}