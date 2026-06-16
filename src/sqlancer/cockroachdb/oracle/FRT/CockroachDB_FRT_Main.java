package sqlancer.cockroachdb.oracle.FRT;

import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.common.oracle.TestOracle;

public class CockroachDB_FRT_Main implements TestOracle<CockroachDBGlobalState> {

    private final CockroachDBGlobalState globalState;

    public CockroachDB_FRT_Main(CockroachDBGlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws Exception {
        // 创建 pipeline
        CockroachDBFRTOracle pipeline = new CockroachDBFRTOracle(globalState);
        // 运行 N 次随机 SQL 并进行函数一致性检测
        pipeline.runAndCollect(1);
    }
}



