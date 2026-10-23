package com.meloong.pcl.launch;

import com.meloong.pcl.version.LibraryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Natives 解压，移植自 <c>ModLaunch.McLaunchNatives</c>。
 *
 * <p>行为：只解压当前平台的原生库文件（dll / so / dylib），大小相同则跳过，
 * 解压完成后删除目录中不属于本次列表的残留文件。
 */
@Component
public class NativeExtractor {

    private static final Logger log = LoggerFactory.getLogger(NativeExtractor.class);

    public int extract(List<LibraryEntry> libraries, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        Set<String> expected = new HashSet<>();
        int count = 0;

        for (LibraryEntry library : libraries) {
            if (!library.natives()) {
                continue;
            }
            Path jar = library.localPath();
            if (!Files.isRegularFile(jar)) {
                log.warn("natives 文件不存在，跳过：{}", jar);
                continue;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !isNativeFile(entry.getName())) {
                        continue;
                    }
                    // 对应原实现：META-INF 等排除项不参与解压
                    if (entry.getName().toUpperCase(Locale.ROOT).startsWith("META-INF/")) {
                        continue;
                    }
                    Path target = nativesDir.resolve(entry.getName());
                    Files.createDirectories(target.getParent());
                    expected.add(target.toAbsolutePath().normalize().toString());
                    if (Files.isRegularFile(target) && Files.size(target) == entry.getSize()) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            } catch (IOException ex) {
                log.warn("打开 natives 文件失败（{}）：{}", jar, ex.getMessage());
            }
        }

        // 删除多余文件
        deleteStale(nativesDir, expected);
        log.info("已解压 {} 个 natives 文件到 {}", count, nativesDir);
        return count;
    }

    private void deleteStale(Path nativesDir, Set<String> expected) {
        List<Path> stale = new ArrayList<>();
        try (var stream = Files.walk(nativesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !expected.contains(path.toAbsolutePath().normalize().toString()))
                    .forEach(stale::add);
        } catch (IOException ex) {
            log.warn("扫描 natives 目录失败：{}", ex.getMessage());
            return;
        }
        for (Path path : stale) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                log.debug("删除残留 natives 失败（{}）：{}", path, ex.getMessage());
            }
        }
    }

    private static boolean isNativeFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
    }
}
