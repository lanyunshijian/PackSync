package com.dsh.packsync.mixin;

import com.dsh.packsync.PackSyncHost;
import com.dsh.packsync.compat.HttpSniffHandler;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把同端口分流器插进 Minecraft 的每一个新连接。
 *
 * <p>注入点是 {@code ServerConnectionListener$1.initChannel} 的 TAIL ——
 * 这是 MC 给每个新连接装配 pipeline 的地方。用 {@code addFirst} 放到最前面，
 * 才能保证我们比 MC 自己的解码器更早看到字节。
 *
 * <p><b>为什么不需要 refmap</b>：目标是匿名内部类，它的 {@code initChannel}
 * 实现的是 Netty 的接口方法，混淆器不会改名 ——
 * 实测映射表里 {@code initChannel} 的 SRG 名就是 {@code initChannel}。
 * 也就是说 dev（official 名）与生产（SRG 名）用的是同一个字符串，
 * 不必引入 MixinGradle 生成 refmap（少一个构建环节就少一处失败可能）。
 *
 * <p>本注入**绝不能影响正常连接**：拿不到端口、装 handler 抛异常，
 * 一律静默跳过 —— 分流是便利性优化，不该成为连不上服务器的原因。
 */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public class ServerConnectionMixin {

    @Inject(method = "initChannel", at = @At("TAIL"))
    private void packsync$installSniffer(Channel channel, CallbackInfo ci) {
        try {
            int port = PackSyncHost.localHttpPort();
            if (port <= 0) {
                System.out.println("[PackSync][sniff] 新连接到达，但分发服务未运行，不装分流器");
                return;
            }
            channel.pipeline().addFirst("packsync-sniff", new HttpSniffHandler(port));
            System.out.println("[PackSync][sniff] 已为一条新连接安装分流器 -> 内部端口 " + port);
        } catch (Throwable t) {
            // 静默跳过：分流失败不该让玩家连不上服务器。
            System.err.println("[PackSync] 安装同端口分流器失败（忽略）：" + t);
        }
    }
}
