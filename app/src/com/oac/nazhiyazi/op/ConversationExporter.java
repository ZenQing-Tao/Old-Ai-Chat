package com.oac.nazhiyazi.op;

import android.content.Context;
import android.text.format.DateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 对话导出器。
 *
 * 与"让 AI 自己用 shell 拼文本"不同，这里直接读数据库里的消息，
 * 因此导出内容与界面完全一致，不会出现轮次错位、重复段落或凭空补写的"分析摘要"。
 *
 * 思考内容（reasoning）由调用方决定是否包含。
 */
public class ConversationExporter {

    /** 导出结果 */
    public static class Result {
        public boolean ok;
        public String path;       // 成功时的文件路径
        public String message;    // 失败原因，或成功时的附加说明
        public int msgCount;
        public boolean withReasoning;
    }

    /**
     * 导出到 Download 目录。
     *
     * @param ctx           上下文
     * @param convId        对话 id
     * @param convTitle     对话标题
     * @param includeReason 是否包含思考内容
     */
    public static Result export(Context ctx, DBHelper db, long convId, String convTitle, boolean includeReason) {
        Result r = new Result();
        r.withReasoning = includeReason;
        try {
            if (db == null) {
                r.message = "数据库未就绪，请稍后重试。";
                return r;
            }
            List<ChatMessage> msgs = db.getMessages(convId);
            if (msgs == null || msgs.isEmpty()) {
                r.message = "当前对话还没有消息，无需导出。";
                return r;
            }
            String text = build(ctx, convTitle, msgs, includeReason);
            File dir = new File("/storage/emulated/0/Download");
            if (!dir.exists()) {
                dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            File f = new File(dir, "OAC_dialog_" + stamp + ".txt");
            // 避免同分钟重复导出互相覆盖
            int n = 2;
            while (f.exists()) {
                f = new File(dir, "OAC_dialog_" + stamp + "-" + n + ".txt");
                n++;
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                // 带 BOM，Windows 记事本打开不会乱码
                fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
                try {
                    w.write(text);
                    w.flush();
                } finally {
                    try { w.close(); } catch (Throwable t) { }
                }
            } finally {
                try { fos.close(); } catch (Throwable t) { }
            }
            r.ok = true;
            r.path = f.getAbsolutePath();
            r.msgCount = msgs.size();
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.message = "导出失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            return r;
        }
    }

    /** 拼出完整文本 */
    private static String build(Context ctx, String convTitle, List<ChatMessage> msgs, boolean includeReason) {
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String title = (convTitle == null || convTitle.trim().length() == 0) ? "（未命名对话）" : convTitle.trim();

        sb.append("========================================\n");
        sb.append("OAC 对话导出\n");
        sb.append("========================================\n");
        sb.append("对话标题：").append(title).append("\n");
        sb.append("导出时间：").append(now).append("\n");
        sb.append("设备：").append(android.os.Build.MODEL).append(" / ")
          .append(android.os.Build.MANUFACTURER).append("\n");
        sb.append("系统：Android ").append(android.os.Build.VERSION.RELEASE)
          .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
        sb.append("消息条数：").append(msgs == null ? 0 : msgs.size()).append("\n");
        sb.append("思考内容：").append(includeReason ? "已包含（原始 reasoning_content）" : "未包含").append("\n");
        sb.append("说明：本文件由 App 直接读取本地数据库生成，内容与界面显示一致，未做改写。\n");
        sb.append("\n");

        // 时间格式：老设备上 24 小时制由系统设置决定
        java.text.DateFormat tfmt = android.text.format.DateFormat.getTimeFormat(ctx);
        SimpleDateFormat dfmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        String lastDay = "";
        int idx = 0;
        for (int i = 0; msgs != null && i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if (m == null) continue;
            idx++;

            // 按天分组，方便阅读
            String day = dfmt.format(new Date(m.createdAt > 0 ? m.createdAt : System.currentTimeMillis()));
            if (!day.equals(lastDay)) {
                sb.append("----------------------------------------\n");
                sb.append("【").append(day).append("】\n");
                sb.append("----------------------------------------\n");
                lastDay = day;
            }

            String who;
            if (m.isUser()) who = "用户";
            else if (m.isAssistant()) who = "AI";
            else if (m.isSystem()) who = "系统";
            else who = (m.role == null ? "未知" : m.role);

            String time = tfmt.format(new Date(m.createdAt > 0 ? m.createdAt : System.currentTimeMillis()));

            sb.append("\n[#").append(idx).append("] ").append(who)
              .append("  ").append(time).append("\n");

            // 思考内容放在正文之前，并明确标注，避免与正文混淆
            if (includeReason && m.isAssistant() && m.hasReasoning()) {
                sb.append("──── 思考 ────\n");
                sb.append(trimTrailing(m.reasoning)).append("\n");
                sb.append("──── 正文 ────\n");
            }

            String body = m.content;
            if (body == null || body.trim().length() == 0) {
                body = "（无正文）";
            }
            sb.append(trimTrailing(body)).append("\n");
        }

        sb.append("\n");
        sb.append("========================================\n");
        sb.append("导出结束，共 ").append(idx).append(" 条消息\n");
        sb.append("========================================\n");
        return sb.toString();
    }

    /** 去掉尾部多余空白，避免文件里堆空行 */
    private static String trimTrailing(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') end--;
            else break;
        }
        return s.substring(0, end);
    }
}
