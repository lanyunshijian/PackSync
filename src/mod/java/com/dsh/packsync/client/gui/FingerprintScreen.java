package com.dsh.packsync.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 服务器身份核对界面：<b>必须手动输入管理员给的指纹，匹配才放行。</b>
 *
 * <p><b>为什么必须"输入"而不是"确认"</b>：这台服务器自己会报一个指纹过来，
 * 如果界面只是把它显示出来、再给个"我信任"的按钮，那玩家点一下就过了 ——
 * 而中间人完全可以把这个指纹换成自己的。等于把锁和钥匙一起挂在门上。
 *
 * <p>所以这里的做法是：<b>不显示服务器报来的指纹</b>，要求玩家从<b>服务器之外</b>
 * 的渠道（群公告、管理员私聊、官网）拿到指纹，粘贴进来。输入的与服务器报来的
 * 一致 → 说明报来的那个没被掉包（中间人不知道正确指纹，伪造不出来）；
 * 不一致 → 拒绝继续。这才是真正能防中间人的一步。
 *
 * <p>获取指纹的方式：管理员在服务端执行 {@code /packsync host fingerprint}，
 * 把输出发给玩家。
 */
public class FingerprintScreen extends Screen {

    private final Screen parent;
    /** 服务器报来的指纹 —— 只用于比对，绝不显示给玩家。 */
    private final String expectedFingerprint;
    private final Consumer<Boolean> callback;
    private final Runnable onCancel;

    private EditBox inputBox;
    private Button verifyButton;
    private String status = "";
    /** 状态文字是"好结果"还是"坏结果"，决定用绿色还是红色。 */
    private boolean statusOk;
    /** >= 0 表示验证已通过、正在倒计时关闭界面（让玩家来得及看见成功提示）。 */
    private int successTicks = -1;

    public FingerprintScreen(Screen parent, String expectedFingerprint,
                             Consumer<Boolean> callback, Runnable onCancel) {
        super(Component.literal("服务器身份核对"));
        this.parent = parent;
        this.expectedFingerprint = expectedFingerprint == null ? "" : expectedFingerprint;
        this.callback = callback;
        this.onCancel = onCancel;
    }

    private int inputY() {
        return this.height / 2 - 16;
    }

    private int buttonY() {
        return this.height / 2 + 14;
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int w = Math.min(360, this.width - 40);

        this.inputBox = new EditBox(this.font, centerX - w / 2, inputY(), w, 20,
                Component.literal("在此粘贴管理员给你的指纹"));
        this.inputBox.setMaxLength(256);
        this.inputBox.setHint(Component.literal("粘贴管理员给你的指纹（不是屏幕上显示的）")
                .withStyle(ChatFormatting.DARK_GRAY));
        this.inputBox.setResponder(s -> refresh());
        addRenderableWidget(inputBox);
        setInitialFocus(inputBox);

        this.verifyButton = Button.builder(
                        Component.literal("验证并继续").withStyle(ChatFormatting.GREEN),
                        b -> verify())
                .bounds(centerX - w / 2, buttonY(), w / 2 - 3, 20).build();
        addRenderableWidget(verifyButton);

        addRenderableWidget(Button.builder(
                        Component.literal("取消同步").withStyle(ChatFormatting.RED),
                        b -> decide(false))
                .bounds(centerX + 3, buttonY(), w / 2 - 3, 20).build());

        refresh();
    }

    /** 输入非空才允许点验证。 */
    private void refresh() {
        if (verifyButton != null) {
            String v = inputBox == null ? "" : inputBox.getValue();
            verifyButton.active = v != null && !v.isBlank();
        }
    }

    /**
     * 归一化指纹。
     *
     * <p>委托给同步流程里的同一个实现 —— 玩家复制来的往往是一整段文本
     * （标题＋等号＋中文说明），那里会提取出其中最长的十六进制片段。
     * 两处若各写一份，迟早会出现"界面说通过、同步说不过"的诡异情况。
     */
    private static String normalize(String s) {
        return com.dsh.packsync.client.ClientSyncTask.normalizeFingerprint(s);
    }

