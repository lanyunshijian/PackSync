package com.dsh.packsync.config;

import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 同步端口的解析。
 *
 * <p>端口由<b>服务端</b>界定：服务端决定分发服务监听哪个口，登录时把实际端口下发下来，
 * 客户端照着用。这里钉死两件事：
 * <ol>
 *   <li>有服务端下发的端口时一律用它 —— 客户端没有第二个来源；</li>
 *   <li>服务端没下发时才回落到 {@code MC 端口 + 1}（默认部署的约定）；
 *       再拿不到就返回 -1，绝不瞎猜一个端口。</li>
 * </ol>
 */
class ClientConfigPortTest {

    private static ClientConfig.ServerEntry entry(int port, int mcPort) {
        ClientConfig.ServerEntry e = new ClientConfig.ServerEntry();
        e.host = "example.com";
        e.port = port;
        e.mcHost = "example.com";
        e.mcPort = mcPort;
        return e;
    }

    @Test
    @DisplayName("★ 有服务端下发的端口就用它")
    void serverPortWins() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(25566, cfg.resolveSyncPort(entry(25566, 25565)));
        assertEquals("25566（服务端下发）", cfg.describeSyncPort(entry(25566, 25565)));
    }

    @Test
    @DisplayName("服务端没下发时按 MC 端口 + 1 推断（默认部署的约定）")
    void fallbackMcPlusOne() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(25566, cfg.resolveSyncPort(entry(-1, 25565)));
        assertEquals(25566, cfg.resolveSyncPort(entry(0, 25565)));
        assertEquals("25566（服务端未下发，按 MC 端口 + 1 推断）", cfg.describeSyncPort(entry(-1, 25565)));
    }

    @Test
    @DisplayName("什么都拿不到时返回 -1，而不是瞎猜")
    void unknown() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(-1, cfg.resolveSyncPort(null));
        assertEquals(-1, cfg.resolveSyncPort(entry(-1, -1)));
        assertEquals("未知", cfg.describeSyncPort(null));
    }

    @Test
    @DisplayName("★ 客户端不再有自己的端口配置：旧配置里的 syncPortOverride 必须被忽略")
    void clientOverrideIsGone() {
        String legacy = "{\"configVersion\":1,\"syncPortOverride\":30000,"
                + "\"installedServers\":{\"a@25566\":{\"host\":\"example.com\",\"port\":25566,"
                + "\"mcHost\":\"example.com\",\"mcPort\":25565}}}";
        ClientConfig cfg = ConfigIO.gson().fromJson(legacy, ClientConfig.class).normalize();
        // 老配置里的残留值不得影响解析结果 —— 仍然以服务端下发的 25566 为准
        assertEquals(25566, cfg.resolveSyncPort(cfg.installedServers.get("a@25566")));
        // 重新落盘时也不该再写出这个字段
        String out = ConfigIO.gson().toJson(cfg);
        assertFalse(out.contains("syncPortOverride"), "已废弃的字段不应再出现在新配置里");
    }
}
