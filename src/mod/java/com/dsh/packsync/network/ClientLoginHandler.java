package com.dsh.packsync.network;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.client.ClientSyncTask;
import com.dsh.packsync.client.gui.DownloadScreen;
import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.core.util.PackPaths;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端侧的登录期处理。
 *
 * <p>收到服务端下发的整合包信息后，按顺序做四件事：
 * <ol>
 *   <li><b>记下这台服务器</b>（地址、分发端口、包名、指纹）—— 之后启动期同步靠它；</li>
 *   <li>判断本地是否已经装过这个整合包；</li>
 *   <li>回一个 ACK，让服务端放行登录；</li>
 *   <li>如果本地没有，<b>由客户端主动断开连接</b>并开始下载。</li>
 * </ol>
 *
 * <p><b>为什么是客户端主动断开</b>：服务端不知道客户端本地有什么文件。
 * 让客户端自己决定"我需要先下载，进服没有意义"，断开后再下载、重启、重连 ——
 * 这也是常见的做法。断开理由是明确写出来给玩家看的，
 * 而不是让他面对一个莫名其妙的"连接中断"。
 */
public final class ClientLoginHandler {

    private ClientLoginHandler() {
    }

    /**
     * 处理一次登录查询。
     *
     * @return 要回发给服务端的数据；<b>返回 null 表示不处理这个查询</b>
     *         （MC 会按"未理解"处理，服务端据此知道客户端没装本 mod）
     */
    public static FriendlyByteBuf handle(int transactionId, ResourceLocation identifier,
                                         FriendlyByteBuf data,
                                         ClientHandshakePacketListenerImpl handler) {
        // 只认我们自己的通道
        if (identifier == null || !"packsync".equals(identifier.getNamespace())
                || !"handshake".equals(identifier.getPath())) {
            return null;
        }
        try {
            String host = data.readUtf(255);
            int port = data.readVarInt();
            String modpackName = data.readUtf(128);
            int fileCount = data.readVarInt();
            long totalBytes = data.readVarLong();
            String fingerprint = data.readUtf(128);
            // 服务端配置的"是否启用身份密钥校验"。旧版服务端不发这个字节，读不到时按"启用"处理。
            boolean requireFingerprint = true;
            try {
                if (data.isReadable()) {
                    requireFingerprint = data.readBoolean();
                }
            } catch (Throwable ignored) {
                // 保持默认（要求校验），更安全的一侧。
            }

            PackSync.LOGGER.info("[PackSync] 登录期收到整合包信息：{}:{} / \"{}\" / {} 个文件（服务端要求指纹校验：{}）",
                    host, port, modpackName, fileCount, requireFingerprint ? "是" : "否");

            boolean firstInstall = record(handler, host, port, modpackName, fingerprint, fileCount,
                    totalBytes, requireFingerprint);

            // 先回 ACK，让服务端放行（否则它会一直钉在登录流程上等我们）
            FriendlyByteBuf ack = new FriendlyByteBuf(Unpooled.buffer());
            ack.writeUtf(com.dsh.packsync.core.PackSyncCore.VERSION, 32);

            if (firstInstall) {
                // 本地还没这个整合包 —— 进服没有意义（mod 根本不匹配），
                // 主动断开、下载、重启、再连。
                disconnectAndSync(handler, modpackName, fileCount, totalBytes, fingerprint);
            }
            return ack;
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 处理登录期整合包信息失败", t);
            // 回一个空包也算"理解"，避免把玩家卡在登录界面。
            return new FriendlyByteBuf(Unpooled.buffer());
        }
    }

