package com.dsh.packsync.client;

import com.dsh.packsync.client.gui.PackSyncScreen;
import com.dsh.packsync.core.PackSyncCore;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 打开 PackSync 界面的快捷键。
 *
 * <p>默认绑定 <b>右 Shift</b>（{@code GLFW_KEY_RIGHT_SHIFT}），想改去
 * "选项 → 控制 → 按键绑定"里改。
 *
 * <p><b>为什么必须给个默认键</b>：设置界面（下载方式 / 身份校验）的入口在主界面里，
 * 而主界面只能靠这个按键打开。以前默认是 {@code GLFW_KEY_UNKNOWN}（完全没绑），
 * 玩家既找不到界面、也改不了任何设置 —— 功能做了却不可达。选右 Shift 是因为它
 * 极少与其它 mod 冲突，也不是常用操作键。
 *
 * <p>注意两个事件分属不同总线：{@code RegisterKeyMappingsEvent} 在 **MOD** 总线
 * （注册期），{@code InputEvent.Key} 在 **FORGE** 总线（运行期）。
 * 混用会导致"注册了但按了没反应"，所以这里分成两个订阅类。
 */
public final class PackSyncKeybinds {

    public static final String CATEGORY = "key.categories.packsync";
    public static final String OPEN_KEY = "key.packsync.open";

    private PackSyncKeybinds() {
    }

    /** MOD 总线：注册按键。 */
    @Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            Keys.OPEN = new KeyMapping(OPEN_KEY, InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY);
            event.register(Keys.OPEN);
        }
    }

    /** FORGE 总线：处理按键。 */
    @Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Handling {
        private Handling() {
        }

        @SubscribeEvent
        public static void onKey(InputEvent.Key event) {
            if (Keys.OPEN == null) {
                return;
            }
            while (Keys.OPEN.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                // ★ 关键：**主菜单也必须能打开**。
                // 因为缺 mod 时 Forge 会在登录阶段直接断开连接 ——
                // "进游戏后再开界面"这条路在首次装包时根本走不通。
                // 玩家必须能在进服之前就打开界面、指定服务器、先把包同步下来。
                if (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                        || mc.screen instanceof net.minecraft.client.gui.screens.ConnectScreen
                        || mc.screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen) {
                    mc.setScreen(new PackSyncScreen(mc.screen));
                }
            }
        }
    }

    static final class Keys {
        static KeyMapping OPEN;

        private Keys() {
        }
    }
}
