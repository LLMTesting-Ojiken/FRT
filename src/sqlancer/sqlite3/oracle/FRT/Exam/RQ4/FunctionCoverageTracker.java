package sqlancer.sqlite3.oracle.FRT.Exam.RQ4;

import java.util.*;
import java.util.regex.*;

public class FunctionCoverageTracker {

    enum Category {
        DATE_TIME,
        AGGREGATE,
        WINDOW,
        MATH,
        JSON,
        CORE
    }

    private final Map<Category, List<String>> functions = new HashMap<>();
    private final Map<Category, Set<String>> covered = new HashMap<>();
    private final Map<String, Pattern> patterns = new HashMap<>();

    // ===== 新增：控制是否输出未覆盖函数 =====
    private final boolean showMissed;

    // 默认：不显示未覆盖函数
    public FunctionCoverageTracker() {
        this(false);
    }

    // 你要的 0/1 构造方式
    public FunctionCoverageTracker(int showMissed) {
        this(showMissed != 0);
    }

    // 实际使用的构造器
    public FunctionCoverageTracker(boolean showMissed) {
        this.showMissed = showMissed;
        initFunctions();
        initPatterns();
    }

    private void initFunctions() {
        functions.put(Category.DATE_TIME, Arrays.asList(
                "date", "time", "datetime", "julianday",
                "unixepoch", "strftime", "timediff"
        ));

        functions.put(Category.AGGREGATE, Arrays.asList(
                "avg", "count", "group_concat", "max", "median",
                "min", "percentile", "percentile_cont", "percentile_disc",
                "string_agg", "sum", "total"
        ));

        functions.put(Category.WINDOW, Arrays.asList(
                "over"
        ));

        functions.put(Category.MATH, Arrays.asList(
                "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh",
                "ceil", "ceiling", "cos", "cosh", "degrees", "exp", "floor",
                "ln", "log", "log10", "log2", "mod", "pi", "pow", "power",
                "radians", "sin", "sinh", "sqrt", "tan", "tanh", "trunc"
        ));

        functions.put(Category.CORE, Arrays.asList(
                "abs",
                "changes",
                "char",
                "coalesce",
                "concat",
                "concat_ws",
                "format",
                "glob",
                "hex",
                "if",
                "ifnull",
                "iif",
                "instr",
                "last_insert_rowid",
                "length",
                "like",
                "likelihood",
                "likely",
                "load_extension",
                "lower",
                "ltrim",
                "max",
                "min",
                "nullif",
                "octet_length",
                "printf",
                "quote",
                "random",
                "randomblob",
                "replace",
                "round",
                "rtrim",
                "sign",
                "soundex",
                "sqlite_compileoption_get",
                "sqlite_compileoption_used",
                "sqlite_offset",
                "sqlite_source_id",
                "sqlite_version",
                "substr",
                "substring",
                "total_changes",
                "trim",
                "typeof",
                "unhex",
                "unicode",
                "unistr",
                "unistr_quote",
                "unlikely",
                "upper",
                "zeroblob"
        ));

        functions.put(Category.JSON, Arrays.asList(
                "json", "jsonb", "json_array", "jsonb_array", "json_array_length",
                "json_error_position", "json_extract", "jsonb_extract",
                "json_insert", "jsonb_insert", "json_object", "jsonb_object",
                "json_patch", "jsonb_patch", "json_pretty", "json_remove",
                "jsonb_remove", "json_replace", "jsonb_replace", "json_set",
                "jsonb_set", "json_type", "json_valid", "json_quote",
                "json_group_array", "jsonb_group_array",
                "json_group_object", "jsonb_group_object",
                "json_each", "json_tree", "jsonb_each", "jsonb_tree"
        ));

        for (Category c : functions.keySet()) {
            covered.put(c, new HashSet<>());
        }
    }

    private void initPatterns() {
        for (Map.Entry<Category, List<String>> e : functions.entrySet()) {
            for (String f : e.getValue()) {
                Pattern p = Pattern.compile("\\b" + f + "\\s*\\(",
                        Pattern.CASE_INSENSITIVE);
                patterns.put(f, p);
            }
        }
    }

    /**
     * 唯一需要调用的方法
     */
    public void observe(String sql) {
        if (sql == null) return;
        String s = sql.toLowerCase();

        for (Map.Entry<Category, List<String>> e : functions.entrySet()) {
            Category cat = e.getKey();
            for (String f : e.getValue()) {
                Pattern p = patterns.get(f);
                if (p.matcher(s).find()) {
                    covered.get(cat).add(f);
                }
            }
        }
    }

    /**
     * 输出覆盖率（根据开关决定是否输出未覆盖函数）
     */
    public void report() {
        System.out.println("=== Function Coverage Report ===");

        for (Category c : Category.values()) {
            List<String> all = functions.get(c);
            Set<String> hitSet = covered.get(c);

            int total = all.size();
            int hit = hitSet.size();

            System.out.printf(
                    "%-10s : %d / %d%n",
                    c.name(), hit, total
            );

            if (!showMissed) {
                continue; // 关闭未覆盖输出
            }

            List<String> missed = new ArrayList<>();
            for (String f : all) {
                if (!hitSet.contains(f)) {
                    missed.add(f);
                }
            }

            if (!missed.isEmpty()) {
                System.out.println("  ❌ Not covered:");
                System.out.println("    " + String.join(", ", missed));
            } else {
                System.out.println("  ✅ All covered");
            }
        }
    }

    /**
     * 获取未覆盖函数（不受开关影响，给程序用）
     */
    public Map<Category, List<String>> getUncoveredFunctions() {
        Map<Category, List<String>> result = new HashMap<>();

        for (Category c : Category.values()) {
            List<String> all = functions.get(c);
            Set<String> hit = covered.get(c);

            List<String> missed = new ArrayList<>();
            for (String f : all) {
                if (!hit.contains(f)) {
                    missed.add(f);
                }
            }

            result.put(c, missed);
        }
        return result;
    }
}