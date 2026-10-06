package com.dsh.packsync.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF（RFC 5869）—— 从「可能有偏」的共享秘密里派生出「多把用途隔离的密钥」。
 *
 * <p>为什么必须有它：X25519 协商出来的 32 字节是群元素，直接当 AES 密钥用并不规范；
 * 而且我们要派生出**多个**密钥（客户端→服务端、服务端→客户端、每个文件一把），
 * 必须保证「一把密钥泄露不影响另一把」。HKDF 用 info 参数做域隔离，
 * 正是干这个的标准工具。
 *
 * <p>只用 JDK 自带的 HmacSHA256，无外部依赖。
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    /** 对外暴露的 HMAC-SHA256（握手用它做 PSK 认证）。 */
    public static byte[] hmac(byte[] key, byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] joined = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, joined, off, p.length);
            off += p.length;
        }
        return hmac(key, joined);
    }

    /** RFC 5869 §2.2 Extract：把任意长度的输入压成一把伪随机密钥 PRK。 */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] realSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        return hmac(realSalt, ikm);
    }

    /** RFC 5869 §2.3 Expand：把 PRK 拉长到所需长度，并用 info 做用途隔离。 */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("HKDF 输出长度越界：" + length);
        }
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int off = 0;
        for (int counter = 1; off < length; counter++) {
            block = hmac(prk, Bytes.concat(block, info, new byte[]{(byte) counter}));
            int n = Math.min(block.length, length - off);
            System.arraycopy(block, 0, out, off, n);
            off += n;
        }
        return out;
    }

    /** extract + expand 一步到位。 */
    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }

    public static byte[] derive(byte[] salt, byte[] ikm, String info, int length) {
        return derive(salt, ikm, Bytes.utf8(info), length);
    }
}
