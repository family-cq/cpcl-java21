package com.meloong.pcl.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.download.DownloadManager;
import com.meloong.pcl.download.InstallJobService;
import com.meloong.pcl.launch.LaunchPlan;
import com.meloong.pcl.launch.LaunchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 版本设置：改名、打开目录、导出启动脚本、导出整合包、Mod 可更新标记。
 *
 * <p>对应 PCL「版本设置」页的文件层操作；所有路径都限制在 versions 目录内。
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);
    private static final String USER_AGENT = "PCL2-Java/1.0 (instance-tools)";
    private static final String MODRINTH = "https://api.modrinth.com/v2";

    /** 导出整合包时跳过的目录 / 文件（运行产物、存档、日志不属于整合包内容）。 */
    private static final Set<String> EXPORT_SKIP_DIRS = Set.of(
            "logs", "crash-reports", "saves", "screenshots", "natives", "exports",
            ".pcltmp", "downloads", "backups", "modpack-backups", "usercache");

    private final PclProperties properties;
    private final ObjectMapper mapper;
    private final InstanceScanner scanner;
    private final ModScanner modScanner;
    private final LaunchService launchService;
    private final DownloadManager downloadManager;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public InstanceService(PclProperties properties, ObjectMapper mapper, InstanceScanner scanner,
                           ModScanner modScanner, LaunchService launchService, DownloadManager downloadManager) {
        this.properties = properties;
        this.mapper = mapper;
        this.scanner = scanner;
        this.modScanner = modScanner;
        this.launchService = launchService;
        this.downloadManager = downloadManager;
    }

    /** 版本目录（版本隔离时就是游戏目录）。 */
    public Path gameDir(String name) {
        return properties.versionsPath().resolve(name);
    }

    // ------------------------------------------------------------ 改名

    /**
     * 重命名版本：移动目录 + 重命名版本 JSON / jar，并同步 JSON 里的 {@code id}。
     *
     * <p>被其他版本 {@code inheritsFrom} 依赖的版本不允许改名，否则依赖它的加载器版本会失效。
     */
    public Map<String, Object> rename(String name, String newName) throws IOException {
        String target = newName == null ? "" : newName.trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("版本名不能为空");
        }
        if (target.equals(name)) {
            return Map.of("renamed", false, "from", name, "to", name);
        }
        if (target.contains("..") || target.contains("/") || target.contains("\\")
                || target.matches(".*[<>:\"|?*].*") || target.endsWith(".")) {
            throw new IllegalArgumentException("版本名含有非法字符：" + target);
        }
        Path src = gameDir(name);
        Path dst = gameDir(target);
        if (!Files.isDirectory(src)) {
            throw new IllegalArgumentException("版本不存在：" + name);
        }
        if (Files.exists(dst)) {
            throw new IllegalArgumentException("已存在同名版本：" + target);
        }
        Path srcJson = src.resolve(name + ".json");
        if (!Files.isRegularFile(srcJson)) {
            throw new IllegalArgumentException("版本 JSON 不存在，无法改名：" + srcJson);
        }
        // 有子版本依赖时禁止改名
        List<String> children = inheritingChildren(name);
        if (!children.isEmpty()) {
            throw new IllegalStateException("版本 " + name + " 被 " + String.join("、", children)
                    + " 依赖，请先处理这些版本再改名");
        }

        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        Path dstJson = dst.resolve(name + ".json");
        Path renamedJson = dst.resolve(target + ".json");
        ObjectNode json = (ObjectNode) mapper.readTree(Files.readString(dstJson));
        json.put("id", target);
        // jar 字段指向自身时同步改名
        if (name.equals(json.path("jar").asText(""))) {
            json.put("jar", target);
        }
        Files.writeString(renamedJson, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
        Files.deleteIfExists(dstJson);

        Path srcJar = dst.resolve(name + ".jar");
        if (Files.isRegularFile(srcJar)) {
            Files.move(srcJar, dst.resolve(target + ".jar"));
        }
        log.info("版本改名：{} → {}", name, target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("renamed", true);
        result.put("from", name);
        result.put("to", target);
        return result;
    }

    private List<String> inheritingChildren(String name) {
        List<String> children = new ArrayList<>();
        Path versionsDir = properties.versionsPath();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String child = dir.getFileName().toString();
                if (child.equals(name)) {
                    continue;
                }
                Path json = dir.resolve(child + ".json");
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                try {
                    String parent = mapper.readTree(json.toFile()).path("inheritsFrom").asText("");
                    if (name.equals(parent)) {
                        children.add(child);
                    }
                } catch (IOException ignored) {
                    // 解析失败按无依赖处理
                }
            }
        } catch (IOException ex) {
            log.warn("扫描版本依赖失败：{}", ex.getMessage());
        }
        return children;
    }

    // ------------------------------------------------------------ 打开目录

    /** 打开实例下的指定目录（root / saves / mods / screenshots），不存在则创建。 */
    public Map<String, Object> openFolder(String name, String target) throws IOException {
        Path base = gameDir(name);
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("版本不存在：" + name);
        }
        Path dir = switch (target == null ? "root" : target) {
            case "saves" -> base.resolve("saves");
            case "mods" -> base.resolve("mods");
            case "screenshots" -> base.resolve("screenshots");
            case "resourcepacks" -> base.resolve("resourcepacks");
            case "shaderpacks" -> base.resolve("shaderpacks");
            default -> base;
        };
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        boolean opened = false;
        try {
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
                opened = true;
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
                opened = true;
            }
        } catch (Exception ex) {
            log.warn("打开目录失败（{}）：{}", dir, ex.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("path", dir.toString());
        result.put("opened", opened);
        return result;
    }

    // ------------------------------------------------------------ 导出启动脚本

    /**
     * 导出启动脚本（Windows 写 .bat，其他系统写 .sh）。
     *
     * <p>复用启动页的启动计划，保证导出的命令与在启动器里点「启动游戏」完全一致。
     */
    public Map<String, Object> exportLaunchScript(String name) throws IOException {
        LaunchPlan plan = launchService.plan(name, null);
        Path dir = gameDir(name);
        boolean windows = isWindows();
        Path script = dir.resolve(windows ? "启动游戏.bat" : "start.sh");
        // 启动计划的首个参数就是 java 可执行文件，避免重复拼接
        List<String> command = plan.command();
        boolean startsWithJava = !command.isEmpty()
                && command.get(0).equalsIgnoreCase(plan.javaPath());
        StringBuilder sb = new StringBuilder();
        if (windows) {
            sb.append("@echo off\r\n");
            sb.append("title ").append(name).append("\r\n");
            sb.append("cd /d \"").append(plan.gameDir()).append("\"\r\n");
            sb.append(quote(startsWithJava ? command.get(0) : plan.javaPath()));
            for (int i = startsWithJava ? 1 : 0; i < command.size(); i++) {
                sb.append(' ').append(quote(command.get(i)));
            }
            sb.append("\r\n");
            sb.append("if errorlevel 1 pause\r\n");
        } else {
            sb.append("#!/bin/sh\n");
            sb.append("cd \"").append(plan.gameDir()).append("\"\n");
            sb.append(quote(startsWithJava ? command.get(0) : plan.javaPath()));
            for (int i = startsWithJava ? 1 : 0; i < command.size(); i++) {
                sb.append(' ').append(quote(command.get(i)));
            }
            sb.append('\n');
            script.toFile().setExecutable(true);
        }
        Charset charset = windows ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        Files.writeString(script, sb.toString(), charset);
        log.info("已导出启动脚本：{}", script);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", script.toString());
        result.put("java", plan.javaPath());
        result.put("memoryMb", plan.memoryMb());
        return result;
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        boolean needQuote = value.isEmpty() || value.chars().anyMatch(Character::isWhitespace);
        return needQuote ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }

    // ------------------------------------------------------------ Mod 列表与可更新标记

    /** Mod 列表（可选检查更新）。 */
    public record ModView(String fileName, String path, boolean enabled, long size,
                          String id, String name, String version, String loader,
                          boolean updateAvailable, String latestVersion) {
    }

    /**
     * 列出 mods 目录。
     *
     * <p>{@code checkUpdates} 时用 SHA1 批量问 Modrinth「这个文件对应项目的最新版本」。
     * 判定规则：返回的最新版本里如果还包含本地这个 SHA1，说明已是最新；否则可更新。
     * 这样即使 Mod 的版本号是 {@code ${file.jarVersion}} 这类占位符也能判对。
     * 只覆盖 Modrinth 上有的 Mod，网络不可用时静默跳过。
     */
    public List<ModView> mods(String name, boolean checkUpdates) {
        List<ModScanner.ModInfo> mods = modScanner.scan(gameDir(name).resolve("mods"));
        Map<String, LatestInfo> latestBySha1 = Map.of();
        if (checkUpdates && !mods.isEmpty()) {
            try {
                InstanceScanner.InstanceInfo info = scanner.info(name);
                String loader = loaderSlug(info.loaders());
                latestBySha1 = latestVersions(mods, loader, info.mcVersion());
            } catch (Exception ex) {
                log.info("检查 Mod 更新失败（忽略）：{}", ex.getMessage());
            }
        }
        List<ModView> result = new ArrayList<>(mods.size());
        for (ModScanner.ModInfo mod : mods) {
            LatestInfo latest = null;
            try {
                String sha1 = DownloadManager.sha1(Path.of(mod.path()));
                latest = latestBySha1.get(sha1);
            } catch (Exception ignored) {
                // 单个文件读取失败不影响其它项
            }
            boolean updatable = latest != null && !latest.upToDate();
            result.add(new ModView(mod.fileName(), mod.path(), mod.enabled(), mod.size(),
                    mod.id(), mod.name(), mod.version(), mod.loader(),
                    updatable, latest == null ? "" : latest.version()));
        }
        return result;
    }

    /** 最新版本信息：版本号 + 本地文件是否就是最新（最新版本的 files 里含本地 SHA1）。 */
    private record LatestInfo(String version, boolean upToDate) {
    }

    private static String loaderSlug(Map<String, String> loaders) {
        if (loaders == null) {
            return "";
        }
        for (String name : loaders.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("neoforge")) {
                return "neoforge";
            }
            if (lower.contains("quilt")) {
                return "quilt";
            }
            if (lower.contains("fabric")) {
                return "fabric";
            }
            if (lower.contains("forge")) {
                return "forge";
            }
        }
        return "";
    }

    /** 批量查询 SHA1 对应的最新版本（Modrinth version_files/update）。 */
    private Map<String, LatestInfo> latestVersions(List<ModScanner.ModInfo> mods, String loader, String mcVersion)
            throws Exception {
        Map<String, String> shaToFile = new HashMap<>();
        List<String> hashes = new ArrayList<>();
        for (ModScanner.ModInfo mod : mods) {
            if (!mod.enabled()) {
                continue;
            }
            try {
                String sha1 = DownloadManager.sha1(Path.of(mod.path()));
                if (sha1 != null && !sha1.isBlank()) {
                    hashes.add(sha1);
                    shaToFile.put(sha1, mod.fileName());
                }
            } catch (Exception ignored) {
                // 跳过读不到的文件
            }
        }
        if (hashes.isEmpty()) {
            return Map.of();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("algorithm", "sha1");
        var hashArray = body.putArray("hashes");
        hashes.forEach(hashArray::add);
        if (loader != null && !loader.isBlank()) {
            body.putArray("loaders").add(loader);
        }
        if (mcVersion != null && !mcVersion.isBlank() && !"Unknown".equals(mcVersion)) {
            body.putArray("game_versions").add(mcVersion);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(MODRINTH + "/version_files/update"))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.info("Modrinth 更新查询返回 HTTP {}", response.statusCode());
            return Map.of();
        }
        Map<String, LatestInfo> result = new HashMap<>();
        var root = mapper.readTree(response.body());
        root.fields().forEachRemaining(entry -> {
            String queried = entry.getKey();
            var version = entry.getValue();
            String number = version.path("version_number").asText("");
            if (number.isBlank()) {
                return;
            }
            // 最新版本的 files 里若还含本地 sha1，说明本地就是最新版
            boolean upToDate = false;
            for (var file : version.path("files")) {
                if (queried.equalsIgnoreCase(file.path("hashes").path("sha1").asText(""))) {
                    upToDate = true;
                    break;
                }
            }
            result.put(queried, new LatestInfo(number, upToDate));
        });
        log.info("Mod 更新检查：{} 个文件，命中 {} 个", shaToFile.size(), result.size());
        return result;
    }

    // ------------------------------------------------------------ 导出整合包

    /**
     * 把当前实例导出成整合包 zip（Modrinth 格式：modrinth.index.json + overrides）。
     *
     * <p>这样导出的包既能被本启动器重新导入，也能被 PCL / Modrinth 生态识别。
     */
    public Map<String, Object> exportPack(String name, String packName, String packVersion,
                                          InstallJobService.Progress progress) throws IOException {
        Path dir = gameDir(name);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("版本不存在：" + name);
        }
        InstanceScanner.InstanceInfo info = scanner.info(name);
        String displayName = packName == null || packName.isBlank() ? name : packName.trim();
        String versionId = packVersion == null || packVersion.isBlank() ? "1.0.0" : packVersion.trim();

        Path exports = properties.minecraftPath().resolve("exports");
        Files.createDirectories(exports);
        Path zipPath = exports.resolve(safeFileName(displayName + "-" + versionId) + ".zip");

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !excludedFromExport(dir.relativize(path), name))
                    .forEach(files::add);
        }

        ObjectNode index = mapper.createObjectNode();
        index.put("formatVersion", 1);
        index.put("game", "minecraft");
        index.put("versionId", versionId);
        index.put("name", displayName);
        index.put("summary", "由 " + properties.getLauncherName() + " 导出的整合包");
        ObjectNode deps = index.putObject("dependencies");
        if (info.mcVersion() != null && !info.mcVersion().isBlank() && !"Unknown".equals(info.mcVersion())) {
            deps.put("minecraft", info.mcVersion());
        }
        if (info.loaders() != null) {
            info.loaders().forEach((loader, version) -> {
                String key = loader.toLowerCase(Locale.ROOT);
                if (key.contains("neoforge")) {
                    deps.put("neoforge", version);
                } else if (key.contains("fabric")) {
                    deps.put("fabric-loader", version);
                } else if (key.contains("quilt")) {
                    deps.put("quilt-loader", version);
                } else if (key.contains("forge")) {
                    deps.put("forge", version);
                }
            });
        }
        index.putArray("files");

        progress.update(5, "收集实例文件…");
        long totalBytes = 0;
        for (Path file : files) {
            try {
                totalBytes += Files.size(file);
            } catch (IOException ignored) {
                // 大小统计失败不影响导出
            }
        }
        Files.deleteIfExists(zipPath);
        int done = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("modrinth.index.json"));
            zos.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(index)
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (Path file : files) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry("overrides/" + relative));
                Files.copy(file, zos);
                zos.closeEntry();
                done++;
                if (done % 20 == 0 || done == files.size()) {
                    int percent = 10 + (int) (85.0 * done / Math.max(1, files.size()));
                    progress.update(percent, "打包 " + done + "/" + files.size() + " 个文件");
                }
            }
        }
        long size = Files.size(zipPath);
        log.info("导出整合包：{}（{} 个文件，{} MB）", zipPath, files.size(), size / 1024 / 1024);
        progress.update(100, "导出完成：" + zipPath.getFileName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", zipPath.toString());
        result.put("fileName", zipPath.getFileName().toString());
        result.put("files", files.size());
        result.put("bytes", size);
        result.put("totalBytes", totalBytes);
        result.put("packName", displayName);
        result.put("packVersion", versionId);
        return result;
    }

    private boolean excludedFromExport(Path relative, String instanceName) {
        List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        if (parts.isEmpty()) {
            return true;
        }
        String first = parts.get(0).toLowerCase(Locale.ROOT);
        if (parts.size() > 1 && EXPORT_SKIP_DIRS.contains(first)) {
            return true;
        }
        String fileName = parts.get(parts.size() - 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        // 版本 JSON / 客户端 jar 属于启动器管理，不打进包
        if (parts.size() == 1 && (fileName.equals(instanceName + ".json") || fileName.equals(instanceName + ".jar"))) {
            return true;
        }
        // 启动器自己生成的启动脚本不属于整合包内容
        if (parts.size() == 1 && (fileName.equals("启动游戏.bat") || fileName.equals("start.sh"))) {
            return true;
        }
        return lower.endsWith(".log") || lower.equals("usercache.json") || lower.equals("launcher_profiles.json");
    }

    private static String safeFileName(String value) {
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "modpack" : cleaned;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 并发保护：同一实例的导出任务不重复执行。 */
    private final Set<String> exporting = ConcurrentHashMap.newKeySet();

    public boolean beginExport(String name) {
        return exporting.add(name);
    }

    public void endExport(String name) {
        exporting.remove(name);
    }
}
