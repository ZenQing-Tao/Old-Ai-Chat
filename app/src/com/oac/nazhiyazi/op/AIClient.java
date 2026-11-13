package com.oac.nazhiyazi.op;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * AI HTTP 客户端。兼容 Android 2.1 (API 7+)。
 *
 * - 通过 NetWorkerFactory 选择网络层：
 *     · OkHttp 2.x + SpongyCastle（Android 2.3+，性能/流式最佳）
 *     · HttpsURLConnection + SpongyCastle 纯 Java TLS（Android 2.1 老网络层）
 * - 信任所有证书并跳过主机名校验（兼容老设备自签名/过期证书场景）
 * - 支持流式 SSE 响应和一次性 JSON 响应
 * - 支持 reasoning_content（DeepSeek 思考链）
 *
 * 本类【不】直接引用 okhttp，okhttp 只由 OkHttpWorker 通过 Class.forName
 * 在运行时按需加载，避免在 Android 2.1 上因类校验失败而 VerifyError 崩溃。
 */
public class AIClient {

    /**
     * 对 AI 请求的统一连接封装（适配中性 IHttpConnection）。
     */
    public static class Connection implements IHttpConnection {
        private final IHttpConnection mConn;
        private final int mCode;

        Connection(IHttpConnection conn) throws IOException {
            mConn = conn;
            mCode = conn.getResponseCode();
        }

        public int getResponseCode() {
            return mCode;
        }

        public InputStream getInputStream() throws IOException {
            return mConn.getInputStream();
        }

        public InputStream getErrorStream() throws IOException {
            return mConn.getErrorStream();
        }

        public String getHeaderField(String name) {
            return mConn.getHeaderField(name);
        }

        public void disconnect() {
            try {
                mConn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 构造 OpenAI 兼容协议请求体 JSON
     */
    public static String buildRequestBody(ModelConfig model, java.util.List<ChatMessage> history,
                                          String userMessage, java.util.List<String> images, boolean stream) {
        // 整个请求体用 org.json 构造：结构上不可能出现少逗号/多括号/转义错误。
        // 早期版本用手工 append 拼字符串，曾多次产出非法 JSON（服务端 400/422）。
        org.json.JSONObject root = new org.json.JSONObject();
        try {
            root.put("model", model.modelId == null ? "" : model.modelId);

            org.json.JSONArray msgs = new org.json.JSONArray();
            if (model.systemPrompt != null && model.systemPrompt.length() > 0) {
                msgs.put(roleContent("system", model.systemPrompt));
            }
            String manual = ShellManual.systemSuffix();
            if (manual != null && manual.length() > 0) {
                msgs.put(roleContent("system", manual));
            }
            if (history != null) {
                for (ChatMessage m : history) {
                    if (m == null || m.isSystem()) continue;
                    msgs.put(roleContent(m.role, m.content));
                }
            }
            if (userMessage != null && userMessage.length() > 0) {
                org.json.JSONObject um = new org.json.JSONObject();
                um.put("role", "user");
                if (model.multimodal && images != null && images.size() > 0) {
                    org.json.JSONArray parts = new org.json.JSONArray();
                    org.json.JSONObject tp = new org.json.JSONObject();
                    tp.put("type", "text");
                    tp.put("text", userMessage);
                    parts.put(tp);
                    for (int i = 0; i < images.size(); i++) {
                        org.json.JSONObject iu = new org.json.JSONObject();
                        iu.put("url", images.get(i));
                        org.json.JSONObject ip = new org.json.JSONObject();
                        ip.put("type", "image_url");
                        ip.put("image_url", iu);
                        parts.put(ip);
                    }
                    um.put("content", parts);
                } else {
                    um.put("content", userMessage);
                }
                msgs.put(um);
            }
            root.put("messages", msgs);

            double temp = model.temperature;
            if (Double.isNaN(temp) || Double.isInfinite(temp)) temp = 0.7;
            root.put("temperature", temp);
            if (model.maxTokens > 0) root.put("max_tokens", model.maxTokens);
            if (stream) root.put("stream", true);

            if (model.shouldShowReasoning()) {
                root.put("include_reasoning", true);
                org.json.JSONObject ctk = new org.json.JSONObject();
                ctk.put("enable_thinking", true);
                root.put("chat_template_kwargs", ctk);
            }

            org.json.JSONArray tools = buildToolsArray(model.enableToolCalls, isShellToolEnabled());
            if (tools != null && tools.length() > 0) root.put("tools", tools);
        } catch (Throwable t) {
            // 单字段失败不影响整体（org.json 已保证输出仍是合法 JSON）
        }
        return root.toString();
    }

    /** 构造一条 {role, content} 消息 */
    private static org.json.JSONObject roleContent(String role, String content) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("role", (role == null || role.length() == 0) ? "user" : role);
            o.put("content", content == null ? "" : content);
        } catch (Throwable t) {}
        return o;
    }

