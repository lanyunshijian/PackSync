package com.dsh.packsync.client;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.PackSyncPresence;
import com.dsh.packsync.client.gui.DownloadScreen;
import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 处理服务端在登录期主动下发的整合包信息。
 *
 * <p><b>这是"连服即同步"的关键一步。</b>有了它，玩家的完整体验是：
 * <pre>
 *   装好 jar → 连服务器 → 弹出风险确认/下载进度 → 重启 → 完成
 * </pre>
 * 不需要绑快捷键，也不需要自己找按钮 —— 与常见做法一致。
 *
 * <p>之前的版本缺了这一环，导致客户端只能靠"猜端口 + 探测 HTTP"发现服务器，
 * 因此必须留一个手动触发点，使用方式就与原版产生了实质差异。
 */
public final class ServerInfoHandler {

    private ServerInfoHandler() {
    }

    public static void handle(PackSyncPresence.ServerInfoMsg msg) {
        if (msg == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // 服务端没填 addressToSend 时，host 留空 —— 那就沿用玩家自己连 MC 用的地址。
        String host = msg.host();
        if (host == null || host.isBlank()) {
            try {
                if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                    host = ServerRecorder.stripPort(mc.getCurrentServer().ip);
                }
            } catch (Throwable ignored) {
                // 拿不到就放弃记录 host。
            }
        }
        if (host == null || host.isBlank()) {
            PackSync.LOGGER.warn("[PackSync] 服务端下发了整合包信息，但无法确定主机地址，已忽略");
            return;
        }

        final String finalHost = host;
        try {
            PackPaths paths = PackPaths.workingDir();
            ClientConfig config = ConfigIO.loadClient(paths.clientConfigFile());

            int mcPort = 25565;
            try {
                if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                    mcPort = ServerRecorder.parsePort(mc.getCurrentServer().ip, 25565);
                }
            } catch (Throwable ignored) {
                // 用默认端口。
            }

            ClientConfig.ServerEntry entry = config.installedServers
                    .computeIfAbsent(finalHost + ":" + mcPort, k -> new ClientConfig.ServerEntry());
            entry.mcHost = finalHost;
            entry.mcPort = mcPort;
            entry.host = finalHost;
            entry.port = msg.port();                 // 服务端直接告诉我们端口，不必再猜
            entry.modpackName = msg.modpackName();
            // ⚠️ 不在这里写 entry.fingerprint —— 那是"信任服务器"的动作，
            //    必须等玩家在核对界面输入过管理员给的指纹、比对通过之后才做。
            //    （同步流程在 ClientSyncTask 里负责写入。）

            boolean firstInstall = isFirstInstall(paths, msg.modpackName());
            if (firstInstall) {
                config.selectedModpack = msg.modpackName();
            }
            ConfigIO.saveClient(paths.clientConfigFile(), config);

            PackSync.LOGGER.info("[PackSync] 服务端下发：{}:{} / 整合包 \"{}\" / {} 个文件 / {}",
                    finalHost, msg.port(), msg.modpackName(), msg.fileCount(),
                    com.dsh.packsync.core.util.Hashing.humanSize(msg.totalBytes()));

            if (!config.autoSyncOnJoin) {
                tell(mc, "已记录本服务器的整合包「" + msg.modpackName()
                        + "」。自动同步已关闭，可在 PackSync 界面手动同步。");
                return;
            }

            if (firstInstall) {
                tell(mc, "检测到整合包「" + msg.modpackName() + "」（" + msg.fileCount()
                        + " 个文件），正在开始同步…");
                startSync(mc);
            } else {
                tell(mc, "整合包「" + msg.modpackName() + "」已安装，下次启动会自动检查更新。");
            }
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 处理服务端下发的整合包信息失败", t);
        }
    }

    /**
     * 本地是否还没装过这个整合包。
     *
     * <p><b>判据是配置里有没有"同步成功过"的记录</b>（参照既有实现的
     * {@code clientConfig.installedModpacks}），也就是 {@code ServerEntry.lastSyncAt > 0}。
     *
     * <p>以前这里判的是"整合包目录里有没有清单文件"，可那个文件<b>从来没被写过</b> ——
     * 结果永远是"首次安装"，每次连服都强制重新下载，玩家永远进不去世界。
     */
    private static boolean isFirstInstall(PackPaths paths, String modpackName) {
        if (modpackName == null || modpackName.isBlank()) {
            return false;
        }
        try {
            ClientConfig cfg = ConfigIO.loadClient(paths.clientConfigFile());
            if (cfg != null && cfg.installedServers != null) {
                for (ClientConfig.ServerEntry e : cfg.installedServers.values()) {
                    if (e != null && e.lastSyncAt > 0 && modpackName.equals(e.modpackName)) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读配置失败时按"首次安装"处理：宁可多同步一遍，也别让玩家缺文件进服。
        }
        return true;
    }

    private static void startSync(Minecraft mc) {
        // 必须切回客户端主线程才能碰界面。
        mc.execute(() -> {
            try {
                if (ClientSyncTask.start()) {
                    mc.setScreen(new DownloadScreen(null));
                }
            } catch (Throwable t) {
                PackSync.LOGGER.error("[PackSync] 启动自动同步失败", t);
            }
        });
    }

    private static void tell(Minecraft mc, String text) {
        mc.execute(() -> {
            try {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[PackSync] " + text).withStyle(ChatFormatting.GREEN),
                            false);
                }
            } catch (Throwable ignored) {
                // 提示失败无所谓。
            }
        });
    }
}
