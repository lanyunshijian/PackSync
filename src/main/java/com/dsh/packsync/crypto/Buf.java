package com.dsh.packsync.crypto;

import java.nio.charset.StandardCharsets;

/**
 * 极简二进制读写缓冲。自定义协议用它编码，**不用 Java 序列化**。
 *
 * <p>为什么不用 Java 原生序列化：那是一个众所周知的远程代码执行面
 * （{@code readObject} 反序列化 gadget 链），而它的输入恰好来自网络对端。
 * 手写定长/TLV 编码没有这个问题，且格式稳定、可跨语言实现。
 *
 * <p>读取端在越界时立刻抛异常；{@link #requireFullyRead()} 用来堵住
 * 「尾部藏了额外字节」这类攻击（长度字段与实际内容不一致）。
 */
public final class Buf {

    private byte[] data;
    private int length;
    private int position;

    private Buf(byte[] data, int length) {
        this.data = data;
        this.length = length;
        this.position = 0;
    }

    public static Buf writer() {
        return new Buf(new byte[64], 0);
    }

    public static Buf reader(byte[] data) {
        return new Buf(data.clone(), data.length);
    }

    // ------------------------------------------------------------------ 写

    private void ensure(int extra) {
        if (length + extra > data.length) {
            int cap = Math.max(data.length * 2, length + extra);
            byte[] bigger = new byte[cap];
            System.arraycopy(data, 0, bigger, 0, length);
            data = bigger;
        }
    }

    public Buf u8(int v) {
        ensure(1);
        data[length++] = (byte) v;
        return this;
    }

    public Buf u16(int v) {
        ensure(2);
        data[length++] = (byte) (v >>> 8);
        data[length++] = (byte) v;
        return this;
    }

    public Buf u32(int v) {
        ensure(4);
        data[length++] = (byte) (v >>> 24);
        data[length++] = (byte) (v >>> 16);
        data[length++] = (byte) (v >>> 8);
        data[length++] = (byte) v;
        return this;
    }

    public Buf u64(long v) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            data[length++] = (byte) (v >>> (56 - 8 * i));
        }
        return this;
    }

    public Buf bytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, data, length, b.length);
        length += b.length;
        return this;
    }

    /** 长度前缀（u32）+ 内容。消除拼接歧义的关键。 */
    public Buf lengthPrefixed(byte[] b) {
        u32(b.length);
        return bytes(b);
    }

    public Buf str(String s) {
        return lengthPrefixed(s.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toBytes() {
        byte[] out = new byte[length];
        System.arraycopy(data, 0, out, 0, length);
        return out;
    }

    public int size() {
        return length;
    }

    // ------------------------------------------------------------------ 读

    private void need(int n) {
        if (position + n > length) {
            throw new ProtocolFormatException("包被截断：需要 " + n + " 字节，剩余 " + (length - position));
        }
    }

    public int readU8() {
        need(1);
        return data[position++] & 0xFF;
    }

    public int readU16() {
        need(2);
        return ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
    }

    public int readU32() {
        need(4);
        return ((data[position++] & 0xFF) << 24) | ((data[position++] & 0xFF) << 16)
                | ((data[position++] & 0xFF) << 8) | (data[position++] & 0xFF);
    }

    public long readU64() {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[position++] & 0xFFL);
        }
        return v;
    }

    public byte[] readBytes(int n) {
        if (n < 0) {
            throw new ProtocolFormatException("负数长度：" + n);
        }
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, position, out, 0, n);
        position += n;
        return out;
    }

    /**
     * 读长度前缀字节串。
     *
     * @param maxLength 上限。**必须**由调用方给出：否则一个 4 字节的
     *                  伪造长度就能让我们尝试分配 2 GB 内存（内存耗尽攻击）。
     */
    public byte[] readLengthPrefixed(int maxLength) {
        int n = readU32();
        if (n < 0 || n > maxLength) {
            throw new ProtocolFormatException("长度前缀越界：" + n + "（上限 " + maxLength + "）");
        }
        return readBytes(n);
    }

    public String readStr(int maxLength) {
        byte[] raw = readLengthPrefixed(maxLength);
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** 确认没有多余尾部字节。 */
    public void requireFullyRead() {
        if (position != length) {
            throw new ProtocolFormatException("包尾有多余 " + (length - position) + " 字节");
        }
    }

    public int remaining() {
        return length - position;
    }

    /** 协议格式错误（与「认证失败」区分开：这是对端乱发或版本不匹配）。 */
    public static class ProtocolFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProtocolFormatException(String message) {
            super(message);
        }
    }
}
