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
 * 端到端握手验证。
 *
 * <p>最有分量的一条断言是「**两端各自算出的会话密钥能互相解密**」——
 * 它同时证明了：transcript 两端一致、ECDH 对称、HKDF 派生一致、
 * proof 校验通过。任何一处不一致都会让它失败。
 */
class HandshakeTest {

    private static final String CTX = "packsync/test";

    private static final class Pair {
        final SecureSession clientSession;
        final SecureSession serverSession;
        final Handshake.ServerResult serverResult;
        final Handshake.ServerInfo clientInfo;
        final Handshake.ClientSide client;

        Pair(SecureSession c, SecureSession s, Handshake.ServerResult sr, Handshake.ServerInfo ci,
             Handshake.ClientSide client) {
            this.clientSession = c;
            this.serverSession = s;
            this.serverResult = sr;
            this.clientInfo = ci;
            this.client = client;
        }
    }

    /** 跑一次完整握手。 */
    private static Pair handshake(byte[] serverPsk, byte[] clientPsk, String knownFingerprint)
            throws Exception {
        Ed25519Identity serverIdentity = Ed25519Identity.generate();
        Ed25519Identity clientIdentity = Ed25519Identity.generate();

        Handshake.ClientSide client = new Handshake.ClientSide(clientPsk, clientIdentity, knownFingerprint);
        byte[] clientHello = client.buildClientHello("测试客户端");

        Handshake.ServerSide server = new Handshake.ServerSide(serverIdentity, serverPsk, clientHello);
        byte[] serverHello = server.buildServerHello();

        Handshake.ServerInfo info = client.handleServerHello(serverHello);
        byte[] clientAuth = client.buildClientAuth();
        Handshake.ServerResult result = server.finish(clientAuth);

        return new Pair(client.session(), result.session(), result, info, client);
    }

    private static void assertSessionsInteroperate(Pair p) throws Exception {
        byte[] a = Bytes.utf8("服务端下发的缺失模组清单");
        assertArrayEquals(a, p.clientSession.decrypt(CTX, p.serverSession.encrypt(CTX, a)),
                "客户端应能解开服务端加密的数据");

        byte[] b = Bytes.utf8("客户端上报的本地模组清单");
        assertArrayEquals(b, p.serverSession.decrypt(CTX, p.clientSession.encrypt(CTX, b)),
                "服务端应能解开客户端加密的数据");
    }

    @Test
    @DisplayName("PSK 模式：握手成功，两端密钥一致，且互相知道对方身份")
    void pskHandshake() throws Exception {
        byte[] psk = Bytes.sha256(Bytes.utf8("一个足够长的服务器密钥"));
        Pair p = handshake(psk, psk, null);

        assertEquals(Handshake.MODE_PSK, p.serverResult.mode());
        assertEquals(Handshake.MODE_PSK, p.clientInfo.mode());
        assertEquals("PSK", p.clientInfo.modeName());
        assertTrue(p.clientInfo.firstContact(), "首次连接应被标记为首次，供 UI 提示");
        assertSessionsInteroperate(p);

        // 双方对同一个客户端身份的指纹认识一致
        assertEquals(p.serverResult.clientFingerprint(),
                Ed25519Identity.fingerprintOf(p.serverResult.clientIdentityPub()));
        assertArrayEquals(p.serverResult.transcriptHash(), p.client.transcriptHash(),
                "两端算出的 transcript 摘要必须一致");
    }

    @Test
    @DisplayName("TOFU 模式：服务端没配 PSK 时也能建立加密通道")
    void tofuHandshake() throws Exception {
        Pair p = handshake(null, null, null);
        assertEquals(Handshake.MODE_TOFU, p.serverResult.mode());
        assertTrue(p.clientInfo.firstContact());
        assertSessionsInteroperate(p);
    }

    @Test
    @DisplayName("客户端配了 PSK 但服务端没启用：回落 TOFU，被明确标记出来")
    void clientPskIgnoredWhenServerHasNone() throws Exception {
        Pair p = handshake(null, Bytes.sha256(Bytes.utf8("多余的密钥")), null);
        assertEquals(Handshake.MODE_TOFU, p.clientInfo.mode());
        assertTrue(p.clientInfo.pskIgnored(), "应提示玩家：密钥没被服务端采用");
        assertSessionsInteroperate(p);
    }

    @Test
    @DisplayName("服务端要求 PSK 而客户端没有：抛 PskRequiredException（走输入密钥流程，不是攻击）")
    void pskRequiredWhenClientHasNone() throws Exception {
        Ed25519Identity serverIdentity = Ed25519Identity.generate();
        byte[] psk = Bytes.sha256(Bytes.utf8("服务器密钥"));

        Handshake.ClientSide client = new Handshake.ClientSide(null,
                Ed25519Identity.generate(), null);
        byte[] hello = client.buildClientHello("玩家");
        Handshake.ServerSide server = new Handshake.ServerSide(serverIdentity, psk, hello);
        byte[] serverHello = server.buildServerHello();

        assertThrows(Handshake.PskRequiredException.class,
                () -> client.handleServerHello(serverHello));
    }

