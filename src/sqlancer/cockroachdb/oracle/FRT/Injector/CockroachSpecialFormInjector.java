package sqlancer.cockroachdb.oracle.FRT.Injector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CockroachDB Special Form Injector
 * - Rewrites SQL special forms into equivalent function calls
 * - Designed for FRT (Function-level Rewriting Testing)
 */
public class CockroachSpecialFormInjector {


    /* ================= Special Form Definition ================= */

    private static class SpecialFormDef {

        final String name;

        SpecialFormDef(String name, int formId) {
            this.name = name;
        }
    }

    /* ================= Special Form Patterns ================= */

    private static final List<SpecialFormDef> FORMS = Arrays.asList(

            new SpecialFormDef("AT TIME ZONE", 1),
            new SpecialFormDef("COLLATION FOR", 1),

            new SpecialFormDef("EXTRACT", 1),
            new SpecialFormDef("EXTRACT_DURATION", 1),

            new SpecialFormDef("OVERLAY_1", 1),
            new SpecialFormDef("OVERLAY_2", 1),

            new SpecialFormDef("POSITION", 1),


            new SpecialFormDef("SUBSTRING_1", 1),
            new SpecialFormDef("SUBSTRING_2", 1),
            new SpecialFormDef("SUBSTRING_3", 1),
            new SpecialFormDef("SUBSTRING_4", 1),

            new SpecialFormDef("TRIM_1", 1),
            new SpecialFormDef("TRIM_2", 1),
            new SpecialFormDef("TRIM_3", 1),
            new SpecialFormDef("TRIM_4", 1),
            new SpecialFormDef("TRIM_5", 1),
            new SpecialFormDef("TRIM_6", 1)

    );

    /* ================= Public Entry ================= */

    public static String inject(String sql) {
        return inject(sql, 1);
    }

    public static String inject(String sql, int maxInjectCount) {

        List<Occurrence> occ = findOccurrences(sql);

        if (occ.isEmpty()) {
            return sql;
        }

        Collections.shuffle(occ);

        int injectCount = Math.max(1, Math.min(maxInjectCount, occ.size()));

        List<Occurrence> chosen = occ.subList(0, injectCount);
        chosen.sort((a, b) -> Integer.compare(b.start, a.start));

        for (Occurrence o : chosen) {

            String replacement = buildReplacement(o);

            sql = sql.substring(0, o.start)
                    + replacement
                    + sql.substring(o.end);
        }

        return sql;
    }

    /* ================= Occurrence ================= */

    private static class Occurrence {
        String type;
        String text;
        int start;
        int end;
    }

    /* ================= Find Special Forms ================= */

    private static List<Occurrence> findOccurrences(String sql) {

        List<Occurrence> result = new ArrayList<>();

        for (SpecialFormDef f : FORMS) {

            Pattern p = Pattern.compile("\\b" + Pattern.quote(f.name) + "\\b",
                    Pattern.CASE_INSENSITIVE);

            Matcher m = p.matcher(sql);

            while (m.find()) {

                Occurrence o = new Occurrence();
                o.type = f.name;
                o.text = m.group();
                o.start = m.start();
                o.end = m.end();

                result.add(o);
            }
        }

        return result;
    }

    /* ================= Build Replacement ================= */

    private static String buildReplacement(Occurrence o) {

        String t = o.type.toUpperCase();

        switch (t) {

            case "CURRENT_DATE":
                return "current_date()";

            case "CURRENT_TIME":
                return "current_time()";

            case "CURRENT_TIMESTAMP":
                return "current_timestamp()";

            case "CURRENT_USER":
            case "SESSION_USER":
            case "USER":
                return "current_user()";

            case "CURRENT_SCHEMA":
                return "current_schema()";

            case "CURRENT_ROLE":
                return "current_user()";

            case "CURRENT_CATALOG":
                return "current_catalog()";

            case "AT TIME ZONE":
                return "timezone()";

            case "COLLATION FOR":
                return "pg_collation_for()";

            case "POSITION":
                return "strpos()";

            case "EXTRACT":
                return "extract()";

            case "EXTRACT_DURATION":
                return "extract_duration()";

            case "OVERLAY_1":
                return "overlay()";

            case "OVERLAY_2":
                return "overlay()";

            case "SUBSTRING_1":
            case "SUBSTRING_2":
            case "SUBSTRING_3":
            case "SUBSTRING_4":
                return "substring()";

            case "TRIM_1":
            case "TRIM_2":
            case "TRIM_3":
            case "TRIM_4":
            case "TRIM_5":
            case "TRIM_6":
                return "btrim()";

            default:
                return o.text;
        }
    }
}