    /** 记录服务器；返回 true 表示"本地还没装过这个整合包"。 */
    private static boolean record(ClientHandshakePacketListenerImpl handler,
                                  String host, int port, String modpackName,
                                  String fingerprint, int fileCount, long totalBytes,
                                  boolean requireFingerprint) {
        try {
            PackPaths paths = PackPaths.workingDir();
            ClientConfig config = ConfigIO.loadClient(paths.clientConfigFile());

            // 玩家正在连接的地址（形如 127.0.0.1 或 example.com:25566）。
            String ip = serverIpOf(handler);
            // 服务端没填对外地址时，就用玩家自己连的那个地址。
            String useHost = host;
            if (useHost == null || useHost.isBlank()) {
                useHost = com.dsh.packsync.client.gui.ServerAddressParser.host(ip);
            }
            if (useHost == null || useHost.isBlank()) {
                // 连地址都拿不到，就构造不出服务器记录，启动期也没法再同步。
                // 这里【绝不能】返回 false —— 那等于告诉调用方"本地已装好、不用下载"，
                // 可玩家实际上根本进不去（mod 不匹配），会被 Forge 踢回主菜单，
                // 而且全程只留一行容易被忽略的 warn。宁可当成首次安装，走下载流程。
                PackSync.LOGGER.error("[PackSync] 无法确定服务端地址（服务端未下发 host，"
                        + "握手里的 ServerData 也取不到）—— 本次按「需要下载」处理");
                return true;
            }

            int mcPort = com.dsh.packsync.client.gui.ServerAddressParser.port(ip, 25565);
            ClientConfig.ServerEntry entry = config.installedServers
                    .computeIfAbsent(useHost + ":" + mcPort + "@" + port,
                            k -> new ClientConfig.ServerEntry());
            entry.mcHost = useHost;
            entry.mcPort = mcPort;
            entry.host = useHost;
            entry.port = port;
            entry.modpackName = modpackName == null ? "" : modpackName;
            // 服务端这次是否要求核对指纹 —— 记在条目上，启动期同步时也用同一个判断。
            entry.requireFingerprint = requireFingerprint;
            // ⚠️ 这里【绝不】写入 entry.fingerprint。
            //    "记录指纹"等于"信任这台服务器"，那是需要玩家核对后才能做的事。
            //    以前这里见指纹为空就存下来，于是验证流程再检查时已经是"已知且一致"，
            //    直接静默放行 —— 玩家永远看不到核对界面。指纹只由 ClientSyncTask
            //    在校验通过之后写入。

            // ★ 判定顺序很关键：必须在计算 firstInstall 【之前】读 entry.lastSyncAt，
            //   因为 entry.lastSyncAt 是从磁盘加载的"上次成功同步时间"。
            boolean firstInstall = !isModpackSynced(config, modpackName, entry);
            if (firstInstall) {
                config.selectedModpack = entry.modpackName;
            }
            ConfigIO.saveClient(paths.clientConfigFile(), config);

            PackSync.LOGGER.info("[PackSync] 已记录服务器 {}:{}（分发端口 {}），整合包 \"{}\" {} 个文件 {}，首次安装={}",
                    useHost, mcPort, port, modpackName, fileCount,
                    Hashing.humanSize(totalBytes), firstInstall);
            return firstInstall;
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 记录服务器失败", t);
            return false;
        }
    }

