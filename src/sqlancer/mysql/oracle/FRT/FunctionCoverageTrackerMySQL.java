package sqlancer.mysql.oracle.FRT;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class FunctionCoverageTrackerMySQL {

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

    // 记录 SQL 数量（不影响统计）
    private final Set<String> observedSQL = ConcurrentHashMap.newKeySet();

    // 是否输出未覆盖函数
    private final boolean showMissed;

    /* ============================================================
     * Constructors
     * ============================================================ */

    public FunctionCoverageTrackerMySQL() {
        this(false);
    }

    public FunctionCoverageTrackerMySQL(int showMissed) {
        this(showMissed != 0);
    }

    public FunctionCoverageTrackerMySQL(boolean showMissed) {
        this.showMissed = showMissed;
        initFunctions();
        initPatterns();
    }

    /* ============================================================
     * Init
     * ============================================================ */

    private void initFunctions() {

        functions.put(Category.AGGREGATE, Arrays.asList(
                "avg",
                "bit_and",
                "bit_or",
                "bit_xor",
                "count",
                "group_concat",
                "json_arrayagg",
                "json_objectagg",
                "max",
                "min",
                "std",
                "stddev",
                "stddev_pop",
                "stddev_samp",
                "sum",
                "var_pop",
                "var_samp",
                "variance"
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
        System.out.println("=== MySQL Function Coverage Report ===");

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
                            "  %-18s %d%n",
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

                        System.out.println(
                                "    " + m
                        );
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
     * Get uncovered functions (for program use)
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