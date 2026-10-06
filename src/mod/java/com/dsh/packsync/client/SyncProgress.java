package com.dsh.packsync.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步进度状态。
 *
 * <p>刻意做成**一个线程安全的状态容器 + 界面轮询**，而不是"后台线程回调切屏"：
 * <ul>
 *   <li>后台线程只更新这里的字段（volatile / 原子量），不碰任何 Minecraft 对象；</li>
 *   <li>界面在自己的 {@code tick()} 里读状态，必要时切换自己。</li>
 * </ul>
 * 这样就不存在"后台线程误操作渲染栈"这类极难复现的崩溃，
 * 也不需要小心维护跨线程回调的时序。
 */
public final class SyncProgress {

    /** 当前所处的阶段。界面据此决定该显示哪一屏。 */
    public enum Stage {
        IDLE,
        /** 正在向服务端取清单。 */
        FETCHING_MANIFEST,
        /** 正在向公共站查询直链。 */
        FETCHING_URLS,
        /** 首次接触服务器且开启了严格模式，**暂停**等待玩家核对指纹。 */
        AWAITING_FINGERPRINT,
        /** 发现无法在公共站匹配到的 jar，**暂停**等待玩家确认（风险确认屏）。 */
        AWAITING_CONFIRMATION,
        /** 正在下载文件。 */
        DOWNLOADING,
        /** 正在把文件搬到游戏目录（去重、删除等）。 */
        APPLYING,
        DONE,
        FAILED,
        CANCELLED
    }

    private volatile Stage stage = Stage.IDLE;
    private volatile int totalFiles;
    private volatile int doneFiles;
    private volatile int failedFiles;
    private volatile long totalBytes;
    private volatile long doneBytes;
    private volatile String currentFile = "";
    private volatile String modpackName = "";
    private volatile String errorMessage = "";
    private volatile String summary = "";

    /** 服务器身份信息（取到清单后填充，供指纹屏与状态屏使用）。 */
    private volatile String serverFingerprint = "";
    private volatile boolean firstContact;

    /** 无法在公共站匹配到的 jar —— 风险确认屏要用它。 */
    private final List<String> unverifiedJars = Collections.synchronizedList(new ArrayList<>());

    /**
     * 本次公共站（Modrinth / CurseForge）匹配到的文件数；{@code -1} 表示尚未查询。
     *
     * <p>用来区分两种<b>完全不同</b>的情形：<b>「公共站可达，但这些文件是私有/改过的 mod」</b>
     * 与 <b>「公共站根本连不上（网络不通）」</b>。前者是整合包本身的性质，
     * 后者是网络问题 —— 玩家该做的事完全不同（后者应改用「只从服务端下载」，免得每次同步都白等超时）。
     * 只报"有 N 个文件无法验证"会把这两件事混为一谈。
     */
    private volatile int publicMatchCount = -1;
    /** 本次的变更摘要（新增/删除），更新日志屏用。 */
    private final List<String> changes = Collections.synchronizedList(new ArrayList<>());
    /** 失败的文件与原因。 */
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean cancelRequested;

    /** 是否正在等玩家核对指纹（严格模式）。 */
    private volatile boolean awaitingFingerprint;
    private final java.util.concurrent.atomic.AtomicBoolean fingerprintConfirmed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 是否正在等玩家确认风险（后台线程会在这里等）。 */
    private volatile boolean awaitingConfirmation;
    private final java.util.concurrent.atomic.AtomicBoolean confirmed = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 用于算速度/ETA 的采样。 */
    private final AtomicLong lastSampleTime = new AtomicLong();
    private final AtomicLong lastSampleBytes = new AtomicLong();
    private volatile double bytesPerSecond;
    private volatile long startTime;

    // ── 生命周期 ──────────────────────────────────────────────────────────

    public synchronized void begin(Stage initial) {
        reset();
        this.stage = initial;
        this.startTime = System.currentTimeMillis();
        this.lastSampleTime.set(this.startTime);
        this.lastSampleBytes.set(0L);
    }

    public synchronized void reset() {
        stage = Stage.IDLE;
        totalFiles = doneFiles = failedFiles = 0;
        totalBytes = doneBytes = 0;
        currentFile = "";
        modpackName = "";
        errorMessage = "";
        summary = "";
        serverFingerprint = "";
        firstContact = false;
        cancelRequested = false;
        bytesPerSecond = 0;
        unverifiedJars.clear();
        publicMatchCount = -1;
        needsRestart = false;
        changes.clear();
        failures.clear();
    }

