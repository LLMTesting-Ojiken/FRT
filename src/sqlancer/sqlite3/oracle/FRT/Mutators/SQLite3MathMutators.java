package sqlancer.sqlite3.oracle.FRT.Mutators;

import sqlancer.Randomly;

import java.util.Map;

/**
 * SQLite3 Math Function Mutators
 * <p>
 * 工业级版本：
 * 1. 统一 epsilon 精度容忍
 * 2. 保证 SQLite NULL / domain semantics
 * 3. 防止 NaN / Inf 传播
 * 4. 所有重写均为等价变换
 */
public final class SQLite3MathMutators {

    private SQLite3MathMutators() {
    }

    public static void register(Map<String,
            SQLite3Function_level_Reconstruction_Oracle.FunctionMutator> mutators) {

        mutators.put("ACOS", new AcosMutator());
        mutators.put("ASIN", new AsinMutator());
        mutators.put("ATAN", new AtanMutator());
        mutators.put("ATAN2", new Atan2Mutator());
        mutators.put("ACOSH", new AcoshMutator());
        mutators.put("ASINH", new AsinhMutator());
        mutators.put("ATANH", new AtanhMutator());

        mutators.put("SIN", new SinMutator());
        mutators.put("COS", new CosMutator());
        mutators.put("TAN", new TanMutator());

        mutators.put("SINH", new SinhMutator());
        mutators.put("COSH", new CoshMutator());
        mutators.put("TANH", new TanhMutator());

        mutators.put("EXP", new ExpMutator());
        mutators.put("LN", new LnMutator());
        mutators.put("LOG", new LogMutator());
        mutators.put("LOG10", new Log10Mutator());
        mutators.put("LOG2", new Log2Mutator());

        mutators.put("SQRT", new SqrtMutator());

        mutators.put("CEIL", new CeilMutator());
        mutators.put("CEILING", new CeilingMutator());
        mutators.put("FLOOR", new FloorMutator());
        mutators.put("TRUNC", new TruncMutator());

        mutators.put("MOD", new ModMutator());

        mutators.put("PI", new PiMutator());

        mutators.put("POW", new PowMutator());
        mutators.put("POWER", new PowerMutator());

        mutators.put("DEGREES", new DegreesMutator());
        mutators.put("RADIANS", new RadiansMutator());
    }

    /**
     * 工业级 numeric guard
     * <p>
     * 防止
     * - NULL
     * - domain error
     * - 非数值
     * - 浮点误差
     */
    static String numericGuard(String x, String original, String mutated) {

        String eps = "1e-7";

        return String.format(
                "(CASE " +

                        "WHEN %s IS NULL THEN NULL " +

                        "WHEN typeof(0.0 + %s) NOT IN ('integer','real') THEN %s " +

                        "WHEN %s IS NULL THEN NULL " +

                        "WHEN %s IS NULL THEN %s " +

                        "WHEN ABS((%s) - (%s)) <= %s * MAX(1, ABS(%s)) THEN %s " +

                        "ELSE %s END)",

                x,
                x, original,
                original,
                mutated, original,
                mutated, original, eps, original, original,
                mutated
        );
    }
    /* ================= ACOS ================= */

    static class AcosMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ACOS(" + x + ")";

