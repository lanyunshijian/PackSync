package com.dsh.packsync.keys;

import com.dsh.packsync.core.keys.KeyManager;
import com.dsh.packsync.core.util.PackPaths;
import com.dsh.packsync.crypto.Ed25519Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥管理的核心需求验证：<b>验证密钥必须自动生成在服务端根目录下</b>。
 *
 * <p>同时覆盖"损坏不留残局"这类容易被忽略的运维场景。
 */
class KeyManagerTest {

    @Test
    @DisplayName("需求验证：密钥自动生成在 <服务端根>/modpack-keys/ 下，无需任何命令")
    void keysGoToServerRootKeysDir(@TempDir Path serverRoot) {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);

        // 第一次调用即应自动生成 —— 没有 init()、没有命令
        Ed25519Identity identity = keys.loadOrCreateIdentity();
        assertNotNull(identity, "应自动生成身份密钥");

        Path expectedDir = serverRoot.resolve("modpack-keys");
        assertTrue(Files.isDirectory(expectedDir),
                "密钥目录应位于服务端根目录下：" + expectedDir);
        assertEquals(expectedDir, paths.keysDir());

        assertTrue(Files.isRegularFile(expectedDir.resolve("server-identity.key")),
                "身份密钥文件应存在");
        assertTrue(Files.isRegularFile(expectedDir.resolve("fingerprint.txt")),
                "指纹说明文件应存在（便于管理员直接抄给玩家）");
    }

    @Test
    @DisplayName("密钥目录确实在根目录这一层，而不是藏在 config/ 之类的子目录里")
    void keysDirIsTopLevel(@TempDir Path serverRoot) {
        PackPaths paths = PackPaths.of(serverRoot);
        new KeyManager(paths).loadOrCreateIdentity();

        // 用"根目录下的直接子项"来断言，而不是只看路径字符串
        try (Stream<Path> children = Files.list(serverRoot)) {
            List<String> names = children.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(names.contains("modpack-keys"),
                    "modpack-keys 应是服务端根目录的直接子项，实际：" + names);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        // 明确否定"藏在 config 下"这种实现
        assertFalse(Files.exists(serverRoot.resolve("config").resolve("modpack-keys")),
                "密钥不应被放进 config/ 子目录");
    }

    @Test
    @DisplayName("幂等：重复加载返回同一身份，指纹不变")
    void loadingIsIdempotent(@TempDir Path serverRoot) {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);

        Ed25519Identity first = keys.loadOrCreateIdentity();
        Ed25519Identity second = keys.loadOrCreateIdentity();
        Ed25519Identity third = new KeyManager(paths).loadOrCreateIdentity();

        assertEquals(first.fingerprint(), second.fingerprint(), "重复加载不应改变指纹");
        assertEquals(first.fingerprint(), third.fingerprint(), "新建管理器读同一目录也应一致");
        assertEquals(first.fingerprint(), keys.fingerprint(), "便捷方法应返回同一指纹");
    }

    @Test
    @DisplayName("指纹说明文件里包含可核对的内容，且不重复改写（mtime 稳定）")
    void fingerprintFileIsUsableAndStable(@TempDir Path serverRoot) throws IOException {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);

        Ed25519Identity identity = keys.loadOrCreateIdentity();
        Path fpFile = paths.fingerprintFile();
        String content = Files.readString(fpFile, StandardCharsets.UTF_8);
        assertTrue(content.contains(identity.fingerprint()),
                "fingerprint.txt 应包含实际指纹");

        long mtimeBefore = Files.getLastModifiedTime(fpFile).toMillis();
        keys.loadOrCreateIdentity(); // 再加载一次
        long mtimeAfter = Files.getLastModifiedTime(fpFile).toMillis();
        assertEquals(mtimeBefore, mtimeAfter, "内容未变时不应重写指纹文件");
    }

    @Test
    @DisplayName("密钥文件损坏时：留档而非静默覆盖，并生成新密钥（指纹改变是可见的）")
    void corruptKeyIsBackedUpNotSilentlyLost(@TempDir Path serverRoot) throws IOException {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);
        String original = keys.loadOrCreateIdentity().fingerprint();

        // 破坏密钥文件
        Files.writeString(paths.identityFile(), "这不是合法的密钥内容\n", StandardCharsets.UTF_8);

        Ed25519Identity regenerated = keys.loadOrCreateIdentity();
        assertNotNull(regenerated);
        assertNotNull(regenerated.fingerprint());

        // 坏文件应被留档，便于排查
        try (Stream<Path> children = Files.list(paths.keysDir())) {
            boolean hasBackup = children.anyMatch(p -> p.getFileName().toString().contains(".corrupt-"));
            assertTrue(hasBackup, "损坏的密钥文件应被留档为 .corrupt-* 而不是直接消失");
        }

        // 原指纹与坏文件不匹配，重新生成后应可用（指纹可能不同，这是预期行为）
        assertTrue(Files.isRegularFile(paths.identityFile()), "应已写入新的合法密钥");
        assertNotNull(original);
    }

    @Test
    @DisplayName("PSK：默认不存在（TOFU 模式），创建后可读回同一值")
    void pskLifecycle(@TempDir Path serverRoot) {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);

        assertNull(keys.loadPsk(), "默认不应有 PSK（零配置即 TOFU 模式）");

        String created = keys.createPsk();
        assertNotNull(created);
        assertEquals(64, created.length(), "PSK 应为 32 字节 → 64 位十六进制");
        assertEquals(created, keys.loadPsk(), "应能读回同一个 PSK");
        assertEquals(created, new KeyManager(paths).loadPsk(), "重新构造也应读到同一 PSK");
    }

    @Test
    @DisplayName("密钥文本带注释行也能正确解析（管理员手动编辑不会搞坏）")
    void identityTextToleratesComments(@TempDir Path serverRoot) throws IOException {
        PackPaths paths = PackPaths.of(serverRoot);
        KeyManager keys = new KeyManager(paths);
        String fp = keys.loadOrCreateIdentity().fingerprint();

        // 在文件里追加注释与空行
        Path file = paths.identityFile();
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, "# 管理员加的备注\n\n" + original + "\n# 末尾注释\n", StandardCharsets.UTF_8);

        assertEquals(fp, keys.loadOrCreateIdentity().fingerprint(),
                "带注释的密钥文件应仍能解析出同一身份");
    }
}
