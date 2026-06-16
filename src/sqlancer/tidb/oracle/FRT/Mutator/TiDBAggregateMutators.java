package sqlancer.tidb.oracle.FRT.Mutator;

import sqlancer.Randomly;
import sqlancer.tidb.oracle.FRT.TiDBFRTOracle;

import java.util.Map;

public final class TiDBAggregateMutators {

    private TiDBAggregateMutators() {
    }

    public static void register(
            Map<String, TiDBFRTOracle.FunctionMutator> mutators) {

        mutators.put("COUNT", new CountMutator());
        mutators.put("SUM", new SumMutator());
        mutators.put("AVG", new AvgMutator());

        mutators.put("MIN", new MinMutator());
        mutators.put("MAX", new MaxMutator());

        mutators.put("GROUP_CONCAT", new GroupConcatMutator());

        mutators.put("JSON_ARRAYAGG", new JsonArrayAggMutator());
        mutators.put("JSON_OBJECTAGG", new JsonObjectAggMutator());

        mutators.put("STD", new StdMutator());
        mutators.put("STDDEV", new StdMutator());
        mutators.put("STDDEV_POP", new StdMutator());
        mutators.put("STDDEV_SAMP", new StddevSampMutator());

        mutators.put("VAR_POP", new VarPopMutator());
        mutators.put("VAR_SAMP", new VarSampMutator());
        mutators.put("VARIANCE", new VarPopMutator());

        mutators.put("APPROX_COUNT_DISTINCT", new ApproxCountDistinctMutator());
        mutators.put("APPROX_PERCENTILE", new ApproxPercentileMutator());
    }

    /* ================= COUNT ================= */

    static class CountMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return !after.toUpperCase().contains("OVER");
        }

        public String mutate(String arg) {

            arg = arg.trim();

            if (arg.equals("*")) {
                return Randomly.fromOptions(

                        // Alias
                        "COUNT(1)",

                        // Functional
                        "COALESCE(SUM(1),0)"
                );
            }

            if (arg.toUpperCase().startsWith("DISTINCT")) {
                String inner = arg.substring("DISTINCT".length()).trim();

                return Randomly.fromOptions(

                        // Conditional
                        String.format(
                                "COUNT(DISTINCT CASE WHEN %s IS NOT NULL THEN %s END)",
                                inner, inner
                        ),

                        // Functional
                        String.format(
                                "COUNT(DISTINCT %s)",
                                inner
                        )
                );
            }

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "COALESCE(SUM(CASE WHEN %s IS NOT NULL THEN 1 ELSE 0 END),0)",
                            arg
                    ),

                    // Mathematical
                    String.format(
                            "COALESCE(SUM(%s IS NOT NULL),0)",
                            arg
                    ),

                    // ✅ 修复版
                    String.format(
                            "COUNT(*) - COALESCE(SUM(%s IS NULL),0)",
                            arg
                    ),

                    // Conditional（另一种写法）
                    String.format(
                            "COUNT(CASE WHEN %s IS NOT NULL THEN %s END)",
                            arg, arg
                    )
            );
        }
    }

    /* ================= SUM ================= */

    static class SumMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return !after.toUpperCase().contains("OVER");
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "SUM(CASE WHEN %s IS NULL THEN NULL ELSE %s END)",
                            arg, arg
                    ),

                    // Conditional（等价变体）
                    String.format(
                            "SUM(CASE WHEN %s IS NOT NULL THEN %s END)",
                            arg, arg
                    ),

                    // Functional（恒等扰动）
                    String.format(
                            "SUM(%s + 0)",
                            arg
                    )
            );
        }
    }

    /* ================= AVG ================= */

    static class AvgMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return !after.toUpperCase().contains("OVER");
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Functional
                    String.format(
                            "SUM(%1$s)/NULLIF(COUNT(%1$s),0)",
                            arg
                    ),

                    // Conditional
                    String.format(
                            "AVG(CASE WHEN %s IS NOT NULL THEN %s END)",
                            arg, arg
                    ),

                    // Nested Functional
                    String.format(
                            "SUM(%1$s)/NULLIF(SUM(CASE WHEN %1$s IS NOT NULL THEN 1 ELSE 0 END),0)",
                            arg
                    )
            );
        }
    }

    /* ================= MIN ================= */

    static class MinMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "MIN(CASE WHEN %s IS NULL THEN NULL ELSE %s END)",
                            arg, arg
                    )

