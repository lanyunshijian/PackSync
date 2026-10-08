package com.dsh.packsync.core.transfer;

import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.util.ServerAddressParser;
import com.dsh.packsync.crypto.AesGcm;
import com.dsh.packsync.crypto.Bytes;
import com.dsh.packsync.crypto.Ed25519Identity;
import com.dsh.packsync.crypto.Handshake;
import com.dsh.packsync.crypto.SecureSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

/**
 * PackSync 客户端：向服务端取清单、下载文件。
 *
 * <p>握手流程（三次消息，两次 HTTP 往返）：
 * <pre>
 * POST /handshake  body=ClientHello  → 响应头 X-PackSync-Session + body=ServerHello
 *                                   → 本地校验服务端指纹
 * POST /auth       body=ClientAuth   → 建立加密会话
 * GET  /manifest                     → 加密清单
 * GET  /file?sha1=                   → 加密文件流
 * </pre>
 *
 * <p>身份校验采用 TOFU（首次信任）：首次连接返回 {@code firstContact=true}，
 * 由上层决定是否让玩家确认指纹；记住之后每次连接都会比对，指纹变了会抛
 * {@link IdentityChangedException}，绝不会静默接受。
 */
public final class PackClient implements AutoCloseable {

    /** 服务端信息（明文端点返回）。 */
    public record ServerInfo(String serverName, String modpackName, String mcVersion, String loader,
                             String loaderVersion, int fileCount, long totalBytes,
                             String fingerprint, boolean pskRequired) {

        public static ServerInfo parse(String json) {
            return ConfigIO.fromJson(json, ServerInfo.class);
        }
    }

    /** 服务端身份与上次记录不一致 —— 可能是换了服务器，也可能是中间人。 */
    public static class IdentityChangedException extends IOException {
        public final String expected;
        public final String actual;

        public IdentityChangedException(String expected, String actual) {
            super("服务器身份已改变：期望 " + expected + "，实际 " + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public IdentityChangedException(String message) {
            super(message);
            this.expected = "";
            this.actual = "";
        }
    }

    /** 服务端要求 PSK 而本机没有配置。 */
    public static class PskRequiredException extends IOException {
        public PskRequiredException() {
            super("服务器要求预共享密钥（PSK），但本机未配置");
        }
    }

    private final String baseUrl;
    private final byte[] psk;
    private final Ed25519Identity clientIdentity;
    private final String expectedFingerprint;

    private String sessionId;
    private SecureSession session;
    private Handshake.ServerInfo handshakeInfo;

    public PackClient(String host, int port, byte[] psk, Ed25519Identity clientIdentity,
                      String expectedFingerprint) {
        // ★ 必须走 urlHost：IPv6 字面量在 URL 里要写成 [addr]，
        //   直接 "http://" + host + ":" + port 会拼出非法 URL
        //   （http://2409:...:88e8:25566/...）并抛 MalformedURLException。
        this.baseUrl = "http://" + ServerAddressParser.urlHost(host) + ":" + port + PackServer.PREFIX;
        this.psk = psk;
        this.clientIdentity = clientIdentity;
        this.expectedFingerprint = (expectedFingerprint == null || expectedFingerprint.isBlank())
                ? null : expectedFingerprint;
    }

    // ── 明文 info ─────────────────────────────────────────────────────────

    /** 取服务端公开信息（不需要握手）。用于"连接前先看看这是哪台服务器"。 */
    public static ServerInfo fetchInfo(String host, int port, int timeoutMs) throws IOException {
        URL url = URI.create("http://" + ServerAddressParser.urlHost(host) + ":" + port
                + PackServer.PREFIX + "/info").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("服务器返回 " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                ServerInfo info = ServerInfo.parse(json);
                if (info == null) {
                    throw new IOException("无法解析服务器信息");
                }
                return info;
            }
        } finally {
            conn.disconnect();
        }
    }

    // ── 握手 ──────────────────────────────────────────────────────────────

