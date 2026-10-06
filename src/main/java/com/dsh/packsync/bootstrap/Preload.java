package com.dsh.packsync.bootstrap;

import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.manifest.LocalManifest;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.sync.SyncEngine;
import com.dsh.packsync.core.sync.SyncPlanner;
import com.dsh.packsync.core.transfer.FileResolver;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.core.util.PackPaths;
import com.dsh.packsync.crypto.Bytes;
import com.dsh.packsync.crypto.Ed25519Identity;
import com.dsh.packsync.crypto.Handshake;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;

/**
 * 启动期同步 —— 这是本模组存在的理由。
 *
 * <p>运行时机：Forge 扫描 {@code mods/} 目录时（由 {@link PackSyncModLocator} 触发），
 * <b>早于任何 mod 被加载</b>。因此这个类：
 * <ul>
 *   <li>只能依赖 JDK 与外层的 {@code core}/{@code crypto}（零 MC 依赖）；</li>
 *   <li><b>绝不能向外抛异常</b> —— 抛了游戏直接起不来。所有失败都必须降级成
 *       "这次不同步，照常启动"。</li>
 * </ul>
 *
 * <p>为什么必须在加载前做：客户端缺 mod 时 Forge 会在**登录阶段**就因 mod 通道
 * 不匹配断开，那时游戏内代码根本没机会运行。只有在扫描 mods 之前把文件补齐，
 * "缺 mod 也能连上服务器"才成立。
 *
 * <p>当前实现的行为：读取上次连接过的服务器 → 拉清单 → 比对 → 下载 →
 * 若涉及 mod 变动则写一份待重启标记（提示由游戏内部分展示）。
 */
public final class Preload {

    private static final String TAG = "[PackSync][preload] ";

    /** 同步结果，供日志与测试观察。 */
    public record Outcome(boolean attempted, boolean changed, boolean needsRestart,
                          int downloaded, int failed, String message) {

        static Outcome skipped(String why) {
            return new Outcome(false, false, false, 0, 0, why);
        }
    }

    private Preload() {
    }

    /**
     * 执行启动期同步。
     *
     * <p><b>永不抛异常</b>：任何问题都返回一个描述性的 Outcome。
     */
    public static Outcome run() {
        try {
            return runInternal();
        } catch (Throwable t) {
            // 兜底：启动期同步失败绝不能变成"游戏起不来"。
            System.err.println(TAG + "同步过程出错，本次跳过（游戏将照常启动）：" + t);
            t.printStackTrace();
            return Outcome.skipped("同步异常，已跳过：" + t);
        }
    }

