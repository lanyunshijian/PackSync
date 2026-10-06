package com.dsh.packsync.core.keys;

import com.dsh.packsync.core.util.PackPaths;
import com.dsh.packsync.crypto.Bytes;
import com.dsh.packsync.crypto.Ed25519Identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;

/**
 * 服务端验证密钥的生命周期管理。
 *
 * <p><b>本类对应需求：「验证环节的密钥直接自动生成在服务端的根目录下」。</b>
 * 落点就是 {@code <服务端根>/modpack-keys/}，而不是藏在 config 或某个点号目录里
 * —— 管理员打开服务器根目录就能看到、备份、并把指纹抄给玩家。
 *
 * <p>首次需要密钥时（服务端启动、或客户端第一次来握手）自动生成，无需任何命令。
 * 生成的东西：
 * <pre>
 * &lt;root&gt;/modpack-keys/server-identity.key   Ed25519 长期身份密钥（Base64 文本，权限 600）
 * &lt;root&gt;/modpack-keys/fingerprint.txt       指纹 + 使用说明（人类可读，可直接发玩家）
 * &lt;root&gt;/modpack-keys/psk.key                预共享密钥（仅在启用 PSK 时创建）
 * </pre>
 *
 * <p>为什么身份密钥存成 Base64 文本而不是二进制：这个目录的用途就是"让人看得见"。
 * 文本格式可以直接 cat / 复制粘贴 / 进版本库（不建议），二进制则在跨平台备份时
 * 容易因换行或编码被改坏。
 */
public final class KeyManager {

    private final PackPaths paths;

    public KeyManager(PackPaths paths) {
        this.paths = paths;
    }

    // ── 身份密钥 ──────────────────────────────────────────────────────────

