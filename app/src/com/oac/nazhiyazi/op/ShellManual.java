package com.oac.nazhiyazi.op;

import android.content.Context;

/**
 * 设备运维手册：仅当 run_shell 开启时追加到 system prompt。
 *
 * 目标：让模型一上手就知道该看哪些节点，避免瞎试；同时把红线写死。
 * 内容按 MTK（MT8167B）+ Android 7.0 + Magisk 实测过的路径编写。
 */
public final class ShellManual {

    private ShellManual() {}

    public static final String TEXT =
        "【本机】MTK MT8167B（4x Cortex-A35）\uff0cAndroid 7.0（API 24）\uff0c内核 4.4.22 aarch64\uff0c"
        + "SELinux Enforcing\uff0cMagisk su 可用。默认只做只读探测\uff1b写操作先说清目的\u3001给回滚方案\u3001等用户确认。\n"
        + "\n【1. 整体状态】\n"
        + "cat /proc/meminfo | head -5\uff1bfree\uff1bcat /proc/loadavg\uff1btop -n 1 -m 10\n"
        + "dumpsys activity processes | head -60\uff08进程优先级与常驻）\n"
        + "dumpsys power | grep -i wake\uff1bdumpsys battery\uff08只看，不要 set）\n"
        + "cat /proc/wakeup_sources\uff1b若不存在用 cat /sys/kernel/debug/wakeup_sources\n"
        + "\n【2. CPU 与调度（MTK）】\n"
        + "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor\n"
        + "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors\n"
        + "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies\n"
        + "cat /sys/devices/system/cpu/online\n"
        + "for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do echo $f; cat $f; done\n"
        + "\n【3. 温度与热管理（MTK 用 thermal_zone，temp 单位为千分之一度）】\n"
        + "for z in /sys/class/thermal/thermal_zone*; do echo $z; cat $z/type; cat $z/temp; done\n"
        + "for c in /sys/class/thermal/cooling_device*; do echo $c; cat $c/type; cat $c/cur_state; done\n"
        + "\n【4. 存储与 I/O】\n"
        + "df -h\uff1bcat /sys/block/mmcblk0/queue/scheduler\n"
        + "cat /proc/sys/vm/swappiness\uff1bcat /proc/sys/vm/dirty_ratio\uff1bcat /proc/pressure/cpu\n"
        + "\n【5. 功耗与待机】\n"
        + "dumpsys deviceidle | head -30\uff1bdumpsys wifi | head -40\n"
        + "settings get global wifi_sleep_policy\uff1bdumpsys telephony.registry | head -30\n"
        + "\n【6. 应用侧】\n"
        + "pm list packages -3\uff08第三方包）\uff1bdumpsys package <pkg> | grep -i -A2 enabled\n"
        + "cmd appops get <pkg>\n"
        + "\n【7. 红线（不要做）】\n"
        + "不改 /system /vendor /boot /persist\uff1b不 reboot\uff1b不 pm uninstall/disable 系统包\uff1b"
        + "不 setprop persist.*\uff1b不动 thermal_zone \u3001cooling_device \u3001原厂热配置\uff1b"
        + "不一次性改多个参数。\n"
        + "\n【8. 改动纪律】\n"
        + "每次只改一个节点\uff1b改前先 cat 原值并记录\uff1b同时给出恢复命令；写失败不要换路径硬凑，"
        + "先告诉用户你尝试了什么、失败在哪。\n";

    /**
     * 仅在 shell 工具开启时返回手册文本，否则返回 null。
     */
    public static String systemSuffix() {
        try {
            Context ctx = OACApplication.getContext();
            if (ctx == null) return null;
            if (!SettingsManager.get(ctx).isShellEnabled()) return null;
        } catch (Throwable t) {
            return null;
        }
        return TEXT;
    }
}
