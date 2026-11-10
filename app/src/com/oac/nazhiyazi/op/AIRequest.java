package com.oac.nazhiyazi.op;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 异步 AI 请求任务。兼容 Android 2.3。
 *
 * 使用 Thread + Handler 实现（不依赖 AsyncTask，避免在某些老机型上的兼容问题）。
 * 支持流式 SSE 响应，每收到一段 delta 即回调 onDelta / onReasoningDelta。
 * 支持取消：调用 cancel() 后会尽快结束读取并断开连接。
 *
 * 错误信息尽量详细：包含 HTTP code、响应体、异常类型与消息。
 */
public class AIRequest {

    public interface AICallback {
        /** 主线程：请求开始（已建立连接） */
        void onStart();
        /** 主线程：流式增量内容到达（content 部分） */
        void onDelta(String delta);
        /** 主线程：流式增量思考内容到达（reasoning_content 部分） */
        void onReasoningDelta(String delta);
        /** 主线程：请求完成，参数为完整回复（content）与完整思考（reasoning） */
        void onComplete(String fullResponse, String fullReasoning);
        /** 主线程：出错 */
        void onError(String error);
    }

    private static final int MSG_START = 1;
    private static final int MSG_DELTA = 2;
    private static final int MSG_REASONING_DELTA = 3;
    private static final int MSG_COMPLETE = 4;
    private static final int MSG_ERROR = 5;

    /** 同时携带 content 和 reasoning 的完成消息 */
    private static class CompleteInfo {
        String content;
        String reasoning;
        CompleteInfo(String c, String r) { content = c; reasoning = r; }
    }

    private final Handler mHandler;
    private volatile Thread mThread;
    private volatile AIClient.Connection mConn;
    private volatile boolean mCancelled = false;

    // 当前请求的模型与消息，供 Tool Calls 的二次请求使用
    private volatile ModelConfig mModel;
    private volatile List<ChatMessage> mHistory;
    private volatile String mUserMessage;
    private volatile List<String> mImages;
    /** 文本化工具调用（DSML）的流式过滤器 */
    private final DsmlToolParser.StreamFilter mDsml = new DsmlToolParser.StreamFilter();

    // 流式 delta 缓冲区：后台线程追加，主线程 flush runnable 读取并清空
    private final StringBuilder mContentBuffer = new StringBuilder();
    private final StringBuilder mReasoningBuffer = new StringBuilder();
    private final Runnable mFlushRunnable;

