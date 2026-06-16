package sqlancer.mysql.oracle.FRT.Mutator;

import sqlancer.Randomly;
import sqlancer.mysql.oracle.FRT.MySQLFRTOracle;

import java.util.Map;

public final class MySQLAggregateMutators {

    private MySQLAggregateMutators() {}

    public static void register(
            Map<String, MySQLFRTOracle.FunctionMutator> mutators) {

        mutators.put("AVG", new AvgMutator());
        mutators.put("SUM", new SumMutator());
        mutators.put("COUNT", new CountMutator());

        mutators.put("MIN", new MinMutator());
        mutators.put("MAX", new MaxMutator());

        mutators.put("GROUP_CONCAT", new GroupConcatMutator());

        mutators.put("BIT_AND", new BitAndMutator());
        mutators.put("BIT_OR", new BitOrMutator());
        mutators.put("BIT_XOR", new BitXorMutator());

        mutators.put("JSON_ARRAYAGG", new JsonArrayAggMutator());
        mutators.put("JSON_OBJECTAGG", new JsonObjectAggMutator());

        mutators.put("STD", new StdMutator());
        mutators.put("STDDEV", new StddevMutator());
        mutators.put("STDDEV_POP", new StddevPopMutator());
        mutators.put("STDDEV_SAMP", new StddevSampMutator());

        mutators.put("VAR_POP", new VarPopMutator());
        mutators.put("VAR_SAMP", new VarSampMutator());
        mutators.put("VARIANCE", new VarianceMutator());
    }

    /* ============================================================
     * AVG
     * ============================================================ */

    static class AvgMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return false;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // Functional Composition
                    String.format("(SUM(%1$s) / COUNT(%1$s))", arg),

                    // Conditional Semantic
                    String.format(
                            "(CASE WHEN COUNT(%1$s)=0 THEN NULL ELSE SUM(%1$s)/COUNT(%1$s) END)",
                            arg
                    ),

                    // Nested Rule Substitution
                    String.format(
                            "(SUM(%1$s) / SUM(CASE WHEN %1$s IS NOT NULL THEN 1 ELSE 0 END))",
                            arg
                    ),

                    // Conditional + Nested
                    String.format(
                            "(CASE WHEN SUM(CASE WHEN %1$s IS NOT NULL THEN 1 ELSE 0 END)=0 " +
                                    "THEN NULL ELSE SUM(%1$s) / SUM(CASE WHEN %1$s IS NOT NULL THEN 1 ELSE 0 END) END)",
                            arg
                    ),

