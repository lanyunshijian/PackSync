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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CurseForge 公共源：用 murmur 指纹批量反查文件直链。
 *
 * <p>端点 {@code POST https://api.curseforge.com/v1/fingerprints}，
 * body {@code {"fingerprints":[murmur...]}}，需要 {@code x-api-key} 头。
 *
 * <p><b>API Key 的端点白名单</b>：请求前强制校验目标必须是
 * https + {@code api.curseforge.com} + 默认端口 + 不含 userInfo。
 * 这不是多余的谨慎 —— Key 一旦被发到别处就等于泄露，
 * 而 URL 在本类里是可配置的。
 *
 * <p>未配置 Key 时 {@link #isAvailable()} 返回 false，上层会跳过该源
 * （而不是报错）：CurseForge 只是可选的加速路径。
 */
public final class CurseForgeSource implements PublicSource {

    private static final String DEFAULT_BASE = "https://api.curseforge.com/v1";

    /** classId 对应的 releaseType 数字映射。 */
    private static String releaseTypeName(int t) {
        return switch (t) {
            case 1 -> "release";
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> null;
        };
    }

    private final String apiKey;
    private final String baseUrl;

    public CurseForgeSource(String apiKey) {
        this(apiKey, DEFAULT_BASE);
    }

    public CurseForgeSource(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return "curseforge";
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    /**
     * 批量反查。返回 {@code sha1 -> 直链}。
     *
     * @param sha1ToMurmur SHA-1 → murmur 的映射（CurseForge 只认 murmur）
     */
    public Map<String, String> resolveBatch(Map<String, String> sha1ToMurmur) {
        Map<String, String> out = new HashMap<>();
        if (sha1ToMurmur == null || sha1ToMurmur.isEmpty() || !isAvailable()) {
            return out;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(sha1ToMurmur.entrySet());
        int batch = 100;
        for (int i = 0; i < entries.size(); i += batch) {
            List<Map.Entry<String, String>> slice = entries.subList(i, Math.min(entries.size(), i + batch));
            out.putAll(resolveOneBatch(slice));
        }
        return out;
    }

    private Map<String, String> resolveOneBatch(List<Map.Entry<String, String>> slice) {
        Map<String, String> out = new HashMap<>();
        // murmur → sha1 的反查表：响应里只给 SHA-1，要靠它找回我们关心的键。
        Map<String, String> murmurToSha1 = new HashMap<>();
        JsonArray fingerprints = new JsonArray();
        for (Map.Entry<String, String> e : slice) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            murmurToSha1.put(e.getValue(), e.getKey().toLowerCase(Locale.ROOT));
            try {
                fingerprints.add(Long.parseLong(e.getValue()));
            } catch (NumberFormatException ignored) {
                // murmur 是无符号 32 位十进制；超范围的值只能跳过。
            }
        }
        if (fingerprints.isEmpty()) {
            return out;
        }
        try {
            JsonObject body = new JsonObject();
            body.add("fingerprints", fingerprints);
            String json = post(baseUrl + "/fingerprints", body.toString());
            if (json == null) {
                return out;
            }
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonObject rootObj = root.getAsJsonObject();
            JsonObject data = (rootObj.has("data") && rootObj.get("data").isJsonObject())
                    ? rootObj.getAsJsonObject("data") : null;
            if (data == null || !data.has("exactMatches")) {
                return out;
            }
            for (JsonElement m : data.getAsJsonArray("exactMatches")) {
                if (m == null || !m.isJsonObject()) {
                    continue;
                }
                JsonObject match = m.getAsJsonObject();
                if (!match.has("file") || !match.get("file").isJsonObject()) {
                    continue;
                }
                JsonObject file = match.getAsJsonObject("file");

                // 从 file.hashes 里找 SHA-1（algo == 1），再回填到 SHA-1 键上。
                String sha1 = null;
                if (file.has("hashes") && file.get("hashes").isJsonArray()) {
                    for (JsonElement h : file.getAsJsonArray("hashes")) {
                        if (h == null || !h.isJsonObject()) {
                            continue;
                        }
                        JsonObject hash = h.getAsJsonObject();
                        if (hash.has("algo") && hash.get("algo").getAsInt() == 1
                                && hash.has("value") && !hash.get("value").isJsonNull()) {
                            sha1 = hash.get("value").getAsString().toLowerCase(Locale.ROOT);
                            break;
                        }
                    }
                }
                if (sha1 == null) {
                    continue;
                }
                if (file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull()) {
                    out.put(sha1, file.get("downloadUrl").getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[PackSync] CurseForge 查询失败（将回退服务端下载）：" + e);
        }
        return out;
    }

    @Override
    public List<String> resolve(String sha1, String murmur) {
        if (murmur == null || murmur.isBlank()) {
            return List.of();
        }
        Map<String, String> m = resolveBatch(Map.of(sha1, murmur));
        String url = m.get(sha1 == null ? "" : sha1.toLowerCase(Locale.ROOT));
        return url == null ? List.of() : List.of(url);
    }

    private String post(String url, String body) throws IOException {
        URL u = URI.create(url).toURL();

        // ★ 端点白名单：API Key 只允许发往官方端点。
        if (!"https".equalsIgnoreCase(u.getProtocol())
                || !"api.curseforge.com".equalsIgnoreCase(u.getHost())
                || u.getPort() != -1
                || u.getUserInfo() != null) {
            throw new IOException("拒绝把 CurseForge API Key 发往非官方端点：" + u);
        }

        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("User-Agent", "PackSync/1.0 (github/dsh/packsync)");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            System.err.println("[PackSync] CurseForge 返回 " + code + "（检查 API Key）");
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