    public AIRequest() {
        mFlushRunnable = new Runnable() {
            @Override
            public void run() {
                String content = null;
                String reasoning = null;
                synchronized (mContentBuffer) {
                    if (mContentBuffer.length() > 0) {
                        content = mContentBuffer.toString();
                        mContentBuffer.setLength(0);
                    }
                }
                synchronized (mReasoningBuffer) {
                    if (mReasoningBuffer.length() > 0) {
                        reasoning = mReasoningBuffer.toString();
                        mReasoningBuffer.setLength(0);
                    }
                }
                AICallback cb = mCallback;
                if (cb == null) return;
                if (content != null) {
                    cb.onDelta(content);
                }
                if (reasoning != null) {
                    cb.onReasoningDelta(reasoning);
                }
            }
        };
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                AICallback cb = mCallback;
                if (cb == null) return;
                switch (msg.what) {
                    case MSG_START:
                        cb.onStart();
                        break;
                    case MSG_DELTA:
                        cb.onDelta((String) msg.obj);
                        break;
                    case MSG_REASONING_DELTA:
                        cb.onReasoningDelta((String) msg.obj);
                        break;
                    case MSG_COMPLETE:
                        CompleteInfo ci = (CompleteInfo) msg.obj;
                        cb.onComplete(ci.content, ci.reasoning);
                        mCallback = null;
                        break;
                    case MSG_ERROR:
                        cb.onError((String) msg.obj);
                        mCallback = null;
                        break;
                }
            }
        };
    }

    private volatile AICallback mCallback;

    /**
     * 发起一次请求。
     *
     * @param model       模型配置
     * @param history     历史消息（不含当前用户消息）
     * @param userMessage 当前用户消息
     * @param stream      是否流式
     * @param callback    回调（所有方法在主线程调用）
     */
    public synchronized void execute(final ModelConfig model,
                                     final List<ChatMessage> history,
                                     final String userMessage,
                                     final List<String> images,
                                     final boolean stream,
                                     final AICallback callback) {
        mCallback = callback;
        mCancelled = false;
        mModel = model;
        mHistory = history;
        mUserMessage = userMessage;
        mImages = images;
        mHandler.removeCallbacks(mFlushRunnable);
        mDsml.reset();
        synchronized (mContentBuffer) { mContentBuffer.setLength(0); }
        synchronized (mReasoningBuffer) { mReasoningBuffer.setLength(0); }
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = AIClient.buildRequestBody(model, history, userMessage, images, stream);
                    AIClient.Connection conn = AIClient.connect(model, body);
                    mConn = conn;

                    int code = conn.getResponseCode();
                    if (mCancelled) {
                        conn.disconnect();
                        return;
                    }
                    if (code < 200 || code >= 300) {
                        // 错误响应：保留完整信息
                        java.io.InputStream es = conn.getErrorStream();
                        String errBody = "";
                        if (es != null) {
                            BufferedReader r = null;
                            try {
                                r = new BufferedReader(new InputStreamReader(es, "UTF-8"));
                                StringBuilder sb = new StringBuilder();
                                String l;
                                while ((l = r.readLine()) != null) sb.append(l).append("\n");
                                errBody = sb.toString();
                            } finally {
                                try { if (r != null) r.close(); } catch (Exception e) {}
                            }
                        }
                        String msg = buildErrorMessage(code, errBody, null);
                        final String errMsg = msg;
                        mHandler.obtainMessage(MSG_ERROR, errMsg).sendToTarget();
                        conn.disconnect();
                        return;
                    }

                    mHandler.sendEmptyMessage(MSG_START);

                    if (stream) {
                        readStream(conn);
                    } else {
                        readFull(conn);
                    }
                } catch (final Exception e) {
                    if (mCancelled) return;
                    String msg = buildErrorMessage(-1, null, e);
                    mHandler.obtainMessage(MSG_ERROR, msg).sendToTarget();
                }
            }
        }, "AIRequest");
        mThread.setDaemon(true);
        mThread.start();
    }

    private void readStream(AIClient.Connection conn) throws Exception {
        InputStream is = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        // 原生 tool_calls 累积
        String toolCallId = null;
        String toolCallName = null;
        StringBuilder toolCallArgsBuf = new StringBuilder();
        // 本轮思考：思考模式下带 tool_calls 的 assistant 消息必须回传 reasoning_content
        StringBuilder roundReasoning = new StringBuilder();
        String line;

        while (!mCancelled && (line = reader.readLine()) != null) {
            StreamDelta d = AIClient.parseStreamLine(line);
            if (d == null) break;
            if (d.isToolCall) {
                if (d.toolCallId != null) toolCallId = d.toolCallId;
                if (d.toolCallName != null) toolCallName = d.toolCallName;
                if (d.toolCallArgs != null) toolCallArgsBuf.append(d.toolCallArgs);
                synchronized (mContentBuffer) {
                    if (mContentBuffer.length() == 0 && toolCallName != null) {
                        mContentBuffer.append("正在处理…");
                        scheduleFlush();
                    }
                }
            }
            if (d.hasContent()) {
                String visible = mDsml.feed(d.content);
                if (visible.length() > 0) {
                    fullContent.append(visible);
                    synchronized (mContentBuffer) { mContentBuffer.append(visible); }
                    scheduleFlush();
                }
            }
            if (d.hasReasoning()) {
                fullReasoning.append(d.reasoning);
                roundReasoning.append(d.reasoning);
                synchronized (mReasoningBuffer) { mReasoningBuffer.append(d.reasoning); }
                scheduleFlush();
            }
        }
        reader.close();
        conn.disconnect();
        if (mCancelled) {
            mHandler.removeCallbacks(mFlushRunnable);
            return;
        }
        String tail = mDsml.flush();
        if (tail.length() > 0) {
            fullContent.append(tail);
            synchronized (mContentBuffer) { mContentBuffer.append(tail); }
            scheduleFlush();
        }

        // ==================== 统一工具循环 ====================
        // 一轮可以包含多个调用（原生 tool_calls 或正文里的 DSML 文本调用）
        StringBuilder toolHistory = new StringBuilder();
        int toolRounds = 0;
        final int MAX_TOOL_ROUNDS = 16;
        java.util.List<DsmlToolParser.Call> pending = null;

        while (!mCancelled && toolRounds < MAX_TOOL_ROUNDS) {
            java.util.List<DsmlToolParser.Call> calls = new java.util.ArrayList<DsmlToolParser.Call>();
            if (toolCallId != null && toolCallName != null) {
                DsmlToolParser.Call c = new DsmlToolParser.Call();
                c.name = toolCallName;
                c.args = toolCallArgsBuf.toString();
                calls.add(c);
            } else if (pending != null && !pending.isEmpty()) {
                calls.addAll(pending);
            }
            pending = null;
            if (calls.isEmpty()) break;
            toolRounds++;

            // 1) assistant 消息（含 tool_calls 与本轮思考）
            toolHistory.append(",{\"role\":\"assistant\"");
            if (roundReasoning.length() > 0) {
                toolHistory.append(",\"reasoning_content\":").append(jsonStr(roundReasoning.toString()));
            }
            toolHistory.append(",\"tool_calls\":[");
            for (int i = 0; i < calls.size(); i++) {
                DsmlToolParser.Call c = calls.get(i);
                if (i > 0) toolHistory.append(",");
                toolHistory.append("{\"id\":").append(jsonStr(callId(toolRounds, i)))
                    .append(",\"type\":\"function\"")
                    .append(",\"function\":{\"name\":").append(jsonStr(c.name))
                    .append(",\"arguments\":").append(jsonStr(c.args)).append("}}");
            }
            toolHistory.append("]}");
            roundReasoning.setLength(0);

            // 2) 逐个执行并回填结果
            for (int i = 0; i < calls.size(); i++) {
                DsmlToolParser.Call c = calls.get(i);
                String result = executeToolCall(c.name, c.args);
                toolHistory.append(",{\"role\":\"tool\",\"tool_call_id\":")
                    .append(jsonStr(callId(toolRounds, i)))
                    .append(",\"content\":").append(jsonStr(result)).append("}");
            }

            // 3) 界面状态
            synchronized (mContentBuffer) { mContentBuffer.setLength(0); }
            synchronized (mReasoningBuffer) { mReasoningBuffer.setLength(0); }
            synchronized (mContentBuffer) {
                mContentBuffer.append("[正在").append(toolLabel(calls.get(0).name)).append("…]");
            }
            scheduleFlush();

            // 4) 续问（不带 tools，避免死循环）
            String followUpBody = buildFollowUpBody(mModel, mHistory, mUserMessage, mImages,
                    toolHistory.toString(), true);
            AIClient.Connection conn2 = AIClient.connect(mModel, followUpBody);
            mConn = conn2;
            if (mCancelled) { conn2.disconnect(); return; }
            int code2 = conn2.getResponseCode();
            if (code2 < 200 || code2 >= 300) {
                String errBody = readErrorBody(conn2);
                conn2.disconnect();
                mHandler.obtainMessage(MSG_ERROR,
                        buildErrorMessage(code2, errBody, null)).sendToTarget();
                return;
            }

            // 5) 读取这一轮的响应
            toolCallId = null;
            toolCallName = null;
            toolCallArgsBuf.setLength(0);
            mDsml.reset();

            InputStream is2 = conn2.getInputStream();
            BufferedReader reader2 = new BufferedReader(new InputStreamReader(is2, "UTF-8"));
            while (!mCancelled && (line = reader2.readLine()) != null) {
                StreamDelta d2 = AIClient.parseStreamLine(line);
                if (d2 == null) break;
                if (d2.isToolCall) {
                    if (d2.toolCallId != null) toolCallId = d2.toolCallId;
                    if (d2.toolCallName != null) toolCallName = d2.toolCallName;
                    if (d2.toolCallArgs != null) toolCallArgsBuf.append(d2.toolCallArgs);
                }
                if (d2.hasContent()) {
                    String visible = mDsml.feed(d2.content);
                    if (visible.length() > 0) {
                        fullContent.append(visible);
                        synchronized (mContentBuffer) { mContentBuffer.append(visible); }
                        scheduleFlush();
                    }
                }
                if (d2.hasReasoning()) {
                    fullReasoning.append(d2.reasoning);
                    roundReasoning.append(d2.reasoning);
                    synchronized (mReasoningBuffer) { mReasoningBuffer.append(d2.reasoning); }
                    scheduleFlush();
                }
            }
            reader2.close();
            conn2.disconnect();
            if (mCancelled) {
                mHandler.removeCallbacks(mFlushRunnable);
                return;
            }
            String tail2 = mDsml.flush();
            if (tail2.length() > 0) {
                fullContent.append(tail2);
                synchronized (mContentBuffer) { mContentBuffer.append(tail2); }
                scheduleFlush();
            }

            // 6) 下一轮：优先原生 tool_calls，其次正文里的 DSML 调用
            if (toolCallId == null && mDsml.hasToolCalls()) {
                pending = DsmlToolParser.parse(mDsml.dsmlText());
            }
        }

        // 兜底：用了工具但没有任何文字
        if (fullContent.length() == 0 && toolHistory.length() > 0) {
            fullContent.append("(model returned no text after using tools)");
        }

        mHandler.removeCallbacks(mFlushRunnable);
        mFlushRunnable.run();
        mHandler.obtainMessage(MSG_COMPLETE,
                new CompleteInfo(fullContent.toString(), fullReasoning.toString())).sendToTarget();
    }

    /** 生成稳定的 tool_call id */
    private static String callId(int round, int index) {
        return "call_" + round + "_" + index;
    }

    /** 从 Tool Calls arguments JSON 中提取 query 字段 */
    private static String extractToolQuery(String toolName, String argsJson) {
        try {
            if (argsJson == null || argsJson.length() == 0) return "";
            org.json.JSONObject json = new org.json.JSONObject(argsJson);
            return json.optString("query", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 执行具体工具调用：web_search 或 fetch_page */
    private static String executeToolCall(String toolName, String argsJson) {
        if ("web_search".equals(toolName)) {
            return WebSearchUtil.search(extractToolQuery(toolName, argsJson));
        } else if ("fetch_page".equals(toolName)) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(argsJson);
                return WebSearchUtil.fetchPage(json.optString("url", ""));
            } catch (Exception e) {
                return "(fetch error: " + e.getMessage() + ")";
            }
        } else if ("get_current_date".equals(toolName)) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date());
        } else if ("run_shell".equals(toolName)) {
            String command = "";
            try {
                org.json.JSONObject json = new org.json.JSONObject(
                        (argsJson == null || argsJson.length() == 0) ? "{}" : argsJson);
                command = json.optString("command", "");
            } catch (Exception e) {
                return "(shell error: 参数解析失败 " + e.getMessage() + ")";
            }
            return ShellGate.run(command);
        }
        return "(unknown tool: " + toolName + ")";
    }

    /** 工具执行期间显示给用户的状态文案 */
    private static String toolLabel(String toolName) {
        if ("run_shell".equals(toolName)) return "执行命令";
        if ("fetch_page".equals(toolName)) return "打开网页";
        if ("web_search".equals(toolName)) return "联网搜索";
        if ("get_current_date".equals(toolName)) return "读取时间";
        return "处理";
    }

    /** 读取错误响应体（复用） */
    private static String readErrorBody(AIClient.Connection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(es, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 构造 Tool Call 的二次请求 body。
     * 在原消息列表后追加 assistant(tool_calls) + tool(result)，不加 tools 数组。
     */
    private static String buildFollowUpBody(ModelConfig model, List<ChatMessage> history,
            String userMessage, List<String> images, String toolHistory, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":");
        sb.append(jsonStr(model.modelId));
        sb.append(",\"messages\":[");
        boolean first = true;
        if (model.systemPrompt != null && model.systemPrompt.length() > 0) {
            sb.append("{\"role\":").append(jsonStr("system"))
              .append(",\"content\":").append(jsonStr(model.systemPrompt)).append("}");
            first = false;
        }
        String manual = ShellManual.systemSuffix();
        if (manual != null) {
            if (!first) sb.append(",");
            sb.append("{\"role\":").append(jsonStr("system"))
              .append(",\"content\":").append(jsonStr(manual)).append("}");
            first = false;
        }
        if (history != null) {
            for (ChatMessage m : history) {
                if (m == null || m.isSystem()) continue;
                if (!first) sb.append(",");
                sb.append("{\"role\":").append(jsonStr(m.role))
                  .append(",\"content\":").append(jsonStr(m.content)).append("}");
                first = false;
            }
        }
        if (userMessage != null && userMessage.length() > 0) {
            if (!first) sb.append(",");
            sb.append("{\"role\":").append(jsonStr("user"));
            if (images != null && images.size() > 0) {
                sb.append(",\"content\":[");
                sb.append("{\"type\":\"text\",\"text\":").append(jsonStr(userMessage)).append("}");
                for (String img : images) {
                    sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                      .append(jsonStr(img)).append("}}");
                }
                sb.append("]");
            } else {
                sb.append(",\"content\":").append(jsonStr(userMessage));
            }
            sb.append("}");
            first = false;
        }
        // 追加所有工具调用历史
        if (toolHistory != null && toolHistory.length() > 0) {
            sb.append(toolHistory);
        }
        sb.append("]");
        sb.append(",\"temperature\":").append(formatDouble(model.temperature));
        if (model.maxTokens > 0) {
            sb.append(",\"max_tokens\":").append(model.maxTokens);
        }
        if (model.shouldShowReasoning()) {
            sb.append(",\"include_reasoning\":true");
            sb.append(",\"chat_template_kwargs\":{\"enable_thinking\":true}");
        }
        if (stream) {
            sb.append(",\"stream\":true");
        }
        sb.append("}");
        return sb.toString();
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
        if (d == (long) d) return String.valueOf((long) d);
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

    /** 安排 20ms 后 flush，兼顾延迟与主线程消息数量 */
    private void scheduleFlush() {
        mHandler.removeCallbacks(mFlushRunnable);
        mHandler.postDelayed(mFlushRunnable, 20);
    }

    /** 去掉文本里的 DSML 标签，只保留可见文字 */
    private static String stripDsml(String s) {
        DsmlToolParser.StreamFilter f = new DsmlToolParser.StreamFilter();
        return f.feed(s) + f.flush();
    }

    private void readFull(AIClient.Connection conn) throws Exception {
        StreamDelta d = AIClient.readFullResponseDelta(conn);
        conn.disconnect();
        if (mCancelled) return;

        // 非流式：模型也可能把工具调用放在正文里（DSML），先尝试解析成结构化调用
        if (!d.isToolCall && d.hasContent()) {
            java.util.List<DsmlToolParser.Call> dc = DsmlToolParser.parse(d.content);
            if (!dc.isEmpty()) {
                DsmlToolParser.Call c0 = dc.get(0);
                d.isToolCall = true;
                d.toolCallName = c0.name;
                d.toolCallArgs = c0.args;
                d.toolCallId = "call_1_0";
                d.content = stripDsml(d.content);
            } else if (d.content.indexOf("|DSML|") >= 0) {
                d.content = stripDsml(d.content);
            }
        }

        // 非流式 tool_calls 处理：与流式类似但只有一轮
        if (d.isToolCall) {
            String toolArgs = (d.toolCallArgs != null) ? d.toolCallArgs : "";
            String toolResult = executeToolCall(d.toolCallName, toolArgs);
            StringBuilder th = new StringBuilder();
            th.append(",{\"role\":\"assistant\"");
            if (d.hasReasoning()) {
                th.append(",\"reasoning_content\":").append(jsonStr(d.reasoning));
            }
            th.append(",\"tool_calls\":[{")
              .append("\"id\":").append(jsonStr(d.toolCallId))
              .append(",\"type\":\"function\"")
              .append(",\"function\":{\"name\":").append(jsonStr(d.toolCallName))
              .append(",\"arguments\":").append(jsonStr(toolArgs))
              .append("}}]}")
              .append(",{\"role\":\"tool\",\"tool_call_id\":").append(jsonStr(d.toolCallId))
              .append(",\"content\":").append(jsonStr(toolResult)).append("}");
            String followUpBody = buildFollowUpBody(mModel, mHistory, mUserMessage, mImages,
                    th.toString(), false);
            AIClient.Connection conn2 = AIClient.connect(mModel, followUpBody);
            mConn = conn2;
            if (mCancelled) { conn2.disconnect(); return; }
            int code2 = conn2.getResponseCode();
            if (code2 < 200 || code2 >= 300) {
                String errBody = readErrorBody(conn2);
                conn2.disconnect();
                mHandler.obtainMessage(MSG_ERROR,
                        buildErrorMessage(code2, errBody, null)).sendToTarget();
                return;
            }
            d = AIClient.readFullResponseDelta(conn2);
            conn2.disconnect();
            if (mCancelled) return;
        }

        if (d.hasContent()) {
            mHandler.obtainMessage(MSG_DELTA, d.content).sendToTarget();
        }
        if (d.hasReasoning()) {
            mHandler.obtainMessage(MSG_REASONING_DELTA, d.reasoning).sendToTarget();
        }
        // 兜底
        String content = d.content;
        if ((content == null || content.length() == 0) && d.isToolCall) {
            content = "(model returned no text after using tools)";
        }
        mHandler.obtainMessage(MSG_COMPLETE,
                new CompleteInfo(content, d.reasoning)).sendToTarget();
    }

    /**
     * 构建详细的错误信息。
     *
     * @param code HTTP 状态码（-1 表示非 HTTP 错误，如网络异常）
     * @param errBody 错误响应体
     * @param e 异常对象（可为 null）
     */
    private static String buildErrorMessage(int code, String errBody, Throwable e) {
        StringBuilder sb = new StringBuilder();
        if (code > 0) {
            sb.append("HTTP ").append(code);
        } else if (e != null) {
            sb.append(e.getClass().getSimpleName());
        } else {
            sb.append("Unknown error");
        }

        // 解析错误响应体
        String parsedMsg = null;
        if (errBody != null && errBody.length() > 0) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(errBody);
                org.json.JSONObject err = json.optJSONObject("error");
                if (err != null) {
                    if (err.isNull("message")) {
                        // skip
                    } else {
                        String m = err.optString("message", "");
                        if (m != null && m.length() > 0 && !"null".equals(m)) {
                            parsedMsg = m;
                        }
                    }
                } else {
                    if (!json.isNull("message")) {
                        String m = json.optString("message", "");
                        if (m != null && m.length() > 0 && !"null".equals(m)) {
                            parsedMsg = m;
                        }
                    }
                }
            } catch (Exception ex) {
                // 不是 JSON，保留原始响应体
            }
        }

        if (parsedMsg != null && parsedMsg.length() > 0) {
            sb.append(": ").append(parsedMsg);
        } else if (errBody != null && errBody.length() > 0) {
            String trunc = errBody.length() > 300 ? errBody.substring(0, 300) + "…" : errBody;
            sb.append("\nResponse: ").append(trunc);
        }

        if (e != null && e.getMessage() != null && e.getMessage().length() > 0) {
            sb.append("\nException: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Cancel the request. Disconnects the socket silently; no onError callback fires.
     *
     * Note: Thread.interrupt() cannot unblock a socket read (BufferedReader.readLine).
     * The actual cancel mechanism is conn.disconnect() closing the underlying socket,
     * which causes readLine to throw IOException. The outer catch then sees mCancelled==true
     * and returns without firing onError.
     */
    public void cancel() {
        mCancelled = true;
        if (mConn != null) {
            // 关闭底层连接，导致 readLine 抛出 SocketException / IOException
            try { mConn.disconnect(); } catch (Exception e) {}
            mConn = null;
        }
        if (mThread != null) {
            // 仅设标志位，不强制中断 I/O（见上方注释）
            mThread.interrupt();
        }
        // 清空缓冲，避免取消后残留 delta 继续回调
        mHandler.removeCallbacks(mFlushRunnable);
        synchronized (mContentBuffer) { mContentBuffer.setLength(0); }
        synchronized (mReasoningBuffer) { mReasoningBuffer.setLength(0); }
    }

    public boolean isCancelled() {
        return mCancelled;
    }
}
