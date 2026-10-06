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
 * 风险确认屏。
 *
 * <p><b>这是整个 mod 最重要的一道安全闸门。</b>
 * 整合包里的 jar 分两类：
 * <ul>
 *   <li>能在 Modrinth / CurseForge 按哈希匹配到的 —— 平台做过恶意软件检查，风险较低；</li>
 *   <li><b>匹配不到的</b> —— 可能是私有 mod，也可能是被人改过的恶意文件。
 *       本 mod **无法区分**这两者。</li>
 * </ul>
 * 所以对第二类必须停下来问一次，而不是默默写进玩家的 {@code mods/}。
 *
 * <p>防手滑设计：
 * <ul>
 *   <li>先等几秒才允许点确认 —— 强制玩家读一眼；</li>
 *   <li>必须**勾选**"我知道风险"才能点下载；</li>
 *   <li>取消按钮始终可用。</li>
 * </ul>
 *
 * <p><b>布局必须自适应</b>：Minecraft 的 GUI 坐标是<b>缩放后</b>的逻辑尺寸，
 * 854×480 的窗口在 GUI scale = 2 时只有 427×240 —— 高度非常小。
 * 所以这里所有纵向位置都<b>相对 {@code height} 从底部倒推</b>，列表行数按剩余空间
 * 动态计算。曾经的写法是从顶部一路 {@code y += …} 累加、行数写死 10 行，
 * 结果在小窗口下列表直接撞进按钮区，警告文字和复选框糊成一团。
 */
public class DangerScreen extends Screen {

    /** 倒计时秒数：期间确认复选框不可点。 */
    private static final int COUNTDOWN_SECONDS = 5;

    /** 列表每行占用高度。 */
    private static final int LINE_HEIGHT = 10;

    private final Screen parent;
    private final SyncProgress progress;

    private int ticks;
    private boolean acknowledged;
    private Button ackButton;
    private Button confirmButton;

    public DangerScreen(Screen parent, SyncProgress progress) {
        super(Component.literal("风险确认"));
        this.parent = parent;
        this.progress = progress;
    }

    // ── 纵向锚点：全部相对底部倒推，保证小窗口下也不重叠 ──────────────

    /** 复选框（"我了解风险"）所在行。 */
    private int ackY() {
        return this.height - 52;
    }

    /** 确认/取消按钮所在行。 */
    private int actionY() {
        return this.height - 28;
    }

    /** 底部两行红色警告的起始 y。 */
    private int warnY() {
        return this.height - 78;
    }

    /** 列表可用区域的起始 y。 */
    private int listTop() {
        return 68;
    }

    /** 列表可用区域的结束 y（警告文字之上）。 */
    private int listBottom() {
        return warnY() - 6;
    }

