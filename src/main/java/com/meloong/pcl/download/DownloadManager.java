package com.meloong.pcl.download;

import com.meloong.pcl.config.PclProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 并发下载，移植自 <c>ModNet</c> / <c>ModDownload</c> 的下载调度部分。
 *
 * <p>保留的行为：多线程下载、按镜像顺序重试、SHA1 校验、已存在且校验通过则跳过。
 * 原实现还有分片续传、限速与下载源自动禁用等复杂逻辑，此处做了简化。
 */
@Service
public class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Java JSSE 的 TLS 指纹会被 cdn.modrinth.com 的 Cloudflare 防护限速
     * （实测单文件 38 KB/s，而 Windows 自带 curl 走 Schannel 同链路 2 MB/s），
     * 因此下载优先用系统 curl.exe，不可用时回退 Java HttpClient。
     */
    private static final Path CURL = locateCurl();

    private final PclProperties properties;
    private final DownloadSources sources;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    /** 正在下载中的临时文件，采样线程按文件体积增量估算实时网速。 */
    private final Set<Path> activeTemps = ConcurrentHashMap.newKeySet();
    private volatile long speedBps = 0;

    public DownloadManager(PclProperties properties, DownloadSources sources) {
        this.properties = properties;
        this.sources = sources;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getDownloadThread()), runnable -> {
            Thread thread = new Thread(runnable, "pcl-download");
            thread.setDaemon(true);
            return thread;
        });
        if (CURL != null) {
            log.info("下载引擎：系统 curl（{}），规避 Java TLS 指纹被 CDN 限速", CURL);
        } else {
            log.info("下载引擎：Java HttpClient（未找到系统 curl）");
        }
        Thread sampler = new Thread(this::sampleSpeed, "pcl-speed");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** 定位系统自带的 curl.exe（Windows 10 1803+ 内置；Linux/macOS 通常也自带）。 */
    private static Path locateCurl() {
        for (String candidate : List.of(
                "C:\\Windows\\System32\\curl.exe",
                "/usr/bin/curl", "/bin/curl", "/usr/local/bin/curl", "/opt/homebrew/bin/curl")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /** 每秒采样一次活动临时文件的体积增量，得到实时下载速度。 */
    private void sampleSpeed() {
        long lastTotal = -1;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                return;
            }
            if (activeTemps.isEmpty()) {
                speedBps = 0;
                lastTotal = -1;
                continue;
            }
            long total = 0;
            for (Path temp : activeTemps) {
                try {
                    total += Files.size(temp);
                } catch (IOException ignored) {
                    // 文件刚好被移走，下一秒自然恢复
                }
            }
            if (lastTotal >= 0 && total >= lastTotal) {
                speedBps = total - lastTotal;
            }
            lastTotal = total;
        }
    }

    /** 当前下载速度（字节/秒），无下载时为 0。 */
    public long currentSpeedBps() {
        return speedBps;
    }

    /** 速度的人类可读格式，如 1.4 MB/s。 */
    public static String formatSpeed(long bps) {
        if (bps <= 0) {
            return "0 B/s";
        }
        if (bps < 1024) {
            return bps + " B/s";
        }
        double kb = bps / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB/s", kb);
        }
        return String.format(Locale.ROOT, "%.2f MB/s", kb / 1024.0);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 批量下载。 */
    public DownloadSummary downloadAll(List<NetFile> files) {
        return downloadAll(files, null);
    }

    /**
     * 批量下载，可附带进度回调。
     *
     * @param onProgress 每完成若干个文件回调一次（已完成数, 待下载总数），可为 null
     */
    public DownloadSummary downloadAll(List<NetFile> files,
                                       java.util.function.BiConsumer<Integer, Integer> onProgress) {
        List<NetFile> pending = new ArrayList<>();
        int skipped = 0;
        for (NetFile file : files) {
            if (file.url() == null || file.url().isBlank()) {
                continue;
            }
            if (isUpToDate(file)) {
                skipped++;
            } else {
                pending.add(file);
            }
        }
        log.info("下载队列：共 {}，待下载 {}，已存在 {}，线程 {}", files.size(), pending.size(), skipped,
                properties.getDownloadThread());

        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (NetFile file : pending) {
            futures.add(executor.submit(() -> {
                try {
                    long size = download(file);
                    bytes.addAndGet(size);
                    int done = downloaded.incrementAndGet();
                    if (done % 50 == 0) {
                        log.info("下载进度：{}/{}", done, pending.size());
                    }
                    if (onProgress != null && (done % 20 == 0 || done == pending.size())) {
                        onProgress.accept(done, pending.size());
                    }
                } catch (Exception ex) {
                    failed.incrementAndGet();
                    errors.add(file.description() + " -> " + file.localPath() + "：" + ex.getMessage());
                    log.warn("下载失败（{}）：{}", file.description(), ex.getMessage());
                }
            }));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ex) {
                failed.incrementAndGet();
                errors.add(ex.getMessage());
            }
        }
        log.info("下载完成：成功 {}，跳过 {}，失败 {}，共 {} 字节", downloaded.get(), skipped, failed.get(), bytes.get());
        return new DownloadSummary(files.size(), downloaded.get(), skipped, failed.get(), bytes.get(), List.copyOf(errors));
    }

    /** 下载单个文件，返回写入字节数；已是最新返回 0。 */
    public long download(NetFile file) throws IOException, InterruptedException {
        if (isUpToDate(file)) {
            return 0;
        }
        List<String> urls = sources.resolve(file.kind(), file.url());
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            for (String url : urls) {
                try {
                    long size = fetch(url, file);
                    return size;
                } catch (Exception ex) {
                    last = ex;
                    log.debug("第 {} 次尝试失败（{}）：{}", attempt, url, ex.getMessage());
                }
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(200L * attempt);
            }
        }
        throw new IOException("多次重试后仍失败：" + (last == null ? "未知原因" : last.getMessage()), last);
    }

    private long fetch(String url, NetFile file) throws IOException, InterruptedException {
        Path target = file.localPath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".pcltmp");
        activeTemps.add(temp);
        try {
            if (CURL != null) {
                fetchByCurl(url, temp);
            } else {
                doFetch(url, temp);
            }
        } finally {
            activeTemps.remove(temp);
        }
        if (file.sha1() != null && !file.sha1().isBlank()) {
            String actual = sha1(temp);
            if (!file.sha1().equalsIgnoreCase(actual)) {
                Files.deleteIfExists(temp);
                throw new IOException("SHA1 校验失败：" + url);
            }
        }
        long size = Files.size(temp);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return size;
    }

    /**
     * 用系统 curl 下载：走 Schannel TLS，避开 Java 指纹被 CDN 限速的问题。
     * --speed-limit/--speed-time 同时充当停滞保护（60 秒内不足 1 KB/s 即断开）。
     */
    private void fetchByCurl(String url, Path temp) throws IOException, InterruptedException {
        Files.deleteIfExists(temp);
        ProcessBuilder builder = new ProcessBuilder(
                CURL.toString(),
                "--fail", "--silent", "--show-error", "--location",
                "--connect-timeout", "15",
                "--speed-time", "60", "--speed-limit", "1024",
                "--max-time", "600",
                "--output", temp.toString(),
                "--user-agent", properties.getLauncherName() + "/" + properties.getLauncherVersion(),
                safeUri(url).toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = process.waitFor();
        if (code != 0) {
            Files.deleteIfExists(temp);
            throw new IOException("curl 退出码 " + code + (output.isBlank() ? "" : "：" + output));
        }
        if (!Files.isRegularFile(temp)) {
            throw new IOException("curl 未产出文件：" + url);
        }
    }

    private void doFetch(String url, Path temp) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(safeUri(url))
                // 超时必须覆盖 body 接收：ofInputStream 只把超时用在响应头上，
                // 之后 Files.copy 读 body 阶段连接挂起会永久阻塞（前置补全实测卡死 20 分钟）
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", properties.getLauncherName() + "/" + properties.getLauncherVersion())
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            Files.deleteIfExists(temp);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp));
        } catch (IOException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(temp);
            throw new IOException("HTTP " + response.statusCode() + "：" + url);
        }
    }

    /**
     * 宽松的 URI 解析：整合包文件名常带空格/中文（如 {@code Apostle T-fix8.zip}），
     * CF 接口返回的 downloadUrl 是未编码原文，直接 URI.create 会抛
     * Illegal character in path。失败时用多参构造器按 RFC 2396 自动百分号编码。
     */
    private URI safeUri(String url) throws IOException {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ignored) {
            // 继续走重编码兜底
        }
        try {
            URI raw = URI.create(url.replace(" ", "%20"));
            return new URI(raw.getScheme(), raw.getAuthority(), raw.getPath(),
                    raw.getQuery(), raw.getFragment());
        } catch (Exception ex) {
            throw new IOException("无法解析下载地址：" + url, ex);
        }
    }

    /** 判断本地文件是否已满足要求（大小或 SHA1）。 */
    public boolean isUpToDate(NetFile file) {
        Path path = file.localPath();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            if (file.sha1() != null && !file.sha1().isBlank()) {
                return file.sha1().equalsIgnoreCase(sha1(path));
            }
            return file.size() > 0 && Files.size(path) == file.size();
        } catch (IOException ex) {
            return false;
        }
    }

    public static String sha1(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }

    /** 下载结果汇总。 */
    public record DownloadSummary(int total, int downloaded, int skipped, int failed, long bytes, List<String> errors) {
    }
}
