package com.meloong.pcl.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.VersionResolver;
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
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包（压缩包）一键安装。
 *
 * <p>支持三种常见格式：
 * <ul>
 *   <li><b>Modrinth（.mrpack / zip）</b>：{@code modrinth.index.json} 描述依赖与文件清单，
 *       文件自带直链，直接下载；</li>
 *   <li><b>CurseForge（zip）</b>：{@code manifest.json} 只有 projectID / fileID，
 *       通过 CurseForge 公开接口解析出下载地址；{@code overrides/} 目录原样解压；</li>
 *   <li><b>裸 zip</b>：内含 {@code mods/}、{@code config/} 等目录的普通压缩包，
 *       解压到指定实例（无版本清单，装不进新实例）。</li>
 * </ul>
 *
 * <p>流程：解析清单 → 解压 overrides → 三条流水线并行推进
 * （① Mod 下载线 ② 游戏本体线：原版 / 加载器 / 版本 JSON ③ 前置检查线）→ 核对收尾。
 */
@Service
public class ModpackInstaller {

    private static final Logger log = LoggerFactory.getLogger(ModpackInstaller.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) pcl-java21/2.99.0";

    private final PclProperties properties;
    private final ObjectMapper mapper;
    private final LoaderInstaller loaderInstaller;
    private final VersionManifestService manifestService;
    private final DownloadManager downloadManager;
    private final VersionResolver versionResolver;
    private final ModDependencyResolver dependencyResolver;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ModpackInstaller(PclProperties properties, ObjectMapper mapper,
                            LoaderInstaller loaderInstaller, VersionManifestService manifestService,
                            DownloadManager downloadManager, VersionResolver versionResolver,
                            ModDependencyResolver dependencyResolver,
                            com.meloong.pcl.settings.SettingsService settingsService) {
        this.properties = properties;
        this.mapper = mapper;
        this.loaderInstaller = loaderInstaller;
        this.manifestService = manifestService;
        this.downloadManager = downloadManager;
        this.versionResolver = versionResolver;
        this.dependencyResolver = dependencyResolver;
        this.settingsService = settingsService;
    }

    private final com.meloong.pcl.settings.SettingsService settingsService;

    /** cfwidget 免 Key 通道地址。 */
    private static final String CFWIDGET = "https://api.cfwidget.com";

    /**
     * cfwidget 项目元数据缓存：一个整合包的 241 个文件往往只来自几十个项目，
     * 每个项目只查一次（cfwidget 有速率限制，重复查询会被排队限流）。
     */
    private final Map<Long, JsonNode> cfProjectCache = new ConcurrentHashMap<>();

    /** 解析出的整合包信息。 */
    public record PackInfo(String name, String format, String mcVersion, String loader,
                           String loaderVersion, int fileCount) {
    }

    // ------------------------------------------------------------ 安装流程

    /**
     * 整合包安装的固定流水线。
     *
     * <p>解压完成后，三条流水线并行推进：① Mod 下载线解析清单并下载整合包内容；
     * ② 游戏本体线装原版 JSON / 支持库 / 资源文件与加载器；③ 前置检查线等 ① 完成后
     * 扫描 mods 目录补装缺失前置（与 ② 并行）。最后统一核对游戏支持库收尾。
     */
    public static final List<String> STAGES = List.of(
            "处理整合包",
            "解压整合包文件",
            "并行下载 Mod / 游戏本体 / 前置检查",
            "完成安装");

    /** 各阶段在总进度条上的起点，下标 = 阶段号；最后一个元素表示结束。 */
    private static final int[] STAGE_PERCENT = {0, 0, 2, 8, 97, 99};

    /** 声明进入某阶段，并把进度条推到该阶段起点。 */
    private static void enter(InstallJobService.Progress progress, int stageNo) {
        String name = STAGES.get(stageNo - 1);
        progress.stage(stageNo, STAGES.size(), name);
        progress.update(STAGE_PERCENT[stageNo], name + "…");
    }

    /**
     * 把一段工作包装成某个阶段（或连续若干阶段）的进度。
     *
     * <p>子进度里的 {@code percent} 是相对值，这里映射到全局区间；子模块自己声明的
     * {@code stage(1..span)} 也会被翻译成全局步骤号，所以 {@link LoaderInstaller} 的
     * 5 步能直接落在第 8~12 步上。
     */
    private static InstallJobService.Progress band(InstallJobService.Progress target,
                                                   int firstStage, int span) {
        int fromPercent = STAGE_PERCENT[firstStage];
        int toPercent = STAGE_PERCENT[Math.min(firstStage + span, STAGE_PERCENT.length - 1)];
        return new InstallJobService.Progress() {
            @Override
            public void update(int percent, String message) {
                int clamped = Math.max(0, Math.min(100, percent));
                int mapped = fromPercent
                        + (int) Math.round((toPercent - fromPercent) * clamped / 100.0);
                target.update(mapped, message);
            }

            @Override
            public void stage(int index, int total, String name) {
                int mappedIndex = firstStage + index - 1;
                if (mappedIndex < firstStage || mappedIndex >= firstStage + span) {
                    return;
                }
                target.stage(mappedIndex, STAGES.size(), STAGES.get(mappedIndex - 1));
            }
        };
    }

    // ------------------------------------------------------------ 解析

    /** 识别整合包格式并解析清单（不下载）。 */
    public PackInfo inspect(Path packZip) throws IOException {
        try (ZipFile zip = new ZipFile(packZip.toFile())) {
            JsonNode modrinth = entryJson(zip, "modrinth.index.json");
            if (modrinth != null) {
                return modrinthInfo(modrinth);
            }
            JsonNode manifest = entryJson(zip, "manifest.json");
            if (manifest != null && manifest.has("minecraft")) {
                return manifestInfo(manifest);
            }
            boolean plain = zip.stream().anyMatch(entry -> entry.getName().startsWith("mods/")
                    || entry.getName().startsWith(".minecraft/mods/"));
            if (plain) {
                String name = packZip.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                return new PackInfo(name, "plain", "", "", "", 0);
            }
            throw new IllegalArgumentException("无法识别的整合包：缺少 modrinth.index.json / manifest.json / mods 目录");
        }
    }

