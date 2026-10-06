package com.dsh.packsync.core.transfer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth 公共源：用 SHA-1 批量反查文件直链。
 *
 * <p>端点：{@code POST https://api.modrinth.com/v2/version_files}
 * body {@code {"hashes":[...],"algorithm":"sha1"}}，
 * 响应是 {@code { "<sha1>": {version 对象} }} 的映射。
 *
 * <p>整批只发一次请求 —— 整合包动辄几百个文件，逐个查会慢到不可用。
 * 任一请求失败都返回已累积结果而不是抛异常：匹配不到只是"少了一条加速路径"，
 * 不该让整个同步失败（可回退服务端通道）。
 */
public final class ModrinthSource implements PublicSource {

    private static final String DEFAULT_BASE = "https://api.modrinth.com/v2";

    private final String baseUrl;

    public ModrinthSource() {
        this(DEFAULT_BASE);
    }

    /** 允许自定义镜像（国内访问官方 API 常常很慢）。 */
    public ModrinthSource(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return "modrinth";
    }

    @Override
    public boolean isAvailable() {
        return true; // 不需要密钥
    }

    /**
     * 批量反查。返回 {@code sha1 -> 直链}（未匹配到的文件不出现在结果里）。
     */
    public java.util.Map<String, String> resolveBatch(List<String> sha1List) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (sha1List == null || sha1List.isEmpty()) {
            return out;
        }
        // 分批：单请求体过大容易被拒。
        int batch = 200;
        for (int i = 0; i < sha1List.size(); i += batch) {
            List<String> slice = sha1List.subList(i, Math.min(sha1List.size(), i + batch));
            out.putAll(resolveOneBatch(slice));
        }
        return out;
    }

    private java.util.Map<String, String> resolveOneBatch(List<String> sha1List) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            JsonObject body = new JsonObject();
            JsonArray hashes = new JsonArray();
            sha1List.forEach(hashes::add);
            body.add("hashes", hashes);
            body.addProperty("algorithm", "sha1");

            String json = post(baseUrl + "/version_files", body.toString());
            if (json == null) {
                return out;
            }
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return out;
            }
            for (var entry : root.getAsJsonObject().entrySet()) {
                String sha1 = entry.getKey();
                JsonElement v = entry.getValue();
                if (v == null || !v.isJsonObject()) {
                    continue;
                }
                JsonObject version = v.getAsJsonObject();
                if (!version.has("files") || !version.get("files").isJsonArray()) {
                    continue;
                }
                JsonArray files = version.getAsJsonArray("files");
                if (files.isEmpty()) {
                    continue;
                }
                JsonElement first = files.get(0);
                if (first == null || !first.isJsonObject()) {
                    continue;
                }
                JsonObject file = first.getAsJsonObject();
                if (file.has("url") && !file.get("url").isJsonNull()) {
                    out.put(sha1.toLowerCase(java.util.Locale.ROOT), file.get("url").getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[PackSync] Modrinth 查询失败（将回退服务端下载）：" + e);
        }
        return out;
    }

    @Override
    public List<String> resolve(String sha1, String murmur) {
        java.util.Map<String, String> m = resolveBatch(List.of(sha1));
        String url = m.get(sha1 == null ? "" : sha1.toLowerCase(java.util.Locale.ROOT));
        List<String> out = new ArrayList<>(1);
        if (url != null && !url.isBlank()) {
            out.add(url);
        }
        return out;
    }

    private static String post(String url, String body) throws IOException {
        URL u = URI.create(url).toURL();
        String host = u.getHost();
        String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();

        // ── 优先 IPv4 直连 ────────────────────────────────────────────────
        // 启动器（PCL 等）常给 MC 加 -Djava.net.preferIPv6Addresses=system，
        // 让 Java 优先解析 IPv6。在 IPv6 出海链路被干扰的网络上，这会让公共站
        // 查询**全部**失败（TLS 被中间设备顶掉），玩家看到"一个都没匹配上"。
        // 详见 Ipv4Http 的类注释与实测数据（同机带该参数 HTTP 0、不带 HTTP 200）。
        try {
            return Ipv4Http.post(host, path, body, 8000, 15000);
        } catch (IOException e) {
            // 所在环境若只有 IPv6，IPv4 直连本就会失败 —— 此时回退默认实现，
            // 免得"为了修 IPv6 问题"反而把正常环境弄坏。
            System.err.println("[PackSync] Modrinth IPv4 直连失败，回退默认 HTTP：" + e);
        }
        return postDefault(url, body);
    }

    /** 默认实现（走 JVM 自己的地址解析策略）。 */
    private static String postDefault(String url, String body) throws IOException {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "PackSync/1.0 (github/dsh/packsync)");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            System.err.println("[PackSync] Modrinth 返回 " + code);
            conn.disconnect();
            return null;
        }
        try (InputStream in = conn.getInputStream()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            return s;
        }
    }
}
