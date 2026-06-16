package sqlancer.cockroachdb.oracle.FRT.Mutator;

import sqlancer.Randomly;
import sqlancer.cockroachdb.oracle.FRT.CockroachDBFRTOracle;

import java.util.Map;

public final class CockroachDBAggregateMutators {

    private CockroachDBAggregateMutators() {}

    public static void register(
            Map<String, CockroachDBFRTOracle.FunctionMutator> mutators) {

        /* numeric */
        mutators.put("AVG", new AvgMutator());
        mutators.put("SUM", new SumMutator());
        mutators.put("SUM_INT", new SumIntMutator());

        /* count */
        mutators.put("COUNT", new CountMutator());
        mutators.put("COUNT_ROWS", new CountRowsMutator());

        /* extrema */
        mutators.put("MIN", new MinMutator());
        mutators.put("MAX", new MaxMutator());

        /* boolean */
        mutators.put("BOOL_AND", new BoolAndMutator());
        mutators.put("BOOL_OR", new BoolOrMutator());
        mutators.put("EVERY", new BoolAndMutator());

        /* bit（⚠️ 禁止不安全 rewrite） */
        mutators.put("BIT_AND", new BitAndMutator());
        mutators.put("BIT_OR", new BitOrMutator());
        mutators.put("XOR_AGG", new XorAggMutator());

        /* arrays */
        mutators.put("ARRAY_AGG", new ArrayAggMutator());
        mutators.put("ARRAY_CAT_AGG", new ArrayCatAggMutator());

        /* string */
        mutators.put("STRING_AGG", new StringAggMutator());
        mutators.put("CONCAT_AGG", new ConcatAggMutator());

        /* json（避免 JSON / JSONB 混用） */
        mutators.put("JSON_AGG", new JsonAggMutator());
        mutators.put("JSONB_AGG", new JsonbAggMutator());
        mutators.put("JSON_OBJECT_AGG", new JsonObjectAggMutator());
        mutators.put("JSONB_OBJECT_AGG", new JsonbObjectAggMutator());

        /* statistics */
        mutators.put("STDDEV", new StddevMutator());
        mutators.put("STDDEV_POP", new StddevPopMutator());
        mutators.put("STDDEV_SAMP", new StddevSampMutator());

        mutators.put("VAR_POP", new VarPopMutator());
        mutators.put("VAR_SAMP", new VarSampMutator());
        mutators.put("VARIANCE", new VarSampMutator());

        /* regression */
        mutators.put("CORR", new CorrMutator());
        mutators.put("COVAR_POP", new CovarPopMutator());
        mutators.put("COVAR_SAMP", new CovarSampMutator());

        /* misc */
        mutators.put("SQRDIFF", new SqrdiffMutator());

        /* percentile */
        mutators.put("PERCENTILE_CONT", new PercentileContMutator());
        mutators.put("PERCENTILE_DISC", new PercentileDiscMutator());
    }

    /* ================= AVG ================= */

    static class AvgMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            String upper = after.toUpperCase();

            // 🚫 window function 不动
            if (upper.contains("OVER")) {
                return false;
            }

            // 🚫 明确 interval / string 语义（粗粒度过滤）
            if (upper.contains("INTERVAL")
                    || upper.contains("'")   // 字符串字面量
                    || upper.contains("::INTERVAL")) {
                return false;
            }

