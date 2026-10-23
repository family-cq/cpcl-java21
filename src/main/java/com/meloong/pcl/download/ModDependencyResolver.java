package com.meloong.pcl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包前置 Mod 自动补齐（PCL 原版依赖思路的 Java 移植，无内置补丁表、可移植）。
 *
 * <p>很多整合包（尤其是作者手工整理的裸 zip）不会把前置 Mod 一起打进来，启动时 FML 直接报
 * 「Missing or unsupported mandatory dependencies」并崩溃。这里按三条<b>通用</b>渠道发现缺失，
 * 换任何一台机器、任何一个整合包都能跑，不依赖任何手工维护的补丁表：
 *
 * <ol>
 *   <li><b>平台依赖链</b>（对应 PCL {@code ResourceVersion.FromPlatformJson} 的
 *       {@code RawDependencies} 逻辑）：对 mods/ 下每个 jar 计算 sha1，用 Modrinth 的
 *       {@code /version_files} 接口反查它属于哪个项目版本，取该版本声明的所有
 *       {@code required} 依赖的 project_id（同样排除 Fabric API / Quilt API），
 *       命中的前置直接从平台下载，递归收敛。</li>
 *   <li><b>本地依赖声明 + 版本区间检查</b>：解析 Forge {@code mods.toml} /
 *       Fabric {@code fabric.mod.json} 里 mandatory 的依赖；对已装版本还做 Maven 区间
 *       满足性检查（如 Cataclysm 声明 {@code lionfishapi [3.0,)}，老包里的 2.4-Fix 不满足
 *       就升级替换），区间外旧 jar 备份到 {@code .pcl-removed}。</li>
 *   <li><b>类引用扫描</b>：mixin / 扩展 Mod 经常<b>不声明</b>依赖（cataclysm_modifier 直接
 *       引用 ExModifier 的 ExUtil 却没有任何 [[dependencies]]），扫 jar 内 class 常量池
 *       引用的外部包，减去 JDK/MC/加载器等环境包与已装 Mod 提供的包，剩下的缺口包转成
 *       搜索词去 Modrinth / CurseForge 找，下载后校验 jar 确实包含该包才落盘。</li>
 * </ol>
 *
 * <p>匹配策略偏保守：按 modId 补的候选要求 slug/标题归一化后一致且 jar 内 modId 对得上；
 * 按缺口包补的候选要求 jar 内真实存在该包。宁可漏装也不装错，因为装错 Mod 会带来更难排查的崩溃。
 */