    private PackInfo modrinthInfo(JsonNode index) {
        JsonNode deps = index.path("dependencies");
        String mc = deps.path("minecraft").asText("");
        String loader = "";
        String loaderVersion = "";
        for (Iterator<Map.Entry<String, JsonNode>> it = deps.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> dep = it.next();
            String key = dep.getKey().toLowerCase(Locale.ROOT);
            if (key.equals("minecraft")) {
                continue;
            }
            if (key.equals("forge") || key.equals("neoforge")) {
                loader = key;
                loaderVersion = dep.getValue().asText();
                break;
            }
            if (key.equals("fabric-loader") || key.equals("quilt-loader")) {
                loader = "fabric";
                loaderVersion = dep.getValue().asText();
                break;
            }
        }
        return new PackInfo(index.path("name").asText("整合包"), "modrinth",
                mc, loader, loaderVersion, index.path("files").size());
    }

    private PackInfo manifestInfo(JsonNode manifest) {
        JsonNode mcNode = manifest.path("minecraft");
        String mc = mcNode.path("version").asText("");
        String loader = "";
        String loaderVersion = "";
        for (JsonNode ml : mcNode.path("modLoaders")) {
            if (!ml.path("primary").asBoolean(false)) {
                continue;
            }
            String id = ml.path("id").asText();
            int dash = id.indexOf('-');
            if (dash > 0) {
                loader = id.substring(0, dash).toLowerCase(Locale.ROOT);
                loaderVersion = id.substring(dash + 1);
            }
            break;
        }
        return new PackInfo(manifest.path("name").asText("整合包"), "curseforge",
                mc, loader, loaderVersion, manifest.path("files").size());
    }

    // ------------------------------------------------------------ 安装

    /**
     * 安装整合包。
     *
     * @param displayName    实例名兜底来源（上传的原始文件名）；带清单的整合包用清单里的包名
     * @param targetInstance 裸 zip 且无法自动识别时的兜底解压目标；带清单的整合包忽略该参数
     * @param conflict       同名实例已存在时的策略：{@code overwrite} 覆盖、{@code rename} 另建，
     *                       留空则沿用「自动追加序号」
     */
    public Map<String, Object> install(Path packZip, String displayName, String targetInstance,
                                       String conflict, InstallJobService.Progress progress) throws Exception {
        // ── 步骤 1：处理整合包（读清单、识别加载器、决定实例目录）
        enter(progress, 1);
        PackInfo inspected = inspect(packZip);
        // 实例目录名 = 包名：带清单的取清单里的 name，裸 zip 取文件名
        String name = "plain".equals(inspected.format()) || inspected.name().isBlank()
                || "整合包".equals(inspected.name())
                ? (displayName == null || displayName.isBlank() ? inspected.name() : displayName)
                : inspected.name();
        PackInfo info = new PackInfo(name, inspected.format(), inspected.mcVersion(),
                inspected.loader(), inspected.loaderVersion(), inspected.fileCount());
        progress.update(2, "识别整合包：" + info.name() + "（" + info.format() + "）");

        String packId;
        Path instanceDir;
        String loader = info.loader();
        String mcVersion = info.mcVersion();
        String loaderVersion = info.loaderVersion();
        boolean autoDetected = false;
        if ("plain".equals(info.format())) {
            // 优先从 Mod 内嵌元数据识别加载器与游戏版本，识别成功即新建实例，
            // 避免 Fabric 包被合并进 NeoForge / Forge 实例导致启动崩溃
            Detected detected = detectFromMods(packZip);
            if (detected == null || detected.loader() == null || detected.mcVersion() == null) {
                String hint = detected == null ? "" : "（识别到加载器 " + detected.loader()
                        + "，但无法确定 Minecraft 版本）";
                if (targetInstance == null || targetInstance.isBlank()) {
                    throw new IllegalArgumentException("该压缩包不包含版本清单，且无法从 Mod 元数据识别出"
                            + "游戏版本与加载器" + hint + "。请在启动页选中一个实例后再上传，将解压到该实例目录");
                }
                if (!Files.isRegularFile(properties.versionsPath().resolve(targetInstance)
                        .resolve(targetInstance + ".json"))) {
                    throw new IllegalArgumentException("实例不存在：" + targetInstance);
                }
                progress.update(3, "无法自动识别，按原样解压到实例 " + targetInstance);
            } else {
                loader = detected.loader();
                mcVersion = detected.mcVersion();
                loaderVersion = "";
                autoDetected = true;
                progress.update(3, "从 Mod 元数据识别：Minecraft " + mcVersion + " + " + loader
                        + "（" + detected.jars() + " 个 Mod）");
            }
        }

        String backup = "";
        // 能确定版本与加载器就新建独立实例，否则只能合并进已有实例
        boolean standalone = !"plain".equals(info.format()) || autoDetected;
        if (standalone) {
            InstanceTarget target = resolveInstance(info.name(), conflict);
            packId = target.id();
            backup = target.backup();
            instanceDir = properties.versionsPath().resolve(packId);
            Files.createDirectories(instanceDir);
        } else {
            packId = targetInstance;
            instanceDir = properties.versionsPath().resolve(packId);
        }

        // ── 步骤 2：解压整合包文件（overrides / 裸 zip 全部内容）
        InstallJobService.Progress unpackStep = band(progress, 2, 1);
        enter(progress, 2);
        unpackStep.update(5, "解压整合包文件…");
        int overrides;
        try (ZipFile zip = new ZipFile(packZip.toFile())) {
            overrides = extractByFormat(zip, instanceDir, info.format());
        }
        unpackStep.update(100, "已解压 " + overrides + " 个配置 / 资源文件");

        // ── 步骤 3：三条流水线并行 ──
        // ① Mod 下载线：解析清单 → 并发下载整合包内容
        // ② 游戏本体线：原版 JSON / 支持库 / 资源文件 → 加载器 → 独立版本 JSON
        // ③ 前置检查线：等 ① 完成后扫描 mods 目录补装缺失前置（与 ② 并行）
        int failed = 0;
        ModDependencyResolver.Result deps = new ModDependencyResolver.Result(0, List.of(), List.of());
        List<NetFile> files = new ArrayList<>();
        enter(progress, 3);
        if (standalone && mcVersion.isBlank()) {
            throw new IllegalArgumentException("整合包未声明 Minecraft 版本，无法安装");
        }

        AtomicInteger modPct = new AtomicInteger();
        AtomicInteger gamePct = new AtomicInteger();
        AtomicInteger depPct = new AtomicInteger(-1);
        AtomicReference<String> modMsg = new AtomicReference<>("等待开始");
        AtomicReference<String> gameMsg = new AtomicReference<>("等待开始");
        AtomicReference<String> depMsg = new AtomicReference<>("");
        AtomicReference<ModDependencyResolver.Result> depsRef =
                new AtomicReference<>(new ModDependencyResolver.Result(0, List.of(), List.of()));
        InstallJobService.Progress parStep = band(progress, 3, 1);
        // loader / mcVersion 在裸 zip 识别分支可能被重赋值，lambda 捕获需要终态快照
        final String lineLoader = loader;
        final String lineMc = mcVersion;
        Runnable push = () -> {
            // 前置未开始时 Mod 下载占整段权重；开始后 Mod 下载与前置各占一半
            double modPart = depPct.get() < 0 ? modPct.get() : 50 + depPct.get() / 2.0;
            int combined = (int) (0.35 * modPart + 0.57 * gamePct.get()
                    + (depPct.get() < 0 ? 0 : 0.08 * depPct.get()));
            StringBuilder message = new StringBuilder("Mod ").append(modMsg.get())
                    .append(" ｜ 游戏 ").append(gameMsg.get());
            if (depPct.get() >= 0) {
                message.append(" ｜ 前置 ").append(depMsg.get());
            }
            parStep.update(Math.max(0, Math.min(100, combined)), message.toString());
        };

        ExecutorService lines = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "pcl-packline");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AtomicInteger failedCount = new AtomicInteger();
            AtomicReference<Throwable> gameError = new AtomicReference<>();
            AtomicReference<String> loaderVersionOut = new AtomicReference<>(loaderVersion);

