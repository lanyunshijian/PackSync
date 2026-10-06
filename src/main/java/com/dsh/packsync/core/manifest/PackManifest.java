package com.dsh.packsync.core.manifest;

import java.util.ArrayList;
import java.util.List;

/**
 * 整合包清单：服务端发布、客户端比对的唯一权威数据。
 *
 * <p>零 MC 依赖，可 Gson 直接序列化，也便于放进加密通道或落盘成 JSON。
 *
 * <p>字段设计刻意保持扁平、自描述，
 * 因为这套结构足以表达「mods/config/资源包/光影/任意文件」
 * 的全部同步语义。
 */
public class PackManifest {

    /** 清单结构版本。 */
    public int manifestVersion = 1;

    /** 整合包名（客户端 modpacks/<名字>/ 的目录名来源）。 */
    public String modpackName = "";

    public String mcVersion = "";
    public String loader = "";
    public String loaderVersion = "";

    /** 服务端自报名称，仅用于界面显示。 */
    public String serverName = "";

    /**
     * 服务端所用的 PackSync 版本。
     *
     * <p>用途：客户端启动期同步时比对，版本不一致就明确告知玩家 ——
     * 协议可能因此不兼容，早点说清楚比让玩家遇到"莫名其妙的握手失败"好得多。
     * 这也正是"自我更新"在本 mod 语境下的实际形态：PackSync 未发布到公共站，
     * 与其去连 Modrinth，不如保证**两端版本一致**。
     */
    public String packsyncVersion = "";

    /** 生成时间（毫秒），用于日志与排障。 */
    public long generatedAt = 0L;

    /** 全部文件条目。 */
    public List<PackFile> files = new ArrayList<>();

    /** 下发给客户端的删除清单。 */
    public List<ToDelete> filesToDelete = new ArrayList<>();

    public PackManifest() {
    }

    public List<PackFile> files() {
        return files == null ? List.of() : files;
    }

    public List<ToDelete> filesToDelete() {
        return filesToDelete == null ? List.of() : filesToDelete;
    }

    /** 按路径取条目，找不到返回 null。 */
    public PackFile find(String path) {
        for (PackFile f : files()) {
            if (f != null && f.path != null && f.path.equals(path)) {
                return f;
            }
        }
        return null;
    }

    public long totalBytes() {
        long sum = 0L;
        for (PackFile f : files()) {
            if (f != null) {
                sum += f.size;
            }
        }
        return sum;
    }

    /**
     * 单个文件条目。
     *
     * <p>{@code path} 是**以 {@code /} 开头的客户端相对路径**（如 {@code /mods/foo.jar}），
     * 这是服务端与客户端之间唯一的路径约定：两边都相对各自根目录解析它。
     */
    public static class PackFile {

        public String path = "";

        /** 文件字节数。 */
        public long size = 0L;

        /** SHA-1 十六进制；比对与传输校验的唯一依据。 */
        public String sha1 = "";

        /** CurseForge 指纹（仅 .jar 计算，用于公共站反查）。非 jar 为 null。 */
        public String murmur = null;

        /** 分类：mod / config / shader / resourcepack / mc_options / other。 */
        public String type = "other";

        /** 客户端可自行修改：只下载一次，之后不再覆盖。 */
        public boolean editable = false;

        /** 强制复制到标准位置（即使是 editable 也覆盖）。 */
        public boolean forceCopy = false;

        public PackFile() {
        }

        public PackFile(String path, long size, String sha1, String murmur, String type,
                        boolean editable, boolean forceCopy) {
            this.path = path;
            this.size = size;
            this.sha1 = sha1;
            this.murmur = murmur;
            this.type = type;
            this.editable = editable;
            this.forceCopy = forceCopy;
        }

        public boolean isMod() {
            return "mod".equals(type);
        }

        @Override
        public String toString() {
            return "PackFile{" + path + ", " + size + "B, sha1=" + sha1 + ", type=" + type
                    + (editable ? ", editable" : "") + (forceCopy ? ", forceCopy" : "") + "}";
        }
    }

    /**
     * 删除条目。带 sha1 是为了**只在客户端那份确实是同一文件时才删** ——
     * 避免玩家自己替换过的文件被服务器一句话抹掉。
     */
    public static class ToDelete {

        public String path = "";
        public String sha1 = "";

        /** 服务端生成的时间戳，客户端用它保证同一条指令只处理一次。 */
        public String timestamp = "";

        public ToDelete() {
        }

        public ToDelete(String path, String sha1, String timestamp) {
            this.path = path;
            this.sha1 = sha1;
            this.timestamp = timestamp;
        }
    }
}
