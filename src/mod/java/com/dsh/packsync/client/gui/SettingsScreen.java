package com.dsh.packsync.client.gui;

import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * PackSync 设置屏：下载方式三选一 + 身份密钥校验开关。
 *
 * <p>为什么要有这一屏：以前下载方式只能靠在主界面反复点一个按钮"循环切换"，
 * 玩家根本不知道有几种、当前是哪种、每种什么含义。密钥校验同理 —— 它是整条
 * 信任链唯一的锚点，关掉意味着"谁都能冒充你连的服务器"，这种事必须让玩家
 * <b>看见并明确选择</b>，而不是藏在配置文件里。
 *
 * <p>布局全部相对底部倒推、行数按可用高度算：MC 的 GUI 坐标是缩放后的逻辑尺寸，
 * 854×480 的窗口在 GUI scale = 2 时只有 427×240，写死坐标必然重叠。
 */
public class SettingsScreen extends Screen {

    private final Screen parent;

    private DownloadMode mode;
    private boolean verifyFingerprint;
    private boolean strictFingerprint;

    /** 三个下载方式按钮，点击后统一刷新文案。 */
    private Button[] modeButtons;
    private Button verifyButton;
    private Button strictButton;

    public SettingsScreen(Screen parent) {
        super(Component.literal("PackSync 设置"));
        this.parent = parent;
    }

    // ── 纵向锚点 ──────────────────────────────────────────────────────────

    private int titleY() {
        return 14;
    }

    private int modeLabelY() {
        return 36;
    }

    private int modeFirstY() {
        return 50;
    }

    private int secLabelY() {
        return modeFirstY() + 3 * 22 + 8;
    }

    /** 完成按钮固定在底部。 */
    private int doneY() {
        return this.height - 28;
    }

    @Override
    protected void init() {
        super.init();
        ClientConfig cfg = load();
        this.mode = cfg.downloadMode();
        this.verifyFingerprint = cfg.verifyServerFingerprint;
        this.strictFingerprint = cfg.strictFingerprint;

        int centerX = this.width / 2;
        int w = Math.min(400, this.width - 40);

        // ── 下载方式：三个并排/堆叠的选项 ──────────────────────────────
        DownloadMode[] modes = {
                DownloadMode.PUBLIC_ONLY,   // 只从公共站
                DownloadMode.PUBLIC_FIRST,  // 混合（两种都用）
                DownloadMode.SERVER_ONLY,   // 只从服务端
        };
        modeButtons = new Button[modes.length];
        for (int i = 0; i < modes.length; i++) {
            final DownloadMode m = modes[i];
            modeButtons[i] = Button.builder(modeLabel(m), b -> {
                this.mode = m;
                refreshLabels();
            }).bounds(centerX - w / 2, modeFirstY() + i * 22, w, 20).build();
            addRenderableWidget(modeButtons[i]);
        }

        // ── 安全：两个开关 ────────────────────────────────────────────
        verifyButton = Button.builder(verifyLabel(), b -> {
            this.verifyFingerprint = !this.verifyFingerprint;
            // 关掉指纹校验后，"严格模式"就无从谈起，顺带置灰。
            if (!this.verifyFingerprint) {
                this.strictFingerprint = false;
            }
            refreshLabels();
        }).bounds(centerX - w / 2, secLabelY(), w, 20).build();
        addRenderableWidget(verifyButton);

        strictButton = Button.builder(strictLabel(), b -> {
            this.strictFingerprint = !this.strictFingerprint;
            if (this.strictFingerprint) {
                this.verifyFingerprint = true; // 严格模式必然要求校验
            }
            refreshLabels();
        }).bounds(centerX - w / 2, secLabelY() + 22, w, 20).build();
        addRenderableWidget(strictButton);

        // ── 完成（保存并返回）─────────────────────────────────────────
        addRenderableWidget(Button.builder(
                        Component.literal("完成").withStyle(ChatFormatting.GREEN),
                        b -> saveAndClose())
                .bounds(centerX - w / 2, doneY(), w, 20).build());

        refreshLabels();
    }

    /** 把当前选择写回配置。 */
    private void saveAndClose() {
        ClientConfig cfg = load();
        cfg.setDownloadMode(this.mode);
        cfg.verifyServerFingerprint = this.verifyFingerprint;
        cfg.strictFingerprint = this.strictFingerprint;
        ConfigIO.saveClient(PackPaths.workingDir().clientConfigFile(), cfg);
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /** 选项文案随状态刷新（选中用 ●，未选用 ○）。 */
    private void refreshLabels() {
        DownloadMode[] modes = {DownloadMode.PUBLIC_ONLY, DownloadMode.PUBLIC_FIRST, DownloadMode.SERVER_ONLY};
        if (modeButtons != null) {
            for (int i = 0; i < modeButtons.length && i < modes.length; i++) {
                modeButtons[i].setMessage(modeLabel(modes[i]));
            }
        }
        if (verifyButton != null) {
            verifyButton.setMessage(verifyLabel());
        }
        if (strictButton != null) {
            strictButton.setMessage(strictLabel());
            strictButton.active = this.verifyFingerprint;
        }
    }

    private Component modeLabel(DownloadMode m) {
        String mark = (m == this.mode) ? "● " : "○ ";
        ChatFormatting color = (m == this.mode) ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        return Component.literal(mark + shortName(m)).withStyle(color);
    }

    private static String shortName(DownloadMode m) {
        switch (m) {
            case PUBLIC_ONLY:
                return "只从公共站下载（Modrinth / CurseForge）";
            case SERVER_ONLY:
                return "只从服务端下载（不查询公共站）";
            case SERVER_FIRST:
                return "混合：优先服务端，失败回退公共站";
            case PUBLIC_FIRST:
            default:
                return "混合：优先公共站，失败回退服务端";
        }
    }

    private Component verifyLabel() {
        String box = this.verifyFingerprint ? "[x] " : "[ ] ";
        return Component.literal(box + "校验服务器身份指纹（防第三方冒充）")
                .withStyle(this.verifyFingerprint ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private Component strictLabel() {
        String box = this.strictFingerprint ? "[x] " : "[ ] ";
        return Component.literal(box + "严格模式：指纹不符时拒绝同步")
                .withStyle(this.strictFingerprint ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        graphics.drawCenteredString(this.font, this.title, centerX, titleY(), 0xFFFFFF);

        graphics.drawCenteredString(this.font,
                Component.literal("下载方式").withStyle(ChatFormatting.YELLOW), centerX, modeLabelY(), 0xFFFF55);

        int secY = secLabelY() - 12;
        if (secY > modeFirstY() + 3 * 22) {
            graphics.drawCenteredString(this.font,
                    Component.literal("身份校验").withStyle(ChatFormatting.YELLOW), centerX, secY, 0xFFFF55);
        }

        // 底部提示：说明当前选择的后果
        int hintY = doneY() - 14;
        if (hintY > secLabelY() + 44) {
            String hint = this.verifyFingerprint
                    ? "开启校验后，服务器身份变化会被发现（服务器正常换密钥时会提示）"
                    : "⚠ 已关闭校验：任何人都可能冒充你连的服务器";
            graphics.drawCenteredString(this.font,
                    Component.literal(hint).withStyle(this.verifyFingerprint
                            ? ChatFormatting.DARK_GRAY : ChatFormatting.RED),
                    centerX, hintY, this.verifyFingerprint ? 0x777777 : 0xFF5555);
        }
    }

    private static ClientConfig load() {
        return ConfigIO.loadClient(PackPaths.workingDir().clientConfigFile());
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        saveAndClose();
        return false;
    }
}
