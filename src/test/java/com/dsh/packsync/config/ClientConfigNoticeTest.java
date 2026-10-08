package com.dsh.packsync.config;

import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏内提示里显示的名字（{@code [PackSync] 整合包「…」已安装…}）。
 *
 * <p>这个名字此前写死在代码里，管理员改不了 —— 想换个自称、给自建整合包品牌化
 * 都只能改源码。现在由客户端配置的 {@code noticePrefix} 决定。
 */
class ClientConfigNoticeTest {

    @Test
    @DisplayName("默认名字是 PackSync")
    void defaultName() {
        assertEquals("PackSync", new ClientConfig().noticePrefix);
    }

    @Test
    @DisplayName("★ 空 / 空白 / null 一律回落到默认，避免提示变成 \"[] xxx\"")
    void blankFallsBack() {
        ClientConfig a = new ClientConfig();
        a.noticePrefix = "";
        assertEquals("PackSync", a.normalize().noticePrefix);

        ClientConfig b = new ClientConfig();
        b.noticePrefix = "   ";
        assertEquals("PackSync", b.normalize().noticePrefix);

        ClientConfig c = new ClientConfig();
        c.noticePrefix = null;
        assertEquals("PackSync", c.normalize().noticePrefix);
    }

    @Test
    @DisplayName("自定义名字保留原样")
    void customName() {
        ClientConfig cfg = new ClientConfig();
        cfg.noticePrefix = "通天之路";
        assertEquals("通天之路", cfg.normalize().noticePrefix);
    }

    @Test
    @DisplayName("JSON 往返保留该字段；老配置缺这一项时回落默认")
    void jsonRoundTrip() {
        ClientConfig cfg = new ClientConfig();
        cfg.noticePrefix = "MyPack";
        String json = ConfigIO.gson().toJson(cfg);
        assertTrue(json.contains("noticePrefix"), "新字段必须写进落盘 JSON");
        assertEquals("MyPack",
                ConfigIO.gson().fromJson(json, ClientConfig.class).normalize().noticePrefix);

        // 旧版本写下的配置：完全没有 noticePrefix 这一项
        String legacy = "{\"configVersion\":1,\"downloadMode\":\"SERVER_ONLY\"}";
        assertEquals("PackSync",
                ConfigIO.gson().fromJson(legacy, ClientConfig.class).normalize().noticePrefix);
    }
}
