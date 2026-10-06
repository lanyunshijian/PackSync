package com.dsh.packsync.core.sync;

import com.dsh.packsync.core.config.DownloadMode;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.transfer.FileResolver;
import com.dsh.packsync.core.transfer.PackClient;
import com.dsh.packsync.core.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步执行器：按计划取文件、校验、落盘。
 *
 * <p>三条硬性保证：
 * <ol>
 *   <li><b>下载后必校验 SHA-1</b> —— 无论来自服务端加密通道还是公共 CDN 直链。
 *       公共直链是第三方内容，不校验就等于把任意内容写进玩家的 mods/。</li>
 *   <li><b>先写临时文件再原子移动</b> —— 中途失败/断电不会留下半截 jar
 *       让游戏启动时崩溃。</li>
 *   <li><b>可编辑文件绝不被覆盖</b> —— 用 {@code CREATE_NEW} 语义保证，
 *       即便这份文件是在计划生成之后才被玩家创建出来的。</li>
 * </ol>
 *
 * <p>并发度固定为 5：与服务端连接池、公共 CDN 的常见限流都匹配；
 * 再高对总吞吐帮助有限，反而更容易触发限流。
 */
public final class SyncEngine implements AutoCloseable {

    /**
     * 并发下载数。
     *
     * <p><b>这里必须是 1。</b>加密会话（{@code SecureSession}）用**严格递增的消息序号**
     * 防重放，它是为"一条连接上串行收发"设计的。而我们用的是多条独立 HTTP 请求，
     * 并发时服务端的加密序号会跨请求交叉（请求 A 用 5,6,7，请求 B 用 8,9），
     * 客户端只要先拿到 B 的响应就会因"序号不连续"解密失败 —— 实测正是如此
     * （3 个文件并发下载时，必然有文件报"文件块解密失败"）。
     *
     * <p>要恢复并发，得让每次请求各自独立加密（用握手的 statelessKey 按请求派生），
     * 而不是共享一个序号状态机。在那之前，正确性优先。
     */
    private static final int MAX_CONCURRENT = 1;
    private static final int MAX_ATTEMPTS_PER_URL = 2;

    private final PackClient client;
    private final FileResolver resolver;
    private final DownloadMode mode;
    private final SyncPlanner planner;
    private final ExecutorService pool;

