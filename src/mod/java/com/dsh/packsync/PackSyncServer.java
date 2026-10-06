package com.dsh.packsync;

import com.dsh.packsync.core.PackSyncCore;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端生命周期与命令注册的事件入口。
 *
 * <p>本类只做"事件 → 转发给 {@link PackSyncHost}"，实际的托管逻辑都在 Host 里 ——
 * 这样 /packsync 命令与事件走的是**同一套状态**，不会出现"命令说在跑、事件说没跑"的错位。
 */
@Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackSyncServer {

    private PackSyncServer() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            PackSync.LOGGER.info("[PackSync] {}", PackSyncHost.start());
        } catch (Throwable t) {
            // 托管失败绝不能让服务器起不来。
            PackSync.LOGGER.error("[PackSync] 启动整合包托管失败（不影响服务器运行）", t);
        }
        selfCheckLoginHandshake();
    }

    /**
     * 自检登录握手的 Mixin 注入是否<b>真的</b>生效。
     *
     * <p><b>为什么必须自检</b>：{@code @Inject} 一律带 {@code require = 0}
     * （为的是名字对不上时降级而不是崩玩家），代价是**方法名写错时它一声不吭地跳过**。
     * 本 mod 就栽过这个坑：{@code tick()} 被误写成同名字段 {@code f_10020_}，
     * 于是登录握手永不发起，缺 mod 的客户端被 Forge 以
     * {@code mismatched mod channel list} 断开，而全流程<b>没有任何报错</b>。
     *
     * <p><b>检测手法</b>：Mixin 会把 handler 方法<b>合并进目标类</b>，名字形如
     * {@code handler$cai000$packsync$tickHandshake}。所以目标类里只要存在
     * 名字含 {@code packsync$tickHandshake} 的方法，就说明注入确实发生了。
     * 这里 {@code Class.forName} 会顺带触发该类的加载与 Mixin 应用。
     */
    private static void selfCheckLoginHandshake() {
        try {
            Class<?> type = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl");
            boolean initOk = false;
            boolean tickOk = false;
            boolean queryOk = false;
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("packsync$initAddon")) {
                    initOk = true;
                }
                if (n.contains("packsync$tickHandshake")) {
                    tickOk = true;
                }
                if (n.contains("packsync$onResponse")) {
                    queryOk = true;
                }
            }
            if (initOk && tickOk && queryOk) {
                PackSync.LOGGER.info("[PackSync] 登录握手注入自检：通过（挂载 + tick 推进 + 应答处理均已就绪）");
            } else {
                PackSync.LOGGER.error("[PackSync] 登录握手注入自检【失败】：挂载={} tick={} 应答={}。"
                                + "登录期自动同步将不会生效，缺 mod 的客户端会被 Forge 直接断开。"
                                + "多半是 ServerLoginMixin 里的 SRG 方法名与本版 MC 不符 —— "
                                + "请用 javap 核对 ServerLoginPacketListenerImpl 的方法名。",
                        initOk, tickOk, queryOk);
            }
        } catch (Throwable t) {
            PackSync.LOGGER.warn("[PackSync] 登录握手注入自检无法执行（不影响服务器运行）", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        try {
            PackSync.LOGGER.info("[PackSync] {}", PackSyncHost.stop());
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 停止托管时出错", t);
        }
    }

    @SubscribeEvent
    public static void onCommandsRegister(RegisterCommandsEvent event) {
        try {
            PackSyncCommands.register(event.getDispatcher());
            PackSync.LOGGER.info("[PackSync] /packsync 命令已注册");
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 注册命令失败", t);
            t.printStackTrace();
        }
    }
}