    // ── 后台线程写入 ──────────────────────────────────────────────────────

    public void setStage(Stage s) {
        this.stage = s;
    }

    public void setManifestInfo(String modpackName, int totalFiles, long totalBytes) {
        this.modpackName = modpackName == null ? "" : modpackName;
        this.totalFiles = totalFiles;
        this.totalBytes = totalBytes;
    }

    public void setServerIdentity(String fingerprint, boolean firstContact) {
        this.serverFingerprint = fingerprint == null ? "" : fingerprint;
        this.firstContact = firstContact;
    }

    public void setCurrentFile(String path) {
        this.currentFile = path == null ? "" : path;
    }

    public void onFileDone(String path, long bytes) {
        doneFiles++;
        doneBytes += Math.max(0, bytes);
        sample();
    }

    public void onFileFailed(String path, String reason) {
        failedFiles++;
        failures.add(path + " → " + reason);
        sample();
    }

    private void sample() {
        long now = System.currentTimeMillis();
        long last = lastSampleTime.get();
        if (now - last < 500) {
            return; // 采样间隔太短会抖得厉害
        }
        long lastBytes = lastSampleBytes.getAndSet(doneBytes);
        lastSampleTime.set(now);
        double seconds = (now - last) / 1000.0;
        if (seconds > 0) {
            bytesPerSecond = Math.max(0, (doneBytes - lastBytes) / seconds);
        }
    }

    public void addUnverifiedJar(String path) {
        if (path != null && !path.isBlank()) {
            unverifiedJars.add(path);
        }
    }

    /** 记录公共站匹配到的文件数；{@code -1} 表示未查询（例如 SERVER_ONLY 模式）。 */
    public void setPublicMatchCount(int count) {
        this.publicMatchCount = count;
    }

    /** 公共站匹配到的文件数；{@code -1} 表示未查询。 */
    public int publicMatchCount() {
        return publicMatchCount;
    }

    public void addChange(String description) {
        if (description != null && !description.isBlank()) {
            changes.add(description);
        }
    }

    /**
     * 是否需要重启游戏才能生效。
     *
     * <p>只有<b>真的动过 mod 文件</b>才为 true。没有变更时若还让玩家重启，
     * 就会出现"重启 → 再同步 → 又提示重启"的死循环 —— 玩家会觉得游戏被卡住进不去。
     */
    private volatile boolean needsRestart;

    /** 同步结束，且<b>需要重启</b>才能生效（有文件变动）。 */
    public void finish(String summary) {
        this.summary = summary == null ? "" : summary;
        this.needsRestart = true;
        this.stage = Stage.DONE;
    }

    /**
     * 同步结束，但<b>无需重启</b>（没有任何文件变动）。
     *
     * <p>界面会据此直接放行，而不是弹「需要重启游戏」。
     */
    public void finishNoRestart(String summary) {
        this.summary = summary == null ? "" : summary;
        this.needsRestart = false;
        this.stage = Stage.DONE;
    }

    public boolean needsRestart() {
        return needsRestart;
    }

    public void fail(String message) {
        this.errorMessage = message == null ? "未知错误" : message;
        this.stage = Stage.FAILED;
    }

    public void requestCancel() {
        this.cancelRequested = true;
        // 取消时同时解除确认等待，否则后台线程会一直挂在这里。
        this.awaitingConfirmation = false;
    }

    /** 进入"等玩家核对指纹"状态（仅严格模式）。 */
    public void requestFingerprint(boolean trusted) {
        if (trusted) {
            // 普通模式：不必打扰玩家，直接放行。
            return;
        }
        fingerprintConfirmed.set(false);
        awaitingFingerprint = true;
        stage = Stage.AWAITING_FINGERPRINT;
    }

    /** 玩家在指纹核对屏点了"信任"。 */
    public void confirmFingerprint(boolean trusted) {
        fingerprintConfirmed.set(trusted);
        awaitingFingerprint = false;
        stage = trusted ? Stage.FETCHING_MANIFEST : Stage.CANCELLED;
    }