    public SyncEngine(PackClient client, FileResolver resolver, Path gameDir, DownloadMode mode) {
        this.client = client;
        this.resolver = resolver;
        this.mode = mode == null ? DownloadMode.DEFAULT : mode;
        this.planner = new SyncPlanner(gameDir);
        this.pool = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
            Thread t = new Thread(r, "PackSync-Download");
            t.setDaemon(true);
            return t;
        });
    }

    /** 进度回调。 */
    public interface Progress {
        void onStart(int totalFiles, long totalBytes);

        void onFileDone(String path, long bytes, boolean fromPublic);

        void onFileFailed(String path, String reason);

        default void onLog(String message) {
        }

        /**
         * 玩家是否点了"取消"。
         *
         * <p>下载循环会在<b>每个文件开始前</b>询问它 —— 没有这个检查，取消按钮就是个摆设：
         * 任务早已一次性全部提交给线程池，点了取消照样把 158 个文件下完。
         */
        default boolean isCancelled() {
            return false;
        }
    }

    /** 执行结果。 */
    public record Result(int downloaded, int keptEditable, int deleted, int failed,
                         long bytes, boolean needsRestart, List<String> failedPaths) {

        public boolean hasFailures() {
            return failed > 0;
        }

        public boolean changedAnything() {
            return downloaded > 0 || deleted > 0;
        }

        public String describe() {
            return "下载成功 " + downloaded + " 个（" + Hashing.humanSize(bytes) + "）"
                    + (failed > 0 ? "，失败 " + failed + " 个" : "")
                    + (deleted > 0 ? "，删除 " + deleted + " 个" : "")
                    + (needsRestart ? "，需要重启游戏" : "");
        }
    }

    /** 执行计划。 */
    public Result execute(SyncPlanner.Plan plan, Progress progress) {
        Progress p = progress == null ? new Progress() {
            @Override
            public void onStart(int totalFiles, long totalBytes) {
            }

            @Override
            public void onFileDone(String path, long bytes, boolean fromPublic) {
            }

            @Override
            public void onFileFailed(String path, String reason) {
            }
        } : progress;

        List<PackManifest.PackFile> todo = plan.toDownload();
        p.onStart(todo.size(), plan.totalBytes());

        // 先批量解析公共直链（SERVER_ONLY 模式下这一步不发任何网络请求）。
        resolver.prefetch(todo, mode, p::onLog);

        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<String> failedPaths = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Callable<Void>> tasks = new ArrayList<>(todo.size());
        for (PackManifest.PackFile f : todo) {
            tasks.add(() -> {
                // ★ 每个文件开始前先看玩家有没有点取消。
                //   没有这一步，"取消"只是个改标志位的摆设 —— 任务早已全部提交，
                //   玩家点了之后照样眼睁睁看着 158 个文件全下完。
                if (p.isCancelled()) {
                    return null;
                }
                try {
                    DownloadOutcome outcome = downloadOne(f);
                    if (outcome.ok) {
                        downloaded.incrementAndGet();
                        bytes.addAndGet(outcome.bytes);
                        p.onFileDone(f.path, outcome.bytes, outcome.fromPublic);
                    } else {
                        failed.incrementAndGet();
                        failedPaths.add(f.path);
                        p.onFileFailed(f.path, outcome.reason);
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    failedPaths.add(f.path);
                    p.onFileFailed(f.path, String.valueOf(e));
                }
                return null;
            });
        }

        try {
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // 单个任务的异常已在任务内部记录。
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.onLog("下载被中断");
        }

        // 取消之后绝不执行删除 —— 那是"服务器要求清掉的文件"，
        // 玩家既然中止了这次同步，就不该再动他目录里的任何东西。
        if (p.isCancelled()) {
            p.onLog("已取消：跳过文件删除与收尾");
            return new Result(downloaded.get(), plan.keepEditable().size(), 0,
                    failed.get(), bytes.get(), false, List.copyOf(failedPaths));
        }

        int deleted = planner.applyDeletions(plan.toDelete());
        boolean needsRestart = plan.requiresRestart() && (downloaded.get() > 0 || deleted > 0);

        return new Result(downloaded.get(), plan.keepEditable().size(), deleted,
                failed.get(), bytes.get(), needsRestart, List.copyOf(failedPaths));
    }

    // ── 单文件下载 ────────────────────────────────────────────────────────

    private record DownloadOutcome(boolean ok, long bytes, boolean fromPublic, String reason) {
        static DownloadOutcome ok(long bytes, boolean fromPublic) {
            return new DownloadOutcome(true, bytes, fromPublic, "");
        }

        static DownloadOutcome fail(String reason) {
            return new DownloadOutcome(false, 0L, false, reason);
        }
    }

    private DownloadOutcome downloadOne(PackManifest.PackFile file) {
        Path target = planner.localPath(file.path);
        List<String> plan = resolver.planFor(file, mode);
        if (plan.isEmpty()) {
            return DownloadOutcome.fail("没有任何可用的下载来源（下载方式：" + mode.describe() + "）");
        }

        String lastError = "未知错误";
        for (String source : plan) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_URL; attempt++) {
                try {
                    long written = source.equals(FileResolver.HOST)
                            ? fetchFromHost(file, target)
                            : fetchFromUrl(source, file, target);
                    return DownloadOutcome.ok(written, !source.equals(FileResolver.HOST));
                } catch (IOException | RuntimeException e) {
                    lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    // 同一来源重试一次再换下一个。
                }
            }
        }
        return DownloadOutcome.fail(lastError);
    }

    /** 走 PackSync 服务端加密通道。 */
    private long fetchFromHost(PackManifest.PackFile file, Path target) throws IOException {
        Path tmp = tempFor(target);
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                client.downloadFile(file.sha1, chunk -> out.write(chunk), null);
            }
            return commit(tmp, target, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 走公共 CDN 直链（下载后同样强制校验哈希）。 */
    private long fetchFromUrl(String url, PackManifest.PackFile file, Path target) throws IOException {
        Path tmp = tempFor(target);
        try {
            URL u = URI.create(url).toURL();
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "PackSync/1.0 (github/dsh/packsync)");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                throw new IOException("公共源返回 " + code);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            } finally {
                conn.disconnect();
            }
            return commit(tmp, target, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 校验临时文件并搬到目标位置。
     *
     * <p>校验不通过就抛异常，绝不会把未验证的内容留在游戏目录里。
     */
    private long commit(Path tmp, Path target, PackManifest.PackFile file) throws IOException {
        String actual = Hashing.sha1(tmp);
        if (actual == null || !actual.equalsIgnoreCase(file.sha1)) {
            throw new IOException("哈希校验失败：期望 " + file.sha1 + "，实际 " + actual);
        }
        long size = Files.size(tmp);

        // 可编辑文件在计划生成之后可能被玩家创建出来 —— 再次确认，绝不覆盖。
        if (file.editable && Files.isRegularFile(target)) {
            Files.deleteIfExists(tmp);
            return size;
        }

        Files.createDirectories(target.getParent());
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return size;
    }

    private static Path tempFor(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return target.resolveSibling(target.getFileName() + ".packsync-tmp");
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
