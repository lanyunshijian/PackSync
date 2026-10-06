package com.dsh.packsync.core.manifest;

import com.dsh.packsync.core.config.ConfigIO;
import com.dsh.packsync.core.util.PackPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地"这个整合包我装过了"的凭据。
 *
 * <p><b>为什么需要它</b>：登录期与启动期都要回答同一个问题 ——「本地装过这个包没有？」
 * 判据是 {@code <游戏目录>/packsync/modpacks/<包名>/manifest.json} 是否存在。
 * 有清单 = 之前同步过，之后的增量更新交给启动期同步；没有 = 首次安装，必须停下来下载。
 *
 * <p>这个文件曾经<b>只被读、从来没被写过</b>（{@code ClientLoginHandler.hasLocalModpack}
 * 与 {@code ServerInfoHandler.isFirstInstall} 都在读它，保存却漏了）。后果非常严重：
 * 判定永远是"首次安装" → 每次连服都强制断开重新下载 → 同步明明成功，玩家却永远进不去世界。
 *
 * <p>所以同步成功之后，<b>必须</b>调用这里把它落盘。
 */
public final class LocalManifest {

    private LocalManifest() {
    }

    /**
     * 保存清单作为"已安装"凭据。失败只记日志不抛出 ——
     * 最坏后果是下次连接重新同步一遍，不该因此让同步本身失败。
     */
    public static void save(PackPaths paths, String modpackName, PackManifest manifest) {
        if (paths == null || modpackName == null || modpackName.isBlank() || manifest == null) {
            return;
        }
        try {
            Path dir = paths.modpackDir(modpackName);
            Files.createDirectories(dir);
            Path target = dir.resolve(paths.manifestFile().getFileName());
            ConfigIO.write(target, manifest);
        } catch (Throwable t) {
            // 只警告：丢了凭据最多让下次多同步一遍。
            System.err.println("[PackSync] 保存本地清单失败（下次连接会重新同步）：" + t);
        }
    }

    /** 本地是否已有该整合包的清单（= 装过）。 */
    public static boolean exists(PackPaths paths, String modpackName) {
        if (paths == null || modpackName == null || modpackName.isBlank()) {
            return false;
        }
        try {
            Path manifest = paths.modpackDir(modpackName).resolve(paths.manifestFile().getFileName());
            return Files.isRegularFile(manifest);
        } catch (Throwable t) {
            return false;
        }
    }
}
