package com.dsh.packsync.transfer;

import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.transfer.FileResolver;
import com.dsh.packsync.core.transfer.PublicSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载来源策略验证 —— <b>对应新增需求 a：「直接从服务端下载」（可选择）</b>。
 *
 * <p>为什么值得单独测：这四种策略的差别不只是"顺序"，
 * 而是<b>要不要发网络请求</b>。SERVER_ONLY 若偷偷去查公共站，
 * 就完全失去了它在私有整合包/内网场景下的意义，而这种错误不会报错、只会变慢。
 */
class FileResolverTest {

    private static PackManifest.PackFile file(String path, String sha1, String murmur) {
        return new PackManifest.PackFile(path, 100L, sha1, murmur, "mod", false, false);
    }

    /** 计数用的假公共源，用来断言"到底有没有去查公共站"。 */
    private static final class CountingSource implements PublicSource {
        final AtomicInteger calls = new AtomicInteger();
        final String url;

        CountingSource(String url) {
            this.url = url;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<String> resolve(String sha1, String murmur) {
            calls.incrementAndGet();
            return url == null ? List.of() : List.of(url);
        }
    }

    // ── ★ 需求 a 的核心 ───────────────────────────────────────────────────

    @Test
    @DisplayName("★ SERVER_ONLY：计划只含服务端通道，且完全不查询公共站")
    void serverOnlyNeverTouchesPublicSources() {
        CountingSource counting = new CountingSource("https://cdn.example/foo.jar");
        FileResolver resolver = new FileResolver(List.of(counting));

        List<PackManifest.PackFile> files = List.of(
                file("/mods/a.jar", "aaa", "111"),
                file("/mods/b.jar", "bbb", "222"));

        resolver.prefetch(files, DownloadMode.SERVER_ONLY, null);

        assertEquals(0, counting.calls.get(),
                "SERVER_ONLY 必须一次公共站请求都不发（这正是该模式的意义）");
        assertEquals(0, resolver.publicMatchCount());
        assertEquals(List.of(FileResolver.HOST), resolver.planFor(files.get(0), DownloadMode.SERVER_ONLY),
                "计划应只有服务端通道");
    }

    @Test
    @DisplayName("SERVER_ONLY 下即使公共站能匹配到，也不会被采用")
    void serverOnlyIgnoresAvailablePublicUrl() {
        FileResolver resolver = new FileResolver(List.of());
        resolver.injectPublicUrl("aaa", "https://cdn.example/a.jar"); // 即便有匹配

        List<String> plan = resolver.planFor(file("/mods/a.jar", "aaa", null), DownloadMode.SERVER_ONLY);
        assertEquals(List.of(FileResolver.HOST), plan);
    }

    // ── 其余三种策略 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PUBLIC_FIRST：公共直链在前、服务端兜底（与常见行为一致，也是默认值）")
    void publicFirstPrefersPublicThenFallsBack() {
        FileResolver resolver = new FileResolver(List.of());
        String url = "https://cdn.example/a.jar";
        resolver.injectPublicUrl("aaa", url);

        List<String> plan = resolver.planFor(file("/mods/a.jar", "aaa", null), DownloadMode.PUBLIC_FIRST);
        assertEquals(List.of(url, FileResolver.HOST), plan);
    }

    @Test
    @DisplayName("PUBLIC_FIRST：匹配不到公共直链时仍然回退服务端（不会下不到东西）")
    void publicFirstFallsBackWhenUnmatched() {
        FileResolver resolver = new FileResolver(List.of());
        List<String> plan = resolver.planFor(file("/mods/x.jar", "xxx", null), DownloadMode.PUBLIC_FIRST);
        assertEquals(List.of(FileResolver.HOST), plan);
    }

    @Test
    @DisplayName("SERVER_FIRST：服务端在前、公共站兜底")
    void serverFirstPrefersHostThenPublic() {
        FileResolver resolver = new FileResolver(List.of());
        String url = "https://cdn.example/a.jar";
        resolver.injectPublicUrl("aaa", url);

        List<String> plan = resolver.planFor(file("/mods/a.jar", "aaa", null), DownloadMode.SERVER_FIRST);
        assertEquals(List.of(FileResolver.HOST, url), plan);
    }

    @Test
    @DisplayName("★ PUBLIC_ONLY：只走公共站；匹配不到就是空计划（语义上不回退服务端）")
    void publicOnlyNeverFallsBackToHost() {
        FileResolver resolver = new FileResolver(List.of());
        String url = "https://cdn.example/a.jar";
        resolver.injectPublicUrl("aaa", url);

        assertEquals(List.of(url),
                resolver.planFor(file("/mods/a.jar", "aaa", null), DownloadMode.PUBLIC_ONLY));

        List<String> unmatched = resolver.planFor(file("/mods/private.jar", "ppp", null), DownloadMode.PUBLIC_ONLY);
        assertTrue(unmatched.isEmpty(), "PUBLIC_ONLY 匹配不到时不应回退服务端（那是 PUBLIC_FIRST 的语义）");
        assertFalse(unmatched.contains(FileResolver.HOST));
    }

