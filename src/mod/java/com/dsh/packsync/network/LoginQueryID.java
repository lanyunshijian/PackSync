package com.dsh.packsync.network;

import net.minecraft.resources.ResourceLocation;

/**
 * 登录期查询的通道。
 *
 * <p>用**负数** transactionId：Forge 自己的 mod 列表查询占用了正整数区段，
 * 负数区段是我们的。
 *
 * <p>为什么需要它：{@code ServerboundCustomQueryPacket}（客户端回包）**不带 identifier**，
 * 服务端只能靠 transactionId 反查"这是哪个通道的响应"。
 * 所以必须自己维护这张映射表。
 */
public enum LoginQueryID {

    /** 服务端 → 客户端：整合包接入信息。 */
    HANDSHAKE(-100, "handshake"),
    /** 客户端 → 服务端：收到并已处理。 */
    ACK(-101, "ack");

    private final int id;
    private final String path;

    LoginQueryID(int id, String path) {
        this.id = id;
        this.path = path;
    }

    public int id() {
        return id;
    }

    public ResourceLocation resourceLocation() {
        return new ResourceLocation("packsync", path);
    }

    /** 按 transactionId 反查通道；不是我们的通道就返回 null。 */
    public static LoginQueryID byId(int id) {
        for (LoginQueryID q : values()) {
            if (q.id == id) {
                return q;
            }
        }
        return null;
    }

    /** 按 identifier 反查通道；不是我们的命名空间就返回 null。 */
    public static LoginQueryID byResourceLocation(ResourceLocation location) {
        if (location == null || !"packsync".equals(location.getNamespace())) {
            return null;
        }
        for (LoginQueryID q : values()) {
            if (q.path.equals(location.getPath())) {
                return q;
            }
        }
        return null;
    }
}
