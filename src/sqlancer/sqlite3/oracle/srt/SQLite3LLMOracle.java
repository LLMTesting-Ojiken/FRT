package sqlancer.sqlite3.oracle.srt;

import sqlancer.IgnoreMeException;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.ast.*;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.SQLite3ToStringVisitorWithPositions;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class SQLite3LLMOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    private static final Set<String> NATURAL_STOP_WORDS = new HashSet<>(Arrays.asList(
            "in", "no", "the", "of", "a", "an", "for", "is", "on", "and", "or", "to", "by", "with", "be", "not"
    ));

    public SQLite3LLMOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    @Override
    public void check() throws SQLException {

        SQLite3Expression expr = null;
        String sql = "";
        SQLite3ToStringVisitorWithPositions visitor = null;

        try {
            expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);

            visitor = new SQLite3ToStringVisitorWithPositions();
            if (expr instanceof SQLite3Select) {
                visitor.visit((SQLite3Select) expr, false);
            } else {
                visitor.visitSpecific(expr);
            }
            sql = visitor.get() + ";";

            globalState.executeStatement(new SQLQueryAdapter(sql));

        } catch (Throwable t) {

            Throwable cause = unwrap(t);
            String message = cause.getMessage();

            // 1) Token 提取
            String content = extractParenthesis(message);
            List<String> tokens = extractTokens(content);

            List<String> meaningfulTokens = tokens.stream()
                    .filter(tok -> !NATURAL_STOP_WORDS.contains(tok.toLowerCase()))
                    .collect(Collectors.toList());

            // 2) 高亮
            String highlightedSQL = highlightSQLMulti(sql, meaningfulTokens);

            // ------------------------------
            // 若高亮成功 → 不进入 LLM
            // ------------------------------
            if (!highlightedSQL.equals(sql)) {
                return;
            }

            // ==============================
            // LLM SQL MUTATION BRANCH
            // ==============================
            System.out.println("\n--- No token highlighted → Using LLM mutation ---");

            System.out.println("Original SQL:");
            System.out.println(sql);

            // 生成变体 SQL
            String mutated = analyzeWithLLM(sql, message);
            System.out.println("\nMutated SQL by LLM:");
            System.out.println(mutated);

            // 比较异常
            compareErrors(sql, mutated);
        }
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    private Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    private String extractParenthesis(String msg) {
        if (msg == null) return "";
        int s = msg.indexOf("(");
        int e = msg.lastIndexOf(")");
        if (s != -1 && e != -1 && s < e) {
            return msg.substring(s + 1, e);
        }
        return msg;
    }

    private List<String> extractTokens(String content) {
        List<String> tokens = new ArrayList<>();
        if (content == null) return tokens;
        String cleaned = content.replaceAll("[^a-zA-Z0-9_]+", " ").trim();
        for (String s : cleaned.split("\\s+")) {
            if (!s.isEmpty()) tokens.add(s);
        }
        return tokens;
    }

    private String highlightSQLMulti(String sql, List<String> tokens) {
        String result = sql;
        for (String token : tokens) {
            result = result.replaceAll("(?i)" + token, ">>>$0<<<");
        }
        return result;
    }

    // ============================================================
    // LLM 等价变异
    // ============================================================

    private String analyzeWithLLM(String sql, String errorMessage) {

        try {
            String apiKey = "sk-Q7BSMIBic6hdXsoRIAFgCERG1J2a4V9pb3rb5c3hZydd1Jqs";

            ChatModel llm = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl("https://xiaoai.plus/v1")
                    .modelName("gpt-4o-mini")
                    .build();

            String prompt =
                    "你是 SQLancer 的 Oracle 模块，用于生成 SQL 等价变体。\n" +
                            "规则（极其重要）：\n" +
                            "1) 语义等价：输出 SQL 必须与原 SQL 在执行语义上完全一致，包括查询结果、聚合、窗口函数、DISTINCT/ALL、ORDER BY/NULLS/COLLATE 等。不可修改列名、表名、函数参数、窗口边界、布尔逻辑或子查询结构。\n" +
                            "2) 仅允许安全变换：空格、换行、关键字大小写、列/表别名、不会改变优先级的冗余括号。不得破坏括号平衡或表达式结合性。\n" +
                            "3) 禁止变换：不可修改任何语义 token（列名、表名、函数名、聚合参数、子句顺序、元组元素顺序等）。\n" +
                            "4) 错误行为约束：若原 SQL 无语法错误，变体不得引入新语法错误；若原 SQL 有语法或执行错误，变体必须保持错误类型和阶段。\n" +
                            "5) 输出格式：仅输出一条可直接执行 SQL，以分号结束，不带注释或说明。若无法生成合法变体，直接返回原 SQL 并以分号结束。\n" +
                            "原 SQL：\n" + sql + "\n\n" +
                            "原始错误信息：\n" + errorMessage + "\n\n" +
                            "请生成单条严格语义等价的 SQL 变体，仅输出该 SQL 并以分号结束：";
            String raw = llm.chat(prompt);

            return sanitize(raw);

        } catch (Exception e) {
            e.printStackTrace();
            return "LLM_FAILED;";
        }
    }

    // ============================================================
    // 关键部分！处理 LLM 输出
    // ============================================================

    private String sanitize(String s) {
        if (s == null) return "LLM_EMPTY;";

        // 去掉 ```sql 和 ```
        s = s.replaceAll("(?i)```sql", "");
        s = s.replaceAll("```", "");

        // 去掉 "sql\n" 前缀
        if (s.trim().toLowerCase().startsWith("sql")) {
            s = s.substring(3);
        }

        // 去掉多余解释，保留从第一个 SELECT 开始的部分
        int idxSelect = s.toLowerCase().indexOf("select");
        if (idxSelect != -1) {
            s = s.substring(idxSelect);
        }

        // 截断到最后一个分号
        int idx = s.lastIndexOf(";");
        if (idx != -1) {
            s = s.substring(0, idx + 1);
        } else {
            s = s.trim() + ";";
        }

        return s.trim();
    }

    // ============================================================
    // 异常比较（仅在 LLM 分支执行）
    // ============================================================

    private void compareErrors(String original, String mutated) {

        Throwable originalCause = null;
        Throwable mutatedCause = null;

        try {
            globalState.executeStatement(new SQLQueryAdapter(original));
        } catch (Throwable t) {
            originalCause = unwrap(t);
        }

        try {
            globalState.executeStatement(new SQLQueryAdapter(mutated));
        } catch (Throwable t) {
            mutatedCause = unwrap(t);
        }

        System.out.println("\n[Error Comparison]");
        System.out.println("Original: " + (originalCause != null ? originalCause.getMessage() : "NO EXCEPTION"));
        System.out.println("Mutated : " + (mutatedCause != null ? mutatedCause.getMessage() : "NO EXCEPTION"));

        int code1 = getSQLiteErrorCode(originalCause);
        int code2 = getSQLiteErrorCode(mutatedCause);

        boolean inconsistent =
                (originalCause != null && mutatedCause == null)
                        || (originalCause == null && mutatedCause != null)
                        || (originalCause != null && mutatedCause != null &&
                        !Objects.equals(originalCause.getMessage(), mutatedCause.getMessage()))
                        || code1 != code2;

        if (inconsistent) {
            System.out.println("\n>>> BUG FOUND: Inconsistent behavior detected!");
            throw new AssertionError("SQLite inconsistent behavior detected.");
        }

        System.out.println("\n>>> Errors match. No bug.");
        throw new IgnoreMeException();
    }

    private int getSQLiteErrorCode(Throwable t) {
        if (t == null) return -1;
        String msg = t.getMessage();
        if (msg == null) return -1;

        try {
            int idx1 = msg.indexOf("[SQLITE_");
            int idx2 = msg.indexOf("]", idx1);
            if (idx1 != -1 && idx2 != -1) {
                String sub = msg.substring(idx1 + 1, idx2);
                return sub.hashCode();
            }
        } catch (Exception ignore) {
        }

        return -1;
    }
}