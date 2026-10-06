package com.dsh.packsync.client.gui;

import com.dsh.packsync.PackSync;
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
 * 首次同步前的设置屏：连服发现缺包时<b>自动弹出</b>，选完再下载。
 *
 * <p><b>为什么要有这一屏</b>：玩家不需要先知道"按某个键能打开设置"再回来连服务器。
 * 真正需要做决定的时刻，就是"我连上了一台服务器，它要往我游戏里放 158 个文件"的这一刻。
 * 所以把下载方式和身份校验的选择放在这里，选完点开始即可。
 *
 * <p>只在<b>首次</b>同步某台服务器时出现（判据是该服务器条目还没有成功同步记录）；
 * 之后同一台服务器直接进下载进度屏，不再每次打扰。
 *
 * <p>布局全部相对底部倒推：MC 的 GUI 坐标是缩放后的逻辑尺寸，小窗口下写死坐标必重叠。
 */
public class SyncSetupScreen extends Screen {

    /** 用户点"开始同步"后的动作。返回 null 表示已成功接管（会自行切屏）；
     *  返回非 null 则是失败原因，显示在界面上 —— 绝不能让点击"毫无反应"。 */
    private final java.util.function.Supplier<String> onStart;
    private final String modpackName;
    private final int fileCount;
    private final long totalBytes;
    /** 服务端身份指纹（SHA-256），显示出来供玩家与管理员核对。 */
    private final String fingerprint;

    private DownloadMode mode;
    private boolean verifyFingerprint;

    private Button[] modeButtons;
    private Button verifyButton;
    /** 启动失败时的提示（例如"已有同步在进行"）。 */
    private String status = "";

    public SyncSetupScreen(String modpackName, int fileCount, long totalBytes,
                           String fingerprint, java.util.function.Supplier<String> onStart) {
        super(Component.literal("同步前设置"));
        this.modpackName = modpackName == null ? "" : modpackName;
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.onStart = onStart;
    }

    private int modeFirstY() {
        return 72;
    }

    private int verifyY() {
        return modeFirstY() + 3 * 22 + 12;
    }

    private int startY() {
        return this.height - 52;
    }

    private int cancelY() {
        return this.height - 28;
    }

    @Override
    protected void init() {
        super.init();
        ClientConfig cfg = load();
        this.mode = cfg.downloadMode();
        this.verifyFingerprint = cfg.verifyServerFingerprint;

        int centerX = this.width / 2;
        int w = Math.min(400, this.width - 40);

        DownloadMode[] modes = {
                DownloadMode.PUBLIC_ONLY,
                DownloadMode.PUBLIC_FIRST,
                DownloadMode.SERVER_ONLY,
        };
        modeButtons = new Button[modes.length];
        for (int i = 0; i < modes.length; i++) {
            final DownloadMode m = modes[i];
            modeButtons[i] = Button.builder(modeLabel(m), b -> {
                this.mode = m;
                refresh();
            }).bounds(centerX - w / 2, modeFirstY() + i * 22, w, 20).build();
            addRenderableWidget(modeButtons[i]);
        }

        verifyButton = Button.builder(verifyLabel(), b -> {
            this.verifyFingerprint = !this.verifyFingerprint;
            refresh();
        }).bounds(centerX - w / 2, verifyY(), w, 20).build();
        addRenderableWidget(verifyButton);

        addRenderableWidget(Button.builder(
                        Component.literal("开始同步").withStyle(ChatFormatting.GREEN),
                        b -> startSync())
                .bounds(centerX - w / 2, startY(), w, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal("取消").withStyle(ChatFormatting.RED),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(null);
                            }
                        })
                .bounds(centerX - w / 2, cancelY(), w, 20).build());

        refresh();
    }

    /** 保存选择并开始下载。 */
    private void startSync() {
        ClientConfig cfg = load();
        cfg.setDownloadMode(this.mode);
        cfg.verifyServerFingerprint = this.verifyFingerprint;
        ConfigIO.saveClient(PackPaths.workingDir().clientConfigFile(), cfg);

        String err = onStart == null ? "内部错误：没有可执行的启动动作" : onStart.get();
        // 成功时 onStart 会自己切到进度屏；失败则把原因显示出来，
        // 不能让玩家点了按钮却什么都不发生。
        status = err == null ? "" : err;
        if (err != null) {
            PackSync.LOGGER.warn("[PackSync] 开始同步失败：{}", err);
        }
    }

    private void refresh() {
        DownloadMode[] modes = {DownloadMode.PUBLIC_ONLY, DownloadMode.PUBLIC_FIRST, DownloadMode.SERVER_ONLY};
        if (modeButtons != null) {
            for (int i = 0; i < modeButtons.length && i < modes.length; i++) {
                modeButtons[i].setMessage(modeLabel(modes[i]));
            }
        }
        if (verifyButton != null) {
            verifyButton.setMessage(verifyLabel());
        }
    }

    private Component modeLabel(DownloadMode m) {
        boolean sel = (m == this.mode);
        return Component.literal((sel ? "● " : "○ ") + shortName(m))
                .withStyle(sel ? ChatFormatting.GREEN : ChatFormatting.GRAY);
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
        return Component.literal((verifyFingerprint ? "[x] " : "[ ] ")
                        + "校验服务器身份指纹（防第三方冒充）")
                .withStyle(verifyFingerprint ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFF);

        graphics.drawCenteredString(this.font,
                Component.literal("本服务器需要同步整合包 \"" + modpackName + "\"")
                        .withStyle(ChatFormatting.WHITE), centerX, 32, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal(fileCount + " 个文件 · "
                                + com.dsh.packsync.client.SyncProgress.humanBytes(totalBytes))
                        .withStyle(ChatFormatting.GRAY), centerX, 46, 0xAAAAAA);

        graphics.drawCenteredString(this.font,
                Component.literal("下载方式").withStyle(ChatFormatting.YELLOW), centerX, 60, 0xFFFF55);

        int hintY = verifyY() + 26;
        if (hintY < startY() - 4) {
            String hint = switch (this.mode) {
                case SERVER_ONLY -> "全部文件由本服务器提供（不访问公共站）";
                case PUBLIC_ONLY -> "只从公共站匹配；匹配不到的会同步失败";
                case SERVER_FIRST -> "先试服务端，失败再去公共站";
                case PUBLIC_FIRST -> "先试公共站（更快），匹配不到再走服务端";
            };
            graphics.drawCenteredString(this.font,
                    Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY),
                    centerX, hintY, 0x777777);
            // 指纹校验的用途说明 —— 它是防中间人篡改的，不是"必须核对那串字符"。
            if (this.verifyFingerprint && hintY + 12 < startY() - 4) {
                graphics.drawCenteredString(this.font,
                        Component.literal("校验指纹用于确认文件确实来自这台服务器，而不是被中途替换")
                                .withStyle(ChatFormatting.DARK_GRAY),
                        centerX, hintY + 12, 0x777777);
            }
        }

        // 启动失败的原因（例如"已有同步在进行"）—— 否则玩家点了按钮毫无反应。
        if (!status.isBlank()) {
            graphics.drawCenteredString(this.font,
                    Component.literal(status).withStyle(ChatFormatting.RED),
                    centerX, startY() - 12, 0xFF5555);
        }
    }

    private static ClientConfig load() {
        return ConfigIO.loadClient(PackPaths.workingDir().clientConfigFile());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 必须明确选择：开始 或 取消
    }
}
