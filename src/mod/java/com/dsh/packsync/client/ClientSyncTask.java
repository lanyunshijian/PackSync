package com.dsh.packsync.client;

import com.dsh.packsync.PackSync;
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
import com.dsh.packsync.crypto.Ed25519Identity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 客户端的手动同步任务，跑在后台线程。
 *
 * <p>它只做两件事：**推进 {@link SyncProgress}** 和**调用核心同步引擎**。
 * 不接触任何 Minecraft 对象 —— 界面自己轮询进度并决定显示哪一屏。
 * 这样即使同步过程出错，也不会把渲染线程带崩。
 *
 * <p>用户可见的效果：玩家打开界面点"立即同步"，就能看到
 * 取清单 → 查直链 → 下载（带进度条/速度/ETA）→ 应用 → 提示重启 的完整过程，
 * 而不是只有日志里几行字。
 */
public final class ClientSyncTask {

    private static final SyncProgress PROGRESS = new SyncProgress();
    private static volatile Thread worker;
    /**
     * 本次同步的目标服务器（null = 从配置里挑）。
     *
     * <p>用普通静态字段而不是 ThreadLocal：{@code start()} 在客户端主线程调用，
     * {@code run()} 在工作线程执行 —— ThreadLocal 跨线程读不到。
     */
    private static volatile ClientConfig.ServerEntry pendingTarget;

    private ClientSyncTask() {
    }

    public static SyncProgress progress() {
        return PROGRESS;
    }

    public static boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    public static void cancel() {
        PROGRESS.requestCancel();
    }

    /**
     * 启动一次后台同步。
     *
     * <p>已在运行时直接返回 false（避免玩家连点造成并发同步）。
     */
    /** 对"当前选中的/唯一记录的"服务器发起同步。 */
    public static synchronized boolean start() {
        return start(null);
    }

    /**
     * 对指定服务器发起同步；{@code explicit} 为 null 时沿用配置里记录的那台。
     *
     * <p>支持显式指定是**首次装包的必要条件**：那时客户端还没连过任何服务器，
     * {@code installedServers} 是空的，只能由玩家直接给出地址。
     */
    public static synchronized boolean start(ClientConfig.ServerEntry explicit) {
        if (isRunning()) {
            // 已有同步在跑。以前这里静默返回 false，调用方什么也不做 ——
            // 表现出来就是"点了开始按钮没反应"。现在打条日志，界面也好给提示。
            PackSync.LOGGER.info("[PackSync] 已有同步正在进行，忽略本次启动请求");
            return false;
        }
        pendingTarget = explicit;
        PROGRESS.begin(SyncProgress.Stage.FETCHING_MANIFEST);
        Thread t = new Thread(ClientSyncTask::run, "PackSync-ClientSync");
        t.setDaemon(true);
        worker = t;
        t.start();
        return true;
    }

    // ── 后台流程 ──────────────────────────────────────────────────────────

