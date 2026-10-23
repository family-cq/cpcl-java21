package com.meloong.pcl.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本地 Mod 扫描与启停，移植自 <c>PageInstanceMod</c> / <c>MyLocalModItem</c> 的文件层逻辑。
 *
 * <p>启用/禁用通过重命名后缀 <c>.disabled</c> 实现（PCL 的做法），不移动文件。
 */
@Service
public class ModScanner {

    private static final Logger log = LoggerFactory.getLogger(ModScanner.class);
    private static final Pattern TOML_FIELD = Pattern.compile("^\\s*(modId|displayName|version)\\s*=\\s*\"?([^\"]*)\"?\\s*$");

    private final ObjectMapper mapper;

    public ModScanner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Mod 信息。 */
    public record ModInfo(String fileName, String path, boolean enabled, long size,
                          String id, String name, String version, String loader) {
    }

    public List<ModInfo> scan(Path modsDir) {
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<ModInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String fileName = path.getFileName().toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".litemod")
                        || lower.endsWith(".disabled"))) {
                    return;
                }
                boolean enabled = !lower.endsWith(".disabled");
                Metadata metadata = readMetadata(path);
                long size;
                try {
                    size = Files.size(path);
                } catch (IOException ex) {
                    size = 0;
                }
                result.add(new ModInfo(fileName, path.toString(), enabled, size,
                        metadata.id(), metadata.name(), metadata.version(), metadata.loader()));
            });
        } catch (IOException ex) {
            log.warn("扫描 mods 目录失败：{}", ex.getMessage());
            return List.of();
        }
        result.sort((a, b) -> a.fileName().compareToIgnoreCase(b.fileName()));
        return result;
    }

    /** 切换 Mod 启用状态，返回切换后的状态。 */
    public boolean toggle(Path modFile) throws IOException {
        String name = modFile.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".disabled")) {
            Path target = modFile.resolveSibling(name.substring(0, name.length() - ".disabled".length()));
            Files.move(modFile, target);
            return true;
        }
        Path target = modFile.resolveSibling(name + ".disabled");
        Files.move(modFile, target);
        return false;
    }

    private record Metadata(String id, String name, String version, String loader) {
        static final Metadata EMPTY = new Metadata(null, null, null, null);
    }

    /** 依次尝试 fabric / quilt / forge / liteloader 的元数据文件。 */
    private Metadata readMetadata(Path file) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".litemod"))) {
            return Metadata.EMPTY;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Metadata fabric = readJsonMeta(zip, "fabric.mod.json", "Fabric");
            if (fabric != null) {
                return fabric;
            }
            Metadata quilt = readJsonMeta(zip, "quilt.mod.json", "Quilt");
            if (quilt != null) {
                return quilt;
            }
            Metadata forge = readTomlMeta(zip);
            if (forge != null) {
                return forge;
            }
            Metadata lite = readLitemodMeta(zip);
            if (lite != null) {
                return lite;
            }
        } catch (Exception ex) {
            log.debug("读取 Mod 元数据失败（{}）：{}", file, ex.getMessage());
        }
        return Metadata.EMPTY;
    }

    private Metadata readJsonMeta(ZipFile zip, String entryName, String loader) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String id = root.path("id").asText(null);
            String version = root.path("version").asText(null);
            String name = root.path("name").asText(null);
            if (name == null || name.isBlank()) {
                name = root.path("quilt_loader").path("metadata").path("name").asText(null);
            }
            return new Metadata(id, name, version, loader);
        } catch (IOException ex) {
            return null;
        }
    }

    private Metadata readTomlMeta(ZipFile zip) {
        for (String candidate : List.of("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
            ZipEntry entry = zip.getEntry(candidate);
            if (entry == null) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String id = null;
                String name = null;
                String version = null;
                for (String line : text.split("\\R")) {
                    Matcher matcher = TOML_FIELD.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }
                    switch (matcher.group(1)) {
                        case "modId" -> id = id == null ? matcher.group(2) : id;
                        case "displayName" -> name = name == null ? matcher.group(2) : name;
                        case "version" -> version = version == null ? matcher.group(2) : version;
                        default -> {
                        }
                    }
                }
                return new Metadata(id, name, version, candidate.contains("neoforge") ? "NeoForge" : "Forge");
            } catch (IOException ex) {
                return null;
            }
        }
        return null;
    }

    private Metadata readLitemodMeta(ZipFile zip) {
        ZipEntry entry = zip.getEntry("litemod.json");
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new Metadata(root.path("name").asText(null), root.path("name").asText(null),
                    root.path("version").asText(null), "LiteLoader");
        } catch (IOException ex) {
            return null;
        }
    }

    /** 解析 mods 目录中的指定文件，找不到返回空。 */
    public Optional<Path> find(Path modsDir, String fileName) {
        Path path = modsDir.resolve(fileName).normalize();
        if (!path.startsWith(modsDir.normalize())) {
            return Optional.empty();
        }
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** 便捷方法：返回文件名 → 启用状态。 */
    public Map<String, Boolean> statusMap(Path modsDir) {
        return scan(modsDir).stream()
                .collect(java.util.stream.Collectors.toMap(ModInfo::fileName, ModInfo::enabled, (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }
}
