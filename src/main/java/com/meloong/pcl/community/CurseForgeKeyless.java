package com.meloong.pcl.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 免 Key 的 CurseForge 查询通道。
 *
 * <p>CurseForge 官方 API 必须带 Key，站点接口被 Cloudflare 挡在外面，所以没有 Key 时
 * 用两条免 Key 通路拼出「按名称直查」的能力：
 * <ul>
 *   <li><b>cfwidget</b>：按项目 ID / Slug 取项目详情与全部文件列表（含文件名、体积、适配版本、加载器）；</li>
 *   <li><b>MC百科（mcmod.cn）</b>：把中文名、缩写或紧凑文件名（如 lanserverproperties）
 *       反解出英文名（如 Lan Server Properties），再转成 CurseForge 的 Slug。</li>
 * </ul>
 *
 * <p>因此免 Key 时只能「直查」而不能做全文检索——全文检索只有官方 API 提供。
 */
@Service
public class CurseForgeKeyless {

    private static final Logger log = LoggerFactory.getLogger(CurseForgeKeyless.class);
    private static final String CFWIDGET = "https://api.cfwidget.com";
    private static final String MCMOD_SEARCH = "https://search.mcmod.cn/s?key=";
    private static final String FORGECDN = "https://edge.forgecdn.net/files/";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** cfwidget 的分类路径前缀，按资源类型顺序尝试。 */
    private static final List<String> PREFIXES = List.of(
            "minecraft/mc-mods", "minecraft/modpacks", "minecraft/texture-packs",
            "minecraft/shaders", "minecraft/worlds");

