package com.meloong.pcl.version;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 版本 JSON 的映射模型，字段对应 Mojang 版本清单结构。
 *
 * <p>arguments 保留为 {@link JsonNode}，以便像原工程遍历 JToken 一样处理混合的字符串/对象参数。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VersionJson(
        String id,
        String type,
        String mainClass,
        String inheritsFrom,
        String jar,
        String assets,
        AssetIndex assetIndex,
        Map<String, DownloadInfo> downloads,
        JavaVersion javaVersion,
        List<Library> libraries,
        Arguments arguments,
        String minecraftArguments,
        String releaseTime,
        String time) {

    public VersionJson {
        libraries = libraries == null ? List.of() : libraries;
    }

    /** 资源索引，对应 assetIndex 字段。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssetIndex(String id, String sha1, long size, long totalSize, String url) {
    }

    /** 单个下载项，client / artifact / classifiers 共用。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DownloadInfo(String path, String sha1, long size, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LibraryDownloads(DownloadInfo artifact, Map<String, DownloadInfo> classifiers) {
    }

    /** 支持库条目。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Library(
            String name,
            String url,
            LibraryDownloads downloads,
            Map<String, String> natives,
            JsonNode rules,
            Extract extract) {
    }

    /** natives 解压排除规则。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Extract(List<String> exclude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JavaVersion(String component, int majorVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Arguments(List<JsonNode> game, List<JsonNode> jvm) {
        public Arguments {
            game = game == null ? List.of() : game;
            jvm = jvm == null ? List.of() : jvm;
        }
    }

    /** 客户端 jar 的下载信息，不存在时返回 null。 */
    public DownloadInfo client() {
        return downloads == null ? null : downloads.get("client");
    }
}
