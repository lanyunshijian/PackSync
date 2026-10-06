package com.dsh.packsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /packsync} 服务端命令树。
 *
 * <p><b>权限分层</b>：所有能改变"玩家会收到什么文件"的子命令 —— {@code generate}、
 * {@code host *}、{@code config reload} —— 一律要求 <b>OP 等级 3</b>，
 * 它们属于管理员职责。
 *
 * <p>普通玩家能用的只有 {@code /packsync verify}：只看不改，用来核对自己本地的
 * mod 与服务器清单是否一致（见 {@link com.dsh.packsync.client.PackSyncClientCommands}，
 * 客户端侧执行、不需要任何服务端权限）。
 *
 * <p>所有子命令都是**同步执行**的：生成清单通常不到一秒，
 * 异步化反而会让回复时序变得难以理解（玩家敲完命令却看不到结果）。
 * 唯一的例外是重启（含启动）—— 它会真的去 bind 端口，因此给出明确的失败信息。
 */
public final class PackSyncCommands {

    private PackSyncCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("packsync")
                .executes(PackSyncCommands::about);

        // ── /packsync generate ────────────────────────────────────────────
        root.then(Commands.literal("generate")
                .requires(PackSyncCommands::isAdmin)
                .executes(ctx -> reply(ctx, PackSyncHost.regenerate())));

        // ── /packsync host ───────────────────────────────────────────────
        LiteralArgumentBuilder<CommandSourceStack> host = Commands.literal("host")
                .requires(PackSyncCommands::isAdmin)
                .executes(ctx -> reply(ctx, PackSyncHost.status()));

        host.then(Commands.literal("start")
                .executes(ctx -> reply(ctx, PackSyncHost.start())));
        host.then(Commands.literal("stop")
                .executes(ctx -> reply(ctx, PackSyncHost.stop())));
        host.then(Commands.literal("restart")
                .executes(ctx -> reply(ctx, PackSyncHost.restart())));
        host.then(Commands.literal("status")
                .executes(ctx -> reply(ctx, PackSyncHost.status())));
        host.then(Commands.literal("fingerprint")
                .executes(ctx -> reply(ctx,
                        "服务器指纹：" + PackSyncHost.fingerprint()
                                + "\n把这个值发给玩家，他们在首次连接时核对")));
        host.then(Commands.literal("keys")
                .executes(ctx -> reply(ctx,
                        "密钥目录：" + PackSyncHost.keysDirectory()
                                + "\n（含 server-identity.key 与 fingerprint.txt）")));
        root.then(host);

        // ── /packsync config reload ──────────────────────────────────────
        root.then(Commands.literal("config")
                .requires(PackSyncCommands::isAdmin)
                .then(Commands.literal("reload")
                        .executes(ctx -> reply(ctx, PackSyncHost.reload()))));

        var node = dispatcher.register(root);
        // 别名：/pks 少打字。用不常见的组合避免与别的 mod 撞车。
        dispatcher.register(Commands.literal("pks").redirect(node));
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(3);
    }

    private static int about(CommandContext<CommandSourceStack> ctx) {
        boolean admin = isAdmin(ctx.getSource());
        StringBuilder sb = new StringBuilder();
        sb.append("PackSync —— 服务端自动分发整合包\n");
        // 普通玩家只看得到这一条：它能查出"我本地缺哪个 mod"，且不做任何改动。
        sb.append("/packsync verify                    校验你本地的 mod 与服务器清单是否一致\n");
        if (admin) {
            sb.append("—— 以下需要管理员权限 ——\n");
            sb.append("/packsync generate                 重新生成整合包清单\n");
            sb.append("/packsync host [start|stop|restart|status]   托管控制\n");
            sb.append("/packsync host fingerprint          显示服务器指纹（发给玩家）\n");
            sb.append("/packsync host keys                 显示密钥目录位置\n");
            sb.append("/packsync config reload             重载配置并重启托管\n");
        }
        sb.append("（/pks 是别名）");
        return reply(ctx, sb.toString());
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
        CommandSourceStack source = ctx.getSource();
        for (String line : message.split("\n")) {
            MutableComponent text = Component.literal(line).withStyle(ChatFormatting.GREEN);
            // 第二条起用普通样式，避免整屏都是绿色把重点冲淡。
            source.sendSuccess(() -> text, false);
        }
        return 1;
    }
}
