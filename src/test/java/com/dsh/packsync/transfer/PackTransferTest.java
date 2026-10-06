package com.dsh.packsync.transfer;

import com.dsh.packsync.core.config.ServerConfig;
import com.dsh.packsync.core.keys.KeyManager;
import com.dsh.packsync.core.manifest.ManifestBuilder;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.transfer.PackServer;
import com.dsh.packsync.core.util.PackPaths;
import com.dsh.packsync.crypto.Ed25519Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输层端到端验证：真实启动 HTTP 服务、真实握手、真实下载。
 *
 * <p>不启动 Minecraft —— 服务端与客户端都是零 MC 依赖的核心类，
 * 因此可以在普通 JUnit 里跑完整链路。
 */
class PackTransferTest {

    private PackServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static void write(Path p, byte[] content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content);
    }

    /** 搭一个"服务端"：host/main 下若干文件 + 已发布的清单。 */
    private PackServer startServer(Path root) throws IOException {
        PackPaths paths = PackPaths.of(root);
        KeyManager keys = new KeyManager(paths);
        keys.loadOrCreateIdentity(); // 触发自动生成密钥（需求 b）

        Path main = paths.hostMainDir();
        write(main.resolve("mods/alpha.jar"), "ALPHA-CONTENT".getBytes(StandardCharsets.UTF_8));
        write(main.resolve("config/settings.toml"), "key = \"value\"".getBytes(StandardCharsets.UTF_8));

        // 造一个 ~700KB 的文件，跨多个 256KB 分块，验证分块编解码
        byte[] big = new byte[700 * 1024];
        new Random(42).nextBytes(big);
        write(main.resolve("resourcepacks/big.zip"), big);

        ServerConfig cfg = new ServerConfig();
        cfg.syncedFiles = List.of();
        cfg.modpackName = "测试包";
        PackManifest manifest = new ManifestBuilder(paths, cfg)
                .build("1.20.1", "forge", "47.3.0", "测试服", null);

        server = new PackServer(keys);
        server.setServerName("测试服");
        int port = server.start(0);
        assertTrue(port > 0, "服务应成功绑定随机端口");

        server.publish(manifest, main);
        // syncedFiles 命中的文件也可能来自服务端根目录，这里逐个补登记。
        for (PackManifest.PackFile f : manifest.files()) {
            Path real = root.resolve(f.path.startsWith("/") ? f.path.substring(1) : f.path);
            if (Files.isRegularFile(real)) {
                server.registerFile(f.path, real);
            }
        }
        return server;
    }

    private static PackClient clientFor(int port, String expectedFingerprint) {
        return new PackClient("127.0.0.1", port, null, Ed25519Identity.generate(), expectedFingerprint);
    }

    // ── 测试 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 端到端：握手 → 取清单 → 逐块下载文件，内容与磁盘完全一致")
    void endToEndHandshakeManifestAndDownload(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        int port = s.port();

        // 1) 明文 info：不握手也能知道这是哪台服务器
        PackClient.ServerInfo info = PackClient.fetchInfo("127.0.0.1", port, 5000);
        assertNotNull(info);
        assertEquals("测试服", info.serverName());
        assertEquals("1.20.1", info.mcVersion());
        assertEquals(3, info.fileCount());
        assertNotNull(info.fingerprint());
        assertFalse(info.fingerprint().isBlank());

        // 2) 握手（首次接触 = TOFU）
        try (PackClient client = clientFor(port, null)) {
            var hs = client.handshake("test-client");
            assertTrue(hs.firstContact(), "首次连接应标记为首次接触");
            assertEquals(info.fingerprint(), hs.fingerprint(), "info 与握手给出的指纹必须一致");
            assertTrue(client.isReady());

            // 3) 取清单
            PackManifest manifest = client.fetchManifest();
            assertEquals("测试包", manifest.modpackName);
            assertEquals(3, manifest.files().size());

            // 4) 逐个下载并逐字节比对
            //    注意：清单里的 path 是"客户端相对路径"，服务端原件在 host/main/ 下。
            Path hostMain = root.resolve("packsync/host/main");
            for (PackManifest.PackFile f : manifest.files()) {
                Path original = hostMain.resolve(f.path.substring(1));
                byte[] expected = Files.readAllBytes(original);

                ByteArrayOutputStream got = new ByteArrayOutputStream();
                client.downloadFile(f.sha1, got::write, null);

                assertArrayEquals(expected, got.toByteArray(),
                        "下载内容应与服务端磁盘一致：" + f.path);
            }
        }
    }

    @Test
    @DisplayName("★ 记住的指纹与服务器不符时抛 IdentityChangedException（不静默接受）")
    void identityChangeIsDetected(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        int port = s.port();

        try (PackClient client = clientFor(port, "deadbeefdeadbeef")) {
            assertThrows(PackClient.IdentityChangedException.class,
                    () -> client.handshake("test-client"),
                    "指纹不匹配必须报错，而不是照常建立会话");
        }
    }

    @Test
    @DisplayName("大文件跨多个分块也能完整还原（每块独立加密）")
    void largeFileSpansMultipleChunks(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        try (PackClient client = clientFor(s.port(), null)) {
            client.handshake("test-client");
            PackManifest manifest = client.fetchManifest();

            PackManifest.PackFile big = manifest.find("/resourcepacks/big.zip");
            assertNotNull(big);
            assertTrue(big.size > 256 * 1024, "测试文件应跨多个分块，实际 " + big.size);

            byte[] downloaded = client.downloadFile(big.sha1);
            byte[] expected = Files.readAllBytes(root.resolve("packsync/host/main/resourcepacks/big.zip"));
            assertArrayEquals(expected, downloaded);
        }
    }

    @Test
    @DisplayName("未握手就取清单会被拒绝（401），不会泄露内容")
    void manifestRequiresHandshake(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        try (PackClient client = clientFor(s.port(), null)) {
            assertThrows(IOException.class, client::fetchManifest,
                    "未握手应被拒绝");
            assertFalse(client.isReady());
        }
    }

    @Test
    @DisplayName("请求不存在的哈希返回错误而不是空文件")
    void missingFileIsRejected(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        try (PackClient client = clientFor(s.port(), null)) {
            client.handshake("test-client");
            assertThrows(IOException.class, () -> client.downloadFile("0".repeat(40)));
        }
    }

    @Test
    @DisplayName("下载进度回调按块累计到文件总大小")
    void progressReachesTotalSize(@TempDir Path root) throws IOException {
        PackServer s = startServer(root);
        try (PackClient client = clientFor(s.port(), null)) {
            client.handshake("test-client");
            PackManifest manifest = client.fetchManifest();
            PackManifest.PackFile big = manifest.find("/resourcepacks/big.zip");
            assertNotNull(big);

            long[] last = {0L};
            client.downloadFile(big.sha1, chunk -> { }, v -> last[0] = v);
            assertEquals(big.size, last[0], "进度应累计到文件实际大小");
        }
    }
}
