package com.meloong.pcl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.launch.JavaLocator;
import com.meloong.pcl.launch.JavaRuntime;
import com.meloong.pcl.settings.SettingsService;
import com.meloong.pcl.version.MavenCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 扩展加载器安装：Fabric / Forge / NeoForge / OptiFine。
 *
 * <p>对应原工程 <c>PageDownloadInstall</c> 的四个加载器卡片：
 * <ul>
 *   <li>Fabric：直接读 Fabric Meta 的 launcherMeta 生成版本 JSON，不需要运行安装器；</li>
 *   <li>Forge / NeoForge：下载官方 installer，以 <c>--installClient</c> 无界面安装；</li>
 *   <li>OptiFine：下载主 Jar 后按原工程「方式 B」生成继承版本 JSON。</li>
 * </ul>
 */
@Service
public class LoaderInstaller {

    private static final Logger log = LoggerFactory.getLogger(LoaderInstaller.class);

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/%s";
    private static final String BMCL = "https://bmclapi2.bangbang93.com";
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    /**
     * 加载器安装的 5 个阶段，与整合包流程的第 8~12 步一一对应。
     *
     * <p>单独安装加载器时也会按这套名字渲染步骤条，两边口径一致。
     */
    public static final List<String> STAGES = List.of(
            "获取加载器详细信息",
            "下载加载器主文件",
            "分析加载器支持库文件",
            "下载加载器支持库文件",
            "安装加载器");

    /** 声明进入第 {@code index} 个阶段（1 起）。 */
    private static void stage(InstallJobService.Progress progress, int index) {
        if (progress == null || index < 1 || index > STAGES.size()) {
            return;
        }
        progress.stage(index, STAGES.size(), STAGES.get(index - 1));
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final PclProperties properties;
    private final DownloadManager downloadManager;
    private final DownloadSources downloadSources;
    private final VersionManifestService manifestService;
    private final SettingsService settingsService;
    private final JavaLocator javaLocator;

    public LoaderInstaller(RestTemplate restTemplate, ObjectMapper mapper, PclProperties properties,
                           DownloadManager downloadManager, DownloadSources downloadSources,
                           VersionManifestService manifestService,
                           SettingsService settingsService, JavaLocator javaLocator) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.properties = properties;
        this.downloadManager = downloadManager;
        this.downloadSources = downloadSources;
        this.manifestService = manifestService;
        this.settingsService = settingsService;
        this.javaLocator = javaLocator;
    }

    /** 某个原版版本可选的扩展。 */
    public record LoaderOption(String loader, String name, String version, String instanceName) {
    }

    /** 每个原版版本只查一次，避免反复请求镜像（BMCLAPI 有频率限制）。 */
    private record CachedOptions(long at, List<LoaderOption> value) {
    }

    private static final long OPTIONS_TTL_MS = 10 * 60 * 1000L;
    private final Map<String, CachedOptions> optionsCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 查询四个加载器的最新可用版本（拿不到就返回空版本，前端置灰）。 */
    public List<LoaderOption> options(String mcVersion) {
        CachedOptions cached = optionsCache.get(mcVersion);
        if (cached != null && System.currentTimeMillis() - cached.at() < OPTIONS_TTL_MS) {
            return cached.value();
        }

        String fabric = safe(() -> fabricVersion(mcVersion));
        String forge = safe(() -> forgeVersion(mcVersion));
        String neo = safe(() -> neoforgeVersion(mcVersion));
        String optifine = safe(() -> optifineEntry(mcVersion).version());

        List<LoaderOption> result = new ArrayList<>();
        result.add(new LoaderOption("fabric", "Fabric", fabric,
                fabric.isBlank() ? "" : mcVersion + "-fabric" + fabric));
        result.add(new LoaderOption("forge", "Forge", forge,
                forge.isBlank() ? "" : mcVersion + "-forge" + forge));
        result.add(new LoaderOption("neoforge", "NeoForge", neo,
                neo.isBlank() ? "" : mcVersion + "-neoforge" + neo));
        result.add(new LoaderOption("optifine", "OptiFine", optifine,
                optifine.isBlank() ? "" : mcVersion + "-OptiFine_" + optifine));

        // 全部失败（多半是网络或限流）时不缓存，下次重试
        if (!fabric.isBlank() || !forge.isBlank() || !neo.isBlank() || !optifine.isBlank()) {
            optionsCache.put(mcVersion, new CachedOptions(System.currentTimeMillis(), result));
        }
        return result;
    }

    private String safe(VersionSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (Exception ex) {
            log.warn("查询加载器版本失败：{}", ex.getMessage());
            return "";
        }
    }

    private interface VersionSupplier {
        String get() throws Exception;
    }

    // ------------------------------------------------------------ 安装入口

    /**
     * 安装加载器。
     *
     * @param installerVersion 为空时自动选择最新版本
     */
    public Map<String, Object> install(String mcVersion, String loader, String installerVersion,
                                       InstallJobService.Progress progress) throws Exception {
        return install(mcVersion, loader, installerVersion, progress, false);
    }

