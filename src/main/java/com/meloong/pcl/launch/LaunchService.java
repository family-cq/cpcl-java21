package com.meloong.pcl.launch;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.download.VersionManifestService;
import com.meloong.pcl.instance.InstanceScanner;
import com.meloong.pcl.settings.Settings;
import com.meloong.pcl.settings.SettingsService;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动编排，对应原工程 <c>McLaunch.McLaunchStart</c> 的主流程（登录 → 预检查 → 参数 → 启动）。
 *
 * <p>与设置页相关的部分（隔离策略、内存、Java、优先级、启动前命令、独显）都在这里落地。
 */
@Service
public class LaunchService {

    private static final Logger log = LoggerFactory.getLogger(LaunchService.class);

    private final PclProperties properties;
    private final VersionResolver versionResolver;
    private final LaunchCommandBuilder commandBuilder;
    private final JavaLocator javaLocator;
    private final JavaAutoDownloader javaDownloader;
    private final GameLauncher gameLauncher;
    private final InstanceScanner instanceScanner;
    private final SettingsService settingsService;
    private final WindowsTweaks windowsTweaks;
    private final VersionManifestService manifestService;
    /** 已做过资源检查的实例，避免重复扫索引。 */
    private final java.util.Set<String> assetsChecked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public LaunchService(PclProperties properties, VersionResolver versionResolver,
                         LaunchCommandBuilder commandBuilder, JavaLocator javaLocator,
                         JavaAutoDownloader javaDownloader, GameLauncher gameLauncher,
                         InstanceScanner instanceScanner, SettingsService settingsService,
                         WindowsTweaks windowsTweaks, VersionManifestService manifestService) {
        this.properties = properties;
        this.versionResolver = versionResolver;
        this.commandBuilder = commandBuilder;
        this.javaLocator = javaLocator;
        this.javaDownloader = javaDownloader;
        this.gameLauncher = gameLauncher;
        this.instanceScanner = instanceScanner;
        this.settingsService = settingsService;
        this.windowsTweaks = windowsTweaks;
        this.manifestService = manifestService;
    }

    /** 构建启动计划（不启动）。 */
    public LaunchPlan plan(String instanceId, LaunchRequest request) throws IOException {
        LaunchRequest effective = request == null ? emptyRequest() : request;
        ResolvedVersion version = versionResolver.resolve(instanceId);
        if (!instanceScanner.installed(instanceId)) {
            throw new IllegalArgumentException("版本未安装：" + instanceId);
        }
        Settings settings = settingsService.get();
        InstanceScanner.InstanceInfo info = instanceScanner.info(instanceId);
        boolean unstable = info.snapshot() || info.fool() || !info.reliable();
        boolean isolate = effective.isolate() != null
                ? effective.isolate()
                : settingsService.isolate(info.modable(), unstable);

        JavaRuntime java = selectJava(version, effective, settings);
        if (java == null) {
            throw new IllegalStateException("未找到可用的 Java，请在设置里指定 Java 路径");
        }
        LaunchOptions options = toOptions(instanceId, effective, settings, isolate);
        int modCount = instanceScanner.countMods(
                isolate ? properties.versionsPath().resolve(instanceId).resolve("mods")
                        : properties.minecraftPath().resolve("mods"));
        return commandBuilder.build(version, java, options, modCount);
    }

    /** 启动游戏。 */
    public GameLauncher.LaunchResult launch(String instanceId, LaunchRequest request) throws IOException {
        LaunchRequest effective = request == null ? emptyRequest() : request;
        Settings settings = settingsService.get();
        LaunchPlan plan = plan(instanceId, effective);
        log.info("准备启动 {}：{}", instanceId, plan.display());

        windowsTweaks.runPreLaunch(settings.advanced.preLaunchCommand, plan.gameDir());
        if (settings.advanced.highPerformanceGpu) {
            windowsTweaks.preferHighPerformanceGpu(Path.of(plan.javaPath()));
        }
        backfillAssetsInBackground(instanceId);

        GameLauncher.LaunchResult result = gameLauncher.launch(plan,
                effective.waitForExit() != null ? effective.waitForExit() : properties.isWaitForExit());
        windowsTweaks.applyPriority(result.pid(), settingsService.priorityClass());
        settingsService.updateLastInstance(instanceId);
        return result;
    }