            // ① Mod 下载线
            Future<?> modsLine = null;
            if (standalone) {
                modsLine = lines.submit(() -> {
                    try {
                        try (ZipFile zip = new ZipFile(packZip.toFile())) {
                            JsonNode modrinth = entryJson(zip, "modrinth.index.json");
                            if (modrinth != null) {
                                modMsg.set("读取 Modrinth 文件清单…");
                                collectModrinthFiles(modrinth, instanceDir, files);
                            } else {
                                JsonNode manifest = entryJson(zip, "manifest.json");
                                if (manifest != null && manifest.has("minecraft")) {
                                    modMsg.set("解析 CurseForge 文件清单…");
                                    collectCurseForgeFiles(manifest, instanceDir, files,
                                            slice(modPct, modMsg, push, 0, 30));
                                }
                            }
                        }
                        if (!files.isEmpty()) {
                            DownloadManager.DownloadSummary summary = downloadManager.downloadAll(files,
                                    (done, total) -> {
                                        modPct.set(30 + (int) (70.0 * done / Math.max(1, total)));
                                        modMsg.set("下载 " + done + "/" + total);
                                        push.run();
                                    });
                            failedCount.set(summary.failed());
                            if (summary.failed() > 0) {
                                log.warn("整合包有 {} 个文件未下载成功：{}", summary.failed(), summary.errors());
                            }
                        }
                        modPct.set(100);
                        modMsg.set(files.size() + " 个文件"
                                + (failedCount.get() > 0 ? "（" + failedCount.get() + " 个失败）" : "，全部完成"));
                    } catch (Throwable t) {
                        log.error("Mod 下载线失败", t);
                        modMsg.set("失败：" + t.getMessage());
                    }
                    push.run();
                });
            }

            // ② 游戏本体线
            Future<?> gameLine = null;
            if (standalone) {
                gameLine = lines.submit(() -> {
                    try {
                        installGame(lineMc, lineLoader, packId, loaderVersionOut,
                                gamePct, gameMsg, push);
                        gamePct.set(100);
                        gameMsg.set("完成");
                    } catch (Throwable t) {
                        gameError.set(t);
                        gameMsg.set("失败：" + t.getMessage());
                        log.error("游戏本体线失败", t);
                    }
                    push.run();
                });
            }

            // 等 Mod 下载线结束后再启动前置检查线（要扫描下载好的 mods 目录）
            if (modsLine != null) {
                modsLine.get();
            }
            Future<?> depLine = null;
            if (standalone && !lineLoader.isBlank() && !lineMc.isBlank()) {
                depMsg.set("检查中…");
                depPct.set(10);
                push.run();
                depLine = lines.submit(() -> {
                    try {
                        ModDependencyResolver.Result result =
                                dependencyResolver.complete(instanceDir.resolve("mods"),
                                        lineLoader, lineMc);
                        depsRef.set(result);
                        depMsg.set(result.installed() > 0
                                ? "补装 " + result.installed() + " 个前置"
                                : "已齐全");
                    } catch (Throwable t) {
                        depMsg.set("检查失败：" + t.getMessage());
                        log.warn("前置检查线失败：{}", t.getMessage());
                    }
                    depPct.set(100);
                    push.run();
                });
            }
            if (gameLine != null) {
                gameLine.get();
            }
            if (depLine != null) {
                depLine.get();
            }

