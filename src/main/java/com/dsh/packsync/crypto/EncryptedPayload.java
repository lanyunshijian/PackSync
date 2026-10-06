package com.dsh.packsync.crypto;

import java.util.Arrays;

/**
 * 无状态载荷的加解密（预连接对账用）。
 *
 * <p><b>为什么不能直接用 {@link SecureSession}</b>：那条通道要求收发序号
 * **严格连续**，靠序号防重放。而 HTTP 请求天生可能乱序、可能被代理重发、
 * 可能因超时重试 —— 硬套计数器只会把正常请求判成攻击，让功能时灵时不灵。
 *
 * <p>所以这里换成「每条消息一个随机 nonce」：
 * <ul>
 *   <li><b>完整性</b>仍由 AES-GCM 认证标签保证（改了任何一位都解不开）；</li>
 *   <li><b>机密性</b>由会话密钥保证（没有握手就拿不到密钥）；</li>
 *   <li><b>重放</b>在这里是**无害**的：重放一次「给我清单」拿到的还是同一份
 *       公开清单，不会改变任何状态 —— 所以不需要为此付出可用性的代价。</li>
 * </ul>
 *
 * <p>nonce 随机化还有个前提：每次握手都会派生**全新的**会话密钥
 * （见 {@link Handshake.SessionKeys}），所以同一把密钥下的加密次数很少，
 * 随机 nonce 的碰撞概率可以忽略。
 */
public final class EncryptedPayload {

    private EncryptedPayload() {
    }

    /** 密封一条消息：{@code nonce(12) || ciphertext+tag}。 */
    public static byte[] seal(byte[] key, String context, byte[] plaintext) {
        byte[] nonce = Bytes.random(AesGcm.NONCE_LEN);
        byte[] sealed = AesGcm.seal(key, nonce, Bytes.utf8(context), plaintext);
        return Bytes.concat(nonce, sealed);
    }

    /** 打开一条消息。 */
    public static byte[] open(byte[] key, String context, byte[] payload)
            throws AesGcm.AuthenticationException {
        if (payload == null || payload.length < AesGcm.NONCE_LEN + 16) {
            throw new AesGcm.AuthenticationException(
                    "载荷太短，不可能是合法的加密封包：" + (payload == null ? "null" : payload.length));
        }
        byte[] nonce = Arrays.copyOfRange(payload, 0, AesGcm.NONCE_LEN);
        byte[] sealed = Arrays.copyOfRange(payload, AesGcm.NONCE_LEN, payload.length);
        return AesGcm.open(key, nonce, Bytes.utf8(context), sealed);
    }

    // =====================================================================
    // 流式分块（文件下载）
    // =====================================================================

    /** 流式传输的明文块大小。 */
    public static final int STREAM_CHUNK = 32 * 1024;

    /** 加密后每块的开销：nonce(12) + tag(16)。 */
    public static final int STREAM_OVERHEAD = AesGcm.NONCE_LEN + 16;

    /** 加密一个流式分块；每块自带 nonce，因此可以独立解密、也无所谓顺序。 */
    public static byte[] sealChunk(byte[] key, String context, byte[] plaintext) {
        return seal(key, context, plaintext);
    }

    /**
     * 从一段连续的流式密文里解出第 {@code index} 块（0 基）。
     *
     * <p>块边界由「每块明文 {@link #STREAM_CHUNK} 字节」推得；最后一块更短。
     * 若密文被截断或篡改，边界会算错，GCM 认证随即失败 —— 不会静默产出坏数据。
     *
     * @return 该块的明文
     */
    public static byte[] openChunkAt(byte[] key, String context, byte[] stream, int index)
            throws AesGcm.AuthenticationException {
        long blockCipherLen = (long) STREAM_CHUNK + STREAM_OVERHEAD;
        long offset = blockCipherLen * index;
        if (offset >= stream.length) {
            throw new AesGcm.AuthenticationException("分块序号越界：" + index);
        }
        int len = (int) Math.min(blockCipherLen, stream.length - offset);
        byte[] slice = Arrays.copyOfRange(stream, (int) offset, (int) offset + len);
        return open(key, context, slice);
    }

    /** 给定明文总长度，算出流式密文的总长。 */
    public static long streamCipherLength(long plainLength) {
        if (plainLength <= 0) {
            return 0;
        }
        long full = plainLength / STREAM_CHUNK;
        long rest = plainLength % STREAM_CHUNK;
        return full * ((long) STREAM_CHUNK + STREAM_OVERHEAD)
                + (rest == 0 ? 0 : rest + STREAM_OVERHEAD);
    }
}