    @Override
    protected void init() {
        super.init();
        ticks = 0;
        acknowledged = false;

        int centerX = this.width / 2;

        this.ackButton = Button.builder(ackLabel(), b -> {
            acknowledged = !acknowledged;
            b.setMessage(ackLabel());
            if (confirmButton != null) {
                confirmButton.active = acknowledged;
            }
        }).bounds(centerX - 150, ackY(), 300, 20).build();
        this.ackButton.active = false; // 倒计时结束前不可点
        addRenderableWidget(ackButton);

        this.confirmButton = Button.builder(
                        Component.literal("我已了解风险，继续下载").withStyle(ChatFormatting.RED),
                        b -> {
                            SyncProgress p = ClientSyncTask.progress();
                            p.confirmDangerous();
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new DownloadScreen(parent));
                            }
                        })
                .bounds(centerX - 155, actionY(), 150, 20)
                .build();
        this.confirmButton.active = false;
        addRenderableWidget(confirmButton);

        addRenderableWidget(Button.builder(
                        Component.literal("取消（不下载）").withStyle(ChatFormatting.GREEN),
                        b -> {
                            ClientSyncTask.cancel();
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(parent);
                            }
                        })
                .bounds(centerX + 5, actionY(), 150, 20).build());
    }

    private Component ackLabel() {
        String box = acknowledged ? "[x] " : "[ ] ";
        boolean counting = !acknowledged && ticks < COUNTDOWN_SECONDS * 20;
        // 倒计时文案尽量短：按钮宽度有限，长句会被裁掉。
        String suffix = counting ? "（" + (COUNTDOWN_SECONDS - ticks / 20) + "s）" : "";
        return Component.literal(box + "我了解风险，并信任此服务器运营者" + suffix)
                .withStyle(ChatFormatting.YELLOW);
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (ackButton != null) {
            boolean ready = ticks >= COUNTDOWN_SECONDS * 20;
            if (ackButton.active != ready) {
                ackButton.active = ready;
                ackButton.setMessage(ackLabel());
            }
            // 倒计时期间每 20 tick 刷新一次文字，让玩家看到还剩几秒
            if (!ready && ticks % 20 == 0) {
                ackButton.setMessage(ackLabel());
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int y = 16;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFF5555);
        y += 16;

        List<String> unverified = progress == null ? List.of() : progress.unverifiedJars();
        int matched = progress == null ? -1 : progress.publicMatchCount();
        // 四种情形，理由和处置都不同，文案必须分开：
        //   matched == -1                只走服务端 —— 全部文件都未经第三方检查；
        //   matched == 0                 查了但一个都没匹配上（多半连不上公共站）；
        //   matched > 0 且列表非空         查过，这些是公共站里确实没有的；
        //   matched > 0 且列表为空         混合模式全匹配 —— 仍可能因 CDN 失败回退服务端。
        boolean serverOnly = matched < 0;
        boolean publicUnreachable = matched == 0;
        boolean mixedMayFallback = !serverOnly && unverified.isEmpty();

        String title;
        if (mixedMayFallback) {
            title = "本次同步仍可能从本服务器下载文件：";
        } else if (serverOnly) {
            title = "以下 " + unverified.size() + " 个 mod 将全部从本服务器下载：";
        } else {
            title = "以下 " + unverified.size() + " 个 JAR 无法在 Modrinth / CurseForge 上匹配到：";
        }
        graphics.drawCenteredString(this.font,
                Component.literal(title).withStyle(ChatFormatting.WHITE), centerX, y, 0xFFFFFF);
        y += 12;

        String subtitle;
        ChatFormatting subtitleColor;
        int subtitleRgb;
        if (mixedMayFallback) {
            // 全部匹配到 ≠ 一定走公共站：CDN 直链失败时会回退服务端。
            subtitle = "公共站直链可能失败并回退服务端，那些文件未经第三方检查";
            subtitleColor = ChatFormatting.GOLD;
            subtitleRgb = 0xFFAA00;
        } else if (serverOnly) {
            subtitle = "它们未经 Modrinth / CurseForge 检查 —— 只在你信任该服务器时才继续";
            subtitleColor = ChatFormatting.GOLD;
            subtitleRgb = 0xFFAA00;
        } else if (publicUnreachable) {
            subtitle = "⚠ 一个都没匹配上 —— 很可能是连不上公共站，而非文件私有";
            subtitleColor = ChatFormatting.GOLD;
            subtitleRgb = 0xFFAA00;
        } else {
            subtitle = "可能是私有 mod，也可能被改动过 —— PackSync 无法区分。";
            subtitleColor = ChatFormatting.GRAY;
            subtitleRgb = 0xAAAAAA;
        }
        graphics.drawCenteredString(this.font,
                Component.literal(subtitle).withStyle(subtitleColor), centerX, y, subtitleRgb);

        // ── 列表：行数按可用空间动态决定，绝不越过 warnY() ──────────────
        int avail = (listBottom() - listTop()) / LINE_HEIGHT;
        // 至少留 1 行给"…以及另外 N 个"
        int maxShown = Math.max(1, avail - 1);
        int shown = Math.min(unverified.size(), maxShown);
        int ly = listTop();
        for (int i = 0; i < shown; i++) {
            graphics.drawCenteredString(this.font,
                    Component.literal("• " + unverified.get(i)).withStyle(ChatFormatting.YELLOW),
                    centerX, ly, 0xFFFF55);
            ly += LINE_HEIGHT;
        }
        if (shown < unverified.size()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("…以及另外 " + (unverified.size() - shown) + " 个")
                            .withStyle(ChatFormatting.DARK_GRAY), centerX, ly, 0x777777);
        }

        // ── 底部警告：固定位置，不随列表累加 ────────────────────────────
        int wy = warnY();
        graphics.drawCenteredString(this.font,
                Component.literal("有效的证书或指纹并不能证明这些 JAR 是安全的。")
                        .withStyle(ChatFormatting.RED), centerX, wy, 0xFF5555);
        graphics.drawCenteredString(this.font,
                Component.literal("只有在认识并信任服务器运营者时才继续。")
                        .withStyle(ChatFormatting.RED), centerX, wy + 11, 0xFF5555);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
