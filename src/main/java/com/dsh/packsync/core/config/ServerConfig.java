package com.dsh.packsync.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端配置。
 *
 * <p>刻意做成**零 MC 依赖的纯数据类 + 公开字段**：
 * <ul>
 *   <li>启动期同步的 locator 阶段要读它，那个类加载器里没有 MC 类；</li>
 *   <li>公开字段便于 Gson 直接读写，新增字段时旧配置文件自动补默认值。</li>
 * </ul>
 *
 * <p>配置文件位置：{@code <服务端根>/config/packsync-server.json}。
 * 密钥**不在**这里，而在 {@code <服务端根>/modpack-keys/}（见 KeyManager）。
 */
public class ServerConfig {

    /** 配置结构版本，供以后迁移用。不要手改。 */
    public int configVersion = 1;

    // ── 整合包身份 ────────────────────────────────────────────────────────

    /** 整合包名。显示给玩家，也是客户端 {@code modpacks/<名字>/} 的目录名。 */
    public String modpackName = "服务器";

    /**
     * 服务器名字（展示用）。
     *
     * <p>{@link #modpackName} 留空时会退而用这个名字，再空才用服务端目录名 ——
     * 目录名常常是"新建文件夹 (2)"这种毫无意义的字符串，直接显示给玩家很难看。
     */
    public String serverName = "服务器";

    /** 是否启用整合包托管（下载服务）。关了客户端就只能拿到清单、下不到文件。 */
    public boolean modpackHost = true;

    /** 服务端启动时自动重新生成整合包清单。改完内容重启即可发布。 */
    public boolean generateOnStart = true;

    // ── 内容范围（glob 规则，相对服务端根目录）────────────────────────────

    /**
     * 要同步给客户端的文件。语法：JDK glob + {@code !} 前缀表示排除。
     * <b>目录必须写 {@code dir/**}</b>（只有文件会被匹配，目录名本身不会被命中）；
     * 只有排除项而没有包含项时结果为「什么都不选」。
     *
     * <p><b>默认只含 {@code /mods/*.jar}，刻意不含 {@code /config/**}。</b>
     * 原因：服务端 {@code config/} 里经常躺着密钥与私有配置
     * （例如其它 mod 的身份密钥文件）。一旦默认同步整个 config，
     * 这些机密就会被打包发给每一个客户端。需要分发配置时请显式添加，
     * 并配合 {@code !} 排除敏感文件。
     */
    public List<String> syncedFiles = new ArrayList<>(List.of(
            "/mods/*.jar"
    ));

    /** 客户端可自行修改的文件：只下载一次，之后不再被覆盖。 */
    public List<String> allowEditsInFiles = new ArrayList<>(List.of(
            "/options.txt",
            "/config/**"
    ));

    /** 强制复制到标准位置的文件（少数对路径敏感的 mod 需要）。 */
    public List<String> forceCopyFiles = new ArrayList<>();

    /** 下发到客户端的删除清单：{@code 相对路径 -> sha1}。只有哈希匹配时才删。 */
    public Map<String, String> filesToDelete = new LinkedHashMap<>();

    // ── 自动排除规则 ──────────────────────────────────────────────────────

    /** 自动排除仅服务端需要的 mod（依据 mod 元数据里的 environment/side 声明）。 */
    public boolean excludeServerSideMods = true;

    /** 自动跳空文件、隐藏文件（`.` 开头）、`.tmp`、`.disabled`、`.bak`。 */
    public boolean excludeUnnecessaryFiles = true;

    // ── 客户端准入 ────────────────────────────────────────────────────────

    /**
     * 客户端没装本 mod 时是否算"不合规"。
     *
     * <p><b>默认 false，而且它不再会断开任何人的连接</b> —— 只影响进服时那句聊天提示的措辞。
     * 曾经它为 true 时会把玩家踢掉，但判据（"有没有收到 hello 包"）与进服事件是赛跑关系，
     * 装了 PackSync 的玩家也会被随机踢下线，表现为"连接很不稳定"。玩家能不能进服，
     * 不该由这个 mod 决定。
     */
    public boolean requireClientMod = false;

