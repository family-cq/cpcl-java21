package com.meloong.pcl.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动器设置，对应原工程 Settings 里与启动相关的一组键。
 *
 * <p>使用公有字段便于直接映射 JSON；默认值即界面首次打开时展示的值。
 */
public class Settings {

    /** PCL 默认的 JVM 参数（设置页「高级选项」的初始值）。 */
    public static final String DEFAULT_JVM_ARGS = "-XX:-OmitStackTraceInFastThrow "
            + "-Djdk.lang.Process.allowAmbiguousCommands=True "
            + "-Dfml.ignoreInvalidMinecraftCertificates=True "
            + "-Dfml.ignorePatchDiscrepancies=True";

    public Launch launch = new Launch();

    public Memory memory = new Memory();

    public Advanced advanced = new Advanced();

    public Account account = new Account();

    /** 上次选择的实例（整合包 / 游戏）。 */
    public String lastInstance = "";

    /**
     * 手动添加的 Java 路径（bin 目录 / JDK 根目录 / java 可执行文件均可）。
     *
     * <p>扫描出来的 Java 每次启动都会重新搜索到，无需保存；手动添加的可能在
     * 搜索范围之外（移动硬盘、自定义目录等），必须落盘才能重启后仍在列表里。
     */
    public List<String> customJavas = new ArrayList<>();

    /** 启动选项。 */
    public static class Launch {

        /**
         * 默认隔离版本：off / modable / unstable / modableOrUnstable / all。
         * 对应原工程 LaunchArgumentRam 之外的 VersionArgumentIndie 一组设置。
         */
        public String isolateMode = "modableOrUnstable";

        /** 进程优先级：low / normal / high。 */
        public String priority = "normal";
    }

    /** 内存分配。 */
    public static class Memory {

        /** auto 自动配置 / custom 自定义。 */
        public String mode = "auto";

        /** 自定义内存（MB）。 */
        public int customMb = 4096;
    }

    /** 高级选项。 */
    public static class Advanced {

        public String jvmArgs = DEFAULT_JVM_ARGS;

        public String gameArgs = "";

        /** 启动前执行的命令，空则不执行。 */
        public String preLaunchCommand = "";

        /** 指定 Java 可执行文件路径，空则自动选择。 */
        public String javaPath = "";

        /** GC 策略：zgc / zgcGen / g1 / g1Tuned / none。 */
        public String gcMode = "zgc";

        /** 禁用 Java Launch Wrapper。 */
        public boolean disableJavaLaunchWrapper = false;

        /** 禁用 LWJGL Unsafe Agent。 */
        public boolean disableLwjglUnsafeAgent = false;

        /** 使用高性能显卡。 */
        public boolean highPerformanceGpu = false;

        /**
         * CurseForge API Key（可选）。
         *
         * <p>整合包缺失的前置 Mod 大多在 Modrinth 上能找到，但少数只发布在 CurseForge；
         * CF 官网被 Cloudflare 挡在启动器之外，只有官方 API + Key 这一条路。留空则跳过。
         */
        public String curseForgeApiKey = "";
    }

    /** 账户。 */
    public static class Account {

        /** offline / microsoft。 */
        public String type = "offline";

        /** 离线玩家名。 */
        public String playerName = "Steve";

        /** 正版玩家名。 */
        public String microsoftName = "";

        /** 正版 UUID（无横线）。 */
        public String microsoftUuid = "";

        /** 访问令牌（不返回给前端明文，仅内部使用）。 */
        public String accessToken = "";

        public String refreshToken = "";

        /** 令牌过期时间（epoch 毫秒），0 表示未知。 */
        public long expiresAt = 0L;
    }

