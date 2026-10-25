package com.meloong.pcl.launch;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.download.DownloadManager;
import com.meloong.pcl.download.NetFile;
import com.meloong.pcl.download.SourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自动下载缺失的 Java 运行时（对应 PCL 的「Java 8 自动下载」行为）。
 *
 * <p>1.7.10～1.12.2 的 Forge 用 launchwrapper 启动，而 launchwrapper 只兼容 Java 8：
 * 它的构造器会把应用类加载器强转成 {@link java.net.URLClassLoader}，Java 9+ 的
 * AppClassLoader 不是 URLClassLoader，直接 {@code ClassCastException}。因此这类版本
 * 必须用 Java 8，Java 11 / 17 / 21 都不行。
 *
 * <p>本机没有 Java 8 时，从清华 Adoptium 镜像下载 Temurin JRE 8 到缓存目录，
 * 解压后登记进 Java 列表（持久化到设置，重启后直接可用）。
 */
@Service
public class JavaAutoDownloader {

    private static final Logger log = LoggerFactory.getLogger(JavaAutoDownloader.class);

    /** 清华 Adoptium 镜像的 JRE 8 目录页（从里面解析最新的 zip 文件名）。 */
    private static final String JRE8_LIST_URL =
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jre/x64/windows/";
    private static final Pattern JRE8_ZIP_PATTERN =
            Pattern.compile("OpenJDK8U-jre_x64_windows_hotspot_[0-9a-zA-Z_]+\\.zip");
    private static final String JRE8_ZIP_FALLBACK =
            "OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip";

    private final PclProperties properties;
    private final DownloadManager downloadManager;
    private final JavaLocator javaLocator;

    public JavaAutoDownloader(PclProperties properties, DownloadManager downloadManager,
                              JavaLocator javaLocator) {
        this.properties = properties;
        this.downloadManager = downloadManager;
        this.javaLocator = javaLocator;
    }

    /**
     * 确保存在可用的 Java 8：先找已安装的，再找之前自动下载的，最后联网下载。
     * 找不到 / 下载失败返回 null。
     */
    public synchronized JavaRuntime ensureJava8() {
        JavaRuntime existing = javaLocator.findExact(8);
        if (existing != null) {
            return existing;
        }
        JavaRuntime downloaded = findDownloaded();
        if (downloaded != null) {
            return downloaded;
        }
        return downloadAndInstall();
    }

    /** 自动下载目录：data/cache/java/temurin8-jre。 */
    private Path runtimeRoot() {
        return properties.dataDirPath().resolve("cache").resolve("java").resolve("temurin8-jre");
    }

    /** 在下载目录里找 java.exe 并登记（zip 解压后外面包着一层版本文件夹）。 */
    private JavaRuntime findDownloaded() {
        Path root = runtimeRoot();
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path bin = findJavaBin(root);
        if (bin == null) {
            return null;
        }
        return register(bin);
    }

    private JavaRuntime downloadAndInstall() {
        Path cacheJava = properties.dataDirPath().resolve("cache").resolve("java");
        Path zipPath = null;
        try {
            Files.createDirectories(cacheJava);
            String fileName = resolveLatestZipName();
            zipPath = cacheJava.resolve(fileName);
            log.info("本机没有 Java 8，开始自动下载（Temurin JRE，清华镜像）：{}", fileName);
            NetFile file = NetFile.of(JRE8_LIST_URL + fileName, zipPath, null, 0, "Temurin JRE 8",
                    SourceKind.META);
            downloadManager.download(file);

            Path root = runtimeRoot();
            unzip(zipPath, root);
            Path bin = findJavaBin(root);
            if (bin == null) {
                log.error("Java 8 解压完成但没有找到 java.exe：{}", root);
                return null;
            }
            JavaRuntime runtime = register(bin);
            if (runtime != null && runtime.major() == 8) {
                log.info("Java 8 自动下载完成：{}", runtime);
                return runtime;
            }
            return null;
        } catch (Exception ex) {
            log.error("自动下载 Java 8 失败：{}", ex.getMessage());
            return null;
        } finally {
            if (zipPath != null) {
                try {
                    Files.deleteIfExists(zipPath);
                } catch (IOException ignored) {
                    // 临时 zip 删不掉不影响结果
                }
            }
        }
    }

    /** 查镜像目录页里最新的 JRE 8 zip 文件名；解析失败用内置文件名兜底。 */
    private String resolveLatestZipName() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(JRE8_LIST_URL))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Matcher matcher = JRE8_ZIP_PATTERN.matcher(response.body());
                String latest = null;
                while (matcher.find()) {
                    latest = matcher.group();
                }
                if (latest != null) {
                    return latest;
                }
            }
            log.warn("JRE 8 镜像目录解析失败（HTTP {}），使用兜底文件名", response.statusCode());
        } catch (Exception ex) {
            log.warn("JRE 8 镜像目录获取失败（{}），使用兜底文件名", ex.getMessage());
        }
        return JRE8_ZIP_FALLBACK;
    }

    private JavaRuntime register(Path binFolder) {
        try {
            return javaLocator.register(binFolder.toString());
        } catch (Exception ex) {
            log.error("登记自动下载的 Java 8 失败（{}）：{}", binFolder, ex.getMessage());
            return null;
        }
    }

    /** 在解压目录里找 bin/java.exe（深度限制 3 层）。 */
    private static Path findJavaBin(Path root) {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        try {
            return Files.walk(root, 3)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(exe))
                    .filter(Files::isRegularFile)
                    .map(Path::getParent)
                    .min(Comparator.comparing(path -> path.toString().length()))
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 解压 zip 到目标目录（防路径穿越）。 */
    private static void unzip(Path zip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
