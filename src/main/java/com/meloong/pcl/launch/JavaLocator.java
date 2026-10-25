package com.meloong.pcl.launch;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.settings.SettingsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Java 搜索与排序，移植自 <c>JavaUtils.CandidateFolders / SearchFoldersAsync / SortAsync</c>。
 *
 * <p>候选目录、特殊路径过滤（javapath / system32 / javatmp）与「优先主版本接近 21」的排序规则均与原实现一致。
 */
@Service
public class JavaLocator {

    private static final Logger log = LoggerFactory.getLogger(JavaLocator.class);
    private static final int MAX_DEPTH = 5;

    private final PclProperties properties;
    private final SettingsService settingsService;
    private volatile List<JavaRuntime> cache = List.of();
    private volatile boolean scanned;

    public JavaLocator(PclProperties properties, SettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
    }

    @PostConstruct
    void warmUp() {
        CompletableFuture.runAsync(this::candidates);
    }

    /** 候选文件夹，对应 <c>JavaUtils.CandidateFolders</c>。 */
    public List<Path> candidateFolders() {
        List<Path> folders = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userHome = System.getProperty("user.home");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            add(folders, appData, ".minecraft", "runtime");
            add(folders, appData, ".hmcl", "java");
            add(folders, appData, "ATLauncher", "runtimes", "minecraft");
            add(folders, appData, "ModrinthApp", "meta", "java_versions");
            add(folders, appData, "PrismLauncher", "java");
            add(folders, userHome, "curseforge", "minecraft", "Install", "runtime");
            add(folders, userHome, ".jdks");
            add(folders, userHome, ".sdkman", "candidates", "java");
            add(folders, localAppData, ".ftba", "bin", "runtime");
            add(folders, programFilesX86, "Minecraft Launcher", "runtime");
            add(folders, programFilesX86, "Minecraft", "runtime");
            add(folders, programFiles, "Java");
            add(folders, programFiles, "Eclipse Adoptium");
            add(folders, programFiles, "Amazon Corretto");
            add(folders, programFiles, "Zulu");
            add(folders, programFiles, "BellSoft");
            add(folders, programFiles, "Microsoft");
            add(folders, userHome, "Documents", "Curse", "Minecraft", "Install", "runtime");
        } else {
            add(folders, userHome, ".sdkman", "candidates", "java");
            add(folders, userHome, ".jdks");
            add(folders, Paths.get("/usr/lib/jvm").toString());
            add(folders, Paths.get("/Library/Java/JavaVirtualMachines").toString());
        }
        // 环境变量
        addPathVariable(folders, System.getenv("JDK_HOME"));
        addPathVariable(folders, System.getenv("JAVA_HOME"));

