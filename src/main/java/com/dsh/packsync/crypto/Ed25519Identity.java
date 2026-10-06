package com.dsh.packsync.crypto;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * 长期身份密钥（Ed25519），用于 TOFU（首次信任）模式。
 *
 * <p>它和 {@link X25519} 的分工：
 * <ul>
 *   <li>X25519 每次连接**临时生成**，负责协商出会话密钥（前向保密）；</li>
 *   <li>Ed25519 是**长期不变**的身份，负责让客户端认出「还是那台服务器」。</li>
 * </ul>
 * 只有签名密钥长期保存在磁盘上，它**不参与**会话密钥派生 ——
 * 即使服务端主机被拖库，拿到身份私钥也无法解密历史流量。
 *
 * <p>落盘格式刻意设计成「种子 + 公钥」共 64 字节：JDK 的 Ed25519 私钥
 * 是纯 32 字节种子，**无法从中反推公钥**（没有公开 API 做标量乘法），
 * 所以公钥必须一起存。{@link #decode} 会用「签一条固定消息再验签」
 * 交叉验证两者确实配对，避免拼接错位这种很难定位的故障。
 */
public final class Ed25519Identity {

    public static final int SEED_LEN = 32;
    public static final int PUBLIC_KEY_LEN = 32;
    public static final int SIGNATURE_LEN = 64;

    private static final byte[] PKCS8_PREFIX = Bytes.fromHex("302e020100300506032b657004220420");
    private static final byte[] SPKI_PREFIX = Bytes.fromHex("302a300506032b6570032100");
    private static final byte[] SELF_CHECK_PROBE = Bytes.utf8("packsync/identity-selfcheck/v1");

    private final byte[] seed;
    private final byte[] publicKey;

    private Ed25519Identity(byte[] seed, byte[] publicKey) {
        this.seed = seed.clone();
        this.publicKey = publicKey.clone();
    }

    public static Ed25519Identity generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            java.security.KeyPair kp = gen.generateKeyPair();
            return new Ed25519Identity(
                    strip(kp.getPrivate().getEncoded(), PKCS8_PREFIX, SEED_LEN, "Ed25519 PKCS#8"),
                    strip(kp.getPublic().getEncoded(), SPKI_PREFIX, PUBLIC_KEY_LEN, "Ed25519 X.509"));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Ed25519 身份密钥", e);
        }
    }

    /** 落盘格式：种子(32) + 公钥(32)。 */
    public static Ed25519Identity fromSeedAndPublic(byte[] seed, byte[] publicKey) {
        if (seed == null || seed.length != SEED_LEN) {
            throw new IllegalArgumentException("Ed25519 种子必须是 " + SEED_LEN + " 字节");
        }
        if (publicKey == null || publicKey.length != PUBLIC_KEY_LEN) {
            throw new IllegalArgumentException("Ed25519 公钥必须是 " + PUBLIC_KEY_LEN + " 字节");
        }
        Ed25519Identity candidate = new Ed25519Identity(seed, publicKey);
        if (!verify(publicKey, SELF_CHECK_PROBE, candidate.sign(SELF_CHECK_PROBE))) {
            throw new IllegalArgumentException("Ed25519 种子与公钥不配对（文件被篡改或拼接错位）");
        }
        return candidate;
    }

    public byte[] encode() {
        return Bytes.concat(seed, publicKey);
    }

    public static Ed25519Identity decode(byte[] encoded) {
        if (encoded == null || encoded.length != SEED_LEN + PUBLIC_KEY_LEN) {
            throw new IllegalArgumentException("身份编码必须是 " + (SEED_LEN + PUBLIC_KEY_LEN)
                    + " 字节，实际 " + (encoded == null ? "null" : encoded.length));
        }
        byte[] seed = new byte[SEED_LEN];
        byte[] pub = new byte[PUBLIC_KEY_LEN];
        System.arraycopy(encoded, 0, seed, 0, SEED_LEN);
        System.arraycopy(encoded, SEED_LEN, pub, 0, PUBLIC_KEY_LEN);
        return fromSeedAndPublic(seed, pub);
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] sign(byte[] message) {
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey priv = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Bytes.concat(PKCS8_PREFIX, seed)));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 签名失败", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != PUBLIC_KEY_LEN
                || signature == null || signature.length != SIGNATURE_LEN) {
            return false;
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PublicKey pub = factory.generatePublic(
                    new X509EncodedKeySpec(Bytes.concat(SPKI_PREFIX, publicKey)));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 人类可读指纹：SHA-256(公钥) 的 4 字符分组形式。 */
    public String fingerprint() {
        return fingerprintOf(publicKey);
    }

    public static String fingerprintOf(byte[] publicKey) {
        return Bytes.fingerprint(Bytes.sha256(publicKey));
    }

    private static byte[] strip(byte[] der, byte[] prefix, int rawLen, String what) {
        if (der.length != prefix.length + rawLen) {
            throw new IllegalStateException(what + " 长度异常：" + der.length);
        }
        for (int i = 0; i < prefix.length; i++) {
            if (der[i] != prefix[i]) {
                throw new IllegalStateException(what + " 前缀不是预期的 Ed25519 形式");
            }
        }
        byte[] raw = new byte[rawLen];
        System.arraycopy(der, prefix.length, raw, 0, rawLen);
        return raw;
    }
}
