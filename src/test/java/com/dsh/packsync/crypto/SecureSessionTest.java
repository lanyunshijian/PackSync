package com.dsh.packsync.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dsh.packsync.crypto.AesGcm.AuthenticationException;

/** 加密通道 {@link SecureSession} 的验证：机密性、防重放、防篡改、用途隔离。 */
class SecureSessionTest {

    private static final String CTX = "packsync/test";

    private static SecureSession[] pair() {
        byte[] c2s = Bytes.random(32);
        byte[] s2c = Bytes.random(32);
        return new SecureSession[]{SecureSession.forClient(c2s, s2c),
                SecureSession.forServer(c2s, s2c)};
    }

    @Test
    @DisplayName("双向通信：客户端发的服务端能解，反之亦然")
    void bidirectionalTraffic() throws Exception {
        SecureSession[] p = pair();
        SecureSession client = p[0];
        SecureSession server = p[1];

        byte[] fromClient = Bytes.utf8("客户端上报的模组清单");
        assertArrayEquals(fromClient, server.decrypt(CTX, client.encrypt(CTX, fromClient)));

        byte[] fromServer = Bytes.utf8("服务端下发的缺失清单");
        assertArrayEquals(fromServer, client.decrypt(CTX, server.encrypt(CTX, fromServer)));

        // 连续多条也不错位
        for (int i = 0; i < 100; i++) {
            byte[] msg = Bytes.utf8("消息 #" + i);
            assertArrayEquals(msg, server.decrypt(CTX, client.encrypt(CTX, msg)));
        }
    }

    @Test
    @DisplayName("重放同一个包会被拒绝（序号必须严格连续）")
    void replayIsRejected() throws Exception {
        SecureSession[] p = pair();
        SecureSession client = p[0];
        SecureSession server = p[1];

        byte[] packet = client.encrypt(CTX, Bytes.utf8("转账 100 元"));
        server.decrypt(CTX, packet); // 第一次正常

        assertThrows(AuthenticationException.class, () -> server.decrypt(CTX, packet),
                "重放必须被拒绝");
    }

    @Test
    @DisplayName("乱序包会被拒绝，且一次失败不会让后续正确包错位")
    void outOfOrderIsRejected() throws Exception {
        SecureSession[] p = pair();
        SecureSession client = p[0];
        SecureSession server = p[1];

        byte[] first = client.encrypt(CTX, Bytes.utf8("第一条"));
        byte[] second = client.encrypt(CTX, Bytes.utf8("第二条"));

        assertThrows(AuthenticationException.class, () -> server.decrypt(CTX, second));

        // 关键：失败的那次不能推进接收计数器，否则会话就永久错位了
        assertArrayEquals(Bytes.utf8("第一条"), server.decrypt(CTX, first));
        assertArrayEquals(Bytes.utf8("第二条"), server.decrypt(CTX, second));
    }

    @Test
    @DisplayName("密文被篡改会被拒绝")
    void tamperingIsRejected() {
        SecureSession[] p = pair();
        byte[] packet = p[0].encrypt(CTX, Bytes.utf8("原始数据"));
        packet[packet.length - 1] ^= 0x01; // 破坏认证标签
        assertThrows(AuthenticationException.class, () -> p[1].decrypt(CTX, packet));
    }

    @Test
    @DisplayName("用途隔离：换 context 解不开（无法把分块包伪装成清单包）")
    void contextIsolation() throws Exception {
        SecureSession[] p = pair();
        SecureSession client = p[0];
        SecureSession server = p[1];

        byte[] packet = client.encrypt("modlist", Bytes.utf8("模组清单"));

        assertThrows(AuthenticationException.class, () -> server.decrypt("chunk", packet));
        // 用正确的 context 仍然能解开 —— 证明上面那次失败没有破坏会话状态
        assertArrayEquals(Bytes.utf8("模组清单"), server.decrypt("modlist", packet));
    }

    @Test
    @DisplayName("把服务端自己的包回射给服务端会被方向标记挡住")
    void reflectedPacketIsRejected() {
        SecureSession[] p = pair();
        byte[] packet = p[1].encrypt(CTX, Bytes.utf8("服务端发出的包"));
        assertThrows(AuthenticationException.class, () -> p[1].decrypt(CTX, packet));
    }

    @Test
    @DisplayName("过短的垃圾包被干净地拒绝，不会抛数组越界")
    void shortGarbageIsRejected() {
        SecureSession[] p = pair();
        assertThrows(AuthenticationException.class, () -> p[1].decrypt(CTX, new byte[3]));
        assertThrows(AuthenticationException.class, () -> p[1].decrypt(CTX, null));
    }
}