    /** 未安装本 mod 的客户端进服时聊天提示（需 requireClientMod=false）。 */
    public boolean nagMissingClients = true;

    public String nagMessage = "本服务器通过 PackSync 自动分发整合包。";
    public String nagClickableMessage = "点此获取 PackSync";
    public String nagClickableLink = "https://modrinth.com/mod/";

    // ── 网络 ──────────────────────────────────────────────────────────────

    /** 绑定地址，留空 = 所有网卡。 */
    public String bindAddress = "";

    /** 下载服务监听端口。<b>-1 = 复用 Minecraft 端口</b>（默认，无需额外端口转发）。 */
    public int bindPort = -1;

    /** 下发给客户端的对外地址（服务器在 NAT 后时填公网域名/IP）。 */
    public String addressToSend = "";

    /** 下发给客户端的对外端口，-1 = 与 MC 端口相同。 */
    public int portToSend = -1;

    /**
     * 预连接对账 HTTP 端口：客户端**进服之前**就能取清单。
     * 0 = 自动使用 {@code MC端口 + 1}。
     * 某些客户端缺 mod 时 Forge 会在登录阶段直接断开，那时游戏内通道没机会运行，
     * 因此这条通道是必要的。
     */
    public int reconcilePort = 0;

    /** 关闭内置 TLS。仅在 bindPort != -1（独立端口）时生效；复用 MC 端口时无法关闭。 */
    public boolean disableInternalTls = false;

    /** 下发带宽上限，单位 Mbps，0 = 不限速。 */
    public int bandwidthLimitMbps = 0;

    // ── 安全 ──────────────────────────────────────────────────────────────

    /** 是否校验下载请求的玩家密钥。 */
    public boolean validateSecrets = true;

    /**
     * 是否启用<b>服务器身份密钥校验</b>（Ed25519 指纹）。
     *
     * <p>开启时（默认），服务端会把自己的公钥指纹随整合包信息一起下发，客户端据此
     * 核对"我连的确实是这台服务器"—— 这是防止<b>第三方冒充服务器</b>往玩家游戏里
     * 塞恶意 mod 的唯一锚点。
     *
     * <p>关掉后客户端不再核对指纹（服务端也不再声明），适合内网/单机测试环境。
     * <b>公网服务器不要关。</b>改法：编辑 {@code <服务端根>/config/packsync-server.json}
     * 里的这一项，然后执行 {@code /packsync config reload}（或重启服务端）。
     */
    public boolean enableFingerprintCheck = true;

    /** 玩家密钥有效期（小时），默认 336 = 14 天。 */
    public long secretLifetimeHours = 336L;

    /** 是否允许本 mod 自我更新。 */
    public boolean selfUpdater = false;

    // ── 派生辅助 ──────────────────────────────────────────────────────────

    /** 规整：去掉 null、统一前导斜杠。返回自身便于链式调用。 */
    public ServerConfig normalize() {
        if (syncedFiles == null) {
            syncedFiles = new ArrayList<>();
        }
        if (allowEditsInFiles == null) {
            allowEditsInFiles = new ArrayList<>();
        }
        if (forceCopyFiles == null) {
            forceCopyFiles = new ArrayList<>();
        }
        if (filesToDelete == null) {
            filesToDelete = new LinkedHashMap<>();
        }
        syncedFiles = normalizePaths(syncedFiles);
        allowEditsInFiles = normalizePaths(allowEditsInFiles);
        forceCopyFiles = normalizePaths(forceCopyFiles);
        if (modpackName == null) {
            modpackName = "";
        }
        if (bindAddress == null) {
            bindAddress = "";
        }
        if (addressToSend == null) {
            addressToSend = "";
        }
        return this;
    }

    private static List<String> normalizePaths(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String t = s.trim();
            if (t.startsWith("/!/")) {
                t = t.substring(1);
            } else if (t.startsWith("!")) {
                t = "!/" + t.substring(1);
            } else if (!t.startsWith("/")) {
                t = "/" + t;
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 复用 MC 端口？ */
    public boolean usesMinecraftPort() {
        return bindPort == -1;
    }
}
