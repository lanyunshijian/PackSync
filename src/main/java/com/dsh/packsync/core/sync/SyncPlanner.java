package com.dsh.packsync.core.sync;

import com.dsh.packsync.core.manifest.PackManifest;
import com.dsh.packsync.core.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 ↔ 服务端清单的比对。
 *
 * <p>只做"算清楚差异"，不做下载（下载在 {@link SyncEngine}）。
 * 分开是为了让比对逻辑能被独立测试 —— 它是整个同步正确性的核心，
 * 且错法很隐蔽：多下会浪费时间，<b>少下会让玩家带着错误的 mod 进服</b>。
 *
 * <p>三条判定规则，按优先级：
 * <ol>
 *   <li>本地没有该文件 → 需要下载</li>
 *   <li>{@code editable} 且本地已存在 → <b>跳过</b>（玩家改过的配置不该被覆盖）</li>
 *   <li>本地哈希与清单不符 → 需要下载</li>
 * </ol>
 *
 * <p>比对位置是**游戏目录**（{@code /mods/x.jar → <游戏目录>/mods/x.jar}），
 * 因为那才是玩家实际运行游戏时读取的地方。
 */
public final class SyncPlanner {

    private final Path gameDir;

    public SyncPlanner(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** 单个文件的比对结果。 */
    public enum Action {
        /** 本地已是最新，无需动作。 */
        UP_TO_DATE,
        /** 需要下载并写入。 */
        DOWNLOAD,
        /** 是可编辑文件且本地已存在，保留玩家版本。 */
        KEEP_EDITABLE
    }

    /** 比对计划。 */
    public record Plan(List<PackManifest.PackFile> toDownload,
                       List<PackManifest.PackFile> keepEditable,
                       int upToDateCount,
                       List<PackManifest.ToDelete> toDelete) {

        public boolean needsAnything() {
            return !toDownload.isEmpty() || !toDelete.isEmpty();
        }

        /** 是否需要重启游戏才能生效（有 mod 类文件变动时）。 */
        public boolean requiresRestart() {
            return toDownload.stream().anyMatch(PackManifest.PackFile::isMod)
                    || toDelete.stream().anyMatch(d -> d.path != null && d.path.endsWith(".jar"));
        }

        public long totalBytes() {
            return toDownload.stream().mapToLong(f -> f.size).sum();
        }

        public String describe() {
            return "需下载 " + toDownload.size() + " 个（" + Hashing.humanSize(totalBytes()) + "）"
                    + "，保留玩家文件 " + keepEditable.size() + " 个"
                    + "，已最新 " + upToDateCount + " 个"
                    + "，待删除 " + toDelete.size() + " 个";
        }
    }

    /**
     * 算出需要下载/保留/删除的内容。
     *
     * @param remote 服务端清单
     * @param allowRemoteDeletions 是否允许执行服务端下发的删除指令
     */
    public Plan plan(PackManifest remote, boolean allowRemoteDeletions) {
        List<PackManifest.PackFile> toDownload = new ArrayList<>();
        List<PackManifest.PackFile> keepEditable = new ArrayList<>();
        int upToDate = 0;

        for (PackManifest.PackFile f : remote.files()) {
            if (f == null || f.path == null || f.sha1 == null) {
                continue;
            }
            Path local = localPath(f.path);

            if (!Files.isRegularFile(local)) {
                toDownload.add(f);
                continue;
            }
            // editable 且本地存在：保留玩家的版本，不下载也不覆盖。
            if (f.editable) {
                keepEditable.add(f);
                continue;
            }
            String localHash = Hashing.sha1(local);
            if (localHash != null && localHash.equalsIgnoreCase(f.sha1)) {
                upToDate++;
            } else {
                toDownload.add(f);
            }
        }

        List<PackManifest.ToDelete> deletes = allowRemoteDeletions
                ? List.copyOf(remote.filesToDelete())
                : List.of();

        return new Plan(toDownload, keepEditable, upToDate, deletes);
    }

    /**
     * 删除清单条目对应的本地文件。
     *
     * <p><b>只删哈希匹配的那一个</b>：如果玩家自己换过这个文件，就不动它。
     * 这条规则很重要 —— 服务器一句"删掉 x.jar"不该抹掉玩家手工替换的东西。
     *
     * @return 实际删除的文件数
     */
    public int applyDeletions(List<PackManifest.ToDelete> deletions) {
        int removed = 0;
        for (PackManifest.ToDelete d : deletions) {
            if (d == null || d.path == null || d.path.isBlank()) {
                continue;
            }
            Path target = localPath(d.path);
            if (!Files.isRegularFile(target)) {
                continue;
            }
            if (d.sha1 != null && !d.sha1.isBlank()) {
                String hash = Hashing.sha1(target);
                if (hash == null || !hash.equalsIgnoreCase(d.sha1)) {
                    System.out.println("[PackSync] 不删除 " + d.path
                            + "（本地内容与服务器记录不符，可能是玩家自己替换的）");
                    continue;
                }
            }
            try {
                Files.delete(target);
                removed++;
                System.out.println("[PackSync] 已按服务器要求删除 " + d.path);
            } catch (IOException e) {
                System.err.println("[PackSync] 删除失败 " + d.path + " -> " + e);
            }
        }
        return removed;
    }

    /** 把清单路径还原成游戏目录下的真实路径（并防路径穿越）。 */
    public Path localPath(String manifestPath) {
        String rel = manifestPath == null ? "" : manifestPath.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path resolved = gameDir.resolve(rel).normalize();
        // 防御：清单来自网络，必须确保解析结果仍在游戏目录内。
        if (!resolved.startsWith(gameDir.normalize())) {
            throw new IllegalArgumentException("清单路径越界（疑似恶意服务器）：" + manifestPath);
        }
        return resolved;
    }

    public Path gameDir() {
        return gameDir;
    }
}
