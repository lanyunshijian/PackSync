package com.dsh.packsync.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 密码学原语的验证。
 *
 * <p>关键测试用 **RFC 官方测试向量**，而不是「自己加密再自己解密」——
 * 后者只能证明代码自洽，证明不了它符合标准。用标准向量才能保证
 * 将来换实现、或与别的语言互通时不会静默不一致。
 */
class CryptoPrimitivesTest {

    // ------------------------------------------------------------------ X25519

    @Test
    @DisplayName("X25519 与 RFC 7748 §6.1 官方向量一致（证明裸密钥提取正确）")
    void x25519MatchesRfc7748TestVector() {
        byte[] alicePriv = Bytes.fromHex(
                "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] alicePub = Bytes.fromHex(
                "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        byte[] bobPriv = Bytes.fromHex(
                "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] bobPub = Bytes.fromHex(
                "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
        byte[] expectedShared = Bytes.fromHex(
                "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");

        // 由私钥推出公钥：与 basepoint(9) 做 ECDH 就是公钥
        byte[] basepoint = new byte[X25519.KEY_LEN];
        basepoint[0] = 9;
        assertArrayEquals(alicePub, X25519.sharedSecret(alicePriv, basepoint),
                "Alice 公钥推导不符");
        assertArrayEquals(bobPub, X25519.sharedSecret(bobPriv, basepoint), "Bob 公钥推导不符");

        // 双方协商出同一个共享秘密
        assertArrayEquals(expectedShared, X25519.sharedSecret(alicePriv, bobPub));
        assertArrayEquals(expectedShared, X25519.sharedSecret(bobPriv, alicePub));
    }

    @Test
    @DisplayName("X25519 随机生成的密钥对可以互相协商出相同秘密")
    void x25519SharedSecretIsSymmetric() {
        X25519.Keys a = X25519.generate();
        X25519.Keys b = X25519.generate();
        byte[] ab = X25519.sharedSecret(a.privateKey(), b.publicKey());
        byte[] ba = X25519.sharedSecret(b.privateKey(), a.publicKey());
        assertArrayEquals(ab, ba);
        assertEquals(32, ab.length);
        assertFalse(X25519.isAllZero(ab));
        assertNotEquals(Bytes.hex(a.publicKey()), Bytes.hex(b.publicKey()));
    }

    @Test
    @DisplayName("全零公钥（低阶点）被拒绝")
    void x25519RejectsLowOrderPoint() {
        assertThrows(IllegalArgumentException.class,
                () -> X25519.validatePeerPublicKey(new byte[X25519.KEY_LEN]));
    }

    // -------------------------------------------------------------------- HKDF

    @Test
    @DisplayName("HKDF 与 RFC 5869 Test Case 1 官方向量一致")
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = Bytes.fromHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = Bytes.fromHex("000102030405060708090a0b0c");
        byte[] info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
                Bytes.hex(prk), "HKDF-Extract 结果不符");

