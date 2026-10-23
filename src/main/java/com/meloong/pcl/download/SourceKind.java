package com.meloong.pcl.download;

/**
 * 下载文件类型，决定镜像替换策略（对应原工程 DlSourceAssetsGet / DlSourceLibraryGet / DlSourceLauncherOrMetaGet）。
 */
public enum SourceKind {
    /** 资源文件（assets）。 */
    ASSETS,
    /** 支持库（libraries）。 */
    LIBRARY,
    /** 启动器 / 版本元数据 JSON。 */
    META
}
