package com.meloong.pcl.version;

import java.nio.file.Path;

/**
 * 解析完成的支持库条目，对应原工程 <c>McLibEntry</c>。
 *
 * @param originalName 原始 Maven 坐标
 * @param url          下载地址（可能为 null，由下载模块按镜像规则展开）
 * @param localPath    本地文件路径
 * @param size         文件大小，未知为 0
 * @param sha1         SHA1，未知为 null
 * @param natives      是否为 natives 包
 */
public record LibraryEntry(String originalName, String url, Path localPath, long size, String sha1, boolean natives) {
}
