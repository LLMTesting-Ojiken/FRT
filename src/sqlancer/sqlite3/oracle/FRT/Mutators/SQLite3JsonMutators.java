package sqlancer.sqlite3.oracle.FRT.Mutators;

import sqlancer.Randomly;

import java.util.Map;

/**
 * JSON / JSONB 函数等价变异器集合
 *
 * 设计目标：
 * 1. 负责 JSON / JSONB 系列函数的等价变异
 * 2. 不关心 SQL folding / unfolding
 * 3. 通过 register(...) 统一对外暴露
 */
public final class SQLite3JsonMutators {

    private SQLite3JsonMutators() {
        // util class，禁止实例化
    }

    /**
     * 向 mutator map 中注册所有 JSON 函数 mutator
     */
    public static void register(Map<String, SQLite3Function_level_Reconstruction_Oracle.FunctionMutator> mutators) {
        // 标量函数
        mutators.put("JSON", new JsonMutator());
        mutators.put("JSONB", new JsonbMutator());
        mutators.put("JSON_ARRAY", new JsonArrayMutator());
        mutators.put("JSONB_ARRAY", new JsonbArrayMutator());
        mutators.put("JSON_EXTRACT", new JsonExtractMutator());
        mutators.put("JSONB_EXTRACT", new JsonbExtractMutator());
        mutators.put("JSON_INSERT", new JsonSetMutator());
        mutators.put("JSON_REPLACE", new JsonSetMutator());
        mutators.put("JSON_SET", new JsonSetMutator());
        mutators.put("JSONB_INSERT", new JsonbSetMutator());
        mutators.put("JSONB_REPLACE", new JsonbSetMutator());
        mutators.put("JSONB_SET", new JsonbSetMutator());
        mutators.put("JSON_PATCH", new JsonSetMutator());
        mutators.put("JSONB_PATCH", new JsonbSetMutator());
        mutators.put("JSON_PRETTY", new JsonMutator());
        mutators.put("JSON_REMOVE", new JsonSetMutator());
        mutators.put("JSONB_REMOVE", new JsonbSetMutator());
        mutators.put("JSON_TYPE", new JsonMutator());
        mutators.put("JSON_VALID", new JsonMutator());
        mutators.put("JSON_QUOTE", new JsonMutator());

        // 聚合函数
        mutators.put("JSON_GROUP_ARRAY", new JsonGroupArrayMutator());
        mutators.put("JSONB_GROUP_ARRAY", new JsonGroupArrayMutator());
        mutators.put("JSON_GROUP_OBJECT", new JsonGroupObjectMutator());
        mutators.put("JSONB_GROUP_OBJECT", new JsonGroupObjectMutator());
    }

    /* ============================================================
     * ===================== 标量函数 Mutators =====================
     * ============================================================ */

    static class JsonMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSON(" + arg + ")",
                    "CAST(JSONB(" + arg + ") AS TEXT)"
            );
        }
    }

    static class JsonbMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSONB(" + arg + ")",
                    "CAST(JSON(" + arg + ") AS BLOB)"
            );
        }
    }

    static class JsonArrayMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSON_ARRAY(" + arg + ")",
                    "CAST(JSONB_ARRAY(" + arg + ") AS TEXT)"
            );
        }
    }

    static class JsonbArrayMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSONB_ARRAY(" + arg + ")",
                    "CAST(JSON_ARRAY(" + arg + ") AS BLOB)"
            );
        }
    }

    static class JsonExtractMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);
            String json = parts[0].trim();
            String path = parts.length > 1 ? parts[1].trim() : "$";

            return Randomly.fromOptions(
                    "JSON_EXTRACT(" + json + "," + path + ")",
                    json + "->'" + path.replaceAll("^\\$\\.", "") + "'",
                    "CAST(JSONB_EXTRACT(" + json + "," + path + ") AS TEXT)"
            );
        }
    }

    static class JsonbExtractMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSONB_EXTRACT(" + arg + ")",
                    "CAST(JSON_EXTRACT(" + arg + ") AS BLOB)"
            );
        }
    }

    static class JsonSetMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 3);
            if (parts.length < 3) return "JSON_SET(" + arg + ")";

            String x = parts[0].trim();
            String path = parts[1].trim();
            String value = parts[2].trim();

            return Randomly.fromOptions(
                    "JSON_SET(" + x + "," + path + "," + value + ")",
                    "JSON_INSERT(" + x + "," + path + "," + value + ")",
                    "JSON_REPLACE(" + x + "," + path + "," + value + ")",
                    "JSON_PATCH(" + x + ", JSON_OBJECT(" + path + "," + value + "))"
            );
        }
    }

    static class JsonbSetMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSONB_SET(" + arg + ")",
                    "JSONB_INSERT(" + arg + ")",
                    "JSONB_REPLACE(" + arg + ")",
                    "JSONB_PATCH(" + arg + ")"
            );
        }
    }

    /* ============================================================
     * ===================== 聚合函数 Mutators =====================
     * ============================================================ */

    static class JsonGroupArrayMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSON_GROUP_ARRAY(" + arg + ")",
                    "JSONB_GROUP_ARRAY(" + arg + ")"
            );
        }
    }

    static class JsonGroupObjectMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override public boolean shouldMutate(String sqlAfterFunction) { return true; }

        @Override
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "JSON_GROUP_OBJECT(" + arg + ")",
                    "JSONB_GROUP_OBJECT(" + arg + ")"
            );
        }
    }
}