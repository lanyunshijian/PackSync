package com.dsh.packsync.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 配置读写：Gson + JSON，原子落盘，解析失败不销毁原文件。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>解析失败时保留原文件并回退默认值</b> —— 玩家手改坏了配置，
 *       要能自己看回来，而不是被我们静默覆盖成默认值。</li>
 *   <li><b>写盘用"临时文件 + 原子移动"</b> —— 游戏被强杀时不会留下半截 JSON。</li>
 *   <li><b>首次生成时写全量默认值</b> —— 用户能看到所有可调项，不用去翻文档。</li>
 * </ol>
 */
public final class ConfigIO {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private ConfigIO() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static Gson compactGson() {
        return COMPACT;
    }

    // ── 服务端 ────────────────────────────────────────────────────────────

    public static ServerConfig loadServer(Path file) {
        ServerConfig cfg = read(file, ServerConfig.class);
        if (cfg == null) {
            cfg = new ServerConfig();
        }
        return cfg.normalize();
    }

    public static void saveServer(Path file, ServerConfig cfg) {
        write(file, cfg == null ? new ServerConfig() : cfg.normalize());
    }

    // ── 客户端 ────────────────────────────────────────────────────────────

    public static ClientConfig loadClient(Path file) {
        ClientConfig cfg = read(file, ClientConfig.class);
        if (cfg == null) {
            cfg = new ClientConfig();
        }
        return cfg.normalize();
    }

    public static void saveClient(Path file, ClientConfig cfg) {
        write(file, cfg == null ? new ClientConfig() : cfg.normalize());
    }

    // ── 通用 ──────────────────────────────────────────────────────────────

    /** 读取；文件不存在或解析失败返回 null（调用方决定回退策略）。 */
    public static <T> T read(Path file, Class<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return null;
            }
            return GSON.fromJson(json, type);
        } catch (JsonSyntaxException e) {
            // 明确区分"语法错误"与"读不出来"，前者是用户手改坏了，值得点名。
            System.err.println("[PackSync] 配置文件 JSON 语法错误，已回退默认值（原文件保留）：" + file);
            return null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PackSync] 读取配置失败，已回退默认值：" + file + " -> " + e);
            return null;
        }
    }

    /** 原子写入：先写同目录临时文件，再 move 覆盖。 */
    public static void write(Path file, Object value) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统（部分网络盘）不支持 ATOMIC_MOVE，退回普通覆盖。
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[PackSync] 写入配置失败：" + file + " -> " + e);
        }
    }

    /** 紧凑 JSON（用于网络传输，不含缩进）。 */
    public static String toCompactJson(Object value) {
        return COMPACT.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
