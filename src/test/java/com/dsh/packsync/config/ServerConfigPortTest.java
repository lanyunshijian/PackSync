package com.dsh.packsync.config;

import com.dsh.packsync.core.config.ServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 服务端如何界定"告诉客户端连哪个端口"。
 *
 * <p>端口是服务端说了算：默认下发分发服务<b>实际监听</b>的端口；
 * 只有在端口映射 / NAT 后面、内外端口不一致时才用 {@code portToSend} 覆盖。
 */
class ServerConfigPortTest {

    @Test
    @DisplayName("★ 默认下发实际监听的端口")
    void defaultsToActualPort() {
        ServerConfig cfg = new ServerConfig();
        assertEquals(25566, cfg.advertisedPort(25566));
        // bindPort 指定了独立端口时，下发的就是那个端口
        cfg.bindPort = 30000;
        assertEquals(30000, cfg.advertisedPort(30000));
    }

    @Test
    @DisplayName("同端口分流（bindPort = -1）时，下发的仍是实际监听端口")
    void reusingMcPort() {
        ServerConfig cfg = new ServerConfig();
        assertEquals(-1, cfg.bindPort);
        assertEquals(25565, cfg.advertisedPort(25565));
    }

    @Test
    @DisplayName("配了 portToSend 才覆盖（端口映射 / NAT 场景）")
    void portToSendOverrides() {
        ServerConfig cfg = new ServerConfig();
        cfg.portToSend = 30000;
        assertEquals(30000, cfg.advertisedPort(25566), "对外端口与监听端口不一致时应下发对外端口");
    }

    @Test
    @DisplayName("portToSend 的非法值不影响默认行为")
    void badPortToSend() {
        ServerConfig cfg = new ServerConfig();
        cfg.portToSend = 0;
        assertEquals(25566, cfg.advertisedPort(25566));
        cfg.portToSend = -5;
        assertEquals(25566, cfg.advertisedPort(25566));
    }
}
