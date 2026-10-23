package com.meloong.pcl.launch;

import com.fasterxml.jackson.databind.JsonNode;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.LibraryEntry;
import com.meloong.pcl.version.LibraryResolver;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.RuleEvaluator;
import com.meloong.pcl.version.VersionJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 启动参数构建，移植自 <c>ModLaunch.McLaunchArgumentsJVM / McLaunchArgumentsGame / McLaunchArgumentsReplace</c>。
 *
 * <p>与原始实现的两点差异（均为 Java 侧的必要适配）：
 * <ol>
 *   <li>原工程拼一条命令行字符串并按空格切分，需要一个「含空格参数加引号」的步骤；
 *       这里直接产出参数列表交给 {@link ProcessBuilder}，因此不做加引号，
 *       仅在 {@link LaunchPlan#display()} 中为展示目的补引号。</li>
 *   <li>原工程依赖携带的 JLW / LwjglUnsafeAgent 等补丁 jar，本移植版未包含这些资源，相关参数不注入。</li>
 * </ol>
 */
@Service
public class LaunchCommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(LaunchCommandBuilder.class);

    /** 与原实现一致的 GC 参数清理正则。 */
    private static final Pattern GC_PATTERN = Pattern.compile(
            "( )*-XX:[+-]?(Use\\w+GC|ZGenerational|UseCompactObjectHeaders|G1\\w+Percent|G1\\w+Size|(Max|Min)(GCPauseMillis|HeapFreeRatio))");

    /** 2023-05-04 之后支持 --quickPlayMultiplayer。 */
    private static final Instant QUICK_PLAY_SINCE = Instant.parse("2023-05-04T00:00:00Z");

    private final PclProperties properties;
    private final RuleEvaluator ruleEvaluator;
    private final LibraryResolver libraryResolver;
    private final MemoryAllocator memoryAllocator;

    public LaunchCommandBuilder(PclProperties properties, RuleEvaluator ruleEvaluator,
                                LibraryResolver libraryResolver, MemoryAllocator memoryAllocator) {
        this.properties = properties;
        this.ruleEvaluator = ruleEvaluator;
        this.libraryResolver = libraryResolver;
        this.memoryAllocator = memoryAllocator;
    }

    public LaunchPlan build(ResolvedVersion version, JavaRuntime java, LaunchOptions options, int modCount) {
        RuleEvaluator.Environment environment = RuleEvaluator.Environment.current()
                .withFeatures(Map.of("has_custom_resolution", options.width() > 0 && options.height() > 0));

        List<LibraryEntry> libraries = libraryResolver.resolve(version, true, environment);
        Path gameDir = options.isolate()
                ? properties.versionsPath().resolve(version.id())
                : properties.minecraftPath();
        Path nativesDir = properties.versionsPath().resolve(version.id()).resolve(version.id() + "-natives");

        int memoryMb = resolveMemory(options, modCount, version);

        List<String> jvm = buildJvmArgs(version, java, options, memoryMb, environment);
        List<String> game = buildGameArgs(version, options, environment);
        Map<String, String> values = placeholders(version, options, libraries, gameDir, nativesDir);

        List<String> jvmReplaced = replace(jvm, values);
        List<String> gameReplaced = replace(game, values);

        List<String> command = new ArrayList<>();
        command.add(java.executable().toString());
        command.addAll(jvmReplaced);
        command.add(version.mainClass());
        command.addAll(gameReplaced);

        String classpath = values.get("${classpath}");
        log.info("已构建启动命令：{}（Java {}，内存 {} MB）", version.id(), java.major(), memoryMb);
        return new LaunchPlan(List.copyOf(command), LaunchPlan.toDisplay(command), classpath, nativesDir,
                gameDir, java.executable().toString(), memoryMb, libraries);
    }

    // ------------------------------------------------------------ JVM 参数

    private List<String> buildJvmArgs(ResolvedVersion version, JavaRuntime java, LaunchOptions options,
                                      int memoryMb, RuleEvaluator.Environment environment) {
        List<String> args = new ArrayList<>();
        if (!version.jvmArgs().isEmpty()) {
            // 新版 MC：从版本 JSON 获取
            for (JsonNode node : version.jvmArgs()) {
                args.addAll(flatten(node, environment));
            }
        } else {
            // 旧版 MC：直接添加
            args.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            args.add("-Djava.library.path=${natives_directory}");
            args.add("-cp");
            args.add("${classpath}");
        }

        // 设置中自定义的参数
        List<String> custom = new ArrayList<>(properties.getExtraJvmArgs());
        custom.addAll(options.extraJvmArgs());
        for (String raw : custom) {
            args.addAll(splitArguments(raw));
        }
        args = deduplicate(args, true);

        // 内存
        args.add("-Xmx" + memoryMb + "m");

        // GC
        applyGc(args, java.major());

        // Log4j 漏洞防御
        if (properties.isLog4jPatch() && args.stream().noneMatch(a -> a.startsWith("-Dlog4j2."))) {
            args.add("-Dlog4j2.formatMsgNoLookups=true");
        }

        // 编码
        applyEncoding(args, java);

        // 部分版本 JSON 缺少 -cp，补一条兜底
        if (args.stream().noneMatch(a -> "-cp".equals(a) || "-classpath".equals(a))) {
            args.add("-cp");
            args.add("${classpath}");
        }
        return deduplicate(args, true);
    }

    /** GC 选择，对应原实现的 LaunchAdvanceGC 四种策略。 */
    private void applyGc(List<String> args, int javaMajor) {
        int mode = properties.getGcMode();
        if (mode == 3) {
            return;
        }
        boolean useG1 = (mode == 0 && javaMajor < 15) || (mode == 1 && javaMajor < 21) || mode == 2 || mode == 4;
        // ZGC 需要 Windows 10 1809+；非 Windows 平台不额外限制
        if (isWindowsBefore1809()) {
            useG1 = true;
        }
        args.removeIf(arg -> GC_PATTERN.matcher(arg).matches());
        args.add("-XX:+UnlockExperimentalVMOptions");
        if (javaMajor >= 24) {
            args.add("-XX:+UseCompactObjectHeaders");
        }
        if (useG1) {
            args.add("-XX:+UseG1GC");
            args.add("-XX:G1NewSizePercent=20");
            args.add("-XX:G1ReservePercent=20");
            args.add("-XX:G1HeapRegionSize=32M");
            args.add("-XX:MaxGCPauseMillis=50");
            if (mode == 4) {
                args.add("-XX:+PerfDisableSharedMem");
                if (javaMajor <= 7) {
                    args.add("-XX:MaxPermSize=512m");
                }
                if (javaMajor == 8) {
                    args.add("-XX:+ParallelRefProcEnabled");
                }
                if (javaMajor >= 12) {
                    args.add("-XX:MinHeapFreeRatio=25");
                    args.add("-XX:MaxHeapFreeRatio=40");
                }
            } else if (javaMajor <= 7) {
                args.add("-XX:MaxPermSize=512m");
            }
        } else {
            args.add("-XX:+UseZGC");
            if (javaMajor == 21 || javaMajor == 22) {
                args.add("-XX:+ZGenerational");
            }
        }
    }

    private static boolean isWindowsBefore1809() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return false;
        }
        String version = System.getProperty("os.version", "");
        try {
            String[] parts = version.split("\\.");
            double major = Double.parseDouble(parts[0]);
            if (major < 10) {
                return true;
            }
            String build = System.getProperty("os.build", "");
            return !build.isBlank() && Integer.parseInt(build) < 17763;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 输出编码参数，对应原实现 #4700 / #8950 的处理。 */
    private void applyEncoding(List<String> args, JavaRuntime java) {
        boolean compat = java.major() >= 18;
        String encoding = compat ? java.nativeEncoding() : java.fileEncoding();
        if (encoding == null || encoding.isBlank()) {
            encoding = "UTF-8";
        }
        if ("gbk".equalsIgnoreCase(encoding)) {
            // Java 侧用 GB18030 兼容 GBK 的字符集
            encoding = "GBK";
        }
        String stdoutArg = java.major() < 19 ? "sun.stdout.encoding" : "stdout.encoding";
        String stderrArg = java.major() < 19 ? "sun.stderr.encoding" : "stderr.encoding";
        String finalEncoding = encoding;
        if (args.stream().noneMatch(a -> a.startsWith("-D" + stdoutArg + "="))) {
            args.add("-D" + stdoutArg + "=" + finalEncoding);
        }
        if (args.stream().noneMatch(a -> a.startsWith("-D" + stderrArg + "="))) {
            args.add("-D" + stderrArg + "=" + finalEncoding);
        }
        if (compat && args.stream().noneMatch(a -> a.startsWith("-Dfile.encoding="))) {
            args.add("-Dfile.encoding=COMPAT");
        }
    }

    // ------------------------------------------------------------ 游戏参数

    private List<String> buildGameArgs(ResolvedVersion version, LaunchOptions options,
                                       RuleEvaluator.Environment environment) {
        List<String> args = new ArrayList<>();
        if (version.minecraftArguments() != null && !version.minecraftArguments().isBlank()) {
            // 旧版：直接使用 minecraftArguments，并补充宽高
            args.addAll(splitArguments(version.minecraftArguments()));
            args.add("--height");
            args.add("${resolution_height}");
            args.add("--width");
            args.add("${resolution_width}");
        }
        for (JsonNode node : version.gameArgs()) {
            args.addAll(flatten(node, environment));
        }
        args = deduplicate(args, false);

        // 额外参数
        for (String raw : properties.getExtraGameArgs()) {
            args.addAll(splitArguments(raw));
        }
        args.addAll(options.extraGameArgs());

        // 全屏
        if (options.fullscreen()) {
            args.add("--fullscreen");
        }

        // 自动进入服务器
        if (!options.serverIp().isBlank()) {
            boolean quickPlay = version.releaseTime() != null
                    && parseInstant(version.releaseTime()).isAfter(QUICK_PLAY_SINCE);
            if (quickPlay) {
                args.add("--quickPlayMultiplayer");
                args.add(options.serverIp());
            } else {
                String ip = options.serverIp();
                String host = ip;
                String port = "25565";
                int colon = ip.lastIndexOf(':');
                if (colon > 0) {
                    host = ip.substring(0, colon);
                    port = ip.substring(colon + 1);
                }
                args.add("--server");
                args.add(host);
                args.add("--port");
                args.add(port);
            }
        }
        return deduplicate(args, false);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return Instant.EPOCH;
        }
    }

    // ------------------------------------------------------------ 占位符

    private Map<String, String> placeholders(ResolvedVersion version, LaunchOptions options,
                                             List<LibraryEntry> libraries, Path gameDir, Path nativesDir) {
        Map<String, String> values = new LinkedHashMap<>();
        StringBuilder classpath = new StringBuilder();
        for (LibraryEntry entry : libraries) {
            if (entry.natives()) {
                continue;
            }
            if (classpath.length() > 0) {
                classpath.append(File.pathSeparator);
            }
            classpath.append(entry.localPath());
        }

        values.put("${classpath_separator}", File.pathSeparator);
        values.put("${natives_directory}", nativesDir.toString());
        values.put("${library_directory}", properties.librariesPath().toString());
        values.put("${libraries_directory}", properties.librariesPath().toString());
        values.put("${pure_directory}", properties.minecraftPath().toString());
        values.put("${launcher_name}", properties.getLauncherName());
        values.put("${launcher_version}", properties.getLauncherVersion());
        values.put("${version_name}", version.id());
        values.put("${game_directory}", gameDir.toString());
        values.put("${assets_root}", properties.assetsPath().toString());
        values.put("${user_properties}", "{}");
        values.put("${auth_player_name}", options.playerName());
        values.put("${auth_uuid}", options.uuid());
        values.put("${auth_access_token}", options.accessToken());
        values.put("${access_token}", options.accessToken());
        values.put("${auth_session}", options.accessToken());
        values.put("${user_type}", options.userType());
        // Forge / NeoForge 的 1.16+ 参数里带这两个占位符，留空即可（对应原实现的 McLoginLegacyUuid）
        values.put("${clientid}", "");
        values.put("${auth_xuid}", "");
        values.put("${xuid}", "");
        values.put("${primary_jar}", properties.versionsPath().resolve(version.jarId())
                .resolve(version.jarId() + ".jar").toString());
        values.put("${game_assets}", properties.assetsPath().resolve("virtual").resolve("legacy").toString());
        values.put("${assets_index_name}", version.assetsIndexName());
        values.put("${version_type}", properties.getLauncherName());
        values.put("${resolution_width}", String.valueOf(options.width()));
        values.put("${resolution_height}", String.valueOf(options.height()));
        values.put("${classpath}", classpath.toString());
        return values;
    }

    private List<String> replace(List<String> args, Map<String, String> values) {
        List<String> result = new ArrayList<>(args.size());
        for (String arg : args) {
            String replaced = arg;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (replaced.contains(entry.getKey())) {
                    replaced = replaced.replace(entry.getKey(), entry.getValue());
                }
            }
            result.add(replaced);
        }
        return result;
    }

    // ------------------------------------------------------------ 工具方法

    /** 展开字符串 / 带 rules 的对象参数，对应原实现遍历 JToken 的两类分支。 */
    private List<String> flatten(JsonNode node, RuleEvaluator.Environment environment) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (!node.isObject()) {
            return List.of();
        }
        JsonNode rules = node.get("rules");
        if (rules != null && !ruleEvaluator.check(rules, environment)) {
            return List.of();
        }
        JsonNode value = node.get("value");
        if (value == null) {
            return List.of();
        }
        if (value.isTextual()) {
            return List.of(value.asText());
        }
        List<String> result = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                result.add(item.asText());
            }
        }
        return result;
    }

    /**
     * 参数字符串切分，移植自 <c>SplitJavaArguments</c>：支持双引号包裹、<c>\"</c> 转义。
     */
    public static List<String> splitArguments(String arg) {
        List<String> result = new ArrayList<>();
        if (arg == null) {
            return result;
        }
        String text = arg.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                current.append("\\\"");
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 参数去重，移植自 <c>DeduplicateJavaArguments</c>。
     *
     * <p>单参数去重；键值对遇到同键时，游戏参数用新值覆盖（<c>--tweakClass</c> 除外）。
     */
    public static List<String> deduplicate(List<String> args, boolean jvmArgs) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < args.size()) {
            String key = args.get(i);
            boolean single = !key.startsWith("-")
                    || i + 1 >= args.size()
                    || (args.get(i + 1).startsWith("-") && valueOfDoubleDash(args.get(i + 1)) == 0);
            if (single) {
                i++;
                if (result.contains(key)) {
                    log.debug("已去重单个{}参数：{}", jvmArgs ? " JVM " : "游戏", key);
                    continue;
                }
                result.add(key);
            } else {
                String value = args.get(i + 1);
                i += 2;
                boolean skip = false;
                for (int j = 0; j + 1 < result.size(); j++) {
                    if (!result.get(j).equals(key)) {
                        continue;
                    }
                    if (!jvmArgs && !"--tweakClass".equals(key)) {
                        result.set(j + 1, value);
                        skip = true;
                        break;
                    }
                    if (result.get(j + 1).equals(value)) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    result.add(key);
                    result.add(value);
                }
            }
        }
        return result;
    }

    /** 对应 VB 的 <c>Val(Args(i + 1).AfterFirst("-"))</c>：把 "-50" 这类数值当成参数值而非开关。 */
    private static double valueOfDoubleDash(String arg) {
        String trimmed = arg.startsWith("-") ? arg.substring(1) : arg;
        try {
            return Double.parseDouble(trimmed.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 内存

    private int resolveMemory(LaunchOptions options, int modCount, ResolvedVersion version) {
        if (options.memoryMb() > 0) {
            return options.memoryMb();
        }
        if (properties.getDefaultMemoryMb() > 0) {
            return properties.getDefaultMemoryMb();
        }
        return memoryAllocator.allocateMb(modCount, version.mcVersion().modable(), version.mcVersion().hasOptiFine());
    }

    /** 供外部复用：计算类路径字符串。 */
    public String classpathOf(ResolvedVersion version, RuleEvaluator.Environment environment) {
        StringBuilder sb = new StringBuilder();
        for (LibraryEntry entry : libraryResolver.resolve(version, true, environment)) {
            if (entry.natives()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(entry.localPath());
        }
        return sb.toString();
    }

    /** 供外部复用：客户端 jar 下载信息。 */
    public VersionJson.DownloadInfo clientDownload(ResolvedVersion version) {
        return version.clientDownload();
    }
}