    /**
     * 安装加载器。
     *
     * @param vanillaReady 原版 JSON / 库 / 资源是否已由调用方准备好（整合包流程自己走完了这几步）。
     *                     为 true 时跳过内部的 ensureVanilla，避免重复扫一遍几千个资源文件
     *                     并覆盖当前阶段的进度消息。
     */
    public Map<String, Object> install(String mcVersion, String loader, String installerVersion,
                                       InstallJobService.Progress progress, boolean vanillaReady)
            throws Exception {
        String id = switch (loader) {
            case "fabric" -> installFabric(mcVersion, installerVersion, progress, vanillaReady);
            case "forge" -> installWithInstaller(mcVersion, "forge", installerVersion, progress, vanillaReady);
            case "neoforge" -> installWithInstaller(mcVersion, "neoforge", installerVersion, progress, vanillaReady);
            case "optifine" -> installOptiFine(mcVersion, installerVersion, progress, vanillaReady);
            default -> throw new IllegalArgumentException("未知的加载器：" + loader);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", id);
        result.put("mcVersion", mcVersion);
        result.put("loader", loader);
        return result;
    }

    /**
     * 只解析加载器版本，不做任何安装。
     *
     * <p>整合包流程要先「获取加载器详细信息」再「下载主文件」，因此把版本探测单独暴露出来；
     * 解析结果原样传回 {@link #install} 的 {@code installerVersion}，避免重复请求镜像。
     */
    public String resolveVersion(String mcVersion, String loader, String hint) throws Exception {
        if (hint != null && !hint.isBlank()) {
            return hint;
        }
        return switch (loader) {
            case "fabric" -> fabricVersion(mcVersion);
            case "forge" -> forgeVersion(mcVersion);
            case "neoforge" -> neoforgeVersion(mcVersion);
            case "optifine" -> optifineEntry(mcVersion).version();
            default -> throw new IllegalArgumentException("未知的加载器：" + loader);
        };
    }

    // ------------------------------------------------------------ Fabric

    private String fabricVersion(String mc) throws Exception {
        JsonNode list = getJson(String.format(FABRIC_META, mc));
        JsonNode picked = null;
        for (JsonNode node : list) {
            if (node.path("loader").path("stable").asBoolean(false)) {
                picked = node;
                break;
            }
        }
        if (picked == null && list.size() > 0) {
            picked = list.get(0);
        }
        if (picked == null) {
            throw new IllegalStateException("Fabric 没有适配 " + mc + " 的版本");
        }
        return picked.path("loader").path("version").asText();
    }


    private String installFabric(String mc, String version, InstallJobService.Progress progress,
                                 boolean vanillaReady) throws Exception {
        if (!vanillaReady) {
            stage(progress, 1);
        }
        progress.update(10, "查询 Fabric 加载器版本…");
        if (!vanillaReady) {
            ensureVanilla(mc, progress);
        }
        JsonNode list = getJson(String.format(FABRIC_META, mc));
        JsonNode picked = null;
        for (JsonNode node : list) {
            String loaderVersion = node.path("loader").path("version").asText();
            if (version == null || version.isBlank() || version.equals(loaderVersion)) {
                if (node.path("loader").path("stable").asBoolean(false) || (version != null && !version.isBlank())) {
                    picked = node;
                    break;
                }
                if (picked == null) {
                    picked = node;
                }
            }
        }
        if (picked == null) {
            throw new IllegalStateException("Fabric 没有该版本：" + mc + " / " + version);
        }
        String loaderVersion = picked.path("loader").path("version").asText();
        String id = mc + "-fabric" + loaderVersion;
        String maven = "https://maven.fabricmc.net/";

        // 主文件就是 fabric-loader 本体，单独下载，界面上对应「下载加载器主文件」
        stage(progress, 2);
        progress.update(22, "下载 Fabric 加载器主文件 " + loaderVersion + "…");
        Path loaderJar = properties.librariesPath()
                .resolve("net/fabricmc/fabric-loader").resolve(loaderVersion)
                .resolve("fabric-loader-" + loaderVersion + ".jar");
        downloadFirst(List.of(maven + "net/fabricmc/fabric-loader/" + loaderVersion
                + "/fabric-loader-" + loaderVersion + ".jar"), loaderJar);

        stage(progress, 3);
        progress.update(38, "分析 Fabric 支持库文件…");
        JsonNode launcherMeta = picked.path("launcherMeta");
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id);
        root.put("inheritsFrom", mc);
        root.put("type", "release");
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        root.put("time", now);
        root.put("releaseTime", now);
        root.put("mainClass", launcherMeta.path("mainClass").path("client")
                .asText("net.fabricmc.loader.impl.launch.knot.KnotClient"));
        ArrayNode libraries = root.putArray("libraries");
        Set<String> seen = new LinkedHashSet<>();
        List<NetFile> libFiles = new ArrayList<>();
        // Fabric Meta 的 launcherMeta 只给了运行期依赖，加载器与 intermediary 要自己补上
        addFabricLibrary(libraries, seen, "net.fabricmc:intermediary:" + mc, maven, libFiles);
        addFabricLibrary(libraries, seen, "net.fabricmc:fabric-loader:" + loaderVersion, maven, libFiles);
        for (String group : List.of("common", "client")) {
            for (JsonNode library : launcherMeta.path("libraries").path(group)) {
                String name = library.path("name").asText();
                if (name.isBlank() || !seen.add(name)) {
                    continue;
                }
                ObjectNode entry = libraries.addObject();
                entry.put("name", name);
                String url = library.path("url").asText("");
                if (!url.isBlank()) {
                    entry.put("url", url);
                }
                addFabricFile(libFiles, name, url.isBlank() ? maven : url);
            }
        }
        ObjectNode arguments = root.putObject("arguments");
        arguments.putArray("game");
        arguments.putArray("jvm");

        stage(progress, 4);
        progress.update(55, "下载 Fabric 支持库文件（共 " + libFiles.size() + " 个）…");
        DownloadManager.DownloadSummary summary = downloadManager.downloadAll(libFiles);
        if (summary.failed() > 0) {
            log.warn("Fabric 支持库有 {} 个未下载成功：{}", summary.failed(), summary.errors());
        }

        stage(progress, 5);
        progress.update(88, "安装 Fabric " + id + "…");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        manifestService.installFromJson(id, json, false);
        progress.update(96, "Fabric 安装完成：" + id);
        return id;
    }

