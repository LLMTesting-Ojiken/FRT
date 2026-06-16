

package sqlancer.cockroachdb.oracle.FRT.Mutator;

import sqlancer.cockroachdb.oracle.FRT.CockroachDBFRTOracle;

import java.util.Map;

public final class CockroachDBFloatFunctionMutators {

    private CockroachDBFloatFunctionMutators() {}

    public static void register(
            Map<String, CockroachDBFRTOracle.FunctionMutator> mutators) {

        /* ================= basic unary ================= */

        mutators.put("ABS", new AbsMutator());

        mutators.put("ACOS", new DomainUnaryMutator("ACOS", -1, 1));
        mutators.put("ACOSD", new DomainUnaryMutator("ACOSD", -1, 1));
        mutators.put("ACOSH", new AcoshMutator("ACOSH"));

        mutators.put("ASIN", new DomainUnaryMutator("ASIN", -1, 1));
        mutators.put("ASIND", new DomainUnaryMutator("ASIND", -1, 1));
        mutators.put("ASINH", new UnaryMutator("ASINH"));

        mutators.put("ATAN", new UnaryMutator("ATAN"));
        mutators.put("ATAND", new UnaryMutator("ATAND"));
        mutators.put("ATANH", new DomainUnaryMutator("ATANH", -1, 1));

        /* ================= trig ================= */

        mutators.put("COS", new UnaryMutator("COS"));
        mutators.put("COSD", new UnaryMutator("COSD"));
        mutators.put("COSH", new UnaryMutator("COSH"));

        mutators.put("SIN", new UnaryMutator("SIN"));
        mutators.put("SIND", new UnaryMutator("SIND"));
        mutators.put("SINH", new UnaryMutator("SINH"));

        mutators.put("TAN", new UnaryMutator("TAN"));
        mutators.put("TAND", new UnaryMutator("TAND"));
        mutators.put("TANH", new UnaryMutator("TANH"));

        mutators.put("COT", new UnaryMutator("COT"));
        mutators.put("COTD", new UnaryMutator("COTD"));

        /* ================= numeric ================= */

        mutators.put("CEIL", new UnaryMutator("CEIL"));
        mutators.put("CEILING", new UnaryMutator("CEILING"));

        mutators.put("FLOOR", new UnaryMutator("FLOOR"));

        mutators.put("SIGN", new SignMutator());

        mutators.put("TRUNC", new TruncMutator());

        mutators.put("ROUND", new RoundMutator());

        mutators.put("EXP", new UnaryMutator("EXP"));

        mutators.put("LN", new LnMutator());

        mutators.put("LOG", new LogMutator());

        mutators.put("SQRT", new SqrtMutator());

        mutators.put("CBRT", new CbrtMutator());

        mutators.put("DEGREES", new UnaryMutator("DEGREES"));
        mutators.put("RADIANS", new UnaryMutator("RADIANS"));

        mutators.put("ISNAN", new IsNaNMutator());

        /* ================= binary ================= */

        mutators.put("MOD", new ModMutator());
        mutators.put("DIV", new DivMutator());

        mutators.put("POW", new PowMutator());
        mutators.put("POWER", new PowMutator());

        /* ================= special ================= */

        mutators.put("PI", new PiMutator());
        mutators.put("RANDOM", new RandomMutator());
        mutators.put("SETSEED", new SetSeedMutator());
    }

    /* =========================================================
     * BASE: safe unary
     * ========================================================= */
    static class UnaryMutator implements CockroachDBFRTOracle.FunctionMutator {

        private final String fn;

        UnaryMutator(String fn) {
            this.fn = fn;
        }

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {
            return fn + "(" + arg + ")";
        }
    }

    /* ================= ABS ================= */