    private void verify() {
        String typed = normalize(inputBox == null ? "" : inputBox.getValue());
        String actual = normalize(expectedFingerprint);
        if (actual.isEmpty()) {
            // 服务端没给指纹（对方关了校验），此时无法核对 —— 不放行，避免"看着像通过"。
            status = "服务器未提供指纹，无法核对。请让管理员检查 enableFingerprintCheck。";
            return;
        }
        if (typed.equals(actual)) {
            // ★ 不要把界面直接关掉：那样玩家只看到"界面没了"，根本不知道是成功还是失败。
            //   这里先明确显示成功，约 1 秒后再自动继续同步。
            statusOk = true;
            status = "✓ 指纹一致，验证通过 —— 即将开始同步…";
            successTicks = 0;
            tellChat("✓ 服务器身份验证通过，开始同步", ChatFormatting.GREEN);
        } else {
            statusOk = false;
            status = "✗ 指纹不匹配 —— 可能有人在冒充该服务器。已拒绝继续。";
            tellChat("✗ 指纹不匹配，已中止同步（可能是中间人）", ChatFormatting.RED);
        }
    }

    /** 往聊天栏发一条，让结果即使界面关掉也留得住。 */
    private void tellChat(String text, ChatFormatting color) {
        try {
            Component msg = Component.literal("[PackSync] " + text).withStyle(color);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(msg, false);
            } else if (this.minecraft != null && this.minecraft.gui != null) {
                this.minecraft.gui.getChat().addMessage(msg);
            }
        } catch (Throwable ignored) {
            // 聊天栏发不出去也不该影响验证结果本身。
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (successTicks >= 0) {
            successTicks++;
            // 留约 1 秒让玩家看清"验证通过"，然后再继续同步。
            if (successTicks >= 20) {
                decide(true);
            }
        }
    }

    private void decide(boolean ok) {
        if (callback != null) {
            callback.accept(ok);
        }
        // ⚠️ 只有回调【没有】自己切屏时才回 parent。
        //   以前这里无条件 setScreen(parent)，把回调刚切过去的"下载进度屏"
        //   又覆盖回了主菜单 —— 表现就是"验证成功后界面不知道跑哪去了"。
        if (this.minecraft != null && this.minecraft.screen == this) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = this.height / 2 - 78;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFFF);
        y += 16;
        graphics.drawCenteredString(this.font,
                Component.literal("本次会从这台服务器下载文件，需要先确认它的身份。")
                        .withStyle(ChatFormatting.WHITE), centerX, y, 0xFFFFFF);
        y += 12;
        graphics.drawCenteredString(this.font,
                Component.literal("请向管理员索取指纹（服务端执行 /packsync host fingerprint），粘贴到下面。")
                        .withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
        y += 12;
        graphics.drawCenteredString(this.font,
                Component.literal("⚠ 屏幕上报来的指纹不作展示 —— 它可能已被中间人替换，照抄它等于没核对。")
                        .withStyle(ChatFormatting.RED), centerX, y, 0xFF5555);

        // 输入框下方的状态提示：成功绿、失败红 —— 让玩家一眼看出结果。
        int sy = buttonY() - 12;
        if (status != null && !status.isBlank()) {
            ChatFormatting c = statusOk ? ChatFormatting.GREEN : ChatFormatting.RED;
            int rgb = statusOk ? 0x55FF55 : 0xFF5555;
            graphics.drawCenteredString(this.font,
                    Component.literal(status).withStyle(c), centerX, sy, rgb);
        }

        y = buttonY() + 26;
        if (y < this.height - 10) {
            graphics.drawCenteredString(this.font,
                    Component.literal("指纹一致 → 说明文件确实来自这台服务器，中途没被掉包。")
                            .withStyle(ChatFormatting.DARK_GRAY), centerX, y, 0x777777);
        }
    }

    @Override
    public void onClose() {
        decide(false);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        decide(false);
        return false;
    }
}
