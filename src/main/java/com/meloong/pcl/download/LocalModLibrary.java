package com.meloong.pcl.download;

import com.meloong.pcl.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本机已有 jar 的索引，用于「同一个 Mod 文件不重复下载」。
 *
 * <p>动机：国内到 cdn.modrinth.com / edge.forgecdn.net 的单连接速度只有几十 KB/s，
 * 一个 6MB 的 Mod 要一分多钟。而玩家机器上往往已经躺着十几个整合包、上千个 jar，
 * 其中相当一部分与目标文件完全一致（整合包互相搬运同一个版本很常见）。
 * 按文件名 + 大小命中后校验 SHA1 直接复制，比任何下载加速都彻底。
 *
 * <p>扫描范围（有界，只做目录遍历，不预读文件内容）：
 * <ol>
 *   <li>设置里的 {@code download.localModDirs}（可用分号或换行分隔）；</li>
 *   <li>{@code .minecraft/mods}；</li>
 *   <li>{@code .minecraft/versions/&#42;/mods}；</li>
 *   <li>{@code .minecraft} 上一级目录 4 层以内所有名为 {@code mods} / {@code MyDownload} 的目录
 *       （覆盖 PCL 的下载缓存与并列摆放的其它整合包）。</li>
 * </ol>
 *
 * <p>只有文件名命中或调用方给出大小才会读文件算 SHA1，未命中的 jar 不会产生 IO。
 */
@Service
public class LocalModLibrary {

    private static final Logger log = LoggerFactory.getLogger(LocalModLibrary.class);
    /** 索引文件数上限，避免在超大目录上把内存吃爆。 */
    private static final int MAX_INDEXED_FILES = 20000;
    /** 目录遍历深度上限（相对 .minecraft 的上一级）。 */
    private static final int MAX_DEPTH = 3;
    /** 遍历目录数上限：防止在巨型目录树上停留。 */
    private static final int MAX_WALK_DIRS = 2000;

    private final PclProperties properties;
    /** 小写文件名 -> 同名的候选文件（可能有多个整合包各放一份）。 */
    private volatile Map<String, List<Path>> index;
    private volatile List<Path> roots = List.of();

    public LocalModLibrary(PclProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试在本机找到与目标文件一致的那一份。
     *
     * @return 命中的本地文件；没有则 empty
     */
    public Optional<Path> find(NetFile file) {
        String name = file.localPath() == null ? null : file.localPath().getFileName().toString();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<Path> candidates = index().get(name.toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (Path candidate : candidates) {
            // 大小已知时先按大小排除，省掉无谓的整文件哈希
            if (file.size() > 0) {
                try {
                    if (Files.size(candidate) != file.size()) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }
            }
            if (file.sha1() != null && !file.sha1().isBlank()) {
                String actual = sha1(candidate);
                if (actual == null || !file.sha1().equalsIgnoreCase(actual)) {
                    continue;
                }
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** 已扫描的目录（日志与排查用）。 */
    public List<Path> scannedRoots() {
        index();
        return roots;
    }

    private Map<String, List<Path>> index() {
        Map<String, List<Path>> current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index != null) {
                return index;
            }
            List<Path> found = discoverRoots();
            Map<String, List<Path>> map = new HashMap<>();
            int files = 0;
            for (Path root : found) {
                try (Stream<Path> list = Files.list(root)) {
                    for (Path p : (Iterable<Path>) list::iterator) {
                        if (files >= MAX_INDEXED_FILES) {
                            break;
                        }
                        if (!Files.isRegularFile(p)) {
                            continue;
                        }
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".jar") && !name.endsWith(".zip") && !name.endsWith(".disabled")) {
                            continue;
                        }
                        map.computeIfAbsent(name, k -> new ArrayList<>(2)).add(p);
                        files++;
                    }
                } catch (IOException ex) {
                    log.debug("扫描本地 Mod 目录失败（{}）：{}", root, ex.getMessage());
                }
            }
            roots = List.copyOf(found);
            index = map;
            log.info("本地 Mod 复用索引：{} 个目录、{} 个 jar；命中同名文件即校验后直接复制", found.size(), files);
            return map;
        }
    }

    private List<Path> discoverRoots() {
        List<Path> result = new ArrayList<>();
        List<Path> mcRoots = new ArrayList<>();
        Path mc = properties.minecraftPath();
        mcRoots.add(mc);
        // 官方启动器 / 其它启动器常用的共享 .minecraft
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            addMcRoot(mcRoots, Path.of(appData, ".minecraft"));
        }
        // 并列摆放的整合包 / PCL 下载缓存 / 共享 .minecraft：从整合包目录的上一级往下找
        Path packDir = mc.getParent();
        if (packDir != null) {
            Path up = packDir.getParent();
            // 盘符根目录（D:\）不遍历：一个整合包放在 D:\pack 时，往上一级就是整块磁盘
            if (up != null && Files.isDirectory(up) && !isDriveRoot(up)) {
                walk(up, 1, result, mcRoots, new int[]{0});
            }
        }
        // 手动指定的额外目录：给的是整合包父目录时同样按结构展开
        for (String extra : properties.getLocalModDirs()) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            Path dir = Path.of(extra.trim());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String lower = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.equals("mods") || lower.equals("mydownload")) {
                addIfDir(result, dir);
            } else if (lower.equals(".minecraft")) {
                addMcRoot(mcRoots, dir);
            } else {
                walk(dir, 1, result, mcRoots, new int[]{0});
            }
        }
        for (Path root : mcRoots) {
            addIfDir(result, root.resolve("mods"));
            Path versions = root.resolve("versions");
            if (Files.isDirectory(versions)) {
                try (Stream<Path> list = Files.list(versions)) {
                    for (Path version : (Iterable<Path>) list::iterator) {
                        addIfDir(result, version.resolve("mods"));
                    }
                } catch (IOException ignored) {
                    // 版本目录读不到就跳过，不影响下载
                }
            }
        }
        return result;
    }

    private void addMcRoot(List<Path> mcRoots, Path root) {
        try {
            if (Files.isDirectory(root) && !mcRoots.contains(root)) {
                mcRoots.add(root);
            }
        } catch (RuntimeException ignored) {
            // 非法路径
        }
    }

    /** 有界遍历：收集 mods / MyDownload 目录，以及并列的 .minecraft（其 versions 另行展开）。 */
    private void walk(Path dir, int depth, List<Path> modDirs, List<Path> mcRoots, int[] visited) {
        if (depth > MAX_DEPTH || visited[0] > MAX_WALK_DIRS) {
            return;
        }
        visited[0]++;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path child : (Iterable<Path>) list::iterator) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String lower = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.equals(".minecraft")) {
                    if (!mcRoots.contains(child)) {
                        mcRoots.add(child);
                    }
                    continue;
                }
                if (lower.equals("mods") || lower.equals("mydownload")) {
                    addIfDir(modDirs, child);
                    continue;
                }
                // 这些目录里不会放 Mod，且体量极大
                if (lower.startsWith(".") || lower.equals("libraries") || lower.equals("assets")
                        || lower.equals("cache") || lower.equals("node_modules") || lower.equals("src")) {
                    continue;
                }
                walk(child, depth + 1, modDirs, mcRoots, visited);
            }
        } catch (IOException ignored) {
            // 权限不足的目录直接跳过
        }
    }

    /** 盘符根 / 文件系统根：不从这里开始遍历。 */
    private static boolean isDriveRoot(Path path) {
        return path.getParent() == null || path.toString().matches("^[A-Za-z]:\\?$");
    }

    private void addIfDir(List<Path> out, Path dir) {
        try {
            if (Files.isDirectory(dir) && !out.contains(dir)) {
                out.add(dir);
            }
        } catch (RuntimeException ignored) {
            // 非法路径
        }
    }

    private String sha1(Path path) {
        try (var in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            return null;
        }
    }
}
