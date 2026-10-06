package com.dsh.packsync.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 跳过验证的二次确认屏。
 *
 * <p>当玩家在指纹核对屏选择"跳过"时会来到这里。
 * <b>它不是走过场</b>：跳过证书验证等于放弃对服务器身份的唯一保障，
 * 之后所有文件都可能是别人伪造的。所以要求玩家：
 * <ol>
 *   <li>等待几秒（强制阅读）；</li>
 *   <li>手动输入一句确认短语。</li>
 * </ol>
 * 目的是把"跳过"从一次点击变成一次明确的、需要动脑的操作。
 */
public class SkipVerificationScreen extends Screen {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final String REQUIRED_TEXT = "I accept the risk";

    private final Screen parent;
    private final Consumer<Boolean> callback;

    private EditBox input;
    private Button skipButton;
    private int ticks;
    private String hint = "";

    public SkipVerificationScreen(Screen parent, Consumer<Boolean> callback) {
        super(Component.literal("安全风险"));
        this.parent = parent;
        this.callback = callback;
    }

    @Override
    protected void init() {
        super.init();
        ticks = 0;
        hint = "";

        int centerX = this.width / 2;
        int y = this.height / 2 + 16;

        this.input = new EditBox(this.font, centerX - 150, y, 300, 20,
                Component.literal("确认短语"));
        this.input.setMaxLength(64);
        this.input.setHint(Component.literal(REQUIRED_TEXT).withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(input);

        this.skipButton = Button.builder(
                        Component.literal("跳过验证").withStyle(ChatFormatting.RED),
                        b -> attemptSkip())
                .bounds(centerX - 150, y + 26, 145, 20)
                .build();
        this.skipButton.active = false;
        addRenderableWidget(skipButton);

        addRenderableWidget(Button.builder(
                        Component.literal("返回核对指纹").withStyle(ChatFormatting.GREEN),
                        b -> back())
                .bounds(centerX + 5, y + 26, 145, 20).build());
    }

    private void attemptSkip() {
        String typed = this.input == null ? "" : this.input.getValue().trim();
        if (!REQUIRED_TEXT.equalsIgnoreCase(typed)) {
            hint = "短语不正确，请照抄上面的英文句子。";
            return;
        }
        if (callback != null) {
            callback.accept(true);
        }
        back();
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (skipButton != null) {
            boolean ready = ticks >= COUNTDOWN_SECONDS * 20;
            if (skipButton.active != ready) {
                skipButton.active = ready;
                skipButton.setMessage(Component.literal(ready
                        ? "跳过验证"
                        : "跳过验证（" + (COUNTDOWN_SECONDS - ticks / 20) + " 秒后可点）")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFF5555);
        y += 18;
        for (String line : new String[]{
                "跳过指纹核对意味着：你无法确认连上的到底是不是这台服务器。",
                "攻击者可以借此把任意文件送进你的 mods/ 目录 —— 那些文件能读写你的电脑。",
                "", "只有在完全清楚后果时才跳过。"
        }) {
            graphics.drawCenteredString(this.font,
                    Component.literal(line).withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
            y += 12;
        }

        y += 8;
        graphics.drawCenteredString(this.font,
                Component.literal("请在下方输入：" + REQUIRED_TEXT)
                        .withStyle(ChatFormatting.YELLOW), centerX, y, 0xFFFF55);

        if (!hint.isBlank()) {
            graphics.drawCenteredString(this.font,
                    Component.literal(hint).withStyle(ChatFormatting.RED),
                    centerX, this.height / 2 + 46, 0xFF5555);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        back();
        return false;
    }
}
