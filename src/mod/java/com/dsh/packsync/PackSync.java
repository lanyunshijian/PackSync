package com.dsh.packsync;

import com.dsh.packsync.core.PackSyncCore;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 游戏内入口。启动期的工作由 {@code com.dsh.packsync.bootstrap.PackSyncModLocator}
 * 在 mod 加载之前完成；这里负责游戏内的部分（命令、存在性握手、界面）。
 */
@Mod(PackSyncCore.MOD_ID_INNER)
public class PackSync {

    public static final Logger LOGGER = LoggerFactory.getLogger(PackSyncCore.MOD_NAME);

    public PackSync() {
        LOGGER.info("{} 游戏内入口已加载", PackSyncCore.describe());
        // 通道两侧都要注册 —— 只在一侧注册会在登录校验阶段被判定为不匹配。
        PackSyncPresence.register();
    }
}