    private void addFabricLibrary(ArrayNode libraries, Set<String> seen, String name, String url,
                                  List<NetFile> files) {
        if (name.isBlank() || !seen.add(name)) {
            return;
        }
        ObjectNode entry = libraries.addObject();
        entry.put("name", name);
        entry.put("url", url);
        addFabricFile(files, name, url);
    }

    /** 把 maven 坐标转成待下载文件，和版本 JSON 里的库条目保持一致。 */
    private void addFabricFile(List<NetFile> files, String name, String baseUrl) {
        try {
            String relative = MavenCoordinate.parse(name).toRelativePath();
            String url = baseUrl.endsWith("/") ? baseUrl + relative : baseUrl + "/" + relative;
            files.add(NetFile.of(url, properties.librariesPath().resolve(relative), null, 0,
                    "支持库 " + name, SourceKind.LIBRARY));
        } catch (RuntimeException ex) {
            log.debug("跳过无法解析的依赖：{}", name);
        }
    }

    // ------------------------------------------------------------ Forge / NeoForge

    private String forgeVersion(String mc) throws Exception {
        JsonNode list = getJson(BMCL + "/forge/minecraft/" + mc);
        List<String> versions = new ArrayList<>();
        for (JsonNode node : list) {
            String value = node.path("version").asText();
            if (!value.isBlank()) {
                versions.add(value);
            }
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException("Forge 没有适配 " + mc + " 的版本");
        }
        versions.sort(Comparator.comparing(LoaderInstaller::numericKey).reversed());
        return versions.get(0);
    }


    private String neoforgePrefix(String mc) {
        // 1.20.1 的 NeoForge 沿用 Forge 的 47.1.x 版本号，1.20.2 起改为 20.2.x
        if ("1.20.1".equals(mc)) {
            return "47.1.";
        }
        // 1.21.1 → 21.1；1.20.2 → 20.2
        String value = mc.startsWith("1.") ? mc.substring(2) : mc;
        return value + ".";
    }

