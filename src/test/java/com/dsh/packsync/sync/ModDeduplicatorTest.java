package com.dsh.packsync.sync;

import com.dsh.packsync.core.manifest.ModInspector;
import com.dsh.packsync.core.sync.ModDeduplicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复 mod 剔除验证。
 *
 * <p>这部分的正确性比"能不能下载"更要紧：
 * 少下一次只是慢，**删错一个 mod 会让玩家的整合包直接起不来**，
 * 而且崩在启动阶段、报错信息往往指向别的 mod，很难定位。
 * 所以每条规则都单独断言，尤其"被依赖的不能删"这条。
 */
class ModDeduplicatorTest {

    private static ModDeduplicator.ModFile mod(String id, String version, String... deps) {
        return new ModDeduplicator.ModFile(
                Path.of("/mods/" + id + "-" + version + ".jar"),
                new ModInspector.ModInfo(id, version, ModInspector.Side.UNIVERSAL, "mods.toml",
                        Set.of(deps), Set.of()));
    }

    private static ModDeduplicator.ModFile modWithProvides(String id, String version,
                                                            Set<String> provides, String... deps) {
        return new ModDeduplicator.ModFile(
                Path.of("/mods/" + id + "-" + version + ".jar"),
                new ModInspector.ModInfo(id, version, ModInspector.Side.UNIVERSAL, "mods.toml",
                        Set.of(deps), provides));
    }

    private static ModDeduplicator.ModFile nested(String id, String version, String... deps) {
        return new ModDeduplicator.ModFile(
                Path.of("/mods/pack.jar!/META-INF/jarjar/" + id + ".jar"),
                new ModInspector.ModInfo(id, version, ModInspector.Side.UNIVERSAL, "jarjar",
                        Set.of(deps), Set.of()));
    }