    private static Outcome runInternal() throws java.io.IOException {
        PackPaths paths = PackPaths.workingDir();
        Path configFile = paths.clientConfigFile();

        // 配置不存在时不要创建 —— 玩家可能只是把 jar 放进 mods 还没配置任何服务器。
        if (!Files.isRegularFile(configFile)) {
            System.out.println(TAG + "尚无客户端配置（" + configFile + "），跳过启动期同步");
            return Outcome.skipped("尚未配置任何服务器");
        }

        ClientConfig config = ConfigIO.loadClient(configFile);
        if (!config.updateOnLaunch) {
            System.out.println(TAG + "updateOnLaunch = false，跳过启动期同步");
            return Outcome.skipped("配置已关闭启动期同步");
        }

        ClientConfig.ServerEntry entry = pickServer(config);
        if (entry == null) {
            System.out.println(TAG + "尚无已安装的服务器记录，跳过启动期同步");
            return Outcome.skipped("尚未连接过任何服务器");
        }

        int port = resolvePort(entry);
        if (port <= 0) {
            System.out.println(TAG + "无法确定服务器端口，跳过启动期同步");
            return Outcome.skipped("服务器端口未知");
        }

        System.out.println(TAG + "开始同步：" + entry.mcHost + ":" + entry.mcPort
                + "（下载方式：" + config.downloadMode().describe() + "）");

        Ed25519Identity clientIdentity = loadOrCreateClientIdentity(paths);
        byte[] psk = null; // TODO: 从配置读取 PSK（当前为 TOFU 模式）
        try (PackClient client = new PackClient(entry.mcHost, port, psk, clientIdentity, entry.fingerprint)) {

            Handshake.ServerInfo info;
            try {
                info = client.handshake("packsync-client");
            } catch (PackClient.PskRequiredException e) {
                System.out.println(TAG + "服务器要求预共享密钥，启动期无法交互，跳过");
                return Outcome.skipped("需要预共享密钥");
            } catch (PackClient.IdentityChangedException e) {
                // 身份变了：绝不自动接受，交给游戏内让玩家确认。
                System.err.println(TAG + "⚠ 服务器身份已改变，已停止自动同步：" + e.getMessage());
                return Outcome.skipped("服务器身份已改变，需要人工确认");
            }
            // 首次接触：先记下"见过这台服务器"。modpackName 要等取到清单才知道，稍后一并落盘。
            final boolean firstContact = info.firstContact();
            if (firstContact) {
                System.out.println(TAG + "首次接触该服务器，指纹：" + info.fingerprint());
            }

            // ── 指纹校验：启动期**绝不能替玩家自动信任** ────────────────────
            // 启动期发生在 mod 加载之前，没有 MC 界面可弹。以前这里对首次接触的服务器
            // 直接 rememberFingerprint() ——等于自动信任了服务器报来的指纹，而那个指纹
            // 可能已被中间人替换。玩家连"核对"的机会都没有，等于没这道防线。
            //
            // 现在改为：只要这台服务器还需要人工核对（首次 / 本地无记录），启动期就
            // 【不做同步】直接退出，把这件事交给进游戏后的登录期同步 —— 那里有
            // FingerprintScreen 能要求玩家手动输入管理员给的指纹。
            boolean verifyWanted = config.verifyServerFingerprint
                    && config.downloadMode() != DownloadMode.PUBLIC_ONLY;
            boolean hasKnownFingerprint = entry.fingerprint != null && !entry.fingerprint.isBlank();
            if (verifyWanted && (firstContact || !hasKnownFingerprint)) {
                System.out.println(TAG + "需要人工核对服务器指纹，跳过启动期自动同步。");
                System.out.println(TAG + "  请启动游戏并连接该服务器，届时会要求你输入管理员提供的指纹。");
                return Outcome.skipped("需要人工核对服务器指纹（请进游戏后连接服务器完成核对）");
            }

            PackManifest remote = client.fetchManifest();
            System.out.println(TAG + "取到清单：" + remote.files().size() + " 个文件，"
                    + Hashing.humanSize(remote.totalBytes()));

            // 走到这里说明：要么不需要校验，要么本地已有可信指纹（且上面 handshake 已比对一致）。
            if (firstContact) {
                rememberFingerprint(paths, config, entry, info.fingerprint(), remote.modpackName);
            }

            // 版本一致性：两端版本不同可能意味着协议不兼容。
            // 与其让玩家遇到"莫名其妙的握手失败"，不如主动把版本对齐。
            String serverVersion = remote.packsyncVersion;
            if (serverVersion != null && !serverVersion.isBlank()
                    && !serverVersion.equals(com.dsh.packsync.core.PackSyncCore.VERSION)) {
                System.err.println(TAG + "⚠ PackSync 版本不一致：本机 "
                        + com.dsh.packsync.core.PackSyncCore.VERSION + "，服务端 " + serverVersion);
                var selfUpdate = com.dsh.packsync.core.selfupdate.SelfUpdater.updateIfNeeded(
                        client, paths.root().resolve("mods"), serverVersion);
                System.out.println(TAG + "自我更新：" + selfUpdate.message());
            }

            SyncPlanner planner = new SyncPlanner(paths.root());
            SyncPlanner.Plan plan = planner.plan(remote, config.allowRemoteDeletions);
            System.out.println(TAG + "比对结果：" + plan.describe());

            if (!plan.needsAnything()) {
                System.out.println(TAG + "整合包已是最新，无需下载");
                markSynced(paths, config, entry, remote.modpackName, remote);
                return new Outcome(true, false, false, 0, 0, "已是最新");
            }

            DownloadMode mode = config.downloadMode();
            try (SyncEngine engine = new SyncEngine(client, buildResolver(config), paths.root(), mode)) {
                SyncEngine.Result result = engine.execute(plan, new SyncEngine.Progress() {
                    @Override
                    public void onStart(int totalFiles, long totalBytes) {
                        System.out.println(TAG + "开始下载 " + totalFiles + " 个文件，共 "
                                + Hashing.humanSize(totalBytes));
                    }

                    @Override
                    public void onFileDone(String path, long bytes, boolean fromPublic) {
                        System.out.println(TAG + "  ✓ " + path + "（来自"
                                + (fromPublic ? "公共站" : "服务端") + "）");
                    }

                    @Override
                    public void onFileFailed(String path, String reason) {
                        System.err.println(TAG + "  ✗ " + path + " -> " + reason);
                    }

                    @Override
                    public void onLog(String message) {
                        System.out.println(TAG + message);
                    }
                });

                System.out.println(TAG + "同步完成：" + result.describe());
                markSynced(paths, config, entry, remote.modpackName, remote);

                if (result.hasFailures()) {
                    // 部分失败不回滚已成功的部分：下次启动会继续补齐。
                    return new Outcome(true, result.changedAnything(), result.needsRestart(),
                            result.downloaded(), result.failed(),
                            "部分文件下载失败：" + String.join(", ", result.failedPaths()));
                }
                if (result.needsRestart()) {
                    System.out.println(TAG + "==========================================================");
                    System.out.println(TAG + "  整合包已更新，涉及 mod 变动 —— 请重启游戏以生效。");
                    System.out.println(TAG + "==========================================================");
                    // 启动期同步发生在 mod 加载**之前**：文件已经换过，但游戏进程
                    // 仍持有旧的类，不重启等于没生效。只打日志不够，必须让玩家看见。
                    boolean shouldQuit = com.dsh.packsync.core.ui.RestartPrompt.ask(result.describe());
                    if (shouldQuit) {
                        System.out.println(TAG + "玩家选择立即重启，正在退出游戏…");
                        System.exit(0);
                    }
                }
                return new Outcome(true, result.changedAnything(), result.needsRestart(),
                        result.downloaded(), 0, result.describe());
            }
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /** 选要同步的服务器：优先 selectedModpack 对应的那台，否则取唯一一台。 */
    private static ClientConfig.ServerEntry pickServer(ClientConfig config) {
        var installed = config.installedServers;
        if (installed == null || installed.isEmpty()) {
            return null;
        }
        String selected = config.selectedModpack;
        if (selected != null && !selected.isBlank()) {
            for (ClientConfig.ServerEntry e : installed.values()) {
                if (e != null && e.isUsable() && selected.equals(e.modpackName)) {
                    return e;
                }
            }
        }
        // 回退：只有一台已安装服务器时直接用它。
        return installed.size() == 1 ? installed.values().iterator().next() : null;
    }

    /** 下载服务端口：优先显式端口，其次 MC 端口 + 1（分发服务的默认约定）。 */
    private static int resolvePort(ClientConfig.ServerEntry entry) {
        if (entry.port > 0) {
            return entry.port;
        }
        if (entry.mcPort > 0) {
            return entry.mcPort + 1;
        }
        return -1;
    }

    /** 按配置构造公共源（SERVER_ONLY 时其实是空的 —— prefetch 会直接跳过）。 */
    private static FileResolver buildResolver(ClientConfig config) {
        List<String> sources = config.downloadMode().allowsPublic()
                ? List.of("modrinth", "curseforge")
                : List.of();
        return FileResolver.of(sources, null);
    }

    /** 客户端长期身份：首次使用时生成并落盘（与 modsync 的同名做法一致）。 */
    private static Ed25519Identity loadOrCreateClientIdentity(PackPaths paths) {
        Path file = paths.cacheDir().resolve("client-identity.key");
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                for (String line : text.split("\\R")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        sb.append(t);
                    }
                }
                if (sb.length() > 0) {
                    return Ed25519Identity.decode(Base64.getDecoder().decode(sb.toString()));
                }
            }
            Ed25519Identity created = Ed25519Identity.generate();
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    "# PackSync 客户端身份密钥（Ed25519）。删除后会在下次运行时重新生成；\n"
                            + "# 服务端会把它记作这台客户端，重新生成会被视为新设备。\n"
                            + Base64.getEncoder().encodeToString(created.encode()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return created;
        } catch (Exception e) {
            System.err.println(TAG + "读写客户端身份失败，本次使用临时身份：" + e);
            return Ed25519Identity.generate();
        }
    }

