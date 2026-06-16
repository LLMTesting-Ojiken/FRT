package sqlancer.sqlite3.oracle.srt;

import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.sqlite3.SQLite3GlobalState;
import sqlancer.sqlite3.ast.*;
import sqlancer.sqlite3.oracle.SQLite3RandomQuerySynthesizer;
import sqlancer.sqlite3.SQLite3ToStringVisitorWithPositions;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class SQLite3SRTOracle implements TestOracle<SQLite3GlobalState> {

    private final SQLite3GlobalState globalState;

    public SQLite3SRTOracle(SQLite3GlobalState globalState) {
        this.globalState = globalState;
    }

    private static final Set<String> NATURAL_STOP_WORDS = new HashSet<>(Arrays.asList(
            "in", "no", "the", "of", "a", "an", "for", "is", "on", "and", "or", "to", "by", "with", "be", "not"
    ));

    @Override
    public void check() throws SQLException {
        SQLite3Expression expr = null;
        String sql = "";

        // 位置记录 visitor
        SQLite3ToStringVisitorWithPositions visitor = null;

        try {
            // 1) 生成随机 AST
            expr = SQLite3RandomQuerySynthesizer.generate(globalState, 1);

            // 2) 用带位置映射的 visitor 生成 SQL
            visitor = new SQLite3ToStringVisitorWithPositions();
            if (expr instanceof SQLite3Select) {
                visitor.visit((SQLite3Select) expr, false);
            } else {
                // visitSpecific 是你在 visitor 中覆盖过的入口，可以用于任意 SQLite3Expression
                visitor.visitSpecific(expr);
            }
            sql = visitor.get() + ";";

            globalState.executeStatement(new SQLQueryAdapter(sql));

        } catch (Throwable t) {

            System.out.println("\n=== [Exception Detected] ===");
            Throwable cause = unwrap(t);
            String message = cause.getMessage();
            System.out.println("Exception: " + cause.getClass().getSimpleName());
            System.out.println("Reason: " + message);

            /* ---------------------------------------
             * 1) 提取错误中括号里的 token
             * --------------------------------------- */
            String content = extractParenthesis(message);
            System.out.println("Content in parentheses: " + content);

            List<String> tokens = extractTokens(content);

            List<String> meaningfulTokens = tokens.stream()
                    .filter(tok -> !NATURAL_STOP_WORDS.contains(tok.toLowerCase()))
                    .collect(Collectors.toList());

            System.out.println("Tokens after stop-word filtering: " + meaningfulTokens);

            /* ---------------------------------------
             * 2) 多 token 高亮 SQL
             * --------------------------------------- */
            String highlightedSQL = highlightSQLMulti(sql, meaningfulTokens);
            System.out.println("\n--- Highlighted SQL ---");
            System.out.println(highlightedSQL);

            /* ---------------------------------------
             * 3) AST反向定位（关键）
             * --------------------------------------- */
            if (visitor != null && expr != null) {
                for (String token : meaningfulTokens) {

                    SQLite3Expression errorNode =
                            visitor.findNodeByToken(sql, token);

                    System.out.println("\n[Token → AST] token = " + token);

                    if (errorNode == null) {
                        System.out.println("  -> No AST node matched.");
                        continue;
                    }

                    System.out.println("  -> AST node type = "
                            + errorNode.getClass().getSimpleName());

                    /* ---------------------------------------
                     * 4) 获取 AST 父链
                     * --------------------------------------- */
                    Map<SQLite3Expression, SQLite3Expression> parents =
                            buildParentMapReflection(expr);

                    List<SQLite3Expression> chain =
                            findParentChain(errorNode, parents);

                    System.out.println("  -> Parent chain:");
                    for (SQLite3Expression p : chain) {
                        System.out.println("     - " + p.getClass().getSimpleName());
                    }
                }
            }

        }
    }

    /** 反射版：通过反射遍历字段构建 AST 父关系图（child -> parent）。
     *  使用 IdentityHashMap/IdentitySet，避免 equals/hashCode 干扰。
     *
     *  兼容性：适用于大多数按字段组织的 AST（单字段、数组、List、Map、Optional 等）。
     */
    private Map<SQLite3Expression, SQLite3Expression> buildParentMapReflection(SQLite3Expression root) {
        Map<SQLite3Expression, SQLite3Expression> parent = new IdentityHashMap<>();
        Set<SQLite3Expression> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<SQLite3Expression> stack = new ArrayDeque<>();
        if (root == null) {
            return parent;
        }
        stack.push(root);
        visited.add(root);

        while (!stack.isEmpty()) {
            SQLite3Expression node = stack.pop();
            Class<?> cls = node.getClass();
            while (cls != null && !cls.equals(Object.class)) {
                Field[] fields = cls.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);
                    Object val;
                    try {
                        val = f.get(node);
                    } catch (IllegalAccessException e) {
                        continue;
                    }
                    if (val == null) continue;

                    // 单个子表达式
                    if (val instanceof SQLite3Expression) {
                        SQLite3Expression child = (SQLite3Expression) val;
                        if (!visited.contains(child)) {
                            parent.put(child, node);
                            visited.add(child);
                            stack.push(child);
                        } else {
                            parent.putIfAbsent(child, node);
                        }
                        continue;
                    }

                    // 数组
                    if (val.getClass().isArray()) {
                        int len = Array.getLength(val);
                        for (int i = 0; i < len; i++) {
                            Object elem = Array.get(val, i);
                            if (elem instanceof SQLite3Expression) {
                                SQLite3Expression child = (SQLite3Expression) elem;
                                if (!visited.contains(child)) {
                                    parent.put(child, node);
                                    visited.add(child);
                                    stack.push(child);
                                } else {
                                    parent.putIfAbsent(child, node);
                                }
                            }
                        }
                        continue;
                    }

                    // 集合
                    if (val instanceof Collection) {
                        for (Object elem : (Collection<?>) val) {
                            if (elem instanceof SQLite3Expression) {
                                SQLite3Expression child = (SQLite3Expression) elem;
                                if (!visited.contains(child)) {
                                    parent.put(child, node);
                                    visited.add(child);
                                    stack.push(child);
                                } else {
                                    parent.putIfAbsent(child, node);
                                }
                            }
                        }
                        continue;
                    }

                    // Optional
                    if (val instanceof Optional) {
                        Object maybe = ((Optional<?>) val).orElse(null);
                        if (maybe instanceof SQLite3Expression) {
                            SQLite3Expression child = (SQLite3Expression) maybe;
                            if (!visited.contains(child)) {
                                parent.put(child, node);
                                visited.add(child);
                                stack.push(child);
                            } else {
                                parent.putIfAbsent(child, node);
                            }
                        }
                    }

                    // Map 值（某些 AST 会用 Map<String, List<Expr>>）
                    if (val instanceof Map) {
                        for (Object vv : ((Map<?, ?>) val).values()) {
                            if (vv instanceof Collection) {
                                for (Object elem : (Collection<?>) vv) {
                                    if (elem instanceof SQLite3Expression) {
                                        SQLite3Expression child = (SQLite3Expression) elem;
                                        if (!visited.contains(child)) {
                                            parent.put(child, node);
                                            visited.add(child);
                                            stack.push(child);
                                        } else {
                                            parent.putIfAbsent(child, node);
                                        }
                                    }
                                }
                            } else if (vv instanceof SQLite3Expression) {
                                SQLite3Expression child = (SQLite3Expression) vv;
                                if (!visited.contains(child)) {
                                    parent.put(child, node);
                                    visited.add(child);
                                    stack.push(child);
                                } else {
                                    parent.putIfAbsent(child, node);
                                }
                            }
                        }
                    }
                } // fields
                cls = cls.getSuperclass();
            } // while cls
        } // while stack
        return parent;
    }

    /** 错误节点 → 父链（从子到祖先） */
    private List<SQLite3Expression> findParentChain(SQLite3Expression node,
                                                    Map<SQLite3Expression, SQLite3Expression> parentMap) {
        List<SQLite3Expression> chain = new ArrayList<>();
        SQLite3Expression cur = node;

        while (cur != null) {
            chain.add(cur);
            cur = parentMap.get(cur);
        }
        return chain;
    }



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

    /**
     * 完整版 TokenLocatorVisitor
     * 遍历所有 SQLite3Visitor 定义的节点类型
     */
}