    /**
     * 完成握手并建立加密会话。
     *
     * @throws IdentityChangedException 服务端指纹与记录不符
     * @throws PskRequiredException     服务端要求 PSK 而本机没配
     */
    public Handshake.ServerInfo handshake(String clientName) throws IOException {
        Handshake.ClientSide client = new Handshake.ClientSide(psk, clientIdentity, expectedFingerprint);
        byte[] clientHello = client.buildClientHello(clientName);

        HttpResult hello = post("/handshake", null, clientHello);
        if (hello.code != 200) {
            throw new IOException("握手被拒绝（" + hello.code + "）："
                    + new String(hello.body, StandardCharsets.UTF_8));
        }
        String sid = hello.sessionId;
        if (sid == null || sid.isBlank()) {
            throw new IOException("服务端没有返回会话 ID");
        }

        Handshake.ServerInfo info;
        try {
            info = client.handleServerHello(hello.body);
        } catch (Handshake.PskRequiredException e) {
            throw new PskRequiredException();
        } catch (Handshake.ServerIdentityChangedException e) {
            // 握手层已经判定"这台服务器不是我记得的那台"。必须原样上抛成明确类型，
            // 不能退化成普通 IOException —— 上层要据此决定是否让玩家重新确认指纹。
            // 注意把【实际指纹】一起带出去：核对界面靠它做比对，
            // 只传 message 会让界面拿不到值，玩家被卡在核对界面上。
            throw new IdentityChangedException(expectedFingerprint, e.actual);
        } catch (Handshake.HandshakeException e) {
            throw new IOException("处理 ServerHello 失败：" + e.getMessage(), e);
        }

        // 身份比对：记过指纹就必须一致；首次接触由上层决定是否信任。
        if (expectedFingerprint != null && !expectedFingerprint.equals(info.fingerprint())) {
            throw new IdentityChangedException(expectedFingerprint, info.fingerprint());
        }

        byte[] clientAuth = client.buildClientAuth();
        HttpResult auth = post("/auth", sid, clientAuth);
        if (auth.code != 200) {
            throw new IOException("认证失败（" + auth.code + "）："
                    + new String(auth.body, StandardCharsets.UTF_8));
        }

        this.sessionId = sid;
        this.session = client.session();
        this.handshakeInfo = info;
        if (session == null) {
            throw new IOException("握手完成但会话未建立");
        }

        // 服务端在 /auth 的响应体里回了一条加密的 "ready"。
        // 必须把它解出来：SecureSession 要求接收序号严格连续，
        // 漏解这一条会让后续每一条消息都因"序号不连续"被拒。
        try {
            session.decrypt(PackServer.CTX_READY, auth.body);
        } catch (AesGcm.AuthenticationException e) {
            // 解不开说明会话密钥不一致，绝不能带着这个会话继续用。
            this.session = null;
            this.sessionId = null;
            throw new IOException("会话建立确认失败（密钥不一致？）", e);
        }
        return info;
    }

    public Handshake.ServerInfo handshakeInfo() {
        return handshakeInfo;
    }

    public boolean isReady() {
        return session != null;
    }

    // ── 取清单 ────────────────────────────────────────────────────────────

    public PackManifest fetchManifest() throws IOException {
        requireSession();
        HttpResult res = get("/manifest", sessionId);
        if (res.code != 200) {
            throw new IOException("取清单失败（" + res.code + "）");
        }
        byte[] plain;
        try {
            plain = session.decrypt(PackServer.CTX_MANIFEST, res.body);
        } catch (AesGcm.AuthenticationException e) {
            throw new IOException("清单解密失败（可能被篡改）", e);
        }
        PackManifest manifest = ConfigIO.fromJson(new String(plain, StandardCharsets.UTF_8), PackManifest.class);
        if (manifest == null) {
            throw new IOException("清单解析失败");
        }
        return manifest;
    }

    // ── 下载文件 ──────────────────────────────────────────────────────────

    /**
     * 分块接收回调。
     *
     * <p>刻意自定义而不是用 {@code Consumer<byte[]>}：写文件会抛 {@link IOException}，
     * 用 JDK 的 Consumer 就得在调用处包一层 try/catch，反而更容易写错。
     */
    @FunctionalInterface
    public interface ChunkSink {
        void accept(byte[] chunk) throws IOException;
    }

