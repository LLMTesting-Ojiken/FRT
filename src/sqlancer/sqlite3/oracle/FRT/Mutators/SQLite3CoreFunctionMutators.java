package sqlancer.sqlite3.oracle.FRT.Mutators;

import sqlancer.Randomly;

import java.util.*;

/**
 * Core Function 等价变异器集合
 * <p>
 * 设计目标：
 * 1. 只负责「Core Scalar 函数」的等价变异
 * 2. 不关心 SQL folding / unfolding 机制
 * 3. 不关心 FunctionMutatorEngine 的替换策略
 * 4. 通过 register(...) 统一对外暴露
 */
public final class SQLite3CoreFunctionMutators {

    private SQLite3CoreFunctionMutators() {
        // util class，禁止实例化
    }

    /**
     * 向 mutator map 中注册所有 Core 函数 mutator
     */
    public static void register(Map<String, SQLite3Function_level_Reconstruction_Oracle.FunctionMutator> mutators) {
        mutators.put("ABS", new AbsMutator());
        mutators.put("COALESCE", new CoalesceMutator());
        mutators.put("CONCAT", new ConcatMutator());
        mutators.put("CONCAT_WS", new ConcatWsMutator());
        mutators.put("LOWER", new LowerMutator());
        mutators.put("UPPER", new UpperMutator());
        mutators.put("LTRIM", new LtrimMutator());
        mutators.put("PRINTF", new PrintfMutator());
        mutators.put("RTRIM", new RtrimMutator());
        mutators.put("TRIM", new TrimMutator());
        mutators.put("ROUND", new RoundMutator());
        mutators.put("SIGN", new SignMutator());
        mutators.put("REPLACE", new ReplaceMutator());
        mutators.put("INSTR", new InstrMutator());
        mutators.put("SUBSTR", new SubstrMutator());
        mutators.put("SUBSTRING", new SubstrMutator()); // substring 与 substr 等价
        mutators.put("OCTET_LENGTH", new OctetLengthMutator());
        mutators.put("HEX", new HexMutator());
        mutators.put("UNHEX", new UnhexMutator());
        mutators.put("LIKELIHOOD", new LikelihoodMutator());
        mutators.put("LIKELY", new LikelyMutator());
        mutators.put("UNLIKELY", new UnlikelyMutator());
        mutators.put("CHAR", new CharMutator());
        mutators.put("UNICODE", new UnicodeMutator());
        mutators.put("QUOTE", new QuoteMutator());
        mutators.put("NULLIF", new NullifMutator());
        mutators.put("IFNULL", new IfnullMutator());
        mutators.put("FORMAT", new FormatMutator());
        mutators.put("GLOB", new GlobMutator());
        mutators.put("ZEROBLOB", new ZeroblobMutator());
    }

    /* ============================================================
     * ===================== NULLIF =====================
     * ============================================================ */
    static class NullifMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // 参数 X,Y
            String[] parts = arg.split(",", 2);
            String x = parts.length > 0 ? parts[0].trim() : "NULL";
            String y = parts.length > 1 ? parts[1].trim() : "NULL";

            // 等价写法1：CASE表达式
            String caseForm = String.format("CASE WHEN %s = %s THEN NULL ELSE %s END", x, y, x);

            // 等价写法2：COALESCE技巧
            String coalesceForm = String.format("COALESCE(NULLIF(%s,%s), %s)", x, y, x);

