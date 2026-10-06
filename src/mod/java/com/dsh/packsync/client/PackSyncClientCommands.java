package com.dsh.packsync.client;

import com.dsh.packsync.PackSync;
import com.dsh.packsync.core.PackSyncCore;
import com.dsh.packsync.core.config.ClientConfig;
import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.manifest.LocalManifest;
import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.util.PackPaths;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 普通玩家可用的那一条指令：{@code /packsync verify}。
 *
 * <p><b>只看不改</b>：把本地 {@code mods/} 与当前服务器的整合包清单比一遍，
 * 告诉你缺了哪些、多了哪些、哪些大小对不上。玩家进不去服、或者怀疑自己
 * mod 装错时，敲一下就知道问题出在哪，而不必去翻日志。
 *
 * <p><b>为什么放在客户端</b>：服务端根本不知道你本地有什么文件 —— 它只看得到
 * 玩家有没有按约定报到。要"校验客户端的 mod"，只有客户端自己扫得出来。
 * 而且做成客户端指令后，它不需要任何服务端权限，普通玩家即可使用；
 * 管理类指令（generate / host / config）仍然限 OP 等级 3。
 *
 * <p>比对用的是<b>文件名 + 字节数</b>而非完整 SHA-1：158 个 jar 逐个算哈希要读几百 MB，
 * 敲个指令就卡住几秒很不礼貌。文件名与大小已足以发现"少装/多装/装错版本"，
 * 真正的哈希校验由同步流程在做。
 */
@Mod.EventBusSubscriber(modid = PackSyncCore.MOD_ID_INNER, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackSyncClientCommands {

    private PackSyncClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            dispatcher.register(Commands.literal("packsync")
                    .then(Commands.literal("verify")
                            .executes(ctx -> verify())));
        } catch (Throwable t) {
            PackSync.LOGGER.warn("[PackSync] 注册客户端指令失败（不影响游戏）", t);
        }
    }

    /** 执行校验并把结果发到聊天栏。 */
    private static int verify() {
        Minecraft mc = Minecraft.getInstance();
        try {
            PackPaths paths = PackPaths.workingDir();
            String modpackName = ConfigIO.loadClient(paths.clientConfigFile()).selectedModpack;

            if (modpackName == null || modpackName.isBlank()) {
                tell(mc, "[PackSync] 还没记录任何整合包 —— 请先连一次服务器。", ChatFormatting.RED);
                return 0;
            }
            if (!LocalManifest.exists(paths, modpackName)) {
                tell(mc, "[PackSync] 本地没有整合包「" + modpackName + "」的清单，无法比对。", ChatFormatting.RED);
                tell(mc, "  连一次服务器让它同步下来，之后再敲这条指令即可。", ChatFormatting.GRAY);
                return 0;
            }

            PackManifest manifest = ConfigIO.read(
                    paths.modpackDir(modpackName).resolve(paths.manifestFile().getFileName()),
                    PackManifest.class);
            if (manifest == null || manifest.files() == null) {
                tell(mc, "[PackSync] 清单读取失败（文件可能损坏）。", ChatFormatting.RED);
                return 0;
            }

            // 本地 mods 目录实际内容：文件名 → 字节数
            Path modsDir = paths.root().resolve("mods");
            Map<String, Long> local = new LinkedHashMap<>();
            if (Files.isDirectory(modsDir)) {
                try (Stream<Path> s = Files.list(modsDir)) {
                    s.filter(Files::isRegularFile).forEach(f -> {
                        String n = f.getFileName().toString();
                        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            try {
                                local.put(n, Files.size(f));
                            } catch (Throwable ignored) {
                                local.put(n, -1L);
                            }
                        }
                    });
                }
            }

            // 与清单逐条比对（清单路径形如 /mods/xxx.jar）
            List<String> missing = new ArrayList<>();
            List<String> sizeMismatch = new ArrayList<>();
            Map<String, Boolean> expected = new LinkedHashMap<>();
            for (PackManifest.PackFile f : manifest.files()) {
                if (f == null || f.path == null || !f.path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                String name = f.path.substring(f.path.lastIndexOf('/') + 1);
                expected.put(name, Boolean.TRUE);
                if (!local.containsKey(name)) {
                    missing.add(name);
                } else if (f.size > 0 && local.get(name) != f.size) {
                    sizeMismatch.add(name + "（本地 " + local.get(name) + " / 服务器 " + f.size + " 字节）");
                }
            }
            List<String> extra = new ArrayList<>();
            for (String n : local.keySet()) {
                if (!expected.containsKey(n)) {
                    extra.add(n);
                }
            }

            int checked = expected.size();
            int ok = checked - missing.size() - sizeMismatch.size();

            tell(mc, "[PackSync] 校验整合包「" + modpackName + "」：共 " + checked + " 个文件", ChatFormatting.WHITE);
            if (missing.isEmpty() && sizeMismatch.isEmpty()) {
                tell(mc, "  ✓ 全部就位（" + ok + " / " + checked + "）", ChatFormatting.GREEN);
            } else {
                tell(mc, "  ✓ 就位 " + ok + " 个", ChatFormatting.GREEN);
                dump(mc, "  ✗ 缺失 " + missing.size() + " 个", missing, ChatFormatting.RED);
                dump(mc, "  ✗ 大小不符 " + sizeMismatch.size() + " 个", sizeMismatch, ChatFormatting.RED);
            }
            if (!extra.isEmpty()) {
                // 多出来的不一定有问题（很多 mod 是客户端专有的），所以只提示不报警。
                dump(mc, "  · 本地多出 " + extra.size() + " 个（服务器清单里没有，通常正常）",
                        extra, ChatFormatting.GRAY);
            }
            if (!missing.isEmpty() || !sizeMismatch.isEmpty()) {
                tell(mc, "  修法：连一次服务器让 PackSync 自动补齐，或点主界面的「同步已记录的服务器」。",
                        ChatFormatting.YELLOW);
            }
            return 1;
        } catch (Throwable t) {
            PackSync.LOGGER.error("[PackSync] 校验失败", t);
            tell(mc, "[PackSync] 校验出错：" + t, ChatFormatting.RED);
            return 0;
        }
    }

    /** 最多列 10 条，其余折叠 —— 一次刷满屏聊天栏比不显示更糟。 */
    private static void dump(Minecraft mc, String title, List<String> items, ChatFormatting color) {
        if (items.isEmpty()) {
            return;
        }
        tell(mc, title, color);
        int shown = 0;
        for (String it : items) {
            if (shown >= 10) {
                tell(mc, "    …以及另外 " + (items.size() - shown) + " 个", ChatFormatting.DARK_GRAY);
                break;
            }
            tell(mc, "    " + it, ChatFormatting.DARK_GRAY);
            shown++;
        }
    }

    /** 发到聊天栏（玩家在世界里时走 HUD 聊天，否则也能看到）。 */
    private static void tell(Minecraft mc, String text, ChatFormatting color) {
        Component msg = Component.literal(text).withStyle(color);
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, false);
        } else if (mc.gui != null) {
            mc.gui.getChat().addMessage(msg);
        }
    }
}