    // ── 批量与容错 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("批量预解析：一次处理整批文件而不是逐个查询")
    void prefetchIsBatched() {
        CountingSource counting = new CountingSource(null);
        FileResolver resolver = new FileResolver(List.of(counting));

        List<PackManifest.PackFile> files = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            files.add(file("/mods/m" + i + ".jar", "sha" + i, String.valueOf(1000 + i)));
        }
        resolver.prefetch(files, DownloadMode.PUBLIC_FIRST, null);

        // 通用 PublicSource 走的是逐条 resolve（Modrinth/CurseForge 有专用批量路径），
        // 这里断言的是"确实遍历到了全部文件"，而不是只查了第一个。
        assertEquals(50, counting.calls.get());
    }

    @Test
    @DisplayName("某个公共源抛异常不影响整体：其余源照常，最终仍能回退服务端")
    void failingSourceDoesNotBreakResolution() {
        PublicSource broken = new PublicSource() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<String> resolve(String sha1, String murmur) {
                throw new IllegalStateException("模拟网络故障");
            }
        };
        FileResolver resolver = new FileResolver(List.of(broken));
        resolver.prefetch(List.of(file("/mods/a.jar", "aaa", null)), DownloadMode.PUBLIC_FIRST, null);

        assertEquals(List.of(FileResolver.HOST),
                resolver.planFor(file("/mods/a.jar", "aaa", null), DownloadMode.PUBLIC_FIRST),
                "公共源故障时必须还能回退服务端");
    }

    @Test
    @DisplayName("不可用的源（如未配 API Key 的 CurseForge）被跳过")
    void unavailableSourceIsSkipped() {
        PublicSource unavailable = new PublicSource() {
            @Override
            public String name() {
                return "curseforge";
            }

            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public List<String> resolve(String sha1, String murmur) {
                throw new AssertionError("不可用的源不应被调用");
            }
        };
        FileResolver resolver = new FileResolver(List.of(unavailable));
        resolver.prefetch(List.of(file("/mods/a.jar", "aaa", "123")), DownloadMode.PUBLIC_FIRST, null);

        assertTrue(resolver.sourceNames().isEmpty());
        assertEquals(List.of(FileResolver.HOST),
                resolver.planFor(file("/mods/a.jar", "aaa", "123"), DownloadMode.PUBLIC_FIRST));
    }

    // ── 模式解析 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("配置里的 downloadMode 字符串宽容解析，写错不会让同步失败")
    void downloadModeParsingIsForgiving() {
        assertEquals(DownloadMode.SERVER_ONLY, DownloadMode.parse("server_only"));
        assertEquals(DownloadMode.SERVER_ONLY, DownloadMode.parse("SERVER"));
        assertEquals(DownloadMode.SERVER_ONLY, DownloadMode.parse(" host "));
        assertEquals(DownloadMode.SERVER_ONLY, DownloadMode.parse("direct"));
        assertEquals(DownloadMode.PUBLIC_ONLY, DownloadMode.parse("public"));
        assertEquals(DownloadMode.PUBLIC_ONLY, DownloadMode.parse("cdn"));
        assertEquals(DownloadMode.SERVER_FIRST, DownloadMode.parse("server-first"));
        assertEquals(DownloadMode.PUBLIC_FIRST, DownloadMode.parse("auto"));
        // 未知值/空值回落到默认（与常见行为一致）
        assertEquals(DownloadMode.PUBLIC_FIRST, DownloadMode.parse("这不是一个模式"));
        assertEquals(DownloadMode.PUBLIC_FIRST, DownloadMode.parse(""));
        assertEquals(DownloadMode.PUBLIC_FIRST, DownloadMode.parse(null));
        assertEquals(DownloadMode.DEFAULT, DownloadMode.PUBLIC_FIRST);
    }

    @Test
    @DisplayName("每种模式都有一句人话描述（界面与日志直接用）")
    void everyModeHasDescription() {
        for (DownloadMode m : DownloadMode.values()) {
            assertFalse(m.describe().isBlank(), m + " 缺少描述");
        }
        assertTrue(DownloadMode.SERVER_ONLY.describe().contains("服务端"));
        assertTrue(DownloadMode.SERVER_ONLY.allowsServer());
        assertFalse(DownloadMode.SERVER_ONLY.allowsPublic());
    }
}
