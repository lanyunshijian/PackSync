package com.dsh.packsync.config;

import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步端口的解析与配置读写。
 *
 * <p>端口的来源有三个，优先级必须稳定：手动指定 &gt; 服务端下发 &gt; MC 端口 + 1。
 * 这条链一旦搞错，表现是"同步连到一个没人监听的端口"，而<b>进服完全正常</b> ——
 * 玩家很难自己看出问题出在哪。
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
    @DisplayName("★ 优先用手动指定的端口")
    void overrideWins() {
        ClientConfig cfg = new ClientConfig();
        cfg.syncPortOverride = 30000;
        assertEquals(30000, cfg.resolveSyncPort(entry(25566, 25565)));
    }

    @Test
    @DisplayName("没手动指定时用服务端下发的端口")
    void serverPort() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(25566, cfg.resolveSyncPort(entry(25566, 25565)));
    }

    @Test
    @DisplayName("服务端没下发时按 MC 端口 + 1 推断")
    void fallbackMcPlusOne() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(25566, cfg.resolveSyncPort(entry(-1, 25565)));
        assertEquals(25566, cfg.resolveSyncPort(entry(0, 25565)));
    }

    @Test
    @DisplayName("什么都拿不到时返回 -1，而不是瞎猜一个端口")
    void unknown() {
        ClientConfig cfg = new ClientConfig();
        assertEquals(-1, cfg.resolveSyncPort(null));
        assertEquals(-1, cfg.resolveSyncPort(entry(-1, -1)));
    }

    @Test
    @DisplayName("非法的 syncPortOverride 在 normalize 时被清成 0（自动）")
    void normalizeRejectsBadOverride() {
        ClientConfig a = new ClientConfig();
        a.syncPortOverride = 70000;
        assertEquals(0, a.normalize().syncPortOverride);

        ClientConfig b = new ClientConfig();
        b.syncPortOverride = -5;
        assertEquals(0, b.normalize().syncPortOverride);

        ClientConfig c = new ClientConfig();
        c.syncPortOverride = 25566;
        assertEquals(25566, c.normalize().syncPortOverride);
    }

    @Test
    @DisplayName("端口来源说明与优先级一致")
    void describe() {
        ClientConfig cfg = new ClientConfig();
        assertEquals("25566（服务端下发）", cfg.describeSyncPort(entry(25566, 25565)));
        assertEquals("25566（MC 端口 + 1 推断）", cfg.describeSyncPort(entry(-1, 25565)));
        assertEquals("未知", cfg.describeSyncPort(null));
        cfg.syncPortOverride = 30000;
        assertEquals("30000（手动指定）", cfg.describeSyncPort(entry(25566, 25565)));
        assertEquals("30000（手动指定）", cfg.describeSyncPort(null),
                "手动指定时，与有没有服务器记录无关");
    }

    @Test
    @DisplayName("JSON 往返不丢 syncPortOverride；老配置缺这一项时回落 0")
    void jsonRoundTrip() {
        ClientConfig cfg = new ClientConfig();
        cfg.syncPortOverride = 25580;
        String json = ConfigIO.gson().toJson(cfg);
        assertTrue(json.contains("syncPortOverride"), "新字段必须出现在落盘 JSON 里");

        ClientConfig back = ConfigIO.gson().fromJson(json, ClientConfig.class).normalize();
        assertEquals(25580, back.syncPortOverride);

        // 模拟旧版本写下的配置：完全没有 syncPortOverride 这一项
        String legacy = "{\"configVersion\":1,\"downloadMode\":\"SERVER_ONLY\"}";
        ClientConfig old = ConfigIO.gson().fromJson(legacy, ClientConfig.class).normalize();
        assertEquals(0, old.syncPortOverride, "老配置必须回落到自动");
    }
}
