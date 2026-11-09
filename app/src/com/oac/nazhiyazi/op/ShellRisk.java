package com.oac.nazhiyazi.op;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 命令风险分级（兼容 Android 2.3 / Java 6 语法：不用 lambda、不用 String.join）
 *
 * SAFE   只读或无害命令，直接执行
 * MEDIUM 会修改系统状态、杀进程、写文件，需要用户在对话框确认
 * HIGH   可能导致无法开机 / 丢数据 / 破坏 root，必须在设置页显式放行
 *
 * 分级策略：白名单优先（未知命令一律落到 MEDIUM，宁严勿松）。
 */
public final class ShellRisk {

    public static final int SAFE = 0;
    public static final int MEDIUM = 1;
    public static final int HIGH = 2;

    private ShellRisk() {}

    public static String label(int risk) {
        if (risk == HIGH) return "高危";
        if (risk == MEDIUM) return "中危";
        return "一般";
    }

    /** 高危：毁机、改分区、掉权限、清数据 */
    private static final Pattern HIGH_PAT = Pattern.compile(
        "(^|[\\s;&|()`])(reboot|shutdown|poweroff|halt|fastboot|flash_image|simg2img|insmod|rmmod|setenforce\\s+0)(\\s|$)"
        + "|(^|[\\s;&|()`])(dd|mkfs|mkfs\\.[a-z0-9]+|mke2fs|fdisk|sfdisk|sgdisk|parted|e2fsck|wipe)(\\s|$)"
        + "|(^|[\\s;&|()`])rm\\s+(-[a-z]*\\s+)*-[a-z]*[rf][a-z]*"
        + "|\\bmount\\b[^\\n]*\\b(remount|--rw|,rw)\\b"
        + "|\\bpm\\s+(uninstall|disable|hide|clear|block|suspend)\\b"
        + "|\\bsetprop\\s+persist\\."
        + "|>\\s*/dev/block"
        + "|>\\s*/(system|vendor|boot|persist|firmware)"
        + "|\\b(cp|mv|ln|chmod|chown|tee|install|touch|mkdir)\\b[^\\n]*\\s/(system|vendor|boot|persist|firmware|data/adb)\\b"
    );

    /** 中危：改配置、改文件、杀进程、装权限 */
    private static final Pattern MEDIUM_PAT = Pattern.compile(
        "\\bsetprop\\b"
        + "|\\bsettings\\s+(put|delete)\\b"
        + "|\\bappops\\s+(set|reset)\\b"
        + "|\\b(kill|killall|pkill|renice)\\b"
        + "|\\bam\\s+(force-stop|start|startservice|broadcast|kill|set-)\\b"
        + "|\\bsvc\\s+[a-z]+\\s+(enable|disable)\\b"
        + "|(^|[\\s;&|(])(stop|start)\\s*$"
        + "|\\bmount\\b"
        + "|\\b(chmod|chown|mkdir|rmdir|touch|cp|mv|ln|rm|tee|install|truncate)\\b"
        + "|\\bsed\\b[^\\n]*-[a-z]*i"
        + "|\\bpm\\s+(grant|revoke|enable)\\b"
        + "|\\blogcat\\b[^\\n]*-[a-z]*c[a-z]*"
        + "|\\binput\\b"
        + "|\\bsendevent\\b"
        + "|(^|[\\s;&|(])su(\\s|$)"
        + "|\\bdumpsys\\b[^\\n]*(battery\\s+set|reset|unplug|simulate)"
        + "|\\bsqlite3\\b"
        + "|\\bsh\\s+-c"
    );

    /** 只读命令白名单（首 token） */
    private static final HashSet<String> SAFE_CMDS = new HashSet<String>(Arrays.asList(new String[]{
        "getprop", "getenforce", "id", "whoami", "uname", "date", "uptime",
        "ps", "top", "free", "df", "du", "ls", "cat", "head", "tail", "wc",
        "grep", "find", "stat", "file", "readlink", "realpath", "which", "type",
        "pidof", "pgrep", "dumpsys", "logcat", "lsmod", "vmstat", "printenv",
        "env", "ifconfig", "netstat", "sleep", "echo", "printf", "true", "false",
        "wm", "settings", "appops", "pm", "cmd", "[", "test"
    }));

    /** shell 控制关键字：不单独执行，递归检查其后的剩余内容 */
    private static final HashSet<String> KEYWORDS = new HashSet<String>(Arrays.asList(new String[]{
        "for", "do", "done", "while", "until", "if", "then", "else", "elif", "fi", "{", "}", "("
    }));

