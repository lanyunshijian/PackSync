package com.dsh.packsync.client;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 连接服务器时**记下它**，这样后续（含下次启动）才知道该跟谁同步。
 *
 * <p>这一步是整个客户端链路的前提：没有它，{@code installedServers} 永远是空的，
 * 启动期同步与手动同步都会直接报"尚未连接过任何服务器"。
 *
 * <p>探测顺序刻意是「**MC端口 + 1 优先，再试 MC 端口**」：
 * <ul>
 *   <li>前者是分发服务的默认端口；</li>
 *   <li>后者能命中**同端口分流**的情况 —— 那时分发服务就挂在 MC 端口上。</li>
 * </ul>
 * 两个都试，玩家不需要知道自己服务器是哪种部署。
 */
public final class ServerRecorder {

    /** 记录成功前不重复探测（同一会话里可能多次触发登录事件）。 */
    private static volatile boolean recorded;

    private ServerRecorder() {
    }

    /** 客户端进入服务器后调用。单人游戏直接跳过。 */
    public static void recordCurrentServer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            ServerData server = mc.getCurrentServer();
            if (server == null || server.ip == null || server.ip.isBlank()) {
                return;
            }
            String host = stripPort(server.ip);
            // 注意：1.20.1 的 ServerData **没有独立的 port 字段**，
            // 端口是编码在 ip 字符串里的（形如 "example.com:25566"），
            // 解析不到就用 MC 默认端口兜底。
            int mcPort = parsePort(server.ip, 25565);
            if (host.isBlank() || mcPort <= 0) {
                return;
            }

            // 网络探测放后台线程：绝不能卡住登录流程。
            Thread t = new Thread(() -> probeAndRemember(host, mcPort), "PackSync-RecordServer");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            PackSync.LOGGER.debug("[PackSync] 记录服务器失败（忽略）：{}", String.valueOf(t));
        }
    }

    private static void probeAndRemember(String host, int mcPort) {
        // 先试 MC端口+1（分发服务默认端口），再试 MC端口（同端口分流部署）
        int[] candidates = {mcPort + 1, mcPort};
        for (int port : candidates) {
            PackClient.ServerInfo info = tryFetch(host, port);
            if (info == null) {
                continue;
            }
            try {
                PackPaths paths = PackPaths.workingDir();
                ClientConfig config = ConfigIO.loadClient(paths.clientConfigFile());

                ClientConfig.ServerEntry entry = config.installedServers
                        .computeIfAbsent(host + ":" + mcPort, k -> new ClientConfig.ServerEntry());
                entry.mcHost = host;
                entry.mcPort = mcPort;
                // 明确记下分发端口：下次同步不必再猜。
                entry.port = port;
                entry.modpackName = info.modpackName() == null ? "" : info.modpackName();
                entry.host = host;
                // ⚠️ 不在这里写 entry.fingerprint。以前这里"首次接触就存下来"，
                //    导致后续的指纹校验一看到"已知且一致"就直接放行，核对界面永不出现。
                //    记录指纹 = 信任该服务器，只能由同步流程在玩家核对通过后写入。
                if (config.selectedModpack == null || config.selectedModpack.isBlank()) {
                    config.selectedModpack = entry.modpackName;
                }
                ConfigIO.saveClient(paths.clientConfigFile(), config);
                recorded = true;
                PackSync.LOGGER.info("[PackSync] 已记录服务器 {}:{}（分发端口 {}，整合包 \"{}\"，{} 个文件）",
                        host, mcPort, port, entry.modpackName, info.fileCount());
                notifyPlayer(entry.modpackName, info.fileCount());
            } catch (Throwable t) {
                PackSync.LOGGER.warn("[PackSync] 写入服务器记录失败：{}", String.valueOf(t));
            }
            return;
        }
        PackSync.LOGGER.warn("[PackSync] 未能在 {}:{} 或其 +1 端口找到 PackSync 分发服务；"
                + "该服务器可能没装 PackSync，或分发端口被改过。", host, mcPort);
    }

    private static PackClient.ServerInfo tryFetch(String host, int port) {
        try {
            return PackClient.fetchInfo(host, port, 4000);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在聊天栏提示玩家下一步该做什么。
     *
     * <p>没有这条提示，玩家的体验是"装了个 mod，什么也没发生"——
     * 因为首次连接**只记录服务器**，真正的同步发生在下次启动或玩家手动触发时。
     * 与其让玩家自己摸索，不如直接告诉他。
     */
    private static void notifyPlayer(String modpackName, int fileCount) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }
            // 后台线程 → 必须切回客户端主线程才能碰聊天组件
            mc.execute(() -> {
                try {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                            "[PackSync] 已记录本服务器的整合包")
                                    .withStyle(net.minecraft.ChatFormatting.GREEN), false);
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                            "「" + modpackName + "」共 " + fileCount
                                                    + " 个文件。打开 PackSync 界面点「立即同步」即可下载。")
                                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
                } catch (Throwable ignored) {
                    // 提示失败无所谓。
                }
            });
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    /** 从 "host:port" 里取出主机部分（IPv6 形如 [::1]:25565 也要处理）。 */
    static String stripPort(String ip) {
        if (ip == null) {
            return "";
        }
        String s = ip.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) { // 只有一个冒号 → host:port
            return s.substring(0, colon);
        }
        return s;
    }

    /** 从 "host:port" 解析端口；解析不到就用兜底值。 */
    static int parsePort(String ip, int fallback) {
        if (ip == null) {
            return fallback;
        }
        String s = ip.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0 && end + 1 < s.length() && s.charAt(end + 1) == ':') {
                try {
                    return Integer.parseInt(s.substring(end + 2));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            try {
                return Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