    /** 后台线程等待玩家核对指纹。 */
    public boolean awaitFingerprint() {
        while (awaitingFingerprint && !cancelRequested) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return fingerprintConfirmed.get() && !cancelRequested;
    }

    /** 进入"等玩家确认风险"状态。 */
    public void requestConfirmation() {
        confirmed.set(false);
        awaitingConfirmation = true;
        stage = Stage.AWAITING_CONFIRMATION;
    }

    /** 玩家点了"我已了解风险，继续下载"。 */
    public void confirmDangerous() {
        confirmed.set(true);
        awaitingConfirmation = false;
        stage = Stage.DOWNLOADING;
    }

    /**
     * 后台线程在此等待玩家决定。
     *
     * <p>用轮询而不是 {@code CountDownLatch}：玩家可能直接关掉界面/退出游戏，
     * 那种情况下 latch 会永远等下去。轮询 + 取消标志能保证进程一定退得出来。
     *
     * @return true 表示玩家确认继续；false 表示取消（或因取消请求而退出）
     */
    public boolean awaitConfirmation() {
        while (awaitingConfirmation && !cancelRequested) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return confirmed.get() && !cancelRequested;
    }

    // ── 界面读取 ──────────────────────────────────────────────────────────

    public Stage stage() {
        return stage;
    }

    public int totalFiles() {
        return totalFiles;
    }

    public int doneFiles() {
        return doneFiles;
    }

    public int failedFiles() {
        return failedFiles;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long doneBytes() {
        return doneBytes;
    }

    public String currentFile() {
        return currentFile;
    }

    public String modpackName() {
        return modpackName;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String summary() {
        return summary;
    }

    public String serverFingerprint() {
        return serverFingerprint;
    }

    public boolean firstContact() {
        return firstContact;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public List<String> unverifiedJars() {
        synchronized (unverifiedJars) {
            return List.copyOf(unverifiedJars);
        }
    }

    public List<String> changes() {
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    public List<String> failures() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }

    public double bytesPerSecond() {
        return bytesPerSecond;
    }

    /** 完成比例 0..1。 */
    public double fraction() {
        if (totalBytes > 0) {
            return Math.min(1.0, (double) doneBytes / (double) totalBytes);
        }
        if (totalFiles > 0) {
            return Math.min(1.0, (double) doneFiles / (double) totalFiles);
        }
        return 0.0;
    }

    /**
     * 预估剩余秒数；无法预估时返回 -1。
     *
     * <p>速度未知或已接近完成时不给数字 —— 显示一个乱跳的 ETA 比不显示更糟。
     */
    public long etaSeconds() {
        double speed = bytesPerSecond;
        if (speed <= 0 || totalBytes <= 0) {
            return -1;
        }
        long remaining = totalBytes - doneBytes;
        if (remaining <= 0) {
            return 0;
        }
        return (long) Math.ceil(remaining / speed);
    }

    public long elapsedMillis() {
        return startTime == 0 ? 0 : System.currentTimeMillis() - startTime;
    }

    /** 是否处于"会持续刷新"的阶段（界面据此决定要不要每 tick 重绘）。 */
    public boolean isActive() {
        return stage == Stage.FETCHING_MANIFEST || stage == Stage.FETCHING_URLS
                || stage == Stage.DOWNLOADING || stage == Stage.APPLYING;
    }

    /** 是否正等玩家做风险确认。 */
    public boolean isAwaitingConfirmation() {
        return stage == Stage.AWAITING_CONFIRMATION;
    }

    /** 是否正等玩家核对指纹（严格模式）。 */
    public boolean isAwaitingFingerprint() {
        return stage == Stage.AWAITING_FINGERPRINT;
    }

    // ── 展示辅助 ──────────────────────────────────────────────────────────

    public static String humanBytes(long bytes) {
        return com.dsh.packsync.core.util.Hashing.humanSize(bytes);
    }

    public String humanSpeed() {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        return humanBytes((long) bytesPerSecond) + "/s";
    }

    public String humanEta() {
        long s = etaSeconds();
        if (s < 0) {
            return "计算中…";
        }
        if (s < 60) {
            return s + " 秒";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " 分 " + (s % 60) + " 秒";
        }
        return (m / 60) + " 时 " + (m % 60) + " 分";
    }
}
