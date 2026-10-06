package com.dsh.packsync.client.gui;

/**
 * 解析玩家输入的服务器地址。
 *
 * <p>接受这几种写法（都能认）：
 * <pre>
 *   play.example.com
 *   play.example.com:25565
 *   192.168.1.10
 *   192.168.1.10:25566
 *   [::1]:25565
 *   ::1
 * </pre>
 *
 * <p>单独成类而不是塞进界面里：它可以被纯 JUnit 直接验证，
 * 而地址解析错了会让玩家"填了地址却连不上"，且很难自查。
 */
public final class ServerAddressParser {

    private ServerAddressParser() {
    }

    /** 取出主机部分（IPv6 的方括号会被剥掉）。 */
    public static String host(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        // [::1]:25565 或 [::1]
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s.substring(1);
        }
        // 只有一个冒号 → host:port；多于一个 → 是裸 IPv6，整体当 host
        int first = s.indexOf(':');
        int last = s.lastIndexOf(':');
        if (first > 0 && first == last) {
            return s.substring(0, first);
        }
        return s;
    }

    /** 取出端口；解析不到就用兜底值。 */
    public static int port(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0 && end + 1 < s.length() && s.charAt(end + 1) == ':') {
                return parseIntOr(s.substring(end + 2), fallback);
            }
            return fallback;
        }
        int first = s.indexOf(':');
        int last = s.lastIndexOf(':');
        if (first > 0 && first == last) {
            return parseIntOr(s.substring(first + 1), fallback);
        }
        return fallback;
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return (p > 0 && p <= 65535) ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
