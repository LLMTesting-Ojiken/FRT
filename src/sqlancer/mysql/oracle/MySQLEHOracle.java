package sqlancer.mysql.oracle;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLSchema;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.gen.MySQLRandomQuerySynthesizer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySQLEHOracle implements TestOracle<MySQLGlobalState> {

    private final MySQLGlobalState globalState;
    private final Random random = new Random();

    public MySQLEHOracle(MySQLGlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {

        String originalSql = MySQLVisitor.asString(MySQLRandomQuerySynthesizer.generate(globalState, 1)) + ';';

        Throwable originalCause = null;
        String complexSql = null;
        Throwable complexCause = null;
        String reducedSql = null;

        try {
            executeWithoutLogging(originalSql);
        } catch (Throwable t) {
            originalCause = unwrap(t);

            // 生成复杂 SQL
            complexSql = generateEnhancedComplexEquivalent(originalSql);

            try {
                executeWithoutLogging(complexSql);
            } catch (Throwable t2) {
                complexCause = unwrap(t2);
            }

            // 异常不一致时进行脱壳和约减
            if (isMismatch(originalCause, complexCause)) {
                reducedSql = tryUnwrapLayers(complexSql, globalState, complexCause);
                globalState.getState().setStatements(new ArrayList<>());
                throw new AssertionError(buildErrorMessage(originalSql, originalCause,
                        complexSql, complexCause, reducedSql));
            }
        }
    }

    /* ==================== 异常比较 ==================== */
    private boolean isMismatch(Throwable t1, Throwable t2) {
        if (t1 == null ^ t2 == null) return true;
        if (t1 == null) return false;
        return !t1.getClass().equals(t2.getClass())
                || !safeMessage(t1).equals(safeMessage(t2))
                || getMySQLErrorCode(t1) != getMySQLErrorCode(t2);
    }

    private String safeMessage(Throwable t) {
        return t == null ? "<null>" : t.getMessage();
    }

    private int getMySQLErrorCode(Throwable t) {
        return (t instanceof SQLException) ? ((SQLException) t).getErrorCode() : -1;
    }

    private Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    /* ==================== SQL 执行 ==================== */
    private void executeWithoutLogging(String sql) throws Exception {
        globalState.executeStatement(new SQLQueryAdapter(sql, false));
    }

    private String safeMessageDetailed(Throwable t) {
        if (t == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(t.getClass().getName()).append("\n");
        sb.append("Message: ").append(t.getMessage()).append("\n");
        sb.append("MySQLErrorCode: ").append(getMySQLErrorCode(t)).append("\n");
        Throwable cause = t.getCause();
        if (cause != null) {
            sb.append("Cause Class: ").append(cause.getClass().getName()).append("\n");
            sb.append("Cause Message: ").append(cause.getMessage()).append("\n");
            sb.append("Cause MySQLErrorCode: ").append(getMySQLErrorCode(cause)).append("\n");
        }
        return sb.toString();
    }

    /* ==================== 错误日志（增加 Reduced SQL 输出） ==================== */
    private String buildErrorMessage(String originalSql, Throwable origCause,
                                     String complexSql, Throwable complexCause,
                                     String reducedSql) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== ORIGINAL SQL =====\n").append(originalSql).append("\n\n");
        sb.append("===== ORIGINAL EXCEPTION =====\n").append(safeMessageDetailed(origCause)).append("\n\n");
        sb.append("===== COMPLEX SQL =====\n").append(complexSql).append("\n\n");
        sb.append("===== COMPLEX EXCEPTION =====\n").append(safeMessageDetailed(complexCause)).append("\n\n");
        sb.append("===== EXCEPTION COMPARISON =====\n");
        if (origCause != null || complexCause != null) {
            sb.append("Original Class: ").append(origCause != null ? origCause.getClass().getName() : "<null>").append("\n");
            sb.append("Complex Class: ").append(complexCause != null ? complexCause.getClass().getName() : "<null>").append("\n");
            sb.append("Original Message: ").append(origCause != null ? origCause.getMessage() : "<null>").append("\n");
            sb.append("Complex Message: ").append(complexCause != null ? complexCause.getMessage() : "<null>").append("\n");
            sb.append("Original MySQLErrorCode: ").append(origCause != null ? getMySQLErrorCode(origCause) : "<null>").append("\n");
            sb.append("Complex MySQLErrorCode: ").append(complexCause != null ? getMySQLErrorCode(complexCause) : "<null>").append("\n");
            sb.append("Same Class: ").append(origCause != null && complexCause != null
                    ? origCause.getClass().equals(complexCause.getClass()) : "N/A").append("\n");
            sb.append("Same Message: ").append(origCause != null && complexCause != null
                    ? safeMessage(origCause).equals(safeMessage(complexCause)) : "N/A").append("\n");
            sb.append("Same MySQLErrorCode: ").append(origCause != null && complexCause != null
                    ? getMySQLErrorCode(origCause) == getMySQLErrorCode(complexCause) : "N/A").append("\n");
        }
        sb.append("\n===== REDUCED SQL =====\n").append(reducedSql).append("\n\n");
        sb.append("===== DATABASE SCHEMA =====\n").append(globalState.getSchema().toString()).append("\n\n");
        sb.append("===== DATABASE CONTENT =====\n").append(dumpAllTables(globalState)).append("\n");
        return sb.toString();
    }

    /* ==================== 表数据 dump ==================== */
    private String dumpAllTables(MySQLGlobalState state) {
        StringBuilder sb = new StringBuilder();
        MySQLSchema schema = state.getSchema();
        for (MySQLSchema.MySQLTable table : schema.getDatabaseTables()) {
//            if (table.isSystemTable()) continue;
            sb.append(dumpTable(state, table)).append("\n");
        }
        return sb.toString();
    }

    private String dumpTable(MySQLGlobalState state, MySQLSchema.MySQLTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(table.getName()).append("\n");
        String sql = "SELECT * FROM " + table.getName() + " LIMIT 50;";
        SQLQueryAdapter query = new SQLQueryAdapter(sql, false);

        try (sqlancer.common.query.SQLancerResultSet rs = state.executeStatementAndGet(query)) {
            if (rs == null) {
                sb.append("  <no result>\n");
                return sb.toString();
            }

            int colCount = rs.getColumnCount();
            sb.append("Columns (").append(colCount).append("): ");
            for (int i = 1; i <= colCount; i++) {
                sb.append(rs.getColumnName(i));
                if (i != colCount) sb.append(", ");
            }
            sb.append("\n");

            while (rs.next()) {
                sb.append("  Row: ");
                for (int i = 1; i <= colCount; i++) {
                    String val = rs.getString(i);
                    sb.append(val == null ? "NULL" : val);
                    if (i != colCount) sb.append(" | ");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("  <error reading table>: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    /* ==================== 复杂 SQL 生成 ==================== */
    private String generateEnhancedComplexEquivalent(String originalSql) {
        String sql = originalSql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);

        sql = "SELECT (" + sql + ") AS val";

        int funcLayers = 2 + random.nextInt(3);
        for (int i = 0; i < funcLayers; i++) {
            int choice = random.nextInt(7);
            switch (choice) {
                case 0: sql = "SELECT ABS(val) AS val FROM (" + sql + ") AS f" + i; break;
                case 1: sql = "SELECT COALESCE(val,val) AS val FROM (" + sql + ") AS f" + i; break;
                case 2: sql = "SELECT ROUND(val+0,0) AS val FROM (" + sql + ") AS f" + i; break;
                case 3: sql = "SELECT NULLIF(val,NULL) AS val FROM (" + sql + ") AS f" + i; break;
                case 4: sql = "SELECT IF(1=1,val,val) AS val FROM (" + sql + ") AS f" + i; break; // MySQL IF 替换 IIF
                case 5: sql = "SELECT CAST(val AS DECIMAL) AS val FROM (" + sql + ") AS f" + i; break; // CAST REAL -> DECIMAL
                default: sql = "SELECT (val+0) AS val FROM (" + sql + ") AS f" + i; break;
            }
        }

        int subqLayers = 2 + random.nextInt(3);
        for (int i = 0; i < subqLayers; i++) {
            sql = "SELECT val FROM (" + sql + ") AS subq" + i;
        }

        int dummyNum = 3 + random.nextInt(5);
        StringBuilder dummy = new StringBuilder();
        for (int i = 1; i <= dummyNum; i++) {
            int t = random.nextInt(4);
            String col;
            switch (t) {
                case 0: col = i + " AS dummy" + i; break;
                case 1: col = "'" + (char)('a' + random.nextInt(26)) + "' AS dummy" + i; break;
                case 2: col = "COALESCE(NULL," + random.nextInt(9999) + ") AS dummy" + i; break;
                default: col = "NULL AS dummy" + i; break;
            }
            dummy.append(", ").append(col);
        }
        sql = sql.replaceFirst("SELECT val", "SELECT val" + dummy);

        return sql + ";";
    }

    /* ==================== 异常等价逐层脱壳 ==================== */
    private String tryUnwrapLayers(String complexSql, MySQLGlobalState state, Throwable targetCause) {
        String sql = complexSql.trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1);

        boolean changed;
        do {
            changed = false;
            String candidate;

            candidate = unwrapOuterFunction(sql);
            if (!candidate.equals(sql) && isExceptionEquivalent(candidate, targetCause, state)) {
                sql = candidate;
                changed = true;
                continue;
            }

            candidate = unwrapOuterSubquery(sql);
            if (!candidate.equals(sql) && isExceptionEquivalent(candidate, targetCause, state)) {
                sql = candidate;
                changed = true;
                continue;
            }

            candidate = unwrapDummyColumns(sql);
            if (!candidate.equals(sql) && isExceptionEquivalent(candidate, targetCause, state)) {
                sql = candidate;
                changed = true;
            }

        } while (changed);

        return sql + ";";
    }

    private boolean isExceptionEquivalent(String candidateSql, Throwable targetCause, MySQLGlobalState state) {
        Throwable tCandidate = null;
        try {
            state.executeStatement(new SQLQueryAdapter(candidateSql, false));
        } catch (Throwable t) {
            tCandidate = unwrap(t);
        }

        if (tCandidate == null && targetCause == null) return true;
        if (tCandidate == null ^ targetCause == null) return false;
        return tCandidate.getClass().equals(targetCause.getClass())
                && safeMessage(tCandidate).equals(safeMessage(targetCause));
    }

    /* ==================== unwrap 方法 ==================== */
    private static final Pattern OUTER_FUNC_PATTERN = Pattern.compile(
            "^SELECT\\s+\\w+\\(val.*?\\)\\s+AS\\s+val\\s+FROM\\s+\\((.+)\\)\\s+AS\\s+f\\d+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private String unwrapOuterFunction(String sql) {
        Matcher m = OUTER_FUNC_PATTERN.matcher(sql.trim());
        if (m.matches()) {
            return m.group(1).trim();
        }
        return sql;
    }

    private static final Pattern OUTER_SUBQ_PATTERN = Pattern.compile(
            "^SELECT\\s+val(?:,.*)?\\s+FROM\\s+\\((.+)\\)\\s+AS\\s+subq\\d+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private String unwrapOuterSubquery(String sql) {
        Matcher m = OUTER_SUBQ_PATTERN.matcher(sql.trim());
        if (m.matches()) {
            return m.group(1).trim();
        }
        return sql;
    }

    private String unwrapDummyColumns(String sql) {
        sql = sql.trim();
        if (sql.startsWith("SELECT val,")) {
            int idx = sql.indexOf(" FROM ");
            if (idx > 0) {
                sql = "SELECT val" + sql.substring(idx);
            }
        }
        return sql;
    }
}