    /**
     * 本地是否已经同步过这个整合包（= 不必再强制下载）。
     *
     * <p><b>参照既有做法：判断依据是配置里的记录，而不是
     * "某个文件存不存在"。</b>它用 {@code clientConfig.installedModpacks} 里有没有这个
     * 包名；本 mod 对应的记录就是 {@code ServerEntry.lastSyncAt}（上次成功同步的时间戳，
     * 由同步流程在成功结束时写入）。
     *
     * <p>教训：本方法以前用的是"清单文件是否存在"，而那个文件<b>从来没被写过</b> ——
     * 于是判定永远为"首次安装"，玩家每次连服都被强制断开重下，同步明明成功却永远
     * 进不去世界。自创一套机制却漏掉写入端，不如照抄一个跑通过的实现。
     *
     * <p>顺序上的坑：必须在 {@code entry.lastSyncAt} 被本次流程改写<b>之前</b>读取，
     * 所以调用点就放在 entry 字段填充完之后、任何写入之前。
     */
    private static boolean isModpackSynced(ClientConfig config, String modpackName,
                                           ClientConfig.ServerEntry current) {
        if (modpackName == null || modpackName.isBlank()) {
            return true; // 服务端没给包名，不擅自触发下载
        }
        // 本次连接的这台服务器之前同步成功过这个包 —— 最主要的判据。
        if (current != null && current.lastSyncAt > 0
                && modpackName.equals(current.modpackName)) {
            return true;
        }
        // 其它已记录服务器同步过同一个包（同一整合包多入口的情况）。
        if (config != null && config.installedServers != null) {
            for (ClientConfig.ServerEntry e : config.installedServers.values()) {
                if (e != null && e.lastSyncAt > 0 && modpackName.equals(e.modpackName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 断开当前连接并开始同步。
     *
     * <p>断开的理由要写清楚 —— 玩家看到"连接中断"会以为服务器坏了，
     * 看到"需要先下载整合包"才知道该等一会儿。
     *
     * <p><b>首次同步会先弹一屏「同步前设置」</b>，让玩家就地选下载方式和身份校验，
     * 选完点开始才真正下载。理由：真正需要做决定的时刻就是这一刻（"这台服务器要往我
     * 游戏里放 158 个文件"），而不是让玩家事先知道去按某个键翻设置。之后的连接不再弹。
     */
    private static void disconnectAndSync(ClientHandshakePacketListenerImpl handler,
                                          String modpackName, int fileCount, long totalBytes,
                                          String fingerprint) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                // 先开界面，再断开 —— 断开后玩家不会面对一个空荡荡的主菜单。
                mc.setScreen(new com.dsh.packsync.client.gui.SyncSetupScreen(
                        modpackName, fileCount, totalBytes, fingerprint,
                        () -> {
                            // 玩家点「开始同步」后才真正启动后台任务并切到进度屏。
                            // 返回 null 表示已接管；否则把失败原因交回界面显示 ——
                            // 不能让玩家点了按钮却什么都没发生。
                            if (ClientSyncTask.start()) {
                                mc.setScreen(new DownloadScreen(null));
                                return null;
                            }
                            return "上一次同步尚未结束，请稍候再试（或先取消它）。";
                        }));
                net.minecraft.network.Connection conn = handler == null ? null : connectionOf(handler);
                if (conn != null) {
                    conn.disconnect(Component.literal(
                            "[PackSync] 需要先下载本服务器的整合包\n\n"
                                    + "整合包：" + modpackName + "（" + fileCount + " 个文件）\n"
                                    + "下载完成后请重启游戏，然后重新连接。"));
                }
            } catch (Throwable t) {
                PackSync.LOGGER.error("[PackSync] 断开并开始同步时出错", t);
            }
        });
    }

    /**
     * 取登录处理器里的私有 {@code connection} 字段。
     *
     * <p>用反射而不是 Mixin {@code @Accessor}：{@code @Accessor} 字段名对不上会
     * 抛异常崩掉客户端，而登录期崩溃等于"连不上服务器"。反射拿不到只返回 null，
     * 代价仅是本次不主动断开。
     */
    private static net.minecraft.network.Connection connectionOf(ClientHandshakePacketListenerImpl handler) {
        return com.dsh.packsync.network.LoginConnectionAccess.connectionOf(handler);
    }

    /**
     * 玩家点"连接"时记下的目标地址（由 {@code ConnectScreenMixin} 在连接发起前写入）。
     *
     * <p>这是登录期<b>唯一可靠</b>的地址来源：那时 {@code Minecraft.getCurrentServer()}
     * 还没被赋值（要等进入世界），而握手实例里的 {@code ServerData} 虽然也在，
     * 但先看这里可以覆盖更多情形（例如服务端未下发对外地址）。
     */
    private static volatile String packsync$targetHost = "";
    private static volatile int packsync$targetPort = 25565;

    /**
     * 由 {@code ConnectScreenMixin} 调用：记录本次连接的目标地址。
     *
     * <p>之所以不在登录期现取，是因为那时候已经太晚 —— 详见类注释与
     * {@link #serverIpOf}。
     */
    public static void rememberServerAddress(String host, int port) {
        if (host != null && !host.isBlank()) {
            packsync$targetHost = host;
            packsync$targetPort = port > 0 ? port : 25565;
        }
    }

    /**
     * 取玩家正在连接的那台服务器的地址串（{@code host} 或 {@code host:port}）。
     *
     * <p>三个来源按可靠性排序：
     * <ol>
     *   <li>{@link #rememberServerAddress 连接前记下的目标地址} —— 最可靠；</li>
     *   <li>握手实例自带的 {@code ServerData}（连接建立时填好，按类型反射取，不看字段名）；</li>
     *   <li>{@code Minecraft.getCurrentServer()} —— 登录期基本是 null，仅作最后兜底。</li>
     * </ol>
     */
    private static String serverIpOf(ClientHandshakePacketListenerImpl handler) {
        String remembered = packsync$targetHost;
        if (remembered != null && !remembered.isBlank()) {
            return remembered + ":" + packsync$targetPort;
        }
        try {
            Object sd = LoginConnectionAccess.fieldValueByTypeName(
                    handler, "net.minecraft.client.multiplayer.ServerData");
            if (sd instanceof net.minecraft.client.multiplayer.ServerData data
                    && data.ip != null && !data.ip.isBlank()) {
                return data.ip;
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底来源
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return mc.getCurrentServer().ip;
            }
        } catch (Throwable ignored) {
            // 交给调用方处理空值
        }
        return "";
    }
}
