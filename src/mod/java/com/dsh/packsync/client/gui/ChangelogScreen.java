package com.dsh.packsync.client.gui;

import com.dsh.packsync.client.SyncProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 更新日志屏。
 *
 * <p>显示本次同步"加了什么、删了什么"。
 * 这个信息比看起来重要：玩家看到"某个 mod 被删了"时，
 * 如果不知道是服务器要求的，第一反应会是"我的整合包坏了"。
 */
public class ChangelogScreen extends Screen {

    private final Screen parent;
    private final SyncProgress progress;
    private int scroll;

    public ChangelogScreen(Screen parent, SyncProgress progress) {
        super(Component.literal("本次变更"));
        this.parent = parent;
        this.progress = progress;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> back())
                .bounds(this.width / 2 - 60, this.height - 32, 120, 20).build());
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
        graphics.drawCenteredString(this.font, this.title, centerX, 24, 0xFFFFFF);

        List<String> changes = progress == null ? List.of() : progress.changes();
        if (changes.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("没有检测到变更。").withStyle(ChatFormatting.GRAY),
                    centerX, 60, 0xAAAAAA);
            return;
        }

        int added = (int) changes.stream().filter(c -> c.startsWith("+ ")).count();
        int removed = (int) changes.stream().filter(c -> c.startsWith("- ")).count();
        graphics.drawCenteredString(this.font,
                Component.literal("新增 " + added + "   ｜   删除 " + removed)
                        .withStyle(ChatFormatting.GRAY),
                centerX, 42, 0xAAAAAA);

        // 简易列表：每行一条，超出可视区就靠滚动
        int lineHeight = 11;
        int top = 62;
        int bottom = this.height - 44;
        int visible = Math.max(1, (bottom - top) / lineHeight);
        int maxScroll = Math.max(0, changes.size() - visible);
        scroll = Math.min(scroll, maxScroll);

        for (int i = 0; i < visible && (i + scroll) < changes.size(); i++) {
            String entry = changes.get(i + scroll);
            boolean add = entry.startsWith("+ ");
            int color = add ? 0x55FF55 : 0xFF5555;
            graphics.drawString(this.font,
                    Component.literal(entry).withStyle(add ? ChatFormatting.GREEN : ChatFormatting.RED),
                    centerX - 150, top + i * lineHeight, color);
        }

        if (maxScroll > 0) {
            graphics.drawCenteredString(this.font,
                    Component.literal("（滚轮查看更多，" + changes.size() + " 条）")
                            .withStyle(ChatFormatting.DARK_GRAY),
                    centerX, bottom + 4, 0x777777);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        back();
        return false;
    }
}
