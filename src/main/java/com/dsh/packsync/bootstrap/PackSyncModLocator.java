package com.dsh.packsync.bootstrap;

import com.dsh.packsync.core.PackSyncCore;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 启动期介入点（等价于同类模组的早期定位器）。
 *
 * <p>Forge 在扫描 {@code mods/} 目录时会通过 {@code ServiceLoader} 发现本类
 * （见 {@code META-INF/services/net.minecraftforge.forgespi.locating.IModLocator}），
 * 并在**真正的 mod 加载之前**调用 {@link #scanCandidates()}。整合包的比对与下载
 * 就发生在那一刻 —— 这是"缺 mod 也能连上服务器"的根源。
 *
 * <p><b>类加载约束</b>：本类的类加载器没有 Minecraft 类。因此这里只能使用
 * JDK 类、Forge 的 SPI 类（{@code net.minecraftforge.forgespi.*} 与
 * {@code fml.loading.moddiscovery.*}）以及 {@code core} 包中同样零 MC 依赖的类。
 *
 * <p>{@link #scanCandidates()} 返回的是"需要额外加载的 mod 文件路径"。
 * 整合包里的 mod 走 {@code mods/} 的常规路径，因此这里返回空流；
 * 该方法存在的意义是**获得启动期的执行时机**。
 */
public class PackSyncModLocator extends AbstractJarFileModLocator {

    private static final String TAG = "[PackSync][bootstrap] ";

    @Override
    public String name() {
        return PackSyncCore.MOD_ID;
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        // 目前不接受启动参数。
    }

    @Override
    public Stream<Path> scanCandidates() {
        try {
            System.out.println(TAG + "IModLocator.scanCandidates() 已被调用 —— 启动期介入成功");
            System.out.println(TAG + "同 jar 的 core 类可加载：" + PackSyncCore.describe());

            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            System.out.println(TAG + "工作目录(user.dir) = " + cwd);

            // ★ 启动期同步：此刻任何 mod 都还没被加载，是替换 mods/ 内容的唯一时机。
            //   客户端缺 mod 时 Forge 会在登录阶段就断开，游戏内代码根本没机会运行，
            //   所以"缺 mod 也能连上服务器"必须在这一步解决。
            Preload.Outcome outcome = Preload.run();
            System.out.println(TAG + "启动期同步结果：" + outcome.message());
        } catch (Throwable t) {
            // locator 阶段任何异常都会让游戏起不来，这里必须兜住。
            System.out.println(TAG + "启动期同步异常（已忽略，游戏照常启动）：" + t);
        }
        // 这里只负责"拿到启动期时机"，**不返回任何候选**。
        //
        // 为什么不能返回自身路径：实测在生产 Forge 服务端上，本阶段
        // getProtectionDomain().getCodeSource().getLocation() 返回的是 "/" 而不是 jar 路径
        // （locator 的类加载器不绑定真实文件），返回它会直接导致：
        //   java.lang.module.ResolutionException: Module forge reads more than one module named packsync
        // 因此"让本 jar 同时作为游戏内 mod 被加载"必须交给
        // PackSyncModLoader（IDependencyLocator）—— 那里能拿到真实路径。
        return Stream.empty();
    }
}
