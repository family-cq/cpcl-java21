package com.meloong.pcl.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.McVersion;
import com.meloong.pcl.version.ModLoader;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 实例（版本）扫描，移植自 <c>ModMinecraft.InitMcInstanceList</c>。
 *
 * <p>原实现带缓存与「按卡片类型分组」，此处简化为扫描 + 排序（按原版版本号从新到旧）。
 */
@Service
public class InstanceScanner {

    private static final Logger log = LoggerFactory.getLogger(InstanceScanner.class);

    private final PclProperties properties;
    private final VersionResolver versionResolver;
    private final ObjectMapper mapper;

    public InstanceScanner(PclProperties properties, VersionResolver versionResolver, ObjectMapper mapper) {
        this.properties = properties;
        this.versionResolver = versionResolver;
        this.mapper = mapper;
    }

    /** 实例摘要。 */
    public record InstanceInfo(String name, String mcVersion, int drop, boolean snapshot, boolean fool,
                               boolean reliable, Map<String, String> loaders, String optiFine,
                               String type, String releaseTime, boolean jarExists, boolean modable,
                               int modCount, int requiredJava, String gameDir) {
    }

    public List<InstanceInfo> scan() {
        Path versionsDir = properties.versionsPath();
        if (!Files.isDirectory(versionsDir)) {
            log.warn("versions 目录不存在：{}", versionsDir);
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        } catch (IOException ex) {
            log.error("扫描 versions 目录失败：{}", ex.getMessage());
            return List.of();
        }

        List<InstanceInfo> result = new ArrayList<>();
        for (String name : names) {
            try {
                result.add(info(name));
            } catch (Exception ex) {
                log.warn("跳过无效版本 {}：{}", name, ex.getMessage());
            }
        }
        result.sort(Comparator.comparingInt(InstanceInfo::drop).reversed()
                .thenComparing(InstanceInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * 轻量扫描：只看 versions 下的目录和各自的版本 JSON，不做支持库合并、继承链展开等完整检测。
     *
     * <p>版本列表刷新走这里，保证秒回；选中某个版本后再调 {@link #info(String)} 做完整检测。
     */
    public List<InstanceInfo> scanQuick() {
        Path versionsDir = properties.versionsPath();
        if (!Files.isDirectory(versionsDir)) {
            log.warn("versions 目录不存在：{}", versionsDir);
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        } catch (IOException ex) {
            log.error("扫描 versions 目录失败：{}", ex.getMessage());
            return List.of();
        }
        List<InstanceInfo> result = new ArrayList<>();
        for (String name : names) {
            try {
                result.add(quickInfo(name));
            } catch (Exception ex) {
                log.warn("跳过无效版本 {}：{}", name, ex.getMessage());
            }
        }
        result.sort(Comparator.comparingInt(InstanceInfo::drop).reversed()
                .thenComparing(InstanceInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** 单个实例的轻量信息：只读自己的版本 JSON（不展开继承链、不解析支持库）。 */
    private InstanceInfo quickInfo(String name) throws IOException {
        Path dir = properties.versionsPath().resolve(name);
        Path jsonPath = dir.resolve(name + ".json");
        McVersion mc = null;
        JsonNode node = null;
        if (Files.isRegularFile(jsonPath)) {
            node = mapper.readTree(jsonPath.toFile());
            mc = McVersion.detect(name, node);
        }
        String vanillaName = name;
        int drop = 0;
        boolean snapshot = false;
        boolean fool = false;
        boolean modable = true;
        String optiFine = null;
        String type = "";
        String releaseTime = "";
        Map<String, String> loaders = new LinkedHashMap<>();
        if (mc != null) {
            vanillaName = mc.vanillaName();
            drop = mc.drop();
            snapshot = mc.snapshot();
            fool = mc.fool();
            modable = mc.modable();
            optiFine = mc.optiFine();
            mc.modLoaderVersions().forEach((loader, ver) -> loaders.put(loader.display(), ver));
        }
        if (node != null) {
            type = node.path("type").asText("");
            releaseTime = node.path("releaseTime").asText("");
        }
        int requiredJava = node == null ? 0 : node.path("javaVersion").path("majorVersion").asInt(0);
        boolean jarExists = Files.isRegularFile(dir.resolve(name + ".jar"));
        return new InstanceInfo(name, vanillaName, drop, snapshot, fool, true, loaders,
                optiFine, type, releaseTime, jarExists, modable,
                countMods(dir.resolve("mods")), requiredJava, dir.toString());
    }

    /** 单个实例信息。 */
    public InstanceInfo info(String name) throws IOException {
        ResolvedVersion version = versionResolver.resolve(name);
        Path gameDir = properties.versionsPath().resolve(name);
        Map<String, String> loaders = new LinkedHashMap<>();
        version.mcVersion().modLoaderVersions().forEach((loader, ver) -> loaders.put(loader.display(), ver));
        int modCount = countMods(gameDir.resolve("mods"));
        boolean jarExists = Files.isRegularFile(
                properties.versionsPath().resolve(version.jarId()).resolve(version.jarId() + ".jar"));
        return new InstanceInfo(name,
                version.mcVersion().vanillaName(),
                version.mcVersion().drop(),
                version.mcVersion().snapshot(),
                version.mcVersion().fool(),
                version.mcVersion().reliable(),
                loaders,
                version.mcVersion().optiFine(),
                version.type(),
                version.releaseTime(),
                jarExists,
                version.mcVersion().modable(),
                modCount,
                version.requiredJavaMajor(),
                gameDir.toString());
    }

    /** 统计可加载的 Mod 数量（对应 PCL 的 jar / zip / litemod）。 */
    public int countMods(Path modsDir) {
        if (!Files.isDirectory(modsDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".litemod")
                            || name.endsWith(".jar.disabled") || name.endsWith(".zip.disabled"))
                    .count();
        } catch (IOException ex) {
            return 0;
        }
    }

    /** 判断版本是否已安装（JSON 存在）。 */
    public boolean installed(String name) {
        return Files.isRegularFile(versionResolver.versionJsonPath(name));
    }

    /**
     * 删除版本：整个版本目录（版本 JSON / jar、存档、配置、Mod 等都在里面）。
     *
     * <p>被其他版本 {@code inheritsFrom} 依赖的原版不允许直接删除，否则那些
     * 加载器版本会一起失效，需要先把依赖它的版本删掉。
     */
    public Map<String, Object> delete(String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("非法的版本名：" + name);
        }
        Path dir = properties.versionsPath().resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("版本不存在：" + name);
        }
        List<String> children = findChildren(name);
        if (!children.isEmpty()) {
            throw new IllegalStateException("版本 " + name + " 被 " + String.join("、", children)
                    + " 依赖，请先删除依赖它的版本");
        }
        long size = directorySize(dir);
        deleteRecursively(dir);
        log.info("已删除版本 {}，释放 {} MB", name, size / 1024 / 1024);
        return Map.of("deleted", name, "freedBytes", size);
    }

    /** 找出 inheritsFrom 指向该版本的其他版本目录。 */
    private List<String> findChildren(String name) {
        Path versionsDir = properties.versionsPath();
        List<String> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                String child = path.getFileName().toString();
                if (child.equals(name)) {
                    return;
                }
                Path json = path.resolve(child + ".json");
                if (!Files.isRegularFile(json)) {
                    return;
                }
                try {
                    String parent = mapper.readTree(json.toFile()).path("inheritsFrom").asText("");
                    if (name.equals(parent)) {
                        children.add(child);
                    }
                } catch (IOException ex) {
                    log.debug("跳过无法解析的版本 {}：{}", child, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("扫描依赖版本失败：{}", ex.getMessage());
        }
        return children;
    }

    private long directorySize(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    return 0L;
                }
            }).sum();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    /** 已安装的加载器列表，用于界面展示。 */
    public List<String> installedLoaders(String name) {
        try {
            ResolvedVersion version = versionResolver.resolve(name);
            return version.mcVersion().modLoaderVersions().entrySet().stream()
                    .map(entry -> entry.getKey().display() + " " + entry.getValue())
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 加载器枚举 → 展示名。 */
    public static String display(ModLoader loader) {
        return loader.display();
    }
}