    /**
     * 把用户填的 API 地址补全成真正的接口地址。
     *
     * 千问 token plan 这类平台的地址是 https://host/compatible-mode/v1，
     * 这是「基础地址」，真正的接口还要再加 /chat/completions。
     * 用户填 "…/v1" 或 "…/v1/" 时自动补全，已带具体路径的不动。
     */
    public static String normalizeApiUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0) return u;
        String lower = u.toLowerCase();
        if (lower.indexOf("/chat/completions") >= 0
                || lower.indexOf("/completions") >= 0
                || lower.indexOf("/messages") >= 0
                || lower.indexOf("/generatecontent") >= 0
                || lower.indexOf("/responses") >= 0) {
            return u;
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        lower = u.toLowerCase();
        if (lower.endsWith("/v1")) return u + "/chat/completions";
        return u;
    }

    /** AI 系统命令工具是否可用（仅看总开关，与 WebSearch 开关解耦） */
    private static boolean isShellToolEnabled() {
        try {
            Context ctx = OACApplication.getContext();
            if (ctx == null) return false;
            return SettingsManager.get(ctx).isShellEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** web_search 工具描述 */
    private static final String DESC_WEB_SEARCH =
        "Search the web. YOU MUST USE THIS for current events, versions, releases, news. "
        + "WRAP KEY TERMS IN QUOTES. 10 results are returned; identify 1-6 high-value URLs. "
        + "After search, USE fetch_page to open each high-value URL one by one. "
        + "Only answer after finding all needed facts. NEVER answer from snippets alone.";

    /** fetch_page 工具描述 */
    private static final String DESC_FETCH_PAGE =
        "Open a URL and read full page content. REQUIRED after web_search. "
        + "Open high-value URLs one at a time. If one page lacks complete info, open the next.";

    /** run_shell 工具描述 */
    private static final String DESC_RUN_SHELL =
        "Run a shell command on the local Android device as root (via su). "
        + "Use it to inspect or tune THIS device: getprop, dumpsys, cat /proc/..., ps, top, df, "
        + "logcat -d, settings get, pm list, lsmod, cat /sys/... . "
        + "Commands are risk-graded: read-only commands run immediately; commands that change state "
        + "need the user to confirm a dialog; dangerous commands (reboot, rm -rf, dd, mkfs, "
        + "pm uninstall/disable, writing to /system, setprop persist.*) are blocked unless released in settings. "
        + "Rules: (1) Prefer read-only probes; gather facts before changing anything. "
        + "(2) One change per command; never chain unrelated modifications. "
        + "(3) If the result says refused or cancelled, do NOT retry; explain to the user instead. "
        + "(4) Output is truncated, so keep commands narrow. "
        + "(5) Explain what you change and why, and prefer reversible changes.";

    /** 构造一个 string 类型属性定义 */
    private static org.json.JSONObject stringSchema(String desc) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("type", "string");
            o.put("description", desc == null ? "" : desc);
        } catch (Throwable t) {}
        return o;
    }

    /** 构造一个 function 类型的工具定义 */
    private static org.json.JSONObject functionTool(String name, String desc, org.json.JSONObject params) {
        org.json.JSONObject tool = new org.json.JSONObject();
        org.json.JSONObject fn = new org.json.JSONObject();
        try {
            fn.put("name", name);
            fn.put("description", desc == null ? "" : desc);
            if (params != null) fn.put("parameters", params);
            tool.put("type", "function");
            tool.put("function", fn);
        } catch (Throwable t) {}
        return tool;
    }