    /** 记住服务器指纹与整合包名，供下次连接比对身份。 */
    private static void rememberFingerprint(PackPaths paths, ClientConfig config,
                                            ClientConfig.ServerEntry entry,
                                            String fingerprint, String modpackName) {
        try {
            entry.fingerprint = fingerprint;
            // 能走到这里说明本次【不需要人工核对】（需要的话早已 return 跳过启动期同步），
            // 所以顺手标记为已核对 —— 否则下次同步又会被要求核对一遍。
            entry.fingerprintVerified = true;
            if (modpackName != null && !modpackName.isBlank()) {
                entry.modpackName = modpackName;
                config.selectedModpack = modpackName;
            }
            ConfigIO.saveClient(paths.clientConfigFile(), config);
        } catch (RuntimeException e) {
            System.err.println(TAG + "记录服务器指纹失败：" + e);
        }
    }

    private static void markSynced(PackPaths paths, ClientConfig config,
                                   ClientConfig.ServerEntry entry, String modpackName,
                                   PackManifest manifest) {
        entry.lastSyncAt = System.currentTimeMillis();
        if (modpackName != null && !modpackName.isBlank()) {
            entry.modpackName = modpackName;
            config.selectedModpack = modpackName;
        }
        ConfigIO.saveClient(paths.clientConfigFile(), config);
        // 启动期同步同样要落下"已安装"凭据：登录期靠它判断是否首次安装，
        // 漏写会让玩家每次连服都被强制断开重下（永远进不去世界）。
        LocalManifest.save(paths, modpackName, manifest);
    }

    /** 供内层游戏代码使用：读取"是否需要重启"的提示信息。 */
    public static String pendingRestartNotice(PackPaths paths) {
        Path marker = paths.cacheDir().resolve("last-sync.txt");
        try {
            return Files.isRegularFile(marker) ? Files.readString(marker, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
