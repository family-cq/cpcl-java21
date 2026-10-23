package com.meloong.pcl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * PCL 设置项的 Java 侧映射（对应原工程 Settings / Configs）。
 */
@ConfigurationProperties(prefix = "pcl")
public class PclProperties {

    /** 数据目录（设置、缓存），留空则使用程序所在目录（绝不落 C 盘）。 */
    private String dataDir = "";

    /** .minecraft 根目录，对应 McFolderSelected；留空则使用“程序目录/.minecraft”。 */
    private String minecraftDir = "";

    /** 微软登录的客户端 ID（设备码流程必需，未填则正版登录不可用）。 */
    private String authClientId = "";

    /** 微软登录租户，个人账号用 consumers。 */
    private String authTenant = "consumers";

    /** 启动器名称，对应 ${launcher_name}。 */
    private String launcherName = "PCL";

    /** 启动器版本号，对应 ${launcher_version} / VersionCode。 */
    private String launcherVersion = "2.99.0.0";

    /** 下载线程数，对应 PCL 的下载线程设置（默认 63）。 */
    private int downloadThread = 16;

    /** 下载源偏好：auto / mirror / official，对应 ToolDownloadSource。 */
    private String downloadSource = "auto";

    /** 默认分配内存（MB），<=0 时按 PCL 的自动内存算法计算。 */
    private int defaultMemoryMb = 0;

    /** GC 策略，对应 LaunchAdvanceGC：0 自动、1 保守、2 仅 G1GC、3 不指定、4 优化 G1GC。 */
    private int gcMode = 0;

    /** 是否追加 Log4j 漏洞防御参数。 */
    private boolean log4jPatch = true;

    /** 额外 JVM 参数，对应 LaunchAdvanceJvm。 */
    private List<String> extraJvmArgs = new ArrayList<>();

    /** 额外游戏参数，对应 LaunchAdvanceGame。 */
    private List<String> extraGameArgs = new ArrayList<>();

    /** 启动后是否等待进程退出（false 时异步启动）。 */
    private boolean waitForExit = false;

    /**
     * 程序所在目录：优先取启动时的 user.dir（启动脚本所在目录），
     * 保证游戏文件与设置都放在启动器旁边，而不是 C 盘用户目录。
     */
    public static Path appDir() {
        String home = System.getProperty("pcl.home");
        if (home != null && !home.isBlank()) {
            return Paths.get(home).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public Path dataDirPath() {
        String dir = dataDir == null || dataDir.isBlank() ? appDir().toString() : dataDir;
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    public Path minecraftPath() {
        String dir = minecraftDir == null || minecraftDir.isBlank()
                ? dataDirPath().resolve(".minecraft").toString()
                : minecraftDir;
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    public Path versionsPath() {
        return minecraftPath().resolve("versions");
    }

    public Path librariesPath() {
        return minecraftPath().resolve("libraries");
    }

    public Path assetsPath() {
        return minecraftPath().resolve("assets");
    }

    public String getMinecraftDir() {
        return minecraftDir;
    }

    public void setMinecraftDir(String minecraftDir) {
        this.minecraftDir = minecraftDir;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getAuthClientId() {
        return authClientId;
    }

    public void setAuthClientId(String authClientId) {
        this.authClientId = authClientId;
    }

    public String getAuthTenant() {
        return authTenant;
    }

    public void setAuthTenant(String authTenant) {
        this.authTenant = authTenant;
    }

    public String getLauncherName() {
        return launcherName;
    }

    public void setLauncherName(String launcherName) {
        this.launcherName = launcherName;
    }

    public String getLauncherVersion() {
        return launcherVersion;
    }

    public void setLauncherVersion(String launcherVersion) {
        this.launcherVersion = launcherVersion;
    }

    public int getDownloadThread() {
        return downloadThread;
    }

    public void setDownloadThread(int downloadThread) {
        this.downloadThread = downloadThread;
    }

    public String getDownloadSource() {
        return downloadSource;
    }

    public void setDownloadSource(String downloadSource) {
        this.downloadSource = downloadSource;
    }

    public int getDefaultMemoryMb() {
        return defaultMemoryMb;
    }

    public void setDefaultMemoryMb(int defaultMemoryMb) {
        this.defaultMemoryMb = defaultMemoryMb;
    }

    public int getGcMode() {
        return gcMode;
    }

    public void setGcMode(int gcMode) {
        this.gcMode = gcMode;
    }

    public boolean isLog4jPatch() {
        return log4jPatch;
    }

    public void setLog4jPatch(boolean log4jPatch) {
        this.log4jPatch = log4jPatch;
    }

    public List<String> getExtraJvmArgs() {
        return extraJvmArgs;
    }

    public void setExtraJvmArgs(List<String> extraJvmArgs) {
        this.extraJvmArgs = extraJvmArgs == null ? new ArrayList<>() : extraJvmArgs;
    }

    public List<String> getExtraGameArgs() {
        return extraGameArgs;
    }

    public void setExtraGameArgs(List<String> extraGameArgs) {
        this.extraGameArgs = extraGameArgs == null ? new ArrayList<>() : extraGameArgs;
    }

    public boolean isWaitForExit() {
        return waitForExit;
    }

    public void setWaitForExit(boolean waitForExit) {
        this.waitForExit = waitForExit;
    }
}
