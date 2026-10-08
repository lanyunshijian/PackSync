package com.dsh.packsync.core.config;

import com.dsh.packsync.core.util.ServerAddressParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端配置。零 MC 依赖，原因同 {@link ServerConfig}
 * （启动期 locator 要读它来决定"这次启动该不该同步"）。
 *
 * <p>位置：{@code <游戏目录>/config/packsync-client.json}。
 */
public class ClientConfig {

    /** 配置结构版本。不要手改。 */
    public int configVersion = 1;

    /** 当前选中的整合包目录名。填上即加载该整合包。 */
    public String selectedModpack = "";

    /** 已安装的服务器整合包：键为 {@code host:port}，值为该服务器的接入信息。 */
    public Map<String, ServerEntry> installedServers = new LinkedHashMap<>();

    /** 启动时自动检查并同步所选整合包（启动期同步的总开关）。 */
    public boolean updateOnLaunch = true;

    /**
     * 首次连到一台提供整合包的服务器时，**自动开始同步**。
     *
     * <p>默认开启，与常见行为一致：玩家连服后就会看到
     * 风险确认/下载进度界面，不需要自己找按钮。
     *
     * <p>关掉它的场景：玩家只想先看看服务器、不想立刻被下载打断游戏。
     * 关掉后仍可随时在 PackSync 界面手动点"立即同步"。
     */
    public boolean autoSyncOnJoin = true;

    /**
     * <b>下载来源策略 —— 本 mod 的新增可选项。</b>
     *
     * <p>取值见 {@link DownloadMode}，默认 {@code PUBLIC_FIRST}（与常见行为一致）。
     * 选 {@code SERVER_ONLY} 即为「直接从服务端下载」，完全不查询公共站。
     *
     * <p>存成字符串而不是枚举：配置文件里写错一个词不会让整个配置解析失败，
     * {@link #downloadMode()} 会宽容回落到默认值。
     */
    public String downloadMode = DownloadMode.DEFAULT.name();

    /**
     * <b>手动指定同步（分发）端口。</b>
     *
     * <p>{@code 0} = 自动：优先用服务端下发的端口，没有下发则按 MC 端口 + 1 推断。
     *
     * <p>什么时候要手填：服务端没下发端口，或者下发的端口连不通
     * （防火墙只放行了某个固定端口、中间有端口映射、同端口分流改成独立端口……）。
     * 填上之后对所有服务器都用这个端口，改回 {@code 0} 即恢复自动。
     *
     * <p>填错不影响进服 —— 它只管整合包同步；进服走的是 MC 自己的端口。
     */
    public int syncPortOverride = 0;

    /** 下载后校验哈希。<b>强烈建议保持开启。</b> */
    public boolean verifyHashAfterDownload = true;

    /** 是否允许服务器让你删除"非整合包文件"。在意本地文件安全时可关。 */
    public boolean allowRemoteDeletions = true;

    // 说明：这里曾经有个 trustNewServers（首次连接自动信任该服务器），已删除。
    // 它等于"第一次随便过" —— 而中间人恰恰只需要赢第一次：一旦伪造的指纹被记下来，
    // 之后所有比对都以它为基准，整条校验链就失效了。现在首次连接也必须由玩家
    // 核对管理员给的指纹（见 FingerprintScreen），没有"自动信任"这条捷径。

    /**
     * 是否启用服务器指纹校验。
     *
     * <p>开启后，同步前会拿服务端下发的指纹与本地记录比对；首次连接或指纹不符时，
     * 必须由玩家<b>手动输入管理员提供的指纹</b>并比对成功才能开始下载 —— 这是防止
     * <b>第三方冒充服务器</b>（中间人往你游戏里塞恶意 mod）的唯一锚点。
     */
    public boolean verifyServerFingerprint = true;

    /**
     * 严格模式：指纹与本地记录<b>不符</b>时直接拒绝同步。
     *
     * <p>默认（false）只警告并把新指纹记下来 —— 因为服务器换密钥是正常运维，
     * 一律拒绝会让玩家莫名其妙连不上。开启后则一律拒绝，直到玩家手动清掉记录。
     */
    public boolean strictFingerprint = false;

    /** 下载时播放等待音乐。 */
    public boolean playMusic = false;

    /** 是否允许本 mod 自我更新。 */
    public boolean selfUpdater = false;

    /** 解析后的下载策略（永不返回 null）。 */
    public DownloadMode downloadMode() {
        return DownloadMode.parse(downloadMode);
    }