    /** 按 SHA-1 下载文件；返回完整字节。大文件请用带进度的重载。 */
    public byte[] downloadFile(String sha1) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        downloadFile(sha1, out::write, null);
        return out.toByteArray();
    }

    /**
     * 按 SHA-1 下载文件，边下边回调进度。
     *
     * <p>逐块解密后立刻交付：服务端每块是"4 字节长度前缀 + 密文"，
     * 任何一块解不开都会抛错而不是产出半截文件。
     */
    public void downloadFile(String sha1, ChunkSink sink, LongConsumer progress) throws IOException {
        requireSession();
        HttpResult res = get("/file?sha1=" + java.net.URLEncoder.encode(sha1, StandardCharsets.UTF_8), sessionId);
        if (res.code != 200) {
            throw new IOException("下载失败（" + res.code + "）：" + new String(res.body, StandardCharsets.UTF_8));
        }
        byte[] raw = res.body;
        int offset = 0;
        long written = 0L;
        while (offset + 4 <= raw.length) {
            int len = readInt(raw, offset);
            offset += 4;
            if (len < 0 || offset + len > raw.length) {
                throw new IOException("文件块长度非法：" + len);
            }
            byte[] sealed = new byte[len];
            System.arraycopy(raw, offset, sealed, 0, len);
            offset += len;

            byte[] chunk;
            try {
                chunk = session.decrypt(PackServer.CTX_FILE_CHUNK, sealed);
            } catch (AesGcm.AuthenticationException e) {
                throw new IOException("文件块解密失败（可能被篡改）", e);
            }
            sink.accept(chunk);
            written += chunk.length;
            if (progress != null) {
                progress.accept(written);
            }
        }
    }

    /**
     * 下载服务端所用的 PackSync jar（自我更新）。
     *
     * @return {@code {文件名, jar 字节}}；服务端未提供时返回空
     */
    public java.util.Optional<SelfPackage> downloadSelf() throws IOException {
        requireSession();
        HttpResult res = get("/self", sessionId);
        if (res.code != 200) {
            return java.util.Optional.empty();
        }
        byte[] plain;
        try {
            plain = session.decrypt(PackServer.CTX_SELF, res.body);
        } catch (AesGcm.AuthenticationException e) {
            throw new IOException("自我更新包解密失败（可能被篡改）", e);
        }
        return java.util.Optional.of(new SelfPackage(
                res.fileName == null ? "packsync-self.jar" : res.fileName,
                res.version == null ? "" : res.version,
                plain));
    }

    /** 自我更新包。 */
    public record SelfPackage(String fileName, String version, byte[] data) {
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private void requireSession() throws IOException {
        if (session == null) {
            throw new IOException("尚未完成握手");
        }
    }

    private HttpResult get(String sub, String sid) throws IOException {
        HttpURLConnection conn = open(sub, "GET");
        if (sid != null) {
            conn.setRequestProperty("X-PackSync-Session", sid);
        }
        return read(conn);
    }

    private HttpResult post(String sub, String sid, byte[] body) throws IOException {
        HttpURLConnection conn = open(sub, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        if (sid != null) {
            conn.setRequestProperty("X-PackSync-Session", sid);
        }
        conn.setFixedLengthStreamingMode(body.length);
        try (var out = conn.getOutputStream()) {
            out.write(body);
        }
        return read(conn);
    }

    private HttpURLConnection open(String sub, String method) throws IOException {
        URL url = URI.create(baseUrl + sub).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10_000);
        // 大文件下载可能很久，不设读取超时以免误杀。
        conn.setReadTimeout(0);
        conn.setRequestMethod(method);
        return conn;
    }

    private static HttpResult read(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] body = in == null ? new byte[0] : in.readAllBytes();
        if (in != null) {
            in.close();
        }
        HttpResult r = new HttpResult(code, body,
                conn.getHeaderField("X-PackSync-Session"),
                conn.getHeaderField("X-PackSync-Version"),
                conn.getHeaderField("X-PackSync-Filename"));
        conn.disconnect();
        return r;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    @Override
    public void close() {
        session = null;
        sessionId = null;
    }

    private record HttpResult(int code, byte[] body, String sessionId,
                              String version, String fileName) {
    }
}
