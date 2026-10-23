package com.meloong.pcl.version;

import com.meloong.pcl.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 支持库解析，移植自 <c>ModMinecraft.McLibListGetWithJson</c>。
 *
 * <p>负责：rules 过滤、natives 按当前系统挑选 classifier、下载地址与本地路径生成。
 */
@Service
public class LibraryResolver {

    private static final Logger log = LoggerFactory.getLogger(LibraryResolver.class);

    private final RuleEvaluator ruleEvaluator;
    private final PclProperties properties;

    public LibraryResolver(RuleEvaluator ruleEvaluator, PclProperties properties) {
        this.ruleEvaluator = ruleEvaluator;
        this.properties = properties;
    }

    /**
     * 解析完整支持库列表。
     *
     * @param includeMainJar 是否在末尾追加客户端主 jar
     */
    public List<LibraryEntry> resolve(ResolvedVersion version, boolean includeMainJar) {
        return resolve(version, includeMainJar, RuleEvaluator.Environment.current());
    }

    public List<LibraryEntry> resolve(ResolvedVersion version, boolean includeMainJar,
                                      RuleEvaluator.Environment environment) {
        Path librariesRoot = properties.librariesPath();
        List<LibraryEntry> result = new ArrayList<>();
        String osName = environment.osName();

        for (VersionJson.Library library : version.libraries()) {
            if (library == null || library.name() == null) {
                continue;
            }
            // rules 过滤
            if (!ruleEvaluator.check(library.rules(), environment)) {
                continue;
            }
            MavenCoordinate coordinate;
            try {
                coordinate = MavenCoordinate.parse(library.name());
            } catch (IllegalArgumentException ex) {
                log.warn("跳过无法解析的支持库：{}", library.name());
                continue;
            }

            // 根节点 url 用于拼接（对应 PCL 中 Library("url") 的处理）
            String rootUrl = library.url();
            if (rootUrl != null && !rootUrl.endsWith("/")) {
                rootUrl = rootUrl + "/";
            }

            boolean hasNatives = library.natives() != null && !library.natives().isEmpty();
            if (!hasNatives) {
                Path localPath = librariesRoot.resolve(coordinate.toRelativePath());
                VersionJson.DownloadInfo artifact = library.downloads() == null ? null : library.downloads().artifact();
                String url;
                if (artifact != null && artifact.url() != null) {
                    url = artifact.url();
                } else if (rootUrl != null) {
                    url = rootUrl + coordinate.toRelativePath().replace('\\', '/');
                } else {
                    url = null;
                }
                Path path = artifact != null && artifact.path() != null
                        ? librariesRoot.resolve(artifact.path().replace('/', java.io.File.separatorChar))
                        : localPath;
                result.add(new LibraryEntry(library.name(), url, path,
                        artifact == null ? 0 : artifact.size(),
                        artifact == null ? null : artifact.sha1(), false));
            } else {
                // natives：按当前系统挑选 classifier
                String classifier = library.natives().get(osName);
                if (classifier == null) {
                    continue;
                }
                // 老版本 JSON 里 classifier 可能带 ${arch}
                if (classifier.contains("${arch}")) {
                    String arch = System.getProperty("os.arch", "amd64").contains("64") ? "64" : "32";
                    classifier = classifier.replace("${arch}", arch);
                }
                VersionJson.DownloadInfo nativeInfo = null;
                if (library.downloads() != null && library.downloads().classifiers() != null) {
                    nativeInfo = library.downloads().classifiers().get(classifier);
                }
                String relative;
                if (nativeInfo != null && nativeInfo.path() != null) {
                    relative = nativeInfo.path();
                } else {
                    MavenCoordinate nativeCoordinate = new MavenCoordinate(coordinate.group(), coordinate.artifact(),
                            coordinate.version(), classifier, "jar");
                    relative = nativeCoordinate.toRelativePath();
                }
                String url;
                if (nativeInfo != null && nativeInfo.url() != null) {
                    url = nativeInfo.url();
                } else if (rootUrl != null) {
                    url = rootUrl + relative.replace('\\', '/');
                } else {
                    url = null;
                }
                result.add(new LibraryEntry(library.name(), url,
                        librariesRoot.resolve(relative.replace('/', java.io.File.separatorChar)),
                        nativeInfo == null ? 0 : nativeInfo.size(),
                        nativeInfo == null ? null : nativeInfo.sha1(), true));
            }
        }

        // Forge / NeoForge 1.17+ 的安装器已经生成了打过补丁的客户端（net.minecraft:client:*-srg），
        // 由 FML 通过 -DlibraryDirectory 自己装成 minecraft 模块。此时若再把原版 jar 放进 -cp，
        // 模块层里会同时出现 minecraft 和以下划线开头的 _1._21._2，两者导出同一批包，
        // 直接报 “Modules minecraft and _1._21._2 export package ... to module mixin_synthetic”。
        // 原版 PCL 对 1.17+ 的 Forge 也不寻找 Inherit，走的就是这条路。
        if (includeMainJar && !isFmlBootstrap(version)) {
            Path mainJar = properties.versionsPath().resolve(version.jarId()).resolve(version.jarId() + ".jar");
            VersionJson.DownloadInfo client = version.clientDownload();
            result.add(new LibraryEntry("net.minecraft:client:" + version.jarId(), client == null ? null : client.url(),
                    mainJar, client == null ? 0 : client.size(), client == null ? null : client.sha1(), false));
        }
        return result;
    }

    /** 是否由 FML 的 BootstrapLauncher 启动（此时客户端 jar 由安装器提供的补丁版负责）。 */
    private static boolean isFmlBootstrap(ResolvedVersion version) {
        String mainClass = version.mainClass();
        return mainClass != null && mainClass.contains("bootstraplauncher");
    }
}