        return folders.stream().distinct().filter(Files::isDirectory).toList();
    }

    private static void add(List<Path> target, String... parts) {
        if (parts.length == 0 || parts[0] == null || parts[0].isBlank()) {
            return;
        }
        Path path = Paths.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            path = path.resolve(parts[i]);
        }
        target.add(path);
    }

    private static void addPathVariable(List<Path> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String part : value.split("[;:]")) {
            String trimmed = part.trim().replace("\"", "");
            if (!trimmed.isBlank()) {
                target.add(Paths.get(trimmed));
            }
        }
    }

    /** 搜索并检查所有 Java，结果按优先级排序（未检查的 Java 会被检查）。 */
    public List<JavaRuntime> candidates() {
        if (scanned) {
            return cache;
        }
        synchronized (this) {
            if (scanned) {
                return cache;
            }
            Map<Path, JavaRuntime> found = new LinkedHashMap<>();
            Set<Path> visited = new LinkedHashSet<>();
            for (Path folder : candidateFolders()) {
                if (!visited.add(folder)) {
                    continue;
                }
                collect(folder, found);
            }
            // 运行本应用的 JVM 自身也算一个候选
            String currentHome = System.getProperty("java.home");
            if (currentHome != null) {
                Path bin = Paths.get(currentHome, "bin");
                if (Files.isRegularFile(bin.resolve(executableName()))) {
                    found.putIfAbsent(bin.toAbsolutePath().normalize(),
                            JavaRuntime.ofBinFolder(bin.toAbsolutePath().normalize()));
                }
            }
            List<JavaRuntime> result = new ArrayList<>(found.values());
            // 配置中保存的手动添加 Java 也并入候选（扫描范围之外的自定义路径）
            for (String custom : settingsService.raw().customJavas) {
                try {
                    JavaRuntime runtime = resolve(custom);
                    runtime.check();
                    if (runtime.available() && !found.containsKey(runtime.binFolder())) {
                        found.put(runtime.binFolder(), runtime);
                        result.add(runtime);
                    }
                } catch (Exception ex) {
                    log.debug("配置中的自定义 Java 不可用（{}）：{}", custom, ex.getMessage());
                }
            }
            result.forEach(JavaRuntime::check);
            result.removeIf(java -> !java.available());
            result.sort(orderComparator());
            cache = List.copyOf(result);
            scanned = true;
            log.info("Java 搜索完成，可用 {} 个：{}", cache.size(), cache);
            return cache;
        }
    }

    /** 强制重新搜索。 */
    public synchronized List<JavaRuntime> refresh() {
        scanned = false;
        cache = List.of();
        return candidates();
    }

    /**
     * 手动登记一个 Java 路径（bin 目录、JDK 根目录或 java 可执行文件均可），
     * 校验可用后加入候选列表并持久化到设置，重启后仍会自动并入。
     * 返回的 JavaRuntime 不可用时给出原因。
     */
    public JavaRuntime register(String input) {
        // 先确保扫描完成：若扫描还在后台进行，此时改 cache/scanned 会被扫描结果覆盖
        candidates();
        JavaRuntime runtime = resolve(input);
        runtime.check();
        if (runtime.available() && !isAcceptable(runtime.binFolder())) {
            runtime = JavaRuntime.ofExecutable(runtime.executable());
            runtime.check();
        }
        if (runtime.available()) {
            JavaRuntime confirmed = runtime;
            boolean added = false;
            synchronized (this) {
                List<JavaRuntime> current = new ArrayList<>(cache);
                boolean wasPresent = current.stream().anyMatch(item -> item.binFolder()
                        .equals(confirmed.binFolder()));
                if (!wasPresent) {
                    current.add(confirmed);
                    current.sort(orderComparator());
                    cache = List.copyOf(current);
                    added = true;
                }
                scanned = true;
            }
            // 只有扫描搜不到的路径才需要持久化（搜得到的重启自然会回来）
            if (added && scannedBySearch(confirmed.binFolder())) {
                log.info("手动登记 Java（在搜索范围内，无需持久化）：{}", confirmed);
            } else if (added) {
                settingsService.addCustomJava(
                        confirmed.binFolder().toAbsolutePath().normalize().toString());
                log.info("手动登记 Java 并已保存到设置：{}", confirmed);
            }
        }
        return runtime;
    }

    /** 按输入形态解析 Java 运行时（bin 目录 / JDK 根目录 / java 可执行文件）。 */
    private JavaRuntime resolve(String input) {
        Path path = Paths.get(input.trim());
        if (Files.isRegularFile(path.resolve(executableName()))) {
            // 输入就是 bin 目录
            return JavaRuntime.ofBinFolder(path);
        }
        if (Files.isRegularFile(path.resolve("bin").resolve(executableName()))) {
            // 输入是 JDK 根目录
            return JavaRuntime.ofBinFolder(path.resolve("bin"));
        }
        // 输入是 java 可执行文件（或不存在，交给 check 报告）
        return JavaRuntime.ofExecutable(path);
    }

    /** 该 bin 目录是否落在默认搜索范围内（搜索范围内即扫描时会重复发现）。 */
    private boolean scannedBySearch(Path bin) {
        for (Path folder : candidateFolders()) {
            try {
                if (bin.startsWith(folder.toRealPath())) {
                    return true;
                }
            } catch (IOException ignored) {
                // 目录不可达，当作不在范围内
            }
        }
        return false;
    }

    private void collect(Path root, Map<Path, JavaRuntime> found) {
        String exe = executableName();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && exe.equalsIgnoreCase(file.getFileName().toString())) {
                        Path bin = file.getParent().toAbsolutePath().normalize();
                        if (isAcceptable(bin)) {
                            found.putIfAbsent(bin, JavaRuntime.ofBinFolder(bin));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.debug("搜索 Java 目录失败（{}）：{}", root, ex.getMessage());
        }
    }

    /** 过滤 javapath / system32 / javatmp 等特殊路径，对应原实现的 IsSpecialPath。 */
    private static boolean isAcceptable(Path bin) {
        String lower = bin.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("java8path_target_") || lower.contains("javapath_target_")
                || lower.contains("javatmp") || lower.contains("system32")) {
            return false;
        }
        // 过滤 Java 运行时的内部 jre/bin（例如 jdk/bin 与 jdk/jre/bin 指向同一套运行时）
        return true;
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    /** 优先主版本接近 21，其次候选目录靠前。 */
    private static Comparator<JavaRuntime> orderComparator() {
        return Comparator
                .comparingInt((JavaRuntime java) -> Math.abs(java.major() - 21))
                .thenComparing(java -> java.binFolder().toString().toLowerCase(Locale.ROOT));
    }

    /** 挑选最适合启动指定版本 MC 的 Java：优先满足最低要求，其次最接近 21。 */
    public JavaRuntime findBest(int requiredMajor) {
        List<JavaRuntime> list = candidates();
        if (list.isEmpty()) {
            return null;
        }
        int required = requiredMajor <= 0 ? 8 : requiredMajor;
        return list.stream()
                .filter(java -> java.major() >= required)
                .min(Comparator.comparingInt(java -> java.major() - required))
                .orElse(list.get(list.size() - 1));
    }

    /** 精确找某个主版本的 Java（launchwrapper 类版本只能用 Java 8，高了直接崩）。 */
    public JavaRuntime findExact(int major) {
        return candidates().stream()
                .filter(java -> java.major() == major)
                .findFirst()
                .orElse(null);
    }

    public JavaRuntime findByPath(String executablePath) {
        Path path = Paths.get(executablePath);
        JavaRuntime runtime = Files.isDirectory(path)
                ? JavaRuntime.ofBinFolder(path.resolve("bin"))
                : JavaRuntime.ofExecutable(path);
        runtime.check();
        return runtime;
    }
}