            String expd = Randomly.fromOptions(
                    "ATAN2(SQRT(1-" + x + "*" + x + ")," + x + ")",
                    "PI()/2-ASIN(" + x + ")"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= ASIN ================= */

    static class AsinMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ASIN(" + x + ")";

            String expd = Randomly.fromOptions(
                    "ATAN(" + x + "/SQRT(1-" + x + "*" + x + "))",
                    "PI()/2-ACOS(" + x + ")"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= ATAN ================= */

    static class AtanMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ATAN(" + x + ")";

            String expd = Randomly.fromOptions(
                    "ATAN2(" + x + ",1)",
                    "ASIN(" + x + "/SQRT(1+" + x + "*" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= ATAN2 ================= */

    static class Atan2Mutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String[] parts = arg.split(",", 2);

            if (parts.length < 2) {
                return "ATAN2(" + arg + ")";
            }

            String y = parts[0].trim();
            String x = parts[1].trim();

            return Randomly.fromOptions(
                    "ATAN(" + y + "/" + x + ")",
                    "ATAN2(" + y + "," + x + ")"
            );
        }
    }

    /* ================= ACOSH ================= */

    static class AcoshMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ACOSH(" + x + ")";

            String expd = "LN(" + x + "+SQRT(" + x + "*" + x + "-1))";

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= ASINH ================= */

    static class AsinhMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ASINH(" + x + ")";

            String expd = "LN(" + x + "+SQRT(" + x + "*" + x + "+1))";

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= ATANH ================= */

    static class AtanhMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "ATANH(" + x + ")";

            String expd = "0.5*LN((1+" + x + ")/(1-" + x + "))";

            return numericGuard(x, orig, expd);
        }
    }
    /* ================= SIN ================= */

    static class SinMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "SIN(" + x + ")";

            String expd = Randomly.fromOptions(
                    "COS(PI()/2-(" + x + "))",
                    "-SIN(-(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= COS ================= */

    static class CosMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "COS(" + x + ")";

            String expd = Randomly.fromOptions(
                    "SIN(PI()/2-(" + x + "))",
                    "COS(-(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= TAN ================= */

    static class TanMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "TAN(" + x + ")";

            String expd = Randomly.fromOptions(
                    "SIN(" + x + ")/COS(" + x + ")",
                    "-TAN(-(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= SINH ================= */

    static class SinhMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "SINH(" + x + ")";

            String expd = Randomly.fromOptions(
                    "(EXP(" + x + ")-EXP(-" + x + "))/2",
                    "TANH(" + x + ")*COSH(" + x + ")"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= COSH ================= */

    static class CoshMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "COSH(" + x + ")";

            String expd = Randomly.fromOptions(
                    "(EXP(" + x + ")+EXP(-" + x + "))/2",
                    "COSH(-(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= TANH ================= */

    static class TanhMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "TANH(" + x + ")";

            String expd = Randomly.fromOptions(
                    "(EXP(" + x + ")-EXP(-" + x + "))/(EXP(" + x + ")+EXP(-" + x + "))",
                    "SINH(" + x + ")/COSH(" + x + ")",
                    "-TANH(-(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= EXP ================= */

    static class ExpMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "POW(EXP(1)," + x + ")";
        }
    }

    /* ================= LN ================= */

    static class LnMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "LN(" + x + ")";

            String expd = "LOG(" + x + ")/LOG(EXP(1))";

            return numericGuard(x, orig, expd);
        }
    }

    /* ================= LOG ================= */

    static class LogMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String[] parts = arg.split(",");

            if (parts.length == 1) {

                String x = parts[0].trim();

                return "LOG10(" + x + ")";
            }

            String b = parts[0].trim();
            String x = parts[1].trim();

            return "LOG10(" + x + ")/LOG10(" + b + ")";
        }
    }

    /* ================= LOG10 ================= */

    static class Log10Mutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "LN(" + x + ")/LN(10)";
        }
    }

    /* ================= LOG2 ================= */

    static class Log2Mutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "LN(" + x + ")/LN(2)";
        }
    }

    /* ================= SQRT ================= */

    static class SqrtMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String orig = "SQRT(" + x + ")";

            String expd = Randomly.fromOptions(
                    "POW(" + x + ",0.5)",
                    "EXP(0.5*LN(" + x + "))"
            );

            return numericGuard(x, orig, expd);
        }
    }
    /* ================= CEIL ================= */

    static class CeilMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return Randomly.fromOptions(
                    "CEILING(" + x + ")",
                    "-FLOOR(-(" + x + "))"
            );
        }
    }

    /* ================= CEILING ================= */

    static class CeilingMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "-FLOOR(-(" + x + "))";
        }
    }

    /* ================= FLOOR ================= */

    static class FloorMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "-CEIL(-(" + x + "))";
        }
    }

    /* ================= TRUNC ================= */

    static class TruncMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "SIGN(" + x + ") * FLOOR(ABS(" + x + "))";
        }
    }

    /* ================= MOD ================= */

    static class ModMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String[] parts = arg.split(",", 2);

            if (parts.length < 2) {
                return "MOD(" + arg.trim() + ")";
            }

            String x = parts[0].trim();
            String y = parts[1].trim();

            return x + " - " + y + " * TRUNC(" + x + "/" + y + ")";
        }
    }

    /* ================= PI ================= */

    static class PiMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            return "3.141592653589793";
        }
    }

    /* ================= POW ================= */

    static class PowMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String[] parts = arg.split(",", 2);

            if (parts.length < 2) {
                return "POW(" + arg.trim() + ")";
            }

            String x = parts[0].trim();
            String y = parts[1].trim();

            return Randomly.fromOptions(
                    "POW(" + x + "," + y + ")",
                    "EXP(" + y + "*LN(" + x + "))"
            );
        }
    }

    /* ================= POWER ================= */

    static class PowerMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String[] parts = arg.split(",", 2);

            if (parts.length < 2) {
                return "POWER(" + arg.trim() + ")";
            }

            String x = parts[0].trim();
            String y = parts[1].trim();

            return "POW(" + x + "," + y + ")";
        }
    }

    /* ================= DEGREES ================= */

    static class DegreesMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "(" + x + " * 180 / PI())";
        }
    }

    /* ================= RADIANS ================= */

    static class RadiansMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            return "(" + x + " * PI() / 180)";
        }
    }

}