    // ── 基本剔除 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 两边都有同 id 且无人依赖 → 删掉标准目录里那份（否则 modId 重复会崩）")
    void duplicateWithoutDependentsIsDeleted() {
        var standard = List.of(mod("sodium", "0.5.8"));
        var pack = List.of(mod("sodium", "0.5.11"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        assertEquals(1, plan.deleteFromStandard().size());
        assertEquals("sodium", plan.deleteFromStandard().get(0).modId());
        assertTrue(plan.replaceInStandard().isEmpty());
        assertTrue(plan.requiresRestart());
    }

    @Test
    @DisplayName("★ 重复但**被别的 mod 依赖** → 不能删，改用整合包版本覆盖（保证版本一致）")
    void duplicateWithDependentsIsReplacedNotDeleted() {
        // sodium-extra 依赖 sodium；两边都有 sodium
        var standard = List.of(mod("sodium", "0.5.8"), mod("sodium-extra", "0.5.8", "sodium"));
        var pack = List.of(mod("sodium", "0.5.11"), mod("sodium-extra", "0.5.11", "sodium"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        // sodium 被 sodium-extra 依赖 → 必须保留（改用整合包版本覆盖）
        assertFalse(plan.deleteFromStandard().stream().anyMatch(m -> m.modId().equals("sodium")),
                "被依赖的 sodium 绝不能删 —— 删了 sodium-extra 就断了");
        assertEquals(1, plan.replaceInStandard().size());
        assertEquals("sodium", plan.replaceInStandard().get(0).modId());
        // sodium-extra 本身无人依赖 → 它的标准副本该删（整合包里会提供新版本）
        assertEquals(List.of("sodium-extra"),
                plan.deleteFromStandard().stream().map(ModDeduplicator.ModFile::modId).toList());
    }

    @Test
    @DisplayName("整合包里没有的 mod 一律不动（玩家自己的东西我们无权处理）")
    void modsNotInPackAreLeftAlone() {
        var standard = List.of(mod("my-private-mod", "1.0"));
        var pack = List.of(mod("sodium", "0.5.11"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        assertTrue(plan.deleteFromStandard().isEmpty());
        assertTrue(plan.replaceInStandard().isEmpty());
        assertEquals(1, plan.keepStandard().size());
        assertEquals("my-private-mod", plan.keepStandard().get(0).modId());
        // isNoop 只表示"没有剔除动作"；整合包里的 sodium 属于新增下载，
        // 由 SyncEngine 负责，不在本计划的语义范围内。
        assertTrue(plan.isNoop(), "没有重复可剔除时计划应为 no-op");
    }

    // ── 依赖闭包 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 依赖闭包会递归展开：A→B→C 时 B 和 C 都必须保住")
    void dependencyClosureIsRecursive() {
        var standard = List.of(
                mod("A", "1.0", "B"),
                mod("B", "1.0", "C"),
                mod("C", "1.0"));

        Set<String> closure = ModDeduplicator.computeDependencyClosure(standard);

        assertTrue(closure.contains("B"), "A 依赖 B，B 必须保留");
        assertTrue(closure.contains("C"), "B 依赖 C，C 也必须保留（递归展开）");
        assertFalse(closure.contains("A"), "A 没被任何人依赖，可以处理");
    }

    @Test
    @DisplayName("★ 三层重复中，只有被依赖的那一层被替换，其余删除")
    void mixedChainIsHandledLayerByLayer() {
        var standard = List.of(
                mod("A", "1.0", "B"),
                mod("B", "1.0", "C"),
                mod("C", "1.0"),
                mod("D", "1.0"));
        var pack = List.of(
                mod("A", "2.0", "B"),
                mod("B", "2.0", "C"),
                mod("C", "2.0"),
                mod("D", "2.0"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        Set<String> replaced = new java.util.HashSet<>();
        plan.replaceInStandard().forEach(m -> replaced.add(m.modId()));
        Set<String> deleted = new java.util.HashSet<>();
        plan.deleteFromStandard().forEach(m -> deleted.add(m.modId()));

        assertEquals(Set.of("B", "C"), replaced, "B、C 被依赖，应被替换");
        assertEquals(Set.of("A", "D"), deleted, "A、D 无人依赖，应被删除");
    }

    @Test
    @DisplayName("provides 别名也算依赖关系（有些 mod 用别名顶替另一个 id）")
    void providesIsConsideredADependency() {
        // extra 依赖别名 "shiny"，而 shiny 由 realmod 通过 provides 提供
        var standard = List.of(
                modWithProvides("realmod", "1.0", Set.of("shiny")),
                mod("extra", "1.0", "shiny"));
        var pack = List.of(
                modWithProvides("realmod", "2.0", Set.of("shiny")),
                mod("extra", "2.0", "shiny"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        assertFalse(plan.deleteFromStandard().stream().anyMatch(m -> m.modId().equals("realmod")),
                "realmod 通过 provides 顶替 shiny，被 extra 依赖，不能删");
        assertEquals(1, plan.replaceInStandard().size());
        assertEquals("realmod", plan.replaceInStandard().get(0).modId());
    }

    @Test
    @DisplayName("被忽略的 modId 完全不参与处理（例如本 mod 自身）")
    void ignoredIdsAreSkipped() {
        var standard = List.of(mod("packsync", "1.0"), mod("sodium", "0.5.8"));
        var pack = List.of(mod("packsync", "9.9"), mod("sodium", "0.5.11"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of("packsync"));

        assertFalse(plan.deleteFromStandard().stream().anyMatch(m -> m.modId().equals("packsync")));
        assertFalse(plan.replaceInStandard().stream().anyMatch(m -> m.modId().equals("packsync")));
        assertEquals(1, plan.deleteFromStandard().size());
        assertEquals("sodium", plan.deleteFromStandard().get(0).modId());
    }

    // ── 嵌套 mod ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("嵌套（jar-in-jar）mod 在标准目录没有同 id 时会被提升")
    void nestedModsArePromoted() {
        var standard = List.of(mod("sodium", "0.5.8"));
        var pack = List.of(mod("sodium", "0.5.11"), nested("embedded-lib", "1.2"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        assertEquals(1, plan.promoteNested().size());
        assertEquals("embedded-lib", plan.promoteNested().get(0).modId());
    }

    @Test
    @DisplayName("标准目录已有同 id 时，嵌套的那份不提升（避免又造成重复）")
    void nestedIsNotPromotedIfAlreadyPresent() {
        var standard = List.of(mod("embedded-lib", "1.0"));
        var pack = List.of(nested("embedded-lib", "1.2"));

        ModDeduplicator.Plan plan = ModDeduplicator.plan(standard, pack, Set.of());

        assertTrue(plan.promoteNested().isEmpty(),
                "标准目录里已经有一份，再提升一份就是重复加载");
    }

    // ── 边界 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("空输入不崩，且计划为 no-op")
    void emptyInputsAreSafe() {
        ModDeduplicator.Plan plan = ModDeduplicator.plan(List.of(), List.of(), Set.of());
        assertTrue(plan.isNoop());
        assertFalse(plan.requiresRestart());
        assertEquals("重复剔除：删除 0，覆盖 0，提升嵌套 0，保留 0", plan.describe());
    }

    @Test
    @DisplayName("null 输入同样安全（上游可能给出 null 集合）")
    void nullInputsAreSafe() {
        ModDeduplicator.Plan plan = ModDeduplicator.plan(null, null, null);
        assertTrue(plan.isNoop());
    }

    @Test
    @DisplayName("自引用依赖不会造成无限递归")
    void selfDependencyTerminates() {
        var standard = List.of(mod("selfish", "1.0", "selfish"));
        Set<String> closure = ModDeduplicator.computeDependencyClosure(standard);
        assertTrue(closure.contains("selfish"));
    }

    @Test
    @DisplayName("循环依赖（A↔B）同样能终止并保住双方")
    void circularDependencyTerminates() {
        var standard = List.of(mod("A", "1.0", "B"), mod("B", "1.0", "A"));
        Set<String> closure = ModDeduplicator.computeDependencyClosure(standard);
        assertEquals(Set.of("A", "B"), closure);
    }
}