    /**
     * 加载服务端身份密钥；不存在则**自动生成**。
     *
     * <p>永不返回 null，也永不因密钥问题抛异常阻塞服务器启动 ——
     * 文件损坏时会把坏文件改名留档（{@code .corrupt-<时间戳>}）再生成新的，
     * 并在日志里明确提示指纹发生了变化（玩家需要重新核对）。
     */
    public Ed25519Identity loadOrCreateIdentity() {
        Path file = paths.identityFile();
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                byte[] raw = decodeIdentityText(text);
                Ed25519Identity id = Ed25519Identity.decode(raw);
                ensureFingerprintFile(id, false);
                return id;
            } catch (Exception e) {
                // 不静默覆盖：先把坏文件留档，否则管理员永远不知道发生了什么。
                Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
                try {
                    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("[PackSync] 身份密钥无法解析（" + e.getMessage()
                            + "），已留档为 " + backup.getFileName() + " 并重新生成。");
                    System.err.println("[PackSync] ⚠ 指纹已改变，玩家需要重新核对新指纹。");
                } catch (IOException io) {
                    System.err.println("[PackSync] 备份损坏的身份密钥失败：" + io);
                }
            }
        }
        Ed25519Identity created = Ed25519Identity.generate();
        writeIdentity(created);
        ensureFingerprintFile(created, true);
        System.out.println("[PackSync] 已在 " + paths.keysDir() + " 自动生成服务端验证密钥");
        System.out.println("[PackSync] 服务器指纹：" + created.fingerprint());
        return created;
    }

    private void writeIdentity(Ed25519Identity id) {
        try {
            Path dir = paths.keysDir();
            Files.createDirectories(dir);
            String text = "# PackSync 服务端身份密钥（Ed25519，64 字节 Base64）\n"
                    + "# 删除本文件会在下次启动时自动重新生成 —— 但那会改变服务器指纹，\n"
                    + "# 已记住旧指纹的玩家会收到「服务器身份变了」的告警。\n"
                    + Base64.getEncoder().encodeToString(id.encode()) + "\n";
            Path file = paths.identityFile();
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictToOwner(file);
            restrictDirToOwner(dir);
        } catch (IOException e) {
            System.err.println("[PackSync] 写入身份密钥失败：" + e);
        }
    }

    /** 解析身份密钥文本：容忍注释行、空行、以及纯 Base64 内容。 */
    private static byte[] decodeIdentityText(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            sb.append(t);
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("密钥文件为空");
        }
        return Base64.getDecoder().decode(sb.toString());
    }

    /** 生成/刷新 fingerprint.txt。已存在且内容一致时不重写，避免每次启动都改动 mtime。 */
    private void ensureFingerprintFile(Ed25519Identity id, boolean force) {
        String fp = id.fingerprint();
        Path file = paths.fingerprintFile();
        try {
            Files.createDirectories(paths.keysDir());
            // 旧版本的文件把指纹埋在说明文字中间，玩家全选复制必然对不上。
            // 这里除了内容不一致，还要在"第一行不是纯指纹"时重写一次，让老部署自动升级格式。
            if (!force && Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8);
                String firstLine = existing.isEmpty() ? "" : existing.split("\\R", 2)[0].trim();
                if (existing.contains(fp) && firstLine.equalsIgnoreCase(fp)) {
                    return;
                }
            }
            // 指纹【单独占第一行且不加任何前缀】—— 这样"复制第一行"就是干净的指纹。
            // 说明文字统一用 # 开头，玩家即使全选复制，客户端也能从文本里认出指纹
            // （见 ClientSyncTask.normalizeFingerprint 的"取最长十六进制片段"）。
            String body = """
                    %s

                    # PackSync 服务器身份指纹（Ed25519 公钥的 SHA-256）
                    # 用法：把上面第一行原样发给玩家。玩家首次连接该服务器时会被要求输入它，
                    #       用来确认"文件确实来自这台服务器、途中没被掉包"（防中间人篡改）。
                    # 服务端可用 /packsync host fingerprint 再次查看。
                    # 若玩家提示「服务器身份变了」，说明 modpack-keys/ 下的密钥被替换或删除过。
                    # 密钥文件：%s
                    """.formatted(fp, paths.identityFile().getFileName());
            Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictToOwner(file);
        } catch (IOException e) {
            System.err.println("[PackSync] 写入指纹说明失败：" + e);
        }
    }

    /** 便捷读取：只想要指纹时不必构造完整身份对象。 */
    public String fingerprint() {
        return loadOrCreateIdentity().fingerprint();
    }

    // ── 预共享密钥（可选）────────────────────────────────────────────────

    /**
     * 加载 PSK；不存在时返回 null（表示使用 TOFU 模式）。
     * 只有管理员显式创建 {@code psk.key} 才会进入 PSK 模式 —— 默认零配置可用。
     */
    public String loadPsk() {
        Path file = paths.pskFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            for (String line : text.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    return t;
                }
            }
        } catch (IOException e) {
            System.err.println("[PackSync] 读取 psk.key 失败：" + e);
        }
        return null;
    }

    /** 生成一个新的 PSK 文件（供命令使用），返回该密钥。 */
    public String createPsk() {
        String psk = Bytes.hex(Bytes.random(32));
        try {
            Files.createDirectories(paths.keysDir());
            String body = "# PackSync 预共享密钥（PSK）。存在本文件即启用 PSK 模式。\n"
                    + "# 把这一行发给玩家填入客户端配置即可；删掉本文件即回到 TOFU 模式。\n"
                    + psk + "\n";
            Files.writeString(paths.pskFile(), body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            restrictToOwner(paths.pskFile());
        } catch (IOException e) {
            System.err.println("[PackSync] 写入 psk.key 失败：" + e);
        }
        return psk;
    }

    // ── 权限 ──────────────────────────────────────────────────────────────

    /** 尽力把文件权限收成 600；Windows 或非 POSIX 文件系统上静默跳过。 */
    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows：靠目录默认 ACL，不额外处理。
        }
    }

    private static void restrictDirToOwner(Path dir) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(dir, perms);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // 同上。
        }
    }
}
