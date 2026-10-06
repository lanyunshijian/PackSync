package com.dsh.packsync.core.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 jar 里读 mod 元数据 —— <b>纯 Java，零 MC 依赖</b>，因为服务端生成清单时
 * 可能跑在启动期 locator 阶段（那时 MC 类还没准备好）。
 *
 * <p>支持四种元数据（按加载器优先级尝试）：
 * <ul>
 *   <li>{@code META-INF/mods.toml}（Forge）</li>
 *   <li>{@code META-INF/neoforge.mods.toml}（NeoForge）</li>
 *   <li>{@code fabric.mod.json}（Fabric）</li>
 *   <li>{@code quilt.mod.json}（Quilt）</li>
 * </ul>
 *
 * <p>用途有两个，都直接影响同步结果：
 * <ol>
 *   <li><b>判定"这是不是一个 mod"</b> —— 决定清单里的 {@code type}，
 *       进而决定它是否参与重复 mod 剔除与嵌套处理。</li>
 *   <li><b>判定"是不是仅服务端需要"</b> —— {@code excludeServerSideMods} 的依据。
 *       注意：**只有显式声明了 server 侧别才会被排除**；没声明的一律按 UNIVERSAL
 *       保留（宁可多发，不可少发 —— 少发会让玩家进不去服务器）。</li>
 * </ol>
 */
public final class ModInspector {

    /** mod 的运行侧别。 */
    public enum Side {
        CLIENT,
        SERVER,
        UNIVERSAL
    }

    /**
     * mod 元数据快照。
     *
     * @param dependencies 该 mod 的依赖 modId（用于"剔除重复 mod 时不能删掉别人依赖的那个"）
     * @param provides     该 mod 声明的别名 id（有些 mod 用 provides 顶替另一个 mod 的身份）
     */
    public record ModInfo(String modId, String version, Side side, String source,
                          java.util.Set<String> dependencies, java.util.Set<String> provides) {

        public boolean isServerOnly() {
            return side == Side.SERVER;
        }

        /** 该 mod 是否"顶替"了某个 id（自身 id 或 provides 里任意一个）。 */
        public boolean providesAny(java.util.Collection<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return false;
            }
            if (ids.contains(modId)) {
                return true;
            }
            for (String p : provides) {
                if (ids.contains(p)) {
                    return true;
                }
            }
            return false;
        }

