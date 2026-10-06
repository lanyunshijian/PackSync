package com.dsh.packsync.core.util;

import java.nio.file.Path;

/**
 * 全部路径的唯一来源。零 MC 依赖 —— 启动期 locator 也要用它。
 *
 * <p>布局（服务端，root = 服务端根目录）：
 * <pre>
 * &lt;root&gt;/modpack-keys/                  ← 验证密钥（需求指定：放服务端根目录下）
 * &lt;root&gt;/config/packsync-server.json    ← 服务端配置
 * &lt;root&gt;/packsync/host/main/            ← 放"要发给客户端"的文件
 * &lt;root&gt;/packsync/host/manifest.json    ← 生成的整合包清单
 * </pre>
 *
 * <p>布局（客户端，root = 游戏目录）：
 * <pre>
 * &lt;root&gt;/config/packsync-client.json    ← 客户端配置
 * &lt;root&gt;/packsync/modpacks/&lt;名字&gt;/      ← 从服务器下载的整合包内容
 * &lt;root&gt;/packsync/cache/                ← 哈希缓存、待删除标记
 * </pre>
 */
public final class PackPaths {

    /** 服务端密钥目录名（位于服务端根目录下）。 */
    public static final String KEYS_DIR = "modpack-keys";

    /** 身份密钥文件名。 */
    public static final String IDENTITY_FILE = "server-identity.key";

    /** 指纹说明文件名（人类可读，便于直接发给玩家核对）。 */
    public static final String FINGERPRINT_FILE = "fingerprint.txt";

    /** 预共享密钥文件名（可选，存在即启用 PSK 模式）。 */
    public static final String PSK_FILE = "psk.key";

    public static final String SERVER_CONFIG_NAME = "packsync-server.json";
    public static final String CLIENT_CONFIG_NAME = "packsync-client.json";

    private final Path root;

    private PackPaths(Path root) {
        this.root = root;
    }

    /** 以当前工作目录为根（服务端根 / 游戏目录）。 */
    public static PackPaths workingDir() {
        return of(Path.of(System.getProperty("user.dir")));
    }

    public static PackPaths of(Path root) {
        return new PackPaths(root.toAbsolutePath().normalize());
    }

    public Path root() {
        return root;
    }

    // ── 密钥（服务端）────────────────────────────────────────────────────

    /** {@code <root>/modpack-keys/} */
    public Path keysDir() {
        return root.resolve(KEYS_DIR);
    }

    public Path identityFile() {
        return keysDir().resolve(IDENTITY_FILE);
    }

    public Path fingerprintFile() {
        return keysDir().resolve(FINGERPRINT_FILE);
    }

    public Path pskFile() {
        return keysDir().resolve(PSK_FILE);
    }

    // ── 配置 ──────────────────────────────────────────────────────────────

    public Path configDir() {
        return root.resolve("config");
    }

    public Path serverConfigFile() {
        return configDir().resolve(SERVER_CONFIG_NAME);
    }

    public Path clientConfigFile() {
        return configDir().resolve(CLIENT_CONFIG_NAME);
    }

    // ── 服务端整合包 ──────────────────────────────────────────────────────

    /** {@code <root>/packsync/host/} */
    public Path hostDir() {
        return root.resolve("packsync").resolve("host");
    }

    /**
     * {@code <root>/packsync/host/main/} —— 放进这里的文件**无条件全部同步**，
     * 路径相对该目录映射到客户端游戏目录（{@code main/mods/x.jar -> mods/x.jar}）。
     */
    public Path hostMainDir() {
        return hostDir().resolve("main");
    }

    public Path manifestFile() {
        return hostDir().resolve("manifest.json");
    }

    // ── 客户端 ────────────────────────────────────────────────────────────

    /** {@code <root>/packsync/modpacks/} */
    public Path modpacksDir() {
        return root.resolve("packsync").resolve("modpacks");
    }

    public Path modpackDir(String name) {
        return modpacksDir().resolve(sanitize(name));
    }

    /** {@code <root>/packsync/cache/} */
    public Path cacheDir() {
        return root.resolve("packsync").resolve("cache");
    }

    /** 把用户提供的名字变成安全的单层目录名（防路径穿越）。 */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        String s = name.replace('\\', '_').replace('/', '_').trim();
        s = s.replaceAll("[<>:\"|?*]", "_");
        // 去掉 ".." 这类会跳出目录的段
        while (s.contains("..")) {
            s = s.replace("..", "_");
        }
        if (s.length() > 96) {
            s = s.substring(0, 96);
        }
        return s.isBlank() ? "default" : s;
    }
}
