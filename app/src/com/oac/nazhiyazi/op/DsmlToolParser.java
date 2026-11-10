package com.oac.nazhiyazi.op;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析模型以纯文本形式输出的工具调用（DSML 标记）。
 *
 * 部分模型/中转层不通过 API 的 tool_calls 字段返回调用，而是在正文里输出：
 *
 *   &lt;|DSML|calls&gt;
 *   &lt;|DSML|invoke name="run_shell"&gt;
 *   &lt;|DSML|parameter name="command" string="true"&gt;su -c id&lt;|/DSML|parameter&gt;
 *   &lt;|DSML|parameter name="purpose" string="true"&gt;确认 root&lt;|/DSML|parameter&gt;
 *   &lt;/|DSML|invoke&gt;
 *   &lt;/|DSML|calls&gt;
 *
 * 这里负责两件事：
 *   1) 流式过滤：不把标签显示给用户，同时把标签内容收集下来；
 *   2) 解析：把标签还原成结构化调用，交给 ShellGate 等去执行。
 *
 * 兼容两种参数写法：
 *   - 单个 arguments 参数里塞一个 JSON：{"command":"...","purpose":"..."}
 *   - 每个字段一个 parameter：name="command" / name="purpose"
 */
public final class DsmlToolParser {

    public static class Call {
        public String name = "";
        public String args = "{}";
    }

    private DsmlToolParser() {}

    // ==================== 流式过滤 ====================

    public static class StreamFilter {
        private final StringBuilder mTag = new StringBuilder();
        private final StringBuilder mDsml = new StringBuilder();
        private boolean mInTag = false;
        private boolean mInDsml = false;

        public void reset() {
            mTag.setLength(0);
            mDsml.setLength(0);
            mInTag = false;
            mInDsml = false;
        }

        /** 喂入一段流式内容，返回应当显示给用户的文本 */
        public String feed(String s) {
            if (s == null || s.length() == 0) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (mInTag) {
                    mTag.append(c);
                    if (c == '>') {
                        String tag = mTag.toString();
                        mTag.setLength(0);
                        mInTag = false;
                        String lowerTag = tag.toLowerCase();
                        if (lowerTag.indexOf("dsml") >= 0) {
                            mDsml.append(tag);
                            // 闭合标签有两种写法：</|DSML|xxx> 与 <|/DSML|xxx>
                            boolean closing = lowerTag.startsWith("</") || lowerTag.startsWith("<|/");
                            if (closing) {
                                if (lowerTag.indexOf("call") >= 0) mInDsml = false;
                            } else {
                                mInDsml = true;
                            }
                        } else {
                            out.append(tag);
                        }
                        continue;
                    }
                    if (mTag.length() > 64) {
                        // 看起来不是标签，原样吐出
                        out.append(mTag);
                        mTag.setLength(0);
                        mInTag = false;
                    }
                    continue;
                }
                if (c == '<') {
                    mInTag = true;
                    mTag.setLength(0);
                    mTag.append(c);
                    continue;
                }
                if (mInDsml) {
                    mDsml.append(c);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        /** 流结束时把缓冲里剩下的可见文本吐出 */
        public String flush() {
            String tail = "";
            if (mInTag && mTag.length() > 0) {
                tail = mTag.toString();
                mTag.setLength(0);
                mInTag = false;
            }
            return tail;
        }

        public boolean hasToolCalls() {
            return mDsml.indexOf("|DSML|") >= 0 || mDsml.indexOf("|dsml|") >= 0;
        }

        public String dsmlText() {
            return mDsml.toString();
        }
    }

    // ==================== 解析 ====================

    public static List<Call> parse(String text) {
        List<Call> out = new ArrayList<Call>();
        if (text == null || text.length() == 0) return out;

        int pos = 0;
        while (true) {
            int inv = indexOfIgnoreCase(text, "invoke", pos);
            if (inv < 0) break;

            int ne = indexOfIgnoreCase(text, "name=", inv);
            if (ne < 0) break;
            int qPos = ne + 5;
            if (qPos >= text.length()) break;
            char q = text.charAt(qPos);
            if (q != '"' && q != '\'') {
                pos = ne + 5;
                continue;
            }
            int vs = qPos + 1;
            int ve = text.indexOf(q, vs);
            if (ve < 0) break;
            String name = text.substring(vs, ve).trim();

            int nextInv = indexOfIgnoreCase(text, "invoke", ve);
            // 闭合标签可能是 </|DSML|invoke> 或 <|/DSML|invoke>，统一按名字匹配
            int closeInv = indexOfIgnoreCase(text, "dsml|invoke", ve);

            int segEnd;
            if (closeInv >= 0 && (nextInv < 0 || closeInv < nextInv)) {
                segEnd = closeInv;
            } else if (nextInv >= 0) {
                segEnd = nextInv;
            } else {
                segEnd = text.length();
            }
            if (segEnd < ve) segEnd = ve;

            String body = text.substring(ve, segEnd);
            Call call = new Call();
            call.name = name;
            call.args = extractArgs(body);
            if (call.name != null && call.name.length() > 0) out.add(call);

            pos = segEnd > ve ? segEnd : ve + 1;
        }
        return out;
    }

    /** 从一个 invoke 的片段里提取参数 JSON */
    private static String extractArgs(String body) {
        List<String> names = new ArrayList<String>();
        List<String> values = new ArrayList<String>();

        int pos = 0;
        while (true) {
            int pi = indexOfIgnoreCase(body, "parameter", pos);
            if (pi < 0) break;
            int ne = indexOfIgnoreCase(body, "name=", pi);
            if (ne < 0) break;
            int qPos = ne + 5;
            if (qPos >= body.length()) break;
            char q = body.charAt(qPos);
            if (q != '"' && q != '\'') {
                pos = ne + 5;
                continue;
            }
            int vs = qPos + 1;
            int ve = body.indexOf(q, vs);
            if (ve < 0) break;
            String pname = body.substring(vs, ve).trim();

            int gt = body.indexOf('>', ve);
            if (gt < 0) break;

            int close = indexOfIgnoreCase(body, "dsml|parameter", gt);
            int valueEnd = close >= 0 ? close : body.length();
            String pval = body.substring(gt + 1, valueEnd).trim();
            // 去掉闭合标签可能带的前缀 “<”
            while (pval.endsWith("<") || pval.endsWith("|") || pval.endsWith("/")) {
                pval = pval.substring(0, pval.length() - 1).trim();
            }
            names.add(pname);
            values.add(pval);

            pos = close >= 0 ? close + 10 : body.length();
        }

        // 优先：单个 arguments 参数里就是完整 JSON
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if ("arguments".equalsIgnoreCase(n) || "args".equalsIgnoreCase(n)) {
                String v = values.get(i);
                if (v != null && v.trim().startsWith("{")) return v.trim();
            }
        }

        // 其次：逐字段拼成 JSON
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (int i = 0; i < names.size(); i++) {
                o.put(names.get(i), values.get(i));
            }
            if (o.length() > 0) return o.toString();
        } catch (Throwable t) {
            // 忽略，回落到空对象
        }
        return "{}";
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        if (s == null || needle == null) return -1;
        int n = s.length();
        int m = needle.length();
        for (int i = Math.max(0, from); i + m <= n; i++) {
            if (s.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }
}
