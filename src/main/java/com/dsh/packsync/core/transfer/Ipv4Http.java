package com.dsh.packsync.core.transfer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 强制走 IPv4 的 HTTPS POST。
 *
 * <p><b>为什么必须自己实现</b>：PCL 等启动器会给 MC 进程加上
 * {@code -Djava.net.preferIPv6Addresses=system}，让 Java 优先解析并使用 IPv6。
 * 在 IPv6 出海链路被干扰的网络上（TLS 会被中间设备顶掉，证书校验直接失败），
 * 结果是公共站查询<b>全部</b>失败 —— 玩家看到 158 个文件全被列为"未验证"，
 * 而实际上这些文件在 Modrinth 上都能查到（实测同一台机器：带这个参数 HTTP 0，
 * 不带 HTTP 200）。
 *
 * <p>那个系统属性是 {@code static final}，类加载时就定死了，运行时改不掉；
 * {@code HttpURLConnection} 也不提供"优先 IPv4"的选项。所以这里直接：
 * <ol>
 *   <li>把主机名解析成 IPv4 地址；</li>
 *   <li>自己建 {@link SSLSocket}，并用 {@link SNIHostName} 把 SNI 设成<b>主机名</b>
 *       （不是 IP）—— 这样证书校验依然对着 {@code api.modrinth.com}，安全性不降级；</li>
 *   <li>手写一个极简的 HTTP/1.1 POST，并解析响应（含 chunked）。</li>
 * </ol>
 *
 * <p>只影响 PackSync 自己的公共站查询，<b>不动全局网络设置</b>，不干扰 Minecraft
 * 与其他 mod 的连接行为。
 */
final class Ipv4Http {

    private Ipv4Http() {
    }

    /** 发起一次 HTTPS POST；返回响应体。非 200 会抛出 {@link IOException}。 */
    static String post(String host, String path, String jsonBody, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        InetAddress ipv4 = firstIpv4(host);
        if (ipv4 == null) {
            throw new IOException("找不到 " + host + " 的 IPv4 地址");
        }

        SSLContext ctx;
        try {
            ctx = SSLContext.getDefault();
        } catch (Exception e) {
            throw new IOException("无法初始化 TLS 上下文", e);
        }
        SSLSocketFactory factory = ctx.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            // SNI 必须是主机名：证书是签给 *.modrinth.com 的，用 IP 做 SNI 会校验失败。
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            socket.setSSLParameters(params);

            socket.connect(new InetSocketAddress(ipv4, 443), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.startHandshake();

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Accept: application/json\r\n"
                    + "User-Agent: PackSync/1.0 (github/dsh/packsync)\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "\r\n";

            OutputStream os = socket.getOutputStream();
            os.write(head.getBytes(StandardCharsets.US_ASCII));
            os.write(payload);
            os.flush();

            return readResponse(socket.getInputStream());
        }
    }

    /** 取第一个 IPv4 地址；没有则返回 null。 */
    private static InetAddress firstIpv4(String host) throws IOException {
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (a instanceof Inet4Address) {
                return a;
            }
        }
        return null;
    }

    /**
     * 读取并解析 HTTP 响应。
     *
     * <p>只处理我们真正会遇到的情况：{@code Content-Length} 或 {@code chunked}，
     * 以及连接关闭作为结束。不处理压缩（请求里已声明 {@code Accept-Encoding: identity}）。
     */
    private static String readResponse(InputStream rawIn) throws IOException {
        // 响应头按行读（ISO-8859-1 是 HTTP 头的既定编码）
        byte[] headerBytes = readHeaderBlock(rawIn);
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headers.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("空响应");
        }

        int status = parseStatus(lines[0]);
        boolean chunked = false;
        long contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("transfer-encoding".equals(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            } else if ("content-length".equals(name)) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // 交给后面的兜底逻辑
                }
            }
        }

        String body = chunked ? readChunked(rawIn) : readFixedOrUntilClose(rawIn, contentLength);
        if (status != 200) {
            throw new IOException("HTTP " + status + "：" + trim(body));
        }
        return body;
    }

    private static int parseStatus(String statusLine) throws IOException {
        // 形如 "HTTP/1.1 200 OK"
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("无法解析状态行：" + statusLine);
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("无法解析状态码：" + statusLine, e);
        }
    }

    /** 读到空行为止，返回整个头部块（含结尾的 CRLFCRLF）。 */
    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(512);
        int state = 0; // 匹配 \r\n\r\n 的进度
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
                if (state == 4) {
                    break;
                }
            } else {
                state = 0;
            }
            if (buf.size() > 65536) {
                throw new IOException("响应头过大");
            }
        }
        return buf.toByteArray();
    }

    private static String readFixedOrUntilClose(InputStream in, long contentLength) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        if (contentLength >= 0) {
            long remaining = contentLength;
            while (remaining > 0) {
                int n = in.read(tmp, 0, (int) Math.min(tmp.length, remaining));
                if (n == -1) {
                    break;
                }
                buf.write(tmp, 0, n);
                remaining -= n;
            }
        } else {
            int n;
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String readChunked(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            String hex = sizeLine.trim();
            int semi = hex.indexOf(';');
            if (semi >= 0) {
                hex = hex.substring(0, semi).trim();
            }
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("无法解析分块长度：" + sizeLine);
            }
            if (size == 0) {
                readLine(in); // 末尾的空行
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n == -1) {
                    break;
                }
                read += n;
            }
            buf.write(chunk, 0, read);
            readLine(in); // 每块后面的 CRLF
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