        /** 是否声明依赖某个 id（含 provides）。 */
        public boolean dependsOn(java.util.Collection<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return false;
            }
            for (String d : dependencies) {
                if (ids.contains(d)) {
                    return true;
                }
            }
            return false;
        }
    }

    private ModInspector() {
    }

    /** 读元数据；不是 mod（或读不出来）返回 null。 */
    public static ModInfo inspect(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)
                || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return null;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // NeoForge 优先于 Forge：NeoForge jar 可能同时带两个文件。
            ModInfo info = fromToml(zip, "META-INF/neoforge.mods.toml", "neoforge.mods.toml");
            if (info != null) {
                return info;
            }
            info = fromJson(zip, "fabric.mod.json", "fabric.mod.json");
            if (info != null) {
                return info;
            }
            info = fromToml(zip, "META-INF/mods.toml", "mods.toml");
            if (info != null) {
                return info;
            }
            return fromJson(zip, "quilt.mod.json", "quilt.mod.json");
        } catch (IOException | RuntimeException e) {
            // 损坏的 jar、非 zip、权限问题 —— 都只是"不是 mod"，不该中断生成。
            return null;
        }
    }

    /** 便捷：是不是 mod。 */
    public static boolean isMod(Path jar) {
        return inspect(jar) != null;
    }

    // ── Fabric / Quilt JSON ───────────────────────────────────────────────

    private static ModInfo fromJson(ZipFile zip, String entryName, String source) {
        String text = readEntry(zip, entryName);
        if (text == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();

            String id = optString(obj, "id");
            String version = optString(obj, "version");
            String environment = optString(obj, "environment");

            // Quilt：字段嵌在 quilt_loader 下；侧别在 minecraft.environment。
            if (obj.has("quilt_loader") && obj.get("quilt_loader").isJsonObject()) {
                JsonObject ql = obj.getAsJsonObject("quilt_loader");
                if (id == null) {
                    id = optString(ql, "id");
                }
                if (version == null) {
                    version = optString(ql, "version");
                }
            }
            if (obj.has("minecraft") && obj.get("minecraft").isJsonObject()) {
                JsonObject mc = obj.getAsJsonObject("minecraft");
                String qEnv = optString(mc, "environment");
                if (qEnv != null) {
                    environment = qEnv;
                }
            }
            if (id == null || id.isBlank()) {
                return null;
            }
            return new ModInfo(id, version == null ? "?" : version, parseSide(environment), source,
                    parseJsonKeys(obj, "depends", "quilt_loader"),
                    parseJsonProvides(obj, "provides", "quilt_loader"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── Forge / NeoForge TOML ─────────────────────────────────────────────

    /**
     * 手写解析而非引入 TOML 库：我们只需要四个字段，而多引一个依赖会
     * 增加启动期类加载失败的风险（locator 阶段的类路径很窄）。
     *
     * <p>侧别来自 {@code [[dependencies.<modid>]]} 段里 {@code modId} 为
     * minecraft/forge/neoforge 的那一条的 {@code side}。
     */
    private static ModInfo fromToml(ZipFile zip, String entryName, String source) {
        String text = readEntry(zip, entryName);
        if (text == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(text))) {
            String modId = null;
            String version = null;
            Side side = Side.UNIVERSAL;

            boolean inModsBlock = false;
            boolean inDependenciesBlock = false;
            String depModId = null;
            String depSide = null;
            boolean depIsPlatform = false;

            java.util.Set<String> tomlDependencies = new java.util.LinkedHashSet<>();
            java.util.Set<String> tomlProvides = new java.util.LinkedHashSet<>();

            String line;
            while ((line = reader.readLine()) != null) {
                String t = stripComment(line).trim();
                if (t.isEmpty()) {
                    continue;
                }
                // 段头
                if (t.startsWith("[[")) {
                    // 进入新段前，结算上一段累积的依赖信息
                    if (inDependenciesBlock && depIsPlatform && depSide != null) {
                        side = parseSide(depSide);
                    }
                    inDependenciesBlock = t.startsWith("[[dependencies.");
                    inModsBlock = t.equals("[[mods]]");
                    depModId = null;
                    depSide = null;
                    depIsPlatform = false;
                    continue;
                }
                if (inModsBlock) {
                    if (modId == null) {
                        modId = valueOf(t, "modId");
                    }
                    String prov = valueOf(t, "provides");
                    if (prov != null && !prov.isBlank()) {
                        for (String piece : prov.split("[,\\s]+")) {
                            if (!piece.isBlank()) {
                                tomlProvides.add(piece.trim());
                            }
                        }
                    }
                    String v = valueOf(t, "version");
                    if (v != null) {
                        version = v;
                    }
                } else if (inDependenciesBlock) {
                    String mid = valueOf(t, "modId");
                    if (mid != null) {
                        depModId = mid;
                        String lower = mid.toLowerCase(Locale.ROOT);
                        depIsPlatform = lower.equals("minecraft") || lower.equals("forge") || lower.equals("neoforge");
                        // 平台自身不算"mod 依赖"，剔除重复 mod 时不该因为它而保留某个 mod。
                        if (!depIsPlatform && !lower.equals("java")) {
                            tomlDependencies.add(mid);
                        }
                    }
                    String s = valueOf(t, "side");
                    if (s != null) {
                        depSide = s;
                    }
                }
            }
            // 文件末尾仍是依赖段时也要结算
            if (inDependenciesBlock && depIsPlatform && depSide != null) {
                side = parseSide(depSide);
            }
            // 有些 mod 把 side 直接写在 [[mods]] 里（非标准但存在）
            if (side == Side.UNIVERSAL && modId == null) {
                return null;
            }
            if (modId == null || modId.isBlank()) {
                return null;
            }
            return new ModInfo(modId, version == null ? "?" : version, side, source,
                    tomlDependencies, tomlProvides);
        } catch (IOException e) {
            return null;
        }
    }

    /** 去掉行尾注释（只处理不在引号内的 #）。 */
    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 从 {@code key = "value"} 或 {@code key="value"} 里取值。 */
    private static String valueOf(String line, String key) {
        int eq = line.indexOf('=');
        if (eq < 0) {
            return null;
        }
        String left = line.substring(0, eq).trim();
        if (!left.equals(key)) {
            return null;
        }
        String right = line.substring(eq + 1).trim();
        if (right.startsWith("\"")) {
            int end = right.indexOf('"', 1);
            if (end > 0) {
                return right.substring(1, end);
            }
        }
        if (right.startsWith("'")) {
            int end = right.indexOf('\'', 1);
            if (end > 0) {
                return right.substring(1, end);
            }
        }
        int comma = right.indexOf(',');
        return (comma >= 0 ? right.substring(0, comma) : right).trim();
    }

    private static Side parseSide(String raw) {
        if (raw == null) {
            return Side.UNIVERSAL;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "client":
                return Side.CLIENT;
            case "server":
                return Side.SERVER;
            default:
                // "*" 与 "both" 都是双端。
                return Side.UNIVERSAL;
        }
    }

    // ── 读条目 ────────────────────────────────────────────────────────────

    private static String readEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            // 元数据文件很小，限长读取避免被畸形 jar 拖垮。
            byte[] buf = in.readNBytes(1 << 20);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 {@code depends} 对象里取依赖 id 集合（Fabric），支持 Quilt 的 {@code quilt_loader.depends}。 */
    private static java.util.Set<String> parseJsonKeys(JsonObject obj, String key, String nested) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collectDependencyKeys(obj, key, out);
        if (obj.has(nested) && obj.get(nested).isJsonObject()) {
            collectDependencyKeys(obj.getAsJsonObject(nested), key, out);
        }
        return out;
    }

    private static void collectDependencyKeys(JsonObject holder, String key, java.util.Set<String> out) {
        if (holder == null || !holder.has(key) || !holder.get(key).isJsonObject()) {
            return;
        }
        for (String dep : holder.getAsJsonObject(key).keySet()) {
            // minecraft/java 是平台自身，不算 mod 依赖。
            if (!dep.equals("minecraft") && !dep.equals("java") && !dep.equals("fabricloader")) {
                out.add(dep);
            }
        }
    }

    /** 取 provides 别名（Fabric 的字符串数组 / Quilt 的对象数组）。 */
    private static java.util.Set<String> parseJsonProvides(JsonObject obj, String key, String nested) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collectProvides(obj, key, out);
        if (obj.has(nested) && obj.get(nested).isJsonObject()) {
            collectProvides(obj.getAsJsonObject(nested), key, out);
        }
        return out;
    }

    private static void collectProvides(JsonObject holder, String key, java.util.Set<String> out) {
        if (holder == null || !holder.has(key) || !holder.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement el : holder.getAsJsonArray(key)) {
            if (el == null || el.isJsonNull()) {
                continue;
            }
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            } else if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                JsonElement id = el.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive()) {
                    out.add(id.getAsString());
                }
            }
        }
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
