package com.dsh.packsync.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 字节与编码工具。整个 crypto 包**不依赖 Minecraft**，只依赖 JDK，
 * 因此可以用普通 JUnit / 纯 javac 直接验证（见 src/test 与 tools/selftest-crypto.sh）。
 */
public final class Bytes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Bytes() {
    }

    /** 密码学安全随机字节。 */
    public static byte[] random(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /**
     * 常量时间比较。
     *
     * <p>用于比较 MAC / 指纹 / 密钥等敏感值：普通 {@code Arrays.equals} 会在第一个
     * 不同字节处提前返回，攻击者能通过计时差逐字节猜出正确值。
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(a, b);
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    public static String hex(byte[] b, int off, int len) {
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String s) {
        String t = s.replaceAll("[\\s:.-]", "");
        if ((t.length() & 1) != 0) {
            throw new IllegalArgumentException("hex 长度必须是偶数：" + t.length());
        }
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 把指纹排成 4 字符一组的易读形式，例如 {@code 3f2a 91c4 ...}。 */
    public static String fingerprint(byte[] digest) {
        String h = hex(digest);
        StringBuilder sb = new StringBuilder(h.length() + h.length() / 4);
        for (int i = 0; i < h.length(); i += 4) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(h, i, Math.min(i + 4, h.length()));
        }
        return sb.toString();
    }

    /**
     * 定长写整数（大端）。握手消息里用它拼 transcript，
     * 定长可以避免「长度不同的输入拼出相同字节串」这类歧义。
     */
    public static byte[] u32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    public static byte[] u64(long v) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (v >>> (56 - 8 * i));
        }
        return out;
    }

    /** 带长度前缀的字节串（u32 长度 + 内容），同样是为了消除拼接歧义。 */
    public static byte[] lengthPrefixed(byte[] b) {
        return concat(u32(b.length), b);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 截断为零填充的十进制字节数，仅用于日志展示。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }
}
