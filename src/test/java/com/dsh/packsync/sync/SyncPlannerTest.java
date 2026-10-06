package com.dsh.packsync.sync;

import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.sync.SyncPlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 比对逻辑验证。
 *
 * <p>这是整个同步流程里最容易"静默出错"的一环：比对少了会让玩家带着错误的 mod 进服，
 * 比对多了只是慢一点。所以每条规则都单独断言。
 */
class SyncPlannerTest {

    private static PackManifest.PackFile file(String path, String content, boolean editable) {
        return new PackManifest.PackFile(path, content.length(),
                sha1Of(content), null, path.endsWith(".jar") ? "mod" : "config", editable, false);
    }

    private static String sha1Of(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static PackManifest manifestOf(PackManifest.PackFile... files) {
        PackManifest m = new PackManifest();
        m.modpackName = "test";
        m.files = new ArrayList<>(List.of(files));
        return m;
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    // ── 基本判定 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("本地缺失 → 需要下载；哈希一致 → 跳过")
    void missingNeedsDownloadAndMatchingIsSkipped(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("mods/b.jar"), "B-CONTENT");

        PackManifest remote = manifestOf(
                file("/mods/a.jar", "A-CONTENT", false),
                file("/mods/b.jar", "B-CONTENT", false)); // 本地已有且一致

        SyncPlanner.Plan plan = new SyncPlanner(gameDir).plan(remote, true);

        assertEquals(List.of("/mods/a.jar"),
                plan.toDownload().stream().map(f -> f.path).toList());
        assertEquals(1, plan.upToDateCount());
        assertTrue(plan.needsAnything());
    }

    @Test
    @DisplayName("本地内容与服务端不符 → 重新下载（不是跳过）")
    void changedContentIsReDownloaded(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("config/foo.toml"), "玩家乱改的内容");

        PackManifest remote = manifestOf(file("/config/foo.toml", "服务端的正确内容", false));
        SyncPlanner.Plan plan = new SyncPlanner(gameDir).plan(remote, true);

        assertEquals(1, plan.toDownload().size());
        assertEquals("/config/foo.toml", plan.toDownload().get(0).path);
    }

    @Test
    @DisplayName("★ editable 文件本地已存在 → 保留玩家版本，不下载也不覆盖")
    void editableLocalFileIsPreserved(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("config/game.toml"), "玩家自己的设置");

        PackManifest remote = manifestOf(file("/config/game.toml", "服务端默认值", true));
        SyncPlanner.Plan plan = new SyncPlanner(gameDir).plan(remote, true);

        assertTrue(plan.toDownload().isEmpty(), "可编辑文件不该被下载覆盖");
        assertEquals(1, plan.keepEditable().size());
        assertEquals(0, plan.upToDateCount());
    }

    @Test
    @DisplayName("editable 但本地不存在 → 仍然要下载（首次装包要给玩家一个起点）")
    void editableMissingIsStillDownloaded(@TempDir Path gameDir) {
        PackManifest remote = manifestOf(file("/config/game.toml", "服务端默认值", true));
        SyncPlanner.Plan plan = new SyncPlanner(gameDir).plan(remote, true);

        assertEquals(1, plan.toDownload().size());
        assertTrue(plan.keepEditable().isEmpty());
    }

    // ── 删除 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 删除只作用于哈希匹配的文件：玩家自己换成别的就不删")
    void deletionOnlyRemovesExactMatch(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("mods/old.jar"), "服务端记录的内容");
        write(gameDir.resolve("mods/replaced.jar"), "玩家自己换过的内容");

        PackManifest remote = manifestOf();
        remote.filesToDelete = List.of(
                new PackManifest.ToDelete("/mods/old.jar", sha1Of("服务端记录的内容"), "1000"),
                new PackManifest.ToDelete("/mods/replaced.jar", sha1Of("服务端记录的原始内容"), "1000"));

        SyncPlanner planner = new SyncPlanner(gameDir);
        int removed = planner.applyDeletions(remote.filesToDelete());

        assertEquals(1, removed, "只有哈希匹配的那个应被删除");
        assertFalse(Files.exists(gameDir.resolve("mods/old.jar")));
        assertTrue(Files.exists(gameDir.resolve("mods/replaced.jar")),
                "玩家自己替换过的文件必须保留");
    }

    @Test
    @DisplayName("关闭 allowRemoteDeletions 时删除指令整体不执行")
    void deletionsRespectClientSwitch(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("mods/old.jar"), "x");
        PackManifest remote = manifestOf();
        remote.filesToDelete = List.of(new PackManifest.ToDelete("/mods/old.jar", sha1Of("x"), "1"));

        SyncPlanner.Plan plan = new SyncPlanner(gameDir).plan(remote, false);
        assertTrue(plan.toDelete().isEmpty(), "客户端关闭远程删除后不应产生删除项");
        assertTrue(Files.exists(gameDir.resolve("mods/old.jar")));
    }

    @Test
    @DisplayName("无 sha1 的删除条目按路径删（服务端没给哈希时的退化行为）")
    void deletionWithoutHashRemovesByPath(@TempDir Path gameDir) throws IOException {
        write(gameDir.resolve("mods/x.jar"), "any");
        int removed = new SyncPlanner(gameDir)
                .applyDeletions(List.of(new PackManifest.ToDelete("/mods/x.jar", "", "1")));
        assertEquals(1, removed);
        assertFalse(Files.exists(gameDir.resolve("mods/x.jar")));
    }

    // ── 安全 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 清单路径越界（../）会被拒绝，不会写出游戏目录")
    void pathTraversalIsRejected(@TempDir Path gameDir) {
        SyncPlanner planner = new SyncPlanner(gameDir);
        assertThrows(IllegalArgumentException.class, () -> planner.localPath("/../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> planner.localPath("/mods/../../outside.txt"));

        // 正常路径不受影响
        assertTrue(planner.localPath("/mods/a.jar").startsWith(gameDir));
    }

    // ── 重启判定 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("涉及 mod 变动时需要重启；只改配置不需要")
    void restartRequirementReflectsModChanges(@TempDir Path gameDir) {
        SyncPlanner planner = new SyncPlanner(gameDir);

        // 只有 config → 不需要重启
        SyncPlanner.Plan configOnly = planner.plan(
                manifestOf(file("/config/a.toml", "x", false)), true);
        assertFalse(configOnly.requiresRestart(), "只改配置不该要求重启");

        // 有 mod → 需要重启
        SyncPlanner.Plan withMod = planner.plan(manifestOf(file("/mods/a.jar", "y", false)), true);
        assertTrue(withMod.requiresRestart(), "mod 变动必须重启才能生效");
    }

    @Test
    @DisplayName("计划描述可读（界面与日志直接用）")
    void planDescriptionIsHumanReadable(@TempDir Path gameDir) {
        SyncPlanner.Plan plan = new SyncPlanner(gameDir)
                .plan(manifestOf(file("/mods/a.jar", "content", false)), true);
        String desc = plan.describe();
        assertTrue(desc.contains("需下载"));
        assertTrue(desc.contains("1"));
    }
}
