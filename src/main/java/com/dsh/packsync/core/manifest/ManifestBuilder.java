package com.dsh.packsync.core.manifest;

import com.dsh.packsync.core.config.ServerConfig;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.core.util.PackPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 生成整合包清单 —— 服务端"发布"整合包的那一步。
 *
 * <p>两个来源合并：
 * <ol>
 *   <li>{@code <根>/packsync/host/main/**} —— 放进这里的东西**无条件全部同步**
 *       （这是"只给客户端"的内容的指定位置）</li>
 *   <li>{@code syncedFiles} glob 规则命中的文件 —— 相对**服务端根目录**，
 *       用于把服务器已经在用的文件（如 {@code /mods/*.jar}）也发给客户端</li>
 * </ol>
 *
 * <p>两者最终都折算成**以 {@code /} 开头的客户端相对路径**，例如
 * {@code host/main/mods/sodium.jar -> /mods/sodium.jar}，
 * 客户端据此还原到自己的游戏目录。
 *
 * <p>排除规则按既有惯例实现，其中两条值得强调：
 * <ul>
 *   <li>{@code excludeServerSideMods} <b>只排除显式声明了 server 侧别的 mod</b>
 *       （依据 {@link ModInspector}）。没声明的一律保留 —— 宁可多发不可少发，
 *       少发会让玩家进不去服务器。</li>
 *   <li>本 mod 自己**永远**不进清单，否则客户端会收到一份自己把自己覆盖掉的清单。</li>
 * </ul>
 */
public final class ManifestBuilder {

    /** 本 mod 的所有 modId，永远排除。 */
    private static final Set<String> SELF_MOD_IDS = Set.of(
            "packsync", "packsync_mod", "packsync-bootstrap", "packsync_bootstrap");

    /**
     * 本 mod 自己的类路径标记。
     *
     * <p>为什么除了 modId 还要按"内容特征"识别自身：外层 jar 为了当纯 locator，
     * <b>不带 mods.toml</b>，因此 {@link ModInspector} 认不出它的 modId，
     * 光靠 modId 会让 {@code /mods/packsync-1.0.0.jar} 混进清单 ——
     * 那会让客户端下载一份"把自己覆盖掉"的整合包。
     */
    private static final String SELF_JAR_MARKER = "com/dsh/packsync/bootstrap/PackSyncModLocator.class";

    /**
     * 无论如何都不进清单的路径前缀。
     *
     * <p>这是**兜底防线**，不是唯一防线：即使管理员把 {@code /config/**}
     * 写进了 {@code syncedFiles}，本 mod 自己的配置与密钥目录也绝不能被分发出去。
     */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "/modpack-keys/",          // 服务端验证密钥（需求 b 的落点）
            "/packsync/",              // 本 mod 的工作目录（含清单、缓存）
            "/config/packsync-server.json",
            "/config/packsync-client.json"
    );

    /**
     * 可疑文件名特征。
     *
     * <p>用于**告警而非拦截**：{@code config/} 下经常躺着别的 mod 的密钥
     * （例如 {@code server-identity.dat}）。管理员一旦把 {@code /config/**}
     * 写进 {@code syncedFiles}，这些机密就会被打包发给每个客户端，
     * 而配置本身看起来完全正常、不会有任何报错。
     *
     * <p>不直接拦截的原因：同名文件可能是无害的普通配置，
     * 强行排除反而会破坏"同步配置"这个正当用法。所以选择让风险**可见**。
     */
    private static final List<String> SENSITIVE_HINTS = List.of(
            "secret", "identity", "private", ".key", "token", "password",
            "credential", "apikey", "api-key", "access_key");

    private final PackPaths paths;
    private final ServerConfig config;

    public ManifestBuilder(PackPaths paths, ServerConfig config) {
        this.paths = paths;
        this.config = config.normalize();
    }

    /**
     * 生成清单。
     *
     * @param mcVersion     MC 版本（写入清单，客户端据此校验）
     * @param loader        加载器名（forge / neoforge / fabric …）
     * @param loaderVersion 加载器版本
     * @param serverName    服务端显示名
     * @param log           进度日志回调，可为 null
     */
    public PackManifest build(String mcVersion, String loader, String loaderVersion,
                              String serverName, Consumer<String> log) {
        Consumer<String> logger = log == null ? s -> { } : log;
        PackManifest manifest = new PackManifest();
        manifest.modpackName = resolveModpackName();
        manifest.mcVersion = mcVersion == null ? "" : mcVersion;
        manifest.loader = loader == null ? "" : loader;
        manifest.loaderVersion = loaderVersion == null ? "" : loaderVersion;
        manifest.serverName = serverName == null ? "" : serverName;
        manifest.packsyncVersion = com.dsh.packsync.core.PackSyncCore.VERSION;
        manifest.generatedAt = System.currentTimeMillis();

        // 收集候选：(客户端相对路径 -> 真实文件)
        Map<String, Path> candidates = new LinkedHashMap<>();

        // ── 来源 1：host/main 下的一切（无条件） ────────────────────────
        Path hostMain = paths.hostMainDir();
        if (Files.isDirectory(hostMain)) {
            logger.accept("扫描 " + hostMain);
            collectAll(hostMain, hostMain, candidates);
        } else {
            logger.accept("提示：" + hostMain + " 不存在，本次只按 syncedFiles 收集");
        }

        // ── 来源 2：syncedFiles glob（相对服务端根目录） ─────────────────
        GlobScanner scanner = new GlobScanner(config.syncedFiles, Set.of(paths.root()));
        if (scanner.hasIncludes()) {
            logger.accept("按 syncedFiles 扫描（" + scanner.includeCount() + " 条包含规则）");
            int before = candidates.size();
            for (Map.Entry<String, Path> e : scanner.scan().entrySet()) {
                // host/main 已收集的路径优先，不覆盖。
                candidates.putIfAbsent(e.getKey(), e.getValue());
            }
            logger.accept("syncedFiles 新增 " + (candidates.size() - before) + " 个文件");
        } else {
            logger.accept("syncedFiles 没有包含规则，跳过该来源");
        }

        // ── editable / forceCopy 的判定规则 ────────────────────────────
        // 这两组规则同时以「服务端根」和「host/main」为基准扫描，
        // 因为它们要匹配的是"清单里的相对路径"，而两种来源的相对基准不同。
        Set<Path> ruleRoots = new java.util.LinkedHashSet<>();
        ruleRoots.add(paths.root());
        if (Files.isDirectory(hostMain)) {
            ruleRoots.add(hostMain);
        }
        GlobScanner editableRules = new GlobScanner(config.allowEditsInFiles, ruleRoots);
        GlobScanner forceCopyRules = new GlobScanner(config.forceCopyFiles, ruleRoots);

        // ── 逐条过滤 + 计算哈希 ────────────────────────────────────────
        List<PackManifest.PackFile> accepted = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, Path> entry : candidates.entrySet()) {
            PackManifest.PackFile file = inspect(entry.getKey(), entry.getValue(), editableRules, forceCopyRules, logger);
            if (file == null) {
                skipped++;
                continue;
            }
            accepted.add(file);
        }

        // 排序保证清单稳定（同样的内容产出同样的 JSON，便于比对与缓存）。
        accepted.sort((a, b) -> a.path.compareTo(b.path));
        manifest.files = accepted;

        // ── 删除清单 ───────────────────────────────────────────────────
        manifest.filesToDelete = buildDeleteList();

        logger.accept("清单生成完成：收录 " + accepted.size() + " 个文件，跳过 " + skipped
                + " 个，合计 " + Hashing.humanSize(manifest.totalBytes()));
        return manifest;
    }

    // ── 收集 ──────────────────────────────────────────────────────────────

    /** 递归收集目录下所有普通文件，键为相对 base 的 {@code /xxx} 路径。 */
    private void collectAll(Path dir, Path base, Map<String, Path> out) {
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(f -> {
                String key = toKey(base.relativize(f));
                out.putIfAbsent(key, f);
            });
        } catch (IOException e) {
            System.err.println("[PackSync] 扫描目录失败：" + dir + " -> " + e);
        }
    }

    // ── 单文件判定 ────────────────────────────────────────────────────────

    /**
     * 判定单个文件是否收录；不收录返回 null。
     *
     * <p>顺序刻意与常见做法一致：先做便宜的路径/名字判断，
     * 再做需要打开 jar 的 mod 元数据解析，最后才算哈希。
     */
    private PackManifest.PackFile inspect(String path, Path real,
                                          GlobScanner editableRules, GlobScanner forceCopyRules,
                                          Consumer<String> logger) {
        try {
            if (!Files.isRegularFile(real)) {
                return null;
            }
            String lower = path.toLowerCase(Locale.ROOT);

            // 兜底防线：本 mod 的密钥目录、工作目录、自己的配置绝不分发。
            for (String forbidden : FORBIDDEN_PREFIXES) {
                if (lower.startsWith(forbidden.toLowerCase(Locale.ROOT))) {
                    return null;
                }
            }

            // 自身 jar 永远不进清单。
            // 外层 jar 不带 mods.toml（纯 locator），modId 识别不出来，
            // 所以必须按"jar 内是否含我们的类"来判断。
            if (lower.endsWith(".jar") && isSelfJar(real)) {
                logger.accept("跳过本 mod 自身的 jar " + path);
                return null;
            }

            long size = Files.size(real);

            // ── 自动排除"没必要同步"的文件 ──────────────────────────────
            if (config.excludeUnnecessaryFiles) {
                if (size == 0) {
                    logger.accept("跳过空文件 " + path);
                    return null;
                }
                String name = real.getFileName().toString();
                if (name.startsWith(".")) {
                    logger.accept("跳过隐藏文件 " + path);
                    return null;
                }
                if (lower.endsWith(".tmp") || lower.endsWith(".disabled") || lower.endsWith(".bak")) {
                    logger.accept("跳过临时/禁用/备份文件 " + path);
                    return null;
                }
            }

            // ── mod 判定 ────────────────────────────────────────────────
            String type;
            ModInspector.ModInfo modInfo = null;
            if (lower.endsWith(".jar")) {
                modInfo = ModInspector.inspect(real);
            }
            if (modInfo != null) {
                type = "mod";
                if (SELF_MOD_IDS.contains(modInfo.modId().toLowerCase(Locale.ROOT))) {
                    logger.accept("跳过本 mod 自身 " + path + "（" + modInfo.modId() + "）");
                    return null;
                }
                if (config.excludeServerSideMods && modInfo.isServerOnly()) {
                    logger.accept("跳过仅服务端 mod " + path + "（" + modInfo.modId() + " 声明 side=server）");
                    return null;
                }
            } else {
                type = GlobScanner.classify(path, false);
            }

            // ── 哈希 ────────────────────────────────────────────────────
            String sha1 = Hashing.sha1(real);
            if (sha1 == null) {
                logger.accept("无法计算哈希，跳过 " + path);
                return null;
            }
            // murmur 只对 .jar 计算，且仅用于 CurseForge 反查直链。
            String murmur = lower.endsWith(".jar") ? Hashing.curseforgeMurmur(real) : null;

            boolean editable = editableRules.matchesFormatted(path);
            boolean forceCopy = forceCopyRules.matchesFormatted(path);

            // 敏感文件放行但告警：这类文件一旦被同步出去就是密钥泄露，
            // 而管理员从配置上看不出任何异常，所以必须让他在日志里看到。
            if (looksSensitive(lower)) {
                logger.accept("⚠ 警告：" + path
                        + " 看起来是密钥/凭据类文件，却匹配了 syncedFiles 并会被分发！"
                        + "如果这不是你想要的，请用 ! 规则排除它。");
            }

            return new PackManifest.PackFile(path, size, sha1, murmur, type, editable, forceCopy);
        } catch (IOException | RuntimeException e) {
            System.err.println("[PackSync] 处理文件失败，跳过 " + path + " -> " + e);
            return null;
        }
    }

    // ── 删除清单 ──────────────────────────────────────────────────────────

    /** 把配置里的 {@code filesToDelete}（路径→sha1）转成清单条目，带当前时间戳。 */
    private List<PackManifest.ToDelete> buildDeleteList() {
        List<PackManifest.ToDelete> out = new ArrayList<>();
        if (config.filesToDelete == null || config.filesToDelete.isEmpty()) {
            return out;
        }
        String ts = String.valueOf(System.currentTimeMillis());
        for (Map.Entry<String, String> e : config.filesToDelete.entrySet()) {
            String p = e.getKey();
            if (p == null || p.isBlank()) {
                continue;
            }
            String normalized = p.replace('\\', '/');
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            out.add(new PackManifest.ToDelete(normalized, e.getValue() == null ? "" : e.getValue(), ts));
        }
        return out;
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /**
     * 整合包名，按优先级回退：
     * <ol>
     *   <li>{@code modpackName} —— 管理员显式指定；</li>
     *   <li>{@code serverName} —— 退而用服务器名（比目录名体面得多）；</li>
     *   <li>服务端目录名 —— 最后兜底，保证客户端总有个稳定的目录名可用。</li>
     * </ol>
     */
    private String resolveModpackName() {
        String configured = config.modpackName;
        if (configured != null && !configured.isBlank()) {
            return PackPaths.sanitize(configured);
        }
        String server = config.serverName;
        if (server != null && !server.isBlank()) {
            return PackPaths.sanitize(server);
        }
        Path root = paths.root();
        Path name = root.getFileName();
        return PackPaths.sanitize(name == null ? "modpack" : name.toString());
    }

    static String toKey(Path relative) {
        String s = relative.toString().replace('\\', '/');
        return s.startsWith("/") ? s : "/" + s;
    }

    /** 路径是否"看起来像"密钥/凭据（仅用于告警）。 */
    private static boolean looksSensitive(String lowerPath) {
        for (String hint : SENSITIVE_HINTS) {
            if (lowerPath.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** jar 里是否含本 mod 的类（用于识别自身，不依赖 mods.toml 是否存在）。 */
    private static boolean isSelfJar(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry(SELF_JAR_MARKER) != null;
        } catch (IOException | RuntimeException e) {
            // 打不开的 jar 交给后面的 mod 判定逻辑处理。
            return false;
        }
    }

    /** 便于测试观察：本次实际参与收集的规则。 */
    public List<String> syncedRules() {
        return new GlobScanner(config.syncedFiles, Set.of(paths.root())).includeRules();
    }

    /** 便于测试观察：被排除的 modId 集合。 */
    public static Set<String> selfModIds() {
        return new TreeSet<>(SELF_MOD_IDS);
    }
}
