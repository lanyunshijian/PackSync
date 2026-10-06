package com.dsh.packsync.bootstrap;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把内层（游戏内）jar 装载为独立的 mod 模块。
 *
 * <p><b>为什么需要它</b>（三条都是实测结论，不是设计偏好）：
 * <ol>
 *   <li>一个 jar 一旦出现在 {@code META-INF/services/…IModLocator} 里，Forge 的常规
 *       {@code mods/} 扫描器就**不再**把它当普通 mod 加载（生产服务端日志里完全没有
 *       该 mod 的加载痕迹，而 dev 环境走 classpath 时有）。只注册 IModLocator
 *       会让 {@code @Mod} 类永远不被实例化。</li>
 *   <li>在 {@code IModLocator} 阶段也**拿不到自身 jar 的真实路径**
 *       （{@code ProtectionDomain} 返回 {@code "/"}）。</li>
 *   <li>试图"自己加载自己"会直接崩：
 *       {@code java.lang.module.ResolutionException: Module forge reads more than one module named packsync}
 *       —— 同一个 jar 不能既是 locator 提供者模块又是 mod 模块。</li>
 * </ol>
 *
 * <p>因此采用两层结构：外层 jar 负责提供 locator，内层 jar
 * （{@code META-INF/jarjar/packsync-mod.jar}，模块名 {@code packsync_mod}）才是真正的
 * 游戏内 mod。两者模块名不同所以不冲突；内层通过 Forge 的自动模块机制可以 import
 * 外层的 core / crypto 类。
 */
public class PackSyncModLoader extends AbstractJarFileDependencyLocator {

    private static final String TAG = "[PackSync][bootstrap] ";
    private static final String INNER_JAR_ENTRY = "META-INF/packsync/packsync-mod.jar";

    @Override
    public String name() {
        return "packsync-mod-loader";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        // 不接受启动参数。
    }

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        List<IModFile> mods = new ArrayList<>(1);
        try {
            Path outerJar = locateOuterJar();
            if (outerJar == null) {
                System.out.println(TAG + "IDependencyLocator：找不到外层 jar，游戏内部分不会加载");
                return mods;
            }

            // 把内层 jar 从外层 jar 里提取到临时文件，再交给 createMod。
            //
            // 为什么不用 Forge 的 jij: 文件系统直接打开（同类模组的做法）：
            // 实测 createMod 会对传入路径做存在性检查，而 jij: 文件系统的根路径
            // "/" 通不过该检查，报
            //   Invalid paths argument, contained no existing paths: []
            // 提取到真实磁盘文件后没有任何歧义，且失败模式简单可控。
            Path innerJar = extractInnerJar(outerJar);
            if (innerJar == null) {
                System.out.println(TAG + "IDependencyLocator：外层 jar 里没有 " + INNER_JAR_ENTRY
                        + "（构建时未嵌入？），游戏内部分不会加载");
                return mods;
            }

            IModFile mod = createMod(innerJar).file();
            System.out.println(TAG + "IDependencyLocator：游戏内模块已装载 <- " + innerJar);
            mods.add(mod);
        } catch (Exception e) {
            // 抛异常会让整个游戏起不来。宁可退化成"没有游戏内部分"，
            // 也不能因为这个加载器出问题就阻断服务器启动。
            System.out.println(TAG + "IDependencyLocator：装载游戏内模块失败：" + e);
            e.printStackTrace();
        }
        return mods;
    }

    /** 从外层 jar 提取内层 jar 到临时文件；不存在则返回 null。 */
    private static Path extractInnerJar(Path outerJar) {
        try (ZipFile zip = new ZipFile(outerJar.toFile())) {
            ZipEntry entry = zip.getEntry(INNER_JAR_ENTRY);
            if (entry == null) {
                return null;
            }
            // 文件名必须是**干净的、固定的** "packsync-mod.jar"：
            // Forge 会依据文件名推导模块名，而 createMod 之后还要与该 jar
            // mods.toml 里的 modId（packsync_mod）对应上。
            // 实测用 Files.createTempFile("packsync-mod-", ".jar") 这种带随机后缀的名字会
            // 让推导结果对不上，报：
            //   mods.toml missing metadata for modid packsync
            Path tempDir = Files.createTempDirectory("packsync-inner-");
            tempDir.toFile().deleteOnExit();
            Path tmp = tempDir.resolve("packsync-mod.jar");
            tmp.toFile().deleteOnExit();
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (IOException e) {
            System.out.println(TAG + "提取内层 jar 失败：" + e);
            return null;
        }
    }

    /**
     * 定位外层 jar。
     *
     * <p>优先用 {@code ProtectionDomain}，但它在 locator 类加载器下实测返回 {@code "/"}，
     * 因此必须有回退：**扫描 {@code mods/} 找内含本类字节码的 jar**。
     * 按内容匹配不依赖类加载器给出路径，实践中稳定成立。
     */
    private static Path locateOuterJar() {
        final String marker = "com/dsh/packsync/bootstrap/PackSyncModLoader.class";

        try {
            URL location = PackSyncModLoader.class.getProtectionDomain().getCodeSource().getLocation();
            Path candidate = Path.of(location.toURI());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            System.out.println(TAG + "ProtectionDomain 未给出真实路径（" + candidate + "），改用 mods/ 扫描");
        } catch (URISyntaxException | RuntimeException e) {
            System.out.println(TAG + "ProtectionDomain 不可用（" + e + "），改用 mods/ 扫描");
        }

        Path modsDir = Path.of(System.getProperty("user.dir")).resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return null;
        }
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    if (zip.getEntry(marker) != null) {
                        return jar;
                    }
                } catch (IOException ignored) {
                    // 非法 zip（例如 .jar.disabled），跳过继续找。
                }
            }
        } catch (IOException e) {
            System.out.println(TAG + "扫描 mods/ 失败：" + e);
        }
        return null;
    }
}
