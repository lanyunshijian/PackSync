package com.dsh.packsync.core.transfer;

import com.dsh.packsync.core.keys.KeyManager;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.crypto.Bytes;
import com.dsh.packsync.crypto.Ed25519Identity;
import com.dsh.packsync.crypto.Handshake;
import com.dsh.packsync.crypto.SecureSession;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端整合包分发服务。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver}（零第三方依赖 —— 启动期 locator
 * 的类路径很窄，能少一个依赖就少一分风险），在自定义协议之上套一层加密会话。
 *
 * <p>端点（前缀 {@code /packsync/v1}）：
 * <pre>
 * GET  /info                     明文：服务器名、MC/加载器版本、文件数、身份指纹、是否需要 PSK
 * POST /handshake   body=ClientHello  → ServerHello，响应头带 X-PackSync-Session
 * POST /auth        body=ClientAuth   → 建立加密会话
 * GET  /manifest                 加密的整合包清单（JSON）
 * GET  /file?sha1=&lt;hash&gt;       加密的文件流（按 SHA-1 定位）
 * </pre>
 *
 * <p>为什么先有明文 /info：客户端需要在校验身份**之前**知道"这是不是我要连的那台服务器"，
 * 以及要不要弹指纹核对界面。指纹走明文是为了让客户端能自行比对，
 * 而真正的数据一律在握手后的加密会话里。
 */
public final class PackServer {

    public static final String PREFIX = "/packsync/v1";

    /** 加密上下文标签：把不同用途的密文彼此隔离，挪包会直接解密失败。 */
    static final String CTX_MANIFEST = "packsync/http/manifest";
    static final String CTX_READY = "packsync/http/ready";
    static final String CTX_FILE_CHUNK = "packsync/http/file-chunk";
    /** 自我更新包的加密上下文。 */
    static final String CTX_SELF = "packsync/http/self";

    /** 单次请求体上限（握手包很小）。 */
    private static final int MAX_BODY = 16 * 1024;
    /** 文件分块大小：与协议默认块一致，便于限速与断点。 */
    private static final int CHUNK = 256 * 1024;
    /** 未完成握手 / 会话的空闲过期时间。 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    /** 文件哈希 → 磁盘路径。生成清单后由外部注入。 */
    private final Map<String, Path> filesByHash = new ConcurrentHashMap<>();
    private final Map<String, Path> filesByPath = new ConcurrentHashMap<>();

    private final KeyManager keys;
    private final SecureRandom random = new SecureRandom();

    private volatile PackManifest manifest = new PackManifest();
    /** 本服务端所用的 PackSync jar，用于客户端的自我更新。 */
    private volatile Path selfJar;
    private volatile String serverName = "PackSync Server";
    private volatile boolean authRequired = true;

    private volatile HttpServer http;
    private volatile int boundPort = -1;

    /** 握手进行中的会话（ClientHello 之后、ClientAuth 之前）。 */
    private final Map<String, PendingHandshake> pending = new ConcurrentHashMap<>();
    /** 已完成握手的会话。 */
    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    public PackServer(KeyManager keys) {
        this.keys = keys;
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /** 启动服务；返回实际绑定的端口，失败返回 -1。 */
    public synchronized int start(int port) {
        if (http != null) {
            return boundPort;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 16);
            server.createContext(PREFIX, this::dispatch);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "PackSync-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            this.http = server;
            this.boundPort = server.getAddress().getPort();
            System.out.println("[PackSync] 整合包分发服务已启动：http://0.0.0.0:" + boundPort + PREFIX);
            System.out.println("[PackSync] 服务器指纹：" + keys.fingerprint());
            return boundPort;
        } catch (IOException e) {
            System.err.println("[PackSync] 启动分发服务失败：" + e);
            return -1;
        }
    }

    public synchronized void stop() {
        HttpServer server = http;
        http = null;
        boundPort = -1;
        if (server != null) {
            server.stop(0);
        }
        pending.clear();
        sessions.clear();
    }

    public boolean isRunning() {
        return http != null;
    }

    public int port() {
        return boundPort;
    }

    // ── 内容注册 ──────────────────────────────────────────────────────────

    /** 发布清单，并建立 哈希→文件 索引。 */
    public void publish(PackManifest manifest, Path modpackRoot) {
        this.manifest = manifest == null ? new PackManifest() : manifest;
        filesByHash.clear();
        filesByPath.clear();
        for (PackManifest.PackFile f : this.manifest.files()) {
            if (f == null || f.sha1 == null || f.sha1.isBlank() || f.path == null) {
                continue;
            }
            Path real = resolveOnDisk(modpackRoot, f.path);
            if (real != null && Files.isRegularFile(real)) {
                filesByHash.put(f.sha1.toLowerCase(java.util.Locale.ROOT), real);
                filesByPath.put(f.path, real);
            } else {
                System.err.println("[PackSync] 清单条目在磁盘上找不到，跳过：" + f.path);
            }
        }
        System.out.println("[PackSync] 已发布整合包：" + this.manifest.files().size()
                + " 个文件，可分发 " + filesByHash.size() + " 个");
    }

