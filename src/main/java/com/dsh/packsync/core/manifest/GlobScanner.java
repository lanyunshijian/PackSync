package com.dsh.packsync.core.manifest;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * glob 规则引擎：把配置里的一串路径规则，变成"清单相对路径 → 真实文件"的映射。
 *
 * <p>语法（语义保持通用，便于管理员迁移配置）：
 * <ul>
 *   <li>{@code *} 单层任意字符（不跨 {@code /}）；{@code ?} 单字符；
 *       {@code **} 跨层；{@code [abc]} / {@code {a,b}} 由 JDK glob 处理。</li>
 *   <li>{@code !} 前缀表示**排除**，优先于包含规则。</li>
 *   <li>前导 {@code /} 会被去掉；{@code **}{@code /**} 折叠成 {@code **}。</li>
 * </ul>
 *
 * <p><b>三条容易踩的语义，这里都按通用行为实现：</b>
 * <ol>
 *   <li><b>目录本身从不参与匹配</b> —— 只收集文件。所以想同步整个目录必须写
 *       {@code /config/**}，写 {@code /config} 只会匹配"名字正好叫 config 的文件"。</li>
 *   <li><b>只有排除项、没有包含项时，结果是空</b> —— 纯 {@code !} 规则不构成"要同步什么"。</li>
 *   <li>{@code **} 允许零级目录：{@code /mods/**}{@code /*.jar} 既匹配
 *       {@code mods/a.jar} 也匹配 {@code mods/x/y.jar}。实现方式是给含 {@code /**}{@code /}
 *       的规则额外编译一份折叠变体。</li>
 * </ol>
 *
 * <p>符号链接**不跟随**（比"跟随但防逃逸"更保守）：
 * 整合包目录里出现指向外部的符号链接更可能是异常配置，静默跳过比冒风险复制出去更合适。
 */
public final class GlobScanner {

    private final List<PathMatcher> includes = new ArrayList<>();
    private final List<PathMatcher> excludes = new ArrayList<>();
    private final List<String> includeSpecs = new ArrayList<>();
    private final Set<Path> startDirs;

    /**
     * @param rules     规则列表（可含 {@code !} 排除项），null/空白项被忽略
     * @param startDirs 扫描起点；每个起点的文件都以"相对该起点"的形式输出
     */
    public GlobScanner(List<String> rules, Set<Path> startDirs) {
        this.startDirs = startDirs == null ? Set.of() : Set.copyOf(startDirs);
        compile(rules);
    }

    private void compile(List<String> rules) {
        if (rules == null) {
            return;
        }
        for (String raw : rules) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String rule = raw.trim();
            boolean negated = rule.startsWith("!");
            String body = negated ? rule.substring(1) : rule;
            if (body.startsWith("/")) {
                body = body.substring(1);
            }
            if (body.isEmpty()) {
                continue;
            }
            while (body.contains("**/**")) {
                body = body.replace("**/**", "**");
            }
            try {
                PathMatcher matcher = Path.of(".").getFileSystem().getPathMatcher("glob:" + body);
                if (negated) {
                    excludes.add(matcher);
                } else {
                    includes.add(matcher);
                    includeSpecs.add(body);
                    // `/**/` 允许零级目录：额外编译一份把 "/**/" 折成 "/" 的变体。
                    if (body.contains("/**/")) {
                        String collapsed = body.replace("/**/", "/");
                        includes.add(Path.of(".").getFileSystem().getPathMatcher("glob:" + collapsed));
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("[PackSync] 非法 glob 规则已忽略：" + raw + " -> " + e.getMessage());
            }
        }
    }

    /** 是否有任何包含规则。没有的话扫描必然为空（纯排除不成事）。 */
    public boolean hasIncludes() {
        return !includes.isEmpty();
    }

    public int includeCount() {
        return includeSpecs.size();
    }

    /**
     * 遍历所有起点，返回 {@code /相对路径 -> 真实文件}。
     *
     * <p>输出键带前导 {@code /}，与服务端清单、客户端还原三处共用同一约定。
     */
    public Map<String, Path> scan() {
        Map<String, Path> out = new LinkedHashMap<>();
        if (includes.isEmpty()) {
            // 明确按"空结果"处理，而不是退化成"匹配所有"——后者会让 ! 规则变成危险的全量同步。
            return out;
        }
        for (Path start : startDirs) {
            if (start == null || !Files.isDirectory(start)) {
                continue;
            }
            Path absStart = start.toAbsolutePath().normalize();
            try {
                Files.walkFileTree(start, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        // 不跟随符号链接：链接目标在起点之外就跳过。
                        try {
                            if (Files.isSymbolicLink(file)) {
                                Path target = file.toRealPath();
                                if (!target.startsWith(absStart)) {
                                    return FileVisitResult.CONTINUE;
                                }
                            }
                        } catch (IOException ignored) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel;
                        try {
                            rel = start.relativize(file);
                        } catch (IllegalArgumentException e) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (accepts(rel)) {
                            out.put(toKey(rel), file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 单个文件读不了不应中断整次扫描。
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                System.err.println("[PackSync] 扫描目录失败：" + start + " -> " + e);
            }
        }
        return out;
    }

    /**
     * 对**已格式化的清单路径**（如 {@code /config/foo.toml}）判断是否命中。
     * 服务端用它来标注 editable / forceCopy。
     */
    public boolean matchesFormatted(String formattedPath) {
        if (formattedPath == null || formattedPath.isBlank()) {
            return false;
        }
        String p = formattedPath.replace('\\', '/');
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return false;
        }
        return accepts(Path.of(p));
    }

    /** 包含命中且排除未命中。 */
    private boolean accepts(Path relative) {
        boolean hit = false;
        for (PathMatcher m : includes) {
            if (m.matches(relative)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return false;
        }
        for (PathMatcher m : excludes) {
            if (m.matches(relative)) {
                return false;
            }
        }
        return true;
    }

    /** 相对路径 → 带前导斜杠、以 / 分隔的键。 */
    public static String toKey(Path relative) {
        String s = relative.toString().replace('\\', '/');
        return s.startsWith("/") ? s : "/" + s;
    }

    /** 把 {@code /mods/foo.jar} 还原成相对 Path（不含前导斜杠）。 */
    public static Path toRelative(String formattedPath) {
        String p = formattedPath == null ? "" : formattedPath.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() ? Path.of("") : Path.of(p);
    }

    /**
     * 判定文件分类。用于决定同步策略（例如只有 {@code mod} 会做重复剔除与嵌套处理）。
     * 规则与常见做法一致。
     */
    public static String classify(String formattedPath, boolean isModJar) {
        if (isModJar) {
            return "mod";
        }
        String lower = formattedPath.toLowerCase(Locale.ROOT);
        if (lower.contains("/config/")) {
            return "config";
        }
        if (lower.contains("/shaderpacks/")) {
            return "shader";
        }
        if (lower.contains("/resourcepacks/")) {
            return "resourcepack";
        }
        if (lower.endsWith("/options.txt")) {
            return "mc_options";
        }
        return "other";
    }

    /** 便于测试与日志：当前生效的包含规则。 */
    public List<String> includeRules() {
        return List.copyOf(new LinkedHashSet<>(includeSpecs));
    }
}
