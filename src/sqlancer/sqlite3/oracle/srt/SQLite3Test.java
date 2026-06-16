package sqlancer.sqlite3.oracle.srt;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.Query;
import sqlancer.sqlite3.SQLite3GlobalState;

import java.util.List;

// tries to trigger a crash
public class SQLite3Test implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    public SQLite3Test(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    private String dumpAllSQLStatements(SQLite3GlobalState state) {
        StringBuilder sb = new StringBuilder();
        List<Query<?>> statements = state.getState().getStatements();
        if (statements == null || statements.isEmpty()) {
            sb.append("  <no statements>\n");
        } else {
            sb.append("====== ALL SQL STATEMENTS ======\n");
            for (int i = 0; i < statements.size(); i++) {
                sb.append(i + 1).append(": ").append(statements.get(i).getQueryString()).append(";\n");
            }
        }
        return sb.toString();
    }

//    private String dumpLastSQLStatements(SQLite3GlobalState state) {
//        StringBuilder sb = new StringBuilder();
//        List<Query<?>> statements = state.getState().getStatements();
//        if (statements == null || statements.isEmpty()) {
//            sb.append("  <no statements>\n");
//        } else {
//            int size = statements.size();
//            int start = Math.max(0, size - 10);
//            sb.append("====== LAST 50 SQL STATEMENTS ======\n");
//            for (int i = start; i < size; i++) {
//                sb.append(i + 1).append(": ").append(statements.get(i).getQueryString()).append(";\n");
//            }
//        }
//        return sb.toString();
//    }


    @Override
    public void check() throws Exception {
        System.out.println(dumpAllSQLStatements(globalState));
        System.exit(0);
//        String s = SQLite3Visitor
//                .asString(SQLite3RandomQuerySynthesizer.generate(globalState, Randomly.smallNumber() + 1)) + ";";
//
//        try {
//            globalState.executeStatement(new SQLQueryAdapter(s));
//            globalState.getManager().incrementSelectQueryCount();
//        } catch (Throwable e) {
//            System.out.println(dumpLastSQLStatements(globalState));
//            throw new AssertionError("demo:" + globalState.toString());
//        }
    }
}
