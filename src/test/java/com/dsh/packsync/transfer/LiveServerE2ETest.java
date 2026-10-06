package com.dsh.packsync.transfer;

import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.util.Hashing;
import com.dsh.packsync.crypto.Ed25519Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对**真实运行中的服务端**的端到端验证（默认跳过）。
 *
 * <p>与其它测试不同，它需要一个已经启动的 PackSync 服务端，因此默认不跑：
 * <pre>
 *   ./gradlew test -Dpacksync.e2e.port=25566 -Dpacksync.e2e.host=127.0.0.1 --tests '*LiveServerE2ETest'
 * </pre>
 *
 * <p>存在的意义：单元测试里的服务端与客户端是同一个进程、同一份类，
 * 而真实环境是**两个独立进程、跨 jar 边界**（外层 locator + 内层 mod），
 * 只有真连一次才能确认握手、清单、文件流在真实部署下都对得上。
 */
@EnabledIfSystemProperty(named = "packsync.e2e.port", matches = "\\d+")
class LiveServerE2ETest {

    private static String host() {
        return System.getProperty("packsync.e2e.host", "127.0.0.1");
    }

    private static int port() {
        return Integer.parseInt(System.getProperty("packsync.e2e.port"));
    }

    @Test
    @DisplayName("★ 真实服务端：明文 info → 握手 → 取清单 → 下载并校验每个文件")
    void fullRoundTripAgainstLiveServer() throws Exception {
        // 1) 明文 info：不握手也能知道这是哪台服务器
        PackClient.ServerInfo info = PackClient.fetchInfo(host(), port(), 5000);
        assertNotNull(info, "应能取到服务器信息");
        assertNotNull(info.fingerprint());
        assertTrue(info.fileCount() > 0, "服务端应已发布至少一个文件");
        System.out.println("[E2E] 服务器：" + info.serverName()
                + "，整合包：" + info.modpackName()
                + "，文件数：" + info.fileCount()
                + "，指纹：" + info.fingerprint());

        // 2) 握手（TOFU）
        try (PackClient client = new PackClient(host(), port(), null,
                Ed25519Identity.generate(), null)) {
            var hs = client.handshake("e2e-test-client");
            assertEquals(info.fingerprint(), hs.fingerprint(), "info 与握手的指纹必须一致");
            System.out.println("[E2E] 握手成功，模式：" + hs.modeName());

            // 3) 取清单
            PackManifest manifest = client.fetchManifest();
            assertEquals(info.fileCount(), manifest.files().size(),
                    "清单文件数应与 info 一致");
            System.out.println("[E2E] 清单：" + manifest.files().size() + " 个文件，"
                    + Hashing.humanSize(manifest.totalBytes()));

            // 4) 逐个下载并校验哈希（这是真正证明"内容没被改坏"的一步）
            int verified = 0;
            for (PackManifest.PackFile f : manifest.files()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                client.downloadFile(f.sha1, out::write, null);
                byte[] got = out.toByteArray();

                assertEquals(f.size, got.length, "文件大小应一致：" + f.path);
                assertEquals(f.sha1, sha1Hex(got), "文件哈希应一致：" + f.path);
                verified++;
                System.out.println("[E2E]   ✓ " + f.path + "（" + got.length + " 字节，哈希校验通过）");
            }
            assertEquals(manifest.files().size(), verified);
            assertTrue(verified > 0, "至少应成功下载并校验一个文件");
        }
    }

    @Test
    @DisplayName("★ 记录指纹后，用错误指纹连接会被明确拒绝")
    void wrongFingerprintIsRejectedByLiveServer() throws Exception {
        try (PackClient client = new PackClient(host(), port(), null,
                Ed25519Identity.generate(), "0000000000000000")) {
            boolean rejected = false;
            try {
                client.handshake("e2e-test-client");
            } catch (PackClient.IdentityChangedException e) {
                rejected = true;
                System.out.println("[E2E] 已按预期拒绝错误指纹：" + e.getMessage());
            }
            assertTrue(rejected, "记住的指纹不匹配时必须拒绝");
        }
    }

    private static String sha1Hex(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
