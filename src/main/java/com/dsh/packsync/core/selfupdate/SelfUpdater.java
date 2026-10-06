package com.dsh.packsync.core.selfupdate;

import com.dsh.packsync.core.PackSyncCore;
import com.dsh.packsync.core.transfer.PackClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 自我更新：把客户端自己的 PackSync jar 换成与服务端一致的版本。
 *
 * <p><b>为什么是"从服务端拿"而不是"从 Modrinth 拿"</b>：
 * 发布在公共站的模组可以用 Modrinth API 检查新版本。
 * PackSync 是自建 mod，公共站上没有；此时**唯一能保证两端版本一致的来源就是服务端自己**。
 * 版本不一致不是小事 —— 协议可能不兼容，表现是"莫名其妙的握手失败"，
 * 与其让玩家去猜，不如直接把版本对齐。
 *
 * <p><b>为什么只下载不自动替换</b>：
 * 当前进程正持有自己的 jar（Windows 上尤其无法删除/覆盖）。
 * 强行自我删除在 Linux 上也许能成功，但会留下"删掉了旧的、新的又没生效"的
 * 半残状态。所以策略是：<b>把新版本放进 mods/，然后明确提示玩家重启并删除旧版</b>
 * —— 多一步人工操作，换"绝不会把玩家搞到起不来"。
 */
public final class SelfUpdater {

    /** 更新结果。 */
    public record Result(Status status, Path downloaded, String version, String message) {

        public enum Status {
            /** 版本一致，无需动作。 */
            UP_TO_DATE,
            /** 已下载新版本，需要玩家重启并删除旧 jar。 */
            DOWNLOADED,
            /** 服务端没提供自我更新包。 */
            NOT_AVAILABLE,
            /** 失败。 */
            FAILED
        }

        public boolean needsRestart() {
            return status == Status.DOWNLOADED;
        }
    }

    private SelfUpdater() {
    }

    /**
     * 比对版本并在需要时下载。
     *
     * @param client      已握手的客户端
     * @param modsDir     客户端 mods 目录（新 jar 落点）
     * @param serverVersion 服务端清单里的 PackSync 版本
     */
    public static Result updateIfNeeded(PackClient client, Path modsDir, String serverVersion) {
        String local = PackSyncCore.VERSION;
        if (serverVersion == null || serverVersion.isBlank() || serverVersion.equals(local)) {
            return new Result(Result.Status.UP_TO_DATE, null, local, "版本一致（" + local + "）");
        }

        try {
            var self = client.downloadSelf();
            if (self.isEmpty()) {
                return new Result(Result.Status.NOT_AVAILABLE, null, local,
                        "服务端未提供自我更新包（本机 " + local + "，服务端 " + serverVersion + "）");
            }
            PackClient.SelfPackage pkg = self.get();
            Path target = modsDir.resolve(pkg.fileName());

            // 已经有同名文件就不重复写（多半是上次更新过、只是还没删旧版）。
            if (Files.isRegularFile(target)) {
                return new Result(Result.Status.DOWNLOADED, target, serverVersion,
                        "新版本已存在于 mods/：" + target.getFileName());
            }

            Files.createDirectories(modsDir);
            Path tmp = target.resolveSibling(target.getFileName() + ".packsync-tmp");
            Files.write(tmp, pkg.data(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }

            System.out.println("[PackSync][selfupdate] 已下载与服务端一致的版本：" + target.getFileName());
            System.out.println("[PackSync][selfupdate] 请重启游戏，并删除旧版 "
                    + "packsync-" + local + ".jar 以避免重复加载。");
            return new Result(Result.Status.DOWNLOADED, target, serverVersion,
                    "已下载 " + target.getFileName() + "；请重启游戏并删除旧版 packsync-" + local + ".jar");
        } catch (IOException | RuntimeException e) {
            return new Result(Result.Status.FAILED, null, local, "自我更新失败：" + e);
        }
    }

    /** 尝试清理旧版本 jar（在**新版已经就位**的前提下才做，失败也无所谓）。 */
    public static boolean tryRemoveOld(Path modsDir, Path currentJar, String keepFileName) {
        try {
            if (currentJar == null || !Files.isRegularFile(currentJar)) {
                return false;
            }
            if (keepFileName != null && currentJar.getFileName().toString().equals(keepFileName)) {
                return false;
            }
            Files.delete(currentJar);
            return true;
        } catch (IOException e) {
            // 正在被 JVM 持有（Windows 常见）—— 留给玩家手动删。
            return false;
        }
    }
}
