package com.dsh.packsync.client.gui;

import com.dsh.packsync.client.ClientSyncTask;
import com.dsh.packsync.client.SyncProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 错误屏。
 *
 * <p>刻意把**失败清单**直接显示出来，而不是笼统地说"出错了"：
 * 玩家需要知道是"整个整合包都没下来"还是"只有某个 jar 失败"，
 * 前者要检查网络，后者重试一次通常就好。
 */
public class ErrorScreen extends Screen {

    private final Screen parent;
    private final SyncProgress progress;
    private int scroll;

    public ErrorScreen(Screen parent, SyncProgress progress) {
        super(Component.literal("同步失败"));
        this.parent = parent;
        this.progress = progress;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int y = this.height - 56;

        addRenderableWidget(Button.builder(
                        Component.literal("重试").withStyle(ChatFormatting.GREEN),
                        b -> {
                            ClientSyncTask.start();
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new DownloadScreen(parent));
                            }
                        })
                .bounds(centerX - 155, y, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> back())
                .bounds(centerX + 5, y, 150, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("打开 PackSync 界面").withStyle(ChatFormatting.GRAY),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new PackSyncScreen(parent));
                            }
                        })
                .bounds(centerX - 75, y + 24, 150, 20).build());
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) (delta * 12);
        scroll = Math.max(0, scroll);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 22, 0xFF5555);

        String message = progress == null ? "" : progress.errorMessage();
        int y = 42;
        int limit = this.height - 90;
        for (String line : message.split("\n")) {
            if (y > limit) {
                graphics.drawCenteredString(this.font,
                        Component.literal("…（内容较长，见日志）").withStyle(ChatFormatting.DARK_GRAY),
                        centerX, y, 0x777777);
                break;
            }
            graphics.drawCenteredString(this.font,
                    Component.literal(line).withStyle(ChatFormatting.WHITE), centerX, y, 0xFFFFFF);
            y += 12;
        }

        List<String> failures = progress == null ? List.of() : progress.failures();
        if (!failures.isEmpty()) {
            y += 8;
            graphics.drawCenteredString(this.font,
                    Component.literal("失败的文件：").withStyle(ChatFormatting.RED), centerX, y, 0xFF5555);
            y += 12;
            for (String f : failures) {
                if (y > limit) {
                    break;
                }
                graphics.drawCenteredString(this.font,
                        Component.literal(f).withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
                y += 11;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        back();
        return false;
    }
}
