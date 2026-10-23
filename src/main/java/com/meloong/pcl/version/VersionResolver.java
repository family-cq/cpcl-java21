package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本 JSON 加载与 inheritsFrom 递归合并。
 *
 * <p>原工程在 <c>McInstance.Load()</c> 与 <c>McLaunchArguments*</c> 中通过循环读取 InheritName 逐层取参数，
 * 此处改为一次性合并成 {@link ResolvedVersion}，语义保持一致：子版本优先，参数按子 → 父顺序拼接。
 */
@Service
public class VersionResolver {

    private static final Logger log = LoggerFactory.getLogger(VersionResolver.class);
    private static final int MAX_INHERIT_DEPTH = 10;

    private final ObjectMapper mapper;
    private final PclProperties properties;

    public VersionResolver(ObjectMapper mapper, PclProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public Path versionDir(String id) {
        return properties.versionsPath().resolve(id);
    }

    public Path versionJsonPath(String id) {
        return versionDir(id).resolve(id + ".json");
    }

    /** 读取单个版本 JSON。 */
    public JsonNode readRaw(String id) throws IOException {
        Path path = versionJsonPath(id);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("版本 " + id + " 的 JSON 不存在：" + path);
        }
        return mapper.readTree(Files.readString(path));
    }

    public VersionJson read(String id) throws IOException {
        Path path = versionJsonPath(id);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("版本 " + id + " 的 JSON 不存在：" + path);
        }
        return mapper.treeToValue(mapper.readTree(Files.readString(path)), VersionJson.class);
    }

    /** 解析版本并展开继承链。 */
    public ResolvedVersion resolve(String id) throws IOException {
        List<VersionJson> chain = new ArrayList<>();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        String current = id;
        while (current != null && !current.isBlank()) {
            if (!visited.add(current)) {
                log.warn("版本 {} 的 inheritsFrom 出现循环（{}），已截断", id, current);
                break;
            }
            VersionJson json;
            try {
                json = read(current);
            } catch (IllegalArgumentException ex) {
                if (chain.isEmpty()) {
                    throw ex;
                }
                log.warn("继承链中的父版本 {} 缺失，已忽略：{}", current, ex.getMessage());
                break;
            }
            chain.add(json);
            current = json.inheritsFrom();
            if (chain.size() > MAX_INHERIT_DEPTH) {
                log.warn("版本 {} 的继承链超过 {} 层，已截断", id, MAX_INHERIT_DEPTH);
                break;
            }
        }

        // 支持库：子版本在前，按 group:artifact[:classifier] 去重（子版本覆盖父版本）。
        // 必须忽略版本号，否则同一个库的新旧两个版本都会进入类路径：
        // 例如原版 1.21.2 带 asm 9.3、NeoForge 用 asm 9.7，两个 jar 同时出现在 -cp 里，
        // BootstrapLauncher 会报 “Module named org.objectweb.asm was already on the JVMs module path”。
        Map<String, VersionJson.Library> libraries = new LinkedHashMap<>();
        List<JsonNode> jvmArgs = new ArrayList<>();
        List<JsonNode> gameArgs = new ArrayList<>();

        String mainClass = null;
        String type = null;
        String assetsIndexName = null;
        String assetIndexUrl = null;
        String releaseTime = null;
        String minecraftArguments = null;
        String jarId = null;
        VersionJson.DownloadInfo clientDownload = null;
        VersionJson.JavaVersion javaVersion = null;

        for (VersionJson json : chain) {
            for (VersionJson.Library library : json.libraries()) {
                if (library == null) {
                    continue;
                }
                String key = libraryDedupeKey(library, libraries.size());
                libraries.putIfAbsent(key, library);
            }
            if (json.arguments() != null) {
                jvmArgs.addAll(json.arguments().jvm());
                gameArgs.addAll(json.arguments().game());
            }
            mainClass = firstNonNull(mainClass, json.mainClass());
            type = firstNonNull(type, json.type());
            releaseTime = firstNonNull(releaseTime, json.releaseTime());
            minecraftArguments = firstNonNull(minecraftArguments, emptyToNull(json.minecraftArguments()));
            clientDownload = clientDownload != null ? clientDownload : json.client();
            javaVersion = javaVersion != null ? javaVersion : json.javaVersion();
            if (assetsIndexName == null) {
                if (json.assetIndex() != null && json.assetIndex().id() != null) {
                    assetsIndexName = json.assetIndex().id();
                    assetIndexUrl = json.assetIndex().url();
                } else if (json.assets() != null) {
                    assetsIndexName = json.assets();
                }
            }
            if (jarId == null) {
                jarId = json.jar();
            }
        }

        // jar 未指定时使用最底层（原版）版本名；父版本缺失时退回 inheritsFrom
        if (jarId == null || jarId.isBlank()) {
            VersionJson last = chain.get(chain.size() - 1);
            jarId = last.inheritsFrom() != null && !last.inheritsFrom().isBlank()
                    ? last.inheritsFrom()
                    : last.id();
        }
        if (assetsIndexName == null || assetsIndexName.isBlank()) {
            assetsIndexName = "legacy";
        }
        if (mainClass == null) {
            mainClass = "net.minecraft.client.main.Main";
        }

        McVersion mcVersion = McVersion.detect(id, readRaw(id));

        return new ResolvedVersion(id, type, mainClass, assetsIndexName, assetIndexUrl, jarId,
                List.copyOf(libraries.values()), List.copyOf(jvmArgs), List.copyOf(gameArgs),
                minecraftArguments, clientDownload, javaVersion, releaseTime, mcVersion, readRaw(id));
    }

    /**
     * 库去重键：只取 group + artifact + classifier，忽略版本与扩展名。
     *
     * <p>同一坐标的不同版本只能保留一个（子版本在前，因此先到的胜出）。
     * 这与启动器的行为一致：类路径里同名模块重复会让 FML 的模块系统直接拒绝启动。
     */
    private static String libraryDedupeKey(VersionJson.Library library, int index) {
        String name = library.name();
        if (name == null || name.isBlank()) {
            return "unnamed-" + index;
        }
        try {
            MavenCoordinate coordinate = MavenCoordinate.parse(name);
            return coordinate.group() + ":" + coordinate.artifact()
                    + (coordinate.classifier() == null ? "" : ":" + coordinate.classifier());
        } catch (RuntimeException ex) {
            return name;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : emptyToNull(candidate);
    }
}
