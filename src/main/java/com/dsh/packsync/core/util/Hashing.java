package com.dsh.packsync.core.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件哈希工具。零 MC 依赖。
 *
 * <p>用到三种哈希，各有明确分工：
 * <ul>
 *   <li><b>SHA-1</b> —— 整合包清单的主键与完整性校验；也是 Modrinth 反查直链的键。</li>
 *   <li><b>CurseForge murmur</b> —— 只用于 CurseForge 的指纹反查。
 *       <b>算法必须与 CurseForge 官方逐位一致</b>，否则永远匹配不到文件，
 *       所以这里照 MurmurHash2(seed=1, 过滤空白后长度) 完整实现。</li>
 *   <li><b>SHA-256</b> —— 密钥指纹等安全用途。</li>
 * </ul>
 *
 * <p>所有实现都是**流式**的：整合包里的 jar 可能上百 MB，
 * 不应为了算个哈希就把整个文件读进内存。
 */
public final class Hashing {

    private static final int MURMUR_M = 0x5BD1E995; // 1540483477

    private Hashing() {
    }

    // ── SHA-1 ─────────────────────────────────────────────────────────────

    /** 文件的 SHA-1（小写十六进制）。失败返回 null。 */
    public static String sha1(Path file) {
        try {
            return digestHex(file, "SHA-1");
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static String sha256(Path file) {
        try {
            return digestHex(file, "SHA-256");
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String digestHex(Path file, String algorithm) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    // ── CurseForge murmur ─────────────────────────────────────────────────

    /**
     * CurseForge 指纹（MurmurHash2-32，seed=1）。
     *
     * <p>算法要点（与官方一致）：
     * <ol>
     *   <li>先把 {@code \t \n \r space} 四种空白字节**全部剔除**；</li>
     *   <li>初始值 {@code h = seed ^ 过滤后字节数}；</li>
     *   <li>每 4 字节小端块：{@code k *= m; k ^= k >>> 24; k *= m; h *= m; h ^= k;}；</li>
     *   <li>尾部不足 4 字节：{@code h ^= k; h *= m;}（注意**不**做 k 的两次乘法）；</li>
     *   <li>收尾 {@code h ^= h >>> 13; h *= m; h ^= h >>> 15;}；</li>
     *   <li>输出**无符号** 32 位十进制字符串。</li>
     * </ol>
     *
     * <p>两遍扫描（先数长度、再算哈希）是刻意的：初始值依赖"过滤后"长度，
     * 只有先知道它才能开始算。这样换来恒定内存占用。
     *
     * @return 十进制字符串；文件不可读时返回 null。
     */
    public static String curseforgeMurmur(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            long length = countFilteredBytes(file);
            int h = 1 ^ (int) length; // seed = 1
            int k = 0;
            int shift = 0;

            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        int b = buf[i] & 0xFF;
                        if (b == 9 || b == 10 || b == 13 || b == 32) {
                            continue;
                        }
                        k |= b << shift;
                        shift += 8;
                        if (shift == 32) {
                            k *= MURMUR_M;
                            k ^= k >>> 24;
                            k *= MURMUR_M;
                            h *= MURMUR_M;
                            h ^= k;
                            k = 0;
                            shift = 0;
                        }
                    }
                }
            }
            if (shift > 0) {
                h ^= k;
                h *= MURMUR_M;
            }
            h ^= h >>> 13;
            h *= MURMUR_M;
            h ^= h >>> 15;
            return Integer.toUnsignedString(h);
        } catch (IOException e) {
            return null;
        }
    }

    private static long countFilteredBytes(Path file) throws IOException {
        long count = 0L;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xFF;
                    if (b != 9 && b != 10 && b != 13 && b != 32) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String toHex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 人类可读大小，仅用于日志与界面。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024.0;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format("%.1f %s", v, units[u]);
    }
}
