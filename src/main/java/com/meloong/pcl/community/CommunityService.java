package com.meloong.pcl.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.download.DownloadManager;
import com.meloong.pcl.download.ModpackInstaller;
import com.meloong.pcl.download.NetFile;
import com.meloong.pcl.download.SourceKind;
import com.meloong.pcl.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 社区资源：Mod / 整合包 / 数据包 / 资源包 / 光影包的搜索、详情与下载。
 *
 * <p>Modrinth 免 Key 直连；CurseForge 需要设置里填 Key（官方 API 不接受匿名调用）。
 */
@Service
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String CURSE_API = "https://api.curseforge.com/v1";
    private static final String FORGECDN = "https://edge.forgecdn.net/files/";
    private static final String USER_AGENT = "PCL2-Java/1.0 (community-browser)";

    /** 资源类型 → Modrinth project_type 与 CurseForge classId、本地目录。 */
    enum Kind {
        MOD("mod", "mods", 6),
        MODPACK("modpack", "", 4471),
        DATAPACK("datapack", "datapacks", 6945),
        RESOURCEPACK("resourcepack", "resourcepacks", 12),
        SHADER("shader", "shaderpacks", 6552);

        final String modrinthType;
        final String folder;
        final int curseClassId;

        Kind(String modrinthType, String folder, int curseClassId) {
            this.modrinthType = modrinthType;
            this.folder = folder;
            this.curseClassId = curseClassId;
        }

        static Kind of(String value) {
            String key = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return switch (key) {
                case "modpack", "modpacks", "整合包" -> MODPACK;
                case "datapack", "datapacks", "数据包" -> DATAPACK;
                case "resourcepack", "resourcepacks", "资源包" -> RESOURCEPACK;
                case "shader", "shaders", "光影包" -> SHADER;
                default -> MOD;
            };
        }
    }

    /** 搜索结果项。 */
    public record Hit(String id, String slug, String name, String summary, String iconUrl, String author,
                      long downloads, String source, String projectType, List<String> categories,
                      String latestVersion) {
    }

    public record SearchResult(String source, int total, int page, List<Hit> hits, String note) {
    }

    /** 可下载的具体版本。 */
    public record VersionEntry(String id, String name, String versionNumber, List<String> gameVersions,
                               List<String> loaders, String datePublished, long downloads,
                               String fileName, String downloadUrl, long fileSize, boolean featured) {
    }

    public record ProjectDetail(String id, String slug, String name, String summary, String body,
                                String iconUrl, String source, String projectType, String author,
                                long downloads, List<String> gameVersions, List<String> loaders,
                                List<String> categories, List<VersionEntry> versions, String note) {
    }

    private final ObjectMapper mapper;
    private final SettingsService settingsService;
    private final DownloadManager downloadManager;
    private final ModpackInstaller modpackInstaller;
    private final PclProperties properties;
    private final CurseForgeKeyless curseKeyless;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CommunityService(ObjectMapper mapper, SettingsService settingsService,
                            DownloadManager downloadManager, ModpackInstaller modpackInstaller,
                            PclProperties properties, CurseForgeKeyless curseKeyless) {
        this.mapper = mapper;
        this.settingsService = settingsService;
        this.downloadManager = downloadManager;
        this.modpackInstaller = modpackInstaller;
        this.properties = properties;
        this.curseKeyless = curseKeyless;
    }

    // ------------------------------------------------------------ 搜索

    public SearchResult search(String type, String query, String gameVersion, String loader,
                               String source, String category, int page) {
        Kind kind = Kind.of(type);
        int index = Math.max(0, page) * 20;
        String normalizedSource = source == null ? "all" : source.toLowerCase(Locale.ROOT);
        String apiKey = curseKey();

        List<Hit> hits = new ArrayList<>();
        int total = 0;
        String note = "";

        if (!"curseforge".equals(normalizedSource)) {
            try {
                Map<String, Object> result = searchModrinth(kind, query, gameVersion, loader, category, index);
                @SuppressWarnings("unchecked")
                List<Hit> modrinthHits = (List<Hit>) result.get("hits");
                hits.addAll(modrinthHits);
                total += (int) result.get("total");
            } catch (Exception ex) {
                log.info("Modrinth 搜索失败：{}", ex.getMessage());
                note = "Modrinth 搜索失败：" + brief(ex.getMessage());
            }
        }

        if (!"modrinth".equals(normalizedSource)) {
            if (apiKey.isBlank()) {
                if (query == null || query.isBlank()) {
                    // 没有关键词就无法直查：明确告诉用户当前处于免 Key 模式
                    note = note.isBlank() ? "CurseForge 免 Key：可直接输入名称 / CurseForge 链接 / 项目 Slug 直查"
                            : note + "；CurseForge 免 Key：可直接输入名称 / 链接 / Slug 直查";
                } else {
                    // 无 Key：走 cfwidget + MC百科反解的「按名称直查」通道
                    try {
                        List<CurseForgeKeyless.Resolved> direct = curseKeyless.search(query,
                                kind.name().toLowerCase(Locale.ROOT), gameVersion, loader, 5);
                        for (CurseForgeKeyless.Resolved resolved : direct) {
                            hits.add(curseHit(resolved, kind));
                        }
                        total += direct.size();
                        String hint = direct.isEmpty()
                                ? "CurseForge 免 Key：未直查到该项目（免 Key 只能按名称 / 链接 / Slug 直查）"
                                : "CurseForge 免 Key：按名称直查命中 " + direct.size() + " 项";
                        note = note.isBlank() ? hint : note + "；" + hint;
                    } catch (Exception ex) {
                        log.info("CurseForge 免 Key 直查失败：{}", ex.getMessage());
                        note = (note.isBlank() ? "" : note + "；")
                                + "CurseForge 免 Key 直查失败：" + brief(ex.getMessage());
                    }
                }
            } else {
                try {
                    Map<String, Object> result = searchCurse(kind, query, gameVersion, loader, apiKey, index);
                    @SuppressWarnings("unchecked")
                    List<Hit> curseHits = (List<Hit>) result.get("hits");
                    hits.addAll(curseHits);
                    total += (int) result.get("total");
                } catch (Exception ex) {
                    log.info("CurseForge 搜索失败：{}", ex.getMessage());
                    note = (note.isBlank() ? "" : note + "；") + "CurseForge 搜索失败：" + brief(ex.getMessage());
                }
            }
        }

        return new SearchResult(source == null ? "all" : source, total, page, hits, note);
    }

    private Map<String, Object> searchModrinth(Kind kind, String query, String gameVersion, String loader,
                                               String category, int offset) throws Exception {
        String facets = modrinthFacets(kind, gameVersion, loader, category);
        String url = MODRINTH + "/search?limit=20&index=relevance&offset=" + offset
                + "&query=" + encode(query == null ? "" : query.trim())
                + (facets.isBlank() ? "" : "&facets=" + encode(facets));
        JsonNode root;
        try {
            root = getJson(url, Map.of());
        } catch (IOException ex) {
            // 分类 slug 不被接受时退回不加分类的搜索，避免整页空白
            if (category != null && !category.isBlank()) {
                String retry = MODRINTH + "/search?limit=20&index=relevance&offset=" + offset
                        + "&query=" + encode(query == null ? "" : query.trim())
                        + "&facets=" + encode(modrinthFacets(kind, gameVersion, loader, ""));
                root = getJson(retry, Map.of());
            } else {
                throw ex;
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonNode node : root.path("hits")) {
            List<String> categories = new ArrayList<>();
            node.path("categories").forEach(item -> categories.add(item.asText()));
            hits.add(new Hit(node.path("project_id").asText(), node.path("slug").asText(),
                    node.path("title").asText(), node.path("description").asText(),
                    node.path("icon_url").asText(""), node.path("author").asText(""),
                    node.path("downloads").asLong(), "modrinth", node.path("project_type").asText(""),
                    categories, node.path("versions").isArray() && node.path("versions").size() > 0
                    ? node.path("versions").get(node.path("versions").size() - 1).asText() : ""));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("total", root.path("total_hits").asInt(hits.size()));
        return result;
    }

    private String modrinthFacets(Kind kind, String gameVersion, String loader, String category) {
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:" + kind.modrinthType + "\"]");
        if (gameVersion != null && !gameVersion.isBlank()) {
            facets.add("[\"versions:" + gameVersion.trim() + "\"]");
        }
        if (loader != null && !loader.isBlank()) {
            facets.add("[\"categories:" + loader.trim().toLowerCase(Locale.ROOT) + "\"]");
        }
        if (category != null && !category.isBlank()) {
            facets.add("[\"categories:" + category.trim() + "\"]");
        }
        return "[" + String.join(",", facets) + "]";
    }

    private Map<String, Object> searchCurse(Kind kind, String query, String gameVersion, String loader,
                                            String apiKey, int index) throws Exception {
        StringBuilder url = new StringBuilder(CURSE_API).append("/mods/search?gameId=432")
                .append("&classId=").append(kind.curseClassId)
                .append("&pageSize=20&index=").append(index)
                .append("&sortField=2&sortOrder=desc")
                .append("&searchFilter=").append(encode(query == null ? "" : query.trim()));
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(gameVersion.trim()));
        }
        int loaderType = curseLoaderType(loader);
        if (loaderType > 0) {
            url.append("&modLoaderType=").append(loaderType);
        }
        JsonNode root = getJson(url.toString(), Map.of("x-api-key", apiKey));
        JsonNode data = root.path("data");
        List<Hit> hits = new ArrayList<>();
        for (JsonNode node : data) {
            String icon = node.path("logo").path("thumbnailUrl").asText("");
            hits.add(new Hit(node.path("id").asText(), node.path("slug").asText(),
                    node.path("name").asText(), node.path("summary").asText(""), icon,
                    "", node.path("downloadCount").asLong(), "curseforge", kind.modrinthType,
                    List.of(), ""));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("total", root.path("pagination").path("totalCount").asInt(hits.size()));
        return result;
    }

    /** 免 Key 直查结果 → 搜索结果项。 */
    private Hit curseHit(CurseForgeKeyless.Resolved resolved, Kind kind) {
        JsonNode node = resolved.project();
        String author = node.path("members").isArray() && node.path("members").size() > 0
                ? node.path("members").get(0).path("username").asText("") : "";
        List<String> categories = new ArrayList<>();
        node.path("categories").forEach(item -> categories.add(item.asText()));
        return new Hit(node.path("id").asText(), resolved.slug(), node.path("title").asText(),
                node.path("summary").asText(""), node.path("thumbnail").asText(""), author,
                node.path("downloads").path("total").asLong(), "curseforge", kind.modrinthType,
                categories, "");
    }

    private static int curseLoaderType(String loader) {
        if (loader == null) {
            return 0;
        }
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
    }

    // ------------------------------------------------------------ 详情 + 版本列表

    public ProjectDetail project(String source, String id, String gameVersion, String loader) {
        String normalized = source == null ? "modrinth" : source.toLowerCase(Locale.ROOT);
        if ("curseforge".equals(normalized)) {
            String apiKey = curseKey();
            if (apiKey.isBlank()) {
                try {
                    JsonNode node = curseKeyless.byId(id)
                            .orElseThrow(() -> new IOException("CurseForge 项目不存在：" + id)).project();
                    return curseKeyless.toDetail(node, CurseForgeKeyless.projectType(node),
                            gameVersion, loader);
                } catch (Exception ex) {
                    throw new IllegalStateException("获取 CurseForge 项目失败：" + brief(ex.getMessage()), ex);
                }
            }
            try {
                return curseProject(id, gameVersion, loader, apiKey);
            } catch (Exception ex) {
                throw new IllegalStateException("获取 CurseForge 项目失败：" + brief(ex.getMessage()), ex);
            }
        }
        try {
            return modrinthProject(id, gameVersion, loader);
        } catch (Exception ex) {
            throw new IllegalStateException("获取 Modrinth 项目失败：" + brief(ex.getMessage()), ex);
        }
    }

    private ProjectDetail modrinthProject(String id, String gameVersion, String loader) throws Exception {
        JsonNode project = getJson(MODRINTH + "/project/" + encode(id), Map.of());
        StringBuilder url = new StringBuilder(MODRINTH).append("/project/").append(encode(id)).append("/version");
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("game_versions=" + encode("[\"" + gameVersion.trim() + "\"]"));
        }
        if (loader != null && !loader.isBlank()) {
            params.add("loaders=" + encode("[\"" + loader.trim().toLowerCase(Locale.ROOT) + "\"]"));
        }
        if (!params.isEmpty()) {
            url.append('?').append(String.join("&", params));
        }
        JsonNode versions = getJson(url.toString(), Map.of());

        List<VersionEntry> entries = new ArrayList<>();
        for (JsonNode node : versions) {
            JsonNode file = primaryFile(node);
            if (file == null) {
                continue;
            }
            entries.add(new VersionEntry(node.path("id").asText(), node.path("name").asText(),
                    node.path("version_number").asText(), toStringList(node.path("game_versions")),
                    toStringList(node.path("loaders")), node.path("date_published").asText(""),
                    node.path("downloads").asLong(), file.path("filename").asText(""),
                    file.path("url").asText(""), file.path("size").asLong(),
                    file.path("primary").asBoolean(true)));
        }
        return new ProjectDetail(project.path("id").asText(), project.path("slug").asText(),
                project.path("title").asText(), project.path("description").asText(""),
                project.path("body").asText(""), project.path("icon_url").asText(""), "modrinth",
                project.path("project_type").asText(""), "", project.path("downloads").asLong(),
                toStringList(project.path("game_versions")), toStringList(project.path("loaders")),
                toStringList(project.path("categories")), entries, "");
    }

    private ProjectDetail curseProject(String id, String gameVersion, String loader, String apiKey) throws Exception {
        JsonNode project = getJson(CURSE_API + "/mods/" + encode(id), Map.of("x-api-key", apiKey)).path("data");
        StringBuilder url = new StringBuilder(CURSE_API).append("/mods/").append(encode(id))
                .append("/files?pageSize=50");
        if (gameVersion != null && !gameVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(gameVersion.trim()));
        }
        int loaderType = curseLoaderType(loader);
        if (loaderType > 0) {
            url.append("&modLoaderType=").append(loaderType);
        }
        JsonNode files = getJson(url.toString(), Map.of("x-api-key", apiKey)).path("data");

        List<VersionEntry> entries = new ArrayList<>();
        for (JsonNode node : files) {
            long fileId = node.path("id").asLong();
            String fileName = node.path("fileName").asText("");
            String download = node.path("downloadUrl").asText("");
            if (download.isBlank()) {
                download = forgeCdnUrl(fileId, fileName);
            }
            entries.add(new VersionEntry(String.valueOf(fileId), node.path("displayName").asText(fileName),
                    node.path("displayName").asText(fileName), toStringList(node.path("gameVersions")),
                    List.of(), node.path("fileDate").asText(""), node.path("downloadCount").asLong(),
                    fileName, download, node.path("fileLength").asLong(), true));
        }
        return new ProjectDetail(project.path("id").asText(), project.path("slug").asText(),
                project.path("name").asText(), project.path("summary").asText(""),
                "", project.path("logo").path("thumbnailUrl").asText(""), "curseforge",
                "", "", project.path("downloadCount").asLong(),
                toStringList(project.path("latestFilesIndexes").isArray() ? project.path("latestFilesIndexes") : null),
                List.of(), List.of(), entries, "");
    }

    private static String forgeCdnUrl(long fileId, String fileName) {
        return FORGECDN + (fileId / 1000) + "/" + (fileId % 1000) + "/"
                + fileName.replace(" ", "%20");
    }

    private JsonNode primaryFile(JsonNode version) {
        JsonNode files = version.path("files");
        for (JsonNode file : files) {
            if (file.path("primary").asBoolean(false)) {
                return file;
            }
        }
        return files.isArray() && files.size() > 0 ? files.get(0) : null;
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                // latestFilesIndexes 之类对象数组只取 gameVersion 字段
                list.add(item.isObject() ? item.path("gameVersion").asText("") : item.asText());
            }
        }
        return list;
    }

    // ------------------------------------------------------------ 下载安装

    public record InstallRequest(String source, String projectId, String type, String versionId,
                                 String instance, String fileName, String downloadUrl) {
    }

    /**
     * 下载所选版本到当前实例的对应目录。
     *
     * <p>整合包特殊处理：下载后交给 {@link ModpackInstaller} 作为新实例安装；
     * 同名实例已存在时返回 {@code {conflict:true, pendingId, ...}}，与上传流程一致。
     */
    public Map<String, Object> install(InstallRequest request) throws Exception {
        Kind kind = Kind.of(request.type());
        String source = request.source() == null ? "modrinth" : request.source().toLowerCase(Locale.ROOT);

        String fileName;
        String url;
        String sha1 = null;
        long size = 0;
        String projectName = request.projectId();
        if ("curseforge".equals(source)) {
            String apiKey = curseKey();
            if (apiKey.isBlank()) {
                // 免 Key：从 cfwidget 的项目文件列表里找到这一版，拼 forgecdn 直链
                JsonNode project = curseKeyless.byId(request.projectId())
                        .orElseThrow(() -> new IllegalStateException("CurseForge 项目不存在"))
                        .project();
                JsonNode file = curseKeyless.file(project, request.versionId())
                        .orElseThrow(() -> new IllegalStateException("CurseForge 项目里找不到该文件"));
                fileName = file.path("name").asText("");
                url = CurseForgeKeyless.forgeCdnUrl(file.path("id").asLong(), fileName);
                size = file.path("filesize").asLong();
                projectName = project.path("title").asText(projectName);
            } else {
                JsonNode file = getJson(CURSE_API + "/mods/" + encode(request.projectId()) + "/files/"
                        + encode(request.versionId()), Map.of("x-api-key", apiKey)).path("data");
                fileName = file.path("fileName").asText("");
                url = file.path("downloadUrl").asText("");
                if (url.isBlank()) {
                    url = forgeCdnUrl(file.path("id").asLong(), fileName);
                }
                size = file.path("fileLength").asLong();
            }
        } else {
            JsonNode version = getJson(MODRINTH + "/version/" + encode(request.versionId()), Map.of());
            JsonNode file = primaryFile(version);
            if (file == null) {
                throw new IllegalStateException("该版本没有可下载的文件");
            }
            fileName = file.path("filename").asText("");
            url = file.path("url").asText("");
            size = file.path("size").asLong();
            sha1 = file.path("hashes").path("sha1").asText(null);
        }
        if (fileName.isBlank() || url.isBlank()) {
            throw new IllegalStateException("没有解析到下载地址");
        }
        fileName = fileName.replaceAll("[\\\\/]", "_");

        if (kind == Kind.MODPACK) {
            return installPack(source, projectName, fileName, url, sha1, size);
        }

        String instance = request.instance();
        if (instance == null || instance.isBlank()) {
            throw new IllegalArgumentException("请先在启动页选择一个实例");
        }
        Path instanceDir = properties.versionsPath().resolve(instance);
        if (!Files.isDirectory(instanceDir)) {
            throw new IllegalArgumentException("实例不存在：" + instance);
        }
        Path targetDir = instanceDir.resolve(kind.folder);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(fileName);

        boolean skipped = Files.isRegularFile(target) && size > 0 && Files.size(target) == size;
        long bytes = 0;
        if (!skipped) {
            // 同名不同版本时先删，避免残留旧 jar
            Files.deleteIfExists(target);
            NetFile file = NetFile.of(url, target, sha1, size, kind.folder + " / " + fileName,
                    SourceKind.LIBRARY);
            bytes = downloadManager.download(file);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance);
        result.put("type", kind.modrinthType);
        result.put("folder", kind.folder);
        result.put("fileName", fileName);
        result.put("path", target.toString());
        result.put("bytes", bytes);
        result.put("skipped", skipped);
        log.info("社区资源下载完成：{} → {}", fileName, target);
        return result;
    }

    private Map<String, Object> installPack(String source, String projectName, String fileName, String url,
                                            String sha1, long size) throws Exception {
        Path tempDir = properties.dataDirPath().resolve("tmp");
        Files.createDirectories(tempDir);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".mrpack")) {
            fileName = fileName + ".mrpack";
        }
        Path saved = tempDir.resolve("community-" + System.currentTimeMillis() + "-" + fileName);
        downloadManager.download(NetFile.of(url, saved, sha1, size, "整合包 " + projectName, SourceKind.LIBRARY));

        ModpackInstaller.PackInfo info = modpackInstaller.inspect(saved);
        String displayName = projectName == null || projectName.isBlank()
                ? fileName.replaceFirst("\\.[^.]+$", "") : projectName;
        String desired = modpackInstaller.desiredInstanceId(info, displayName);
        if (Files.exists(modpackInstaller.versionsPath().resolve(desired))) {
            ModpackInstaller.Pending pending = modpackInstaller.holdPending(saved, displayName, "", info, desired);
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("conflict", true);
            conflict.put("pendingId", pending.id());
            conflict.put("name", desired);
            conflict.put("format", info.format());
            conflict.put("mcVersion", info.mcVersion());
            conflict.put("loader", info.loader());
            conflict.put("loaderVersion", info.loaderVersion());
            conflict.put("fileCount", info.fileCount());
            conflict.put("saved", saved.toString());
            return conflict;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packJob", true);
        result.put("saved", saved.toString());
        result.put("displayName", displayName);
        result.put("name", desired);
        return result;
    }

    // ------------------------------------------------------------ HTTP

    private String curseKey() {
        try {
            String key = settingsService.get().advanced.curseForgeApiKey;
            return key == null ? "" : key.trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private JsonNode getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + "：" + url);
        }
        return mapper.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String brief(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }
}
