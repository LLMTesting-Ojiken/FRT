package sqlancer.sqlite3.oracle.FRT.Exam.RQ4;

import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.oracle.FRT.Injector.*;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;

// tries to trigger a crash
public class SQLite3FRT_randomDesign implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    // ===== 运行模式 =====
    enum Mode {
        BY_COUNT,
        BY_TIME
    }

    // ===== 配置区（不再是编译期常量）=====
    private Mode MODE = Mode.BY_TIME;

    private int MAX_COUNT = 1000;
    private int DURATION_SECONDS = 10;

    // ===== 覆盖率观测配置 =====
    private int REPORT_EVERY_N = 0;
    private int REPORT_EVERY_SECONDS = 0;

    public SQLite3FRT_randomDesign(SQLite3GlobalState globalState) {
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

    // ===== 执行一次 SQL =====
    private boolean runOne(FunctionCoverageTracker tracker) {

        // 生成原始 SQL
        String original_simple_SQL = SQLite3Visitor.asString(
                SQLite3RandomQuerySynthesizer.generate(globalState, Randomly.smallNumber() + 1)
        ) + ";";

        // ===== 随机选择一种注入策略 =====
        int choice = Randomly.fromOptions(0, 1, 2, 3, 4);

        String originalSQL;

        switch (choice) {
            case 0:
                // Agg
                originalSQL = RandomAggFunctionInjector.injectSmartAggFunction(original_simple_SQL, 1);
                break;
            case 1:
                // Math
                originalSQL = RandomMathFunctionInjector.injectSmartMathFunction(original_simple_SQL, 1);
                break;
            case 2:
                // DateTime
                originalSQL = RandomDateTimeFunctionInjector.injectSmartDateTimeFunction(original_simple_SQL, 1);
                break;
            case 3:
                // Core
                originalSQL = RandomCoreFunctionInjector.injectRandomCoreFunction(original_simple_SQL, 1);
                break;
            case 4:
                // Json
                originalSQL = RandomJsonFunctionInjector.injectSmartJsonFunction(original_simple_SQL, 1);
                break;
            default:
                originalSQL = original_simple_SQL;
        }
        try {
            globalState.executeStatement(new SQLQueryAdapter(originalSQL));
            tracker.observe(originalSQL);
            return true;
        } catch (Throwable e) {
            return false;
        }
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