    /** 设置下载策略。 */
    public void setDownloadMode(DownloadMode mode) {
        this.downloadMode = (mode == null ? DownloadMode.DEFAULT : mode).name();
    }

    public ClientConfig normalize() {
        if (selectedModpack == null) {
            selectedModpack = "";
        }
        if (installedServers == null) {
            installedServers = new LinkedHashMap<>();
        }
        if (downloadMode == null) {
            downloadMode = DownloadMode.DEFAULT.name();
        }
        if (syncPortOverride != 0 && (syncPortOverride < 1 || syncPortOverride > 65535)) {
            // 写坏了就退回自动：宁可让它自己去推断端口，也不要卡在一个非法端口上。
            syncPortOverride = 0;
        }
        return this;
    }

    /**
     * 解析本次同步要连的端口。
     *
     * <p>优先级：手动指定的 {@link #syncPortOverride} &gt; 服务端下发的
     * {@link ServerEntry#port} &gt; MC 端口 + 1。
     *
     * @return 端口；{@code -1} 表示无从确定
     */
    public int resolveSyncPort(ServerEntry entry) {
        if (syncPortOverride > 0) {
            return syncPortOverride;
        }
        if (entry != null) {
            if (entry.port > 0) {
                return entry.port;
            }
            if (entry.mcPort > 0) {
                return entry.mcPort + 1;
            }
        }
        return -1;
    }

    /** 当前端口的来源说明，用于日志与界面提示。 */
    public String describeSyncPort(ServerEntry entry) {
        if (syncPortOverride > 0) {
            return syncPortOverride + "（手动指定）";
        }
        if (entry != null && entry.port > 0) {
            return entry.port + "（服务端下发）";
        }
        if (entry != null && entry.mcPort > 0) {
            return (entry.mcPort + 1) + "（MC 端口 + 1 推断）";
        }
        return "未知";
    }

    /** 本机记住的、用于启动期同步的服务器条目。 */
    public static class ServerEntry {

        /** 整合包名（服务端下发）。 */
        public String modpackName = "";

        /** 下载主机地址（服务端下发的 addressToSend，为空则用 MC 地址）。 */
        public String host = "";

        /** 下载端口，-1 表示与 MC 端口相同。 */
        public int port = -1;

        /** 玩家当初手输/点击加入的 MC 服务器地址，用于启动期回连。 */
        public String mcHost = "";

        public int mcPort = 25565;

        /** 是否需要先发明文魔数握手（复用 MC 端口时必须）。 */
        public boolean requiresMagic = true;

        /** 服务端身份指纹（SHA-256 十六进制），TOFU 用它识别"还是那台服务器"。 */
        public String fingerprint = "";

        /**
         * 该服务端是否要求核对身份指纹（由服务端配置 {@code enableFingerprintCheck} 下发）。
         *
         * <p>服务端关掉后，客户端不再因指纹不符而中断同步 —— 内网/测试环境用得上；
         * 公网服务器应保持开启。
         */
        public boolean requireFingerprint = true;

        /**
         * 这个指纹是否<b>经过玩家核对</b>。
         *
         * <p>必须与 {@link #fingerprint} 分开看：指纹字段可能被别处顺手写进来
         * （登录期握手、收到整合包信息时……），那不等于玩家信任了这台服务器。
         * 只有玩家在核对界面<b>输入过管理员给的指纹并比对成功</b>，才置 true。
         *
         * <p>没有这个标记的话，"被自动写入的指纹"与"核对过的一致指纹"无法区分，
         * 校验环节就会被静默跳过 —— 核对界面永远不出现，防线形同虚设。
         */
        public boolean fingerprintVerified = false;

        /** 上次同步时间戳（毫秒），仅用于展示与排障。 */
        public long lastSyncAt = 0L;

        /**
         * 规范化后的主机名。
         *
         * <p>历史配置里的 {@link #mcHost} 可能混进了 {@code ":端口"}：玩家用 IPv6 联机、
         * 且服务端没下发对外地址时，旧版本会把 {@code host + ":" + port} 的结果整个当成
         * host 存下来（{@code 2409:...:88e8:25565}）。拼 URL 时会直接抛
         * {@code MalformedURLException}。这里统一清洗一次，老配置无需手工修。
         */
        public String resolvedHost() {
            String h = ServerAddressParser.host(mcHost);
            return (h == null || h.isBlank()) ? mcHost : h;
        }

        public String key() {
            return resolvedHost() + ":" + mcPort;
        }

        public boolean isUsable() {
            return mcHost != null && !mcHost.isBlank();
        }
    }
}
