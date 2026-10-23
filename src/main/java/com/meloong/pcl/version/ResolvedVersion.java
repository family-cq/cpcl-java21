package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 合并 inheritsFrom 之后的完整版本描述，对应原工程 <c>McInstance</c> 展开后的 JsonObject。
 */
public record ResolvedVersion(
        String id,
        String type,
        String mainClass,
        String assetsIndexName,
        String assetIndexUrl,
        String jarId,
        List<VersionJson.Library> libraries,
        List<JsonNode> jvmArgs,
        List<JsonNode> gameArgs,
        String minecraftArguments,
        VersionJson.DownloadInfo clientDownload,
        VersionJson.JavaVersion javaVersion,
        String releaseTime,
        McVersion mcVersion,
        JsonNode rawJson) {

    /** 需要的 Java 主版本号，未知时返回 -1。 */
    public int requiredJavaMajor() {
        return javaVersion == null || javaVersion.majorVersion() <= 0 ? -1 : javaVersion.majorVersion();
    }

    /** 是否为 1.13 之前的旧版参数格式。 */
    public boolean legacyArguments() {
        return (jvmArgs == null || jvmArgs.isEmpty())
                && (gameArgs == null || gameArgs.isEmpty())
                && minecraftArguments != null && !minecraftArguments.isBlank();
    }
}