    /**
     * 把清单里的客户端相对路径还原成服务端磁盘路径。
     * 两个来源：host/main 优先，其次服务端根目录。
     */
    private Path resolveOnDisk(Path hostMainOrNull, String clientPath) {
        String rel = clientPath.startsWith("/") ? clientPath.substring(1) : clientPath;
        if (hostMainOrNull != null) {
            Path p = hostMainOrNull.resolve(rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** 额外注册一个来源目录下的文件（用于 syncedFiles 命中的服务端根目录文件）。 */
    public void registerFile(String clientPath, Path real) {
        if (clientPath == null || real == null || !Files.isRegularFile(real)) {
            return;
        }
        PackManifest.PackFile f = manifest.find(clientPath);
        if (f != null && f.sha1 != null) {
            filesByHash.put(f.sha1.toLowerCase(java.util.Locale.ROOT), real);
        }
        filesByPath.put(clientPath, real);
    }

    /** 注册本服务端的 PackSync jar；客户端版本不一致时可从这里取到一致的版本。 */
    public void setSelfJar(Path jar) {
        this.selfJar = (jar != null && Files.isRegularFile(jar)) ? jar : null;
        if (this.selfJar != null) {
            System.out.println("[PackSync] 自我更新源已就绪：" + this.selfJar.getFileName());
        }
    }

    public void setServerName(String name) {
        this.serverName = name == null ? "PackSync Server" : name;
    }

    public void setAuthRequired(boolean required) {
        this.authRequired = required;
    }

    public PackManifest manifest() {
        return manifest;
    }

    /** 当前活跃会话数（用于 /packsync status 与运维观察）。 */
    public int activeSessions() {
        return sessions.size();
    }

    /** 握手进行中的会话数（连上了但还没认证完）。 */
    public int pendingHandshakes() {
        return pending.size();
    }

    /** 已发布的可分发文件数（按哈希索引去重后的数量）。 */
    public int hostedFileCount() {
        return filesByHash.size();
    }

    /**
     * 自我更新端点：把本服务端所用的 PackSync jar 发给客户端。
     *
     * <p>为什么不让客户端去 Modrinth 拿：PackSync 是自建 mod，公共站上没有。
     * 真正能保证"两端版本一致"的来源只有服务端自己。
     */
    private void handleSelf(HttpExchange ex) throws IOException {
        ActiveSession s = requireSession(ex);
        if (s == null) {
            return;
        }
        Path jar = selfJar;
        if (jar == null) {
            sendText(ex, 404, "服务端未提供自我更新包");
            return;
        }
        byte[] raw = Files.readAllBytes(jar);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("X-PackSync-Version", manifest.packsyncVersion == null ? "" : manifest.packsyncVersion);
        h.set("X-PackSync-Filename", jar.getFileName().toString());
        sendBytes(ex, 200, s.session.encrypt(CTX_SELF, raw));
    }

    // ── 路由 ──────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            purgeExpired();
            String path = ex.getRequestURI().getPath();
            String sub = path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "";
            switch (sub) {
                case "/info" -> handleInfo(ex);
                case "/handshake" -> handleHandshake(ex);
                case "/auth" -> handleAuth(ex);
                case "/manifest" -> handleManifest(ex);
                case "/file" -> handleFile(ex);
                case "/self" -> handleSelf(ex);
                default -> sendText(ex, 404, "not found");
            }
        } catch (Throwable t) {
            // 任何异常都不能把服务打挂；返回 500 并记录。
            System.err.println("[PackSync] 处理请求出错 " + ex.getRequestURI() + " -> " + t);
            try {
                sendText(ex, 500, "internal error");
            } catch (IOException ignored) {
                // 连接已断开。
            }
        } finally {
            ex.close();
        }
    }

    private void handleInfo(HttpExchange ex) throws IOException {
        Ed25519Identity id = keys.loadOrCreateIdentity();
        String psk = authRequired ? keys.loadPsk() : null;
        String json = "{"
                + "\"protocol\":1,"
                + "\"serverName\":\"" + escape(serverName) + "\","
                + "\"modpackName\":\"" + escape(manifest.modpackName) + "\","
                + "\"mcVersion\":\"" + escape(manifest.mcVersion) + "\","
                + "\"loader\":\"" + escape(manifest.loader) + "\","
                + "\"loaderVersion\":\"" + escape(manifest.loaderVersion) + "\","
                + "\"fileCount\":" + manifest.files().size() + ","
                + "\"totalBytes\":" + manifest.totalBytes() + ","
                + "\"fingerprint\":\"" + id.fingerprint() + "\","
                + "\"pskRequired\":" + (psk != null && !psk.isBlank())
                + "}";
        sendJson(ex, 200, json);
    }

    private void handleHandshake(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body.length == 0) {
            sendText(ex, 400, "empty client hello");
            return;
        }
        try {
            Ed25519Identity id = keys.loadOrCreateIdentity();
            byte[] psk = authRequired ? pskBytes(keys.loadPsk()) : null;
            Handshake.ServerSide server = new Handshake.ServerSide(id, psk, body);
            byte[] hello = server.buildServerHello();

            String sid = newSessionId();
            pending.put(sid, new PendingHandshake(server, System.currentTimeMillis()));

            Headers h = ex.getResponseHeaders();
            h.set("X-PackSync-Session", sid);
            h.set("Content-Type", "application/octet-stream");
            sendBytes(ex, 200, hello);
        } catch (Handshake.HandshakeException e) {
            sendText(ex, 400, "handshake rejected: " + e.getMessage());
        }
    }

