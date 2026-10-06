package com.dsh.packsync.core.sync;

import com.dsh.packsync.core.manifest.ModInspector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 重复 mod 剔除与嵌套 mod 提升。
 *
 * <p>整合包同步最麻烦的地方不在"把文件下下来"，而在"下下来之后别把玩家的实例搞坏"。
 * 典型场景：
 * <ul>
 *   <li>玩家自己的 {@code mods/} 里已经有一个 mod，整合包也带了一份 ——
 *       两份同时加载会因为 modId 重复直接崩；</li>
 *   <li>但**不能简单删掉玩家那份**：可能别的 mod 依赖它，
 *       删了这个依赖链就断了；</li>
 *   <li>有些 mod 把自己的依赖打包成 jar-in-jar 藏在
 *       {@code META-INF/jarjar/} 里，那份也需要被"提升"到可见位置。</li>
 * </ul>
 *
 * <p>本类的策略与常见做法一致，核心是**依赖闭包**：
 * 先算出"被依赖的 mod 集合"，只对**不在**该集合里的重复项做删除；
 * 在集合里的则用整合包版本覆盖（保持版本与服务器一致）。
 * 宁可多留一个 mod，也不要删掉别人依赖的那个。
 *
 * <p>零 MC 依赖，可纯 JUnit 验证。
 */
public final class ModDeduplicator {

    /** 一个有 mod 元数据的 jar。 */
    public record ModFile(Path path, ModInspector.ModInfo info) {

        public String modId() {
            return info.modId();
        }

        public String hashKey() {
            return info.modId() + "@" + info.version();
        }
    }

    /**
     * 处理计划。
     *
     * @param deleteFromStandard 从标准 mods/ 删除（整合包里有同 id 且无人依赖）
     * @param replaceInStandard  用整合包版本覆盖标准 mods/ 里的同名 mod（有人依赖，必须版本一致）
     * @param promoteNested      把嵌套（jar-in-jar）里的 mod 提升到标准 mods/
     * @param keepStandard       明确保留、不动
     */
    public record Plan(List<ModFile> deleteFromStandard,
                       List<ModFile> replaceInStandard,
                       List<ModFile> promoteNested,
                       List<ModFile> keepStandard) {

        public boolean isNoop() {
            return deleteFromStandard.isEmpty() && replaceInStandard.isEmpty() && promoteNested.isEmpty();
        }

        public boolean requiresRestart() {
            return !deleteFromStandard.isEmpty() || !replaceInStandard.isEmpty() || !promoteNested.isEmpty();
        }

        public String describe() {
            return "重复剔除：删除 " + deleteFromStandard.size()
                    + "，覆盖 " + replaceInStandard.size()
                    + "，提升嵌套 " + promoteNested.size()
                    + "，保留 " + keepStandard.size();
        }
    }

    private ModDeduplicator() {
    }

    /**
     * 计算处理计划。
     *
     * @param standardMods 玩家标准 {@code mods/} 目录里的 mod
     * @param packMods     整合包里带的 mod
     * @param ignoredIds   不参与处理的 modId（例如本 mod 自身、或管理员指定的例外）
     */
    public static Plan plan(Collection<ModFile> standardMods,
                            Collection<ModFile> packMods,
                            Set<String> ignoredIds) {
        Set<String> ignored = ignoredIds == null ? Set.of() : ignoredIds;

        List<ModFile> standard = sanitize(standardMods, ignored);
        List<ModFile> pack = sanitize(packMods, ignored);

        // 按 modId 归组：只有"两边都有同 id"才算重复。
        Map<String, ModFile> packById = new LinkedHashMap<>();
        for (ModFile m : pack) {
            packById.putIfAbsent(m.modId(), m);
        }

        Map<String, ModFile> standardById = new LinkedHashMap<>();
        for (ModFile m : standard) {
            standardById.putIfAbsent(m.modId(), m);
        }

        // ── 依赖闭包：从"所有标准 mod"出发，算出不能删的集合 ──────────────
        // 规则：如果一个 mod 被保留集合里的任何成员依赖（或被 provides 顶替），
        // 它也必须保留 —— 递归展开。
        Set<String> keepIds = computeDependencyClosure(standard);

        List<ModFile> delete = new ArrayList<>();
        List<ModFile> replace = new ArrayList<>();
        List<ModFile> keep = new ArrayList<>();

        for (ModFile std : standard) {
            ModFile packed = packById.get(std.modId());
            if (packed == null) {
                keep.add(std); // 整合包里没有同 id，不动玩家的东西
                continue;
            }
            if (keepIds.contains(std.modId())) {
                // 有人依赖它 —— 不能删，但要用整合包版本保证与服务器一致。
                replace.add(std);
            } else {
                delete.add(std);
            }
        }

        // ── 嵌套 mod：整合包里"藏在 jar-in-jar 里"的依赖 ──────────────────
        // 只有它与标准目录里的东西冲突、且标准目录里没有它时，才需要提升。
        List<ModFile> promote = new ArrayList<>();
        for (ModFile nested : pack) {
            if (!nested.info().source().contains("jarjar")) {
                continue;
            }
            if (standardById.containsKey(nested.modId())) {
                continue; // 标准目录已有，不必提升
            }
            if (packById.containsKey(nested.modId()) && !nested.info().source().contains("jarjar")) {
                continue; // 整合包顶层已有非嵌套版本
            }
            promote.add(nested);
        }

        return new Plan(List.copyOf(delete), List.copyOf(replace),
                List.copyOf(promote), List.copyOf(keep));
    }

    /**
     * 依赖闭包：算出"不能删"的 mod id 集合。
     *
     * <p>从每个 mod 的依赖出发做广度优先展开；被依赖者自身若还有依赖也一并纳入。
     * 这样"A 依赖 B、B 依赖 C"时，删 B 会连带保住 C，不会留下断链。
     */
    public static Set<String> computeDependencyClosure(Collection<ModFile> mods) {
        // id（含 provides 别名）→ 提供它的 mod
        Map<String, ModFile> providerById = new HashMap<>();
        for (ModFile m : mods) {
            providerById.putIfAbsent(m.modId(), m);
            for (String p : m.info().provides()) {
                providerById.putIfAbsent(p, m);
            }
        }

        Set<String> keep = new LinkedHashSet<>();
        // 收集全部被引用的 id 作为起点
        Set<String> queue = new LinkedHashSet<>();
        for (ModFile m : mods) {
            queue.addAll(m.info().dependencies());
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Set<String> next = new LinkedHashSet<>();
            for (String depId : queue) {
                if (!visited.add(depId)) {
                    continue;
                }
                ModFile provider = providerById.get(depId);
                if (provider == null) {
                    continue; // 依赖不在本地 mod 集合里（可能是别的来源）
                }
                keep.add(provider.modId());
                next.addAll(provider.info().dependencies()); // 递归展开它的依赖
            }
            queue = next;
        }
        return keep;
    }

    private static List<ModFile> sanitize(Collection<ModFile> in, Set<String> ignored) {
        List<ModFile> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (ModFile m : in) {
            if (m == null || m.info() == null || m.path() == null) {
                continue;
            }
            if (ignored.contains(m.modId())) {
                continue;
            }
            out.add(m);
        }
        return out;
    }
}
