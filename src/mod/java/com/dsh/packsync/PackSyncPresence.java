package com.dsh.packsync;

import com.dsh.packsync.core.PackSyncCore;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.config.ServerConfig;
import com.dsh.packsync.core.util.PackPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "客户端装没装 PackSync"的检测，以及随之而来的准入处理。
 *
 * <p>为什么需要它：本 mod 的 {@code displayTest="IGNORE_ALL_VERSION"} 保证了
 * <b>没装的客户端也能进服</b>（这是核心承诺，绝不能反悔）。但服务端仍然想
 * "知道谁没装" —— 要么给个提示，要么在管理员要求时拒绝进入。
 *
 * <p>检测方式刻意做得**被动**：客户端连上后主动发一个 {@code hello} 包，
 * 服务端记下这个人。没收到 hello 的人就是没装。
 * 这样没装的客户端不会收到任何自定义包，也就不可能因为"未知 payload"被踢
 * —— 与 modsync 同一条设计原则：<b>一切通信由客户端发起</b>。
 *
 * <p>通道用 {@code acceptMissingOr(...)} 注册：即使客户端没装，
 * 通道不匹配也不会拒绝连接。
 */
@Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackSyncPresence {

    private static final String PROTOCOL = "1";
    private static final String CHANNEL_NAME = "packsync:hello";

    /** 已确认安装本 mod 的玩家 UUID。 */
    private static final Set<UUID> PRESENT = ConcurrentHashMap.newKeySet();

    /** 通道：双向都用它。allowMissing 是"没装的客户端也能连"的技术保证。 */
    private static SimpleChannel channel;

    private PackSyncPresence() {
    }

    /** 由 mod 构造时调用（两侧都要注册，否则通道不匹配）。 */
    public static synchronized void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(CHANNEL_NAME),
                () -> PROTOCOL,
                // 客户端与"服务端声明的版本"比较：一律接受，避免版本差异导致连不上。
                version -> true,
                version -> true);
        channel.messageBuilder(Hello.class, 0)
                .encoder(Hello::encode)
                .decoder(Hello::decode)
                .consumerMainThread(Hello::handle)
                .add();
        // 服务端 → 客户端：把整合包接入信息**主动推给客户端**。
        // 这是"连服即自动同步"的关键 —— 没有它，客户端只能靠猜端口去探测，
        // 也就必须有个手动触发点（这正是最初版本行为不一致的原因）。
        channel.messageBuilder(ServerInfoMsg.class, 1)
                .encoder(ServerInfoMsg::encode)
                .decoder(ServerInfoMsg::decode)
                .consumerMainThread(ServerInfoMsg::handle)
                .add();
    }

    /** 客户端：连上后主动报到。 */
    public static void sendHelloIfClient() {
        if (channel == null) {
            return;
        }
        try {
            channel.sendToServer(new Hello(PackSyncCore.VERSION));
        } catch (Throwable t) {
            // 报到失败不该影响游戏。
            PackSync.LOGGER.debug("[PackSync] 客户端报到失败（忽略）：{}", String.valueOf(t));
        }
    }

    /** 玩家断开时清掉标记，避免重连后误判。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PRESENT.remove(sp.getUUID());
        }
    }

    /**
     * 玩家进入世界。
     *
     * <p><b>这里绝不允许断开连接。</b>曾经有段逻辑：没在 {@code PRESENT} 里找到就按
     * {@code requireClientMod} 把玩家踢掉。但 {@code PRESENT} 依赖的是"进服后客户端补发的
     * hello 包"，而本事件是进服瞬间触发的 —— <b>两者在赛跑</b>。结果就是装了 PackSync 的
     * 玩家也会被随机踢掉，表现为"连接很不稳定，老是说我没装"。
     *
     * <p>能不能连上服务器，绝不该由这个 mod 决定。所以现在无论配置怎么写，这里都只发提示、
     * 不断线。真正权威的"客户端装没装"判据在登录期握手（{@code ServerLoginAddon.clientUnderstood}），
     * 那才是确定的时机；即便如此也只用于告知，不用于拦截。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            if (PRESENT.contains(player.getUUID())) {
                return; // 已报到，什么都不做
            }
            ServerConfig config = loadConfig();

            // ★ 不再有 disconnect —— 只提示，不影响玩家进服。
            if (config.nagMissingClients) {
                String msg = config.requireClientMod
                        ? "本服务器建议安装 PackSync 以自动同步整合包（不装也能玩，但 mod 可能不匹配）。"
                        : config.nagMessage;
                player.sendSystemMessage(Component.literal(msg)
                        .withStyle(ChatFormatting.YELLOW));
                Component clickable = Component.literal(config.nagClickableMessage)
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, config.nagClickableLink)));
                player.sendSystemMessage(clickable);
            }
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 处理玩家进入时出错（忽略）", t);
        }
    }

    private static ServerConfig loadConfig() {
        PackPaths paths = PackPaths.of(FMLPaths.GAMEDIR.get());
        return ConfigIO.loadServer(paths.serverConfigFile()).normalize();
    }

    /** 供状态查询/调试：当前确认安装了本 mod 的玩家数。 */
    public static int presentCount() {
        return PRESENT.size();
    }

    static void markPresent(UUID id) {
        if (id != null) {
            PRESENT.add(id);
        }
    }

    // ── 包 ────────────────────────────────────────────────────────────────

    /**
     * 客户端报到包。
     *
     * <p>只带一个版本字符串：服务端目前不据此做任何准入判断
     * （那是 {@code PackSyncCore.VERSION} 与清单里的版本字段的职责），
     * 只用来确认"对端确实有这个 mod"。
     */
    public record Hello(String version) {

        static void encode(Hello msg, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(msg.version == null ? "" : msg.version, 64);
        }

        static Hello decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new Hello(buf.readUtf(64));
        }

        static void handle(Hello msg, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            var context = ctx.get();
            var sender = context.getSender();
            if (sender != null) {
                markPresent(sender.getUUID());
                PackSync.LOGGER.info("[PackSync] 玩家 {} 已确认安装 PackSync {}",
                        sender.getGameProfile().getName(), msg.version());
                // ★ 主动把整合包接入信息推回去，让客户端无需任何手动操作就知道该跟谁同步
                try {
                    ServerInfoMsg info = PackSyncHost.describeForClient();
                    if (info != null) {
                        // 用标准方式定向发给该玩家（Context.reply 在 1.20.1 不存在）
                        channel.send(PacketDistributor.PLAYER.with(() -> sender), info);
                        PackSync.LOGGER.info("[PackSync] 已向 {} 下发整合包信息：{}:{} / {} 个文件",
                                sender.getGameProfile().getName(), info.host(), info.port(), info.fileCount());
                    } else {
                        PackSync.LOGGER.warn("[PackSync] 托管未运行，无法向 {} 下发整合包信息",
                                sender.getGameProfile().getName());
                    }
                } catch (Throwable t) {
                    PackSync.LOGGER.error("[PackSync] 下发整合包信息失败", t);
                }
            }
            context.setPacketHandled(true);
        }
    }

    /**
     * 服务端 → 客户端：整合包接入信息。
     *
     * <p>客户端拿到它之后就能**直接开始同步**，不需要玩家绑快捷键、点按钮，
     * 也不需要去猜分发端口 —— 行为与登录期下发一致。
     */
    public record ServerInfoMsg(String host, int port, String modpackName,
                                int fileCount, long totalBytes, String fingerprint,
                                boolean requireFingerprint) {

        static void encode(ServerInfoMsg msg, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(msg.host == null ? "" : msg.host, 255);
            buf.writeVarInt(msg.port);
            buf.writeUtf(msg.modpackName == null ? "" : msg.modpackName, 128);
            buf.writeVarInt(msg.fileCount);
            buf.writeVarLong(msg.totalBytes);
            buf.writeUtf(msg.fingerprint == null ? "" : msg.fingerprint, 128);
            // 服务端是否要求核对身份指纹（对应 ServerConfig.enableFingerprintCheck）。
            buf.writeBoolean(msg.requireFingerprint);
        }

        static ServerInfoMsg decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new ServerInfoMsg(buf.readUtf(255), buf.readVarInt(), buf.readUtf(128),
                    buf.readVarInt(), buf.readVarLong(), buf.readUtf(128), buf.readBoolean());
        }

        static void handle(ServerInfoMsg msg,
                           java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            try {
                // 交给客户端侧的处理器（它需要碰 Minecraft 对象，因此放到专用类里）。
                com.dsh.packsync.client.ServerInfoHandler.handle(msg);
            } catch (Throwable t) {
                PackSync.LOGGER.error("[PackSync] 处理服务端下发的整合包信息失败", t);
            }
            ctx.get().setPacketHandled(true);
        }
    }
}