                    // Conditional rewrite
                    String.format(
                            "AVG(CASE WHEN %1$s IS NOT NULL THEN %1$s END)",
                            arg
                    )
            );
        }
    }

    /* ============================================================
     * SUM
     * ============================================================ */

    static class SumMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "SUM(CASE WHEN %1$s IS NULL THEN NULL ELSE %1$s END)",
                            arg
                    )

            );
        }
    }

    /* ============================================================
     * COUNT
     * ============================================================ */

    static class CountMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            arg = arg.trim();

            if (arg.equals("*")) {

                return Randomly.fromOptions(

                        "COUNT(1)",

                        "COALESCE(SUM(1),0)"
                );

            } else {

                return Randomly.fromOptions(

                        String.format(
                                "COALESCE(SUM(CASE WHEN %s IS NOT NULL THEN 1 END),0)",
                                arg
                        )
                );
            }
        }
    }

    /* ============================================================
     * MIN
     * ============================================================ */

    static class MinMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return false;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "MIN(CASE WHEN %1$s IS NULL THEN NULL ELSE %1$s END)",
                            arg
                    )
            );
        }
    }

    /* ============================================================
     * MAX
     * ============================================================ */

    static class MaxMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "MAX(CASE WHEN %1$s IS NULL THEN NULL ELSE %1$s END)",
                            arg
                    )
            );
        }
    }

    /* ============================================================
     * GROUP_CONCAT
     * ============================================================ */

    static class GroupConcatMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    "GROUP_CONCAT(" + arg + ")",

                    "GROUP_CONCAT(CAST(" + arg + " AS CHAR))"
            );
        }
    }

    /* ============================================================
     * BIT_AND
     * ============================================================ */

    static class BitAndMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "BIT_AND(CAST(%s AS UNSIGNED))",
                            arg
                    )
            );
        }
    }

    /* ============================================================
     * BIT_OR
     * ============================================================ */

    static class BitOrMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "BIT_OR(CAST(%s AS UNSIGNED))",
                            arg
                    ),

                    String.format(
                            "BIT_OR(CASE WHEN %1$s IS NULL THEN NULL ELSE %1$s END)",
                            arg
                    )
            );
        }
    }
    /* ============================================================
     * BIT_XOR
     * ============================================================ */

    static class BitXorMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "BIT_XOR(CAST(%s AS UNSIGNED))",
                            arg
                    ),

                    String.format(
                            "BIT_XOR(CASE WHEN %1$s IS NULL THEN NULL ELSE %1$s END)",
                            arg
                    )
            );
        }
    }
    /* ============================================================
     * JSON_ARRAYAGG
     * ============================================================ */

    static class JsonArrayAggMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    "JSON_ARRAYAGG(" + arg + ")",

                    "JSON_ARRAYAGG(CAST(" + arg + " AS JSON))"
            );
        }
    }

    /* ============================================================
     * JSON_OBJECTAGG
     * ============================================================ */

    static class JsonObjectAggMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            int idx = arg.indexOf(",");

            if (idx == -1) {
                return "JSON_OBJECTAGG(" + arg + ")";
            }

            String key = arg.substring(0, idx).trim();
            String val = arg.substring(idx + 1).trim();

            return Randomly.fromOptions(

                    "JSON_OBJECTAGG(" + key + "," + val + ")",

                    "JSON_OBJECTAGG(CAST(" + key + " AS CHAR)," + val + ")"
            );
        }
    }

    /* ============================================================
     * STD family
     * ============================================================ */

    static class StdMutator extends StddevPopMutator {}
    static class StddevMutator extends StddevPopMutator {}

    static class StddevPopMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "SQRT((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / COUNT(%1$s))",
                            arg
                    ),

                    String.format(
                            "SQRT(AVG(%1$s*%1$s) - AVG(%1$s)*AVG(%1$s))",
                            arg
                    ),

                    String.format(
                            "(CASE WHEN COUNT(%1$s)=0 THEN NULL ELSE " +
                                    "SQRT((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / COUNT(%1$s)) END)",
                            arg
                    )
            );
        }
    }

    static class StddevSampMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "SQRT((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / (COUNT(%1$s)-1))",
                            arg
                    ),

                    String.format(
                            "SQRT(VAR_SAMP(%s))",
                            arg
                    ),

                    String.format(
                            "(CASE WHEN COUNT(%1$s)<=1 THEN NULL ELSE " +
                                    "SQRT((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / (COUNT(%1$s)-1)) END)",
                            arg
                    )
            );
        }
    }

    /* ============================================================
     * VAR family
     * ============================================================ */

    static class VarPopMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / COUNT(%1$s))",
                            arg
                    ),

                    String.format(
                            "(AVG(%1$s*%1$s) - AVG(%1$s)*AVG(%1$s))",
                            arg
                    ),

                    String.format(
                            "(CASE WHEN COUNT(%1$s)=0 THEN NULL ELSE " +
                                    "(SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / COUNT(%1$s) END)",
                            arg
                    )
            );
        }
    }

    static class VarSampMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format(
                            "((SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / (COUNT(%1$s)-1))",
                            arg
                    ),

                    String.format(
                            "((AVG(%1$s*%1$s) - AVG(%1$s)*AVG(%1$s)) * COUNT(%1$s) / (COUNT(%1$s)-1))",
                            arg
                    ),

                    String.format(
                            "(CASE WHEN COUNT(%1$s)<=1 THEN NULL ELSE " +
                                    "(SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / (COUNT(%1$s)-1) END)",
                            arg
                    )
            );
        }
    }

    static class VarianceMutator implements MySQLFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    String.format("VAR_POP(%s)", arg),

                    String.format(
                            "(AVG(%1$s*%1$s) - AVG(%1$s)*AVG(%1$s))",
                            arg
                    ),

                    String.format(
                            "(CASE WHEN COUNT(%1$s)=0 THEN NULL ELSE " +
                                    "(SUM(%1$s*%1$s) - SUM(%1$s)*SUM(%1$s)/COUNT(%1$s)) / COUNT(%1$s) END)",
                            arg
                    )
            );
        }
    }
}