    /** 构造 object 类型的参数 schema */
    private static org.json.JSONObject objectSchema(String[] propNames, String[] propDescs, String[] required) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("type", "object");
            org.json.JSONObject props = new org.json.JSONObject();
            for (int k = 0; k < propNames.length; k++) {
                props.put(propNames[k], stringSchema(propDescs[k]));
            }
            o.put("properties", props);
            if (required != null && required.length > 0) {
                org.json.JSONArray req = new org.json.JSONArray();
                for (int k = 0; k < required.length; k++) req.put(required[k]);
                o.put("required", req);
            }
            o.put("additionalProperties", false);
        } catch (Throwable t) {}
        return o;
    }

    /**
     * 构造 tools 数组（不直接拼字符串，交由 org.json 序列化）。
     */
    private static org.json.JSONArray buildToolsArray(boolean webTools, boolean shellTool) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            if (webTools) {
                arr.put(functionTool("web_search", DESC_WEB_SEARCH,
                        objectSchema(new String[]{"query"},
                                new String[]{"Search keywords in user's language"},
                                new String[]{"query"})));
                arr.put(functionTool("fetch_page", DESC_FETCH_PAGE,
                        objectSchema(new String[]{"url"},
                                new String[]{"The full URL to open from search results"},
                                new String[]{"url"})));
                arr.put(functionTool("get_current_date", "Get current date and time from device.",
                        objectSchema(new String[0], new String[0], null)));
            }
            if (shellTool) {
                arr.put(functionTool("run_shell", DESC_RUN_SHELL,
                        objectSchema(new String[]{"command", "purpose"},
                                new String[]{"Full one-line shell command, executed as root",
                                        "Short reason shown to the user in the confirm dialog"},
                                new String[]{"command"})));
            }
        } catch (Throwable t) {
            // 构造失败就返回已有的部分，调用方会按长度决定是否带上
        }
        return arr;
    }

    private static void appendOptimizationParams(ModelConfig model, StringBuilder sb) {
        if (model == null || sb == null) return;
        if (!model.shouldShowReasoning()) return;
        sb.append(",\"include_reasoning\":true");
        sb.append(",\"chat_template_kwargs\":{\"enable_thinking\":true}");
    }

    /**
     * 建立到 API 的连接（流式 / 非流式通用）。
     */
    public static Connection connect(ModelConfig model, String body) throws Exception {
        Context ctx = OACApplication.getContext();
        if (ctx == null) {
            throw new IllegalStateException("OACApplication context not initialized");
        }
        return connect(ctx, model, body);
    }

    public static Connection connect(Context ctx, ModelConfig model, String body) throws Exception {
        // 发送前自检 + 规范化：用 org.json 解析再序列化一遍，
        // 既能保证服务端一定解析得动（转义/控制字符问题会被修正），
        // 又能在真的非法时报出长度与位置，并落盘请求体便于排查。
        body = normalizeBodyOrThrow(ctx, body);
        NetRequest req = new NetRequest();
        req.url = normalizeApiUrl(model.apiUrl);
        req.method = "POST";
        req.contentType = "application/json; charset=utf-8";
        req.body = body.getBytes("UTF-8");
        req.headers = new java.util.HashMap<String, String>();
        req.headers.put("Accept", "text/event-stream, application/json");
        req.headers.put("Connection", "keep-alive");
        if (model.apiKey != null && model.apiKey.length() > 0) {
            req.headers.put("Authorization", "Bearer " + model.apiKey);
        }
        IHttpConnection conn = NetWorkerFactory.getWorker().connect(req);
        return new Connection(conn);
    }

    /**
     * 请求体规范化 + 自检。
     *
     * 1) 用 org.json 解析：失败说明拼接出的 JSON 真的非法，直接抛错并带长度/位置；
     * 2) 解析成功则用重新序列化的结果替换，抹平转义、控制字符等潜在差异；
     * 3) 无论成功失败都把请求体写到 files/last_request.json，便于离线排查。
     */
    private static String normalizeBodyOrThrow(Context ctx, String body) throws Exception {
        if (body == null) return null;
        String err = null;
        String normalized = body;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(body);
            normalized = obj.toString();
        } catch (Throwable t) {
            err = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : (": " + t.getMessage()));
            // 附带出错位置附近的原文，便于一眼看出是结构问题还是转义问题
            try {
                String m = t.getMessage();
                if (m != null) {
                    int k = m.indexOf(" at ");
                    if (k > 0) {
                        int p = Integer.parseInt(m.substring(k + 4).trim());
                        int from = Math.max(0, p - 120);
                        int to = Math.min(body.length(), p + 60);
                        err += "\n附近原文: " + body.substring(from, to).replace("\n", "\\n");
                    }
                }
            } catch (Throwable ignore) {}
        }

        try {
            java.io.File dir = null;
            if (ctx != null) {
                dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
            }
            if (dir != null) {
                java.io.File f = new java.io.File(dir, "last_request.json");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
                fos.write(normalized.getBytes("UTF-8"));
                fos.close();
            }
        } catch (Throwable t) {
            // 落盘失败不影响请求
        }

        if (err != null) {
            int bytes = 0;
            try { bytes = body.getBytes("UTF-8").length; } catch (Throwable t) {}
            throw new IllegalStateException("请求体 JSON 非法（字符 " + body.length()
                + " / 字节 " + bytes + "）：" + err);
        }
        return normalized;
    }

    public static String readFullResponse(Connection conn) throws Exception {
        StreamDelta d = readFullResponseDelta(conn);
        return d.content;
    }

    public static StreamDelta readFullResponseDelta(Connection conn) throws Exception {
        int code = conn.getResponseCode();
        // 错误码用 getErrorStream()：老网络层（HttpURLConnection）在 4xx/5xx 上调
        // getInputStream() 会抛 IOException；okhttp 实现中两者等价（均为 body 流）。
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (is == null) {
            throw new Exception("HTTP " + code);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        String body = sb.toString();

        if (code >= 200 && code < 300) {
            return parseFullResponseDelta(body);
        } else {
            String msg = parseErrorMessage(body);
            throw new Exception("HTTP " + code + (msg != null ? ": " + msg : ""));
        }
    }

    private static String parseFullResponseContent(String body) {
        return parseFullResponseDelta(body).content;
    }

    private static StreamDelta parseFullResponseDelta(String body) {
        StreamDelta d = new StreamDelta();
        if (body == null || body.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject message = first.optJSONObject("message");
                if (message != null) {
                    d.content = safeOptString(message, "content");
                    d.reasoning = safeOptString(message, "reasoning_content");
                    if (d.reasoning.length() == 0) {
                        d.reasoning = safeOptString(message, "reasoning");
                    }
                    if (d.content.length() == 0) {
                        d.content = safeOptString(message, "text");
                    }
                    org.json.JSONArray toolCalls = message.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        org.json.JSONObject tc = toolCalls.getJSONObject(0);
                        d.isToolCall = true;
                        String tcid = safeOptString(tc, "id");
                        if (tcid.length() > 0) d.toolCallId = tcid;
                        org.json.JSONObject func = tc.optJSONObject("function");
                        if (func != null) {
                            String fn = safeOptString(func, "name");
                            if (fn.length() > 0) d.toolCallName = fn;
                            d.toolCallArgs = safeOptString(func, "arguments");
                        }
                    }
                    return d;
                }
                d.content = safeOptString(first, "text");
            }
        } catch (Exception e) {
        }
        return d;
    }

    private static String parseErrorMessage(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String msg = safeOptString(err, "message");
                if (msg.length() > 0) return msg;
            }
            String msg = safeOptString(json, "message");
            if (msg.length() > 0) return msg;
        } catch (Exception e) {
            if (body.length() > 200) body = body.substring(0, 200);
            return body;
        }
        return body;
    }

    public static StreamDelta parseStreamLine(String line) {
        StreamDelta d = new StreamDelta();
        if (line == null) return d;
        line = line.trim();
        if (line.length() == 0) return d;
        if (!line.startsWith("data:")) return d;
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) return null;
        if (data.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject delta = first.optJSONObject("delta");
                if (delta == null) {
                    delta = first.optJSONObject("message");
                }
                if (delta != null) {
                    d.content = safeOptString(delta, "content");
                    d.reasoning = safeOptString(delta, "reasoning_content");
                    if (d.reasoning.length() == 0) {
                        d.reasoning = safeOptString(delta, "reasoning");
                    }
                    if (d.content.length() == 0) {
                        d.content = safeOptString(delta, "text");
                    }
                    org.json.JSONArray toolCalls = delta.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        org.json.JSONObject tc = toolCalls.getJSONObject(0);
                        d.isToolCall = true;
                        String tcId = safeOptString(tc, "id");
                        if (tcId.length() > 0) d.toolCallId = tcId;
                        org.json.JSONObject func = tc.optJSONObject("function");
                        if (func != null) {
                            String fnName = safeOptString(func, "name");
                            if (fnName.length() > 0) d.toolCallName = fnName;
                            d.toolCallArgs = safeOptString(func, "arguments");
                        }
                    }
                }
                // finish_reason 在 choices[0] 层，可能和 delta 同 chunk 出现，也可能只在最后一条
                String fr = safeOptString(first, "finish_reason");
                if (fr.length() > 0) d.finishReason = fr;
            }
        } catch (Exception e) {
        }
        return d;
    }

    private static String safeOptString(org.json.JSONObject obj, String key) {
        if (obj == null) return "";
        if (obj.isNull(key)) return "";
        try {
            String s = obj.optString(key, "");
            if (s == null || "null".equals(s)) return "";
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        while (hex.length() < 4) hex = "0" + hex;
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) d = 0.7;
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        String s = String.valueOf(d);
        if (s.indexOf('.') > 0) {
            while (s.length() > 1 && s.charAt(s.length() - 1) == '0') {
                s = s.substring(0, s.length() - 1);
            }
            if (s.length() > 1 && s.charAt(s.length() - 1) == '.') {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    /** 对任意 HttpURLConnection 应用 Trust-All SSL（供 WebSearchUtil 等复用） */
    public static void applyTrustAllSSL(java.net.HttpURLConnection conn) {
        // 已改用 OkHttp，旧入口保留空实现避免编译错误
    }
}
