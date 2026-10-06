package com.dsh.packsync.core.config;

import java.util.Locale;

/**
 * 下载来源策略 —— 本 mod 的**新增可选项**。
 *
 * <p>既有做法固定为「优先公共站 CDN、失败回退服务端」。本 mod 把它变成
 * 玩家/管理员可选的策略，其中 {@link #SERVER_ONLY} 就是需求里的
 * 「直接从服务端下载」：完全不查公共站，所有文件都走自己的服务端。
 *
 * <p>为什么这个选项有意义：
 * <ul>
 *   <li>{@link #SERVER_ONLY} —— 私有/魔改整合包在公共站根本匹配不到，
 *       走公共站只会白白等待 API 往返；内网部署时直连服务端也更快。</li>
 *   <li>{@link #PUBLIC_ONLY} —— 服务端带宽紧张（家宽/限流）时，
 *       把流量全部卸载到公共 CDN。</li>
 *   <li>{@link #PUBLIC_FIRST} / {@link #SERVER_FIRST} —— 折中，只是回退顺序不同。</li>
 * </ul>
 *
 * <p>解析对大小写与前后空白宽容，认不出时回落到 {@link #PUBLIC_FIRST}（与常见行为一致），
 * 绝不因为一个配置写错就让同步失败。
 */
public enum DownloadMode {

    /** 只从服务端下载（新增的「直接从服务端下载」）。 */
    SERVER_ONLY,

    /** 只从公共站（Modrinth / CurseForge）下载；匹配不到即视为失败，不回退服务端。 */
    PUBLIC_ONLY,

    /** 优先公共站直链，失败回退服务端。等价于传统的默认行为，默认值。 */
    PUBLIC_FIRST,

    /** 优先服务端，失败回退公共站。 */
    SERVER_FIRST;

    public static final DownloadMode DEFAULT = PUBLIC_FIRST;

    /** 宽容解析：null / 空白 / 未知值 → {@link #DEFAULT}。 */
    public static DownloadMode parse(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (DownloadMode m : values()) {
            if (m.name().equals(s)) {
                return m;
            }
        }
        // 兼容几种直观写法
        switch (s) {
            case "SERVER":
            case "HOST":
            case "SERVERDIRECT":
            case "DIRECT":
                return SERVER_ONLY;
            case "PUBLIC":
            case "CDN":
            case "MODRINTH":
                return PUBLIC_ONLY;
            case "AUTO":
            case "PUBLICFIRST":
                return PUBLIC_FIRST;
            case "SERVERFIRST":
            case "AUTOSERVER":
                return SERVER_FIRST;
            default:
                return DEFAULT;
        }
    }

    /** 是否允许使用公共站。 */
    public boolean allowsPublic() {
        return this != SERVER_ONLY;
    }

    /** 是否允许使用服务端通道。 */
    public boolean allowsServer() {
        return this != PUBLIC_ONLY;
    }

    /** 优先尝试的通道是否为服务端。 */
    public boolean serverFirst() {
        return this == SERVER_ONLY || this == SERVER_FIRST;
    }

    public String describe() {
        switch (this) {
            case SERVER_ONLY:
                return "只从服务端下载（不查询公共站）";
            case PUBLIC_ONLY:
                return "只从公共站下载（Modrinth/CurseForge）";
            case SERVER_FIRST:
                return "优先从服务端下载，失败回退公共站";
            case PUBLIC_FIRST:
            default:
                return "优先从公共站下载，失败回退服务端";
        }
    }
}
