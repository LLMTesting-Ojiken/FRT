package sqlancer.cockroachdb.oracle.FRT.Mutator;

import sqlancer.Randomly;
import sqlancer.cockroachdb.oracle.FRT.CockroachDBFRTOracle;

import java.util.Map;

public final class CockroachDBSpecialFormMutators {

    private CockroachDBSpecialFormMutators() {}

    public static void register(
            Map<String, CockroachDBFRTOracle.FunctionMutator> mutators) {

        /* ===== TIME ===== */
        mutators.put("AT_TIME_ZONE", new AtTimeZoneMutator());

        /* ===== SYSTEM ===== */
        mutators.put("CURRENT_CATALOG", new CurrentCatalogMutator());
        mutators.put("CURRENT_DATE", new CurrentDateMutator());
        mutators.put("CURRENT_ROLE", new CurrentRoleMutator());
        mutators.put("CURRENT_SCHEMA", new CurrentSchemaMutator());
        mutators.put("CURRENT_TIMESTAMP", new CurrentTimestampMutator());
        mutators.put("CURRENT_TIME", new CurrentTimeMutator());
        mutators.put("CURRENT_USER", new CurrentUserMutator());
        mutators.put("SESSION_USER", new SessionUserMutator());
        mutators.put("USER", new UserMutator());

        /* ===== COLLATION ===== */
        mutators.put("COLLATION_FOR", new CollationForMutator());

        /* ===== EXTRACT ===== */
        mutators.put("EXTRACT", new ExtractMutator());
        mutators.put("EXTRACT_DURATION", new ExtractDurationMutator());

        /* ===== STRING ===== */
        mutators.put("OVERLAY", new OverlayMutator());
        mutators.put("POSITION", new PositionMutator());

        mutators.put("SUBSTRING_FOR_FROM", new SubstringForFromMutator());
        mutators.put("SUBSTRING_FOR", new SubstringForMutator());
        mutators.put("SUBSTRING_FROM_FOR", new SubstringFromForMutator());
        mutators.put("SUBSTRING_FROM", new SubstringFromMutator());

        /* ===== TRIM FULL SET ===== */
        mutators.put("TRIM_BOTH", new TrimBothMutator());
        mutators.put("TRIM_SIMPLE", new TrimSimpleMutator());
        mutators.put("TRIM_FROM", new TrimFromMutator());
        mutators.put("TRIM_LEADING", new TrimLeadingMutator());
        mutators.put("TRIM_LEADING_FROM", new TrimLeadingFromMutator());
        mutators.put("TRIM_TRAILING", new TrimTrailingMutator());
        mutators.put("TRIM_TRAILING_FROM", new TrimTrailingFromMutator());
    }

    /* ================= AT TIME ZONE ================= */