    /** 命令分隔符：| ; && || 与换行 */
    private static final Pattern SPLIT_PAT = Pattern.compile("\\|\\||&&|[|;\\n]");

    /**
     * 判定风险等级。
     */
    public static int classify(String cmd) {
        if (cmd == null) return MEDIUM;
        String raw = cmd.trim();
        if (raw.length() == 0) return MEDIUM;
        String s = raw.toLowerCase(Locale.US);

        if (HIGH_PAT.matcher(s).find()) return HIGH;
        if (MEDIUM_PAT.matcher(s).find()) return MEDIUM;
        if (s.indexOf('>') >= 0) return MEDIUM;   // 任何输出重定向都可能写文件

        String[] segs = SPLIT_PAT.split(s);
        for (int i = 0; i < segs.length; i++) {
            if (!isSegmentSafe(segs[i])) return MEDIUM;
        }
        return SAFE;
    }

    /** 单段命令是否只读安全 */
    private static boolean isSegmentSafe(String seg) {
        String s = seg == null ? "" : seg.trim();
        if (s.length() == 0) return true;

        // 跳过前置的环境变量赋值：FOO=1 cmd ...
        for (int guard = 0; guard < 4; guard++) {
            int sp = s.indexOf(' ');
            if (sp <= 0) break;
            String head = s.substring(0, sp);
            if (head.indexOf('=') > 0) { s = s.substring(sp + 1).trim(); continue; }
            break;
        }
        if (s.length() == 0) return true;

        int sp = s.indexOf(' ');
        String name = sp < 0 ? s : s.substring(0, sp);
        String rest = sp < 0 ? "" : s.substring(sp + 1).trim();
        // 绝对路径调用：取 basename
        if (name.length() > 0 && name.charAt(0) == '/') {
            int slash = name.lastIndexOf('/');
            name = name.substring(slash + 1);
        }
        name = name.replace("(\"", "").replace("`", "").replace(";", "");

        // 命令替换 $(...) 与反引号可能藏任意命令，一律降级
        if (s.indexOf("$(") >= 0 || s.indexOf('`') >= 0) return false;

        // shell 关键字处理
        if (KEYWORDS.contains(name)) {
            // for / while / until 后面是变量列表与集合，不是命令本身
            if ("for".equals(name) || "while".equals(name) || "until".equals(name)) return true;
            // 纯结构关键字，无命令内容
            if ("done".equals(name) || "fi".equals(name)
                    || "}".equals(name) || "{".equals(name) || "(".equals(name)) return true;
            // do / then / else / elif / if：递归检查其后的真实命令
            return isSegmentSafe(rest);
        }

        if (!SAFE_CMDS.contains(name)) return false;

        if ("logcat".equals(name)) {
            return rest.indexOf("-d") >= 0 || rest.indexOf("-t") >= 0;
        }
        if ("settings".equals(name)) {
            return rest.startsWith("get") || rest.startsWith("list");
        }
        if ("appops".equals(name)) {
            return rest.startsWith("get");
        }
        if ("pm".equals(name)) {
            return rest.startsWith("list") || rest.startsWith("path")
                || rest.startsWith("dump") || rest.startsWith("get-");
        }
        if ("cmd".equals(name)) {
            return rest.indexOf(" list") >= 0 || rest.startsWith("list")
                || rest.indexOf(" get") >= 0 || rest.startsWith("get")
                || rest.indexOf("help") >= 0;
        }
        if ("wm".equals(name)) {
            return rest.equals("size") || rest.equals("density");
        }
        if ("date".equals(name)) {
            return rest.indexOf("-s") < 0 && rest.indexOf("set") < 0;
        }
        if ("find".equals(name)) {
            return rest.indexOf("-delete") < 0 && rest.indexOf("-exec") < 0;
        }
        return true;
    }

    /**
     * 用户白名单：每行一个正则（或纯文本），命中则视为安全。
     */
    public static boolean isWhitelisted(String cmd, String whitelistText) {
        if (cmd == null || whitelistText == null) return false;
        String text = whitelistText.trim();
        if (text.length() == 0) return false;
        String lower = cmd.toLowerCase(Locale.US);
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String rule = lines[i] == null ? "" : lines[i].trim();
            if (rule.length() == 0 || rule.startsWith("#")) continue;
            try {
                if (Pattern.compile(rule, Pattern.CASE_INSENSITIVE).matcher(cmd).find()) return true;
            } catch (Throwable t) {
                if (lower.indexOf(rule.toLowerCase(Locale.US)) >= 0) return true;
            }
        }
        return false;
    }
}
