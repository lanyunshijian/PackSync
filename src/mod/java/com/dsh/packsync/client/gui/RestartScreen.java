package com.dsh.packsync.client.gui;

import com.dsh.packsync.client.SyncProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 重启提示屏。
 *
 * <p>为什么这一步必须由玩家手动触发：同步已经改写了 {@code mods/} 里的文件，
 * 但**当前进程已经加载了旧的类**。不重启的话，玩家会带着"文件是新版、
 * 运行的是旧版"的错位状态继续玩，出问题时极难定位。
 *
 * <p>差别：有的实现把"立即关闭游戏"做成默认按钮。
 * 这里两个按钮等权重，避免手滑丢进度 —— 玩家可能正在服务器上。
 */
public class RestartScreen extends Screen {

    private final Screen parent;
    private final SyncProgress progress;

    public RestartScreen(Screen parent, SyncProgress progress) {
        super(Component.literal("需要重启游戏"));
        this.parent = parent;
        this.progress = progress;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int y = this.height / 2 + 30;

        addRenderableWidget(Button.builder(
                        Component.literal("查看本次变更").withStyle(ChatFormatting.AQUA),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new ChangelogScreen(this, progress));
                            }
                        })
                .bounds(centerX - 155, y, 150, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("稍后自己重启").withStyle(ChatFormatting.GRAY),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(parent);
                            }
                        })
                .bounds(centerX + 5, y, 150, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("现在关闭游戏").withStyle(ChatFormatting.GREEN),
                        b -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null) {
                                mc.stop();
                            }
                        })
                .bounds(centerX - 75, y + 26, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 60;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0x55FF55);
        y += 20;
        graphics.drawCenteredString(this.font,
                Component.literal("整合包已同步完成，但需要重启游戏才能生效。")
                        .withStyle(ChatFormatting.WHITE), centerX, y, 0xFFFFFF);
        y += 14;
        graphics.drawCenteredString(this.font,
                Component.literal("（未重启时，游戏仍在用旧的 mod 文件）")
                        .withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);

        if (progress != null && !progress.summary().isBlank()) {
            y += 22;
            for (String line : progress.summary().split("\n")) {
                graphics.drawCenteredString(this.font,
                        Component.literal(line).withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
                y += 11;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
