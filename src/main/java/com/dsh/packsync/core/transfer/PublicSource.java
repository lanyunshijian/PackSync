package com.dsh.packsync.core.transfer;

import java.util.List;

/**
 * 公共下载源（Modrinth / CurseForge）。
 *
 * <p>公共源的价值是**卸载服务端带宽**：能在公开平台匹配到的文件，
 * 让客户端直接去官方 CDN 拉，而不是全部挤在服务器上。
 *
 * <p>但它是**可选**的 —— 私有/魔改整合包在公共平台根本匹配不到，
 * 那时只会白白等待 API 往返。这正是 {@link com.dsh.packsync.core.config.DownloadMode}
 * 存在的意义，也是新增的「直接从服务端下载」模式的立足点。
 */
public interface PublicSource {

    /** 源名，用于日志与界面（如 "modrinth"）。 */
    String name();

    /** 是否已具备可用条件（例如 CurseForge 需要 API Key）。 */
    boolean isAvailable();

    /**
     * 按哈希反查直链。
     *
     * @param sha1   文件 SHA-1（所有源都可以用）
     * @param murmur CurseForge 指纹，可能为 null（非 jar 或未计算）
     * @return 可用的直链列表（可能为空，表示未匹配到）
     */
    List<String> resolve(String sha1, String murmur);
}