            return true;
        }

        public String mutate(String arg) {

            // 👉 保守版本（推荐默认）
            return Randomly.fromOptions(
                    "AVG(" + arg + ")",

                    // ✅ 严格版（带 NULL 语义）
                    "(CASE WHEN COUNT(" + arg + ")=0 THEN NULL " +
                            "ELSE SUM(" + arg + ") / COUNT(" + arg + ") END)"
            );
        }
    }

    /* ================= SUM ================= */

    static class SumMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // 原始 SUM（最安全 fallback）
                    "SUM(" + arg + ")",

                    // NULL-safe version
                    "SUM(CASE WHEN " + arg + " IS NULL THEN NULL ELSE " + arg + " END)",

                    // FIX: 强制 numeric cast，避免 interval/string + 0
                    "SUM((" + arg + ")::FLOAT8)"
            );
        }
    }

    static class SumIntMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "SUM_INT(" + arg + ")",
                    "CAST(SUM(" + arg + ") AS INT)"
            );
        }
    }

    /* ================= COUNT ================= */

    static class CountMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {

            if (arg.trim().equals("*")) {
                return Randomly.fromOptions(
                        "COUNT(*)",
                        "COUNT(1)",
                        "COALESCE(SUM(1),0)"
                );
            }

            return Randomly.fromOptions(
                    "COUNT(" + arg + ")",
                    "COALESCE(SUM(CASE WHEN " + arg + " IS NOT NULL THEN 1 ELSE 0 END),0)"
            );
        }
    }

    static class CountRowsMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "COUNT(*)",
                    "COALESCE(SUM(1),0)"
            );
        }
    }

    /* ================= MIN/MAX ================= */

    static class MinMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return "MIN(" + arg + ")";
        }
    }

    static class MaxMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return "MAX(" + arg + ")";
        }
    }

    /* ================= BOOL（三值逻辑修复） ================= */

    static class BoolAndMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "BOOL_AND(" + arg + ")",
                    "CASE WHEN COUNT(" + arg + ")=0 THEN NULL ELSE " +
                            "MIN(CASE WHEN " + arg + " IS TRUE THEN 1 " +
                            "WHEN " + arg + " IS FALSE THEN 0 END)::BOOL END"
            );
        }
    }

    static class BoolOrMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "BOOL_OR(" + arg + ")",
                    "CASE WHEN COUNT(" + arg + ")=0 THEN NULL ELSE " +
                            "MAX(CASE WHEN " + arg + " IS TRUE THEN 1 " +
                            "WHEN " + arg + " IS FALSE THEN 0 END)::BOOL END"
            );
        }
    }

    /* ================= BIT（禁 rewrite） ================= */

    static class BitAndMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "BIT_AND(" + arg + ")"; }
    }

    static class BitOrMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "BIT_OR(" + arg + ")"; }
    }

    static class XorAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "XOR_AGG(" + arg + ")"; }
    }

    /* ================= ARRAY ================= */

    static class ArrayAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {
            return "ARRAY_AGG(" + arg + ")";
        }
    }

    static class ArrayCatAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "ARRAY_CAT_AGG(" + arg + ")"; }
    }

    /* ================= STRING ================= */

    static class StringAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "STRING_AGG(" + arg + ")"; }
    }

    static class ConcatAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "CONCAT_AGG(" + arg + ")"; }
    }

    /* ================= JSON ================= */

    static class JsonAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSON_AGG(" + arg + ")",
                    "TO_JSONB(ARRAY_AGG(" + arg + "))"
            );
        }
    }

    static class JsonbAggMutator extends JsonAggMutator {}

    static class JsonObjectAggMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "JSON_OBJECT_AGG(" + arg + ")"; }
    }

    static class JsonbObjectAggMutator extends JsonObjectAggMutator {}

    /* ================= STDDEV ================= */

    static class StddevMutator implements CockroachDBFRTOracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String after) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            return "CASE " +
                    "WHEN COUNT(" + arg + ") <= 1 THEN NULL " +
                    "WHEN VAR_SAMP(" + arg + ") IS NULL THEN NULL " +
                    "WHEN VAR_SAMP(" + arg + ") IS NAN THEN 'nan' " +
                    "ELSE SQRT(GREATEST(VAR_SAMP(" + arg + "),0)) " +
                    "END";
        }
    }
    static class StddevPopMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "STDDEV_POP(" + arg + ")"; }
    }

    static class StddevSampMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "STDDEV_SAMP(" + arg + ")"; }
    }

    /* ================= VAR ================= */

    static class VarPopMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            String rewrite =
                    "(CASE WHEN COUNT(" + arg + ")=0 THEN NULL ELSE " +
                            "((SUM((" + arg + ")*(" + arg + ")) - " +
                            "(SUM(" + arg + ")*SUM(" + arg + "))/COUNT(" + arg + ")) " +
                            "/ COUNT(" + arg + ")) END)";

            return Randomly.fromOptions(
                    "VAR_POP(" + arg + ")",
                    // 🔥 关键：类型对齐
                    "CAST(" + rewrite + " AS FLOAT)"
            );
        }
    }

    static class VarSampMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "VAR_SAMP(" + arg + ")"; }
    }

    /* ================= REGRESSION ================= */

    static class CorrMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }

        public String mutate(String arg) {

            String[] parts = arg.split(",", 2);
            if (parts.length < 2) return "CORR(" + arg + ")";

            String x = parts[0];
            String y = parts[1];

            return Randomly.fromOptions(
                    "CORR(" + arg + ")",
                    "COVAR_POP(" + x + "," + y + ") / " +
                            "(STDDEV_POP(" + x + ")*STDDEV_POP(" + y + "))"
            );
        }
    }

    static class CovarPopMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "COVAR_POP(" + arg + ")"; }
    }

    static class CovarSampMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "COVAR_SAMP(" + arg + ")"; }
    }

    /* ================= SQRDIFF ================= */

    static class SqrdiffMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return Randomly.fromOptions(

                    // =========================
                    // fallback（最安全）
                    // =========================
                    "SQRDIFF(" + arg + ")",

                    // =========================
                    // safe algebraic expansion
                    // fully type-normalized to FLOAT8
                    // =========================
                    "SUM(POWER((" + arg + ")::FLOAT8, 2))"
                            + " - (SUM((" + arg + ")::FLOAT8) * SUM((" + arg + ")::FLOAT8))"
                            + " / NULLIF(COUNT(" + arg + "), 0)::FLOAT8"
            );
        }
    }

    /* ================= PERCENTILE ================= */

    static class PercentileContMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "PERCENTILE_CONT(" + arg + ")"; }
    }

    static class PercentileDiscMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) { return "PERCENTILE_DISC(" + arg + ")"; }
    }
}