    /**
     * 后台补全资源文件（assets），按版本判断，不阻塞启动。
     *
     * <p>早先安装的版本可能跳过了资源下载，进游戏会到处丢贴图。资源是按内容哈希共享的，
     * 一个目录里混着多个版本的资源，所以不能只看目录是否为空——按当前版本的资源索引逐个核对，
     * 缺什么补什么（已存在的会被下载器跳过）。每个实例每次运行只检查一次。
     */
    private void backfillAssetsInBackground(String instanceId) {
        if (!assetsChecked.add(instanceId)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                int count = manifestService.ensureAssets(instanceId);
                if (count > 0) {
                    log.info("资源文件已补全：{}（新增 {} 个）", instanceId, count);
                }
            } catch (Exception ex) {
                log.warn("资源补全失败（{}）：{}", instanceId, ex.getMessage());
            }
        }, "pcl-assets");
        thread.setDaemon(true);
        thread.start();
    }

    private JavaRuntime selectJava(ResolvedVersion version, LaunchRequest request, Settings settings) {
        String configured = request.javaPath() != null && !request.javaPath().isBlank()
                ? request.javaPath()
                : settings.advanced.javaPath;
        if (configured != null && !configured.isBlank()) {
            JavaRuntime java = javaLocator.findByPath(configured);
            if (java.available()) {
                if (usesLaunchWrapper(version) && java.major() != 8) {
                    log.warn("设置指定的 Java {} 与 launchwrapper 不兼容（必须 Java 8），改用自动选择", java);
                } else {
                    return java;
                }
            } else {
                log.warn("设置中的 Java 不可用，回退到自动选择：{}", configured);
            }
        }
        boolean launchWrapper = usesLaunchWrapper(version);
        int required = version.requiredJavaMajor();
        JavaRuntime java;
        if (launchWrapper) {
            // launchwrapper 把应用类加载器强转 URLClassLoader，只有 Java 8 上成立，
            // Java 9+ 直接 ClassCastException（用户遇到的启动报错就是这个）
            java = javaLocator.findExact(8);
            if (java == null) {
                log.info("launchwrapper 版本需要 Java 8，本机没有，尝试自动下载…");
                java = javaDownloader.ensureJava8();
            }
            if (java == null) {
                throw new IllegalStateException(
                        "1.7.10～1.12.2 的 Forge / 整合包用 launchwrapper 启动，只能用 Java 8（Java 11/17/21 都会报 "
                                + "ClassCastException）。自动下载 Java 8 失败，请检查网络后在设置里手动添加 Java 8。");
            }
            log.info("launchwrapper 版本，使用 Java 8：{}", java);
        } else {
            java = javaLocator.findBest(required);
            if (java != null) {
                log.info("自动选择 {}（版本要求 Java {}）", java, required);
            }
        }
        return java;
    }

    private static boolean usesLaunchWrapper(ResolvedVersion version) {
        return version.mainClass() != null && version.mainClass().contains("net.minecraft.launchwrapper");
    }

    private LaunchOptions toOptions(String instanceId, LaunchRequest request, Settings settings, boolean isolate) {
        boolean forceOffline = "offline".equalsIgnoreCase(request.userType());
        boolean hasMicrosoft = "microsoft".equals(settings.account.type)
                && !settings.account.accessToken.isBlank();
        String requestedName = request.playerName() == null ? "" : request.playerName().trim();

        String playerName;
        String uuid = null;
        String accessToken = "0";
        String userType = "legacy";
        if (hasMicrosoft && !forceOffline) {
            playerName = settings.account.microsoftName.isBlank() ? "Player" : settings.account.microsoftName;
            uuid = settings.account.microsoftUuid;
            accessToken = settings.account.accessToken;
            userType = "msa";
        } else {
            playerName = requestedName.isBlank() ? settings.account.playerName : requestedName;
        }

        int memoryMb = request.memoryMb() != null && request.memoryMb() > 0
                ? request.memoryMb()
                : settingsService.memoryMb();

        return new LaunchOptions(instanceId,
                playerName,
                uuid,
                accessToken,
                userType,
                memoryMb,
                Boolean.TRUE.equals(request.fullscreen()),
                request.width() == null ? 854 : request.width(),
                request.height() == null ? 480 : request.height(),
                request.serverIp(),
                isolate,
                request.extraJvmArgs(),
                request.extraGameArgs());
    }

    private static LaunchRequest emptyRequest() {
        return new LaunchRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * 启动请求，字段全部可选。
     */
    public record LaunchRequest(
            String playerName,
            String uuid,
            String accessToken,
            String userType,
            Integer memoryMb,
            Boolean fullscreen,
            Integer width,
            Integer height,
            String serverIp,
            Boolean isolate,
            String javaPath,
            List<String> extraJvmArgs,
            List<String> extraGameArgs,
            Boolean waitForExit) {
    }
}