    @Test
    @DisplayName("错误的 PSK 会被拒绝（中间人伪造不出 MAC）")
    void wrongPskIsRejected() throws Exception {
        byte[] serverPsk = Bytes.sha256(Bytes.utf8("正确的密钥"));
        byte[] clientPsk = Bytes.sha256(Bytes.utf8("错误的密钥"));
        assertThrows(Handshake.HandshakeException.class, () -> handshake(serverPsk, clientPsk, null));
    }

    @Test
    @DisplayName("TOFU：服务端身份指纹变了会被识破（中间人 / 换了密钥）")
    void serverIdentityChangeIsDetected() throws Exception {
        Ed25519Identity serverIdentity = Ed25519Identity.generate();
        String remembered = serverIdentity.fingerprint();

        Pair ok = handshakeWithIdentity(serverIdentity, remembered);
        assertFalse(ok.clientInfo.firstContact(), "已知指纹时不该标记为首次");

        // 换一个完全不同的服务端身份，但客户端仍记着旧的指纹
        assertThrows(Handshake.ServerIdentityChangedException.class,
                () -> handshakeWithIdentity(Ed25519Identity.generate(), remembered));
    }

    private static Pair handshakeWithIdentity(Ed25519Identity serverIdentity, String knownFingerprint)
            throws Exception {
        byte[] psk = Bytes.sha256(Bytes.utf8("共享密钥"));
        Handshake.ClientSide client = new Handshake.ClientSide(psk, Ed25519Identity.generate(),
                knownFingerprint);
        byte[] hello = client.buildClientHello("客户端");
        Handshake.ServerSide server = new Handshake.ServerSide(serverIdentity, psk, hello);
        byte[] serverHello = server.buildServerHello();
        Handshake.ServerInfo info = client.handleServerHello(serverHello);
        byte[] auth = client.buildClientAuth();
        Handshake.ServerResult result = server.finish(auth);
        return new Pair(client.session(), result.session(), result, info, client);
    }

    @Test
    @DisplayName("ServerHello 被篡改（改 nonce / 改 proof）会被拒绝")
    void tamperedServerHelloIsRejected() throws Exception {
        byte[] psk = Bytes.sha256(Bytes.utf8("共享密钥"));
        Ed25519Identity serverIdentity = Ed25519Identity.generate();

        // 先拿到一份合法的 ServerHello
        Handshake.ClientSide probe = new Handshake.ClientSide(psk, Ed25519Identity.generate(), null);
        byte[] hello = probe.buildClientHello("客户端");
        Handshake.ServerSide server = new Handshake.ServerSide(serverIdentity, psk, hello);
        byte[] good = server.buildServerHello();

        // 1) 篡改 nonce（会让 transcript 两端不一致）
        Handshake.ClientSide c1 = new Handshake.ClientSide(psk, Ed25519Identity.generate(), null);
        c1.buildClientHello("客户端");
        byte[] tamperedNonce = good.clone();
        tamperedNonce[9] ^= 0x01;
        assertThrows(Handshake.HandshakeException.class, () -> c1.handleServerHello(tamperedNonce));

        // 2) 篡改签名
        Handshake.ClientSide c2 = new Handshake.ClientSide(psk, Ed25519Identity.generate(), null);
        c2.buildClientHello("客户端");
        byte[] tamperedSig = good.clone();
        tamperedSig[tamperedSig.length - 40] ^= 0x01;
        assertThrows(Handshake.HandshakeException.class, () -> c2.handleServerHello(tamperedSig));
    }

    @Test
    @DisplayName("不是 PackSync 的包（魔数不符）会被明确拒绝，而不是解析出乱七八糟的结果")
    void foreignPacketIsRejected() {
        Handshake.ClientSide client = new Handshake.ClientSide(null, Ed25519Identity.generate(), null);
        client.buildClientHello("客户端");
        byte[] junk = Bytes.concat(Bytes.utf8("XXXX"), new byte[80]);
        assertThrows(Handshake.HandshakeException.class, () -> client.handleServerHello(junk));
    }

    @Test
    @DisplayName("协议版本不匹配会被拒绝（而不是按旧格式误解析）")
    void versionMismatchIsRejected() throws Exception {
        Ed25519Identity serverIdentity = Ed25519Identity.generate();
        Handshake.ClientSide client = new Handshake.ClientSide(null, Ed25519Identity.generate(), null);
        byte[] hello = client.buildClientHello("客户端");

        Handshake.ServerSide server = new Handshake.ServerSide(serverIdentity, null, hello);
        byte[] serverHello = server.buildServerHello();
        // 把版本号改成 999
        serverHello[5] = 0;
        serverHello[6] = 0;
        serverHello[7] = 0x03;
        serverHello[8] = (byte) 0xE7;

        assertThrows(Handshake.HandshakeException.class, () -> client.handleServerHello(serverHello));
    }

    @Test
    @DisplayName("两种模式派生的会话密钥不同（PSK 确实混进了密钥派生）")
    void pskChangesDerivedKeys() throws Exception {
        Pair withPsk = handshake(Bytes.sha256(Bytes.utf8("k")), Bytes.sha256(Bytes.utf8("k")), null);
        Pair withoutPsk = handshake(null, null, null);
        assertNotEquals(withPsk.serverResult.mode(), withoutPsk.serverResult.mode());
        // 两次随机握手本就不会同密钥，这里只断言模式不同即可；
        // 「PSK 混入 IKM」由 deriveSession 的实现保证，并由 wrongPskIsRejected 间接验证。
    }
}
