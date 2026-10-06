package com.dsh.packsync.crypto;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;

/**
 * X25519（RFC 7748）临时密钥协商 —— 提供**前向保密**的会话密钥。
 *
 * <p>为什么选 X25519：它是 JDK 11+ 自带的 {@code XDH} 算法（无需第三方库），
 * 密钥只有 32 字节，且每次连接都换新密钥对 —— 即使某次会话密钥泄露，
 * 也推不出历史会话（前向保密）。这是「传输过程需要加密」这一需求的底座。
 *
 * <p>JDK 只提供 DER 编码的密钥（X.509 / PKCS#8），而 DER 对 X25519 来说
 * 是「固定前缀 + 32 字节裸密钥」。本类负责这层转换，并在前缀不符时**直接抛异常**
 * 而不是猜 —— 静默用错字节会导致协商出的密钥两边不一致，症状会非常难查。
 */
public final class X25519 {

    /** X25519 公钥裸长度。 */
    public static final int KEY_LEN = 32;

    // SubjectPublicKeyInfo: SEQUENCE(42) { SEQUENCE { OID 1.3.101.110 } BIT STRING(33) 00 <32B> }
    private static final byte[] SPKI_PREFIX = Bytes.fromHex("302a300506032b656e032100");
    // PrivateKeyInfo:       SEQUENCE(46) { INTEGER 0, SEQUENCE { OID 1.3.101.110 }, OCTET STRING(34) <32B> }
    private static final byte[] PKCS8_PREFIX = Bytes.fromHex("302e020100300506032b656e04220420");

    private X25519() {
    }

    /** 一对裸密钥（各 32 字节）。访问器做了防御性拷贝。 */
    public static final class Keys {
        private final byte[] privateKey;
        private final byte[] publicKey;

        Keys(byte[] privateKey, byte[] publicKey) {
            this.privateKey = privateKey.clone();
            this.publicKey = publicKey.clone();
        }

        public byte[] privateKey() {
            return privateKey.clone();
        }

        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    public static Keys generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("X25519");
            java.security.KeyPair kp = gen.generateKeyPair();
            return new Keys(stripPrefix(kp.getPrivate().getEncoded(), PKCS8_PREFIX, "PKCS#8"),
                    stripPrefix(kp.getPublic().getEncoded(), SPKI_PREFIX, "X.509"));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 X25519 密钥对", e);
        }
    }

    /**
     * 计算 ECDH 共享秘密。
     *
     * @param myPrivate   本端裸私钥（32 字节）
     * @param theirPublic 对端裸公钥（32 字节）
     */
    public static byte[] sharedSecret(byte[] myPrivate, byte[] theirPublic) {
        try {
            KeyFactory factory = KeyFactory.getInstance("X25519");
            PrivateKey priv = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Bytes.concat(PKCS8_PREFIX, require32(myPrivate, "本端私钥"))));
            PublicKey pub = factory.generatePublic(
                    new X509EncodedKeySpec(Bytes.concat(SPKI_PREFIX, require32(theirPublic, "对端公钥"))));

            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(priv);
            ka.doPhase(pub, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("X25519 密钥协商失败", e);
        }
    }

    /**
     * 由裸公钥推导「公开可发布的」指纹（SHA-256）。
     * 用它做 TOFU 的信任锚：客户端记住这个值，之后变了就报警。
     */
    public static byte[] publicKeyFingerprint(byte[] rawPublicKey) {
        return Bytes.sha256(require32(rawPublicKey, "公钥"));
    }

    private static byte[] stripPrefix(byte[] der, byte[] prefix, String what) {
        if (der.length != prefix.length + KEY_LEN) {
            throw new IllegalStateException(what + " 长度异常：" + der.length);
        }
        for (int i = 0; i < prefix.length; i++) {
            if (der[i] != prefix[i]) {
                throw new IllegalStateException(what + " 前缀不是预期的 X25519 形式（JDK 编码变了？）");
            }
        }
        byte[] raw = new byte[KEY_LEN];
        System.arraycopy(der, prefix.length, raw, 0, KEY_LEN);
        return raw;
    }

    private static byte[] require32(byte[] key, String what) {
        if (key == null || key.length != KEY_LEN) {
            throw new IllegalArgumentException(what + " 必须是 " + KEY_LEN + " 字节，实际 "
                    + (key == null ? "null" : key.length));
        }
        return key;
    }

    /** 全零公钥是 X25519 的低阶点，协商结果可预测；显式拒绝。 */
    public static boolean isAllZero(byte[] key) {
        for (byte b : key) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** 供上层使用：校验对端公钥是否可用。 */
    public static void validatePeerPublicKey(byte[] raw) {
        require32(raw, "对端公钥");
        if (isAllZero(raw)) {
            throw new IllegalArgumentException("对端公钥为全零（低阶点攻击特征），拒绝握手");
        }
    }
}
