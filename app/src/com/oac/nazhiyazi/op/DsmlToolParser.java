package com.oac.nazhiyazi.op;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析模型以纯文本形式输出的工具调用（DSML 标记）。
 *
 * 实测发现不同模型/网关会输出不同的标记写法，必须全部兼容：
 *
 *   半角：<|DSML|invoke name="run_shell">      </|DSML|invoke>
 *   斜杠在前：<|/DSML|parameter>                （闭合标签的另一种写法）
 *   全角（实测！）：<｜｜DSML｜｜ invoke ...>     </｜｜DSML｜｜ invoke>
 *
 * 因此解析时**不依赖竖线字符**：只要能找到 "dsml" 字样 + 紧邻的 < >，
 * 就把它当作标签边界处理。标签内容一律不进正文显示。
 */
public final class DsmlToolParser {

    public static class Call {
        public String name = "";
        public String args = "{}";
    }

    private DsmlToolParser() {}

    /** 全角竖线等异体字符先归一化，方便后续判断 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\uFF5C', '|')   // ｜
                .replace('\u02D8', '|')
                .replace('\u2016', '|');
    }

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
                            mDsml.append(tag).append(' ');
                            boolean closing = lowerTag.startsWith("</") || lowerTag.startsWith("<|/")
                                    || lowerTag.startsWith("<\uFF5C/") || lowerTag.startsWith("<|/");
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
                    if (mTag.length() > 160) {
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
            String s = normalize(mDsml.toString()).toLowerCase();
            return s.indexOf("dsml") >= 0;
        }

        public String dsmlText() {
            return mDsml.toString();
        }
    }

    // ==================== 标签识别（不依赖竖线） ====================

    /**
     * 判断 text 中 pos 处的 '<' 开始的是不是一个 DSML 标签，
     * 是则返回标签内 dsml 之后紧跟的名字（小写），否则返回 null。
     */
    private static String tagNameAt(String s, int lt) {
        if (lt < 0 || lt >= s.length() || s.charAt(lt) != '<') return null;
        int limit = Math.min(s.length(), lt + 160);
        int gt = s.indexOf('>', lt);
        if (gt < 0 || gt >= limit) return null;
        String inner = normalize(s.substring(lt + 1, gt));
        int d = inner.toLowerCase().indexOf("dsml");
        if (d < 0) return null;
        // dsml 之后跳过非字母字符，取名字
        int k = d + 4;
        while (k < inner.length() && !isLetter(inner.charAt(k))) k++;
        int start = k;
        while (k < inner.length() && isLetter(inner.charAt(k))) k++;
        String name = inner.substring(start, k).toLowerCase();
        boolean closing = inner.indexOf('/') >= 0;
        return closing ? ("/" + name) : name;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 找到从 from 开始的下一个 DSML 标签的 '<' 位置 */
    private static int nextDsmlTag(String s, int from) {
        int i = Math.max(0, from);
        while (true) {
            int lt = s.indexOf('<', i);
            if (lt < 0) return -1;
            if (tagNameAt(s, lt) != null) return lt;
            i = lt + 1;
        }
    }

    /** 找到从 from 开始的下一个指定名字的开启标签 */
    private static int nextOpeningTag(String s, String name, int from) {
        int i = Math.max(0, from);
        while (true) {
            int lt = nextDsmlTag(s, i);
            if (lt < 0) return -1;
            String n = tagNameAt(s, lt);
            if (n != null && n.equals(name)) return lt;
            int gt = s.indexOf('>', lt);
            i = gt < 0 ? s.length() : gt + 1;
        }
    }

    // ==================== 解析 ====================

    public static List<Call> parse(String text) {
        List<Call> out = new ArrayList<Call>();
        if (text == null || text.length() == 0) return out;
        String s = normalize(text);

        int pos = 0;
        while (true) {
            int inv = nextOpeningTag(s, "invoke", pos);
            if (inv < 0) break;
            int invGt = s.indexOf('>', inv);
            if (invGt < 0) break;

            // 取 name="xxx"
            String name = attrValue(s, inv, invGt, "name");
            if (name == null || name.length() == 0) {
                pos = invGt + 1;
                continue;
            }

            // 本 invoke 的范围：到下一个 invoke 开启标签或文末
            int nextInv = nextOpeningTag(s, "invoke", invGt + 1);
            int segEnd = nextInv >= 0 ? nextInv : s.length();

            String body = s.substring(invGt + 1, segEnd);
            String args = extractArgs(body);

            // 实测：模型偶尔会把多个 JSON 对象塞进同一个 parameter，例如
            //   {"command":"id"}{"command":"cat /proc/meminfo"}
            // 只取第一个会静默丢掉后面的调用，所以这里拆分。
            List<String> parts = splitConcatenatedJson(args);
            if (parts.size() > 1) {
                for (int i = 0; i < parts.size(); i++) {
                    Call c = new Call();
                    c.name = name;
                    c.args = parts.get(i);
                    out.add(c);
                }
            } else {
                Call call = new Call();
                call.name = name;
                call.args = args;
                out.add(call);
            }

            pos = segEnd > invGt ? segEnd : invGt + 1;
        }
        return out;
    }

    /**
     * 把连在一起的多个 JSON 对象拆开：{"a":1}{"b":2} → ["{"a":1}", "{"b":2}"]
     * 拆不开（正常单个 JSON）时返回单元素列表。
     */
    private static List<String> splitConcatenatedJson(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String t = s.trim();
        if (t.length() == 0 || t.charAt(0) != '{') {
            out.add(t);
            return out;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int start = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String one = t.substring(start, i + 1).trim();
                    if (one.length() > 1) out.add(one);
                    start = -1;
                }
            }
        }
        if (out.isEmpty()) out.add(t);
        return out;
    }

    /** 从标签里取属性值，如 name="run_shell" */
    private static String attrValue(String s, int lt, int gt, String attr) {
        String inner = s.substring(lt, gt);
        int i = indexOfIgnoreCase(inner, attr + "=", 0);
        if (i < 0) i = indexOfIgnoreCase(inner, attr + " =", 0);
        if (i < 0) return null;
        int k = i + attr.length();
        while (k < inner.length() && (inner.charAt(k) == '=' || inner.charAt(k) == ' ')) k++;
        if (k >= inner.length()) return null;
        char q = inner.charAt(k);
        if (q != '"' && q != '\'') return null;
        int end = inner.indexOf(q, k + 1);
        if (end < 0) return null;
        return inner.substring(k + 1, end).trim();
    }

    /** 从一个 invoke 的片段里提取参数 JSON */
    private static String extractArgs(String body) {
        List<String> names = new ArrayList<String>();
        List<String> values = new ArrayList<String>();

        int pos = 0;
        while (true) {
            int p = nextOpeningTag(body, "parameter", pos);
            if (p < 0) break;
            int gt = body.indexOf('>', p);
            if (gt < 0) break;
            String pname = attrValue(body, p, gt, "name");
            if (pname == null || pname.length() == 0) {
                pos = gt + 1;
                continue;
            }

            // 值：从 '>' 之后，到下一个 parameter 开启标签（或本段结束）
            int nextP = nextOpeningTag(body, "parameter", gt + 1);
            int vEnd = nextP >= 0 ? nextP : body.length();
            String val = body.substring(gt + 1, vEnd);

            // 值里如果还夹着闭合标签，从第一个标签处截断
            int cut = nextDsmlTag(val, 0);
            if (cut >= 0) val = val.substring(0, cut);

            names.add(pname);
            values.add(trimTagChars(val));

            pos = vEnd > gt ? vEnd : gt + 1;
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
            // ignore
        }
        return "{}";
    }

    /** 去掉值尾部的标签残余字符 */
    private static String trimTagChars(String v) {
        if (v == null) return "";
        String s = v.trim();
        while (s.length() > 0) {
            char c = s.charAt(s.length() - 1);
            if (c == '<' || c == '>' || c == '|' || c == '/' || c == ' ' || c == '\n' || c == '\r'
                    || c == '\uFF5C') {
                s = s.substring(0, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
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