    static class AbsMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " < 0 THEN -" + arg +
                    " ELSE " + arg + " END";
        }
    }

    /* ================= DOMAIN CHECK UNARY ================= */

    static class DomainUnaryMutator implements CockroachDBFRTOracle.FunctionMutator {

        private final String fn;
        private final double min;
        private final double max;

        DomainUnaryMutator(String fn, double min, double max) {
            this.fn = fn;
            this.min = min;
            this.max = max;
        }

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " < " + min + " OR " + arg + " > " + max + " THEN 'nan' " +
                    "ELSE " + fn + "(" + arg + ") END";
        }
    }

    static class AcoshMutator implements CockroachDBFRTOracle.FunctionMutator {

        private final String fn;

        AcoshMutator(String fn) {
            this.fn = fn;
        }

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE " +
                    "WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " < 1 THEN 'NaN'::FLOAT " +
                    "ELSE " + fn + "(" + arg + ") " +
                    "END";
        }
    }

    static class PositiveDomainMutator implements CockroachDBFRTOracle.FunctionMutator {

        private final String fn;

        PositiveDomainMutator(String fn) {
            this.fn = fn;
        }

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " <= 0 THEN NULL " +
                    "ELSE " + fn + "(" + arg + ") END";
        }
    }

    /* ================= SIGN ================= */

    static class SignMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " > 0 THEN 1 " +
                    "WHEN " + arg + " < 0 THEN -1 " +
                    "ELSE 0 END";
        }
    }

    /* ================= SQRT ================= */

    static class SqrtMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " < 0 THEN NULL " +
                    "ELSE SQRT(" + arg + ") END";
        }
    }

    /* ================= CBRT ================= */

    static class CbrtMutator implements CockroachDBFRTOracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String after) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return "CASE " +
                    "WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " >= 0 THEN POWER(" + arg + ", 1.0/3.0) " +
                    "ELSE -POWER(-(" + arg + "), 1.0/3.0) " +
                    "END";
        }
    }

    /* ================= LN ================= */

    static class LnMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {
            return "CASE " +
                    "WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " < 0 THEN ('NaN')::FLOAT8 " +
                    "WHEN " + arg + " = 0 THEN ('-Infinity')::FLOAT8 " +
                    "ELSE LN(" + arg + ") " +
                    "END";
        }
    }

    /* ================= LOG ================= */

    static class LogMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            String[] p = arg.split(",", 2);

            if (p.length == 1) {
                String x = p[0].trim();
                return "CASE WHEN " + x + " <= 0 THEN 'nan' " +
                        "ELSE LN(" + x + ") / LN(10) END";
            }

            String b = p[0].trim();
            String x = p[1].trim();

            return "CASE WHEN " + b + " <= 0 OR " + x + " <= 0 THEN NULL " +
                    "ELSE LN(" + x + ") / LN(" + b + ") END";
        }
    }

    /* ================= MOD ================= */

    static class ModMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            String[] p = arg.split(",", 2);
            if (p.length < 2) return "MOD(" + arg + ")";

            String x = p[0].trim();
            String y = p[1].trim();

            return "CASE WHEN " + x + " IS NULL OR " + y + " IS NULL THEN NULL " +
                    "WHEN " + y + " = 0 THEN NULL " +
                    "ELSE " + x + " - FLOOR(" + x + "/" + y + ")*" + y + " END";
        }
    }

    /* ================= DIV ================= */

    static class DivMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            String[] p = arg.split(",", 2);
            if (p.length < 2) return "DIV(" + arg + ")";

            return "FLOOR(" + p[0] + "/" + p[1] + ")";
        }
    }

    /* ================= POW ================= */

    static class PowMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            String[] p = arg.split(",", 2);
            if (p.length < 2) return "POWER(" + arg + ")";

            String x = p[0].trim();
            String y = p[1].trim();

            return "CASE WHEN " + x + " IS NULL OR " + y + " IS NULL THEN NULL " +
                    "WHEN " + x + " <= 0 THEN POWER(" + x + "," + y + ") " +
                    "ELSE EXP(" + y + " * LN(" + x + ")) END";
        }
    }

    /* ================= TRUNC ================= */

    static class TruncMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {
            return "CASE WHEN " + arg + " >= 0 THEN FLOOR(" + arg + ") ELSE CEIL(" + arg + ") END";
        }
    }

    /* ================= ROUND ================= */

    static class RoundMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {
            return "ROUND(" + arg + ")";
        }
    }

    /* ================= SPECIAL ================= */

    static class PiMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "PI()"; }
    }

    static class RandomMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "RANDOM()"; }
    }

    static class SetSeedMutator implements CockroachDBFRTOracle.FunctionMutator {
        public boolean shouldMutate(String after) { return false; }
        public String mutate(String arg) { return "SETSEED(" + arg + ")"; }
    }
    static class IsNaNMutator implements CockroachDBFRTOracle.FunctionMutator {

        public boolean shouldMutate(String after) {
            return true;
        }

        public String mutate(String arg) {

            return "CASE WHEN " + arg + " IS NULL THEN NULL " +
                    "WHEN " + arg + " <> " + arg + " THEN TRUE " +
                    "ELSE FALSE END";
        }
    }
}