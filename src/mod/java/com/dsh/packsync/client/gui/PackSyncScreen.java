package com.dsh.packsync.client.gui;

import com.dsh.packsync.client.ClientSyncTask;
import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PackSync 主界面：查看状态、切换下载方式、手动同步。
 *
 * <p><b>正常流程并不需要玩家来这里。</b>连服务器时服务端会在登录期下发整合包信息，
 * 客户端自动开始同步（见 {@code PackSyncLoginNetworking}）。
 *
 * <p>这一屏的意义是"兜底与自助"：
 * <ul>
 *   <li>自动同步被关掉（{@code autoSyncOnJoin=false}）时手动触发；</li>
 *   <li>切换下载方式（本 mod 的新增项）；</li>
 *   <li>出问题时查看已知服务器与指纹。</li>
 * </ul>
 */
public class PackSyncScreen extends Screen {

    private final Screen parent;

    private EditBox addressBox;
    private String statusLine = "";
    private ChatFormatting statusColor = ChatFormatting.GRAY;

    public PackSyncScreen(Screen parent) {
        super(Component.literal("PackSync"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        ClientConfig config = loadConfig();

        int centerX = this.width / 2;
        int y = this.height / 2 - 62;

        this.addressBox = new EditBox(this.font, centerX - 150, y, 300, 20,
                Component.literal("服务器地址"));
        this.addressBox.setMaxLength(255);
        this.addressBox.setHint(Component.literal("play.example.com 或 play.example.com:25565")
                .withStyle(ChatFormatting.DARK_GRAY));
        String known = firstKnownAddress(config);
        if (!known.isBlank()) {
            this.addressBox.setValue(known);
        }
        addRenderableWidget(addressBox);

        y += 26;

        addRenderableWidget(Button.builder(
                        Component.literal("添加并同步").withStyle(ChatFormatting.GREEN),
                        b -> addAndSync())
                .bounds(centerX - 150, y, 145, 20).build());

        addRenderableWidget(Button.builder(modeLabel(config.downloadMode()), b -> {
            ClientConfig cfg = loadConfig();
            DownloadMode next = nextMode(cfg.downloadMode());
            cfg.setDownloadMode(next);
            saveConfig(cfg);
            b.setMessage(modeLabel(next));
            statusLine = "下载方式已切换为：" + next.describe();
            statusColor = ChatFormatting.YELLOW;
        }).bounds(centerX + 5, y, 145, 20).build());

        y += 26;

        addRenderableWidget(Button.builder(
                        Component.literal("同步已记录的服务器"),
                        b -> {
                            if (ClientSyncTask.start()) {
                                if (this.minecraft != null) {
                                    this.minecraft.setScreen(new FetchScreen(parent));
                                }
                            } else {
                                statusLine = "没有可同步的服务器，请在上方填写地址后点「添加并同步」。";
                                statusColor = ChatFormatting.RED;
                            }
                        })
                .bounds(centerX - 150, y, 300, 20).build());

        y += 26;

        addRenderableWidget(Button.builder(
                        Component.literal("设置（下载方式 / 身份校验）").withStyle(ChatFormatting.AQUA),
                        b -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new SettingsScreen(this));
                            }
                        })
                .bounds(centerX - 150, y, 300, 20).build());

