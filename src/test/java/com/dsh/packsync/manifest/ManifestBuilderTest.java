package com.dsh.packsync.manifest;

import com.dsh.packsync.core.config.ServerConfig;
import com.dsh.packsync.core.manifest.ManifestBuilder;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.util.PackPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清单生成验证 —— 整合包"发布"这一步的正确性。
 *
 * <p>重点覆盖两类**静默出错**的情形：路径映射错了会让客户端把文件放到错误位置；
 * 服务端专用 mod 没被排除会让玩家拿到跑不起来的 mod。
 */
class ManifestBuilderTest {

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    /** 造一个带 mods.toml 的假 mod jar；{@code side} 为 null 时不写依赖段。 */
    private static void writeModJar(Path jar, String modId, String side) throws IOException {
        StringBuilder toml = new StringBuilder();
        toml.append("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n\n");
        toml.append("[[mods]]\nmodId=\"").append(modId).append("\"\nversion=\"1.0.0\"\n");
        if (side != null) {
            toml.append("\n[[dependencies.").append(modId).append("]]\n");
            toml.append("modId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\nside=\"")
                    .append(side).append("\"\n");
        }
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zos.write(toml.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dummy.txt"));
            zos.write("payload".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static PackManifest build(Path root, ServerConfig cfg) {
        return new ManifestBuilder(PackPaths.of(root), cfg).build("1.20.1", "forge", "47.3.0", "测试服", null);
    }

    // ── host/main：无条件同步 + 路径映射 ─────────────────────────────────

    @Test
    @DisplayName("★ host/main 下的文件无条件同步，且路径相对 host/main 映射到客户端游戏目录")
    void hostMainIsMirroredToClientRoot(@TempDir Path root) throws IOException {
        Path main = root.resolve("packsync/host/main");
        write(main.resolve("mods/sodium.jar"), "fake-jar-content");
        write(main.resolve("config/foo.toml"), "cfg");
        write(main.resolve("shaderpacks/s.zip"), "shader");
        write(main.resolve("options.txt"), "opts");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();          // 只靠 host/main
        PackManifest manifest = build(root, cfg);

        List<String> paths = manifest.files().stream().map(f -> f.path).sorted().toList();
        assertEquals(List.of("/config/foo.toml", "/mods/sodium.jar",
                "/options.txt", "/shaderpacks/s.zip"), paths,
                "host/main/mods/x.jar 必须映射为客户端 /mods/x.jar");
    }

    @Test
    @DisplayName("host/main 之外的工作文件不进清单（packsync/、modpack-keys/ 自排除）")
    void ownWorkingDirsAreExcluded(@TempDir Path root) throws IOException {
        write(root.resolve("packsync/host/main/mods/keep.jar"), "keep");
        write(root.resolve("packsync/host/manifest.json"), "{}");
        write(root.resolve("packsync/cache/x.bin"), "cache");
        write(root.resolve("modpack-keys/server-identity.key"), "secret");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        PackManifest manifest = build(root, cfg);

        assertEquals(List.of("/mods/keep.jar"),
                manifest.files().stream().map(f -> f.path).toList(),
                "自己的工作目录与密钥目录绝不能被同步出去");
    }

    // ── syncedFiles ───────────────────────────────────────────────────────

    @Test
    @DisplayName("syncedFiles 按服务端根目录收集，并能与 host/main 合并去重")
    void syncedFilesCollectFromServerRoot(@TempDir Path root) throws IOException {
        write(root.resolve("mods/servermod.jar"), "from-server-mods");
        write(root.resolve("config/server.toml"), "server-cfg");
        write(root.resolve("packsync/host/main/mods/clientonly.jar"), "from-host-main");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of("/mods/*.jar", "/config/**");
        PackManifest manifest = build(root, cfg);

        List<String> paths = manifest.files().stream().map(f -> f.path).sorted().toList();
        assertEquals(List.of("/config/server.toml", "/mods/clientonly.jar", "/mods/servermod.jar"), paths);
    }

    @Test
    @DisplayName("editable 规则命中时打标（客户端只下载一次，之后不再覆盖）")
    void editableFlagIsApplied(@TempDir Path root) throws IOException {
        write(root.resolve("config/game.toml"), "a");
        write(root.resolve("mods/fixed.jar"), "b");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of("/config/**", "/mods/*.jar");
        cfg.allowEditsInFiles = List.of("/config/**");   // 只有 config 可编辑

        PackManifest manifest = build(root, cfg);
        PackManifest.PackFile config = manifest.find("/config/game.toml");
        PackManifest.PackFile mod = manifest.find("/mods/fixed.jar");

        assertNotNull(config);
        assertNotNull(mod);
        assertTrue(config.editable, "config 应标记为可编辑");
        assertFalse(mod.editable, "mod 不应标记为可编辑");
    }

    // ── 排除规则 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("自动排除空文件、隐藏文件、.tmp/.disabled/.bak")
    void unnecessaryFilesAreExcluded(@TempDir Path root) throws IOException {
        Path main = root.resolve("packsync/host/main");
        write(main.resolve("mods/ok.jar"), "ok");
        write(main.resolve("mods/empty.jar"), "");
        write(main.resolve(".hidden"), "h");
        write(main.resolve("mods/a.tmp"), "t");
        write(main.resolve("mods/b.disabled"), "d");
        write(main.resolve("mods/c.bak"), "b");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        PackManifest manifest = build(root, cfg);

        assertEquals(List.of("/mods/ok.jar"),
                manifest.files().stream().map(f -> f.path).toList());
    }

    @Test
    @DisplayName("★ 只排除显式声明 side=server 的 mod；未声明的一律保留（宁可多发不可少发）")
    void onlyExplicitServerSideModsAreExcluded(@TempDir Path root) throws IOException {
        Path main = root.resolve("packsync/host/main/mods");
        writeModJar(main.resolve("clientmod.jar"), "clientmod", "client");
        writeModJar(main.resolve("servermod.jar"), "servermod", "server");
        writeModJar(main.resolve("bothmod.jar"), "bothmod", "BOTH");
        writeModJar(main.resolve("unspecified.jar"), "unspecified", null);

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.excludeServerSideMods = true;
        PackManifest manifest = build(root, cfg);

        List<String> paths = manifest.files().stream().map(f -> f.path).sorted().toList();
        assertEquals(List.of("/mods/bothmod.jar", "/mods/clientmod.jar", "/mods/unspecified.jar"), paths,
                "只有 side=server 的应被排除；未声明侧别的必须保留");
        assertTrue(manifest.files().stream().allMatch(f -> "mod".equals(f.type)),
                "能解析出元数据的 jar 应归类为 mod");
    }

    @Test
    @DisplayName("关掉 excludeServerSideMods 后，服务端 mod 也会被发出去")
    void exclusionCanBeDisabled(@TempDir Path root) throws IOException {
        writeModJar(root.resolve("packsync/host/main/mods/servermod.jar"), "servermod", "server");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.excludeServerSideMods = false;
        PackManifest manifest = build(root, cfg);
        assertEquals(1, manifest.files().size());
    }

    @Test
    @DisplayName("★ 本 mod 自身永远不进清单（否则客户端会收到覆盖自己的清单）")
    void ownModIsNeverIncluded(@TempDir Path root) throws IOException {
        Path main = root.resolve("packsync/host/main/mods");
        writeModJar(main.resolve("packsync-1.0.0.jar"), "packsync", null);
        writeModJar(main.resolve("packsync-mod.jar"), "packsync_mod", null);
        writeModJar(main.resolve("other.jar"), "othermod", null);

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        PackManifest manifest = build(root, cfg);

        assertEquals(List.of("/mods/other.jar"),
                manifest.files().stream().map(f -> f.path).toList());
    }

    // ── 清单本身 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("清单带齐哈希与元信息；jar 有 murmur，非 jar 没有")
    void manifestCarriesHashesAndMetadata(@TempDir Path root) throws IOException {
        write(root.resolve("packsync/host/main/mods/a.jar"), "jar-bytes");
        write(root.resolve("packsync/host/main/config/b.toml"), "text");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.modpackName = "我的整合包";
        PackManifest manifest = build(root, cfg);

        assertEquals("我的整合包", manifest.modpackName);
        assertEquals("1.20.1", manifest.mcVersion);
        assertEquals("forge", manifest.loader);
        assertEquals("47.3.0", manifest.loaderVersion);
        assertEquals("测试服", manifest.serverName);
        assertTrue(manifest.generatedAt > 0);
        assertTrue(manifest.totalBytes() > 0);

        PackManifest.PackFile jar = manifest.find("/mods/a.jar");
        assertNotNull(jar);
        assertEquals(40, jar.sha1.length(), "SHA-1 应为 40 位十六进制");
        assertNotNull(jar.murmur, "jar 应计算 CurseForge murmur");

        PackManifest.PackFile cfgFile = manifest.find("/config/b.toml");
        assertNotNull(cfgFile);
        assertNull(cfgFile.murmur, "非 jar 不计算 murmur");
        assertEquals("config", cfgFile.type);
    }

    @Test
    @DisplayName("清单顺序稳定（同样内容产出同样顺序，便于比对与缓存）")
    void manifestOrderIsDeterministic(@TempDir Path root) throws IOException {
        Path main = root.resolve("packsync/host/main");
        for (String name : List.of("z.jar", "a.jar", "m.jar")) {
            write(main.resolve("mods/" + name), name);
        }
        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();

        List<String> first = build(root, cfg).files().stream().map(f -> f.path).toList();
        List<String> second = build(root, cfg).files().stream().map(f -> f.path).toList();
        assertEquals(first, second);
        assertEquals(List.of("/mods/a.jar", "/mods/m.jar", "/mods/z.jar"), first);
    }

    @Test
    @DisplayName("modpackName 留空时用服务器目录名兜底（客户端总要有稳定目录名）")
    void modpackNameFallsBackToDirName(@TempDir Path root) throws IOException {
        write(root.resolve("packsync/host/main/mods/a.jar"), "x");
        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.modpackName = "";

        PackManifest manifest = build(root, cfg);
        assertNotNull(manifest.modpackName);
        assertFalse(manifest.modpackName.isBlank());
    }

    @Test
    @DisplayName("filesToDelete 配置被转成带时间戳的删除条目")
    void deleteListIsBuilt(@TempDir Path root) {
        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.filesToDelete = new java.util.LinkedHashMap<>();
        cfg.filesToDelete.put("mods/old.jar", "abc123");
        cfg.filesToDelete.put("/config/legacy.toml", "def456");

        PackManifest manifest = build(root, cfg);
        assertEquals(2, manifest.filesToDelete().size());
        PackManifest.ToDelete first = manifest.filesToDelete().stream()
                .filter(d -> d.path.equals("/mods/old.jar")).findFirst().orElseThrow();
        assertEquals("abc123", first.sha1);
        assertFalse(first.timestamp.isBlank(), "删除条目必须带时间戳（客户端据此只处理一次）");
    }

    // ── 安全：兜底排除 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 即使 syncedFiles 配了 /config/**，本 mod 的配置与密钥也绝不分发")
    void ownSecretsAreNeverSynced(@TempDir Path root) throws IOException {
        write(root.resolve("config/packsync-server.json"), "{\"secret\":\"x\"}");
        write(root.resolve("config/othermod/key.dat"), "别人的密钥");
        write(root.resolve("modpack-keys/server-identity.key"), "本机密钥");
        write(root.resolve("config/normal.toml"), "普通配置");

        ServerConfig cfg = new ServerConfig();
        // 故意配成"会命中"的规则，验证兜底防线仍然生效
        cfg.syncedFiles = List.of("/config/**", "/modpack-keys/**");
        cfg.excludeUnnecessaryFiles = false;

        PackManifest manifest = build(root, cfg);
        List<String> paths = manifest.files().stream().map(f -> f.path).sorted().toList();

        assertFalse(paths.contains("/config/packsync-server.json"),
                "本 mod 自己的配置绝不能被分发出去");
        assertFalse(paths.stream().anyMatch(p -> p.startsWith("/modpack-keys/")),
                "密钥目录绝不能被分发出去");
        assertTrue(paths.contains("/config/normal.toml"), "普通配置文件应正常分发");
        assertTrue(paths.contains("/config/othermod/key.dat"),
                "其它 mod 的配置是否分发由管理员决定 —— 我们只兜底自己的");
    }

    @Test
    @DisplayName("★ 自身 jar 按内容特征识别并排除（外层 jar 没有 mods.toml 也能认出来）")
    void selfJarIsExcludedByContentMarker(@TempDir Path root) throws IOException {
        Path mods = root.resolve("packsync/host/main/mods");
        Files.createDirectories(mods);
        // 造一个"像我们自己"的 jar：含 bootstrap 类条目，但**没有** mods.toml
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(mods.resolve("packsync-1.0.0.jar")))) {
            zos.putNextEntry(new ZipEntry("com/dsh/packsync/bootstrap/PackSyncModLocator.class"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        write(mods.resolve("other.jar"), "not-a-real-jar");

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        PackManifest manifest = build(root, cfg);

        List<String> paths = manifest.files().stream().map(f -> f.path).toList();
        assertFalse(paths.contains("/mods/packsync-1.0.0.jar"), "自身 jar 必须被排除");
        assertTrue(paths.contains("/mods/other.jar"), "其它文件应正常分发");
    }

    @Test
    @DisplayName("默认 syncedFiles 不含 /config/**（避免把服务端密钥默认同步出去）")
    void defaultSyncedFilesDoNotIncludeConfig() {
        ServerConfig cfg = new ServerConfig();
        assertFalse(cfg.syncedFiles.stream().anyMatch(s -> s.contains("config")),
                "默认配置绝不能包含 config —— 那里常躺着密钥；实际：" + cfg.syncedFiles);
        assertTrue(cfg.syncedFiles.contains("/mods/*.jar"));
    }
}
