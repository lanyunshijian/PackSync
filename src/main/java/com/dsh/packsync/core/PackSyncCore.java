package com.dsh.packsync.core;

/**
 * PackSync 核心层的根常量与诊断入口。
 *
 * <p>这个类**刻意不引用任何 Minecraft / Forge 类**：它必须能被
 * {@code IModLocator} 的类加载器加载 —— 那个加载器在 Forge 扫描 mods 目录时
 * 就已存在，此时 MC 的类还没准备好。整个 {@code core} 包都遵守这条约束。
 */
public final class PackSyncCore {

    public static final String MOD_ID = "packsync";

    /**
     * 内层（游戏内）模块的 modId。
     *
     * <p><b>必须与 {@code @Mod} 注解的值、以及内层 mods.toml 里的 modId 三者完全一致。</b>
     * Forge 会用 {@code @Mod} 的值去 mods.toml 里找元数据，对不上就报
     * {@code mods.toml missing metadata for modid <值>} 并让整个游戏启动失败 ——
     * 这个错误不会提示"注解写错了"，排查成本很高，所以在此显式记录。
     *
     * <p>为什么不直接复用 {@link #MOD_ID}：外层 jar 提供 IModLocator，其模块名已占用
     * {@code packsync}；两层必须是不同模块名，否则报
     * {@code java.lang.module.ResolutionException: Module forge reads more than one module named packsync}。
     */
    public static final String MOD_ID_INNER = "packsync_mod";
    public static final String MOD_NAME = "PackSync";
    public static final String VERSION = "1.0.0";

    /** 密钥目录名：相对于**服务端根目录**（不是 config/）。 */
    public static final String SERVER_KEYS_DIR = "modpack-keys";

    private PackSyncCore() {
    }

    public static String describe() {
        return MOD_NAME + " core v" + VERSION + " (modId=" + MOD_ID + ")";
    }
}