        y += 26;

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(centerX - 150, y, 300, 20).build());
    }

    // ── 手动指定服务器并同步 ──────────────────────────────────────────────

    private void addAndSync() {
        String raw = addressBox == null ? "" : addressBox.getValue().trim();
        if (raw.isBlank()) {
            statusLine = "请先填写服务器地址。";
            statusColor = ChatFormatting.RED;
            return;
        }
        statusLine = "正在探测 " + raw + " …";
        statusColor = ChatFormatting.YELLOW;

        new Thread(() -> {
            String outcome = probeAndRecord(raw);
            boolean ok = outcome.startsWith("已找到");
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    statusLine = outcome;
                    statusColor = ok ? ChatFormatting.GREEN : ChatFormatting.RED;
                    if (ok && ClientSyncTask.start()) {
                        this.minecraft.setScreen(new FetchScreen(parent));
                    }
                });
            }
        }, "PackSync-Probe").start();
    }

    private String probeAndRecord(String raw) {
        String host = ServerAddressParser.host(raw);
        int mcPort = ServerAddressParser.port(raw, 25565);
        if (host.isBlank()) {
            return "地址格式不对。示例：play.example.com 或 play.example.com:25565";
        }
        // 分发服务默认在 MC 端口 +1；同端口分流部署则在原端口
        for (int port : new int[]{mcPort + 1, mcPort}) {
            PackClient.ServerInfo info;
            try {
                info = PackClient.fetchInfo(host, port, 5000);
            } catch (Throwable t) {
                continue;
            }
            try {
                PackPaths paths = PackPaths.workingDir();
                ClientConfig config = ConfigIO.loadClient(paths.clientConfigFile());
                ClientConfig.ServerEntry entry = config.installedServers
                        .computeIfAbsent(host + ":" + mcPort + "@" + port,
                                k -> new ClientConfig.ServerEntry());
                entry.mcHost = host;
                entry.mcPort = mcPort;
                entry.host = host;
                entry.port = port;
                entry.modpackName = info.modpackName() == null ? "" : info.modpackName();
                // ⚠️ 不在这里写 entry.fingerprint。手动填地址添加服务器时，
                //    玩家同样没有核对过指纹 —— 交给同步流程去要求他核对。
                config.selectedModpack = entry.modpackName;
                ConfigIO.saveClient(paths.clientConfigFile(), config);
                return "已找到：" + entry.modpackName + "，" + info.fileCount()
                        + " 个文件 / " + Hashing.humanSize(info.totalBytes()) + "，开始同步…";
            } catch (Throwable t) {
                return "已连上服务端，但写入配置失败：" + t;
            }
        }
        return "在 " + host + ":" + mcPort + "（及其 +1 端口）没找到 PackSync 分发服务。";
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        ClientConfig config = loadConfig();
        int centerX = this.width / 2;
        int y = this.height / 2 - 92;

        graphics.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFFF);
        y += 16;
        graphics.drawCenteredString(this.font,
                Component.literal("正常情况无需手动操作：连服务器时会自动同步")
                        .withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);

        y = this.height / 2 + 46;
        List<String> known = new ArrayList<>();
        if (config.installedServers != null) {
            for (ClientConfig.ServerEntry e : config.installedServers.values()) {
                if (e != null && e.mcHost != null && !e.mcHost.isBlank()) {
                    known.add(e.mcHost + ":" + e.mcPort
                            + (e.modpackName == null || e.modpackName.isBlank() ? "" : " → " + e.modpackName));
                }
            }
        }
        if (known.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("（还没有记录任何服务器）").withStyle(ChatFormatting.DARK_GRAY),
                    centerX, y, 0x777777);
        } else {
            graphics.drawCenteredString(this.font,
                    Component.literal("已记录的服务器：").withStyle(ChatFormatting.GRAY), centerX, y, 0xAAAAAA);
            y += 11;
            for (int i = 0; i < Math.min(known.size(), 3); i++) {
                graphics.drawCenteredString(this.font,
                        Component.literal("• " + known.get(i)).withStyle(ChatFormatting.DARK_GRAY),
                        centerX, y, 0x888888);
                y += 11;
            }
        }

        if (!statusLine.isBlank()) {
            graphics.drawCenteredString(this.font, Component.literal(statusLine).withStyle(statusColor),
                    centerX, y + 6, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private static String firstKnownAddress(ClientConfig config) {
        if (config.installedServers == null) {
            return "";
        }
        for (ClientConfig.ServerEntry e : config.installedServers.values()) {
            if (e != null && e.mcHost != null && !e.mcHost.isBlank()) {
                return e.mcHost + (e.mcPort == 25565 ? "" : ":" + e.mcPort);
            }
        }
        return "";
    }

    private static DownloadMode nextMode(DownloadMode current) {
        DownloadMode[] all = DownloadMode.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == current) {
                return all[(i + 1) % all.length];
            }
        }
        return DownloadMode.DEFAULT;
    }

    private static Component modeLabel(DownloadMode mode) {
        return Component.literal("下载方式：" + mode.name());
    }

    private static ClientConfig loadConfig() {
        return ConfigIO.loadClient(PackPaths.workingDir().clientConfigFile());
    }

    private static void saveConfig(ClientConfig config) {
        ConfigIO.saveClient(PackPaths.workingDir().clientConfigFile(), config);
    }
}
