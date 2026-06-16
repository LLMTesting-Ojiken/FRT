package sqlancer.sqlite3.oracle.epo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

import sqlancer.sqlite3.SQLite3Errors;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;

/**
 * Experimental Oracle:
 * Detects SQLite bugs where an originally failing query (triggering a known SQLite3Errors message)
 * stops throwing the same error after mutating parts *after* the error-causing clause.
 */
public class SQLite3ErrorPreservingOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState state;
    private final ExpectedErrors expectedErrors = new ExpectedErrors();



    public SQLite3ErrorPreservingOracle(SQLite3GlobalState state) {
        this.state = state;
        // Collect known errors so we can recognize them later
        SQLite3Errors.addExpectedExpressionErrors(expectedErrors);
        SQLite3Errors.addDeleteErrors(expectedErrors);
        SQLite3Errors.addMatchQueryErrors(expectedErrors);
        SQLite3Errors.addInsertNowErrors(expectedErrors);
        SQLite3Errors.addInsertUpdateErrors(expectedErrors);
        SQLite3Errors.addQueryErrors(expectedErrors);
        SQLite3Errors.addTableManipulationErrors(expectedErrors);
    }

    @Override
    public void check() throws SQLException {
//        随机生成一个sql语句
        String sql = SQLite3Visitor
                .asString(SQLite3RandomQuerySynthesizer.generate(state, Randomly.smallNumber() + 1)) + ";";

        String firstError = executeAndCaptureError(sql);

        if (firstError == null) {

            throw new IgnoreMeException();
        }



        int pos = locateErrorPosition(sql, firstError);
        String mutated = mutateAfterPosition(sql, pos);

        String mutatedError = executeAndCaptureError(mutated);

        if (mutatedError == null) {
            throw new AssertionError("[Bug] Error disappeared after harmless mutation.\n" +
                    "Original: " + sql + "\n" +
                    "Error: " + firstError + "\n" +
                    "Mutated: " + mutated);
        }

        if (!mutatedError.equals(firstError)) {
            state.getLogger().writeCurrent("\n[Warning] Error changed:\n" + firstError + "\n→ " + mutatedError);
        }
    }

    private String executeAndCaptureError(String sql) {
//        SQLQueryAdapter q = new SQLQueryAdapter(sql);
        SQLQueryAdapter q = new SQLQueryAdapter(sql, expectedErrors);
        try {
            state.executeStatement(q);

            return null;
        } catch (Exception e) {
            String msg = e.getMessage();

            if (msg != null && isSQLite3Error(msg)) {

                return msg;
            }
            return null;
        }
    }
//
    private boolean isSQLite3Error(String msg) {
        List<String> knownErrors = new ArrayList<>();
        knownErrors.addAll(SQLite3Errors.getExpectedExpressionErrors());
        knownErrors.addAll(SQLite3Errors.getDeleteErrors());
        knownErrors.addAll(SQLite3Errors.getMatchQueryErrors());
        knownErrors.addAll(SQLite3Errors.getInsertNowErrors());
        knownErrors.addAll(SQLite3Errors.getInsertUpdateErrors());
        knownErrors.addAll(SQLite3Errors.getQueryErrors());
        knownErrors.addAll(SQLite3Errors.getTableManipulationErrors());

        for (String s : knownErrors) {
            if (msg.contains(s)) {
                return true;
            }
        }

        return msg.contains("SQLITE_") || msg.contains("syntax") || msg.contains("error") ||
                msg.contains("constraint") || msg.contains("locked") || msg.contains("no such");
    }

    private int locateErrorPosition(String sql, String errorMsg) {
        int pos = sql.length() / 2;
        if (errorMsg.contains("WHERE")) {
            pos = sql.indexOf("WHERE");
        } else if (errorMsg.contains("SELECT")) {
            pos = sql.indexOf("SELECT");
        } else if (errorMsg.contains("FROM")) {
            pos = sql.indexOf("FROM");
        }
        if (pos < 0) {
            pos = sql.length() / 2;
        }
        return pos;
    }

    private String mutateAfterPosition(String sql, int pos) {
        String before = sql.substring(0, pos);
        String after = sql.substring(pos);

        String mutation;
        int choice = Randomly.fromOptions(0, 1, 2);
        if (choice == 0) {
            mutation = " /*noop*/ ";
        } else if (choice == 1) {
            mutation = " LIMIT " + Randomly.smallNumber() + " ";
        } else {
            mutation = " ORDER BY " + Randomly.getNotCachedInteger(1, 4);
        }

        return before + after + mutation;
    }
}