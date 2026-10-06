package com.dsh.packsync;

import com.dsh.packsync.core.PackSyncCore;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.config.ServerConfig;
import com.dsh.packsync.core.keys.KeyManager;
import com.dsh.packsync.core.manifest.ManifestBuilder;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.transfer.PackServer;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端托管的**唯一状态持有者**。
 *
 * <p>把生命周期从事件处理器里抽出来，是为了让 /packsync 命令能直接操作它
 * （启动/停止/重启/重载/重新生成），而不必绕一圈去碰 Forge 事件。
 *
 * <p>所有公开方法都**保证不抛异常** —— 管理员敲一条命令不该把服务器搞崩。
 * 失败信息通过返回值回给命令层展示。
 */
public final class PackSyncHost {

    private static PackServer server;
    private static PackPaths paths;
    private static ServerConfig config;
    private static KeyManager keys;
    private static PackManifest lastManifest;

    private PackSyncHost() {
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /** 服务器启动完成时调用：读取配置、生成清单、启动分发服务。 */
    public static synchronized String start() {
        try {
            paths = PackPaths.of(FMLPaths.GAMEDIR.get());
            config = ConfigIO.loadServer(paths.serverConfigFile());
            // 首次运行落盘默认配置，让管理员能看到所有可调项。
            ConfigIO.saveServer(paths.serverConfigFile(), config);
            keys = new KeyManager(paths);

            // 触发密钥生成（需求 b：自动生成在 <服务端根>/modpack-keys/）。
            String fingerprint = keys.fingerprint();
            PackSync.LOGGER.info("[PackSync] 验证密钥目录：{}", paths.keysDir());
            PackSync.LOGGER.info("[PackSync] 服务器指纹：{}", fingerprint);

            if (!config.modpackHost) {
                return "modpackHost = false，未启动分发服务";
            }

            PackManifest manifest = buildManifest();
            if (manifest == null) {
                return "清单生成失败，未启动分发服务";
            }

            server = new PackServer(keys);
            server.setServerName(resolveServerName());
            // 把自己的 jar 作为自我更新源：客户端版本不一致时可从这里取到一致版本。
            server.setSelfJar(locateOwnJar());
            server.setAuthRequired(config.validateSecrets);

            int desired = resolvePort();
            int actual = server.start(desired);
            if (actual <= 0) {
                server = null;
                return "分发服务启动失败（端口 " + desired + " 可能被占用，可改配置 bindPort/reconcilePort）";
            }
            publishTo(server, manifest);

            PackSync.LOGGER.info("[PackSync] 分发服务就绪：端口 {}，共 {} 个文件",
                    actual, manifest.files().size());
            PackSync.LOGGER.info("[PackSync] 把指纹发给玩家即可完成首次核对：{}", fingerprint);
            return "分发服务已启动，端口 " + actual + "，共 " + manifest.files().size() + " 个文件";
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 启动托管失败", t);
            return "启动托管失败：" + t;
        }
    }

    /** 服务器停止时调用。 */
    public static synchronized String stop() {
        try {
            if (server == null) {
                return "分发服务本来就未运行";
            }
            server.stop();
            server = null;
            return "分发服务已停止";
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 停止托管失败", t);
            return "停止失败：" + t;
        }
    }

    public static synchronized String restart() {
        stop();
        return start();
    }

    /** 配置热重载 + 重新生成清单 + 重启服务（端口可能变了）。 */
    public static synchronized String reload() {
        try {
            if (paths == null) {
                paths = PackPaths.of(FMLPaths.GAMEDIR.get());
            }
            ServerConfig fresh = ConfigIO.loadServer(paths.serverConfigFile());
            config = fresh.normalize();
            PackSync.LOGGER.info("[PackSync] 配置已重新载入：{}", paths.serverConfigFile());
            // 配置里的端口/开关都可能变，最稳妥的做法是整体重启一次。
            return "配置已重载；" + restart();
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 重载配置失败", t);
            return "重载失败：" + t;
        }
    }

    /** 只重新生成清单并推给正在运行的服务（不重启、不断开玩家）。 */
    public static synchronized String regenerate() {
        try {
            if (paths == null) {
                paths = PackPaths.of(FMLPaths.GAMEDIR.get());
                config = ConfigIO.loadServer(paths.serverConfigFile());
            }
            PackManifest manifest = buildManifest();
            if (manifest == null) {
                return "清单生成失败，详见日志";
            }
            if (server != null) {
                publishTo(server, manifest);
                return "清单已重新生成并生效：共 " + manifest.files().size() + " 个文件（"
                        + Hashing.humanSize(manifest.totalBytes()) + "）";
            }
            return "清单已重新生成（分发服务未运行）：共 " + manifest.files().size() + " 个文件";
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 重新生成清单失败", t);
            return "生成失败：" + t;
        }
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    public static synchronized String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("托管状态：").append(server != null && server.isRunning() ? "运行中" : "未运行");
        if (server != null && server.isRunning()) {
            sb.append("\n端口：").append(server.port());
            sb.append("\n已发布文件：").append(server.hostedFileCount());
            sb.append("\n活跃会话：").append(server.activeSessions())
                    .append("（握手中 ").append(server.pendingHandshakes()).append("）");
        }
        if (lastManifest != null) {
            sb.append("\n清单：").append(lastManifest.files().size()).append(" 个文件，")
                    .append(Hashing.humanSize(lastManifest.totalBytes()));
            sb.append("\n整合包名：").append(lastManifest.modpackName);
        }
        if (paths != null) {
            sb.append("\n密钥目录：").append(paths.keysDir());
        }
        return sb.toString();
    }

    public static synchronized String fingerprint() {
        try {
            if (keys == null) {
                keys = new KeyManager(PackPaths.of(FMLPaths.GAMEDIR.get()));
            }
            return keys.fingerprint();
        } catch (Throwable t) {
            return "无法读取指纹：" + t;
        }
    }

    public static synchronized String keysDirectory() {
        if (paths == null) {
            paths = PackPaths.of(FMLPaths.GAMEDIR.get());
        }
        return paths.keysDir().toString();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 展示给玩家的服务器名。
     *
     * <p>取配置里的 {@code serverName}；读不到时退回默认的「服务器」——
     * 绝不要退成服务端目录名，那通常是"新建文件夹 (2)"这种毫无意义的东西。
     */
    private static String resolveServerName() {
        if (config != null && config.serverName != null && !config.serverName.isBlank()) {
            return config.serverName;
        }
        return "服务器";
    }

    private static PackManifest buildManifest() {
        if (config == null) {
            return null;
        }
        if (paths == null) {
            paths = PackPaths.of(FMLPaths.GAMEDIR.get());
        }
        PackManifest manifest;
        if (config.generateOnStart) {
            manifest = new ManifestBuilder(paths, config).build(
                    mcVersion(), loaderName(), loaderVersion(), resolveServerName(), PackSync.LOGGER::info);
            ConfigIO.write(paths.manifestFile(), manifest);
        } else {
            manifest = ConfigIO.read(paths.manifestFile(), PackManifest.class);
            if (manifest == null) {
                PackSync.LOGGER.warn("[PackSync] 未找到已有清单且 generateOnStart = false，改为立即生成");
                manifest = new ManifestBuilder(paths, config).build(
                        mcVersion(), loaderName(), loaderVersion(), resolveServerName(), PackSync.LOGGER::info);
                ConfigIO.write(paths.manifestFile(), manifest);
            }
        }
        if (manifest.files().isEmpty()) {
            PackSync.LOGGER.warn("[PackSync] 清单为空 —— 请把要分发的文件放进 {}", paths.hostMainDir());
        }
        lastManifest = manifest;
        return manifest;
    }

    private static void publishTo(PackServer s, PackManifest manifest) {
        s.publish(manifest, paths.hostMainDir());
        // syncedFiles 命中的文件可能在服务端根目录（而非 host/main），逐个补登记。
        for (PackManifest.PackFile f : manifest.files()) {
            if (f.path == null) {
                continue;
            }
            String rel = f.path.startsWith("/") ? f.path.substring(1) : f.path;
            Path candidate = paths.root().resolve(rel);
            if (Files.isRegularFile(candidate)) {
                s.registerFile(f.path, candidate);
            }
        }
        // 注意：publish() 里那句"可分发 N 个"是在上面这轮补登记【之前】打印的，
        // 当 host/main 不存在时它会显示"可分发 0 个"，极易被误读成"一个都发不出去"。
        // 这里补一条补登记【之后】的真实统计，省得下次又照着误导性日志白查半天。
        int hosted = s.hostedFileCount();
        int total = manifest.files().size();
        if (hosted < total) {
            PackSync.LOGGER.warn("[PackSync] 文件索引就绪：可分发 {}/{} 个 —— 有 {} 个在磁盘上没找到，"
                            + "请检查 syncedFiles 规则与文件是否已被移动",
                    hosted, total, total - hosted);
        } else {
            PackSync.LOGGER.info("[PackSync] 文件索引就绪：{} 个文件全部可分发", hosted);
        }
    }

    /**
     * 分发服务端口。
     *
     * <p>默认用 {@code MC端口 + 1}：预连接对账必须在**进服之前**可用，
     * 不能与 MC 抢同一个端口。
     *
     * <p><b>注意</b>：ModSync 也默认用 {@code MC端口 + 1}，两个 mod 同时装会端口冲突。
     * 需要给其中一个配置 {@code reconcilePort}。
     */
    private static int resolvePort() {
        if (config.bindPort > 0) {
            return config.bindPort;
        }
        if (config.reconcilePort > 0) {
            return config.reconcilePort;
        }
        return mcPort() + 1;
    }

    private static int mcPort() {
        try {
            var s = ServerLifecycleHooks.getCurrentServer();
            if (s != null) {
                return s.getPort();
            }
        } catch (Throwable ignored) {
            // 退化到默认端口。
        }
        return 25565;
    }

    private static String mcVersion() {
        try {
            return FMLLoader.versionInfo().mcVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String loaderVersion() {
        try {
            return FMLLoader.versionInfo().forgeVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String loaderName() {
        return "forge";
    }

    /**
     * 定位本 mod 的**外层 jar**（mods/packsync-x.y.z.jar）。
     *
     * <p>内层 jar 是被解压到临时目录的，不能作为自我更新源 ——
     * 客户端需要的是能直接放进 mods/ 的完整文件，也就是外层这个。
     * 用"按内容匹配"而不是按文件名：文件名可能被玩家改过。
     */
    private static Path locateOwnJar() {
        try {
            Path cwd = FMLPaths.GAMEDIR.get();
            Path modsDir = cwd.resolve("mods");
            if (!Files.isDirectory(modsDir)) {
                return null;
            }
            final String marker = "com/dsh/packsync/bootstrap/PackSyncModLocator.class";
            try (var stream = Files.list(modsDir)) {
                for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                        if (zip.getEntry(marker) != null) {
                            return jar;
                        }
                    } catch (java.io.IOException ignored) {
                        // 非法 zip，跳过。
                    }
                }
            }
        } catch (Throwable t) {
            PackSync.LOGGER.debug("[PackSync] 定位自身 jar 失败（自我更新将不可用）：{}", String.valueOf(t));
        }
        return null;
    }

    static boolean isRunning() {
        return server != null && server.isRunning();
    }

    /**
     * 打包一份"客户端该连哪里"的信息，供登录期下发给客户端。
     *
     * <p>托管未运行（或没有清单）时返回 null —— 那种情况下就算告诉客户端地址，
     * 它也连不上，不如不下发。
     *
     * @param advertisedHost 对外地址；为空表示"客户端用自己连 MC 的那个地址"
     */
    public static synchronized PackSyncPresence.ServerInfoMsg describeForClient() {
        try {
            if (server == null || !server.isRunning() || lastManifest == null) {
                return null;
            }
            String host = config == null || config.addressToSend == null || config.addressToSend.isBlank()
                    ? ""                       // 留空 = 客户端沿用自己连 MC 用的主机名
                    : config.addressToSend;
            int port = server.port();
            return new PackSyncPresence.ServerInfoMsg(
                    host,
                    port,
                    lastManifest.modpackName == null ? "" : lastManifest.modpackName,
                    lastManifest.files().size(),
                    lastManifest.totalBytes(),
                    fingerprint(),
                    // 服务端配置决定是否要求客户端核对身份指纹；配置读不到时按"要求"处理（更安全）。
                    config == null || config.enableFingerprintCheck);
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 组装下发信息失败", t);
            return null;
        }
    }

    /** 当前清单（供下发信息用）。 */
    static PackManifest currentManifest() {
        return lastManifest;
    }

    /**
     * 本机分发服务实际监听的端口；未运行时返回 -1。
     *
     * <p>同端口分流器需要它来决定"要不要接管这条连接"，
     * 以及把流量代理到哪里。
     */
    public static synchronized int localHttpPort() {
        try {
            if (server == null || !server.isRunning()) {
                return -1;
            }
            return server.port();
        } catch (Throwable t) {
            return -1;
        }
    }

    static String modId() {
        return PackSyncCore.MOD_ID_INNER;
    }
}