    private static final Set<String> LOADERS = Set.of("forge", "fabric", "quilt", "neoforge",
            "liteloader", "rift");
    private static final Pattern MC_VERSION = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "curseforge\\.com/(?:minecraft/)?([a-z-]+)/([a-z0-9][a-z0-9-]*)");
    private static final Pattern MCMOD_ITEM = Pattern.compile(
            "<div class=\"result-item\">.*?href=\"https?://www\\.mcmod\\.cn/class/(\\d+)\\.html\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 解析结果：cfwidget 项目节点 + 官方 Slug。 */
    public record Resolved(JsonNode project, String slug) {
        public String id() {
            return project.path("id").asText();
        }

        public String title() {
            return project.path("title").asText("");
        }
    }

    /** MC百科的搜索结果条目：中文名 + 若干候选英文名（方括号缩写、英文全称）。 */
    record McmodHit(String classId, String title, List<String> aliases) {
    }

    /** 单次查询的请求预算：防止「组合爆炸 + 404」把一次搜索拖到几十秒。 */
    private static final class Budget {
        private final int maxCalls;
        private final long deadline;
        private int calls;

        Budget(int maxCalls, long millis) {
            this.maxCalls = maxCalls;
            this.deadline = System.nanoTime() + millis * 1_000_000L;
        }

        boolean spend() {
            if (expired()) {
                return false;
            }
            calls++;
            return true;
        }

        boolean expired() {
            return calls >= maxCalls || System.nanoTime() > deadline;
        }
    }

    private final ObjectMapper mapper;
    private final Map<String, Optional<Resolved>> pathCache = new ConcurrentHashMap<>();
    private final Map<String, List<McmodHit>> mcmodCache = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CurseForgeKeyless(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------ 对外入口

    /**
     * 把一个自由文本输入解析成 CurseForge 项目。
     *
     * <p>接受：数字项目 ID、CurseForge 链接、项目 Slug、英文名、中文名、紧凑文件名
     * （例如 {@code lanserverproperties} 会被反解成 slug {@code lan-server-properties}）。
     */
    public Optional<Resolved> resolve(String raw, String expectedType) {
        return resolve(raw, expectedType, new Budget(24, 8_000));
    }

    private Optional<Resolved> resolve(String raw, String expectedType, Budget budget) {
        for (String query : queryVariants(raw)) {
            if (budget.expired()) {
                break;
            }
            Optional<Resolved> hit = resolveOne(query, expectedType, budget);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /**
     * 把输入拆成若干可尝试的形式。
     *
     * <p>用户经常直接粘文件名：{@code [自定义局域网联机] lanserverproperties-1.11.1-forge.jar}，
     * 需要依次去掉中文前缀、{@code .jar}、版本与加载器后缀，才可能对上 Slug。
     */
    static List<String> queryVariants(String raw) {
        Set<String> variants = new LinkedHashSet<>();
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            return List.of();
        }
        variants.add(query);
        String noJar = query.replaceAll("(?i)\\.jar$", "").trim();
        variants.add(noJar);
        String noBracket = noJar.replaceAll("^\\s*\\[[^\\]]{0,40}\\]\\s*", "").trim();
        variants.add(noBracket);
        String base = noBracket.replaceAll("(?i)[-_]v?\\d[\\w.+-]*$", "").trim();
        variants.add(base);
        String baseNoLoader = base.replaceAll("(?i)[-_](forge|fabric|neoforge|quilt|liteloader)$", "").trim();
        variants.add(baseNoLoader);
        variants.removeIf(item -> item.length() < 2);
        return new ArrayList<>(variants);
    }

    private Optional<Resolved> resolveOne(String query, String expectedType, Budget budget) {
        if (query.length() < 2) {
            return Optional.empty();
        }

        // 1) 纯数字 → 项目 ID
        if (query.matches("\\d{2,}")) {
            Optional<Resolved> byId = fetch(query, "id", expectedType, budget);
            if (byId.isPresent()) {
                return byId;
            }
        }

        // 2) 直接贴 CurseForge 链接
        Matcher url = URL_PATTERN.matcher(query);
        if (url.find()) {
            Optional<Resolved> direct = fetchBySlug(url.group(2), expectedType, budget);
            if (direct.isPresent()) {
                return direct;
            }
        }

        // 3) 输入本身像 Slug
        for (String slug : slugCandidates(query)) {
            if (budget.expired()) {
                return Optional.empty();
            }
            Optional<Resolved> hit = fetchBySlug(slug, expectedType, budget);
            if (hit.isPresent()) {
                return hit;
            }
        }

        // 4) 中文名 / 紧凑文件名 → MC百科反解英文名
        for (McmodHit hit : mcmodSearch(query)) {
            if (budget.expired()) {
                break;
            }
            if (!looksRelevant(query, hit)) {
                continue;
            }
            for (String alias : hit.aliases()) {
                for (String slug : slugCandidates(alias)) {
                    if (budget.expired()) {
                        return Optional.empty();
                    }
                    Optional<Resolved> resolved = fetchBySlug(slug, expectedType, budget);
                    if (resolved.isPresent()) {
                        log.info("CurseForge 免 Key 直查：{} → {}（slug {}）", query, resolved.get().title(), slug);
                        return resolved;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 按关键词批量直查（搜索结果用）。
     *
     * <p>命中数量有限：直接命中 1 个，或者按 MC百科前几条结果反解出若干项目。
     */
    public List<Resolved> search(String raw, String expectedType, String gameVersion, String loader, int limit) {
        List<Resolved> result = new ArrayList<>();
        String query = raw == null ? "" : raw.trim();
        if (query.isBlank() || limit <= 0) {
            return result;
        }
        // 整个搜索有预算上限：cfwidget 404 很便宜，但组合爆炸时必须及时收手
        Budget budget = new Budget(25, 8_000);

        Optional<Resolved> direct = resolve(query, expectedType, budget);
        if (direct.isPresent()) {
            result.add(direct.get());
        }

        Set<String> seen = new LinkedHashSet<>();
        result.forEach(item -> seen.add(item.id()));

        Set<String> seenTitles = new LinkedHashSet<>();
        List<McmodHit> mcmodHits = new ArrayList<>();
        for (String variant : queryVariants(query)) {
            for (McmodHit hit : mcmodSearch(variant)) {
                if (seenTitles.add(hit.title()) && mcmodHits.size() < 4) {
                    mcmodHits.add(hit);
                }
            }
        }

        for (McmodHit hit : mcmodHits) {
            if (result.size() >= limit || budget.expired()) {
                break;
            }
            if (!looksRelevant(query, hit)) {
                continue;
            }
            boolean hitResolved = false;
            for (String alias : hit.aliases()) {
                if (hitResolved) {
                    break;
                }
                for (String slug : slugCandidates(alias)) {
                    if (budget.expired()) {
                        break;
                    }
                    Optional<Resolved> resolved = fetchBySlug(slug, expectedType, budget);
                    if (resolved.isPresent()) {
                        if (seen.add(resolved.get().id())) {
                            result.add(resolved.get());
                        }
                        hitResolved = true;
                        break;
                    }
                }
            }
        }

        // 有指定游戏版本 / 加载器时，只保留确实有对应文件的条目
        List<Resolved> filtered = new ArrayList<>();
        for (Resolved item : result) {
            if (matchesAnyFile(item.project(), gameVersion, loader)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /** 按项目 ID 取详情（详情页用）。 */
    public Optional<Resolved> byId(String id) {
        return fetch(id, "id", null, new Budget(4, 20_000));
    }

    // ------------------------------------------------------------ cfwidget

    private Optional<Resolved> fetchBySlug(String slug, String expectedType, Budget budget) {
        if (slug == null || slug.length() < 2) {
            return Optional.empty();
        }
        for (String prefix : prefixesFor(expectedType)) {
            if (budget.expired()) {
                return Optional.empty();
            }
            Optional<Resolved> hit = fetch(prefix + "/" + slug, "path", expectedType, budget);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** 已知资源类型时只查对应的分类路径，省掉 4 次必然 404 的请求。 */
    private static List<String> prefixesFor(String expectedType) {
        String kind = expectedType == null ? "" : expectedType.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "mod" -> List.of("minecraft/mc-mods");
            case "modpack" -> List.of("minecraft/modpacks");
            case "resourcepack" -> List.of("minecraft/texture-packs");
            case "shader" -> List.of("minecraft/shaders");
            case "datapack" -> List.of("minecraft/worlds");
            default -> PREFIXES;
        };
    }

    /**
     * 请求 cfwidget。
     *
     * @param key  项目 ID 或 {@code 前缀/slug}
     * @param mode {@code id} 或 {@code path}
     */
    private Optional<Resolved> fetch(String key, String mode, String expectedType, Budget budget) {
        String cacheKey = ("id".equals(mode) ? "id:" : "path:") + key + "|" + (expectedType == null ? "" : expectedType);
        Optional<Resolved> cached = pathCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (!budget.spend()) {
            return Optional.empty();
        }
        Optional<Resolved> resolved = doFetch(key, mode, expectedType);
        if (pathCache.size() < 600) {
            pathCache.put(cacheKey, resolved);
        }
        return resolved;
    }

    private Optional<Resolved> doFetch(String key, String mode, String expectedType) {
        String url = CFWIDGET + "/" + key;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", BROWSER_UA)
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 202) {
                    // 冷项目进队列，等一会儿再取
                    Thread.sleep(1200);
                    continue;
                }
                if (status == 404) {
                    return Optional.empty();
                }
                if (status / 100 != 2) {
                    log.debug("cfwidget 返回 {}：{}", status, url);
                    return Optional.empty();
                }
                JsonNode node = mapper.readTree(response.body());
                if (node.path("id").asLong(0) == 0) {
                    return Optional.empty();
                }
                if (!typeMatches(node, expectedType)) {
                    return Optional.empty();
                }
                return Optional.of(new Resolved(node, slugOf(node, key)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception ex) {
                log.debug("cfwidget 请求失败 {}：{}", url, ex.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String slugOf(JsonNode node, String key) {
        String cfUrl = node.path("urls").path("curseforge").asText("");
        if (cfUrl.contains("/")) {
            String tail = cfUrl.substring(cfUrl.lastIndexOf('/') + 1);
            if (!tail.isBlank()) {
                return tail;
            }
        }
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /** cfwidget 的 type（Mods / Modpacks / Resource Packs / Shaders / Worlds）与资源类型是否一致。 */
    private static boolean typeMatches(JsonNode node, String expectedType) {
        String kind = expectedType == null ? "" : expectedType.toLowerCase(Locale.ROOT);
        if (kind.isBlank()) {
            return true;
        }
        String type = node.path("type").asText("").toLowerCase(Locale.ROOT);
        if (type.isBlank()) {
            return true;
        }
        return switch (kind) {
            case "mod" -> type.contains("mod") && !type.contains("modpack");
            case "modpack" -> type.contains("modpack");
            case "resourcepack" -> type.contains("resource");
            case "shader" -> type.contains("shader");
            case "datapack" -> type.contains("world") || type.contains("data");
            default -> true;
        };
    }

    private static boolean matchesAnyFile(JsonNode project, String gameVersion, String loader) {
        boolean checkVersion = gameVersion != null && !gameVersion.isBlank();
        boolean checkLoader = loader != null && !loader.isBlank();
        if (!checkVersion && !checkLoader) {
            return true;
        }
        for (JsonNode file : project.path("files")) {
            if (fileMatches(file, gameVersion, loader)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fileMatches(JsonNode file, String gameVersion, String loader) {
        boolean checkVersion = gameVersion != null && !gameVersion.isBlank();
        boolean checkLoader = loader != null && !loader.isBlank();
        boolean versionOk = !checkVersion;
        boolean loaderOk = !checkLoader;
        for (JsonNode item : file.path("versions")) {
            String value = item.asText("");
            if (checkVersion && value.equalsIgnoreCase(gameVersion)) {
                versionOk = true;
            }
            if (checkLoader && value.equalsIgnoreCase(loader)) {
                loaderOk = true;
            }
        }
        return versionOk && loaderOk;
    }

    // ------------------------------------------------------------ MC百科

    /** 用 MC百科搜索把中文名 / 紧凑文件名换成候选英文名。 */
    List<McmodHit> mcmodSearch(String query) {
        String key = query.trim().toLowerCase(Locale.ROOT);
        if (key.length() < 2) {
            return List.of();
        }
        List<McmodHit> cached = mcmodCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<McmodHit> hits = new ArrayList<>();
        try {
            String url = MCMOD_SEARCH + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + "&filter=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.debug("MC百科搜索返回 {}：{}", response.statusCode(), url);
                return List.of();
            }
            Matcher matcher = MCMOD_ITEM.matcher(response.body());
            while (matcher.find() && hits.size() < 6) {
                String classId = matcher.group(1);
                String title = stripTags(matcher.group(2));
                List<String> aliases = aliasesOf(title);
                if (!aliases.isEmpty()) {
                    hits.add(new McmodHit(classId, title, aliases));
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception ex) {
            log.debug("MC百科搜索失败（{}）：{}", query, ex.getMessage());
            return List.of();
        }
        if (mcmodCache.size() < 300) {
            mcmodCache.put(key, hits);
        }
        return hits;
    }

    /**
     * 从标题里抽英文名候选：{@code [LSP] 自定义局域网联机 (Lan Server Properties)}
     * → {@code ["Lan Server Properties", "LSP"]}。
     */
    static List<String> aliasesOf(String title) {
        Set<String> aliases = new LinkedHashSet<>();
        Matcher bracket = Pattern.compile("^\\s*\\[([^\\]]{2,20})\\]").matcher(title);
        if (bracket.find() && !CHINESE.matcher(bracket.group(1)).find()) {
            aliases.add(bracket.group(1).trim());
        }
        Matcher paren = Pattern.compile("\\(([^)]{2,80})\\)").matcher(title);
        while (paren.find()) {
            String value = paren.group(1).trim();
            if (!CHINESE.matcher(value).find() && value.matches(".*[A-Za-z].*")) {
                aliases.add(value);
            }
        }
        // 标题里直接写的英文名（不带括号），例如「[TD] 暮色森林 Twilight Forest」
        String english = title.replaceAll("[\\u4e00-\\u9fa5]", " ").replaceAll("[\\[\\]()]", " ")
                .replaceAll("\\s+", " ").trim();
        if (english.length() >= 3 && english.matches(".*[A-Za-z]{3}.*")) {
            aliases.add(english);
        }
        return new ArrayList<>(aliases);
    }

    /** 粗略判断 MC百科结果是否与查询相关，避免把不相干条目解析成 CurseForge 项目。 */
    private static boolean looksRelevant(String query, McmodHit hit) {
        String q = query.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (q.length() < 4) {
            return true;
        }
        String title = hit.title().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        if (title.contains(q)) {
            return true;
        }
        for (String alias : hit.aliases()) {
            String a = alias.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
            if (a.contains(q) || q.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").trim();
    }

    // ------------------------------------------------------------ Slug 候选

    /** 名称 → 可能的 CurseForge Slug（英文名多是空格转连字符）。 */
    static List<String> slugCandidates(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return List.of();
        }
        value = value.replaceAll("[\u2018\u2019\u201c\u201d]", "")
                .replace("&", "and")
                .replaceAll("[^a-z0-9\\s_-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.isEmpty()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(value.replace(' ', '-'));
        candidates.add(value.replace(" ", ""));
        candidates.add(value.replace(' ', '_'));
        candidates.add(value.replaceAll("[\\s_-]", ""));
        List<String> result = new ArrayList<>();
        for (String item : candidates) {
            if (item.length() >= 2) {
                result.add(item);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 详情映射

    /** cfwidget 的 type 字段 → 项目类型（与前端 type 参数一致）。 */
    public static String projectType(JsonNode project) {
        String type = project.path("type").asText("").toLowerCase(Locale.ROOT);
        if (type.contains("modpack")) {
            return "modpack";
        }
        if (type.contains("resource")) {
            return "resourcepack";
        }
        if (type.contains("shader")) {
            return "shader";
        }
        if (type.contains("world")) {
            return "datapack";
        }
        return "mod";
    }

    /** 免 Key 详情：项目信息 + 按游戏版本 / 加载器过滤后的文件列表。 */
    public CommunityService.ProjectDetail toDetail(JsonNode project, String projectType,
                                                   String gameVersion, String loader) {
        String author = project.path("members").isArray() && project.path("members").size() > 0
                ? project.path("members").get(0).path("username").asText("") : "";
        List<CommunityService.VersionEntry> entries = versionEntries(project, gameVersion, loader);
        String note = "CurseForge 免 Key 直查（数据来自 cfwidget）";
        if (entries.isEmpty()) {
            // cfwidget 对少数项目（例如部分资源包）不收录文件列表
            note = project.path("files").isArray() && project.path("files").size() == 0
                    ? "cfwidget 未收录该项目的文件列表；在设置页填入 CurseForge API Key 可获取完整版本，"
                    + "或直接到 CurseForge 项目页下载"
                    : "没有匹配当前游戏版本 / 加载器的文件，试试把筛选改成「全部」";
        }
        return new CommunityService.ProjectDetail(project.path("id").asText(),
                slugOf(project, project.path("id").asText()), project.path("title").asText(""),
                project.path("summary").asText(""),
                project.path("description").asText(project.path("summary").asText("")),
                project.path("thumbnail").asText(""), "curseforge", projectType, author,
                project.path("downloads").path("total").asLong(), versionsOf(project), loadersOf(project),
                toStringList(project.path("categories")), entries, note);
    }

    /** 项目内符合条件的文件，按上传时间倒序；每条都带可直接下载的 forgecdn 地址。 */
    public List<CommunityService.VersionEntry> versionEntries(JsonNode project, String gameVersion,
                                                              String loader) {
        List<JsonNode> files = new ArrayList<>();
        for (JsonNode file : project.path("files")) {
            if (fileMatches(file, gameVersion, loader)) {
                files.add(file);
            }
        }
        files.sort((left, right) -> right.path("uploaded_at").asText("")
                .compareTo(left.path("uploaded_at").asText("")));
        List<CommunityService.VersionEntry> entries = new ArrayList<>();
        for (JsonNode file : files) {
            long id = file.path("id").asLong();
            String name = file.path("name").asText("");
            entries.add(new CommunityService.VersionEntry(String.valueOf(id),
                    file.path("display").asText(name), file.path("display").asText(name),
                    majorVersions(file), loaderNames(file), file.path("uploaded_at").asText(""),
                    file.path("downloads").asLong(), name, forgeCdnUrl(id, name),
                    file.path("filesize").asLong(), entries.isEmpty()));
        }
        return entries;
    }

    /** 从项目文件列表里找出指定 fileId 的文件名（下载时拼直链用）。 */
    public Optional<JsonNode> file(JsonNode project, String fileId) {
        for (JsonNode file : project.path("files")) {
            if (String.valueOf(file.path("id").asLong()).equals(fileId)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    public static String forgeCdnUrl(long fileId, String fileName) {
        return FORGECDN + (fileId / 1000) + "/" + (fileId % 1000) + "/"
                + fileName.replace(" ", "%20");
    }

    private static List<String> majorVersions(JsonNode file) {
        List<String> versions = new ArrayList<>();
        for (JsonNode item : file.path("versions")) {
            String value = item.asText("");
            if (MC_VERSION.matcher(value).matches()) {
                versions.add(value);
            }
        }
        return versions;
    }

    private static List<String> loaderNames(JsonNode file) {
        List<String> loaders = new ArrayList<>();
        for (JsonNode item : file.path("versions")) {
            String value = item.asText("").toLowerCase(Locale.ROOT);
            if (LOADERS.contains(value)) {
                loaders.add(value);
            }
        }
        return loaders;
    }

    private static List<String> versionsOf(JsonNode project) {
        Set<String> versions = new LinkedHashSet<>();
        for (JsonNode file : project.path("files")) {
            versions.addAll(majorVersions(file));
        }
        return new ArrayList<>(versions);
    }

    private static List<String> loadersOf(JsonNode project) {
        Set<String> loaders = new LinkedHashSet<>();
        for (JsonNode file : project.path("files")) {
            loaders.addAll(loaderNames(file));
        }
        return new ArrayList<>(loaders);
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.isObject() ? item.path("name").asText("") : item.asText("");
                if (!value.isBlank()) {
                    list.add(value);
                }
            }
        }
        return list;
    }
}
