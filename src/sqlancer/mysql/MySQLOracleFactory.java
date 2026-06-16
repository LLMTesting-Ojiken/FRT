package sqlancer.mysql;

import java.sql.SQLException;
import java.util.Optional;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.CERTOracle;
import sqlancer.common.oracle.TLPWhereOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.mysql.gen.MySQLExpressionGenerator;
import sqlancer.mysql.oracle.*;
import sqlancer.mysql.oracle.FRT.MySQL_FRT_Main;

public enum MySQLOracleFactory implements OracleFactory<MySQLGlobalState> {

    TLP_WHERE {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            MySQLExpressionGenerator gen = new MySQLExpressionGenerator(globalState);
            ExpectedErrors expectedErrors = ExpectedErrors.newErrors().with(MySQLErrors.getExpressionErrors())
                    .withRegex(MySQLErrors.getExpressionRegexErrors()).build();

            return new TLPWhereOracle<>(globalState, gen, expectedErrors);
        }

    },
    PQS {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            return new MySQLPivotedQuerySynthesisOracle(globalState);
        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }

    },
    CERT {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            MySQLExpressionGenerator gen = new MySQLExpressionGenerator(globalState);
            ExpectedErrors expectedErrors = ExpectedErrors.newErrors().with(MySQLErrors.getExpressionErrors())
                    .withRegex(MySQLErrors.getExpressionRegexErrors()).build();
            CERTOracle.CheckedFunction<SQLancerResultSet, Optional<Long>> rowCountParser = (rs) -> {
                int rowCount = rs.getInt(10);
                return Optional.of((long) rowCount);
            };
            CERTOracle.CheckedFunction<SQLancerResultSet, Optional<String>> queryPlanParser = (rs) -> {
                String operation = rs.getString(2);
                return Optional.of(operation);
            };

            return new CERTOracle<>(globalState, gen, expectedErrors, rowCountParser, queryPlanParser);

        }

        @Override
        public boolean requiresAllTablesToContainRows() {
            return true;
        }
    },
    FRT {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws Exception {
            return new MySQL_FRT_Main(globalState);
        }
    },
    FUZZER {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws Exception {
            return new MySQLFuzzer(globalState);
        }

    },
    EHO {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            return new MySQLEHOracle(globalState);
        }
    },
    DQP {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            return new MySQLDQPOracle(globalState);
        }
    },
    DQE {
        @Override
        public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
            return new MySQLDQEOracle(globalState);
        }
    };
}
