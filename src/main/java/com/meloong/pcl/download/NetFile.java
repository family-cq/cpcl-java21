package com.meloong.pcl.download;

import java.nio.file.Path;

/**
 * 一个待下载文件，对应原工程 <c>NetFile</c>。
 *
 * @param url         原始下载地址
 * @param localPath   本地保存路径
 * @param sha1        预期 SHA1，未知为 null
 * @param size        预期大小，未知为 0
 * @param description 描述，用于日志
 * @param kind        类型，决定镜像替换策略
 */
public record NetFile(String url, Path localPath, String sha1, long size, String description, SourceKind kind) {

    public static NetFile of(String url, Path localPath, String sha1, long size, String description, SourceKind kind) {
        return new NetFile(url, localPath, sha1, size, description, kind);
    }

    public NetFile withLocalPath(Path path) {
        return new NetFile(url, path, sha1, size, description, kind);
    }
}
