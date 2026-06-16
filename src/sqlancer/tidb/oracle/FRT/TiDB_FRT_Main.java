package sqlancer.tidb.oracle.FRT;

import sqlancer.common.oracle.TestOracle;
import sqlancer.tidb.TiDBProvider;

public class TiDB_FRT_Main implements TestOracle<TiDBProvider.TiDBGlobalState> {
    private final TiDBProvider.TiDBGlobalState globalState;

    public TiDB_FRT_Main(TiDBProvider.TiDBGlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws Exception {
        // 创建 pipeline
        TiDBFRTOracle pipeline = new TiDBFRTOracle(globalState);
        // 运行 N 次随机 SQL 并进行函数一致性检测
        pipeline.runAndCollect(1);
    }
}



