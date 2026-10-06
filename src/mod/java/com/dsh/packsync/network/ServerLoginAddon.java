package com.dsh.packsync.network;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.PackSyncHost;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * 服务端的登录期握手状态机。
 *
 * <p><b>这是整个"连服即自动同步"的支点。</b>它解决一个死结：
 * 客户端缺 mod 时，Forge 会在登录阶段直接断开连接 —— 游戏内的一切代码
 * （界面、聊天、事件）都来不及运行。所以信息交换**必须抢在 Forge 的
 * mod 列表校验之前完成**，而这个时机只有登录期才有。
 *
 * <p>做法如下：
 * <ol>
 *   <li>在登录处理器的 {@code tick} 里发起 {@code ClientboundCustomQueryPacket}；</li>
 *   <li>只要还没收到应答，就**取消原版 tick**，把登录流程钉住 —— 这样它不会
 *       继续往下走去做 mod 校验；</li>
 *   <li>客户端应答后放行。</li>
 * </ol>
 *
 * <p><b>两道保险，避免把玩家锁在门外</b>：
 * <ul>
 *   <li>超时强制放行（客户端没装我们、或网络卡住时，最多等 {@link #TIMEOUT_TICKS} tick）；</li>
 *   <li>整个流程包在 try/catch 里，任何异常都直接放行 —— 这个 mod 绝不能
 *       成为"连不上服务器"的原因。</li>
 * </ul>
 */
public final class ServerLoginAddon {

    /** 最多等这么久（tick）。20 tick ≈ 1 秒，这里给 5 秒。 */
    private static final int TIMEOUT_TICKS = 100;

    private final ServerLoginPacketListenerImpl handler;

    private boolean querySent;
    private boolean finished;
    private int ticks;

    /** 客户端是否"理解了"这次查询（回包带数据 = 装了本 mod）。 */
    private boolean clientUnderstood;

    public ServerLoginAddon(ServerLoginPacketListenerImpl handler) {
        this.handler = handler;
    }

    /**
     * 每个 tick 推进一次。
     *
     * @return true 表示握手已结束（可以继续原版登录流程）；
     *         false 表示"还没弄完"，调用方应取消原版 tick 以钉住登录
     */
    public boolean tick() {
        if (finished) {
            return true;
        }
        try {
            ticks++;
            if (!querySent) {
                sendQuery();
                return false;
            }
            if (clientUnderstood) {
                finished = true;
                return true;
            }
            if (ticks > TIMEOUT_TICKS) {
                // 客户端没装本 mod（或没响应）。放行 —— 是否允许没装的玩家进来
                // 由 PackSyncHost/配置决定，不该在这里卡死。
                finished = true;
                PackSync.LOGGER.info("[PackSync] 客户端未响应登录握手（可能没装 PackSync），已放行");
                return true;
            }
            return false;
        } catch (Throwable t) {
            // 任何问题都放行：本 mod 绝不能挡住玩家进服。
            PackSync.LOGGER.error("[PackSync] 登录握手异常，已放行", t);
            finished = true;
            return true;
        }
    }

    /** 发起查询，把整合包接入信息带过去。 */
    private void sendQuery() {
        querySent = true;
        var info = PackSyncHost.describeForClient();
        if (info == null) {
            // 托管没运行：没什么可告诉客户端的，直接结束握手。
            finished = true;
            PackSync.LOGGER.debug("[PackSync] 托管未运行，跳过登录握手");
            return;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(info.host() == null ? "" : info.host(), 255);
        buf.writeVarInt(info.port());
        buf.writeUtf(info.modpackName() == null ? "" : info.modpackName(), 128);
        buf.writeVarInt(info.fileCount());
        buf.writeVarLong(info.totalBytes());
        buf.writeUtf(info.fingerprint() == null ? "" : info.fingerprint(), 128);
        // 服务端配置里的"是否启用身份密钥校验"，随信息一起告诉客户端。
        buf.writeBoolean(info.requireFingerprint());

        net.minecraft.network.Connection conn = connectionOf(handler);
        if (conn == null) {
            // 取不到连接就发不出去；结束握手放行，绝不因此卡住玩家。
            finished = true;
            PackSync.LOGGER.warn("[PackSync] 取不到登录连接，跳过整合包信息下发");
            return;
        }
        conn.send(new ClientboundCustomQueryPacket(
                LoginQueryID.HANDSHAKE.id(),
                LoginQueryID.HANDSHAKE.resourceLocation(),
                buf));

        PackSync.LOGGER.info("[PackSync] 已向登录中的客户端下发整合包信息：{}:{} / \"{}\" / {} 个文件",
                info.host(), info.port(), info.modpackName(), info.fileCount());
    }

    /**
     * 处理客户端应答。
     *
     * @param transactionId 包里的 transactionId（据此反查通道）
     * @param data          客户端回的数据；<b>为 null 表示"没装本 mod"</b>
     *                      —— MC 对未知查询会自动回一个空数据包
     */
    public void handleResponse(int transactionId, FriendlyByteBuf data) {
        if (transactionId != LoginQueryID.HANDSHAKE.id()) {
            return;
        }
        clientUnderstood = data != null;
        if (clientUnderstood) {
            PackSync.LOGGER.info("[PackSync] 客户端已确认收到整合包信息");
        }
    }

    /**
     * 取登录处理器里的私有 {@code connection} 字段。
     *
     * <p>用反射而不是 Mixin {@code @Accessor} —— 后者不支持 {@code require = 0}，
     * 字段名对不上会让整个登录类加载失败。拿不到就返回 null，由调用方放行。
     */
    private static net.minecraft.network.Connection connectionOf(ServerLoginPacketListenerImpl handler) {
        return com.dsh.packsync.network.LoginConnectionAccess.connectionOf(handler);
    }

    public boolean isFinished() {
        return finished;
    }

    /** 客户端是否装了本 mod（握手期就能知道，不必等进服后）。 */
    public boolean clientUnderstood() {
        return clientUnderstood;
    }
}
