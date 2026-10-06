package com.dsh.packsync.crypto;

import com.dsh.packsync.crypto.AesGcm.AuthenticationException;

/**
 * 握手完成后的**加密通道**：所有上层消息都经过它加解密。
 *
 * <p>它解决三件事：
 * <ol>
 *   <li><b>机密性</b> —— 每个方向一把独立密钥（c2s / s2c），AES-256-GCM；</li>
 *   <li><b>防重放</b> —— 每个包带单调递增计数器，接收方要求**严格连续**；
 *       重放的旧包计数器必然落后，直接拒绝；</li>
 *   <li><b>防跨用途挪包</b> —— 调用方给每类消息一个 context 字符串，
 *       它进入 AAD；攻击者无法把「下载文件的分块」伪装成「模组清单」。</li>
 * </ol>
 *
 * <p>底层是 TCP（Forge 的 SimpleChannel 走同一条连接），有序且可靠，
 * 所以这里敢用「严格连续」而不是滑动窗口 —— 一旦不连续就是异常，
 * 会被立刻发现而不是被悄悄容忍。
 */
public final class SecureSession {

    /** 单个计数器用尽前的上限（2^64 实际上不可能达到，这是纵深防御）。 */
    private static final long COUNTER_LIMIT = Long.MAX_VALUE - 1;

    // 方向前缀让两个方向的 nonce 空间天然不重叠（纵深防御：
    // 即使将来有人误把两把密钥配成同一把，也不会发生 GCM 的 nonce 复用灾难）。
    private static final byte CLIENT_DIRECTION = (byte) 0xC1;
    private static final byte SERVER_DIRECTION = (byte) 0x51;

    private final byte[] sendKey;
    private final byte[] receiveKey;
    private final byte sendDirection;
    private final byte receiveDirection;

    private long sendCounter;
    private long expectedReceiveCounter;

    private SecureSession(byte[] sendKey, byte[] receiveKey, byte sendDir, byte receiveDir) {
        this.sendKey = sendKey.clone();
        this.receiveKey = receiveKey.clone();
        this.sendDirection = sendDir;
        this.receiveDirection = receiveDir;
        this.sendCounter = 0;
        this.expectedReceiveCounter = 0;
    }

    /** 客户端视角：自己加密用 c2s，解密用 s2c。 */
    public static SecureSession forClient(byte[] c2sKey, byte[] s2cKey) {
        return new SecureSession(c2sKey, s2cKey, CLIENT_DIRECTION, SERVER_DIRECTION);
    }

    /** 服务端视角：自己加密用 s2c，解密用 c2s。 */
    public static SecureSession forServer(byte[] c2sKey, byte[] s2cKey) {
        return new SecureSession(s2cKey, c2sKey, SERVER_DIRECTION, CLIENT_DIRECTION);
    }

    /**
     * 加密一条消息。
     *
     * @param context 用途标签（进入 AAD），例如 {@code "modlist"} / {@code "chunk"}。
     *                同一会话内不同用途必须用不同 context。
     * @return 线上格式：{@code nonce(12) || ciphertext+tag}
     */
    public byte[] encrypt(String context, byte[] plaintext) {
        if (sendCounter >= COUNTER_LIMIT) {
            throw new IllegalStateException("会话计数器耗尽，必须重新握手");
        }
        long counter = sendCounter++;
        byte[] nonce = nonce(sendDirection, counter);
        byte[] aad = aad(context, counter);
        byte[] sealed = AesGcm.seal(sendKey, nonce, aad, plaintext);
        return Bytes.concat(nonce, sealed);
    }

    /**
     * 解密一条消息，并强制序号连续以防重放。
     *
     * @throws AuthenticationException 密文被篡改、密钥不对、context 不匹配、
     *                                 或序号不连续（重放/乱序/丢包）时抛出
     */
    public byte[] decrypt(String context, byte[] packet) throws AuthenticationException {
        if (packet == null || packet.length < AesGcm.NONCE_LEN + 16) {
            throw new AuthenticationException("包太短，不可能是合法的加密封包：" +
                    (packet == null ? "null" : packet.length + " 字节"));
        }
        byte[] nonce = new byte[AesGcm.NONCE_LEN];
        System.arraycopy(packet, 0, nonce, 0, AesGcm.NONCE_LEN);
        byte[] sealed = new byte[packet.length - AesGcm.NONCE_LEN];
        System.arraycopy(packet, AesGcm.NONCE_LEN, sealed, 0, sealed.length);

        if (nonce[0] != receiveDirection) {
            throw new AuthenticationException("方向标记不符：可能收到的是自己发出去的包被回射");
        }
        long counter = readCounter(nonce);
        if (counter != expectedReceiveCounter) {
            throw new AuthenticationException("序号不连续：期望 " + expectedReceiveCounter
                    + "，收到 " + counter + "（重放 / 丢包 / 顺序被打乱）");
        }

        byte[] plaintext = AesGcm.open(receiveKey, nonce, aad(context, counter), sealed);
        expectedReceiveCounter++; // 只有验签通过才推进，否则一次伪造包就能让会话错位
        return plaintext;
    }

    public long sendCounter() {
        return sendCounter;
    }

    public long expectedReceiveCounter() {
        return expectedReceiveCounter;
    }

    /**
     * nonce 布局：{@code [方向(1)] [保留(3)] [计数器(8)]}，共 12 字节（GCM 标准长度）。
     *
     * <p>方向字节保证两个方向的 nonce 空间不重叠；保留字节是为了让计数器
     * 落在后 8 字节，将来若要换成 4 字节计数器也留有余地。
     */
    private static byte[] nonce(byte direction, long counter) {
        return Bytes.concat(new byte[]{direction, 0, 0, 0}, Bytes.u64(counter));
    }

    private static long readCounter(byte[] nonce) {
        long v = 0;
        for (int i = 4; i < AesGcm.NONCE_LEN; i++) {
            v = (v << 8) | (nonce[i] & 0xFFL);
        }
        return v;
    }

    private static byte[] aad(String context, long counter) {
        return Bytes.concat(Bytes.utf8(context), Bytes.u64(counter));
    }
}
