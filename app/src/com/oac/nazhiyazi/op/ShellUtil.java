package com.oac.nazhiyazi.op;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地命令执行器（都是 shell 命令 + root）。
 *
 * 兼容性约束（Android 2.3 / API 9）：
 *   - 不用 Process.waitFor(timeout, unit)（API 26+ 才有），自己轮询 exitValue
 *   - 不用 try-with-resources
 *   - stdout / stderr 必须并发读，否则缓冲区满会死锁
 */
public final class ShellUtil {

    /** 输出上限：防止 dumpsys 类命令把 token 烧光或 OOM */
    public static final int MAX_OUTPUT = 6000;
    public static final int DEFAULT_TIMEOUT_MS = 25000;

    /** su 候选路径，按顺序尝试 */
    private static final String[] SU_CANDIDATES = new String[]{
        "su", "/system/xbin/su", "/system/bin/su", "/sbin/su",
        "/su/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su"
    };

    /** null = 尚不可用；"" = 检测过不可用；其他 = 可用的 su 路径 */
    private static volatile String sSuBin = null;
    private static volatile boolean sProbed = false;

    private ShellUtil() {}

    public static class Result {
        public int exit = -1;
        public String output = "";
        public boolean timeout = false;
        public boolean rootUsed = false;
    }

    /**
     * 探测 su 是否可用。首次会触发 Magisk 授权弹窗，因此给足 60s。
     */
    public static synchronized String probeSu(Context ctx) {
        if (sProbed) {
            return sSuBin == null ? "" : sSuBin;
        }
        sProbed = true;
        for (int i = 0; i < SU_CANDIDATES.length; i++) {
            String bin = SU_CANDIDATES[i];
            Result r = exec(new String[]{bin, "-c", "id -u"}, 60000);
            if (r != null && !r.timeout && r.exit == 0
                    && r.output != null && r.output.trim().indexOf("0") >= 0) {
                sSuBin = bin;
                return bin;
            }
        }
        sSuBin = "";
        return "";
    }

    public static boolean hasRoot(Context ctx) {
        String su = probeSu(ctx);
        return su != null && su.length() > 0;
    }

    /** 清缓存，设置页“重新检测 root”用 */
    public static synchronized void resetSuCache() {
        sProbed = false;
        sSuBin = null;
    }

    /**
     * 有 su 走 su，没有就降级为普通 shell（只读命令仍可能成功）。
     */
    public static Result runRoot(Context ctx, String command, int timeoutMs) {
        String su = probeSu(ctx);
        Result r;
        if (su != null && su.length() > 0) {
            r = exec(new String[]{su, "-c", command}, timeoutMs);
            r.rootUsed = true;
        } else {
            r = exec(new String[]{"/system/bin/sh", "-c", command}, timeoutMs);
            r.rootUsed = false;
        }
        return r;
    }

    private static Result exec(String[] argv, int timeoutMs) {
        Result r = new Result();
        StringBuilder buf = new StringBuilder();
        Process p;
        try {
            p = Runtime.getRuntime().exec(argv);
        } catch (Throwable t) {
            r.output = "(启动进程失败: " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : (": " + t.getMessage())) + ")";
            r.exit = -1;
            return r;
        }

        Pump out = new Pump(p.getInputStream(), buf);
        Pump err = new Pump(p.getErrorStream(), buf);
        out.start();
        err.start();

        int wait = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        long deadline = System.currentTimeMillis() + wait;
        boolean done = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                done = true;
                break;
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    break;
                }
            } catch (Throwable t) {
                break;
            }
        }

        if (!done) {
            r.timeout = true;
            try { p.destroy(); } catch (Throwable t) {}
        }

        try { out.join(1500); } catch (Throwable t) {}
        try { err.join(1500); } catch (Throwable t) {}

        if (!r.timeout) {
            try {
                r.exit = p.exitValue();
            } catch (Throwable t) {
                r.exit = -1;
            }
        }

        String text;
        synchronized (buf) {
            text = buf.toString();
        }
        r.output = truncate(text);
        return r;
    }

    /** 并发读取单条流，超过上限后只计数不追加 */
    private static class Pump extends Thread {
        private final InputStream mIn;
        private final StringBuilder mBuf;
        private final byte[] mChunk = new byte[4096];

        Pump(InputStream in, StringBuilder buf) {
            mIn = in;
            mBuf = buf;
            setDaemon(true);
        }

        public void run() {
            try {
                int n;
                while ((n = mIn.read(mChunk)) > 0) {
                    synchronized (mBuf) {
                        if (mBuf.length() >= MAX_OUTPUT * 3) continue;
                        String s;
                        try {
                            s = new String(mChunk, 0, n, "UTF-8");
                        } catch (Throwable t) {
                            s = new String(mChunk, 0, n);
                        }
                        mBuf.append(s);
                    }
                }
            } catch (Throwable t) {
                // ignore
            } finally {
                try { mIn.close(); } catch (Throwable t) {}
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        int len = s.length();
        if (len <= MAX_OUTPUT) return s;
        return s.substring(0, MAX_OUTPUT) + "\n…(输出已截断，原始 " + len + " 字符)";
    }

    /**
     * 审计日志：每条命令都落盘，出事能回溯。
     */
    public static void audit(Context ctx, int risk, String cmd, Result r, long costMs) {
        if (ctx == null || r == null) return;
        FileOutputStream fos = null;
        try {
            File dir = null;
            try { dir = ctx.getExternalFilesDir(null); } catch (Throwable t) {}
            if (dir == null) dir = ctx.getFilesDir();
            if (dir == null) return;
            File f = new File(dir, "shell-audit.log");
            if (f.exists() && f.length() > 512 * 1024) {
                f.delete();
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String head = r.output == null ? "" : r.output;
            if (head.length() > 300) head = head.substring(0, 300);
            head = head.replace("\n", " ⏎ ");
            String line = fmt.format(new Date())
                + " risk=" + ShellRisk.label(risk)
                + " root=" + (r.rootUsed ? "1" : "0")
                + " exit=" + r.exit
                + " timeout=" + (r.timeout ? "1" : "0")
                + " cost=" + costMs + "ms\n"
                + "CMD: " + cmd + "\n"
                + "OUT: " + head + "\n----\n";
            fos = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(line);
            w.flush();
            w.close();
            fos = null;
        } catch (Throwable t) {
            // 日志失败不影响主流程
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable t) {}
            }
        }
    }
}