    static class AtTimeZoneMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("AT TIME ZONE"); }

        public String mutate(String arg) {
            String[] parts = arg.split("AT TIME ZONE");
            if (parts.length != 2) return arg;

            String ts = parts[0].trim();
            String zone = parts[1].trim();

            return Randomly.fromOptions(
                    ts + " AT TIME ZONE " + zone,
                    "timezone(" + zone + ", " + ts + ")"
            );
        }
    }

    /* ================= SYSTEM ================= */

    static class CurrentCatalogMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_CATALOG", "current_catalog()");
        }
    }

    static class CurrentDateMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_DATE", "current_date()");
        }
    }

    static class CurrentRoleMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_ROLE", "current_user()");
        }
    }

    static class CurrentSchemaMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_SCHEMA", "current_schema()");
        }
    }

    static class CurrentTimestampMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_TIMESTAMP", "current_timestamp()");
        }
    }

    static class CurrentTimeMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_TIME", "current_time()");
        }
    }

    static class CurrentUserMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("CURRENT_USER", "current_user()");
        }
    }

    static class SessionUserMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("SESSION_USER", "current_user()");
        }
    }

    static class UserMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return true; }
        public String mutate(String arg) {
            return Randomly.fromOptions("USER", "current_user()");
        }
    }

    /* ================= COLLATION ================= */

    static class CollationForMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("COLLATION FOR"); }

        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "COLLATION FOR (" + arg + ")",
                    "pg_collation_for(" + arg + ")"
            );
        }
    }

    /* ================= EXTRACT ================= */

    static class ExtractMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("EXTRACT"); }

        public String mutate(String arg) {
            String[] parts = arg.split("FROM");
            if (parts.length != 2) return "EXTRACT(" + arg + ")";

            return Randomly.fromOptions(
                    "EXTRACT(" + arg + ")",
                    "extract('" + parts[0].trim() + "', " + parts[1].trim() + ")"
            );
        }
    }

    static class ExtractDurationMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("EXTRACT_DURATION"); }

        public String mutate(String arg) {
            String[] parts = arg.split("FROM");
            if (parts.length != 2) return "EXTRACT_DURATION(" + arg + ")";

            return Randomly.fromOptions(
                    "EXTRACT_DURATION(" + arg + ")",
                    "extract_duration('" + parts[0].trim() + "', " + parts[1].trim() + ")"
            );
        }
    }

    /* ================= OVERLAY ================= */

    static class OverlayMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("OVERLAY"); }

        public String mutate(String arg) {
            // 两种形式
            if (arg.contains("FOR")) {
                String[] parts = arg.split("PLACING|FROM|FOR");
                if (parts.length == 4) {
                    return Randomly.fromOptions(
                            "OVERLAY(" + arg + ")",
                            "overlay(" + parts[0].trim() + "," +
                                    parts[1].trim() + "," +
                                    parts[2].trim() + "," +
                                    parts[3].trim() + ")"
                    );
                }
            } else {
                String[] parts = arg.split("PLACING|FROM");
                if (parts.length == 3) {
                    return Randomly.fromOptions(
                            "OVERLAY(" + arg + ")",
                            "overlay(" + parts[0].trim() + "," +
                                    parts[1].trim() + "," +
                                    parts[2].trim() + ")"
                    );
                }
            }
            return "OVERLAY(" + arg + ")";
        }
    }

    /* ================= POSITION ================= */

    static class PositionMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("POSITION"); }

        public String mutate(String arg) {
            String[] parts = arg.split("IN");
            if (parts.length != 2) return "POSITION(" + arg + ")";

            return Randomly.fromOptions(
                    "POSITION(" + arg + ")",
                    "strpos(" + parts[1].trim() + "," + parts[0].trim() + ")"
            );
        }
    }

    /* ================= SUBSTRING FULL ================= */

    static class SubstringForFromMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("SUBSTRING") && after.contains("FOR") && after.contains("FROM"); }

        public String mutate(String arg) {
            String[] p = arg.split("FOR|FROM");
            if (p.length != 3) return "SUBSTRING(" + arg + ")";
            return Randomly.fromOptions(
                    "SUBSTRING(" + arg + ")",
                    "substring(" + p[0].trim() + "," + p[2].trim() + "," + p[1].trim() + ")"
            );
        }
    }

    static class SubstringForMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("SUBSTRING") && after.contains("FOR") && !after.contains("FROM"); }

        public String mutate(String arg) {
            String[] p = arg.split("FOR");
            if (p.length != 2) return "SUBSTRING(" + arg + ")";
            return Randomly.fromOptions(
                    "SUBSTRING(" + arg + ")",
                    "substring(" + p[0].trim() + ",1," + p[1].trim() + ")"
            );
        }
    }

    static class SubstringFromForMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("SUBSTRING") && after.contains("FROM") && after.contains("FOR"); }

        public String mutate(String arg) {
            String[] p = arg.split("FROM|FOR");
            if (p.length != 3) return "SUBSTRING(" + arg + ")";
            return Randomly.fromOptions(
                    "SUBSTRING(" + arg + ")",
                    "substring(" + p[0].trim() + "," + p[1].trim() + "," + p[2].trim() + ")"
            );
        }
    }

    static class SubstringFromMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("SUBSTRING") && after.contains("FROM") && !after.contains("FOR"); }

        public String mutate(String arg) {
            String[] p = arg.split("FROM");
            if (p.length != 2) return "SUBSTRING(" + arg + ")";
            return Randomly.fromOptions(
                    "SUBSTRING(" + arg + ")",
                    "substring(" + p[0].trim() + "," + p[1].trim() + ")"
            );
        }
    }

    /* ================= TRIM FULL ================= */

    static class TrimBothMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("TRIM") && after.contains(","); }
        public String mutate(String arg) {
            String[] p = arg.split(",");
            return Randomly.fromOptions(
                    "TRIM(" + arg + ")",
                    "btrim(" + p[0].trim() + "," + p[1].trim() + ")"
            );
        }
    }

    static class TrimSimpleMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.equalsIgnoreCase("TRIM"); }
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "TRIM(" + arg + ")",
                    "btrim(" + arg + ")"
            );
        }
    }

    static class TrimFromMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("TRIM") && after.contains("FROM"); }
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "TRIM(" + arg + ")",
                    "btrim(" + arg + ")"
            );
        }
    }

    static class TrimLeadingMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("LEADING"); }
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "TRIM(" + arg + ")",
                    "ltrim(" + arg + ")"
            );
        }
    }

    static class TrimLeadingFromMutator extends TrimLeadingMutator {}

    static class TrimTrailingMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return after.contains("TRAILING"); }
        public String mutate(String arg) {
            return Randomly.fromOptions(
                    "TRIM(" + arg + ")",
                    "rtrim(" + arg + ")"
            );
        }
    }

    static class TrimTrailingFromMutator extends TrimTrailingMutator {}
}