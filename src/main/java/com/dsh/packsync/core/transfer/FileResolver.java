package com.dsh.packsync.core.transfer;

import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.manifest.PackManifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 决定"每个文件从哪下" —— 本 mod 的**核心新增能力**。
 *
 * <p>常见做法固定为「优先公共站 CDN、失败回退服务端」。这里把它变成
 * 由 {@link DownloadMode} 控制的策略，其中 {@code SERVER_ONLY} 就是
 * <b>「直接从服务端下载」</b>：完全不查询公共站。
 *
 * <p>为什么要做成可选项而不是固定策略：
 * <ul>
 *   <li>私有/魔改整合包在公共站根本匹配不到 —— 每次同步都白等一遍 API 往返；
 *       内网部署时直连服务端也更快。</li>
 *   <li>反过来，服务端在家宽/限流环境下，把流量卸载到公共 CDN 又能救命。</li>
 * </ul>
 *
 * <p>性能上一个关键点：公共站查询是**批量**的（一次请求带上几百个哈希），
 * 而不是逐文件查询 —— 否则几百个文件的整合包会因为几百次 HTTP 往返而慢到不可用。
 */
public final class FileResolver {

    /** 尝试顺序里的特殊标记：表示走 PackSync 服务端通道。 */
    public static final String HOST = "host";

    private final List<PublicSource> sources;
    /** sha1 → 公共直链（解析结果缓存，避免重复查询）。 */
    private final Map<String, String> publicUrls = new ConcurrentHashMap<>();

    public FileResolver(List<PublicSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 便捷构造：按配置决定启用哪些公共源。 */
    public static FileResolver of(List<String> enabledSourceNames, String curseForgeApiKey) {
        List<PublicSource> list = new ArrayList<>();
        List<String> names = enabledSourceNames == null ? List.of() : enabledSourceNames;
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("modrinth")) {
                // 支持 "modrinth@https://mirror.example/v2" 形式的自定义镜像
                int at = raw.indexOf('@');
                list.add(at > 0 ? new ModrinthSource(raw.substring(at + 1)) : new ModrinthSource());
            } else if (name.startsWith("curseforge")) {
                if (curseForgeApiKey != null && !curseForgeApiKey.isBlank()) {
                    list.add(new CurseForgeSource(curseForgeApiKey));
                }
            }
        }
        return new FileResolver(list);
    }

    /** 当前启用的公共源名。 */
    public List<String> sourceNames() {
        return sources.stream().filter(PublicSource::isAvailable).map(PublicSource::name).toList();
    }

    /**
     * 预解析一批文件的公共直链（批量查询）。
     *
     * <p><b>SERVER_ONLY 模式直接返回，一次网络请求都不发</b> —— 这是该模式的价值所在。
     *
     * @param log 进度日志，可为 null
     */
    public void prefetch(Collection<PackManifest.PackFile> files, DownloadMode mode, Consumer<String> log) {
        Consumer<String> logger = log == null ? s -> { } : log;
        // 已经查过就不重复查：调用方可能先查一次用于风险确认，
        // 之后 SyncEngine 还会再调一次 —— 重复查询既慢又可能触发公共站限流。
        if (!publicUrls.isEmpty()) {
            return;
        }
        publicUrls.clear();

        if (!mode.allowsPublic()) {
            logger.accept("下载方式为「" + mode.describe() + "」，跳过公共站查询");
            return;
        }
        if (files == null || files.isEmpty()) {
            return;
        }
        List<PublicSource> usable = sources.stream().filter(PublicSource::isAvailable).toList();
        if (usable.isEmpty()) {
            logger.accept("没有可用的公共源，全部走服务端通道");
            return;
        }

        // 只有 jar 才有 murmur；非 jar 仍可用 SHA-1 查 Modrinth。
        List<String> sha1List = new ArrayList<>();
        Map<String, String> sha1ToMurmur = new HashMap<>();
        for (PackManifest.PackFile f : files) {
            if (f == null || f.sha1 == null || f.sha1.isBlank()) {
                continue;
            }
            String key = f.sha1.toLowerCase(Locale.ROOT);
            sha1List.add(key);
            if (f.murmur != null && !f.murmur.isBlank()) {
                sha1ToMurmur.put(key, f.murmur);
            }
        }

        for (PublicSource source : usable) {
            try {
                Map<String, String> found;
                if (source instanceof ModrinthSource ms) {
                    found = ms.resolveBatch(sha1List);
                } else if (source instanceof CurseForgeSource cf) {
                    found = cf.resolveBatch(sha1ToMurmur);
                } else {
                    found = new HashMap<>();
                    for (String sha1 : sha1List) {
                        List<String> urls = source.resolve(sha1, sha1ToMurmur.get(sha1));
                        if (!urls.isEmpty()) {
                            found.put(sha1, urls.get(0));
                        }
                    }
                }
                found.forEach(publicUrls::putIfAbsent);
                logger.accept("公共源 " + source.name() + " 匹配到 " + found.size() + " 个文件");
            } catch (RuntimeException e) {
                // 单个源出问题不影响其它源，也不影响最终能否同步。
                logger.accept("公共源 " + source.name() + " 查询异常：" + e);
            }
        }
        logger.accept("公共直链合计匹配 " + publicUrls.size() + " / " + sha1List.size() + " 个文件");
    }

    /**
     * 给出某个文件的**尝试顺序**。
     *
     * <p>返回值里的 {@link #HOST} 表示服务端通道，其余是直链。
     * 按顺序尝试，前面的失败了再试后面的。
     *
     * @return 尝试顺序；{@code PUBLIC_ONLY} 且匹配不到时返回空列表（视为失败，不回退）
     */
    public List<String> planFor(PackManifest.PackFile file, DownloadMode mode) {
        String sha1 = file == null || file.sha1 == null ? "" : file.sha1.toLowerCase(Locale.ROOT);
        String url = publicUrls.get(sha1);

        LinkedHashSet<String> plan = new LinkedHashSet<>();
        switch (mode) {
            case SERVER_ONLY -> plan.add(HOST);
            case PUBLIC_ONLY -> {
                if (url != null) {
                    plan.add(url);
                }
                // 匹配不到就什么都不加 —— PUBLIC_ONLY 的语义是"不回退服务端"
            }
            case SERVER_FIRST -> {
                plan.add(HOST);
                if (url != null) {
                    plan.add(url);
                }
            }
            case PUBLIC_FIRST -> {
                if (url != null) {
                    plan.add(url);
                }
                plan.add(HOST);
            }
        }
        return List.copyOf(plan);
    }

    /** 是否已为该文件找到公共直链。 */
    public boolean hasPublicUrl(String sha1) {
        return sha1 != null && publicUrls.containsKey(sha1.toLowerCase(Locale.ROOT));
    }

    public int publicMatchCount() {
        return publicUrls.size();
    }

    /** 供测试与界面展示：某个文件的公共直链。 */
    public String publicUrlOf(String sha1) {
        return sha1 == null ? null : publicUrls.get(sha1.toLowerCase(Locale.ROOT));
    }

    /** 直接注入匹配结果（离线测试用，避免真的打网络）。 */
    public void injectPublicUrl(String sha1, String url) {
        if (sha1 != null && url != null) {
            publicUrls.put(sha1.toLowerCase(Locale.ROOT), url);
        }
    }
}
