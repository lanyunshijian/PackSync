package com.dsh.packsync.mixin;

import com.dsh.packsync.network.ClientLoginHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家点下"连接"的那一刻，把目标服务器地址记下来。
 *
 * <p><b>为什么非在这里记不可</b>：登录期 {@code Minecraft.getCurrentServer()} 还是
 * {@code null} —— 它要等<b>进入世界</b>才被赋值。所以等到握手时再去取地址，必然取不到：
 * 客户端于是不知道"这个整合包属于哪台服务器"，既无法落记录、也无法在启动期继续同步，
 * 最后表现为"收到整合包信息却什么也不做"，然后被 Forge 以 {@code mismatched mod list}
 * 踢回主菜单。
 *
 * <p>常见的做法就是在 {@code ConnectScreen.connect} 的 HEAD 记录
 * 原始服务器地址（见其 {@code ConnectScreenMixin}），本类照此实现。
 *
 * <p>{@code connect} 是 {@code ConnectScreen} 的 private 实例方法，签名
 * {@code (Minecraft, ServerAddress, ServerData)}；方法名写 MCP 名，refmap 负责映射。
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {

    @Inject(method = "connect", at = @At("HEAD"))
    private void packsync$rememberTarget(Minecraft mc, ServerAddress address, ServerData data, CallbackInfo ci) {
        try {
            if (address != null) {
                ClientLoginHandler.rememberServerAddress(address.getHost(), address.getPort());
            }
        } catch (Throwable t) {
            // 记不下来也不该影响连接本身。
        }
    }
}
