package com.meloong.pcl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.LibraryEntry;
import com.meloong.pcl.version.LibraryResolver;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.RuleEvaluator;
import com.meloong.pcl.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本清单与安装，移植自 <c>ModDownload.DlVersionList*</c> 与 <c>McDownloadClientUpdate</c> 的下载部分。
 */
@Service
public class VersionManifestService {

    private static final Logger log = LoggerFactory.getLogger(VersionManifestService.class);
    private static final int CACHE_MINUTES = 5;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final PclProperties properties;
    private final DownloadSources sources;
    private final DownloadManager downloadManager;
    private final VersionResolver versionResolver;
    private final LibraryResolver libraryResolver;

    private volatile JsonNode manifest;
    private volatile Instant fetchedAt = Instant.EPOCH;

    public VersionManifestService(RestTemplate restTemplate, ObjectMapper mapper, PclProperties properties,
                                  DownloadSources sources, DownloadManager downloadManager,
                                  VersionResolver versionResolver, LibraryResolver libraryResolver) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.properties = properties;
        this.sources = sources;
        this.downloadManager = downloadManager;
        this.versionResolver = versionResolver;
        this.libraryResolver = libraryResolver;
    }

    /** 获取版本清单（带 5 分钟缓存）。 */
    public synchronized JsonNode manifest() {
        if (manifest != null && fetchedAt.plusSeconds(CACHE_MINUTES * 60L).isAfter(Instant.now())) {
            return manifest;
        }
        IOException last = null;
        for (String url : sources.versionManifest()) {
            try {
                String body = restTemplate.getForObject(url, String.class);
                if (body != null && !body.isBlank()) {
                    manifest = mapper.readTree(body);
                    fetchedAt = Instant.now();
                    log.info("版本清单已更新，共 {} 个版本（来源：{}）",
                            manifest.path("versions").size(), url);
                    return manifest;
                }
            } catch (Exception ex) {
                last = new IOException("获取版本清单失败：" + url + "，" + ex.getMessage(), ex);
                log.debug(last.getMessage());
            }
        }
        if (manifest != null) {
            log.warn("版本清单刷新失败，继续使用缓存");
            return manifest;
        }
        throw new IllegalStateException("无法获取 Minecraft 版本清单", last);
    }

    /** 版本清单中的条目。 */
    public record ManifestVersion(String id, String type, String url, String releaseTime) {
    }

    public List<ManifestVersion> versions() {
        JsonNode versions = manifest().path("versions");
        List<ManifestVersion> result = new ArrayList<>(versions.size());
        for (JsonNode node : versions) {
            result.add(new ManifestVersion(node.path("id").asText(), node.path("type").asText(),
                    node.path("url").asText(), node.path("releaseTime").asText()));
        }
        return result;
    }

    /** 最新正式版 / 快照。 */
    public Map<String, String> latest() {
        JsonNode latest = manifest().path("latest");
        Map<String, String> result = new HashMap<>();
        result.put("release", latest.path("release").asText());
        result.put("snapshot", latest.path("snapshot").asText());
        return result;
    }

    /**
     * 安装指定版本：版本 JSON、客户端 jar、支持库，可选资源文件。
     */
    public InstallReport install(String id, boolean withAssets) throws IOException {
        return install(id, withAssets, null);
    }

    /** 安装指定版本，并上报进度（可为 null）。 */
    public InstallReport install(String id, boolean withAssets, InstallJobService.Progress progress)
            throws IOException {
        ensureVersionJson(id);
        return complete(id, withAssets, progress);
    }

    /**
     * 只下载原版版本 JSON（不碰 jar / 库 / 资源）。
     *
     * <p>整合包安装流程把它拆成独立一步，界面上才好对应「下载原版 JSON 文件」。
     *
     * @return 落盘后的 JSON 路径
     */
    public Path ensureVersionJson(String id) throws IOException {
        ManifestVersion target = versions().stream()
                .filter(version -> version.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("版本清单中不存在：" + id));

        Path versionDir = properties.versionsPath().resolve(id);
        Files.createDirectories(versionDir);
        Path jsonPath = versionDir.resolve(id + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            downloadManager.downloadAll(List.of(
                    NetFile.of(target.url(), jsonPath, null, 0, "版本 JSON " + id, SourceKind.META)));
        }
        if (!Files.isRegularFile(jsonPath)) {
            throw new IllegalStateException("下载版本 JSON 失败：" + id);
        }
        return jsonPath;
    }

    /**
     * 只补齐客户端 jar 与支持库（不下载资源文件）。
     *
     * <p>对应「下载原版支持库文件」：客户端 jar 也在这里落盘，后面的「安装游戏」
     * 阶段要靠它复制进整合包独立实例。
     */
    public InstallReport ensureLibraries(String id, InstallJobService.Progress progress) throws IOException {
        ResolvedVersion version = versionResolver.resolve(id);
        List<NetFile> files = new ArrayList<>();

        if (version.clientDownload() != null && version.clientDownload().url() != null) {
            Path jarPath = properties.versionsPath().resolve(version.jarId()).resolve(version.jarId() + ".jar");
            files.add(NetFile.of(version.clientDownload().url(), jarPath, version.clientDownload().sha1(),
                    version.clientDownload().size(), "客户端 jar " + version.jarId(), SourceKind.META));
        }
        for (LibraryEntry library : libraryResolver.resolve(version, false, RuleEvaluator.Environment.current())) {
            if (library.url() == null || library.url().isBlank()) {
                continue;
            }
            files.add(NetFile.of(library.url(), library.localPath(), library.sha1(), library.size(),
                    "支持库 " + library.originalName(), SourceKind.LIBRARY));
        }

        DownloadManager.DownloadSummary summary = downloadManager.downloadAll(files,
                progress == null ? null : (done, total) -> {
                    int percent = total <= 0 ? 0 : (int) (100.0 * done / total);
                    progress.update(percent, "支持库 " + done + "/" + total);
                });
        log.info("版本 {} 支持库就绪：共 {}，下载 {}，跳过 {}，失败 {}", id,
                summary.total(), summary.downloaded(), summary.skipped(), summary.failed());
        return new InstallReport(id, summary.total(), summary.downloaded(), summary.skipped(),
                summary.failed(), 0, summary.errors());
    }

    /**
     * 用给定的版本 JSON 完成安装：写盘后下载客户端 jar、支持库与资源索引。
     * 供加载器安装（Fabric / Forge / OptiFine 等）复用。
     */
    public InstallReport installFromJson(String id, String json, boolean withAssets) throws IOException {
        Path versionDir = properties.versionsPath().resolve(id);
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(id + ".json"), json);
        log.info("已写入版本 JSON：{}", id);
        return complete(id, withAssets);
    }

    /** 按已存在的版本 JSON 补齐文件。 */
    public InstallReport complete(String id, boolean withAssets) throws IOException {
        return complete(id, withAssets, null);
    }

    /** 按已存在的版本 JSON 补齐文件，并上报进度（可为 null）。 */
    public InstallReport complete(String id, boolean withAssets, InstallJobService.Progress progress)
            throws IOException {
        List<NetFile> files = new ArrayList<>();
        ResolvedVersion version = versionResolver.resolve(id);

        // 客户端 jar
        if (version.clientDownload() != null && version.clientDownload().url() != null) {
            Path jarPath = properties.versionsPath().resolve(version.jarId()).resolve(version.jarId() + ".jar");
            files.add(NetFile.of(version.clientDownload().url(), jarPath, version.clientDownload().sha1(),
                    version.clientDownload().size(), "客户端 jar " + version.jarId(), SourceKind.META));
        }

        // 支持库
        for (LibraryEntry library : libraryResolver.resolve(version, false, RuleEvaluator.Environment.current())) {
            if (library.url() == null || library.url().isBlank()) {
                continue;
            }
            files.add(NetFile.of(library.url(), library.localPath(), library.sha1(), library.size(),
                    "支持库 " + library.originalName(), SourceKind.LIBRARY));
        }

        // 资源索引
        if (version.assetIndexUrl() != null && !version.assetIndexUrl().isBlank()) {
            Path indexPath = properties.assetsPath().resolve("indexes").resolve(version.assetsIndexName() + ".json");
            files.add(NetFile.of(version.assetIndexUrl(), indexPath, null, 0,
                    "资源索引 " + version.assetsIndexName(), SourceKind.META));
        }

        DownloadManager.DownloadSummary summary = downloadManager.downloadAll(files);
        int assetCount = 0;
        if (withAssets && version.assetIndexUrl() != null) {
            if (progress != null) {
                progress.update(55, "准备下载资源文件…");
            }
            assetCount = downloadAssets(version.assetsIndexName(), progress == null ? null
                    : (done, total) -> {
                        int percent = total <= 0 ? 55 : 55 + (int) (37.0 * done / total);
                        progress.update(percent, "下载资源文件 " + done + "/" + total);
                    });
        }
        log.info("版本 {} 安装完成：下载 {}，跳过 {}，失败 {}", id,
                summary.downloaded(), summary.skipped(), summary.failed());
        return new InstallReport(id, summary.total(), summary.downloaded(), summary.skipped(),
                summary.failed(), assetCount, summary.errors());
    }

    /**
     * 确保某个版本的资源文件（assets/objects）已下载，缺失则补上。
     *
     * <p>启动器在安装版本时可以跳过资源（{@code withAssets=false}）以加快速度，
     * 但缺资源的游戏会满屏丢贴图，因此装加载器/启动前都调这个补一遍。
     *
     * @return 本次新下载的文件数
     */
    public int ensureAssets(String versionId) throws IOException {
        return ensureAssets(versionId, null);
    }

    /** 带进度的资源补全，供「下载原版资源文件」阶段使用。 */
    public int ensureAssets(String versionId, InstallJobService.Progress progress) throws IOException {
        ResolvedVersion version = versionResolver.resolve(versionId);
        String indexName = version.assetsIndexName();
        if (indexName == null || indexName.isBlank()) {
            return 0;
        }
        Path indexPath = properties.assetsPath().resolve("indexes").resolve(indexName + ".json");
        if (!Files.isRegularFile(indexPath) && version.assetIndexUrl() != null
                && !version.assetIndexUrl().isBlank()) {
            if (progress != null) {
                progress.update(2, "下载资源索引 " + indexName + "…");
            }
            downloadManager.downloadAll(List.of(NetFile.of(version.assetIndexUrl(), indexPath, null, 0,
                    "资源索引 " + indexName, SourceKind.META)));
        }
        if (!Files.isRegularFile(indexPath)) {
            log.warn("资源索引仍不存在，跳过资源下载：{}", indexPath);
            return 0;
        }
        return downloadAssets(indexName, progress == null ? null : (done, total) -> {
            int percent = total <= 0 ? 50 : (int) (100.0 * done / total);
            progress.update(percent, "资源文件 " + done + "/" + total);
        });
    }

    /** 下载资源文件（objects），返回新下载的文件数。 */
    public int downloadAssets(String indexName) throws IOException {
        return downloadAssets(indexName, null);
    }

    /** 下载资源文件（objects），可上报进度。 */
    public int downloadAssets(String indexName,
                              java.util.function.BiConsumer<Integer, Integer> onProgress) throws IOException {
        Path indexPath = properties.assetsPath().resolve("indexes").resolve(indexName + ".json");
        if (!Files.isRegularFile(indexPath)) {
            log.warn("资源索引不存在，跳过资源下载：{}", indexPath);
            return 0;
        }
        JsonNode index = mapper.readTree(Files.readString(indexPath));
        JsonNode objects = index.path("objects");
        List<NetFile> files = new ArrayList<>(objects.size());
        for (var it = objects.fields(); it.hasNext(); ) {
            var entry = it.next();
            String hash = entry.getValue().path("hash").asText();
            long size = entry.getValue().path("size").asLong();
            if (hash.length() < 2) {
                continue;
            }
            String prefix = hash.substring(0, 2);
            Path local = properties.assetsPath().resolve("objects").resolve(prefix).resolve(hash);
            String url = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
            files.add(NetFile.of(url, local, hash, size, "资源 " + entry.getKey(), SourceKind.ASSETS));
        }
        DownloadManager.DownloadSummary summary = downloadManager.downloadAll(files, onProgress);
        if (summary.failed() > 0) {
            log.warn("资源文件有 {} 个下载失败", summary.failed());
        }
        return summary.downloaded();
    }

    /** 安装结果。 */
    public record InstallReport(String id, int total, int downloaded, int skipped, int failed,
                                int assets, List<String> errors) {
    }
}
