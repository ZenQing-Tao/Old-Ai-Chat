package com.oac.nazhiyazi.op;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行闸门：分级 → 放行判定 → 用户确认 → 执行 → 审计。
 *
 * 这是 AI 工具 run_shell 的唯一入口。AI 无法绕过此处的限制：
 *   - 中危：弹窗等待用户点击，超时视为拒绝
 *   - 高危：设置页未放行则直接拒执行，即使放行也仍会弹窗二次确认
 */
public final class ShellGate {

    /** 确认弹窗的等待上限，超时视作拒绝 */
    private static final int CONFIRM_TIMEOUT_MS = 120000;

    /** 命令最大长度，防止超长命令注入 */
    private static final int MAX_CMD_LEN = 2000;

    private static volatile WeakReference<Activity> sAct = new WeakReference<Activity>(null);

    private ShellGate() {}

    /** MainActivity 在前台时注册，用于弹确认框 */
    public static void attach(Activity a) {
        sAct = new WeakReference<Activity>(a);
    }

    public static void detach(Activity a) {
        Activity cur = sAct.get();
        if (cur == null || cur == a) {
            sAct = new WeakReference<Activity>(null);
        }
    }

    /**
     * 执行一条命令，返回给模型看的文本结果。此方法会阻塞（在 AI 请求后台线程调用）。
     */
    public static String run(String command) {
        Context ctx = OACApplication.getContext();
        if (ctx == null) return "(shell error: 应用上下文未初始化)";

        String cmd = ShellRisk.unwrapSu(command);
        if (cmd.length() == 0) return "(shell error: 命令为空)";
        if (cmd.length() > MAX_CMD_LEN) {
            return "(shell error: 命令过长，上限 " + MAX_CMD_LEN + " 字符)";
        }

        SettingsManager sm = SettingsManager.get(ctx);
        int risk = ShellRisk.classify(cmd);
        boolean whitelisted = false;

        if (risk != ShellRisk.SAFE && ShellRisk.isWhitelisted(cmd, sm.getShellWhitelist())) {
            whitelisted = true;
            risk = ShellRisk.SAFE;
        }

        if (risk == ShellRisk.HIGH && !sm.isShellHighEnabled()) {
            return "(refused: 该命令被判定为【高危】，当前未在设置中放行，已阻止执行。\n"
                + "请在「设置 → AI 系统命令 → 放行高危命令」中开启，或把该命令加入白名单后重试。\n"
                + "如果只是想查看信息，请改用只读命令：getprop / dumpsys / cat /proc/... / ps / df。)";
        }

        if (risk != ShellRisk.SAFE && !confirm(ctx, cmd, risk)) {
            return "(cancelled: 用户拒绝或未在 " + (CONFIRM_TIMEOUT_MS / 1000)
                + " 秒内确认，命令未执行。不要重复尝试同一条命令，请向用户说明原因并等待指示。)";
        }

        int timeout = risk == ShellRisk.HIGH ? 30000 : ShellUtil.DEFAULT_TIMEOUT_MS;
        long t0 = System.currentTimeMillis();
        ShellUtil.Result r = ShellUtil.runRoot(ctx, cmd, timeout);
        long cost = System.currentTimeMillis() - t0;
        ShellUtil.audit(ctx, risk, cmd, r, cost);

        StringBuilder sb = new StringBuilder();
        sb.append("[risk=").append(ShellRisk.label(risk));
        if (whitelisted) sb.append("/白名单");
        sb.append(" root=").append(r.rootUsed ? "是" : "否");
        sb.append(" exit=").append(r.exit);
        sb.append(" cost=").append(cost).append("ms");
        if (r.timeout) sb.append(" TIMEOUT(已强杀)");
        sb.append("]\n");
        if (r.output == null || r.output.length() == 0) {
            sb.append("(无输出)");
        } else {
            sb.append(r.output);
        }
        return sb.toString();
    }

    /**
     * 阻塞式确认：主线程弹窗，后台线程等待结果。
     */
    private static boolean confirm(final Context ctx, final String cmd, final int risk) {
        final Activity act = sAct.get();
        if (act == null) return false;   // 拿不到界面就不执行，宁可拒绝

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] result = new boolean[]{false};
        final AlertDialog[] holder = new AlertDialog[1];

        Runnable show = new Runnable() {
            public void run() {
                try {
                    final Handler h = new Handler(Looper.getMainLooper());
                    String title = risk == ShellRisk.HIGH
                        ? "高危命令确认（可能损坏设备）" : "命令执行确认";
                    String prefix = risk == ShellRisk.HIGH
                        ? "AI 请求执行以下高危命令，可能导致无法开机、数据丢失或失去 root：\n\n"
                        : "AI 请求执行以下命令来读取/修改设备状态：\n\n";

                    AlertDialog.Builder b = new AlertDialog.Builder(act);
                    b.setTitle(title);
                    b.setMessage(prefix + cmd);
                    b.setCancelable(false);
                    b.setPositiveButton(risk == ShellRisk.HIGH ? "已了解风险，执行" : "允许",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                result[0] = true;
                                latch.countDown();
                            }
                        });
                    b.setNegativeButton("拒绝",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                result[0] = false;
                                latch.countDown();
                            }
                        });

                    holder[0] = b.create();
                    holder[0].show();

                    h.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                if (holder[0] != null && holder[0].isShowing()) holder[0].dismiss();
                            } catch (Throwable t) {}
                            latch.countDown();
                        }
                    }, CONFIRM_TIMEOUT_MS);
                } catch (Throwable t) {
                    latch.countDown();
                }
            }
        };

        try {
            act.runOnUiThread(show);
        } catch (Throwable t) {
            return false;
        }

        try {
            if (!latch.await(CONFIRM_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS)) return false;
        } catch (Throwable t) {
            return false;
        }
        return result[0];
    }
}
