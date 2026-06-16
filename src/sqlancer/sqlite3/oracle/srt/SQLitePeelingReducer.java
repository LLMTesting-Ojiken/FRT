package sqlancer.sqlite3.oracle.srt;

import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import org.sqlite.SQLiteException;

public class SQLitePeelingReducer {

    private final SQLite3GlobalState globalState;
    private boolean debug = false;

    public SQLitePeelingReducer(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    public void enableDebug() {
        this.debug = true;
    }

    /** ================
     *  Public API
     * ================ */
    public String reduce(String originalSql, String complexSql) {
        String current = complexSql;

        while (true) {
            PeelResult peel = peelOneLayer(current);

            if (!peel.success) {
                log("[STOP] No more structural layers to peel.");
                return current;
            }

            log("[TRY] Peeled one layer:\n" + peel.peeled);

            if (stillMismatches(originalSql, peel.peeled)) {
                log("[KEEP] Peeled version still mismatches → accept peel");
                current = peel.peeled;
            } else {
                log("[STOP] Further peeling destroys mismatch → stop reducing.");
                return current;
            }
        }
    }


    /** ================
     *   Mismatch Re-check
     * ================ */
    private boolean stillMismatches(String original, String complex) {
        Throwable origEx = execCatch(original);
        Throwable compEx = execCatch(complex);

        if (origEx == null && compEx == null) return false;
        if (origEx == null || compEx == null) return true;

        boolean typeDiff = !origEx.getClass().equals(compEx.getClass());
        boolean msgDiff = !safeEquals(origEx.getMessage(), compEx.getMessage());
        boolean codeDiff = getErrorCode(origEx) != getErrorCode(compEx);

        return typeDiff || msgDiff || codeDiff;
    }

    private Throwable execCatch(String sql) {
        try {
            globalState.executeStatement(new SQLQueryAdapter(sql, false));
            return null;
        } catch (Throwable t) {
            return unwrap(t);
        }
    }

    private int getErrorCode(Throwable t) {
        if (t instanceof SQLiteException) {
            return ((SQLiteException) t).getResultCode().code;
        }
        return -1;
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }


    /** ================
     *  Structured Peeling Controller
     * ================ */
    private PeelResult peelOneLayer(String sql) {

        // 1. Dummy columns
        String s1 = peelDummyColumns(sql);
        if (s1 != null) return PeelResult.ok(s1);

        // 2. Subquery wrappers
        String s2 = peelSubquery(sql);
        if (s2 != null) return PeelResult.ok(s2);

        // 3. Function wrappers
        String s3 = peelFunction(sql);
        if (s3 != null) return PeelResult.ok(s3);

        // 4. Outer SELECT (expr)
        String s4 = peelOuter(sql);
        if (s4 != null) return PeelResult.ok(s4);

        return PeelResult.fail();
    }


    /** ================
     *  Structured Peeling Steps
     * ================ */

    /** Step 1: remove dummy columns after SELECT val */
    private String peelDummyColumns(String sql) {
        String upper = sql.toUpperCase();
        int idx = upper.indexOf("SELECT VAL");
        if (idx < 0) return null;

        int fromIdx = upper.indexOf("FROM", idx);
        if (fromIdx < 0) return null;

        return "SELECT val " + sql.substring(fromIdx);
    }


    /** Step 2: peel SELECT val FROM (<inner>) AS subqX */
    private String peelSubquery(String sql) {
        String s = sql.trim().toUpperCase();
        if (!s.startsWith("SELECT VAL FROM (")) return null;

        int firstParen = sql.indexOf('(');
        int close = findMatchingParen(sql, firstParen);
        if (close < 0) return null;

        return sql.substring(firstParen + 1, close).trim();
    }


    /** Step 3: peel SELECT func(val) AS val FROM (<inner>) AS fX */
    private String peelFunction(String sql) {
        String s = sql.trim().toUpperCase();

        if (!s.startsWith("SELECT")) return null;

        int fromIdx = s.indexOf("FROM");
        if (fromIdx < 0) return null;

        int firstParen = s.indexOf('(', fromIdx);
        if (firstParen < 0) return null;

        int close = findMatchingParen(sql, firstParen);
        if (close < 0) return null;

        return sql.substring(firstParen + 1, close).trim();
    }


    /** Step 4: peel SELECT (expr) AS val */
    private String peelOuter(String sql) {
        String s = sql.trim().toUpperCase();
        if (!s.startsWith("SELECT (")) return null;

        int firstParen = s.indexOf('(');
        int close = findMatchingParen(s, firstParen);
        if (close < 0) return null;

        return sql.substring(firstParen + 1, close).trim();
    }


    /** ================
     *  Utility: find matching parenthesis
     * ================ */
    private int findMatchingParen(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }


    private void log(String msg) {
        if (debug) {
            System.out.println("[Reducer] " + msg);
        }
    }


    /** ================
     *  Peel Result Wrapper
     * ================ */
    private static class PeelResult {
        final boolean success;
        final String peeled;

        private PeelResult(boolean success, String peeled) {
            this.success = success;
            this.peeled = peeled;
        }

        static PeelResult ok(String peeled) {
            return new PeelResult(true, peeled);
        }

        static PeelResult fail() {
            return new PeelResult(false, null);
        }
    }
}