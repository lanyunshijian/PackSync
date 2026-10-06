package com.dsh.packsync.client.gui;

import com.dsh.packsync.client.ClientSyncTask;
import com.dsh.packsync.client.SyncProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 查询直链屏。
 *
 * <p>为什么单独给这一步一屏：公共站查询可能耗时（几百个文件的批量哈希反查），
 * 如果直接跳到下载屏，玩家会看到"进度条不动"而误以为卡死。
 * 单独说明"正在查直链"能消除这种误解。
 *
 * <p>之所以能加速：能在公共站匹配到的文件会直接从官方 CDN 下载，
 * 不占用服务器带宽 —— 这也是本 mod 保留下载方式选项的原因之一。
 */
public class FetchScreen extends Screen {

    private final Screen parent;
    private Button cancelButton;

    public FetchScreen(Screen parent) {
        super(Component.literal("正在获取下载直链"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.cancelButton = Button.builder(
                        Component.literal("取消").withStyle(ChatFormatting.RED),
                        b -> {
                            ClientSyncTask.cancel();
                            b.active = false;
                        })
                .bounds(this.width / 2 - 60, this.height / 2 + 40, 120, 20)
                .build();
        addRenderableWidget(cancelButton);
    }

    @Override
    public void tick() {
        super.tick();
        SyncProgress p = ClientSyncTask.progress();
        // 阶段一变就交给下载屏 —— 本屏的职责只有"说明正在查"。
        if (this.minecraft != null && p.stage() != SyncProgress.Stage.FETCHING_MANIFEST
                && p.stage() != SyncProgress.Stage.FETCHING_URLS) {
            this.minecraft.setScreen(new DownloadScreen(parent));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFFF);
        y += 18;
        graphics.drawCenteredString(this.font,
                Component.literal("正在向 Modrinth 与 CurseForge 反查文件哈希…")
                        .withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
        y += 14;
        graphics.drawCenteredString(this.font,
                Component.literal("匹配到的文件将直接从官方 CDN 下载，不占用服务器带宽。")
                        .withStyle(ChatFormatting.DARK_GRAY), centerX, y, 0x888888);
        y += 24;

        SyncProgress p = ClientSyncTask.progress();
        if (!p.modpackName().isBlank()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("整合包：" + p.modpackName()).withStyle(ChatFormatting.GRAY),
                    centerX, y, 0xAAAAAA);
        }

        // 一个简单的动态省略号，表明"它还活着"
        int dots = (int) ((System.currentTimeMillis() / 400) % 4);
        graphics.drawCenteredString(this.font,
                Component.literal(".".repeat(dots)), centerX, y + 18, 0x55FF55);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
