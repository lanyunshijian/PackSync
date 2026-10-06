package com.dsh.packsync;

import com.dsh.packsync.core.PackSyncCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端侧：连上服务器后主动向服务端报到，以及主菜单入口。
 *
 * <p>刻意做成**客户端主动**：没装本 mod 的客户端不会收到任何东西，
 * 也就不可能因为"未知 payload"被踢。服务端那边只需被动等待，
 * 收不到 hello 就是没装。
 */
@Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackSyncClient {

    private PackSyncClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            // 1) 向服务端报到（服务端据此知道"这个客户端装了 PackSync"）
            PackSyncPresence.sendHelloIfClient();
            // 2) 记下当前服务器 —— 没有这一步，后续同步根本不知道要连谁
            com.dsh.packsync.client.ServerRecorder.recordCurrentServer();
        } catch (Throwable t) {
            PackSync.LOGGER.debug("[PackSync] 登录处理异常（忽略）：{}", String.valueOf(t));
        }
    }

    /**
     * 在主菜单加一个 PackSync 按钮。
     *
     * <p><b>为什么必须在主菜单</b>：缺 mod 时 Forge 会在登录阶段直接断开，玩家根本
     * 进不了世界 —— 所以"进游戏后再开界面"这条路在首次装包时走不通。界面入口必须
     * 在主菜单就能摸到。按键那条路也留着（默认右 Shift），但按钮更好找。
     *
     * <p>放在右下角：那里通常没有原版按钮，不跟主菜单既有布局打架。
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        try {
            Screen screen = event.getScreen();
            if (!(screen instanceof TitleScreen)) {
                return;
            }
            Button open = Button.builder(
                            Component.literal("PackSync"),
                            b -> Minecraft.getInstance().setScreen(new com.dsh.packsync.client.gui.PackSyncScreen(screen)))
                    .bounds(screen.width - 110, screen.height - 26, 100, 20)
                    .build();
            event.addListener(open);
        } catch (Throwable t) {
            // 加个按钮失败绝不能影响主菜单本身。
            PackSync.LOGGER.debug("[PackSync] 主菜单加入口失败（忽略）：{}", String.valueOf(t));
        }
    }
}