    private static void run() {
        PackPaths paths = PackPaths.workingDir();
        try {
            ClientConfig config = ConfigIO.loadClient(paths.clientConfigFile());
            ClientConfig.ServerEntry explicit = pendingTarget;
            pendingTarget = null;
            ClientConfig.ServerEntry entry = explicit != null ? explicit : pickServer(config);
            if (entry == null) {
                PROGRESS.fail("还没有可同步的服务器。\n\n"
                        + "请在 PackSync 界面的地址栏里填上服务器地址（形如 play.example.com 或 "
                        + "play.example.com:25565），再点「立即同步」。\n\n"
                        + "注意：缺 mod 时 Forge 会在登录阶段直接断开连接，"
                        + "所以首次装包必须**先进界面同步、再进服务器**。");
                return;
            }
            int port = entry.port > 0 ? entry.port : entry.mcPort + 1;

            Ed25519Identity identity = loadOrCreateIdentity(paths);
            // 手动管理连接（不用 try-with-resources）：指纹不符时需要
            // 用核对后的新指纹【重建连接重试】，而 try-with-resources 的变量是 final。
            PackClient client = new PackClient(entry.resolvedHost(), port, null, identity, entry.fingerprint);
            try {

                // ── 1. 握手（指纹不符时给玩家一次重新核对的机会）──────────
                com.dsh.packsync.crypto.Handshake.ServerInfo info = null;
                for (int attempt = 0; attempt < 2 && info == null; attempt++) {
                    try {
                        info = client.handshake("packsync-client");
                    } catch (PackClient.IdentityChangedException e) {
                        // 服务端身份与本地记录不符：可能是管理员正常换过密钥，
                        // 也可能是中间人。**由玩家拿管理员给的指纹来判定** ——
                        // 直接失败会让"服务器正常换密钥"变成永久进不去。
                        if (attempt >= 1) {
                            PROGRESS.fail("服务器身份已改变，核对后仍不匹配：\n" + e.getMessage());
                            return;
                        }
                        PackSync.LOGGER.warn("[PackSync] 服务器指纹与记录不符，要求人工重新核对");
                        PROGRESS.setServerIdentity(e.actual, false);
                        PROGRESS.requestFingerprint(false);
                        if (!PROGRESS.awaitFingerprint()) {
                            PROGRESS.setStage(SyncProgress.Stage.CANCELLED);
                            return;
                        }
                        // 核对通过 → 以新指纹为准，重建连接再试一次。
                        entry.fingerprint = e.actual;
                        entry.fingerprintVerified = true;
                        ConfigIO.saveClient(paths.clientConfigFile(), config);
                        PackSync.LOGGER.info("[PackSync] 已接受新的服务器指纹，重试握手");
                        try {
                            client.close();
                        } catch (Throwable ignored) {
                            // 关不掉也无妨，下面会建新的。
                        }
                        client = new PackClient(entry.resolvedHost(), port, null, identity, entry.fingerprint);
                    } catch (PackClient.PskRequiredException e) {
                        PROGRESS.fail("服务器要求预共享密钥（PSK），请在客户端配置里填写后再试。");
                        return;
                    }
                }
                if (info == null) {
                    PROGRESS.fail("无法与服务器完成握手（指纹核对未通过）。");
                    return;
                }
                PROGRESS.setServerIdentity(info.fingerprint(), info.firstContact());

                // ── 身份指纹校验 ─────────────────────────────────────────────
                // 规则（按需求定死）：
                //   * 「只从公共站下载」豁免 —— 文件全部来自平台 CDN，不存在
                //     "服务器把假文件塞给你"的问题，没必要核对身份；
                //   * 其余下载方式（混合 / 优先服务端 / 只从服务端）只要开了
                //     verifyServerFingerprint，就【必须】通过核对才能继续同步。
                //
                // 核对方式见 FingerprintScreen：要求玩家【手动输入】管理员给的指纹，
                // 而不是点一下"我信任"。理由很简单 —— 服务器自己报来的指纹可能已被
                // 中间人替换，把它显示出来再让人点是防不住任何人的。
                DownloadMode chosenMode = config.downloadMode();
                boolean verifyWanted = config.verifyServerFingerprint
                        && chosenMode != DownloadMode.PUBLIC_ONLY;
                String known = entry.fingerprint;
                boolean hasKnown = known != null && !known.isBlank();
                String actual = info.fingerprint();

                if (verifyWanted) {
                    // ⚠️ 关键：必须同时满足"指纹一致"【且】"这个指纹是玩家核对过的"。
                    //   只看指纹字段是不够的 —— 它可能被登录期握手之类的路径顺手写入，
                    //   那种"自动信任"绝不算数，否则核对界面永远不会出现。
                    boolean knownMatches = hasKnown && entry.fingerprintVerified
                            && sameFingerprint(known, actual);
                    if (!knownMatches) {
                        // 首次连接，或记录里的指纹与本次不符 —— 都必须人工核对。
                        PackSync.LOGGER.info("[PackSync] 需要人工核对服务器指纹（{}）",
                                !hasKnown ? "本地无记录"
                                        : (!entry.fingerprintVerified ? "该指纹尚未经玩家核对"
                                                : "与记录不符"));
                        PROGRESS.requestFingerprint(false);
                        if (!PROGRESS.awaitFingerprint()) {
                            // 没通过核对就不许开始下载 —— 这正是这一步的意义。
                            PackSync.LOGGER.warn("[PackSync] 指纹核对未通过，已中止同步");
                            PROGRESS.setStage(SyncProgress.Stage.CANCELLED);
                            return;
                        }
                    }
                    if (actual != null && !actual.isBlank()) {
                        entry.fingerprint = actual;
                        entry.fingerprintVerified = true;   // 核对通过，这里才算"信任"
                        ConfigIO.saveClient(paths.clientConfigFile(), config);
                        PackSync.LOGGER.info("[PackSync] ✓ 服务器指纹核对通过：{}", actual);
                    }
                } else if (actual != null && !actual.isBlank() && !hasKnown) {
                    // 豁免核对（只从公共站）：仍把指纹记下来，方便日后切换到服务端下载时比对。
                    entry.fingerprint = actual;
                    ConfigIO.saveClient(paths.clientConfigFile(), config);
                }

                // ── 2. 取清单 ────────────────────────────────────────────
                PackManifest remote = client.fetchManifest();
                PROGRESS.setManifestInfo(remote.modpackName, remote.files().size(), remote.totalBytes());

                if (remote.packsyncVersion != null && !remote.packsyncVersion.isBlank()
                        && !remote.packsyncVersion.equals(com.dsh.packsync.core.PackSyncCore.VERSION)) {
                    PROGRESS.addChange("PackSync 版本不一致（本机 "
                            + com.dsh.packsync.core.PackSyncCore.VERSION + "，服务端 "
                            + remote.packsyncVersion + "）");
                }

                // ── 3. 比对 ──────────────────────────────────────────────
                SyncPlanner planner = new SyncPlanner(paths.root());
                SyncPlanner.Plan plan = planner.plan(remote, config.allowRemoteDeletions);
                for (PackManifest.PackFile f : plan.toDownload()) {
                    PROGRESS.addChange("+ " + f.path);
                }
                for (PackManifest.ToDelete d : plan.toDelete()) {
                    PROGRESS.addChange("- " + d.path);
                }

                if (!plan.needsAnything()) {
                    // 一个文件都不用改 —— 重启毫无意义。用"无需重启"的完成状态，
                    // 免得玩家陷入"重启→再同步→又要求重启"的死循环。
                    PROGRESS.finishNoRestart("整合包已是最新，无需下载。");
                    // ★ 这条分支同样必须写"已安装"凭据！玩家本地文件本来就齐（比如
                    //   旧版本漏写了凭据），若这里不补写，判定会一直是"首次安装"，
                    //   于是每次连服都被强制断开重下 —— 这正是"同步成功却进不去世界"。
                    LocalManifest.save(paths, remote.modpackName, remote);
                    return;
                }

                // ── 4. 查直链 ────────────────────────────────────────────
                PROGRESS.setStage(SyncProgress.Stage.FETCHING_URLS);
                if (PROGRESS.cancelRequested()) {
                    PROGRESS.setStage(SyncProgress.Stage.CANCELLED);
                    return;
                }

                // ── 4. 查直链（先查一次，用于判断哪些 jar 无法验证）──────
                FileResolver resolver = FileResolver.of(
                        config.downloadMode().allowsPublic() ? List.of("modrinth", "curseforge") : List.of(),
                        null);
                resolver.prefetch(plan.toDownload(), config.downloadMode(), null);
                // 记下公共站到底匹配到几个：一个都没有 = 多半是连不上公共站，
                // 而不是"这些文件都是私有的" —— 风险确认屏据此给不同的说明。
                PROGRESS.setPublicMatchCount(resolver.publicMatchCount());

                // ── 4.5 风险确认（选源 → 警告 → 同步）──────────────────────
                // 规则：**只有「只从公共站 + 全部匹配到」才跳过警告**，其余一律强制弹。
                //
                // 为什么混合模式即使全部匹配到也要弹：匹配到只代表公共站有这个文件，
                // 真正下载时那条 CDN 直链仍可能失败，而失败就会回退到服务端 ——
                // 也就是说混合模式下**始终存在"文件来自服务器"的可能**，
                // 那就必须让玩家知道并确认。只从公共站则不同：匹配不到会直接失败，
                // 全部匹配到的情况下每个字节都来自平台 CDN，没有需要额外告知的风险。
                java.util.List<PackManifest.PackFile> unverified = new java.util.ArrayList<>();
                DownloadMode mode = config.downloadMode();
                boolean publicOnly = mode == DownloadMode.PUBLIC_ONLY;

                for (PackManifest.PackFile f : plan.toDownload()) {
                    if (!f.isMod()) {
                        continue;
                    }
                    if (publicOnly) {
                        // 只从公共站：只关心"匹配不到"的（它们会同步失败）
                        if (!resolver.hasPublicUrl(f.sha1)) {
                            unverified.add(f);
                        }
                    } else if (mode == DownloadMode.SERVER_ONLY) {
                        // 只从服务端：全部文件都未经第三方检查
                        unverified.add(f);
                    } else {
                        // 混合：匹配不到的一定走服务端；匹配到的也可能因 CDN 失败回退。
                        if (!resolver.hasPublicUrl(f.sha1)) {
                            unverified.add(f);
                        }
                    }
                }

                // 唯一豁免：只从公共站，且全部都匹配到了。
                boolean mayComeFromServer = !publicOnly;
                boolean mustWarn = mayComeFromServer || !unverified.isEmpty();
                if (mustWarn) {
                    for (PackManifest.PackFile f : unverified) {
                        PROGRESS.addUnverifiedJar(f.path);
                    }
                    PROGRESS.requestConfirmation();
                    if (!PROGRESS.awaitConfirmation()) {
                        PROGRESS.setStage(SyncProgress.Stage.CANCELLED);
                        return;
                    }
                }

                // ── 5. 下载 ──────────────────────────────────────────────
                PROGRESS.setStage(SyncProgress.Stage.DOWNLOADING);

                try (SyncEngine engine = new SyncEngine(client, resolver, paths.root(), config.downloadMode())) {
                    SyncEngine.Result result = engine.execute(plan, new SyncEngine.Progress() {
                        @Override
                        public void onStart(int totalFiles, long totalBytes) {
                            PROGRESS.setManifestInfo(remote.modpackName, totalFiles, totalBytes);
                        }

                        @Override
                        public void onFileDone(String path, long bytes, boolean fromPublic) {
                            PROGRESS.onFileDone(path, bytes);
                        }

                        @Override
                        public void onFileFailed(String path, String reason) {
                            PROGRESS.onFileFailed(path, reason);
                        }

                        @Override
                        public boolean isCancelled() {
                            // 让下载循环能真正停下来 —— 否则"取消"按钮只改标志位，
                            // 文件还是会一个不落地全部下完。
                            return PROGRESS.cancelRequested();
                        }
                    });

                    // 未在公共站匹配到的 jar —— 风险确认屏要用它
                    if (config.downloadMode().allowsPublic()) {
                        for (PackManifest.PackFile f : plan.toDownload()) {
                            if (f.isMod() && !resolver.hasPublicUrl(f.sha1)) {
                                PROGRESS.addUnverifiedJar(f.path);
                            }
                        }
                    }

                    PROGRESS.setStage(SyncProgress.Stage.APPLYING);
                    // 玩家中途点了取消：不写"已安装"凭据、不宣称完成，直接收尾。
                    if (PROGRESS.cancelRequested()) {
                        PROGRESS.setStage(SyncProgress.Stage.CANCELLED);
                        PackSync.LOGGER.info("[PackSync] 同步已被玩家取消（已完成 {} 个文件）",
                                PROGRESS.doneFiles());
                        return;
                    }
                    if (result.hasFailures()) {
                        PROGRESS.fail("部分文件下载失败（" + result.failed() + " 个）：\n"
                                + String.join("\n", result.failedPaths())
                                + "\n\n下次同步会自动重试。");
                        return;
                    }
                    entry.lastSyncAt = System.currentTimeMillis();
                    entry.modpackName = remote.modpackName;
                    config.selectedModpack = remote.modpackName;
                    ConfigIO.saveClient(paths.clientConfigFile(), config);
                    // ★ 把远端清单落盘到 <root>/packsync/modpacks/<包名>/manifest.json。
                    //   这是"这个包我装过了"的唯一凭据 —— 登录期 hasLocalModpack() 与
                    //   启动期 isFirstInstall() 都靠它判断。以前谁都没写它，导致判定永远为
                    //   "首次安装"，于是每次连服都强制断开重下，玩家永远进不去世界。
                    LocalManifest.save(paths, remote.modpackName, remote);

                    if (result.needsRestart()) {
                        PROGRESS.finish(result.describe()
                                + "\n\n涉及 mod 变动，需要重启游戏才能生效。");
                    } else {
                        // 只动了配置之类、不影响已加载的类 —— 别拿"需要重启"吓玩家。
                        PROGRESS.finishNoRestart(result.describe());
                    }
                    PackSync.LOGGER.info("[PackSync] 同步完成：{}", result.describe());
                }
            } finally {
                // 手动关闭（原先用 try-with-resources；为了能在核对新指纹后重建连接才改成手动）。
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // 关闭失败不该影响同步结果。
                }
            }
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 同步过程异常", t);
            PROGRESS.fail("同步失败：" + t);
        } finally {
            // 这条日志是"同步到底有没有正常收尾"的唯一凭据 ——
            // 界面卡住时，先看它有没有出现、以及当时的 stage。
            PackSync.LOGGER.info("[PackSync] 同步线程结束（stage={}）", PROGRESS.stage());
            // 显式清掉引用：isRunning() 靠 isAlive() 判断，这里再确定一次，
            // 避免"线程已结束但引用还在"造成下次 start() 被误判为'已有同步在跑'。
            worker = null;
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /** 指纹比较：忽略格式差异，两边都归一化后比。 */
    static boolean sameFingerprint(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = normalizeFingerprint(a);
        String nb = normalizeFingerprint(b);
        return !na.isEmpty() && na.equals(nb);
    }

    /**
     * 从一段文本里认出指纹。
     *
     * <p><b>为什么要这么宽容</b>：玩家拿到的指纹通常是从 {@code modpack-keys/fingerprint.txt}
     * 或聊天栏复制来的，而那里面混着标题、等号、整段中文说明（甚至复制时带上乱码）。
     * 只去掉空格和连字符是远远不够的 —— 实测"全选复制"必然失败，玩家会觉得
     * "复制粘贴也能错？"。
     *
     * <p>做法：先去掉空白与常见分隔符，让 {@code 7af1 d978 …} 拼成连续串；
     * 再取出其中<b>最长的连续十六进制片段</b>。指纹是 64 位十六进制，
     * 混进来的中文和标点会自然把它切成若干段，最长的那段就是它。
     */
    public static String normalizeFingerprint(String s) {
        if (s == null) {
            return "";
        }
        String compact = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\-:]", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9a-f]+").matcher(compact);
        String best = "";
        while (m.find()) {
            if (m.group().length() > best.length()) {
                best = m.group();
            }
        }
        // 足够长才认（SHA-256 的十六进制是 64 位；放宽到 16 位以防被截断复制）
        if (best.length() >= 16) {
            return best;
        }
        // 太短说明输入里根本没有像指纹的东西 —— 返回原串，让比对如实失败。
        return compact;
    }

    private static ClientConfig.ServerEntry pickServer(ClientConfig config) {
        if (config.installedServers == null || config.installedServers.isEmpty()) {
            return null;
        }
        String selected = config.selectedModpack;
        if (selected != null && !selected.isBlank()) {
            for (ClientConfig.ServerEntry e : config.installedServers.values()) {
                if (e != null && e.isUsable() && selected.equals(e.modpackName)) {
                    return e;
                }
            }
        }
        return config.installedServers.size() == 1
                ? config.installedServers.values().iterator().next() : null;
    }

    /** 客户端长期身份：与启动期同步共用同一份，避免被服务端当成两台设备。 */
    private static Ed25519Identity loadOrCreateIdentity(PackPaths paths) throws Exception {
        Path file = paths.cacheDir().resolve("client-identity.key");
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
        Files.writeString(file, Base64.getEncoder().encodeToString(created.encode()), StandardCharsets.UTF_8);
        return created;
    }

    /** 供界面展示：本次同步的合计字节（人类可读）。 */
    public static String humanTotal() {
        return Hashing.humanSize(PROGRESS.totalBytes());
    }
}
