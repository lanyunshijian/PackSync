package com.dsh.packsync.core.util;

/**
 * 解析玩家输入的服务器地址，并规范化成可用的 host / port。
 *
 * <p>接受这几种写法（都能认）：
 * <pre>
 *   play.example.com
 *   play.example.com:25565
 *   192.168.1.10
 *   192.168.1.10:25566
 *   [::1]:25565
 *   ::1
 *   2409:8d28:12a:e52:e981:6774:7bdb:88e8
 *   2409:8d28:12a:e52:e981:6774:7bdb:88e8:25565      ← 裸 IPv6 + 端口
 * </pre>
 *
 * <p>最后一种写法没有方括号，按 RFC 3986 是区分不出「端口」的。这里用可判定的
 * 规则处理：只有当【去掉最后一段后剩下的仍是合法 IPv6】且【最后一段是合法端口号】
 * 时，才把最后一段当端口；否则整体视作 IPv6 地址。
 *
 * <p>这条规则曾经缺失：IPv6 联机的玩家被记成
 * {@code host = "2409:8d28:12a:e52:e981:6774:7bdb:88e8:25565"}，端口混进了 host，
 * 最终拼出 {@code http://2409:...:88e8:25565:25566/packsync/v1} 这种非法 URL，
 * 同步以 {@code MalformedURLException} 直接失败。
 *
 * <p>放在 core（零 MC 依赖）而不是界面包里：它可以被纯 JUnit 直接验证，
 * 而地址解析错了会让玩家"填了地址却连不上"，且很难自查。
 */
public final class ServerAddressParser {

    private ServerAddressParser() {
    }

    /** 取出主机部分：剥掉方括号、剥掉端口；裸 IPv6 原样返回（不带括号）。 */
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
        int first = s.indexOf(':');
        if (first < 0) {
            return s;                        // 纯主机名 / IPv4
        }
        int last = s.lastIndexOf(':');
        if (first == last) {
            return s.substring(0, first);     // host:port
        }
        // 多于一个冒号：要么是裸 IPv6，要么是「裸 IPv6 + 端口」
        String head = s.substring(0, last);
        String tail = s.substring(last + 1);
        if (isPortNumber(tail) && isIpv6Literal(head)) {
            return head;
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
        if (first < 0) {
            return fallback;
        }
        int last = s.lastIndexOf(':');
        if (first == last) {
            return parseIntOr(s.substring(first + 1), fallback);
        }
        String head = s.substring(0, last);
        String tail = s.substring(last + 1);
        if (isPortNumber(tail) && isIpv6Literal(head)) {
            return parseIntOr(tail, fallback);
        }
        return fallback;
    }

    /**
     * 拼 URL 时用的主机表示：IPv6 必须加方括号。
     *
     * <p>{@code "http://" + host + ":" + port} 对 IPv6 会产出非法 URL
     * （{@code http://2409:...:88e8:25566/...}），必须写成
     * {@code http://[2409:...:88e8]:25566/...}。已经带括号的原样返回。
     */
    public static String urlHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.isEmpty() || h.startsWith("[")) {
            return h;
        }
        return h.indexOf(':') >= 0 ? "[" + h + "]" : h;
    }

    /** 是否是合法端口号（1..65535）。 */
    public static boolean isPortNumber(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            int p = Integer.parseInt(text.trim());
            return p > 0 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 是否是 IPv6 字面量。纯文本判断，不做 DNS 查询。
     *
     * <p>认两种形态：恰好 8 段的完整写法，或含一个 {@code ::} 的压缩写法。
     */
    public static boolean isIpv6Literal(String text) {
        if (text == null || text.indexOf(':') < 0) {
            return false;
        }
        if (!text.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        int dc = text.indexOf("::");
        if (dc >= 0) {
            return dc == text.lastIndexOf("::");
        }
        return text.split(":", -1).length == 8;
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