    private List<String> neoforgeVersions(String mc) throws Exception {
        String xml = getText(BMCL + "/maven/net/neoforged/neoforge/maven-metadata.xml");
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("NeoForge 版本列表为空");
        }
        String prefix = neoforgePrefix(mc);
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_TAG.matcher(xml);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.startsWith(prefix)) {
                versions.add(value);
            }
        }
        versions.sort(Comparator.comparing(LoaderInstaller::numericKey).reversed());
        return versions;
    }

    private String neoforgeVersion(String mc) throws Exception {
        List<String> versions = neoforgeVersions(mc);
        if (versions.isEmpty()) {
            throw new IllegalStateException("NeoForge 没有适配 " + mc + " 的版本");
        }
        for (String version : versions) {
            String lower = version.toLowerCase();
            if (!lower.contains("beta") && !lower.contains("alpha")) {
                return version;
            }
        }
        return versions.get(0);
    }


    /** 下载 installer 并以 --installClient 无界面安装。 */
    private String installWithInstaller(String mc, String loader, String version,
                                        InstallJobService.Progress progress, boolean vanillaReady)
            throws Exception {
        String installVersion = version == null || version.isBlank()
                ? ("forge".equals(loader) ? forgeVersion(mc) : neoforgeVersion(mc))
                : version;
        if (!vanillaReady) {
            stage(progress, 1);
        }
        if (vanillaReady) {
            progress.update(5, "原版 " + mc + " 已就绪…");
        } else {
            progress.update(5, "准备原版 " + mc + " 环境…");
            ensureVanilla(mc, progress);
        }

        String fileName = "forge".equals(loader)
                ? "forge-" + mc + "-" + installVersion + "-installer.jar"
                : "neoforge-" + installVersion + "-installer.jar";
        List<String> urls = "forge".equals(loader)
                ? List.of(
                BMCL + "/maven/net/minecraftforge/forge/" + mc + "-" + installVersion + "/" + fileName,
                "https://maven.minecraftforge.net/net/minecraftforge/forge/" + mc + "-" + installVersion + "/" + fileName)
                : List.of(
                BMCL + "/maven/net/neoforged/neoforge/" + installVersion + "/" + fileName,
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + installVersion + "/" + fileName);

        Path installer = properties.dataDirPath().resolve("cache").resolve("installers").resolve(fileName);
        stage(progress, 2);
        progress.update(15, "下载加载器主文件 " + fileName + "…");
        downloadFirst(urls, installer);

        Path mcDir = properties.minecraftPath();
        Files.createDirectories(mcDir);
        createLauncherProfiles(mcDir);

        // 运行前先记录已有版本 JSON 的修改时间，安装完成后对比即可识别新装的版本，
        // 不依赖目录命名规则（各家加载器命名差异太大）
        Map<String, Long> before = snapshotVersionJsons();

        Prefill prefill = prefillInstallerLibraries(installer, mcDir, loader, progress);
        stage(progress, 5);
        progress.update(45, prefill.total() > 0
                ? "运行安装器（依赖已就绪，本地组装）…"
                : "运行安装器（这一步会下载较多文件，请耐心等待）…");
        // installertools 的 DOWNLOAD_MOJMAPS 直写不校验，网络抖动会留下截断文件并让
        // 后续 MERGE_MAPPING 崩溃，所以这里先自己校验着下好，再让安装器复用它
        Path effectiveInstaller = installer;
        if (prepareMojmaps(installer, mcDir, mc)) {
            Path patched = patchInstallerMojmaps(installer);
            if (patched != null) {
                effectiveInstaller = patched;
            }
        }
        try {
            runInstaller(effectiveInstaller, mcDir, prefill.offline(), progress);
        } catch (IllegalStateException ex) {
            if (prefill.total() <= 0) {
                throw ex;
            }
            // 本地组装失败时退回联网模式，便于网络正常的环境自行补齐
            log.warn("离线安装未成功，改用联网模式重试：{}", ex.getMessage());
            progress.update(45, "本地组装未成功，改用联网模式重试…");
            runInstaller(effectiveInstaller, mcDir, false, progress);
        }
        String installedId = findInstalled(mc, loader, before);
        if (installedId == null) {
            throw new IllegalStateException("安装器没有生成版本目录，请查看日志");
        }
        progress.update(92, "补全 " + installedId + " 的依赖库…");
        try {
            manifestService.complete(installedId, false);
        } catch (Exception ex) {
            log.warn("补全依赖失败（安装器通常已下载）：{}", ex.getMessage());
        }
        progress.update(96, "安装完成：" + installedId);
        return installedId;
    }

    /**
     * 原版是否已就绪：版本 JSON 与客户端 jar 都必须在本地。
     * 安装器的离线模式不会联网补这两个文件，缺一个就会报错。
     */
    private void ensureVanilla(String mc, InstallJobService.Progress progress) throws IOException {
        Path dir = properties.versionsPath().resolve(mc);
        Path json = dir.resolve(mc + ".json");
        Path jar = dir.resolve(mc + ".jar");
        if (Files.isRegularFile(json) && Files.isRegularFile(jar)) {
            // 原版已装好，但资源文件（assets）可能缺——缺了进游戏会到处丢贴图，这里补一下
            ensureAssets(mc, progress);
            return;
        }
        progress.update(6, "原版 " + mc + " 尚未安装完整，先下载原版…");
        manifestService.install(mc, true);
    }

    /** 资源文件（assets）缺失时补下载；失败不影响安装主流程。 */
    private void ensureAssets(String mc, InstallJobService.Progress progress) {
        try {
            progress.update(8, "检查游戏资源文件（assets）…");
            int downloaded = manifestService.ensureAssets(mc);
            if (downloaded > 0) {
                progress.update(10, "补充了 " + downloaded + " 个资源文件");
            }
        } catch (Exception ex) {
            log.warn("检查/补全资源文件失败：{}", ex.getMessage());
        }
    }

    /** 预下载结果：文件数，以及安装器是否支持 {@code --offline}（只有 install_profile v1 支持）。 */
    private record Prefill(int total, boolean offline) {
    }

    /**
     * 预下载安装器清单里的全部依赖库（走镜像）。
     *
     * <p>Forge / NeoForge 的安装器只会请求 maven.neoforged.net 或 maven.minecraftforge.net，
     * 这两个域名在国内常被直接拒绝（{@code Connection refused}），而安装器的 {@code --mirror}
     * 参数只影响 Mojang 元数据，改不了 maven 地址。因此这里先按 {@code install_profile.json}
     * 的 artifact 清单把文件下载并校验到 libraries 目录，再让安装器以 {@code --offline}
     * 只做本地组装（解包、打补丁），从而不依赖那两个域名。
     *
     * <p>老版本 Forge（1.7.10 / 1.12.2 那批）的清单是 v0 格式、安装器也没有 {@code --offline}，
     * 但它会先检查本地文件是否存在再决定是否联网，所以这里同样预下载，只是不加离线参数。
     */
    private Prefill prefillInstallerLibraries(Path installer, Path mcDir, String loader,
                                              InstallJobService.Progress progress) throws IOException {
        stage(progress, 3);
        progress.update(28, "分析安装器支持库清单…");
        JsonNode profile;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            ZipEntry entry = zip.getEntry("install_profile.json");
            if (entry == null) {
                return new Prefill(0, false);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                profile = mapper.readTree(in);
            }
        }
        JsonNode libraries = profile.path("libraries");
        if (!libraries.isArray() || libraries.isEmpty()) {
            return new Prefill(0, false);
        }
        boolean offline = "1".equals(profile.path("spec").asText(""))
                && profile.path("processors").isArray() && !profile.path("processors").isEmpty();

        String mavenBase = "forge".equals(loader)
                ? "https://maven.minecraftforge.net"
                : "https://maven.neoforged.net/releases";
        List<NetFile> files = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (JsonNode library : libraries) {
            JsonNode artifact = library.path("downloads").path("artifact");
            String relative = artifact.path("path").asText("");
            if (relative.isBlank()) {
                String name = library.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                try {
                    relative = MavenCoordinate.parse(name).toRelativePath();
                } catch (RuntimeException ex) {
                    log.debug("跳过无法解析的依赖：{}", name);
                    continue;
                }
            }
            String url = artifact.path("url").asText("");
            if (url.isBlank()) {
                // v0 清单把下载地址直接写在 library.url 上
                url = library.path("url").asText("");
            }
            if (url.isBlank()) {
                url = mavenBase + "/" + relative;
            }
            String sha1 = artifact.path("sha1").asText("");
            long size = artifact.path("size").asLong(0);
            Path target = mcDir.resolve("libraries").resolve(relative);
            if (seen.add(target)) {
                files.add(NetFile.of(url, target, sha1.isBlank() ? null : sha1, size,
                        "安装器依赖 " + relative, SourceKind.LIBRARY));
            }
        }
        // 安装器 version.json 里声明的加载器运行库（如 asm 9.7.1、eventbus 6.0.5）
        // 不在 install_profile 内，离线模式下缺失会直接判定「libraries failed to download」
        appendVersionLibraries(installer, mcDir, files, seen);

        stage(progress, 4);
        progress.update(36, "下载加载器支持库（共 " + files.size() + " 个）…");
        DownloadManager.DownloadSummary summary = downloadManager.downloadAll(files);
        progress.update(43, "依赖就绪（新下载 " + summary.downloaded() + "，已存在 " + summary.skipped()
                + (summary.failed() > 0 ? "，失败 " + summary.failed() : "") + "）");
        if (summary.failed() > 0) {
            log.warn("安装器依赖有 {} 个未下载成功：{}", summary.failed(), summary.errors());
        }
        return new Prefill(files.size(), offline);
    }

    /** 追加安装器 {@code version.json} 里声明的加载器运行库（离线模式不会自行下载）。 */
    private void appendVersionLibraries(Path installer, Path mcDir, List<NetFile> files, Set<Path> seen) {
        JsonNode version;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            ZipEntry entry = zip.getEntry("version.json");
            if (entry == null) {
                return;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                version = mapper.readTree(in);
            }
        } catch (IOException ex) {
            log.debug("读取安装器 version.json 失败：{}", ex.getMessage());
            return;
        }
        for (JsonNode library : version.path("libraries")) {
            JsonNode artifact = library.path("downloads").path("artifact");
            String relative = artifact.path("path").asText("");
            String url = artifact.path("url").asText("");
            if (relative.isBlank() || url.isBlank()) {
                continue;
            }
            Path target = mcDir.resolve("libraries").resolve(relative);
            if (!seen.add(target)) {
                continue;
            }
            String sha1 = artifact.path("sha1").asText("");
            long size = artifact.path("size").asLong(0);
            files.add(NetFile.of(url, target, sha1.isBlank() ? null : sha1, size,
                    "加载器运行库 " + relative, SourceKind.LIBRARY));
        }
    }

    /**
     * 预置 Mojang 官方映射表（{@code DOWNLOAD_MOJMAPS} 的产物）。
     *
     * <p>installertools 的 {@code DownloadMojmaps} 是 {@code URL.openStream()} 直接
     * {@code Files.copy}，既不校验 sha1 / 大小、也不重试。国内链路抖动时会写出一个
     * 截断的 txt，紧接着 {@code MERGE_MAPPING} 解析 proguard 格式就抛
     * {@code ArrayIndexOutOfBoundsException}，整次安装失败。这里改用带校验和镜像的
     * 下载器先把它放好，再让安装器通过 {@code --skipIfExists} 直接复用。
     *
     * @return 文件是否已就绪
     */
    private boolean prepareMojmaps(Path installer, Path mcDir, String mc) {
        try {
            JsonNode profile;
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                ZipEntry entry = zip.getEntry("install_profile.json");
                if (entry == null) {
                    return false;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    profile = mapper.readTree(in);
                }
            }
            String relative = mavenRelative(profile.path("data").path("MOJMAPS").path("client").asText(""));
            if (relative == null) {
                return false;
            }
            Path vanillaJson = properties.versionsPath().resolve(mc).resolve(mc + ".json");
            if (!Files.isRegularFile(vanillaJson)) {
                return false;
            }
            JsonNode mappings = mapper.readTree(Files.readAllBytes(vanillaJson))
                    .path("downloads").path("client_mappings");
            String url = mappings.path("url").asText("");
            String sha1 = mappings.path("sha1").asText("");
            if (url.isBlank() || sha1.isBlank()) {
                return false;
            }
            long size = mappings.path("size").asLong(0);
            Path target = mcDir.resolve("libraries").resolve(relative);
            DownloadManager.DownloadSummary summary = downloadManager.downloadAll(List.of(
                    NetFile.of(url, target, sha1, size, "Mojang 官方映射表", SourceKind.META)));
            if (summary.failed() > 0) {
                log.warn("Mojang 映射表预置失败，交回安装器自行下载：{}", summary.errors());
                return false;
            }
            log.info("Mojang 映射表已就绪：{}", target);
            return true;
        } catch (Exception ex) {
            log.warn("准备 Mojang 映射表失败，交回安装器自行下载：{}", ex.getMessage());
            return false;
        }
    }

    /** 把 {@code [group:artifact:version:classifier@ext]} 解析成 maven 相对路径。 */
    private static String mavenRelative(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            return null;
        }
        String text = coordinate.trim();
        if (text.startsWith("[")) {
            text = text.substring(1);
        }
        if (text.endsWith("]")) {
            text = text.substring(0, text.length() - 1);
        }
        String ext = "jar";
        int at = text.indexOf('@');
        if (at >= 0) {
            ext = text.substring(at + 1);
            text = text.substring(0, at);
        }
        String[] parts = text.split(":");
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : "";
        String fileName = artifact + "-" + version
                + (classifier.isBlank() ? "" : "-" + classifier) + "." + ext;
        return group + "/" + artifact + "/" + version + "/" + fileName;
    }

    /**
     * 生成一份「跳过映射表下载」的安装器副本。
     *
     * <p>只做一件事：给 {@code processors} 中 {@code DOWNLOAD_MOJMAPS} 的参数追加
     * {@code --skipIfExists}（installertools 自带选项），让它复用已校验好的本地文件。
     * 由于条目被改动，必须顺手去掉 jar 签名（{@code META-INF/*.SF|DSA|RSA}）并重建
     * MANIFEST，否则读取时会触发 digest 校验异常。
     *
     * @return 副本路径；无法生成时返回 {@code null}（继续用原装安装器）
     */
    private Path patchInstallerMojmaps(Path installer) {
        try {
            Path target = installer.resolveSibling(
                    installer.getFileName().toString().replaceFirst("\\.jar$", "-pcl.jar"));
            if (Files.isRegularFile(target)
                    && Files.getLastModifiedTime(target).toMillis() >= Files.getLastModifiedTime(installer).toMillis()) {
                return target;
            }
            ObjectNode profile;
            Manifest original = new Manifest();
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                ZipEntry entry = zip.getEntry("install_profile.json");
                if (entry == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    profile = (ObjectNode) mapper.readTree(in);
                }
                ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
                if (mf != null) {
                    try (InputStream in = zip.getInputStream(mf)) {
                        original.read(in);
                    }
                }
            }
            boolean changed = false;
            for (JsonNode node : profile.path("processors")) {
                if (!(node instanceof ObjectNode processor)) {
                    continue;
                }
                JsonNode argsNode = processor.get("args");
                if (!(argsNode instanceof ArrayNode args)) {
                    continue;
                }
                boolean mojmaps = false;
                boolean skip = false;
                for (JsonNode arg : args) {
                    String text = arg.asText("");
                    if ("DOWNLOAD_MOJMAPS".equals(text)) {
                        mojmaps = true;
                    } else if ("--skipIfExists".equals(text)) {
                        skip = true;
                    }
                }
                if (mojmaps && !skip) {
                    args.add("--skipIfExists");
                    changed = true;
                }
            }
            if (!changed) {
                return null;
            }

            Manifest clean = new Manifest();
            clean.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            String mainClass = original.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null) {
                clean.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
            }
            byte[] patchedProfile = mapper.writeValueAsBytes(profile);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.createDirectories(target.getParent());
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(installer));
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    String upper = name.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".DSA")
                            || upper.endsWith(".RSA") || upper.equals("META-INF/MANIFEST.MF"))) {
                        continue;
                    }
                    out.putNextEntry(new ZipEntry(name));
                    if ("install_profile.json".equals(name)) {
                        out.write(patchedProfile);
                    } else if (!entry.isDirectory()) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                clean.write(out);
                out.closeEntry();
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("已生成跳过映射表下载的安装器副本：{}", target.getFileName());
            return target;
        } catch (Exception ex) {
            log.warn("生成安装器副本失败，继续使用原安装器：{}", ex.getMessage());
            return null;
        }
    }

    private void createLauncherProfiles(Path mcDir) throws IOException {
        Path file = mcDir.resolve("launcher_profiles.json");
        if (Files.isRegularFile(file)) {
            return;
        }
        String json = "{\"profiles\":{},\"settings\":{\"enableSnapshots\":false},"
                + "\"version\":3,\"clientToken\":\"pcl-java21\"}";
        Files.writeString(file, json);
    }

    /**
     * 运行安装器。
     *
     * @param offline true 时加 {@code --offline}，安装器只做本地组装、不发起任何网络请求
     */
    private void runInstaller(Path installer, Path mcDir, boolean offline,
                              InstallJobService.Progress progress)
            throws IOException, InterruptedException {
        JavaRuntime java = installerJava();
        List<String> command = new ArrayList<>(List.of(java.executable().toString(), "-jar",
                installer.toString(), "--installClient", mcDir.toString()));
        if (offline) {
            command.add("--offline");
        }
        log.info("运行安装器：{}", String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(mcDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Path logFile = properties.dataDirPath().resolve("logs")
                .resolve("installer-" + System.currentTimeMillis() + ".log");
        Files.createDirectories(logFile.getParent());
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (lines++ % 6 == 0) {
                    int percent = 46 + Math.min(44, lines / 8);
                    progress.update(percent, trim(line));
                }
            }
        }
        boolean finished = process.waitFor(20, TimeUnit.MINUTES);
        Files.writeString(logFile, output.toString());
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("安装器超时（20 分钟），日志：" + logFile);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("安装器退出码 " + process.exitValue() + "，日志：" + logFile);
        }
    }

    private JavaRuntime installerJava() {
        String configured = settingsService.raw().advanced.javaPath;
        if (configured != null && !configured.isBlank()) {
            JavaRuntime runtime = javaLocator.findByPath(configured);
            if (runtime.available()) {
                return runtime;
            }
        }
        JavaRuntime runtime = javaLocator.findBest(17);
        if (runtime == null) {
            runtime = javaLocator.findBest(8);
        }
        if (runtime == null) {
            throw new IllegalStateException("未找到可用的 Java，无法运行安装器");
        }
        return runtime;
    }

    /**
     * 记录各版本目录下版本 JSON 的修改时间，用于识别安装器刚刚生成/更新的版本。
     */
    private Map<String, Long> snapshotVersionJsons() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        Path versionsDir = properties.versionsPath();
        if (!Files.isDirectory(versionsDir)) {
            return snapshot;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                Path json = path.resolve(path.getFileName().toString() + ".json");
                if (Files.isRegularFile(json)) {
                    try {
                        snapshot.put(path.getFileName().toString(), Files.getLastModifiedTime(json).toMillis());
                    } catch (IOException ex) {
                        log.debug("读取版本 JSON 时间失败：{}", json);
                    }
                }
            });
        } catch (IOException ex) {
            log.debug("扫描 versions 目录失败：{}", ex.getMessage());
        }
        return snapshot;
    }

    /**
     * 找到安装器刚生成的版本目录。
     *
     * <p>优先用「安装前后版本 JSON 的新增 / 更新」来判断——安装器刚写出的目录一定
     * 是本次安装的结果，不受命名差异影响。找不到时退回按名称 / {@code inheritsFrom} 匹配。
     */
    private String findInstalled(String mc, String loader, Map<String, Long> before) {
        String updated = snapshotVersionJsons().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase().contains(loader))
                .filter(entry -> {
                    Long old = before.get(entry.getKey());
                    return old == null || entry.getValue() > old;
                })
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (updated != null) {
            return updated;
        }
        return findInstalledByMatch(mc, loader);
    }

    private String findInstalledByMatch(String mc, String loader) {
        Path versionsDir = properties.versionsPath();
        if (!Files.isDirectory(versionsDir)) {
            return null;
        }
        try (var stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> matchesInstalled(path, mc, loader))
                    .max(Comparator.comparingLong(this::versionJsonTime))
                    .map(path -> path.getFileName().toString())
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private boolean matchesInstalled(Path dir, String mc, String loader) {
        String name = dir.getFileName().toString();
        boolean named = name.startsWith(mc + "-" + loader) || name.startsWith(loader + "-")
                || (name.startsWith(mc + "-") && name.toLowerCase().contains(loader));
        Path json = dir.resolve(name + ".json");
        if (!Files.isRegularFile(json)) {
            return named;
        }
        try {
            String parent = mapper.readTree(json.toFile()).path("inheritsFrom").asText("");
            if (!parent.isBlank()) {
                // 安装器可能装的是比所选版本更细的补丁版本（如选 26.1 装出继承 26.1.2 的版本），
                // 因此只要求同族，不要求严格相等
                return parent.equals(mc) || parent.startsWith(mc) || mc.startsWith(parent)
                        ? name.toLowerCase().contains(loader) : named;
            }
        } catch (IOException ex) {
            log.debug("读取版本 JSON 失败：{}", json);
        }
        return named;
    }

    private long versionJsonTime(Path dir) {
        Path json = dir.resolve(dir.getFileName() + ".json");
        try {
            return Files.getLastModifiedTime(json).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ OptiFine

    public record OptiFineEntry(String version, String type, String patch, String fileName, String downloadUrl) {
    }

    private OptiFineEntry optifineEntry(String mc) throws Exception {
        JsonNode list = getJson(BMCL + "/optifine/" + mc);
        OptiFineEntry best = null;
        for (JsonNode node : list) {
            String patch = node.path("patch").asText();
            String type = node.path("type").asText();
            String fileName = node.path("filename").asText();
            if (patch.isBlank() || fileName.isBlank() || patch.toLowerCase().contains("pre")) {
                continue;
            }
            String url = BMCL + "/optifine/" + mc + "/" + type + "/" + patch;
            OptiFineEntry entry = new OptiFineEntry(type + "_" + patch, type, patch, fileName, url);
            if (best == null || numericKey(entry.version().replace("_", "."))
                    .compareTo(numericKey(best.version().replace("_", "."))) > 0) {
                best = entry;
            }
        }
        if (best == null) {
            throw new IllegalStateException("OptiFine 没有适配 " + mc + " 的稳定版本");
        }
        return best;
    }


    private String installOptiFine(String mc, String version, InstallJobService.Progress progress,
                                   boolean vanillaReady) throws Exception {
        if (!vanillaReady) {
            stage(progress, 1);
        }
        progress.update(10, "查询 OptiFine 版本…");
        OptiFineEntry entry = optifineEntry(mc);
        if (version != null && !version.isBlank()) {
            String normalized = version.replace("OptiFine_", "").replace(mc + "_", "");
            if (!normalized.equals(entry.version())) {
                // 指定了别的版本时按 type/patch 反查
                JsonNode list = getJson(BMCL + "/optifine/" + mc);
                for (JsonNode node : list) {
                    String candidate = node.path("type").asText() + "_" + node.path("patch").asText();
                    if (candidate.equals(normalized) || candidate.equals(version)) {
                        entry = new OptiFineEntry(candidate, node.path("type").asText(),
                                node.path("patch").asText(), node.path("filename").asText(),
                                BMCL + "/optifine/" + mc + "/" + node.path("type").asText()
                                        + "/" + node.path("patch").asText());
                        break;
                    }
                }
            }
        }

        if (!vanillaReady) {
            ensureVanilla(mc, progress);
        }
        String id = mc + "-OptiFine_" + entry.version();
        String libraryVersion = mc + "_" + entry.version();
        Path jar = properties.librariesPath()
                .resolve("optifine").resolve("OptiFine").resolve(libraryVersion)
                .resolve("OptiFine-" + libraryVersion + ".jar");

        stage(progress, 2);
        progress.update(25, "下载加载器主文件 OptiFine " + entry.version() + "…");
        downloadFirst(List.of(entry.downloadUrl()), jar);

        stage(progress, 3);
        progress.update(55, "分析 OptiFine 支持库文件…");
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String json = """
                {
                  "id": "%s",
                  "inheritsFrom": "%s",
                  "type": "release",
                  "time": "%s",
                  "releaseTime": "%s",
                  "minimumLauncherVersion": 21,
                  "mainClass": "net.minecraft.launchwrapper.Launch",
                  "libraries": [
                    {"name": "optifine:OptiFine:%s"},
                    {"name": "net.minecraft:launchwrapper:1.12"}
                  ],
                  "arguments": {
                    "game": ["--tweakClass", "optifine.OptiFineTweaker"]
                  }
                }
                """.formatted(id, mc, now, now, libraryVersion);

        stage(progress, 4);
        progress.update(70, "下载 OptiFine 支持库文件（launchwrapper 等）…");
        Path launchwrapper = properties.librariesPath()
                .resolve("net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar");
        try {
            downloadFirst(List.of(
                    BMCL + "/maven/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                    "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
                    launchwrapper);
        } catch (IOException ex) {
            log.warn("launchwrapper 下载失败（安装器会再试一次）：{}", ex.getMessage());
        }

        stage(progress, 5);
        progress.update(88, "安装 OptiFine " + id + "…");
        manifestService.installFromJson(id, json, false);
        progress.update(96, "OptiFine 安装完成：" + id);
        return id;
    }

    // ------------------------------------------------------------ 工具

    /** 纯文本下载（XML / HTML），带一次重试，缓解镜像限流。 */
    private String getText(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return restTemplate.getForObject(url, String.class);
            } catch (Exception ex) {
                last = ex;
                Thread.sleep(400);
            }
        }
        throw last == null ? new IllegalStateException("请求失败：" + url) : last;
    }

    private JsonNode getJson(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
    }

    /** 依次尝试多个地址，第一个成功的下载到目标路径。 */
    private void downloadFirst(List<String> urls, Path target) throws IOException {
        IOException last = null;
        for (String url : urls) {
            try {
                downloadManager.download(NetFile.of(url, target, null, 0, target.getFileName().toString(),
                        SourceKind.LIBRARY));
                if (Files.isRegularFile(target) && Files.size(target) > 1024) {
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断", ex);
            } catch (Exception ex) {
                last = ex instanceof IOException io ? io : new IOException(ex.getMessage(), ex);
                log.warn("下载失败：{}（{}）", url, ex.getMessage());
            }
        }
        throw last == null ? new IOException("下载失败：" + target) : last;
    }

    /** 版本号比较用的数字键：47.2.0 → 47.002.000。 */
    private static String numericKey(String version) {
        StringBuilder builder = new StringBuilder();
        for (String part : version.split("[.\\-+_]")) {
            if (part.isBlank()) {
                continue;
            }
            String digits = part.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                continue;
            }
            if (digits.length() > 6) {
                digits = digits.substring(0, 6);
            }
            builder.append(String.format("%6s", digits).replace(' ', '0')).append('.');
        }
        return builder.length() == 0 ? "0" : builder.toString();
    }

    private static String trim(String line) {
        if (line == null) {
            return "";
        }
        String value = line.strip();
        return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }
}
