package sqlancer.mysql.oracle.FRT;

import sqlancer.common.oracle.TestOracle;
import sqlancer.mysql.MySQLGlobalState;

public class MySQL_FRT_Main implements TestOracle<MySQLGlobalState> {
    private final MySQLGlobalState globalState;

    public MySQL_FRT_Main(MySQLGlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws Exception {
        // 创建 pipeline
        MySQLFRTOracle pipeline = new MySQLFRTOracle(globalState);
        // 运行 N 次随机 SQL 并进行函数一致性检测
        pipeline.runAndCollect(1);
    }
}