            // 返回随机等价形式
            return Randomly.fromOptions(caseForm, coalesceForm);
        }
    }

    /* ============================================================
     * ===================== OCTET_LENGTH =====================
     * ============================================================ */
    static class OctetLengthMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();

            // 严格等价：按 BLOB 语义计算字节长度
            String blobLength = String.format("LENGTH(CAST(%s AS BLOB))", x);

            // 原始形式（保留作为一种等价写法）
            String octetLength = String.format("OCTET_LENGTH(%s)", x);

            // NULL 安全包裹（不改变语义）
            String nullSafe = String.format(
                    "(CASE WHEN %s IS NULL THEN NULL ELSE LENGTH(CAST(%s AS BLOB)) END)",
                    x, x
            );

            return Randomly.fromOptions(octetLength, blobLength, nullSafe);
        }
    }
    /* ============================================================
     * ===================== ABS (终极安全版) =====================
     * ============================================================ */
    static class AbsMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return false;
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();

            // 1. 强制数值语义
            String num = "(0 + " + x + ")";

            // 2. 计算绝对值（数值层面）
            String absNum = String.format(
                    "(CASE WHEN %s < 0 THEN -%s ELSE %s END)",
                    num, num, num
            );

            // 3. 类型保持：如果是 integer，就 CAST 回 integer
            String typePreservingAbs = String.format(
                    "(CASE " +
                            "WHEN %s IS NULL THEN NULL " +
                            "WHEN typeof(%s) = 'integer' THEN CAST(%s AS INTEGER) " +
                            "ELSE %s " +
                            "END)",
                    x, num, absNum, absNum
            );

            // 4. 可选：MAX 版本（同样加类型保持）
            String safeMax = String.format(
                    "(CASE " +
                            "WHEN %s IS NULL THEN NULL " +
                            "WHEN typeof(%s) = 'integer' THEN CAST(MAX(%s, -%s) AS INTEGER) " +
                            "ELSE MAX(%s, -%s) " +
                            "END)",
                    x, num, num, num, num, num
            );

            return Randomly.fromOptions(typePreservingAbs, safeMax);
        }
    }    /* ============================================================
     * ===================== COALESCE =====================
     * ============================================================ */
    static class CoalesceMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            // COALESCE 在任意场景下都可以等价变异
            return true;
        }

        @Override
        public String mutate(String arg) {
            // arg 是以逗号分隔的参数列表
            String[] parts = arg.split(",");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }

            // 等价写法1: 保持原样 COALESCE(X1,X2,...)
            String coalesceOriginal = "COALESCE(" + String.join(", ", parts) + ")";

            // 等价写法2: 嵌套 IFNULL
            // 至少两个参数
            if (parts.length < 2) {
                return coalesceOriginal; // 退化为原样
            }
            String nestedIfnull = parts[parts.length - 1];
            for (int i = parts.length - 2; i >= 0; i--) {
                nestedIfnull = String.format("IFNULL(%s, %s)", parts[i], nestedIfnull);
            }

            // 随机返回其中一种等价形式
            return Randomly.fromOptions(coalesceOriginal, nestedIfnull);
        }
    }

    /* ============================================================
     * ===================== CONCAT =====================
     * ============================================================ */
    static class ConcatMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // 逗号分隔的多个参数
            String[] args = arg.split(",");
            String joined = String.join(", ", args);

            // 等价写法1：原生 CONCAT
            String form1 = "CONCAT(" + joined + ")";

            // 等价写法2：用 ifnull 拼接，保证 NULL 转空串
            StringBuilder form2 = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) form2.append(" || ");
                form2.append("IFNULL(").append(args[i].trim()).append(", '')");
            }

            // 等价写法3：用 COALESCE 包裹
            StringBuilder form3 = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) form3.append(" || ");
                form3.append("COALESCE(").append(args[i].trim()).append(", '')");
            }

            return Randomly.fromOptions(form1, form2.toString(), form3.toString());
        }
    }

    /* ============================================================
     * ===================== LOWER =====================
     * ============================================================ */
    static class LowerMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return String.format("LOWER(UPPER(%s))", arg.trim());
        }
    }

    /* ============================================================
     * ===================== UPPER =====================
     * ============================================================ */
    static class UpperMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return String.format("UPPER(UPPER(%s))", arg.trim());
        }
    }

    /* ============================================================
     * ===================== LTRIM =====================
     * ============================================================ */
    static class LtrimMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return String.format("LTRIM(%s)", arg.trim());
        }
    }

    /* ============================================================
     * ===================== RTRIM =====================
     * ============================================================ */
    static class RtrimMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();

            // 分割参数
            String[] parts = arg.split(",", 2);
            String x = parts[0].trim();

            if (parts.length == 1) {
                // rtrim(X) -> 默认去掉右侧空格
                String option1 = String.format("RTRIM(%s)", x);
                String option2 = String.format("RTRIM(%s, ' ')", x);
                String option3 = String.format("(CASE WHEN %s IS NULL THEN NULL ELSE RTRIM(%s) END)", x, x);
                return Randomly.fromOptions(option1, option2, option3);
            } else {
                String y = parts[1].trim();
                String option1 = String.format("RTRIM(%s, %s)", x, y);
                String option2 = String.format("(CASE WHEN %s IS NULL THEN NULL ELSE RTRIM(%s, %s) END)", x, x, y);
                String option3 = String.format("SUBSTR(%s, 1, LENGTH(%s) - LENGTH(TRIM(TRAILING %s FROM %s)))", x, x, y, x);
                return Randomly.fromOptions(option1, option2, option3);
            }
        }
    }

    /* ============================================================
     * ===================== TRIM =====================
     * ============================================================ */
    static class TrimMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();

            // 判断是否有两个参数（X,Y）
            String[] parts = arg.split(",", 2);
            String x = parts.length > 0 ? parts[0].trim() : "''";
            String y = parts.length > 1 ? parts[1].trim() : "' '"; // 默认去空格

            // 等价形式 1: 使用 TRIM(X,Y)
            String option1 = String.format("TRIM(%s, %s)", x, y);

            // 等价形式 2: 用 LTRIM + RTRIM 组合
            String option2 = String.format("LTRIM(RTRIM(%s, %s), %s)", x, y, y);

            // 等价形式 3: 用 CASE WHEN 包裹空值防护（可用于测试）
            String option3 = String.format(
                    "(CASE WHEN %s IS NULL THEN NULL ELSE TRIM(%s, %s) END)",
                    x, x, y
            );

            return Randomly.fromOptions(option1, option2, option3);
        }
    }

    /* ============================================================
     * ===================== ROUND =====================
     * ============================================================ */
    static class RoundMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();

            // 分割参数，最多两个
            String[] parts = arg.split(",", 2);
            String x = parts.length > 0 ? parts[0].trim() : "0";

            if (parts.length == 1) {
                // round(X) 等价于 round(X,0)
                String option1 = String.format("ROUND(%s, 0)", x);
                String option2 = String.format("CAST(ROUND(%s) AS REAL)", x); // 显式类型转换
                String option3 = String.format(
                        "(CASE WHEN %s IS NULL THEN NULL ELSE ROUND(%s, 0) END)",
                        x, x
                );
                return Randomly.fromOptions(option1, option2, option3);
            } else {
                String y = parts[1].trim();
                String option1 = String.format("ROUND(%s, %s)", x, y);
                String option2 = String.format(
                        "(CASE WHEN %s IS NULL OR %s IS NULL THEN NULL ELSE ROUND(%s, %s) END)",
                        x, y, x, y
                );
                String option3 = String.format(
                        "ROUND(CAST(%s AS REAL), %s)", x, y
                );
                return Randomly.fromOptions(option1, option2, option3);
            }
        }
    }

    static class SignMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();
            String nx = "CAST(" + x + " AS REAL)";

            return String.format(
                    "(CASE " +
                            "WHEN %s IS NULL THEN NULL " +
                            "WHEN %s < 0 THEN -1" +
                            "WHEN %s > 0 THEN 1 " +
                            "WHEN %s = 0 THEN 0 " +
                            "ELSE NULL END)",
                    x, x, nx, nx
            );
        }
    }

    /* ============================================================
     * ===================== REPLACE =====================
     * ============================================================ */
    static class ReplaceMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();
            String[] parts = arg.split(",", 3);

            // 安全取值，防止数组越界
            String x = parts.length > 0 ? parts[0].trim() : "NULL";
            String y = parts.length > 1 ? parts[1].trim() : "''"; // 默认替换空字符串
            String z = parts.length > 2 ? parts[2].trim() : "''"; // 默认替换空字符串

            // 如果 Y 是空字符串，REPLACE(X,'',Z) ≡ X
            String yStripped = y.replace("'", "").replace("\"", "").trim();
            if (yStripped.isEmpty()) {
                return x;
            }

            String option1 = String.format("REPLACE(%s, %s, %s)", x, y, z);
            String option2 = String.format(
                    "(CASE WHEN %s IS NULL OR %s IS NULL OR %s IS NULL THEN NULL ELSE REPLACE(%s, %s, %s) END)",
                    x, y, z, x, y, z
            );

            // 默认省略 REGEXP_REPLACE，避免 SQLite 报函数不存在
            return Randomly.fromOptions(option1, option2);
        }
    }

    /* ============================================================
     * ===================== INSTR =====================
     * ============================================================ */
    static class InstrMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);

            return String.format("CASE WHEN %s IS NULL OR %s IS NULL THEN NULL ELSE INSTR(%s,%s) END",
                    parts[0].trim(), parts[1].trim(), parts[0].trim(), parts[1].trim());
        }
    }

    /* ============================================================
     * ===================== SUBSTR =====================
     * ============================================================ */
    static class SubstrMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();

            // 最多切成 3 段：X , Y , Z
            String[] parts = arg.split(",", 3);
            String x = parts.length > 0 ? parts[0].trim() : "NULL";
            String y = parts.length > 1 ? parts[1].trim() : "1"; // 默认从第1个字符
            // 至少需要两个参数：SUBSTR(X,Y)
            if (parts.length < 2) {
                // 参数不合法，直接返回原始形式，避免崩溃
                return "SUBSTR(" + arg + ")";
            }

            if (parts.length == 2) {
                // SUBSTR(X,Y)
                String option1 = String.format("SUBSTR(%s, %s)", x, y);
                String option2 = String.format("SUBSTRING(%s, %s)", x, y); // 别名等价
                String option3 = String.format(
                        "(CASE WHEN %s IS NULL THEN NULL ELSE SUBSTR(%s, %s) END)",
                        x, x, y
                );
                return Randomly.fromOptions(option1, option2, option3);
            } else {
                // SUBSTR(X,Y,Z)
                String z = parts[2].trim();

                String option1 = String.format("SUBSTR(%s, %s, %s)", x, y, z);
                String option2 = String.format("SUBSTRING(%s, %s, %s)", x, y, z); // ANSI SQL
                String option3 = String.format(
                        "(CASE WHEN %s IS NULL THEN NULL ELSE SUBSTR(%s, %s, %s) END)",
                        x, x, y, z
                );
                return Randomly.fromOptions(option1, option2, option3);
            }
        }
    }

    /* ============================================================
     * ===================== PRINTF =====================
     * ============================================================ */
    static class PrintfMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();

            // 等价写法1：直接调用 FORMAT
            String formatForm = "FORMAT(" + trimmed + ")";

            // 等价写法2：printf原样
            String printfForm = "PRINTF(" + trimmed + ")";

            // 等价写法3：如果是简单字符串拼接，可以转换为 CONCAT
            String concatForm = trimmed.contains(",") ? "CONCAT(" + trimmed + ")" : printfForm;

            return Randomly.fromOptions(formatForm, printfForm, concatForm);
        }
    }


    static class UnhexMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            arg = arg.trim();

            // 只处理 UNHEX('3132') 这种字面量情况
            if (arg.startsWith("'") && arg.endsWith("'")) {
                String hex = arg.substring(1, arg.length() - 1);

                // 简单校验：是否可能是十六进制
                if (hex.matches("[0-9a-fA-F]+")) {
                    // UNHEX('3132')  ==>  X'3132'
                    return "X'" + hex + "'";
                }
            }

            // 其他情况（列 / 表达式），保持原样，避免产生错误等价
            return "UNHEX(" + arg + ")";
        }
    }

    /* ============================================================
     * ===================== LIKELIHOOD =====================
     * ============================================================ */
    static class LikelihoodMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return arg.trim().split(",")[0]; // likelihood(X,Y) ≡ X
        }
    }

    static class LikelyMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return false;
        }

        @Override
        public String mutate(String arg) {
            return arg.trim().split(",")[0]; // likely(X) ≡ X
        }
    }

    static class UnlikelyMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();

            // 生成其他等价形式（依然保持安全）
            String option2 = String.format("LIKELIHOOD(%s, 0.0625)", x);  // 等价 hint


            return Randomly.fromOptions(option2);
        }
    }

    /* ============================================================
     * ===================== CHAR =====================
     * ============================================================ */
    static class CharMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return String.format("CHAR(%s)", arg.trim());
        }
    }

    /* ============================================================
     * ===================== UNICODE =====================
     * ============================================================ */
    static class UnicodeMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {
        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            return String.format("UNICODE(%s)", arg.trim());
        }
    }

    /* ============================================================
     * ===================== QUOTE =====================
     * ============================================================ */
    static class QuoteMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String x = arg.trim();

            // 等价写法1：直接 quote
            String quoteForm = "QUOTE(" + x + ")";

            // 等价写法2：unistr_quote（兼容控制字符）
            String unistrForm = "UNISTR_QUOTE(" + x + ")";

            // 等价写法3：手动 CASE 处理 NULL
            String caseForm = String.format("CASE WHEN %s IS NULL THEN 'NULL' ELSE QUOTE(%s) END", x, x);

            return Randomly.fromOptions(quoteForm, unistrForm, caseForm);
        }
    }

    /* ==================== ZEROBLOB ==================== */
    static class ZeroblobMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String n = arg.trim();

            String option1 =
                    String.format("ZEROBLOB(CAST(%s AS INTEGER))", n);

            String option2 =
                    String.format(
                            "CASE WHEN %s IS NULL THEN NULL ELSE ZEROBLOB(%s) END",
                            n, n
                    );

            return Randomly.fromOptions(option1, option2);
        }
    }
    /* ============================================================
     * ===================== CONCAT_WS =====================
     * ============================================================ */
    static class ConcatWsMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            // 参数：第一个是分隔符，其余是内容
            String[] parts = arg.split(",", 2);
            String sep = parts.length > 0 ? parts[0].trim() : "','"; // 默认逗号
            String rest = parts.length > 1 ? parts[1].trim() : "''";

            // 等价写法1：原生 CONCAT_WS
            String form1 = "CONCAT_WS(" + sep + ", " + rest + ")";

            // 等价写法2：手动拼接，跳过 NULL
            StringBuilder form2 = new StringBuilder();
            String[] args = rest.split(",");
            boolean first = true;
            for (String a : args) {
                a = a.trim();
                if (!first) form2.append(" || ").append(sep).append(" || ");
                form2.append("IFNULL(").append(a).append(", '')");
                first = false;
            }

            // 等价写法3：COALESCE 版本
            StringBuilder form3 = new StringBuilder();
            first = true;
            for (String a : args) {
                a = a.trim();
                if (!first) form3.append(" || ").append(sep).append(" || ");
                form3.append("COALESCE(").append(a).append(", '')");
                first = false;
            }

            return Randomly.fromOptions(form1, form2.toString(), form3.toString());
        }
    }

    /* ============================================================
     * ===================== FORMAT / PRINTF =====================
     * ============================================================ */
    static class FormatMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String trimmed = arg.trim();

            // 等价写法1：FORMAT
            String form1 = "FORMAT(" + trimmed + ")";

            // 等价写法2：PRINTF
            String form2 = "PRINTF(" + trimmed + ")";

            // 等价写法3：简单字符串拼接转 concat
            String form3 = trimmed.contains(",") ? "CONCAT(" + trimmed + ")" : form2;

            return Randomly.fromOptions(form1, form2, form3);
        }
    }

    /* ============================================================
     * ===================== GLOB =====================
     * ============================================================ */
    static class GlobMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);
            String pattern = parts.length > 0 ? parts[0].trim() : "''";
            String str = parts.length > 1 ? parts[1].trim() : "''";

            // 等价写法1：原生 GLOB 函数
            String form1 = "GLOB(" + pattern + ", " + str + ")";

            // 等价写法2：交换参数使用 infix GLOB
            String form2 = str + " GLOB " + pattern;

            // 等价写法3：CASE 包装 NULL
            String form3 = String.format("CASE WHEN %s IS NULL THEN NULL ELSE %s GLOB %s END", str, str, pattern);

            return Randomly.fromOptions(form1, form2, form3);
        }
    }

    /* ============================================================
     * ===================== HEX =====================
     * ============================================================ */
    static class HexMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {

            String x = arg.trim();

            String form1 = "HEX(" + x + ")";
            String form2 = "HEX(UNHEX(HEX(" + x +")))";
            String form3 = "UPPER(HEX(" + x + "))";
            String form4 = "SUBSTR(HEX(" + x + "),1)";

            return Randomly.fromOptions(form1, form2, form3, form4);
        }
    }
    /* ============================================================
     * ===================== IFNULL =====================
     * ============================================================ */
    static class IfnullMutator implements SQLite3Function_level_Reconstruction_Oracle.FunctionMutator {

        @Override
        public boolean shouldMutate(String sqlAfterFunction) {
            return true;
        }

        @Override
        public String mutate(String arg) {
            String[] parts = arg.split(",", 2);

            String x = parts.length > 0 ? parts[0].trim() : "NULL";
            String y = parts.length > 1 ? parts[1].trim() : "NULL"; // 如果缺失第二参数，默认 NULL

            // 等价写法1：IFNULL
            String form1 = "IFNULL(" + x + ", " + y + ")";

            // 等价写法2：COALESCE
            String form2 = "COALESCE(" + x + ", " + y + ")";

            // 等价写法3：CASE
            String form3 = String.format("CASE WHEN %s IS NULL THEN %s ELSE %s END", x, y, x);

            return Randomly.fromOptions(form1, form2, form3);
        }
    }
}