    private void handleAuth(HttpExchange ex) throws IOException {
        String sid = header(ex, "X-PackSync-Session");
        PendingHandshake ph = sid == null ? null : pending.remove(sid);
        if (ph == null) {
            sendText(ex, 409, "no pending handshake");
            return;
        }
        byte[] body = readBody(ex);
        try {
            Handshake.ServerResult result = ph.server.finish(body);
            SecureSession session = result.session();
            sessions.put(sid, new ActiveSession(session, System.currentTimeMillis()));
            byte[] ready = session.encrypt(CTX_READY, Bytes.utf8("ready"));
            sendBytes(ex, 200, ready);
        } catch (Handshake.HandshakeException e) {
            sendText(ex, 403, "auth failed: " + e.getMessage());
        }
    }

    private void handleManifest(HttpExchange ex) throws IOException {
        ActiveSession s = requireSession(ex);
        if (s == null) {
            return;
        }
        byte[] json = Bytes.utf8(com.dsh.packsync.core.config.ConfigIO.toCompactJson(manifest));
        sendBytes(ex, 200, s.session.encrypt(CTX_MANIFEST, json));
    }

    private void handleFile(HttpExchange ex) throws IOException {
        ActiveSession s = requireSession(ex);
        if (s == null) {
            return;
        }
        String sha1 = queryParam(ex, "sha1");
        if (sha1 == null || sha1.isBlank()) {
            sendText(ex, 400, "missing sha1");
            return;
        }
        Path file = filesByHash.get(sha1.toLowerCase(java.util.Locale.ROOT));
        if (file == null || !Files.isRegularFile(file)) {
            sendText(ex, 404, "no such file");
            return;
        }

        long size = Files.size(file);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/octet-stream");
        h.set("X-PackSync-Size", String.valueOf(size));
        h.set("X-PackSync-Chunk", String.valueOf(CHUNK));
        ex.sendResponseHeaders(200, 0);

        // 分块读取 → 逐块独立加密 → 写成一个连续的流。
        // 每块的密文自带长度前缀，客户端据此切分。
        try (OutputStream out = ex.getResponseBody();
             var in = Files.newInputStream(file)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                byte[] sealed = s.session.encrypt(CTX_FILE_CHUNK, chunk);
                out.write(Bytes.u32(sealed.length));
                out.write(sealed);
            }
            out.flush();
        }
    }

    // ── 会话 ──────────────────────────────────────────────────────────────

    private ActiveSession requireSession(HttpExchange ex) throws IOException {
        String sid = header(ex, "X-PackSync-Session");
        ActiveSession s = sid == null ? null : sessions.get(sid);
        if (s == null) {
            sendText(ex, 401, "unauthorized");
            return null;
        }
        s.touch();
        return s;
    }

    private String newSessionId() {
        byte[] raw = Bytes.random(24);
        return Bytes.hex(raw);
    }

    private static byte[] pskBytes(String psk) {
        if (psk == null || psk.isBlank()) {
            return null;
        }
        // PSK 按文本参与密钥派生（与客户端填入的内容一致即可）。
        return Bytes.utf8(psk.trim());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().createdAt > TTL_MS);
        sessions.entrySet().removeIf(e -> now - e.getValue().lastUsed > TTL_MS);
    }

    // ── HTTP 小工具 ───────────────────────────────────────────────────────

    private static byte[] readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            throw new IOException("request body too large");
        }
        return raw;
    }

    private static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        sendBytes(ex, code, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        sendBytes(ex, code, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── 内部类型 ──────────────────────────────────────────────────────────

    private record PendingHandshake(Handshake.ServerSide server, long createdAt) {
    }

    private static final class ActiveSession {
        final SecureSession session;
        volatile long lastUsed;

        ActiveSession(SecureSession session, long now) {
            this.session = session;
            this.lastUsed = now;
        }

        void touch() {
            this.lastUsed = System.currentTimeMillis();
        }
    }
}
