package com.dsh.packsync.client.gui;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.client.ClientSyncTask;
import com.dsh.packsync.client.SyncProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 下载进度屏。
 *
 * <p>玩家的核心诉求就是这一屏：**"它到底在干什么、还要多久"**。
 * 所以这里把四件事同时显示出来 —— 进度条、已下载/总量、实时速度、剩余时间，
 * 而不是只给一个转圈动画。
 *
 * <p>它**不注册任何网络回调**：只在 {@code tick()} 里读
 * {@link SyncProgress}，并据此决定下一步切到哪一屏。
 * 这样后台同步线程完全不接触渲染栈。
 */
public class DownloadScreen extends Screen {

    private final Screen parent;
    private Button cancelButton;
    private int tickCounter;
    /**
     * 已经跳转过一次就不再重复跳（避免同一帧内反复切屏）。
     *
     * <p><b>注意</b>：只在<b>真的调用过 setScreen</b> 之后才能置位 ——
     * 见 {@link #tick()} 里那段注释，这里是"同步到 100% 卡住"那个 bug 的现场。
     */
    private boolean handedOff;

    public DownloadScreen(Screen parent) {
        super(Component.literal("正在同步整合包"));
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
                .bounds(this.width / 2 - 60, this.height / 2 + 62, 120, 20)
                .build();
        addRenderableWidget(cancelButton);
    }

    @Override
    public void tick() {
        super.tick();
        SyncProgress p = ClientSyncTask.progress();
        tickCounter++;

        if (handedOff) {
            // 正常情况跳转过就不再管；但如果同步早已结束（stage=DONE）却又回到了本屏，
            // 说明玩家是从后续界面退回来的（例如在更新日志屏按 Esc、或点「稍后自己重启」）。
            // 此时绝不能停在进度条上干等 —— 那个界面已经没有任何意义了。
            // 放开标记，让下面的 DONE 分支再送一程。
            if (p.stage() != SyncProgress.Stage.DONE) {
                return;
            }
            handedOff = false;
        }

        // ⚠️ 绝不能在 minecraft == null 时置 handedOff。
        // 曾经的写法是先进 case、置 handedOff、再判 null —— 一旦 minecraft 为 null，
        // 就变成"标记为已跳转，却根本没跳"，界面永久停在进度条上：
        // 现象就是"同步到 100% 之后卡死不动"，而且 stage 已是 DONE，
        // 所以那行 stage 文字是空的。这里改成：拿不到就原地下次再试。
        Minecraft mc = this.minecraft;
        if (mc == null) {
            return;
        }

        switch (p.stage()) {
            case DONE -> {
                handedOff = true;
                if (!p.needsRestart()) {
                    // 一个文件都没变 —— 重启毫无意义。直接放行回上层。
                    // 否则玩家重启后启动期同步再跑一次又被拦，看起来就是"死活说你没重启"。
                    PackSync.LOGGER.info("[PackSync] 同步结束（无变更，无需重启）：{}", p.summary());
                    mc.setScreen(this.parent);
                    return;
                }
                // ⚠️ parent 必须用【本屏的 parent】，而不是 this。
                // 以前传的是 this，于是更新日志屏/重启屏的"返回"都指回这个已经结束的进度屏，
                // 而它 handedOff 已置位不再跳转 —— 玩家就被困在 100% 进度条上出不来。
                RestartScreen restart = new RestartScreen(this.parent, p);
                Screen next = p.changes().isEmpty()
                        ? restart
                        : new ChangelogScreen(restart, p);
                PackSync.LOGGER.info("[PackSync] 同步结束（{}），切换到 {}",
                        p.summary().isBlank() ? "无摘要" : p.summary().split("\n")[0],
                        next.getClass().getSimpleName());
                mc.setScreen(next);
            }
            case FAILED -> {
                handedOff = true;
                PackSync.LOGGER.warn("[PackSync] 同步失败，切换到错误屏：{}", p.errorMessage());
                mc.setScreen(new ErrorScreen(this, p));
            }
            case AWAITING_FINGERPRINT -> {
                handedOff = true;
                mc.setScreen(new FingerprintScreen(parent, p.serverFingerprint(),
                        trusted -> {
                            p.confirmFingerprint(trusted);
                            if (trusted && this.minecraft != null) {
                                this.minecraft.setScreen(new DownloadScreen(parent));
                            }
                        },
                        () -> p.confirmFingerprint(false)));
            }
            case AWAITING_CONFIRMATION -> {
                handedOff = true;
                mc.setScreen(new DangerScreen(parent, p));
            }
            case CANCELLED -> {
                handedOff = true;
                mc.setScreen(parent);
            }
            default -> {
                // 还在跑，继续留在本屏
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        SyncProgress p = ClientSyncTask.progress();
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFFF);
        y += 16;
        if (!p.modpackName().isBlank()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("整合包：" + p.modpackName()).withStyle(ChatFormatting.GRAY),
                    centerX, y, 0xAAAAAA);
        }
        y += 22;

        // ── 进度条（自绘，避免依赖贴图资源）──────────────────────────
        int barWidth = 240;
        int barHeight = 14;
        int barX = centerX - barWidth / 2;
        int barY = y;
        graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xFF404040);
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF202020);
        int filled = (int) Math.round(barWidth * p.fraction());
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + barHeight, 0xFF4CAF50);
        }
        y += barHeight + 8;

        // ── 数字信息 ────────────────────────────────────────────────
        String percent = String.format("%.1f%%", p.fraction() * 100.0);
        graphics.drawCenteredString(this.font,
                Component.literal(percent + "   " + p.doneFiles() + " / " + p.totalFiles() + " 个文件"),
                centerX, y, 0xFFFFFF);
        y += 12;

        graphics.drawCenteredString(this.font,
                Component.literal(SyncProgress.humanBytes(p.doneBytes()) + " / "
                                + SyncProgress.humanBytes(p.totalBytes()))
                        .withStyle(ChatFormatting.GRAY),
                centerX, y, 0xAAAAAA);
        y += 12;

        String stageText = switch (p.stage()) {
            case FETCHING_MANIFEST -> "正在获取整合包清单…";
            case FETCHING_URLS -> "正在从公共站查询直链…";
            case DOWNLOADING -> "下载中";
            case APPLYING -> "正在应用到游戏目录…";
            case DONE -> "已完成";
            default -> "";
        };
        String line = "速度 " + p.humanSpeed() + "   ｜   剩余 " + p.humanEta();
        if (!stageText.isEmpty()) {
            line += "   ｜   " + stageText;
        }
        graphics.drawCenteredString(this.font, Component.literal(line), centerX, y, 0xAAAAAA);
        y += 16;

        // ── 当前文件 ────────────────────────────────────────────────
        String current = p.currentFile();
        if (current != null && !current.isBlank()) {
            graphics.drawCenteredString(this.font,
                    Component.literal(shorten(current)).withStyle(ChatFormatting.DARK_GRAY),
                    centerX, y, 0x888888);
        }

        // 失败计数（有失败时用红字，让玩家一眼看到）
        if (p.failedFiles() > 0) {
            graphics.drawCenteredString(this.font,
                    Component.literal("失败 " + p.failedFiles() + " 个（会在结束时列出）")
                            .withStyle(ChatFormatting.RED),
                    centerX, y + 12, 0xFF5555);
        }
    }

    private static String shorten(String path) {
        if (path.length() <= 56) {
            return path;
        }
        return "…" + path.substring(path.length() - 55);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // 同步进行中不允许用 Esc 意外关掉进度屏；要停请点"取消"。
        return false;
    }
}
