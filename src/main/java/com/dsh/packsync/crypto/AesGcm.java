package com.dsh.packsync.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 认证加密。
 *
 * <p>选 GCM 而不是「AES-CBC + 单独 HMAC」的原因：GCM 是 AEAD，
 * 一次调用同时给出**机密性**与**完整性**，不存在「先加密后校验的顺序写错」
 * 或「IV 可预测」这类经典漏洞。
 *
 * <p>关于 nonce 复用：GCM 下**同一密钥复用 nonce 会直接导致密钥流泄漏**，
 * 是最致命的误用。所以这里不接受调用方随意传 nonce —— 上层
 * {@link SecureSession} 用「方向前缀 + 单调计数器」统一生成，
 * 并在解密侧强制单调递增，同时顺带实现重放防护。
 */
public final class AesGcm {

    public static final int KEY_LEN = 32;   // AES-256
    public static final int NONCE_LEN = 12; // GCM 标准值
    public static final int TAG_BITS = 128; // 认证标签位数

    private AesGcm() {
    }

    /**
     * 加密。
     *
     * @param aad 附加认证数据：会被完整性保护但**不加密**。
     *            我们把「消息类型 + 序号 + 双方公钥指纹」放进 aad，
     *            这样攻击者无法把一条消息从某个会话/某个用途挪到别处复用。
     * @return 密文（含 16 字节认证标签）
     */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        checkKey(key);
        checkNonce(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密并验签。
     *
     * @return 明文
     * @throws AuthenticationException 密文被篡改、aad 不匹配或密钥不对时抛出
     *                                 （GCM 无法区分这几种情况，这是设计使然）
     */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext)
            throws AuthenticationException {
        checkKey(key);
        checkNonce(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new AuthenticationException("AES-GCM 校验失败（密文被篡改 / 密钥或 AAD 不匹配）", e);
        }
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException("AES-256 需要 " + KEY_LEN + " 字节密钥，实际 "
                    + (key == null ? "null" : key.length));
        }
    }

    private static void checkNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("GCM nonce 必须是 " + NONCE_LEN + " 字节，实际 "
                    + (nonce == null ? "null" : nonce.length));
        }
    }

    /** 认证失败。单独一个异常类型，方便上层区分「通道被攻击/损坏」与「程序 bug」。 */
    public static class AuthenticationException extends Exception {
        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }

        public AuthenticationException(String message) {
            super(message);
        }
    }
}
