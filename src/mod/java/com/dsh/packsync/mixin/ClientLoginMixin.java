package com.dsh.packsync.mixin;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.network.ClientLoginHandler;
import com.dsh.packsync.network.LoginConnectionAccess;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端登录期握手的注入点。
 *
 * <p>拦截 {@code handleCustomQuery}：如果查询来自我们的通道，就自己处理并回包，
 * 同时取消原版处理（否则原版会回一个"未理解"的空包，服务端会误判成"客户端没装"）。
 *
 * <p>方法名写 <b>MCP 名</b>（{@code handleCustomQuery}），由 refmap 映射成 SRG 名
 * {@code m_7254_}，也就是 refmap 里形如
 * {@code "handleCustomQuery": "...ClientHandshakePacketListenerImpl;m_7254_(...)V"}
 * 的那条映射。
 * 不要再手写 SRG 名：那样写错时无从验证，而且一旦配合 {@code require = 0} 就会静默失效。
 *
 * <p><b>关于取 {@code connection} 字段</b>：这里用反射（{@link LoginConnectionAccess}）
 * 而非 Mixin {@code @Accessor}。{@code @Accessor} <b>不支持 {@code require = 0}</b>，
 * 字段名对不上会抛 {@code InvalidAccessorException}，被包成 {@code MixinTransformerError}
 * —— 发生在登录期就等于"客户端一连接就崩"。反射拿不到只返回 null，本方法随即放手
 * 交回原版，代价仅是本次握手指令不生效。
 */
@Mixin(value = ClientHandshakePacketListenerImpl.class, priority = 300)
public class ClientLoginMixin {

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void packsync$handleQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        try {
            ResourceLocation identifier = packet.getIdentifier();
            FriendlyByteBuf data = packet.getData();

            FriendlyByteBuf reply = ClientLoginHandler.handle(
                    packet.getTransactionId(), identifier, data,
                    (ClientHandshakePacketListenerImpl) (Object) this);

            if (reply == null) {
                return; // 不是我们的查询，交给原版
            }

            Connection connection = LoginConnectionAccess.connectionOf(this);
            if (connection == null) {
                // 取不到连接就回不了包，交回原版 —— 宁可握手不生效，也不能崩。
                return;
            }

            // 回发应答。注意 ServerboundCustomQueryPacket 不带 identifier，
            // 只带 transactionId —— 服务端靠它反查通道。
            connection.send(new ServerboundCustomQueryPacket(packet.getTransactionId(), reply));

            ci.cancel();
        } catch (Throwable t) {
            // 客户端侧异常绝不能挡住连接。交给原版处理即可。
            PackSync.LOGGER.error("[PackSync] 处理登录查询失败（交回原版）", t);
        }
    }
}