    /** 深拷贝，避免前端提交的对象直接替换内部状态。 */
    public Settings copy() {
        Settings target = new Settings();
        target.launch.isolateMode = launch.isolateMode;
        target.launch.priority = launch.priority;
        target.memory.mode = memory.mode;
        target.memory.customMb = memory.customMb;
        target.advanced.jvmArgs = advanced.jvmArgs;
        target.advanced.gameArgs = advanced.gameArgs;
        target.advanced.preLaunchCommand = advanced.preLaunchCommand;
        target.advanced.javaPath = advanced.javaPath;
        target.advanced.gcMode = advanced.gcMode;
        target.advanced.disableJavaLaunchWrapper = advanced.disableJavaLaunchWrapper;
        target.advanced.disableLwjglUnsafeAgent = advanced.disableLwjglUnsafeAgent;
        target.advanced.highPerformanceGpu = advanced.highPerformanceGpu;
        target.advanced.curseForgeApiKey = advanced.curseForgeApiKey;
        target.account.type = account.type;
        target.account.playerName = account.playerName;
        target.account.microsoftName = account.microsoftName;
        target.account.microsoftUuid = account.microsoftUuid;
        target.account.accessToken = account.accessToken;
        target.account.refreshToken = account.refreshToken;
        target.account.expiresAt = account.expiresAt;
        target.lastInstance = lastInstance;
        target.customJavas = new ArrayList<>(customJavas);
        return target;
    }

    /** 把可能缺失/非法的字段收敛到合法值。 */
    public void normalize() {
        if (launch == null) {
            launch = new Launch();
        }
        if (memory == null) {
            memory = new Memory();
        }
        if (advanced == null) {
            advanced = new Advanced();
        }
        if (account == null) {
            account = new Account();
        }
        if (launch.isolateMode == null || launch.isolateMode.isBlank()) {
            launch.isolateMode = "modableOrUnstable";
        }
        if (launch.priority == null || launch.priority.isBlank()) {
            launch.priority = "normal";
        }
        if (!"custom".equals(memory.mode)) {
            memory.mode = "auto";
        }
        int maxMb = (int) Math.max(512, MemoryInfo.totalMb() - 1024);
        memory.customMb = Math.max(256, Math.min(memory.customMb, maxMb));
        if (advanced.jvmArgs == null) {
            advanced.jvmArgs = DEFAULT_JVM_ARGS;
        }
        if (advanced.gameArgs == null) {
            advanced.gameArgs = "";
        }
        if (advanced.preLaunchCommand == null) {
            advanced.preLaunchCommand = "";
        }
        if (advanced.javaPath == null) {
            advanced.javaPath = "";
        }
        if (advanced.curseForgeApiKey == null) {
            advanced.curseForgeApiKey = "";
        }
        if (advanced.gcMode == null || advanced.gcMode.isBlank()) {
            advanced.gcMode = "zgc";
        }
        if (!"microsoft".equals(account.type)) {
            account.type = "offline";
        }
        if (account.playerName == null || account.playerName.isBlank()) {
            account.playerName = "Steve";
        }
        if (lastInstance == null) {
            lastInstance = "";
        }
        if (customJavas == null) {
            customJavas = new ArrayList<>();
        }
        customJavas.removeIf(path -> path == null || path.isBlank());
        List<String> distinct = customJavas.stream().distinct().toList();
        customJavas.clear();
        customJavas.addAll(distinct);
    }

    /** GC 策略 → 原工程的整数枚举（0 自动 / 1 保守 / 2 仅 G1 / 3 不指定 / 4 优化 G1）。 */
    public static int gcModeOf(String value) {
        return switch (value == null ? "" : value) {
            case "zgcGen" -> 1;
            case "g1" -> 2;
            case "g1Tuned" -> 4;
            case "none" -> 3;
            default -> 0;
        };
    }

    /** 物理内存信息（供界面的进度条上限与自动内存说明使用）。 */
    public static final class MemoryInfo {

        private MemoryInfo() {
        }

        public static long totalMb() {
            try {
                java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                    return sun.getTotalMemorySize() / 1024 / 1024;
                }
            } catch (Throwable ignored) {
                // 非 HotSpot 时退化为 8G
            }
            return 8192;
        }

        public static long availableMb() {
            try {
                java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                    return sun.getFreeMemorySize() / 1024 / 1024;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            return 4096;
        }
    }
}
