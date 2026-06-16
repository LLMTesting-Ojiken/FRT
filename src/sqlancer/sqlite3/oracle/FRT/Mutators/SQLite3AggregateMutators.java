package sqlancer.sqlite3.oracle.FRT.Mutators;

import sqlancer.Randomly;

import java.util.*;


/**
 * 聚合函数等价变异器集合
 * <p>
 * 设计目标：
 * 1. 只负责「聚合函数」的等价变异
 * 2. 不关心 SQL folding / unfolding 机制
 * 3. 不关心 FunctionMutatorEngine 的替换策略
 * 4. 通过 register(...) 统一对外暴露
 * <p>
 * 未来可平行扩展：
 * - CoreFunctionMutators
 * - MathFunctionMutators
 * - StringFunctionMutators
 */
public final class SQLite3AggregateMutators {

    private SQLite3AggregateMutators() {
        // util class，禁止实例化
    }

    /**
     * 向 mutator map 中注册所有聚合函数 mutator
     */
    public static void register(Map<String, SQLite3Function_level_Reconstruction_Oracle.FunctionMutator> mutators) {
        mutators.put("AVG", new AvgMutator());
        mutators.put("TOTAL", new TotalMutator());
        mutators.put("SUM", new SumMutator());
        mutators.put("MIN", new MinMutator());
        mutators.put("MAX", new MaxMutator());
        mutators.put("COUNT", new CountXMutator());
        mutators.put("GROUP_CONCAT", new GroupConcatMutator());
        mutators.put("MEDIAN", new MedianMutator());
        mutators.put("PERCENTILE", new PercentileMutator());
        mutators.put("PERCENTILE_CONT", new PercentileContMutator());
        mutators.put("PERCENTILE_DISC", new PercentileDiscMutator());
    }

    /* ============================================================
     * ===================== AVG =====================
     * ============================================================ */

    static class AvgMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            // 避免 window function
            return !sqlAfterFunction.toUpperCase().matches("^\\s*OVER\\b.*");
        }

        @Override
        public String mutate(String arg) {
            return String.format(
                    "(CASE WHEN COUNT(%1$s)=0 THEN NULL ELSE TOTAL(%1$s)/COUNT(%1$s) END)",
                    arg
            );
        }
    }

    /* ============================================================
     * ===================== TOTAL =====================
     * ============================================================ */

    static class TotalMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !sqlAfterFunction.toUpperCase().contains("OVER");
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();
            return String.format("COALESCE(SUM(CAST(%s AS REAL)), 0.0)", x);
        }
    }

    /* ============================================================
     * ===================== SUM =====================
     * ============================================================ */

    static class SumMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !sqlAfterFunction.toUpperCase().contains("OVER");
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();
            return String.format(
                    "(CASE WHEN COUNT(%1$s) = 0 THEN NULL ELSE SUM(%1$s) END)",
                    x
            );
        }
    }

    /* ============================================================
     * ===================== COUNT =====================
     * ============================================================ */

    static class CountXMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            // 避免 window function
            return !sqlAfterFunction.toUpperCase().matches("^\\s*OVER\\b.*");
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();
            if (arg.equals("*")) {
                return Randomly.fromOptions(
                        "(COALESCE(SUM(1),0))",
                        "(COALESCE(SUM(CASE WHEN TRUE THEN 1 ELSE 0 END),0))",
                        "(TOTAL(1))"
                );
            } else {
                return String.format(
                        "(COALESCE(SUM(CASE WHEN %s IS NOT NULL THEN 1 ELSE 0 END),0))",
                        arg
                );
            }
        }
    }

    /* ============================================================
     * ===================== MIN =====================
     * ============================================================ */

    static class MinMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !sqlAfterFunction.toUpperCase().matches("^\\s*OVER\\b.*");
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();

            // 检查是否是多列：简单判断是否包含逗号
            if (trimmed.contains(",")) {
                return "MIN(" + trimmed + ")";
            } else {
                // 单列 -> 原先逻辑
                return "MIN(CASE WHEN " + trimmed + " IS NULL THEN NULL ELSE " + trimmed + " END)";
            }
        }
    }

    /* ============================================================
     * ===================== MAX =====================
     * ============================================================ */

    static class MaxMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return !sqlAfterFunction.toUpperCase().matches("^\\s*OVER\\b.*");
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();
            if (trimmed.contains(",")) {
                return "MAX(" + trimmed + ")";
            } else {
                // 单列 -> 原先逻辑
                return "MAX(CASE WHEN " + trimmed + " IS NULL THEN NULL ELSE " + trimmed + " END)";
            }
        }
    }

    /* ============================================================
     * ===================== GROUP_CONCAT / STRING_AGG =====================
     * ============================================================ */

    static class GroupConcatMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            // SQLite 的 group_concat 本身不支持 window
            return true;
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();
            return Randomly.fromOptions(
                    "GROUP_CONCAT(" + trimmed + ", ',')",
                    "STRING_AGG(" + trimmed + ", ',')"
            );
        }
    }

    /* ============================================================
     * ===================== MEDIAN =====================
     * ============================================================ */
    static class MedianMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            // SQLite 的 group_concat 本身不支持 window
            return true;
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();
            return Randomly.fromOptions(
                    "PERCENTILE_CONT(" + trimmed + ", 0.5)"
            );
        }
    }

    static class PercentileMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // percentile(Y,P) 等价于 percentile_cont(Y, P/100.0)
            String[] parts = arg.split(",");
            String col = parts[0].trim();
            String p = parts[1].trim();
            return String.format("PERCENTILE_CONT(%s, %s/100.0)", col, p);
        }
    }

    static class PercentileContMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // percentile_cont(Y,P) 保持原样
            String[] parts = arg.split(",");
            String col = parts[0].trim();
            String p = parts[1].trim();
            return String.format("PERCENTILE_CONT(%s, %s)", col, p);
        }
    }

    static class PercentileDiscMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // percentile_disc(Y,P) 返回离散值（向下取整的中位数）
            String[] parts = arg.split(",");
            String col = parts[0].trim();
            String p = parts[1].trim();
            return String.format(
                    "(SELECT col FROM t0 WHERE %s IS NOT NULL ORDER BY %s ASC LIMIT 1 OFFSET CAST(FLOOR((SELECT COUNT(*) FROM t0 WHERE %s IS NOT NULL) * %s) AS INTEGER))",
                    col, col, col, p
            );
        }
    }
}

