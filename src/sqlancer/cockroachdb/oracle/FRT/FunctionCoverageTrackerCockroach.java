package sqlancer.cockroachdb.oracle.FRT;

import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class FunctionCoverageTrackerCockroach {

    /* ============================================================
     * Category
     * ============================================================ */

    public enum Category {
        AGGREGATE
    }

    /* ============================================================
     * Data Structures
     * ============================================================ */

    private final Map<Category, List<String>> functions = new HashMap<>();

    // 已覆盖函数
    private final Map<Category, Set<String>> covered = new HashMap<>();

    // 每个函数调用次数
    private final Map<String, Integer> callCount = new ConcurrentHashMap<>();

    // regex pattern
    private final Map<String, Pattern> patterns = new HashMap<>();

    // 记录 SQL 数量
    private final Set<String> observedSQL = ConcurrentHashMap.newKeySet();

    // 是否输出未覆盖函数
    private final boolean showMissed;

    /* ============================================================
     * Constructors
     * ============================================================ */

    public FunctionCoverageTrackerCockroach() {
        this(false);
    }

    public FunctionCoverageTrackerCockroach(int showMissed) {
        this(showMissed != 0);
    }

    public FunctionCoverageTrackerCockroach(boolean showMissed) {
        this.showMissed = showMissed;
        initFunctions();
        initPatterns();
    }

    /* ============================================================
     * Init
     * ============================================================ */

    private void initFunctions() {

        functions.put(Category.AGGREGATE, Arrays.asList(

                "array_agg",
                "array_cat_agg",
                "avg",
                "bit_and",
                "bit_or",
                "bool_and",
                "bool_or",
                "concat_agg",
                "corr",
                "count",
                "count_rows",
                "covar_pop",
                "covar_samp",
                "every",
                "json_agg",
                "json_object_agg",
                "jsonb_agg",
                "jsonb_object_agg",
                "max",
                "min",
                "percentile_cont",
                "percentile_disc",
                "regr_avgx",
                "regr_avgy",
                "regr_count",
                "regr_intercept",
                "regr_r2",
                "regr_slope",
                "regr_sxx",
                "regr_sxy",
                "regr_syy",
                "sqrdiff",
                "st_collect",
                "st_extent",
                "st_makeline",
                "st_memcollect",
                "st_memunion",
                "st_union",
                "stddev",
                "stddev_pop",
                "stddev_samp",
                "string_agg",
                "sum",
                "sum_int",
                "var_pop",
                "var_samp",
                "variance",
                "xor_agg"
        ));

        for (Category c : functions.keySet()) {
            covered.put(c, ConcurrentHashMap.newKeySet());
        }

        for (List<String> list : functions.values()) {
            for (String f : list) {
                callCount.put(f, 0);
            }
        }
    }

    private void initPatterns() {

        for (Map.Entry<Category, List<String>> e : functions.entrySet()) {

            for (String f : e.getValue()) {

                Pattern p = Pattern.compile(
                        "(?i)\\b" + f + "\\s*\\("
                );

                patterns.put(f, p);
            }
        }
    }

    /* ============================================================
     * Observe SQL
     * ============================================================ */

    public void observe(String sql) {

        if (sql == null) {
            return;
        }

        observedSQL.add(sql);

        for (Map.Entry<Category, List<String>> e : functions.entrySet()) {

            Category cat = e.getKey();

            for (String f : e.getValue()) {

                Pattern p = patterns.get(f);

                if (p.matcher(sql).find()) {

                    covered.get(cat).add(f);

                    callCount.merge(f, 1, Integer::sum);
                }
            }
        }
    }

    /* ============================================================
     * Report
     * ============================================================ */

    public void report() {

        System.out.println();
        System.out.println("=== CockroachDB Function Coverage Report ===");

        for (Category c : Category.values()) {

            List<String> all = functions.get(c);
            Set<String> hitSet = covered.get(c);

            int total = all.size();
            int hit = hitSet.size();

            double ratio = total == 0 ? 0 :
                    (100.0 * hit / total);

            System.out.printf(
                    "%-12s : %d / %d (%.2f%%)%n",
                    c.name(),
                    hit,
                    total,
                    ratio
            );

            System.out.println("  --- Call Frequency ---");

            for (String f : all) {

                int count = callCount.getOrDefault(f, 0);

                if (count > 0) {

                    System.out.printf(
                            "  %-20s %d%n",
                            f,
                            count
                    );
                }
            }

            if (showMissed) {

                List<String> missed = new ArrayList<>();

                for (String f : all) {
                    if (!hitSet.contains(f)) {
                        missed.add(f);
                    }
                }

                if (!missed.isEmpty()) {

                    System.out.println();
                    System.out.println("  ❌ Not Covered:");

                    for (String m : missed) {
                        System.out.println("    " + m);
                    }
                }
            }

            System.out.println();
        }

        System.out.println(
                "Distinct SQL Observed: "
                        + observedSQL.size()
        );
    }

    /* ============================================================
     * Get uncovered functions
     * ============================================================ */

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