//                    // Functional（恒等）
//                    String.format(
//                            "MIN(%s + 0)",
//                            arg
//                    )
            );
        }
    }

    /* ================= MAX ================= */

    static class MaxMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "MAX(CASE WHEN %s IS NULL THEN NULL ELSE %s END)",
                            arg, arg
                    )

//                    // Functional
//                    String.format(
//                            "MAX(%s + 0)",
//                            arg
//                    )
            );
        }
    }

    /* ================= GROUP_CONCAT ================= */

    static class GroupConcatMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "GROUP_CONCAT(CASE WHEN %s IS NOT NULL THEN %s END)",
                            arg, arg
                    ),

                    // Functional（类型映射）
                    String.format(
                            "GROUP_CONCAT(CAST(%s AS CHAR))",
                            arg
                    )
            );
        }
    }

    /* ================= JSON_ARRAYAGG ================= */

    static class JsonArrayAggMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "JSON_ARRAYAGG(CASE WHEN %s IS NOT NULL THEN %s END)",
                            arg, arg
                    ),

                    // Functional（恒等）
                    "JSON_ARRAYAGG(" + arg + ")"
            );
        }
    }

    /* ================= JSON_OBJECTAGG ================= */

    static class JsonObjectAggMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            int idx = arg.indexOf(",");

            if (idx == -1) {
                return "JSON_OBJECTAGG(" + arg + ")";
            }

            String k = arg.substring(0, idx).trim();
            String v = arg.substring(idx + 1).trim();

            return Randomly.fromOptions(

                    // Conditional
                    String.format(
                            "JSON_OBJECTAGG(CASE WHEN %s IS NOT NULL THEN %s END, CASE WHEN %s IS NOT NULL THEN %s END)",
                            k, k, k, v
                    ),

                    // Functional
                    String.format(
                            "JSON_OBJECTAGG(%s, %s)",
                            k, v
                    )
            );
        }
    }

    /* ================= STD ================= */

    static class StdMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(
                    "STD(" + arg + ")",
                    "STDDEV(" + arg + ")",
                    // Alias
                    "STDDEV_POP(" + arg + ")",

                    // Mathematical
                    "SQRT(VAR_POP(" + arg + "))"
            );
        }
    }

    static class StddevSampMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Alias
                    "STDDEV_SAMP(" + arg + ")",

                    // Mathematical
                    "SQRT(VAR_SAMP(" + arg + "))"
            );
        }
    }

    /* ================= VAR_POP ================= */

    static class VarPopMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Mathematical（核心）
                    String.format(
                            "AVG(%1$s * %1$s) - AVG(%1$s) * AVG(%1$s)",
                            arg
                    ),
                    "VAR_POP(" + arg + ")",
                    "VARIANCE(" + arg + ")",

                    // Functional（展开）
                    String.format(
                            "(SUM(%1$s * %1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / NULLIF(COUNT(%1$s),0)",
                            arg
                    )
            );
        }
    }

    /* ================= VAR_SAMP ================= */

    static class VarSampMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Mathematical
                    String.format(
                            "(SUM(%1$s * %1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / NULLIF(COUNT(%1$s)-1,0)",
                            arg
                    ),

                    // Functional
                    String.format(
                            "VAR_POP(%1$s) * COUNT(%1$s) / NULLIF(COUNT(%1$s)-1,0)",
                            arg
                    )
            );
        }
    }

    /* ================= APPROX ================= */

    static class ApproxCountDistinctMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return false;
        }

        public String mutate(String arg) {
            return "APPROX_COUNT_DISTINCT(" + arg + ")";
        }
    }

    static class ApproxPercentileMutator implements TiDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return false;
        }

        public String mutate(String arg) {
            return "APPROX_PERCENTILE(" + arg + ",50)";
        }
    }
}