        byte[] okm = Hkdf.expand(prk, info, 42);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865",
                Bytes.hex(okm), "HKDF-Expand 结果不符");
    }

    @Test
    @DisplayName("HKDF 的 info 起域隔离作用：换 info 就换密钥")
    void hkdfInfoSeparatesKeys() {
        byte[] ikm = Bytes.random(32);
        byte[] salt = Bytes.random(32);
        byte[] a = Hkdf.derive(salt, ikm, "purpose-a", 32);
        byte[] b = Hkdf.derive(salt, ikm, "purpose-b", 32);
        assertFalse(Bytes.constantTimeEquals(a, b), "不同用途必须得到不同密钥");

        // 长度超限必须报错（防止实现里静默截断）
        assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(Hkdf.extract(salt, ikm), new byte[0], 255 * 32 + 1));
    }

    // ---------------------------------------------------------------- AES-GCM

    @Test
    @DisplayName("AES-256-GCM 加解密往返")
    void aesGcmRoundTrip() throws Exception {
        byte[] key = Bytes.random(AesGcm.KEY_LEN);
        byte[] nonce = Bytes.random(AesGcm.NONCE_LEN);
        byte[] aad = Bytes.utf8("context");
        byte[] plaintext = Bytes.utf8("服务端缺失的模组清单：jei, jade, sodium");

        byte[] sealed = AesGcm.seal(key, nonce, aad, plaintext);
        assertFalse(Bytes.hex(sealed).contains(Bytes.hex(plaintext)));
        assertArrayEquals(plaintext, AesGcm.open(key, nonce, aad, sealed));
    }

    @Test
    @DisplayName("AES-GCM 能发现密文被篡改（哪怕只改一个比特）")
    void aesGcmDetectsTampering() {
        byte[] key = Bytes.random(32);
        byte[] nonce = Bytes.random(12);
        byte[] sealed = AesGcm.seal(key, nonce, null, Bytes.utf8("原始内容"));

        byte[] flipped = sealed.clone();
        flipped[0] ^= 0x01;
        assertThrows(AesGcm.AuthenticationException.class,
                () -> AesGcm.open(key, nonce, null, flipped));
    }

    @Test
    @DisplayName("AES-GCM 能发现 AAD 被换（防止跨用途挪包）")
    void aesGcmDetectsWrongAad() {
        byte[] key = Bytes.random(32);
        byte[] nonce = Bytes.random(12);
        byte[] sealed = AesGcm.seal(key, nonce, Bytes.utf8("modlist"), Bytes.utf8("内容"));

        assertThrows(AesGcm.AuthenticationException.class,
                () -> AesGcm.open(key, nonce, Bytes.utf8("chunk"), sealed));
        assertThrows(AesGcm.AuthenticationException.class,
                () -> AesGcm.open(Bytes.random(32), nonce, Bytes.utf8("modlist"), sealed));
    }

    // ---------------------------------------------------------------- Ed25519

    @Test
    @DisplayName("Ed25519 签名/验签往返，且篡改消息后验签失败")
    void ed25519SignVerifyRoundTrip() {
        Ed25519Identity id = Ed25519Identity.generate();
        byte[] message = Bytes.utf8("packsync/v1/transcript");
        byte[] signature = id.sign(message);

        assertEquals(Ed25519Identity.SIGNATURE_LEN, signature.length);
        assertTrue(Ed25519Identity.verify(id.publicKey(), message, signature));
        assertFalse(Ed25519Identity.verify(id.publicKey(), Bytes.utf8("被改过的消息"), signature));

        Ed25519Identity other = Ed25519Identity.generate();
        assertFalse(Ed25519Identity.verify(other.publicKey(), message, signature),
                "换一把公钥就不该验得过");
    }

    @Test
    @DisplayName("Ed25519 身份编码/解码往返，且拒绝不配对的种子与公钥")
    void ed25519IdentityEncodeDecode() {
        Ed25519Identity id = Ed25519Identity.generate();
        Ed25519Identity restored = Ed25519Identity.decode(id.encode());

        assertArrayEquals(id.publicKey(), restored.publicKey());
        assertEquals(id.fingerprint(), restored.fingerprint());

        byte[] tampered = id.encode();
        tampered[0] ^= 0x7F; // 改种子，公钥不动 -> 两者不再配对
        assertThrows(IllegalArgumentException.class, () -> Ed25519Identity.decode(tampered));
    }

    @Test
    @DisplayName("指纹格式稳定且随密钥变化")
    void fingerprintFormat() {
        Ed25519Identity a = Ed25519Identity.generate();
        Ed25519Identity b = Ed25519Identity.generate();
        assertNotEquals(a.fingerprint(), b.fingerprint());
        // 4 字符一组，共 16 组（SHA-256 = 64 hex）
        assertEquals(16, a.fingerprint().split(" ").length);
    }

    // -------------------------------------------------------------------- Buf

    @Test
    @DisplayName("Buf 拒绝截断的包与超长长度前缀（内存耗尽攻击）")
    void bufHardening() {
        byte[] encoded = Buf.writer().u32(1).str("你好").bytes(new byte[]{1, 2, 3}).toBytes();

        // 截断：必须读到缺失的部分才会报错，所以要把整条读完
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(Buf.ProtocolFormatException.class, () -> {
            Buf in = Buf.reader(truncated);
            in.readU32();
            in.readStr(16);
            in.readBytes(3);
        });

        // 伪造一个巨大的长度前缀，必须被上限挡住
        byte[] evil = Buf.writer().u32(Integer.MAX_VALUE).toBytes();
        assertThrows(Buf.ProtocolFormatException.class,
                () -> Buf.reader(evil).readLengthPrefixed(1024));

        // 尾部多余字节会被发现
        Buf in = Buf.reader(encoded);
        in.readU32();
        in.readStr(16);
        in.readBytes(3);
        in.requireFullyRead(); // 恰好读完，不应抛

        // 尾部藏了多余字节：必须被发现，否则会出现「同一个包两种解读」
        Buf withExtra = Buf.reader(
                Buf.writer().u32(1).bytes(new byte[]{9}).u8(0x7F).toBytes());
        withExtra.readU32();
        withExtra.readBytes(1);
        assertThrows(Buf.ProtocolFormatException.class, withExtra::requireFullyRead);
    }

    @Test
    @DisplayName("常量时间比较对长度不同的输入也安全返回 false")
    void constantTimeEqualsHandlesDifferentLengths() {
        assertTrue(Bytes.constantTimeEquals(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}));
        assertFalse(Bytes.constantTimeEquals(new byte[]{1, 2, 3}, new byte[]{1, 2}));
        assertFalse(Bytes.constantTimeEquals(null, new byte[]{1}));
    }
}
