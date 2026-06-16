package sqlancer.sqlite3.oracle.FRT;

import sqlancer.common.oracle.TestOracle;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.oracle.FRT.Mutators.SQLite3Function_level_Reconstruction_Oracle;

public class SQLite3FRT_Main implements TestOracle<SQLite3GlobalState> {
    private final SQLite3GlobalState globalState;

    public SQLite3FRT_Main(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws Exception {
        // 创建 pipeline
        SQLite3Function_level_Reconstruction_Oracle pipeline = new SQLite3Function_level_Reconstruction_Oracle(globalState);

        // 运行 N 次随机 SQL 并进行函数一致性检测
        pipeline.runAndCollect(1);
    }
}



