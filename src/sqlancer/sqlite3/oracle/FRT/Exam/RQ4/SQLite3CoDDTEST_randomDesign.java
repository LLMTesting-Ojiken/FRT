package sqlancer.sqlite3.oracle.FRT.Exam.RQ4;

import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Select;
import sqlancer.sqlite3.gen.SQLite3Common;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.schema.SQLite3Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// tries to trigger a crash
public class SQLite3CoDDTEST_randomDesign implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    // ===== 运行模式 =====
    enum Mode {
        BY_COUNT,
        BY_TIME
    }

    // ===== 配置区（不再是编译期常量）=====
    private Mode MODE = Mode.BY_TIME;

    private int MAX_COUNT = 100_000;
    private int DURATION_SECONDS = 10;

    // ===== 覆盖率观测配置 =====
    private int REPORT_EVERY_N = 0;
    private int REPORT_EVERY_SECONDS = 0;

    public SQLite3CoDDTEST_randomDesign(SQLite3GlobalState globalState) {
        this.globalState = globalState;

        // ✅ 支持 JVM 参数覆盖
        String modeProp = System.getProperty("mode", "time");
        if (modeProp.equalsIgnoreCase("count")) {
            MODE = Mode.BY_COUNT;
        } else {
            MODE = Mode.BY_TIME;
        }

        MAX_COUNT = Integer.getInteger("maxCount", MAX_COUNT);
        DURATION_SECONDS = Integer.getInteger("durationSeconds", DURATION_SECONDS);
        REPORT_EVERY_N = Integer.getInteger("reportEveryN", REPORT_EVERY_N);
        REPORT_EVERY_SECONDS = Integer.getInteger("reportEverySeconds", REPORT_EVERY_SECONDS);
    }

    @Override
    public void check() throws Exception {

        FunctionCoverageTracker tracker = new FunctionCoverageTracker(0);

        int total = 0;
        int success = 0;
        int fail = 0;

        long startTime = System.nanoTime();
        long nextReportTime = startTime + REPORT_EVERY_SECONDS * 1_000_000_000L;

        if (MODE == Mode.BY_COUNT) {
            for (int i = 0; i < MAX_COUNT; i++) {
                boolean ok = runOne(tracker);
                total++;
                if (ok) success++; else fail++;

                nextReportTime = periodicReport(tracker, startTime, total, nextReportTime);
            }
        } else {
            long endTimeLimit = startTime + DURATION_SECONDS * 1_000_000_000L;

            while (System.nanoTime() < endTimeLimit) {
                boolean ok = runOne(tracker);
                total++;
                if (ok) success++; else fail++;

                nextReportTime = periodicReport(tracker, startTime, total, nextReportTime);
            }
        }

        long endTime = System.nanoTime();
        double timeSeconds = (endTime - startTime) / 1_000_000_000.0;

        System.out.println("=== Final Statistics ===");
        System.out.println("Mode                 : " + MODE);
        System.out.println("Total generated SQL  : " + total);
        System.out.println("Executed successfully: " + success);
        System.out.println("Execution failed     : " + fail);

        double successRate = total == 0 ? 0.0 : success * 100.0 / total;
        System.out.printf("Success rate         : %.2f%%%n", successRate);
        System.out.printf("Total time (seconds) : %.2f s%n", timeSeconds);

        tracker.report();
        System.exit(1);
    }

    private boolean runOne(FunctionCoverageTracker tracker) {
        try {
            // ===== 完全按 CODDTest 的方式生成查询 =====
            SQLite3Schema s = globalState.getSchema();
            SQLite3Schema.SQLite3Tables targetTables = s.getRandomTableNonEmptyTables();

            SQLite3ExpressionGenerator gen =
                    new SQLite3ExpressionGenerator(globalState)
                            .setColumns(targetTables.getColumns());

            SQLite3Select select = new SQLite3Select();
            select.setFetchColumns(generateFetchColumns(targetTables));

            List<SQLite3Schema.SQLite3Table> tables = targetTables.getTables();
            select.setJoinClauses(gen.getRandomJoinClauses(tables));
            select.setFromList(SQLite3Common.getTableRefs(tables, s));
            select.setWhereClause(null); // 和 CODDTest 初始一致

            // 转成 SQL 字符串
            String sql = SQLite3Visitor.asString(select) + ";";

            // ===== 执行并统计 =====
            globalState.executeStatement(new SQLQueryAdapter(sql));
            tracker.observe(sql);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
    private List<SQLite3Expression> generateFetchColumns(SQLite3Schema.SQLite3Tables targetTables) {
        List<SQLite3Expression> columns = new ArrayList<>();
        if (Randomly.getBoolean()) {
            columns.add(new SQLite3Expression.SQLite3ColumnName(SQLite3Schema.SQLite3Column.createDummy("*"), null));
        } else {
            columns = Randomly.nonEmptySubset(targetTables.getColumns()).stream()
                    .map(c -> new SQLite3Expression.SQLite3ColumnName(c, null))
                    .collect(Collectors.toList());
        }
        return columns;
    }
    // ===== 周期性输出覆盖率 =====
    private long periodicReport(FunctionCoverageTracker tracker,
                                long startTime,
                                int total,
                                long nextReportTime) {

        long now = System.nanoTime();
        double elapsedSeconds = (now - startTime) / 1_000_000_000.0;

        if (REPORT_EVERY_N > 0 && total % REPORT_EVERY_N == 0) {
            System.out.printf("=== Coverage at %d queries (%.1f s) ===%n",
                    total, elapsedSeconds);
            tracker.report();
        }

        if (REPORT_EVERY_SECONDS > 0 && now >= nextReportTime) {
            System.out.printf("=== Coverage at %.1f seconds (%d queries) ===%n",
                    elapsedSeconds, total);
            tracker.report();
            return nextReportTime + REPORT_EVERY_SECONDS * 1_000_000_000L;
        }

        return nextReportTime;
    }
}