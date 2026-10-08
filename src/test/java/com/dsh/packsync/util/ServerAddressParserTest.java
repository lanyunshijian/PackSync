package com.dsh.packsync.util;

import com.dsh.packsync.core.util.ServerAddressParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 服务器地址解析的回归测试。
 *
 * <p>这组用例的由来是一起线上故障：IPv6 联机的玩家在客户端配置里被记成
 * {@code host = "2409:8d28:12a:e52:e981:6774:7bdb:88e8:25565"}（端口混进了 host），
 * 于是拼出 {@code http://2409:...:88e8:25565:25566/packsync/v1}，
 * 同步直接以 {@code MalformedURLException} 失败。下面把这些形态全部钉死。
 */
class ServerAddressParserTest {

    @Test
    @DisplayName("普通域名与 IPv4")
    void plainHosts() {
        assertEquals("play.example.com", ServerAddressParser.host("play.example.com"));
        assertEquals("play.example.com", ServerAddressParser.host("play.example.com:25565"));
        assertEquals("192.168.1.10", ServerAddressParser.host("192.168.1.10"));
        assertEquals("192.168.1.10", ServerAddressParser.host("192.168.1.10:25566"));
        assertEquals("192.168.1.10", ServerAddressParser.host("  192.168.1.10:25566  "));
        assertEquals(25565, ServerAddressParser.port("play.example.com", 25565));
        assertEquals(25566, ServerAddressParser.port("192.168.1.10:25566", 25565));
    }

    @Test
    @DisplayName("方括号 IPv6")
    void bracketedIpv6() {
        assertEquals("::1", ServerAddressParser.host("[::1]"));
        assertEquals("::1", ServerAddressParser.host("[::1]:25565"));
        assertEquals(25565, ServerAddressParser.port("[::1]:25565", 25565));
        assertEquals(25565, ServerAddressParser.port("[::1]", 25565));
        assertEquals("2409:8d28:12a:e52:e981:6774:7bdb:88e8",
                ServerAddressParser.host("[2409:8d28:12a:e52:e981:6774:7bdb:88e8]:25566"));
    }

    @Test
    @DisplayName("裸 IPv6（无端口）整体当 host")
    void bareIpv6() {
        assertEquals("2409:8d28:12a:e52:e981:6774:7bdb:88e8",
                ServerAddressParser.host("2409:8d28:12a:e52:e981:6774:7bdb:88e8"));
        assertEquals(25565,
                ServerAddressParser.port("2409:8d28:12a:e52:e981:6774:7bdb:88e8", 25565));
        assertEquals("::1", ServerAddressParser.host("::1"));
        assertEquals("fe80::1", ServerAddressParser.host("fe80::1"));
        assertEquals(25565, ServerAddressParser.port("::1", 25565));
    }

    @Test
    @DisplayName("★ 裸 IPv6 + 端口：端口必须被剥掉（本次故障的根因）")
    void bareIpv6WithPort() {
        String raw = "2409:8d28:12a:e52:e981:6774:7bdb:88e8:25565";
        assertEquals("2409:8d28:12a:e52:e981:6774:7bdb:88e8", ServerAddressParser.host(raw));
        assertEquals(25565, ServerAddressParser.port(raw, 0));
    }

    @Test
    @DisplayName("★ 拼 URL 时 IPv6 必须加方括号")
    void urlHost() {
        assertEquals("[2409:8d28:12a:e52:e981:6774:7bdb:88e8]",
                ServerAddressParser.urlHost("2409:8d28:12a:e52:e981:6774:7bdb:88e8"));
        assertEquals("[::1]", ServerAddressParser.urlHost("::1"));
        assertEquals("[::1]", ServerAddressParser.urlHost("[::1]"), "已带括号不应重复加");
        assertEquals("play.example.com", ServerAddressParser.urlHost("play.example.com"));
        assertEquals("127.0.0.1", ServerAddressParser.urlHost("127.0.0.1"));
        assertEquals("", ServerAddressParser.urlHost(null));
    }

    @Test
    @DisplayName("★ 规范化后的 host 拼出的 URL 必须合法（旧写法会抛 MalformedURLException）")
    void urlIsWellFormed() throws Exception {
        String raw = "2409:8d28:12a:e52:e981:6774:7bdb:88e8:25565";
        String host = ServerAddressParser.host(raw);
        int port = ServerAddressParser.port(raw, 25565);
        String url = "http://" + ServerAddressParser.urlHost(host) + ":" + port + "/packsync/v1";
        assertEquals("http://[2409:8d28:12a:e52:e981:6774:7bdb:88e8]:25565/packsync/v1", url);
        java.net.URI.create(url).toURL(); // 不抛异常即为合法
    }

    @Test
    @DisplayName("畸形输入不炸，一律回落到兜底值")
    void malformed() {
        assertEquals("", ServerAddressParser.host(null));
        assertEquals("", ServerAddressParser.host("   "));
        assertEquals(25565, ServerAddressParser.port(null, 25565));
        assertEquals(25565, ServerAddressParser.port("host:notaport", 25565));
        assertEquals(25565, ServerAddressParser.port("host:99999", 25565));
        assertEquals(25565, ServerAddressParser.port("", 25565));
    }
}
