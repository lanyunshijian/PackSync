package com.dsh.packsync.manifest;

import com.dsh.packsync.core.manifest.GlobScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * glob 规则引擎验证。
 *
 * <p>重点是那三条"容易踩、且踩了不会报错只会静默同步错东西"的语义：
 * 目录必须写 {@code dir/**}、纯排除规则等于什么都不选、{@code **} 允许零级目录。
 */
class GlobScannerTest {

    private static void touch(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    @DisplayName("★ 目录本身不参与匹配：写 /config 匹配不到 config/ 里的文件，必须写 /config/**")
    void directoryPatternDoesNotMatchContents(@TempDir Path root) throws IOException {
        touch(root.resolve("config/a.toml"), "a");
        touch(root.resolve("config/sub/b.toml"), "b");

        // 写 /config：只当作"一个叫 config 的文件"，目录内容不会被展开
        Map<String, Path> bare = new GlobScanner(List.of("/config"), Set.of(root)).scan();
        assertTrue(bare.isEmpty(),
                "写 /config 不应展开目录内容（要整目录必须写 /config/**），实际命中：" + bare.keySet());

        // 写 /config/**：才拿到目录下全部文件
        Map<String, Path> globbed = new GlobScanner(List.of("/config/**"), Set.of(root)).scan();
        assertEquals(Set.of("/config/a.toml", "/config/sub/b.toml"), globbed.keySet());
    }

    @Test
    @DisplayName("★ 只有排除规则、没有包含规则时结果是空（不会退化成全量同步）")
    void pureExclusionSelectsNothing(@TempDir Path root) throws IOException {
        touch(root.resolve("mods/a.jar"), "a");
        touch(root.resolve("mods/b.jar"), "b");

        Map<String, Path> result = new GlobScanner(List.of("!/mods/b.jar"), Set.of(root)).scan();
        assertTrue(result.isEmpty(),
                "纯 ! 规则不构成'要同步什么'，结果必须为空而不是匹配所有");

        assertFalse(new GlobScanner(List.of("!/mods/b.jar"), Set.of(root)).hasIncludes());
    }

    @Test
    @DisplayName("★ ** 允许零级目录：mods/**/*.jar 同时匹配 mods/a.jar 与 mods/x/y.jar")
    void doubleWildcardMatchesZeroLevels(@TempDir Path root) throws IOException {
        touch(root.resolve("mods/a.jar"), "a");
        touch(root.resolve("mods/x/y.jar"), "b");
        touch(root.resolve("mods/x/y/z.jar"), "c");
        touch(root.resolve("mods/readme.txt"), "t");

        Map<String, Path> result = new GlobScanner(List.of("/mods/**/*.jar"), Set.of(root)).scan();
        assertEquals(Set.of("/mods/a.jar", "/mods/x/y.jar", "/mods/x/y/z.jar"), result.keySet());
    }

    @Test
    @DisplayName("排除优先于包含，且支持通配")
    void exclusionWins(@TempDir Path root) throws IOException {
        touch(root.resolve("kubejs/a.js"), "a");
        touch(root.resolve("kubejs/server_scripts/s.js"), "s");
        touch(root.resolve("kubejs/client_scripts/c.js"), "c");

        Map<String, Path> result = new GlobScanner(
                List.of("/kubejs/**", "!/kubejs/server_scripts/**"), Set.of(root)).scan();

        assertEquals(Set.of("/kubejs/a.js", "/kubejs/client_scripts/c.js"), result.keySet(),
                "server_scripts 应被排除，其余保留");
    }

    @Test
    @DisplayName("前导斜杠可有可无；**/** 被折叠；非法规则不影响其它规则")
    void normalizationAndInvalidRules(@TempDir Path root) throws IOException {
        touch(root.resolve("a/b/c.txt"), "x");

        // 不带前导斜杠
        assertEquals(1, new GlobScanner(List.of("a/**"), Set.of(root)).scan().size());
        // **/** 折叠
        assertEquals(1, new GlobScanner(List.of("/a/**/**"), Set.of(root)).scan().size());
        // 非法规则与合法规则混用：合法的仍生效
        Map<String, Path> mixed = new GlobScanner(List.of("[[[bad", "/a/**"), Set.of(root)).scan();
        assertEquals(1, mixed.size(), "非法 glob 应被忽略，合法规则照常生效");
    }

    @Test
    @DisplayName("matchesFormatted 对已格式化的清单路径判定 editable/forceCopy")
    void matchesFormatted(@TempDir Path root) {
        GlobScanner s = new GlobScanner(List.of("/options.txt", "/config/**"), Set.of(root));
        assertTrue(s.matchesFormatted("/options.txt"));
        assertTrue(s.matchesFormatted("/config/foo.toml"));
        assertTrue(s.matchesFormatted("config/foo.toml"), "无前导斜杠也应命中");
        assertFalse(s.matchesFormatted("/mods/a.jar"));
        assertFalse(s.matchesFormatted(""));
        assertFalse(s.matchesFormatted(null));
    }

    @Test
    @DisplayName("符号链接若指向扫描起点之外则跳过（不把外部内容同步出去）")
    void symlinkEscapingRootIsSkipped(@TempDir Path root, @TempDir Path outside) throws IOException {
        touch(outside.resolve("secret.txt"), "不该被同步出去");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // 环境不支持符号链接则跳过该用例
        }
        Map<String, Path> result = new GlobScanner(List.of("/**"), Set.of(root)).scan();
        assertFalse(result.containsKey("/link.txt"), "指向外部的符号链接不应被收集");
    }
}