@Service
public class ModDependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(ModDependencyResolver.class);

    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String CURSE_API = "https://api.curseforge.com/v1";
    private static final String CURSE_TOOLS = "https://api.curse.tools/v1/cf";
    private static final String CURSE_CDN = "https://www.curseforge.com/api/v1/mods";
    /** 第三方 CF 元数据镜像：只读、免 Key、国内可直连（官方站与 api.curseforge.com 都要 Key 或过 Cloudflare）。 */
    private static final String CFWIDGET = "https://api.cfwidget.com";
    /** CF 文件 CDN，规则是 files/{id/1000}/{id%1000}/{文件名}，不需要 Key。 */
    private static final String FORGECDN = "https://mediafilez.forgecdn.net/files";

    /** 由启动器负责、不需要也不能作为 Mod 下载的依赖。 */
    private static final Set<String> BUILTIN = Set.of(
            "minecraft", "forge", "neoforge", "fml", "java", "mcp", "optifine",
            "fabricloader", "fabric-loader", "quilt_loader", "quilted_fabric_loader", "neoforged",
            "fabric-api", "quilt-api");

    /**
     * 平台依赖里由启动器/API 自身负责的项目（与 PCL 同款排除清单）：
     * P7dR8mSH 是 Fabric API，qvIfYCYJ 是 Quilt Standard Libraries。
     */
    private static final Set<String> PLATFORM_NOISE_PIDS = Set.of("P7dR8mSH", "qvIfYCYJ");

    /**
     * 环境包前缀：JDK、Minecraft、加载器与其自带三方库提供的包。
     * 类引用扫描时这些不算缺口（它们由游戏/加载器环境提供，不需要装 Mod）。
     * 只放环境库；像 architectury、cloth-config 这类<b>真 Mod</b> 的包绝不能进来，
     * 否则该补的前置会被漏掉。
     */
    private static final List<String> ENV_PREFIXES = List.of(
            "java/", "javax/", "sun/", "com/sun/", "jdk/", "org/w3c/", "org/xml/", "org/ietf/",
            "com/mojang/", "net/minecraft/", "net/minecraftforge/", "net/neoforged/",
            "net/fabricmc/", "net/fabricloader/", "org/quiltmc/", "cpw/mods/", "io/undertow/",
            "org/spongepowered/", "org/objectweb/", "org/apache/", "com/google/", "com/fasterxml/",
            "io/netty/", "it/unimi/", "org/joml/", "org/lwjgl/", "org/slf4j/", "org/jetbrains/",
            "org/intellij/", "org/jline/", "org/ow2/", "org/yaml/", "org/tukaani/", "org/glm/",
            "oshi/", "com/machinezoo/", "net/jodah/", "com/ibm/", "com/zaxxer/", "gnu/trove/",
            "kotlin/", "kotlinx/", "com/electronwill/", "net/minecrell/", "joptsimple/",
            "com/mumfrey/", "com/llamalad7/", "org/joml/");

    /** 搜索词里跳过的无意义包名段（生成缺口包搜索词时用）。 */
    private static final Set<String> NOISE_SEGMENTS = Set.of(
            "com", "net", "org", "io", "dev", "github", "mods", "mod", "mc", "minecraft",
            "forge", "fabric", "neoforge", "content", "common", "core", "util", "utils");

    /**
     * 用于给 modId 猜想 slug 分词的常见词尾。
     *
     * <p>只用于「猜 slug」这一步，命中后仍要求归一化完全相等，所以词表不全会漏装，
     * 但不会因为猜错而装错 Mod。
     */
    private static final List<String> TAIL_WORDS = List.of(
            "delight", "delights", "config", "configs", "api", "lib", "library", "core", "blade",
            "blades", "health", "gel", "damage", "craft", "crafting", "harvester", "harvest", "relic",
            "relics", "tweaks", "tweak", "animator", "animation", "fix", "fixes", "enhanced", "extended",
            "compat", "compatibility", "utils", "util", "loader", "menu", "items", "item", "mobs", "mob",
            "biome", "biomes", "world", "structures", "structure", "weapons", "weapon", "armor", "tools",
            "storage", "machine", "machines", "energy", "recipe", "recipes", "quest", "quests", "addon",
            "additions", "control", "controls", "manager", "helper", "enough", "blade", "slash");

    /** 单轮最多补装数量，防止包内依赖链异常时无限扩散。 */
    private static final int MAX_PER_ROUND = 60;
    /**
     * 单轮最多处理的类引用缺口数。
     *
     * <p>类引用扫描的缺口里混着大量「生态 API」（JEI/REI/EMI 等编译期集成），
     * 它们不装通常也能启动；真正的扩展前置（exmodifier、ftbquests）引用者很少、优先级高。
     * 每轮只补最像必需前置的几个，其余留到下一轮或列入未解决清单。
     */
    private static final int MAX_GAP_PER_ROUND = 10;
    /** 缺口候选的体积上限：只拦异常超大（100MB+）的包，本体 70MB 的 Cataclysm 必须能过。 */
    private static final long MAX_GAP_CANDIDATE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ROUNDS = 4;
    /** 并行补装的线程数：每个 Mod 的候选查询与下载相互独立，串行时 16 个前置要跑五六分钟。 */
    private static final int PARALLELISM = 8;
    /** Modrinth 批量反查接口单次最多支持的 hash 数。 */
    private static final int HASH_BATCH = 50;

    private static final Pattern SECTION = Pattern.compile("\\[\\[([^\\]]+)\\]\\]([\\s\\S]*?)(?=\\[\\[|$)");
    private static final Pattern STRING_VALUE = Pattern.compile("%s\\s*=\\s*\"([^\"]*)\"");
    /** TOML 原始值：可能带引号（"1.20.1"）也可能不带（true / 1.5），还可能有行尾注释。 */
    private static final Pattern RAW_VALUE = Pattern.compile("%s\\s*=\\s*([^\\r\\n]*)");
    /** 布尔写法里视为「否」的那些。 */
    private static final Set<String> FALSE_WORDS = Set.of("false", "0", "no", "off");
    /**
     * class 常量池里的类引用：形如 {@code Lnet/exmo/exmodifier/util/ExUtil;}。
     * 直接对 class 字节流做正则，无需完整解析字节码，速度足够且误报极少
     * （字符串常量里出现 {@code Lx/y/z;} 的概率可以忽略）。
     */
    private static final Pattern CLASS_REF =
            Pattern.compile("L([a-z][a-zA-Z0-9_]*(?:/[a-zA-Z0-9_$]+)+);");

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final PclProperties properties;
    private final DownloadManager downloadManager;
    private final com.meloong.pcl.settings.SettingsService settingsService;

    public ModDependencyResolver(RestTemplate restTemplate, ObjectMapper mapper,
                                 PclProperties properties, DownloadManager downloadManager,
                                 com.meloong.pcl.settings.SettingsService settingsService) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.properties = properties;
        this.downloadManager = downloadManager;
        this.settingsService = settingsService;
    }

    /** 单个 jar 的元数据。 */
    record JarMeta(String modId, String version, List<Dep> deps) {
        static JarMeta empty() {
            return new JarMeta("", "", List.of());
        }
    }

    record Dep(String modId, String versionRange, boolean mandatory) {
    }

    private record Candidate(String url, String sha1, String fileName, long size) {
    }

    /** 补齐结果。 */
    public record Result(int installed, List<String> installedMods, List<String> unresolved) {
    }

    /**
     * 一条待补齐依赖。
     *
     * @param key               日志标识：modId、平台 project_id 或 {@code pkg:xxx}
     * @param platformProjectId 非 null 时走平台直取（Modrinth project_id）
     * @param gapPackage        非 null 时是「类引用缺口包」，下载后必须校验 jar 含该包
     * @param upgrade           true 表示已装但版本不满足声明区间，装前要把旧 jar 移走
     * @param requiredBy        谁需要它（日志/前端展示用）
     */
    private record Missing(String key, String platformProjectId, String gapPackage,
                           boolean upgrade, String requiredBy) {
    }

    /**
     * 扫描并补齐 {@code modsDir} 下所有 Mod 的必装依赖。
     *
     * @param modsDir  实例的 mods 目录
     * @param loader   forge / neoforge / fabric
     * @param mcVersion 目标游戏版本
     */
    public Result complete(Path modsDir, String loader, String mcVersion) {
        if (modsDir == null || !Files.isDirectory(modsDir) || loader == null || loader.isBlank()
                || mcVersion == null || mcVersion.isBlank()) {
            return new Result(0, List.of(), List.of());
        }
        List<String> installedMods = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        // 记下「谁需要它」，补不到时才能告诉用户该去找哪个包的前置
        Map<String, Set<String>> requiredBy = new LinkedHashMap<>();
        try {
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                Scan scan = scan(modsDir, loader, mcVersion);
                List<Missing> missing = collect(scan);
                for (Missing item : missing) {
                    requiredBy.computeIfAbsent(item.key(), k -> new LinkedHashSet<>())
                            .add(item.requiredBy());
                }
                // 上一轮就没补到的不再重复请求
                missing.removeIf(item -> unresolved.contains(item.key()));
                if (missing.isEmpty()) {
                    break;
                }
                if (missing.size() > MAX_PER_ROUND) {
                    log.warn("缺失前置过多（{} 个），本轮只处理前 {} 个", missing.size(), MAX_PER_ROUND);
                    missing = missing.subList(0, MAX_PER_ROUND);
                }
                log.info("第 {} 轮前置检查：缺失 {} 个 -> {}", round, missing.size(),
                        missing.stream().map(Missing::key).toList());

                int installedThisRound = 0;
                // 并行补装：各 Mod 的查询与下载互不依赖，串行时 16 个前置要五六分钟
                ExecutorService pool =
                        Executors.newFixedThreadPool(Math.min(PARALLELISM, missing.size()));
                try {
                    List<Future<String[]>> futures = new ArrayList<>();
                    for (Missing item : missing) {
                        futures.add(pool.submit(() -> {
                            // 单个 Mod 查询/下载失败不能中断整轮补齐
                            try {
                                return install(modsDir, item, loader, mcVersion)
                                        ? new String[]{item.key(), null} : new String[]{item.key(), "notfound"};
                            } catch (Exception ex) {
                                log.warn("补齐前置 {} 时出错：{}", item.key(), brief(ex.getMessage()));
                                return new String[]{item.key(), "error"};
                            }
                        }));
                    }
                    for (Future<String[]> future : futures) {
                        String[] outcome = future.get();
                        if (outcome[1] == null) {
                            installedMods.add(outcome[0]);
                            installedThisRound++;
                        } else {
                            unresolved.add(outcome[0]);
                        }
                    }
                } catch (java.util.concurrent.ExecutionException ex) {
                    log.warn("并行补装异常：{}", brief(String.valueOf(ex.getCause())));
                } finally {
                    pool.shutdownNow();
                }
                if (installedThisRound == 0) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("前置依赖检查失败（不影响已安装内容）：{}", ex.getMessage());
        }
        if (!installedMods.isEmpty()) {
            log.info("已自动补装前置 Mod {} 个：{}", installedMods.size(), installedMods);
        }
        List<String> unresolvedView = new ArrayList<>();
        for (String key : unresolved) {
            Set<String> owners = requiredBy.get(key);
            unresolvedView.add(owners == null || owners.isEmpty()
                    ? key
                    : key + "（" + String.join("、", owners) + " 需要）");
        }
        if (!unresolvedView.isEmpty()) {
            log.warn("以下前置 Mod 未能自动获取，需手动放进 mods 目录：{}", unresolvedView);
        }
        return new Result(installedMods.size(), installedMods, unresolvedView);
    }

    // ------------------------------------------------------------------ 缺失收集

    /** 从扫描结果里收集所有缺失/不满足的依赖。 */
    private List<Missing> collect(Scan scan) {
        List<Missing> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 1) 本地声明：mandatory 且缺失，或已装但版本明确低于声明的下界
        for (Map.Entry<String, List<Dep>> entry : scan.deps().entrySet()) {
            for (Dep dep : entry.getValue()) {
                if (!dep.mandatory() || isBuiltin(dep.modId()) || dep.modId().equals(entry.getKey())) {
                    continue;
                }
                String have = scan.installed().get(dep.modId());
                if (have == null) {
                    if (seen.add(dep.modId())) {
                        missing.add(new Missing(dep.modId(), null, null, false,
                                entry.getKey() + " 声明依赖"));
                    }
                } else if (upgradeNeeded(have, dep.versionRange())) {
                    // 已装但版本明确不满足（如 lionfishapi 2.4-Fix < [3.0,)），需要升级替换
                    if (seen.add(dep.modId())) {
                        missing.add(new Missing(dep.modId(), null, null, true,
                                entry.getKey() + " 要求 " + dep.versionRange() + "（现有 " + have + "）"));
                    }
                }
            }
        }

        // 2) 平台依赖链：sha1 反查到的版本声明了 required 前置
        for (Map.Entry<String, List<String>> entry : scan.platformRequired().entrySet()) {
            String ownerPid = entry.getKey();
            String ownerName = scan.platformNames().getOrDefault(ownerPid, ownerPid);
            for (String pid : entry.getValue()) {
                if (PLATFORM_NOISE_PIDS.contains(pid) || scan.platformIds().containsValue(pid)) {
                    continue;
                }
                if (seen.add("pid:" + pid)) {
                    missing.add(new Missing("pid:" + pid, pid, null, false,
                            ownerName + " 平台声明依赖"));
                }
            }
        }

        // 3) 类引用缺口：不声明依赖的扩展 Mod 引用了未安装的外部包
        //    同一库往往有多个 4 段缺口包（exmodifier/util、exmodifier/content），
        //    按前 3 段去重；单轮只补引用者最少的几个（越少越像特定扩展的必需前置）。
        //    注意不能按引用者数量「一刀切」过滤生态 API：像 exmodifier 被
        //    cataclysm_modifier/elementex/rough_blade 整个 mod 族引用，但它就是必需前置。
        Map<String, Set<String>> groupOwners = new LinkedHashMap<>();
        Map<String, String> groupPkg = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : scan.references().entrySet()) {
            for (String ref : entry.getValue()) {
                if (isEnvClass(ref) || ownedByInstalled(ref, scan.providedDirs())) {
                    continue;
                }
                String pkg = gapPkgOf(ref);
                if (pkg == null || coveredBy(pkg, scan.providedDirs())) {
                    continue;
                }
                String group = groupOf(pkg);
                groupOwners.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(entry.getKey());
                groupPkg.putIfAbsent(group, pkg);
            }
        }
        List<Map.Entry<String, Set<String>>> groups = new ArrayList<>(groupOwners.entrySet());
        groups.sort(java.util.Comparator.comparingInt(e -> e.getValue().size()));
        int gapBudget = MAX_GAP_PER_ROUND;
        for (Map.Entry<String, Set<String>> group : groups) {
            if (gapBudget <= 0) {
                break;
            }
            Set<String> owners = group.getValue();
            String pkg = groupPkg.get(group.getKey());
            if (!seen.add("pkg:" + pkg)) {
                continue;
            }
            missing.add(new Missing("pkg:" + pkg, null, pkg, false,
                    String.join("、", owners) + " 引用了包 " + pkg.replace('/', '.')));
            gapBudget--;
        }
        return missing;
    }

    /**
     * 是否值得升级替换已装版本。
     *
     * <p>条件苛刻是有意的：区间必须是单侧下界 {@code [x,)}（带精确值或有上界的不碰，
     * 那些判断错了会装进错误版本），且已装版本必须<b>可解析</b>——很多 Mod 的
     * {@code version} 字段是没替换的占位符 {@code ${file.jarVersion}}，
     * 清洗后是 0，比任何下界都小，直接比较会误杀（Cucumber/TerraBlender 被误移的教训）。
     */
    static boolean upgradeNeeded(String have, String range) {
        if (have == null || have.isBlank() || have.contains("${")) {
            return false;
        }
        String text = range == null ? "" : range.trim();
        if (!text.startsWith("[") || !text.endsWith(")")) {
            return false;
        }
        int comma = text.indexOf(',');
        if (comma < 0) {
            return false;
        }
        String lower = text.substring(1, comma).trim();
        if (lower.isEmpty()) {
            return false;
        }
        return compareVersion(have, lower) < 0;
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 一次扫描的结果。
     *
     * @param installed       modId -> 已装版本
     * @param deps            modId -> 声明的依赖
     * @param paths           modId -> jar 路径
     * @param platformIds     modId -> Modrinth project_id（sha1 反查命中才有）
     * @param platformNames   project_id -> 项目名
     * @param platformRequired project_id -> 该版本声明的 required 依赖 project_id 列表
     * @param providedDirs    所有已装 jar 提供的 class 目录路径全集
     * @param references      modId -> 引用的外部类路径（未经环境过滤）
     */
    private record Scan(Map<String, String> installed, Map<String, List<Dep>> deps,
                        Map<String, Path> paths, Map<String, String> platformIds,
                        Map<String, String> platformNames, Map<String, List<String>> platformRequired,
                        Set<String> providedDirs, Map<String, Set<String>> references) {
    }

    private record JarScan(Path jar, String modId, String version, List<Dep> deps, String sha1,
                           Set<String> dirs, Set<String> refs) {
    }

    /** 扫描 mods 目录：并行读元数据/hash/包结构，然后批量反查 Modrinth。 */
    private Scan scan(Path modsDir, String loader, String mcVersion) throws IOException {
        List<Path> jars;
        try (var stream = Files.list(modsDir)) {
            jars = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList();
        }
        // 并行读每个 jar 的元数据、sha1、class 目录与类引用
        List<JarScan> scans = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            List<Future<JarScan>> futures = new ArrayList<>();
            for (Path jar : jars) {
                futures.add(pool.submit(() -> readJarDeep(jar)));
            }
            for (Future<JarScan> future : futures) {
                try {
                    JarScan result = future.get();
                    if (result != null) {
                        scans.add(result);
                    }
                } catch (Exception ex) {
                    log.debug("扫描 Mod 失败：{}", brief(String.valueOf(ex.getCause())));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, String> installed = new LinkedHashMap<>();
        Map<String, List<Dep>> deps = new LinkedHashMap<>();
        Map<String, Path> paths = new LinkedHashMap<>();
        Set<String> providedDirs = new LinkedHashSet<>();
        Map<String, Set<String>> references = new LinkedHashMap<>();
        Map<String, String> sha1ByModId = new LinkedHashMap<>();
        for (JarScan item : scans) {
            if (item.modId().isBlank()) {
                continue;
            }
            installed.putIfAbsent(item.modId(), item.version());
            paths.putIfAbsent(item.modId(), item.jar());
            if (!item.deps().isEmpty()) {
                deps.put(item.modId(), item.deps());
            }
            providedDirs.addAll(item.dirs());
            if (!item.refs().isEmpty()) {
                references.put(item.modId(), item.refs());
            }
            sha1ByModId.put(item.modId(), item.sha1());
        }

        // 批量 sha1 -> Modrinth 版本反查
        Map<String, String> platformIds = new LinkedHashMap<>();
        Map<String, String> platformNames = new LinkedHashMap<>();
        Map<String, List<String>> platformRequired = new LinkedHashMap<>();
        try {
            Map<String, JsonNode> hits = queryPlatformVersions(scans);
            for (JarScan item : scans) {
                JsonNode version = hits.get(item.sha1());
                if (version == null || item.modId().isBlank()) {
                    continue;
                }
                String pid = version.path("project_id").asText("");
                if (pid.isBlank()) {
                    continue;
                }
                platformIds.put(item.modId(), pid);
                platformNames.putIfAbsent(pid, version.path("name").asText(pid));
                List<String> required = new ArrayList<>();
                for (JsonNode dep : version.path("dependencies")) {
                    if ("required".equalsIgnoreCase(dep.path("dependency_type").asText())) {
                        String depPid = dep.path("project_id").asText("");
                        if (!depPid.isBlank()) {
                            required.add(depPid);
                        }
                    }
                }
                if (!required.isEmpty()) {
                    platformRequired.put(pid, required);
                }
            }
        } catch (Exception ex) {
            // 平台反查只是渠道之一，失败不影响声明解析与类引用扫描
            log.info("Modrinth sha1 反查失败（不影响其余渠道）：{}", brief(ex.getMessage()));
        }
        return new Scan(installed, deps, paths, platformIds, platformNames, platformRequired,
                providedDirs, references);
    }

    /** 深读一个 jar：元数据 + sha1 + class 目录 + 类引用。 */
    private JarScan readJarDeep(Path jar) {
        String sha1 = sha1Of(jar);
        Set<String> dirs = new LinkedHashSet<>();
        Set<String> refs = new LinkedHashSet<>();
        JarMeta meta = JarMeta.empty();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (java.util.Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    int slash = name.lastIndexOf('/');
                    if (slash > 0) {
                        dirs.add(name.substring(0, slash));
                    }
                }
            }
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                try (InputStream in = zip.getInputStream(fabric)) {
                    JsonNode node = mapper.readTree(in);
                    List<Dep> list = new ArrayList<>();
                    node.path("depends").fields().forEachRemaining(e ->
                            list.add(new Dep(e.getKey(), e.getValue().asText(""), true)));
                    meta = new JarMeta(node.path("id").asText(""), node.path("version").asText(""), list);
                }
            } else {
                for (String tomlName : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                    ZipEntry entry = zip.getEntry(tomlName);
                    if (entry != null) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            meta = parseToml(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                        break;
                    }
                }
            }
            // 类引用扫描：对 class 字节流直接正则，无完整字节码解析
            for (java.util.Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (data.length > 3_000_000) {
                    continue; // 异常巨大的 class 跳过，避免极端性能问题
                }
                Matcher m = CLASS_REF.matcher(new String(data, StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    refs.add(m.group(1));
                }
            }
        } catch (Exception ex) {
            log.debug("读取 Mod 元数据失败（{}）：{}", jar.getFileName(), ex.getMessage());
        }
        return new JarScan(jar, meta.modId(), meta.version(), meta.deps(), sha1, dirs, refs);
    }

    private String sha1Of(Path jar) {
        return sha1Static(jar);
    }

    /** 批量 sha1 反查 Modrinth 版本信息，返回 sha1 -> version JSON。 */
    private Map<String, JsonNode> queryPlatformVersions(List<JarScan> scans) {
        Map<String, JsonNode> result = new HashMap<>();
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        for (JarScan item : scans) {
            if (!item.sha1().isBlank()) {
                hashes.add(item.sha1());
            }
        }
        List<String> all = new ArrayList<>(hashes);
        for (int from = 0; from < all.size(); from += HASH_BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + HASH_BATCH));
            try {
                String body = mapper.writeValueAsString(Map.of("hashes", batch, "algorithm", "sha1"));
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("User-Agent", properties.getLauncherName() + "/" + properties.getLauncherVersion()
                        + " (mod dependency resolver)");
                ResponseEntity<String> response = restTemplate.exchange(
                        URI.create(MODRINTH + "/version_files"), HttpMethod.POST,
                        new HttpEntity<>(body, headers), String.class);
                JsonNode map = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
                map.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
                // 整批一个都没命中，正常
            } catch (Exception ex) {
                log.debug("批量反查 sha1 失败：{}", brief(ex.getMessage()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 类引用归属

    /** 引用类是否属于环境包（JDK / MC / 加载器 / 自带三方库）。 */
    private static boolean isEnvClass(String ref) {
        for (String prefix : ENV_PREFIXES) {
            if (ref.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引用类是否由已装 Mod 提供：取它的各级目录前缀，任一命中已装 class 目录即归属。
     * 例如 {@code net/exmo/exmodifier/util/ExUtil} 会在 exmodifier 已装时命中其目录
     * {@code net/exmo/exmodifier/util}。
     */
    private static boolean ownedByInstalled(String ref, Set<String> providedDirs) {
        int end = ref.lastIndexOf('/');
        while (end > 0) {
            if (providedDirs.contains(ref.substring(0, end))) {
                return true;
            }
            end = ref.lastIndexOf('/', end - 1);
        }
        return false;
    }

    /**
     * 缺口包：引用类去掉最后一段（类名）后取前 4 段。
     * {@code dev/ftb/mods/ftbquests/team/Team} -> {@code dev/ftb/mods/ftbquests}。
     */
    private static String gapPkgOf(String ref) {
        String pkg = ref.contains("/")
                ? ref.substring(0, ref.lastIndexOf('/'))
                : ref;
        String[] parts = pkg.split("/");
        if (parts.length < 2) {
            return null;
        }
        int keep = Math.min(4, parts.length);
        return String.join("/", java.util.Arrays.copyOfRange(parts, 0, keep));
    }

    /** 缺口包按前 3 段分组去重用（同一库的不同子包视为一组）。 */
    private static String groupOf(String pkg) {
        String[] parts = pkg.split("/");
        return String.join("/", java.util.Arrays.copyOfRange(parts, 0, Math.min(3, parts.length)));
    }

    /** 缺口包是否已被已装目录覆盖（目录等于它、是它的前缀或它本身是某目录前缀）。 */
    private static boolean coveredBy(String pkg, Set<String> providedDirs) {
        for (String dir : providedDirs) {
            if (dir.equals(pkg) || dir.startsWith(pkg + "/") || pkg.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ TOML 解析

    /**
     * 解析 Forge 系 mods.toml：{@code [[mods]]} 段给自身 id/version，{@code [[dependencies.x]]} 段给依赖。
     */
    JarMeta parseToml(String toml) {
        String modId = "";
        String version = "";
        List<Dep> deps = new ArrayList<>();
        Matcher sections = SECTION.matcher(toml);
        while (sections.find()) {
            String section = sections.group(1).trim();
            String body = sections.group(2);
            if ("mods".equals(section)) {
                if (modId.isBlank()) {
                    modId = stringValue(body, "modId");
                    version = stringValue(body, "version");
                }
            } else if (section.startsWith("dependencies.")) {
                String depId = stringValue(body, "modId");
                if (depId.isBlank()) {
                    continue;
                }
                deps.add(new Dep(depId, stringValue(body, "versionRange"),
                        boolValue(body, "mandatory", true)));
            }
        }
        return new JarMeta(modId, version, deps);
    }

    private static String stringValue(String body, String key) {
        Matcher matcher = Pattern.compile(String.format(STRING_VALUE.pattern(), key)).matcher(body);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** 取一个 TOML 值的原文（去引号、去行尾注释），不带引号的布尔/数字也能拿到。 */
    private static String rawValue(String body, String key) {
        Matcher matcher = Pattern.compile(String.format(RAW_VALUE.pattern(), key)).matcher(body);
        if (!matcher.find()) {
            return "";
        }
        String value = matcher.group(1).trim();
        int comment = value.indexOf('#');
        if (comment >= 0) {
            value = value.substring(0, comment).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    /**
     * 读布尔字段。
     *
     * <p>Forge 的 {@code mandatory} 在 mods.toml 里是<b>不带引号</b>的布尔值
     * （{@code mandatory=false}），只按 {@code key="..."} 匹配会取到空串，
     * 进而把可选依赖误判成必装。
     */
    private static boolean boolValue(String body, String key, boolean fallback) {
        String value = rawValue(body, key);
        if (value.isBlank()) {
            return fallback;
        }
        return !FALSE_WORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 按 Missing 的类型选择安装路径，成功写入 mods 目录返回 true。
     *
     * <p>关键保险：按 modId 补的候选下载完先读 jar 里的 modId，对不上就丢弃换下一个；
     * 按缺口包补的候选要求 jar 里真实存在该包。装错 Mod 会导致更难排查的崩溃。
     */
    private boolean install(Path modsDir, Missing item, String loader, String mcVersion) {
        // 版本不满足的旧 jar 先移走（备份到 .pcl-removed），否则同 modId 两份会冲突
        if (item.upgrade()) {
            moveOldVersion(modsDir, item);
        }
        if (item.platformProjectId() != null) {
            return installPlatform(modsDir, item, loader, mcVersion);
        }
        if (item.gapPackage() != null) {
            return installGap(modsDir, item, loader, mcVersion);
        }
        return installById(modsDir, item, loader, mcVersion);
    }

    private void moveOldVersion(Path modsDir, Missing item) {
        Path oldJar = findJarByModId(modsDir, item.key());
        if (oldJar == null) {
            return;
        }
        try {
            Path backup = modsDir.resolve(".pcl-removed");
            Files.createDirectories(backup);
            Path moved = backup.resolve(oldJar.getFileName());
            Files.move(oldJar, moved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.warn("版本不满足：旧 jar {} 已移到 {}，即将补装满足要求的新版", oldJar.getFileName(), moved);
        } catch (Exception ex) {
            log.warn("移动旧 jar 失败（{}）：{}", oldJar, brief(ex.getMessage()));
        }
    }

    /** 按 jar 内声明的 modId 反查它的文件。 */
    private Path findJarByModId(Path modsDir, String modId) {
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(p -> p.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                Set<String> ids = modIds(jar);
                if (ids.contains(modId)) {
                    return jar;
                }
            }
        } catch (Exception ignored) {
            // 找不到就当作没有
        }
        return null;
    }

    /** 平台直取：project_id -> 项目信息 -> 版本文件。 */
    private boolean installPlatform(Path modsDir, Missing item, String loader, String mcVersion) {
        String facetLoader = "neoforge".equals(loader) ? "neoforge" : loader;
        JsonNode project = tryJson(MODRINTH + "/project/" + encode(item.platformProjectId()));
        if (project == null || !supports(project, facetLoader, mcVersion)) {
            return false;
        }
        Candidate candidate = pickVersion(project.path("slug").asText(""), facetLoader, mcVersion);
        if (candidate == null) {
            return false;
        }
        return downloadCandidate(modsDir, candidate, item.key(), null);
    }

    /** 类引用缺口：把缺口包转成搜索词找候选，下载后校验 jar 确实包含该包。 */
    private boolean installGap(Path modsDir, Missing item, String loader, String mcVersion) {
        String facetLoader = "neoforge".equals(loader) ? "neoforge" : loader;
        int tried = 0;
        for (Candidate candidate : gapCandidates(item.gapPackage(), facetLoader, mcVersion)) {
            // 缺口搜索命中的候选很多是 Create/Thermal 这类大 Mod（错误候选），
            // 而真正的扩展前置一般不超过十几 MB，超大先跳过，避免一轮补齐被拖上半小时
            if (candidate.size() > MAX_GAP_CANDIDATE_BYTES) {
                log.info("缺口 {} 候选 {} 体积 {}MB 过大，跳过", item.gapPackage(),
                        candidate.fileName(), candidate.size() / 1024 / 1024);
                continue;
            }
            if (downloadCandidate(modsDir, candidate, item.key(), item.gapPackage())) {
                return true;
            }
            if (++tried >= 4) {
                break;
            }
        }
        return false;
    }

    /**
     * 缺口包的候选来源：包名尾部有效段转成 slug / 搜索词。
     * {@code dev/ftb/mods/ftbquests} -> ftb-quests / FTB Quests；
     * {@code net/exmo/exmodifier} -> exmodifier。
     */
    private List<Candidate> gapCandidates(String gapPackage, String loader, String mcVersion) {
        // 从包名尾部往前收集有效段（跳过 com/net/dev 等噪音段）
        List<String> segs = new ArrayList<>();
        String[] parts = gapPackage.split("/");
        for (int i = parts.length - 1; i >= 0 && segs.size() < 2; i--) {
            String seg = parts[i].toLowerCase(Locale.ROOT);
            if (!NOISE_SEGMENTS.contains(seg)) {
                segs.add(0, seg);
            }
        }
        if (segs.isEmpty()) {
            segs.add(parts[parts.length - 1].toLowerCase(Locale.ROOT));
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(String.join("-", segs));
        queries.add(String.join(" ", segs));
        if (segs.size() > 1) {
            queries.add(segs.get(segs.size() - 1));
        }
        log.info("缺口包 {} 转搜索词：{}", gapPackage, queries);

        List<Candidate> result = new ArrayList<>();
        // 先按 slug 直查（dev/ftb/mods/ftbquests -> ftb-quests 这种正好就是 slug）
        for (String slugQuery : new LinkedHashSet<>(List.copyOf(queries))) {
            String slug = slugQuery.replace(' ', '-');
            Candidate candidate = bySlug(slug, loader, mcVersion);
            if (candidate != null) {
                result.add(candidate);
                if (result.size() >= 3) {
                    return result;
                }
            }
        }
        for (String slug : searchSlugs(new ArrayList<>(queries), loader, mcVersion)) {
            Candidate candidate = bySlug(slug, loader, mcVersion);
            if (candidate != null) {
                result.add(candidate);
                if (result.size() >= 3) {
                    return result;
                }
            }
        }
        // CurseForge 兜底（cfwidget 免 Key）
        for (String slugQuery : queries) {
            try {
                Candidate curse = fromCfwidget(slugQuery.replace(' ', '-'), loader, mcVersion);
                if (curse != null) {
                    result.add(curse);
                    break;
                }
            } catch (Exception ex) {
                log.info("cfwidget 查询 {} 失败：{}", slugQuery, brief(ex.getMessage()));
            }
        }
        return result;
    }

    /**
     * 按 modId 补装，依次尝试候选来源，成功写入 mods 目录返回 true。
     *
     * <p>关键保险：下载完先读 jar 里的 modId，对不上就丢弃换下一个候选。
     * Modrinth 上同一个 Mod 的 slug 往往与 modId 无关（{@code structure_gel} 对应
     * {@code structure-gel-api}），光靠名字猜会装错，而装错 Mod 会导致更难排查的崩溃。
     */
    private boolean installById(Path modsDir, Missing item, String loader, String mcVersion) {
        String modId = item.key();
        Path tmp = modsDir.resolve(".pcl-dep-" + modId.replaceAll("[^A-Za-z0-9_-]", "_") + ".jar");
        for (Candidate candidate : candidates(modId, loader, mcVersion)) {
            try {
                Files.deleteIfExists(tmp);
                DownloadManager.DownloadSummary summary = downloadManager.downloadAll(List.of(
                        NetFile.of(candidate.url(), tmp, candidate.sha1(), 0,
                                "前置 Mod " + modId, SourceKind.LIBRARY)));
                if (summary.failed() > 0) {
                    log.warn("前置 Mod {} 候选 {} 下载失败：{}", modId,
                            candidate.fileName(), summary.errors());
                    continue;
                }
                Set<String> ids = modIds(tmp);
                if (!ids.contains(modId)) {
                    log.info("候选 {} 里没有 modId {}（实际 {}），丢弃换下一个",
                            candidate.fileName(), modId, ids);
                    Files.deleteIfExists(tmp);
                    continue;
                }
                Path target = modsDir.resolve(safeName(candidate.fileName(), modId + ".jar"));
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("已补装前置 Mod {} -> {}", modId, target.getFileName());
                return true;
            } catch (Exception ex) {
                log.warn("前置 Mod {} 下载异常：{}", modId, ex.getMessage());
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败不影响后续候选
                }
            }
        }
        return false;
    }

    /** 统一下载入口；expectPackage 非 null 时要求 jar 里确实存在该包。 */
    private boolean downloadCandidate(Path modsDir, Candidate candidate, String key, String expectPackage) {
        Path tmp = modsDir.resolve(".pcl-dep-" + key.replaceAll("[^A-Za-z0-9_-]", "_") + ".jar");
        try {
            Files.deleteIfExists(tmp);
            DownloadManager.DownloadSummary summary = downloadManager.downloadAll(List.of(
                    NetFile.of(candidate.url(), tmp, candidate.sha1(), 0,
                            "前置 Mod " + key, SourceKind.LIBRARY)));
            if (summary.failed() > 0) {
                log.warn("前置 {} 候选 {} 下载失败：{}", key, candidate.fileName(), summary.errors());
                Files.deleteIfExists(tmp);
                return false;
            }
            // 先校验缺口包再查重：搜索命中的候选可能是别的 Mod（如搜 config 命中
            // cloth-config），它确实已在 mods 目录但解决不了当前缺口，不能当成功
            if (expectPackage != null && !jarContainsPackage(tmp, expectPackage)) {
                log.info("候选 {} 里没有包 {}，丢弃换下一个", candidate.fileName(), expectPackage);
                Files.deleteIfExists(tmp);
                return false;
            }
            // 声明链与平台链可能指向同一个前置，按内容去重，避免重复落盘
            if (alreadyInstalled(modsDir, tmp)) {
                log.info("前置 {} 的候选 {} 已在 mods 目录（内容一致），跳过", key, candidate.fileName());
                Files.deleteIfExists(tmp);
                return true;
            }
            Path target = modsDir.resolve(safeName(candidate.fileName(), key + ".jar"));
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已补装前置 {} -> {}", key, target.getFileName());
            return true;
        } catch (Exception ex) {
            log.warn("前置 {} 下载异常：{}", key, ex.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响后续候选
            }
            return false;
        }
    }

    /** mods 目录里是否已存在内容完全相同（sha1 一致）的 jar。 */
    private static boolean alreadyInstalled(Path modsDir, Path tmp) {
        String sha1 = sha1Static(tmp);
        if (sha1.isEmpty()) {
            return false;
        }
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(p -> p.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                if (sha1Static(jar).equals(sha1)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 查不了就当没有
        }
        return false;
    }

    private static String sha1Static(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** jar 里是否存在指定包目录（含其子包）。 */
    private static boolean jarContainsPackage(Path jar, String pkg) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String prefix = pkg.endsWith("/") ? pkg : pkg + "/";
            for (java.util.Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                if (it.nextElement().getName().startsWith(prefix)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 读不了当作没有
        }
        return false;
    }

    /**
     * 候选清单，按可信度排序：
     * <ol>
     *   <li>slug 直接等于 modId（含下划线转连字符、驼峰拆分）；</li>
     *   <li>按常见词尾切分出的 slug（farmersdelight → farmers-delight）；</li>
     *   <li>搜索结果的头部候选（slug 与 modId 无关时靠这里，如 structure-gel-api）；</li>
     *   <li>CurseForge。</li>
     * </ol>
     */
    private List<Candidate> candidates(String modId, String loader, String mcVersion) {
        String facetLoader = "neoforge".equals(loader) ? "neoforge" : loader;
        List<Candidate> result = new ArrayList<>();
        Set<String> tried = new LinkedHashSet<>();

        for (String slug : directSlugs(modId)) {
            if (!tried.add(slug)) {
                continue;
            }
            Candidate candidate = bySlug(slug, facetLoader, mcVersion);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        for (String slug : wordSplits(modId)) {
            if (!tried.add(slug)) {
                continue;
            }
            Candidate candidate = bySlug(slug, facetLoader, mcVersion);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        // 前面的都没命中时才走搜索，避免大量无谓请求
        if (result.isEmpty()) {
            for (String slug : searchSlugs(new ArrayList<>(queriesFor(modId, facetLoader, mcVersion)),
                    facetLoader, mcVersion)) {
                if (!tried.add(slug)) {
                    continue;
                }
                Candidate candidate = bySlug(slug, facetLoader, mcVersion);
                if (candidate != null) {
                    result.add(candidate);
                }
            }
        }
        if (result.isEmpty()) {
            try {
                Candidate curse = fromCurseForge(modId, facetLoader, mcVersion);
                if (curse != null) {
                    result.add(curse);
                }
            } catch (Exception ex) {
                // CurseForge 在国内常被 Cloudflare 拦（403），拿不到就算了，不能影响整体补齐
                log.info("CurseForge 不可用（{}）：{}", modId, brief(ex.getMessage()));
            }
        }
        return result.size() > 3 ? result.subList(0, 3) : result;
    }

    /** 按 modId 生成搜索查询词。 */
    private static Set<String> queriesFor(String modId, String loader, String mcVersion) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(modId);
        queries.add(modId.replace('_', ' '));
        // 拆词后转成空格版：roughlyenoughitems → "roughlyenough items"，这样才搜得到 REI
        for (String split : wordSplits(modId)) {
            queries.add(split.replace('-', ' '));
            if (queries.size() >= 5) {
                break;
            }
        }
        return queries;
    }

    /** 最可能的三种写法：原样、下划线转连字符、驼峰拆分。 */
    private static List<String> directSlugs(String modId) {
        String lower = modId.toLowerCase(Locale.ROOT);
        String kebab = modId.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT).replace('_', '-');
        return new java.util.LinkedHashSet<>(List.of(lower, lower.replace('_', '-'), kebab))
                .stream().filter(s -> !s.isBlank()).toList();
    }

    /**
     * 按常见词尾切分：{@code farmersdelight → farmers-delight}、
     * {@code legendreliclib → legend-relic-lib}。
     */
    private static List<String> wordSplits(String modId) {
        String text = modId.toLowerCase(Locale.ROOT).replace('_', '-');
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tail : TAIL_WORDS) {
            if (!text.endsWith(tail) || text.length() <= tail.length() + 2) {
                continue;
            }
            String head = text.substring(0, text.length() - tail.length());
            String joined = (head.endsWith("-") ? head : head + "-") + tail;
            result.add(joined);
            for (String inner : TAIL_WORDS) {
                if (!head.endsWith(inner) || head.length() <= inner.length() + 2) {
                    continue;
                }
                String outer = head.substring(0, head.length() - inner.length());
                String prefix = (outer.endsWith("-") ? outer : outer + "-") + inner;
                result.add(prefix + "-" + tail);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 搜索结果里适配当前加载器与游戏版本的 slug（最多 3 个）。
     *
     * <p>只收与查询词<b>相关</b>的结果：搜「cataclysm」时排前面的常有
     * goety-cataclysm、cataclysm-dimension 这类衍生包，而本体叫
     * {@code l_enders-cataclysm}；用子序列匹配（查询词每个字符按序出现在
     * slug/标题里即可）能放行 {@code lendercataclysm → lenderscataclysm}、
     * 同时排除 {@code lendercataclysm → goetycataclysm}。误放行的还有下载后的
     * jar modId / 缺口包校验兜底，不会装错。
     */
    private List<String> searchSlugs(List<String> queries, String loader, String mcVersion) {
        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        for (String query : queries) {
            String key = norm(query);
            JsonNode hits = getJson(MODRINTH + "/search?limit=8&index=relevance&query="
                    + encode(query) + "&facets=" + encode("[[\"project_type:mod\"]]")).path("hits");
            for (JsonNode hit : hits) {
                String slug = hit.path("slug").asText("");
                if (slug.isBlank() || !hitSupports(hit, loader, mcVersion)) {
                    continue;
                }
                if (!isSubsequence(key, norm(hit.path("title").asText("")))
                        && !isSubsequence(key, norm(slug))) {
                    continue;
                }
                slugs.add(slug);
                if (slugs.size() >= 3) {
                    return new ArrayList<>(slugs);
                }
            }
        }
        return new ArrayList<>(slugs);
    }

    /** 判断 key 的每个字符是否按顺序出现在 target 中（大小写与符号已由 norm 归一）。 */
    private static boolean isSubsequence(String key, String target) {
        if (key.isEmpty()) {
            return false;
        }
        int i = 0;
        for (int j = 0; j < target.length() && i < key.length(); j++) {
            if (target.charAt(j) == key.charAt(i)) {
                i++;
            }
        }
        return i == key.length();
    }

    private static boolean hitSupports(JsonNode hit, String loader, String mcVersion) {
        boolean loaderOk = false;
        for (JsonNode category : hit.path("categories")) {
            if (loader.equalsIgnoreCase(category.asText())) {
                loaderOk = true;
                break;
            }
        }
        if (!loaderOk) {
            return false;
        }
        for (JsonNode version : hit.path("versions")) {
            if (mcVersion.equalsIgnoreCase(version.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 按 slug 取项目并校验加载器/版本，通过则返回该项目的下载文件。 */
    private Candidate bySlug(String slug, String loader, String mcVersion) {
        JsonNode project = tryJson(MODRINTH + "/project/" + encode(slug));
        if (project == null || !supports(project, loader, mcVersion)) {
            return null;
        }
        return pickVersion(project.path("slug").asText(slug), loader, mcVersion);
    }

    private static boolean supports(JsonNode project, String loader, String mcVersion) {
        boolean loaderOk = false;
        for (JsonNode item : project.path("loaders")) {
            if (loader.equalsIgnoreCase(item.asText())) {
                loaderOk = true;
                break;
            }
        }
        if (!loaderOk) {
            return false;
        }
        for (JsonNode item : project.path("game_versions")) {
            if (mcVersion.equalsIgnoreCase(item.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 取该项目下适配指定加载器与游戏版本的版本文件，优先正式版。 */
    private Candidate pickVersion(String slug, String loader, String mcVersion) {
        JsonNode versions = getJson(MODRINTH + "/project/" + encode(slug) + "/version?loaders="
                + encode("[\"" + loader + "\"]") + "&game_versions=" + encode("[\"" + mcVersion + "\"]"));
        if (!versions.isArray() || versions.isEmpty()) {
            return null;
        }
        JsonNode chosen = versions.get(0);
        for (JsonNode version : versions) {
            if ("release".equals(version.path("version_type").asText())) {
                chosen = version;
                break;
            }
        }
        JsonNode files = chosen.path("files");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        JsonNode file = files.get(0);
        for (JsonNode item : files) {
            if (item.path("primary").asBoolean(false)) {
                file = item;
                break;
            }
        }
        String url = file.path("url").asText("");
        if (url.isBlank()) {
            return null;
        }
        return new Candidate(url, file.path("hashes").path("sha1").asText(""),
                file.path("filename").asText(slug + ".jar"), file.path("size").asLong(-1));
    }

    /** 404 等不存在的情况返回 null，其它错误照常抛出。 */
    private JsonNode tryJson(String url) {
        try {
            return getJson(url);
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw ex;
        }
    }

    /** 读 jar 内声明的全部 modId，用于校验下载到的候选是否正确。 */
    private Set<String> modIds(Path jar) {
        Set<String> ids = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                try (InputStream in = zip.getInputStream(fabric)) {
                    ids.add(mapper.readTree(in).path("id").asText(""));
                }
            }
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    // 只认 [[mods]] 段：[[dependencies.x]] 段里的 modId 是「依赖谁」，
                    // 拿它当自身 id 会让错误候选蒙混过关
                    Matcher matcher = Pattern.compile("\\[\\[mods\\]\\]([\\s\\S]*?)(?=\\[\\[|$)").matcher(toml);
                    while (matcher.find()) {
                        String id = stringValue(matcher.group(1), "modId");
                        if (!id.isBlank()) {
                            ids.add(id);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("读取候选 Mod 元数据失败（{}）：{}", jar.getFileName(), ex.getMessage());
        }
        ids.remove("");
        return ids;
    }

    /**
     * CurseForge 兜底。
     *
     * <p>CF 官网（www.curseforge.com）在启动器里会被 Cloudflare 直接挑战，只有官方 API
     * {@code api.curseforge.com} 可达，而它要求 {@code x-api-key}。Key 由用户在设置里填，
     * 没填就走 cfwidget 免 Key 镜像（实际能覆盖绝大部分 Mod），最后才试 curse.tools。
     */
    private Candidate fromCurseForge(String modId, String loader, String mcVersion) {
        String apiKey = settingsService.raw().advanced.curseForgeApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            Candidate keyless = null;
            try {
                keyless = fromCfwidget(modId, loader, mcVersion);
            } catch (Exception ex) {
                log.info("cfwidget 查询 {} 失败：{}", modId, brief(ex.getMessage()));
            }
            return keyless != null ? keyless : fromCurseTools(modId, loader, mcVersion);
        }
        int loaderType = loaderType(loader);
        JsonNode mods = getJson(CURSE_API + "/mods/search?gameId=432&classId=6&pageSize=10"
                + "&sortField=2&sortOrder=desc&gameVersion=" + encode(mcVersion)
                + "&modLoaderType=" + loaderType + "&searchFilter=" + encode(modId),
                Map.of("x-api-key", apiKey)).path("data");
        long projectId = -1;
        for (JsonNode mod : mods) {
            if (norm(mod.path("slug").asText("")).equals(norm(modId))
                    || norm(mod.path("name").asText("")).equals(norm(modId))) {
                projectId = mod.path("id").asLong(-1);
                break;
            }
        }
        if (projectId < 0) {
            return null;
        }
        JsonNode files = getJson(CURSE_API + "/mods/" + projectId + "/files?pageSize=5"
                + "&gameVersion=" + encode(mcVersion) + "&modLoaderType=" + loaderType,
                Map.of("x-api-key", apiKey)).path("data");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        JsonNode file = files.get(0);
        String name = file.path("fileName").asText(modId + ".jar");
        String download = file.path("downloadUrl").asText("");
        if (download.isBlank()) {
            // 作者禁止第三方分发时 downloadUrl 为空，只能拼 CDN 规则
            long fileId = file.path("id").asLong();
            download = FORGECDN + "/" + (fileId / 1000) + "/" + (fileId % 1000) + "/" + encodePath(name);
        }
        return new Candidate(download, file.path("hashes").path(0).path("value").asText(""), name, -1);
    }

    /**
     * 用 cfwidget 拿 CF 项目元数据（免 Key）。
     *
     * <p>很多 Mod（如 legendreliclib、slashblade）只在 CurseForge 发布，Modrinth 搜不到，
     * 而官方 API 需要 Key。cfwidget 提供 {@code /minecraft/mc-mods/{slug}} 的只读 JSON，
     * 里面有项目 id、标题和文件列表（含 fileId、文件名、适用的版本/加载器标签），
     * 配合 {@code mediafilez.forgecdn.net} 的 ID 拼链规则就能直接下载。
     *
     * <p>安全边界不变：slug 或标题归一化后必须与查询词完全相等，且下载后仍要校验
     * jar 内 modId 或缺口包。
     */
    private Candidate fromCfwidget(String slugQuery, String loader, String mcVersion) {
        LinkedHashSet<String> slugs = new LinkedHashSet<>(directSlugs(slugQuery));
        slugs.addAll(wordSplits(slugQuery));
        for (String slug : slugs) {
            JsonNode project = tryJson(CFWIDGET + "/minecraft/mc-mods/" + encode(slug));
            if (project == null) {
                continue;
            }
            boolean matched = norm(project.path("title").asText("")).equals(norm(slugQuery))
                    || norm(slug).equals(norm(slugQuery));
            if (!matched) {
                continue;
            }
            Candidate candidate = pickCfwidgetFile(project, loader, mcVersion);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** 从 cfwidget 的 files 里挑一个适配当前加载器与游戏版本的文件。 */
    private static Candidate pickCfwidgetFile(JsonNode project, String loader, String mcVersion) {
        String loaderLabel = loaderLabel(loader);
        JsonNode files = project.path("files");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        Candidate fallback = null;
        for (JsonNode file : files) {
            boolean versionOk = false;
            boolean loaderOk = false;
            for (JsonNode tag : file.path("versions")) {
                String text = tag.asText("");
                if (text.equalsIgnoreCase(mcVersion)) {
                    versionOk = true;
                } else if (text.equalsIgnoreCase(loaderLabel)) {
                    loaderOk = true;
                }
            }
            if (!versionOk || !loaderOk) {
                continue;
            }
            long fileId = file.path("id").asLong(-1);
            String name = file.path("name").asText("");
            if (fileId < 0 || name.isBlank()) {
                continue;
            }
            Candidate candidate = new Candidate(
                    FORGECDN + "/" + (fileId / 1000) + "/" + (fileId % 1000) + "/" + encodePath(name),
                    "", name, -1);
            if ("release".equalsIgnoreCase(file.path("type").asText(""))) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private static String loaderLabel(String loader) {
        return switch (loader) {
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            default -> "Forge";
        };
    }

    /** 无 Key 时的旧通道，国内基本被 Cloudflare 拦，只当兜底。 */
    private Candidate fromCurseTools(String modId, String loader, String mcVersion) {
        int loaderType = loaderType(loader);
        JsonNode mods = getJson(CURSE_TOOLS + "/mods/search?gameId=432&classId=6&pageSize=10"
                + "&sortField=2&sortOrder=desc&gameVersion=" + encode(mcVersion)
                + "&modLoaderType=" + loaderType + "&searchFilter=" + encode(modId)).path("data");
        long projectId = -1;
        for (JsonNode mod : mods) {
            if (norm(mod.path("slug").asText("")).equals(norm(modId))) {
                projectId = mod.path("id").asLong(-1);
                break;
            }
        }
        if (projectId < 0) {
            return null;
        }
        JsonNode files = getJson(CURSE_TOOLS + "/mods/" + projectId + "/files?pageSize=5"
                + "&gameVersion=" + encode(mcVersion) + "&modLoaderType=" + loaderType).path("data");
        if (!files.isArray() || files.isEmpty()) {
            return null;
        }
        JsonNode file = files.get(0);
        String name = file.path("fileName").asText(modId + ".jar");
        String download = file.path("downloadUrl").asText("");
        if (download.isBlank()) {
            download = CURSE_CDN + "/" + projectId + "/files/" + file.path("id").asLong() + "/download";
        }
        return new Candidate(download, file.path("hashes").path(0).path("value").asText(""), name, -1);
    }

    private static int loaderType(String loader) {
        return "fabric".equals(loader) ? 4 : "neoforge".equals(loader) ? 6 : 1;
    }

    // ------------------------------------------------------------------ Maven 版本区间

    /**
     * 已装版本是否满足声明的版本区间（Maven 区间语法的常用子集）。
     *
     * <p>支持 {@code [3.0,)}、{@code [1.20.1,1.21)}、{@code (,2.0]}、用 {@code ||} 分隔的多段，
     * 以及无括号的裸版本（Forge 把它当软约束，任何版本都接受，这里同样直接放行）。
     */
    static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank() || "*".equals(range.trim())
                || "-".equals(range.trim()) || range.trim().equalsIgnoreCase("any")) {
            return true;
        }
        String text = range.trim();
        if (!text.startsWith("[") && !text.startsWith("(")) {
            return true; // 裸版本是软约束
        }
        for (String part : text.split("\\|\\|")) {
            if (satisfiesOne(version, part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean satisfiesOne(String version, String range) {
        if (range.isEmpty()) {
            return true;
        }
        boolean lowerInclusive = range.startsWith("[");
        boolean upperInclusive = range.endsWith("]");
        String inner = range.substring(1, range.length() - 1).trim();
        String lower = "";
        String upper = "";
        int comma = inner.indexOf(',');
        if (comma < 0) {
            // [1.20.1] 单值精确区间
            return compareVersion(version, inner) == 0;
        }
        lower = inner.substring(0, comma).trim();
        upper = inner.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int cmp = compareVersion(version, lower);
            if (lowerInclusive ? cmp < 0 : cmp <= 0) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int cmp = compareVersion(version, upper);
            if (upperInclusive ? cmp > 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 宽松版本比较：按数字段逐位比（{@code 2.4-Fix} → 2.4 < 3.0）。
     * 只用于前置版本区间判断，不追求完整语义化版本语义。
     */
    static int compareVersion(String a, String b) {
        int[] left = segments(a);
        int[] right = segments(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    static boolean versionLess(String have, String min) {
        return compareVersion(have, min) < 0;
    }

    private static int[] segments(String version) {
        String cleaned = version == null ? "" : version.trim().replaceAll("[^0-9.]", "");
        if (cleaned.isBlank()) {
            return new int[1];
        }
        String[] parts = cleaned.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static boolean isBuiltin(String modId) {
        return modId == null || modId.isBlank() || BUILTIN.contains(modId.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode getJson(String url) {
        return getJson(url, Map.of());
    }

    private JsonNode getJson(String url, Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        extraHeaders.forEach(headers::set);
        headers.set("User-Agent", properties.getLauncherName() + "/" + properties.getLauncherVersion()
                + " (mod dependency resolver)");
        headers.set("Accept", "application/json");
        // 必须用 URI 而不是 String：String 形式会被当成模板再编码一次，
        // facets 里的 %5B / %22 会变成 %255B / %2522，服务端就解析不出过滤条件
        ResponseEntity<String> response = restTemplate.exchange(java.net.URI.create(url), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        try {
            return mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
        } catch (IOException ex) {
            throw new IllegalStateException("解析接口响应失败：" + url, ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** URL 路径段编码：空格要变 %20 而不是 +，否则 CDN 上带空格的文件名会 404。 */
    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    /** 归一化用于匹配：忽略大小写、连字符与下划线（cloth-config 对齐 cloth_config）。 */
    private static String norm(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String safeName(String name, String fallback) {
        String result = name == null || name.isBlank() ? fallback : name;
        return result.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 异常信息可能带整页 HTML，截断后再打日志。 */
    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String single = message.replaceAll("\\s+", " ").strip();
        return single.length() > 160 ? single.substring(0, 160) + "…" : single;
    }
}