            failed = failedCount.get();
            deps = depsRef.get();
            loaderVersion = loaderVersionOut.get();
            if (gameError.get() != null) {
                Throwable t = gameError.get();
                throw t instanceof Exception ex ? ex : new IllegalStateException(t);
            }
        } finally {
            lines.shutdownNow();
        }

        // ── 步骤 4：完成安装（按最终版本 JSON 全量核对一遍游戏支持库）
        enter(progress, 4);
        if (standalone) {
            InstallJobService.Progress gameLibStep = band(progress, 4, 1);
            gameLibStep.update(5, "核对游戏支持库文件…");
            try {
                VersionManifestService.InstallReport report =
                        manifestService.complete(packId, false, gameLibStep);
                if (report.failed() > 0) {
                    log.warn("游戏支持库有 {} 个未就绪：{}", report.failed(), report.errors());
                }
            } catch (Exception ex) {
                log.warn("游戏支持库核对失败（不影响已安装内容）：{}", ex.getMessage());
            }
            gameLibStep.update(100, "游戏支持库就绪");
        } else {
            // 裸 zip 合并进现有实例：没有版本信息，只解压，后面的安装步骤不适用
            progress.stage(STAGES.size(), STAGES.size(), "已解压到现有实例（无需安装游戏）");
        }

        progress.update(100, "整合包安装完成：" + packId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", packId);
        result.put("packName", info.name());
        result.put("format", info.format());
        result.put("mcVersion", mcVersion);
        result.put("loader", loader);
        result.put("loaderVersion", loaderVersion);
        result.put("autoDetected", autoDetected);
        result.put("files", files.size());
        result.put("depsInstalled", deps.installedMods());
        result.put("depsUnresolved", deps.unresolved());
        result.put("overrides", overrides);
        result.put("failed", failed);
        if (!backup.isBlank()) {
            result.put("backup", backup);
        }
        return result;
    }

    /**
     * 游戏本体线：原版 JSON → 原版支持库 → 资源文件 → 加载器 → 生成整合包独立版本 JSON。
     *
     * <p>与 Mod 下载线并行执行，进度通过 {@code gamePct/gameMsg} 汇报到同一条步骤条上。
     * 内部区间划分：版本 JSON 0~4%、原版支持库 4~34%、资源文件 34~44%、加载器 44~86%、
     * 生成独立实例 86~96%。
     */
    private void installGame(String mcVersion, String loader, String packId,
                             AtomicReference<String> loaderVersionOut,
                             AtomicInteger gamePct, AtomicReference<String> gameMsg, Runnable push)
            throws Exception {
        // 原版 JSON（很快，不细分进度）
        gameMsg.set("下载原版 " + mcVersion + " 的版本 JSON…");
        push.run();
        manifestService.ensureVersionJson(mcVersion);

        // 原版支持库（含客户端 jar）
        manifestService.ensureLibraries(mcVersion, slice(gamePct, gameMsg, push, 4, 34));

        // 原版资源文件
        gameMsg.set("检查游戏资源文件（assets）…");
        try {
            manifestService.ensureAssets(mcVersion, slice(gamePct, gameMsg, push, 34, 44));
        } catch (Exception ex) {
            log.warn("资源文件补全失败（不影响安装）：{}", ex.getMessage());
        }

        // 加载器（获取详情 → 主文件 → 分析库 → 下载库 → 安装，步骤由 LoaderInstaller 内部推进）
        String baseId = mcVersion;
        if (!loader.isBlank()) {
            gameMsg.set("获取 " + loader + " 版本信息…");
            push.run();
            String resolvedVersion = loaderInstaller.resolveVersion(mcVersion, loader,
                    loaderVersionOut.get());
            loaderVersionOut.set(resolvedVersion);
            Map<String, Object> loaderResult = loaderInstaller.install(
                    mcVersion, loader, resolvedVersion, slice(gamePct, gameMsg, push, 44, 86), true);
            baseId = String.valueOf(loaderResult.get("instance"));
        }

        // 生成自包含版本 JSON + 复制客户端 jar
        gamePct.set(86);
        gameMsg.set("生成整合包独立实例 " + packId + "…");
        push.run();
        writeStandaloneVersionJson(packId, baseId);
        gamePct.set(96);
        gameMsg.set("实例 " + packId + " 已生成");
        push.run();
    }

    /**
     * 并行线内部的进度切片：把子模块汇报的 0~100 线性映射到该线自身的 [from, to] 区间，
     * 并顺手刷新该线的状态消息。并行阶段共用同一步骤条，子模块的阶段声明不再切换步骤。
     */
    private static InstallJobService.Progress slice(AtomicInteger pct, AtomicReference<String> msg,
                                                    Runnable push, int from, int to) {
        return new InstallJobService.Progress() {
            @Override
            public void update(int percent, String message) {
                int clamped = Math.max(0, Math.min(100, percent));
                pct.set(from + (to - from) * clamped / 100);
                if (message != null && !message.isBlank()) {
                    msg.set(message);
                }
                push.run();
            }

            @Override
            public void stage(int index, int total, String name) {
                // 并行阶段不切换步骤条
            }
        };
    }

    /**
     * 按格式决定解压范围：Modrinth 解 {@code overrides} + {@code client-overrides}（与 PCL 一致，
     * 不少整合包把全部覆写放进 client-overrides，标准 overrides 目录反而是空的），
     * CurseForge 解清单 overrides 字段指定的目录，裸 zip 全解。
     */
    private int extractByFormat(ZipFile zip, Path instanceDir, String format) throws IOException {
        if ("modrinth".equals(format)) {
            int count = extractOverrides(zip, "overrides", instanceDir);
            count += extractOverrides(zip, "client-overrides", instanceDir);
            if (count == 0) {
                log.warn("Modrinth 包中 overrides / client-overrides 均为空，"
                        + "若包实际带覆写目录请检查包结构（如自定义覆写目录名）");
            }
            return count;
        }
        if ("curseforge".equals(format)) {
            JsonNode manifest = entryJson(zip, "manifest.json");
            String overridesDir = manifest == null ? "overrides"
                    : manifest.path("overrides").asText("overrides");
            return extractOverrides(zip, overridesDir, instanceDir);
        }
        return extractAll(zip, instanceDir);
    }

    /**
     * 生成「自包含」的整合包版本 JSON：把原版 + 加载器的完整描述展开写进整合包自己的版本文件，
     * 并把客户端 jar 复制一份到整合包目录。
     *
     * <p>不使用 {@code inheritsFrom}：整合包实例与已安装的原版 / 加载器版本彻底解耦，
     * 后续删除或改动那些版本都不会影响整合包（支持库仍在共享的 libraries 目录）。
     */
    private void writeStandaloneVersionJson(String packId, String baseId) throws IOException {
        ResolvedVersion base = versionResolver.resolve(baseId);
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        ObjectNode root = mapper.createObjectNode();
        root.put("id", packId);
        root.put("type", base.type() == null ? "release" : base.type());
        root.put("time", now);
        root.put("releaseTime", base.releaseTime() == null ? now : base.releaseTime());
        root.put("mainClass", base.mainClass());

        if (base.assetIndexUrl() != null) {
            ObjectNode assetIndex = root.putObject("assetIndex");
            assetIndex.put("id", base.assetsIndexName());
            assetIndex.put("url", base.assetIndexUrl());
        } else {
            root.put("assets", base.assetsIndexName());
        }
        if (base.javaVersion() != null) {
            ObjectNode javaVersion = root.putObject("javaVersion");
            javaVersion.put("component", base.javaVersion().component());
            javaVersion.put("majorVersion", base.javaVersion().majorVersion());
        }
        if (base.clientDownload() != null) {
            root.putObject("downloads").set("client", mapper.valueToTree(base.clientDownload()));
        }
        if (base.minecraftArguments() != null && !base.minecraftArguments().isBlank()) {
            root.put("minecraftArguments", base.minecraftArguments());
        }
        ObjectNode arguments = root.putObject("arguments");
        var game = arguments.putArray("game");
        base.gameArgs().forEach(game::add);
        var jvm = arguments.putArray("jvm");
        base.jvmArgs().forEach(jvm::add);

        var libraries = root.putArray("libraries");
        base.libraries().forEach(library -> libraries.add(mapper.valueToTree(library)));

        root.put("_pclPack", true);
        root.put("_pclPackBase", baseId);

        Path dir = properties.versionsPath().resolve(packId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(packId + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        copyClientJar(packId, base);
        log.info("已生成整合包独立版本 JSON：{}（来源 {}，{} 个库）", packId, baseId, base.libraries().size());
    }

    /** 把客户端 jar 复制到整合包实例目录，使其不再依赖原版版本目录。 */
    private void copyClientJar(String packId, ResolvedVersion base) throws IOException {
        String jarId = base.jarId();
        Path source = properties.versionsPath().resolve(jarId).resolve(jarId + ".jar");
        Path target = properties.versionsPath().resolve(packId).resolve(packId + ".jar");
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("找不到客户端 jar：" + source + "，请重新安装 " + jarId);
        }
        if (Files.isRegularFile(target) && Files.size(target) == Files.size(source)) {
            return;
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("已复制客户端 jar 到整合包实例：{}（{} MB）", target,
                String.format(Locale.ROOT, "%.1f", Files.size(target) / 1048576.0));
    }

    // ------------------------------------------------------------ 裸 zip 识别

    /** 从 Mod 元数据识别出的加载器与游戏版本。 */
    private record Detected(String loader, String mcVersion, int jars) {
    }

    /**
     * 遍历 zip 内 {@code mods/}（含 {@code .minecraft/mods/}）下的 jar，
     * 读取 {@code fabric.mod.json} / {@code neoforge.mods.toml} / {@code mods.toml}，
     * 投票得出加载器与精确的 Minecraft 版本。
     */
    private Detected detectFromMods(Path packZip) {
        int jars = 0;
        int fabric = 0;
        int forge = 0;
        int neoforge = 0;
        Map<String, Integer> versionVotes = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(packZip.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.endsWith(".jar")) {
                    continue;
                }
                if (!(name.startsWith("mods/") || name.startsWith(".minecraft/mods/"))) {
                    continue;
                }
                jars++;
                try (var nested = new java.util.zip.ZipInputStream(zip.getInputStream(entry))) {
                    java.util.zip.ZipEntry inner;
                    while ((inner = nested.getNextEntry()) != null) {
                        String innerName = inner.getName();
                        if (innerName.equals("fabric.mod.json")) {
                            fabric++;
                            JsonNode mod = mapper.readTree(nested);
                            String pin = exactMcPin(mod.path("depends").path("minecraft").asText(""));
                            if (pin != null) {
                                versionVotes.merge(pin, 1, Integer::sum);
                            }
                            break;
                        }
                        if (innerName.equals("META-INF/neoforge.mods.toml")) {
                            neoforge++;
                            String pin = tomlMcPin(readToString(nested));
                            if (pin != null) {
                                versionVotes.merge(pin, 1, Integer::sum);
                            }
                            break;
                        }
                        if (innerName.equals("META-INF/mods.toml")) {
                            forge++;
                            String pin = tomlMcPin(readToString(nested));
                            if (pin != null) {
                                versionVotes.merge(pin, 1, Integer::sum);
                            }
                            break;
                        }
                    }
                } catch (Exception ex) {
                    log.debug("读取 Mod 元数据失败（{}）：{}", name, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("扫描整合包失败：{}", ex.getMessage());
            return null;
        }

        String loader = null;
        int best = Math.max(fabric, Math.max(forge, neoforge));
        if (jars >= 3 && best > jars / 2) {
            loader = best == fabric ? "fabric" : best == neoforge ? "neoforge" : "forge";
        }
        String version = null;
        int bestVotes = versionVotes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (bestVotes >= 2 || (bestVotes == 1 && jars == 1)) {
            for (Map.Entry<String, Integer> vote : versionVotes.entrySet()) {
                if (vote.getValue() == bestVotes) {
                    version = vote.getKey();
                    break;
                }
            }
        }
        log.info("整合包识别结果：jars={}，fabric={}，forge={}，neoforge={}，版本投票={}",
                jars, fabric, forge, neoforge, versionVotes);
        return new Detected(loader, version, jars);
    }

    /** 从 fabric.mod.json 的 depends.minecraft 提取精确版本（如 {@code >=1.21.11- <1.21.12-}）。 */
    private String exactMcPin(String dep) {
        if (dep == null || dep.isBlank() || dep.strip().startsWith("<")) {
            return null;
        }
        Matcher matcher = Pattern.compile("1\\.\\d+\\.\\d+").matcher(dep);
        if (!matcher.find()) {
            return null;
        }
        String version = matcher.group();
        // 有上界时必须是「下一补丁」才算锁定，如 >=1.21.11 <1.21.12
        Matcher upper = Pattern.compile("<\\s*1\\.\\d+\\.\\d+").matcher(dep);
        if (upper.find()) {
            return upper.group().substring(1).equals(nextPatch(version)) ? version : null;
        }
        return version;
    }

    /** 从 mods.toml / neoforge.mods.toml 的 minecraft versionRange 提取精确版本。 */
    private String tomlMcPin(String toml) {
        Matcher section = Pattern.compile("\\[\\[?dependencies\\.minecraft\\]?\\]([^\\[]*)",
                Pattern.CASE_INSENSITIVE).matcher(toml);
        if (!section.find()) {
            return null;
        }
        String body = section.group(1);
        Matcher range = Pattern.compile("versionRange\\s*=\\s*\"([^\"]+)\"").matcher(body);
        String dep = range.find() ? range.group(1) : "";
        if (dep.isBlank() || dep.startsWith("*") || dep.contains(",") && !dep.matches("\\[?1\\.\\d+\\.\\d+\\]?")) {
            return null;
        }
        String cleaned = dep.replaceAll("[\\[\\]\\(\\)]", "").strip();
        if (!cleaned.matches("1\\.\\d+\\.\\d+")) {
            return null;
        }
        return cleaned;
    }

    private String nextPatch(String version) {
        String[] parts = version.split("\\.");
        parts[2] = String.valueOf(Integer.parseInt(parts[2]) + 1);
        return String.join(".", parts);
    }

    private String readToString(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ Modrinth

    private void collectModrinthFiles(JsonNode index, Path instanceDir, List<NetFile> files) {
        for (JsonNode file : index.path("files")) {
            JsonNode env = file.path("env");
            if (!env.isMissingNode() && "unsupported".equalsIgnoreCase(env.path("client").asText("required"))) {
                continue;
            }
            String relative = file.path("path").asText();
            if (relative.isBlank()) {
                continue;
            }
            String url = firstDownload(file.path("downloads"));
            if (url.isBlank()) {
                continue;
            }
            Path target = safeResolve(instanceDir, relative);
            if (Files.isRegularFile(target)) {
                continue; // overrides 里可能已带同名文件
            }
            files.add(NetFile.of(url, target, file.path("hashes").path("sha1").asText(null),
                    0, "整合包文件 " + relative, SourceKind.LIBRARY));
        }
    }

    private String firstDownload(JsonNode downloads) {
        return downloads.isArray() && downloads.size() > 0 ? downloads.get(0).asText("") : "";
    }

    private Path safeResolve(Path base, String relative) {
        Path resolved = base.resolve(relative.replace('\\', '/')).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("越界路径：" + relative);
        }
        return resolved;
    }

    // ------------------------------------------------------------ CurseForge

    private void collectCurseForgeFiles(JsonNode manifest, Path instanceDir,
                                        List<NetFile> files, InstallJobService.Progress progress) {
        JsonNode fileNodes = manifest.path("files");
        int index = 0;
        for (JsonNode file : fileNodes) {
            long projectId = file.path("projectID").asLong();
            long fileId = file.path("fileID").asLong();
            if (projectId <= 0 || fileId <= 0) {
                continue;
            }
            index++;
            progress.update((int) (100.0 * index / fileNodes.size()),
                    "解析 CurseForge 文件 " + index + "/" + fileNodes.size() + "…");
            try {
                CfFile resolved = resolveCurseForgeFile(projectId, fileId);
                if (resolved == null) {
                    continue;
                }
                Path target = instanceDir.resolve(curseForgeTargetDir(resolved.name())).resolve(resolved.name());
                if (Files.isRegularFile(target)) {
                    continue;
                }
                files.add(NetFile.of(resolved.url(), target, null, 0,
                        "整合包文件 " + resolved.name(), SourceKind.LIBRARY));
            } catch (Exception ex) {
                log.warn("解析 CurseForge 文件失败（{}#{}）：{}", projectId, fileId, ex.getMessage());
            }
        }
    }

    private record CfFile(String name, String url) {
    }

    /**
     * 把 projectID/fileID 解析成文件名与下载地址。
     *
     * <p>PCL 官方版的内置 CurseForge Key 不随开源代码发布（开源版读环境变量且为空），
     * 所以默认走免 Key 通道，实测全部可用：
     * <ol>
     *   <li>官方 API（api.curseforge.com）——仅在设置里填了 Key 时使用，对应 PCL 的做法；</li>
     *   <li>cfwidget 免 Key 镜像——按项目 ID 查文件名，拼 forgecdn 直链（edge.forgecdn.net
     *       无需 Key 可直连，实测 200）；</li>
     *   <li>www.curseforge.com 站点接口——国内基本被 Cloudflare 拦（实测 403），仅保底。</li>
     * </ol>
     */
    private CfFile resolveCurseForgeFile(long projectId, long fileId) throws Exception {
        String apiKey = settingsService.raw().advanced.curseForgeApiKey;
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                CfFile file = toCfFile(getJson("https://api.curseforge.com/v1/mods/" + projectId
                        + "/files/" + fileId, Map.of("x-api-key", apiKey)).path("data"), fileId);
                if (file != null) {
                    return file;
                }
            } catch (Exception ex) {
                log.debug("CurseForge 官方 API 不可用：{}", ex.getMessage());
            }
        }
        try {
            return resolveByCfwidget(projectId, fileId);
        } catch (Exception ex) {
            log.debug("cfwidget 解析 {}#{} 失败：{}", projectId, fileId, ex.getMessage());
        }
        // 保底：站点接口（Cloudflare 常拦，不抱期望）
        try {
            CfFile file = toCfFile(getJson("https://www.curseforge.com/api/v1/mods/"
                    + projectId + "/files/" + fileId).path("data"), fileId);
            if (file != null) {
                return file;
            }
        } catch (Exception ex) {
            log.debug("CurseForge 站点接口不可用：{}", ex.getMessage());
        }
        return null;
    }

    /** 官方接口文件节点 → 文件名 + 下载地址（downloadUrl 缺失时按 forgecdn 规则拼接）。 */
    private static CfFile toCfFile(JsonNode data, long fileId) {
        String name = data.path("fileName").asText("");
        if (name.isBlank()) {
            return null;
        }
        String download = data.path("downloadUrl").asText("");
        if (download.isBlank()) {
            download = forgecdnUrl(fileId, name);
        }
        return new CfFile(name, download);
    }

    /** forgecdn 直链规则：文件 ID 拆成「千位段/余数段」拼路径，无需 Key（实测 200）。 */
    private static String forgecdnUrl(long fileId, String fileName) {
        return "https://edge.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000)
                + "/" + fileName;
    }

    /**
     * cfwidget 免 Key 通道：按项目 ID 拉取全部文件清单，找到对应 fileID 的文件名后拼直链。
     * 项目首次被查询时 cfwidget 会排队（返回 202 + 无 files），稍等重试几次即可；
     * 结果按项目缓存，一个整合包的几十个项目各只查一次。
     */
    private CfFile resolveByCfwidget(long projectId, long fileId) throws Exception {
        JsonNode project = cfProjectCache.get(projectId);
        if (project == null) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                project = getJson(CFWIDGET + "/" + projectId);
                if (project.path("files").isArray() && !project.path("files").isEmpty()) {
                    cfProjectCache.put(projectId, project);
                    break;
                }
                if (attempt < 3) {
                    log.info("cfwidget 正在解析项目 {}（第 {} 次等待，首次查询该项目会排队）…",
                            projectId, attempt);
                    Thread.sleep(3000L * attempt);
                }
            }
            if (project == null || !project.path("files").isArray()
                    || project.path("files").isEmpty()) {
                throw new IOException("cfwidget 未返回文件清单");
            }
        }
        for (JsonNode file : project.path("files")) {
            if (file.path("id").asLong() == fileId) {
                String name = file.path("name").asText("");
                if (!name.isBlank()) {
                    return new CfFile(name, forgecdnUrl(fileId, name));
                }
            }
        }
        throw new IOException("文件清单中未找到 fileID " + fileId);
    }

    /** CurseForge 文件按扩展名 / 文件名归类到实例目录。 */
    private String curseForgeTargetDir(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") && lower.contains("shader")) {
            return "shaderpacks";
        }
        if (lower.endsWith(".zip") && (lower.contains("resource") || lower.contains("texture"))) {
            return "resourcepacks";
        }
        return "mods";
    }

    // ------------------------------------------------------------ 解压

    /** 解压清单指定的 overrides 目录（Modrinth 是 overrides/，CurseForge 可自定义）。 */
    private int extractOverrides(ZipFile zip, String prefix, Path instanceDir) throws IOException {
        String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
        int count = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(normalized) || entry.isDirectory()) {
                continue;
            }
            copyEntry(zip, entry, instanceDir.resolve(safeRelative(name.substring(normalized.length()))), instanceDir);
            count++;
        }
        if (count > 0) {
            log.info("已解压 overrides：{} 个文件", count);
        }
        return count;
    }

    /** 裸 zip：整个包视为实例内容（.minecraft 前缀剥掉）。 */
    private int extractAll(ZipFile zip, Path instanceDir) throws IOException {
        int count = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || name.contains("..")) {
                continue;
            }
            if (name.startsWith(".minecraft/") || name.startsWith(".minecraft\\")) {
                name = name.substring(".minecraft/".length());
            }
            if (name.isBlank()) {
                continue;
            }
            copyEntry(zip, entry, instanceDir.resolve(safeRelative(name)), instanceDir);
            count++;
        }
        return count;
    }

    private void copyEntry(ZipFile zip, ZipEntry entry, Path target, Path instanceDir) throws IOException {
        Path safe = target.normalize();
        if (!safe.startsWith(instanceDir)) {
            log.warn("跳过越界路径：{}", entry.getName());
            return;
        }
        Files.createDirectories(safe.getParent());
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, safe, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 禁止绝对路径与 .. 的相对路径转换。 */
    private String safeRelative(String relative) {
        Path path = Paths.get(relative.replace('\\', '/')).normalize();
        List<String> parts = new ArrayList<>();
        path.forEach(part -> parts.add(part.toString()));
        String joined = String.join("/", parts);
        return joined.startsWith("/") ? joined.substring(1) : joined;
    }

    // ------------------------------------------------------------ 工具

    /** 同名冲突策略：覆盖（旧目录备份后重建）。 */
    public static final String CONFLICT_OVERWRITE = "overwrite";
    /** 同名冲突策略：另建（自动追加 -2 / -3 序号）。 */
    public static final String CONFLICT_RENAME = "rename";

    /**
     * 实例目录名 = 包名（带清单用清单 name，裸 zip 用上传文件名）。
     * Controller 在上传后先调用它判断是否与现有实例重名。
     */
    public String desiredInstanceId(PackInfo info, String displayName) {
        String raw = "plain".equals(info.format())
                ? (displayName == null || displayName.isBlank() ? info.name() : displayName)
                : info.name();
        return sanitizeId(raw);
    }

    /** 决定整合包实例目录：无同名直接用包名；覆盖则备份旧目录后复用包名；另建则追加序号。 */
    private InstanceTarget resolveInstance(String packName, String conflict) throws IOException {
        String desired = sanitizeId(packName);
        Path dir = properties.versionsPath().resolve(desired);
        if (!Files.exists(dir)) {
            return new InstanceTarget(desired, "");
        }
        if (CONFLICT_OVERWRITE.equals(conflict)) {
            if (!isPackInstance(dir)) {
                throw new IllegalArgumentException("实例「" + desired + "」不是整合包实例（是游戏 / 加载器本体），"
                        + "不能覆盖；请选择「另建文件夹」");
            }
            Path backupRoot = properties.minecraftPath().resolve("modpack-backups");
            Files.createDirectories(backupRoot);
            String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = backupRoot.resolve(desired + "-" + stamp);
            Files.move(dir, backup);
            log.info("覆盖安装：旧实例 {} 已备份到 {}", desired, backup);
            return new InstanceTarget(desired, backup.toString());
        }
        return new InstanceTarget(uniqueInstanceId(desired), "");
    }

    /** 解析出的实例目录与（可选的）旧目录备份位置。 */
    private record InstanceTarget(String id, String backup) {
    }

    /** 判断目录是否是整合包实例（而不是原版 / 加载器本体），覆盖安装只允许针对整合包实例。 */
    private boolean isPackInstance(Path dir) {
        Path json = dir.resolve(dir.getFileName().toString() + ".json");
        if (!Files.isRegularFile(json)) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(json.toFile());
            if (node.path("_pclPack").asBoolean(false)) {
                return true;
            }
            // 旧版本生成的整合包实例没有标记：继承自其他版本且没有 mainClass 即认为是整合包壳
            return node.has("inheritsFrom") && !node.has("mainClass");
        } catch (IOException ex) {
            return false;
        }
    }

    /** 实例目录根路径（供 Controller 判断同名冲突）。 */
    public Path versionsPath() {
        return properties.versionsPath();
    }

    /** 清洗为可用作目录名的实例名。 */
    private String sanitizeId(String name) {
        String base = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "").strip();
        if (base.isBlank()) {
            base = "modpack";
        }
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    /** 整合包实例名：清洗非法字符，重名时追加序号。 */
    private String uniqueInstanceId(String name) {
        String base = sanitizeId(name);
        String candidate = base;
        int seq = 2;
        while (Files.exists(properties.versionsPath().resolve(candidate))) {
            candidate = base + "-" + seq++;
        }
        return candidate;
    }

    private JsonNode entryJson(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return mapper.readTree(in);
        }
    }

    private JsonNode getJson(String url) throws Exception {
        return getJson(url, Map.of());
    }

    private JsonNode getJson(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + "：" + url);
        }
        return mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ 重复包确认

    /** 上传后等待用户选择「覆盖 / 另建」的待安装包。 */
    public record Pending(String id, Path file, String displayName, String targetInstance,
                          String name, String format, String mcVersion, String loader,
                          String loaderVersion, int fileCount, long createdAt) {
    }

    private final Map<String, Pending> pendings = new java.util.concurrent.ConcurrentHashMap<>();

    /** 暂存上传的包并等待用户决定同名实例的处理方式。 */
    public Pending holdPending(Path file, String displayName, String targetInstance,
                               PackInfo info, String name) {
        purgePendings();
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Pending pending = new Pending(id, file, displayName, targetInstance, name, info.format(),
                info.mcVersion(), info.loader(), info.loaderVersion(), info.fileCount(),
                System.currentTimeMillis());
        pendings.put(id, pending);
        return pending;
    }

    /** 取出待确认包（取出即移除）。 */
    public Pending takePending(String id) {
        purgePendings();
        Pending pending = id == null ? null : pendings.remove(id);
        if (pending == null) {
            throw new IllegalArgumentException("待确认的整合包已过期或不存在，请重新拖入压缩包");
        }
        return pending;
    }

    /** 清掉超过 2 小时未响应的待确认包（连同临时文件）。 */
    private void purgePendings() {
        long deadline = System.currentTimeMillis() - Duration.ofHours(2).toMillis();
        pendings.entrySet().removeIf(entry -> {
            if (entry.getValue().createdAt() >= deadline) {
                return false;
            }
            try {
                Files.deleteIfExists(entry.getValue().file());
            } catch (IOException ignored) {
                // 临时文件删不掉不影响主流程
            }
            return true;
        });
    }

    /** 供 Controller 转存上传文件用。 */
    public Path saveUpload(String fileName, InputStream in) throws IOException {
        Path dir = properties.dataDirPath().resolve("cache").resolve("modpacks");
        Files.createDirectories(dir);
        String safe = fileName == null || fileName.isBlank() ? "modpack.zip" : fileName;
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(System.currentTimeMillis() + "-" + safe);
        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
