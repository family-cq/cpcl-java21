package com.meloong.pcl.web;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.settings.Settings;
import com.meloong.pcl.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final PclProperties properties;

    public SettingsController(SettingsService settingsService, PclProperties properties) {
        this.settingsService = settingsService;
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> get() {
        return payload(settingsService.get());
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody Settings settings) {
        return payload(settingsService.save(settings));
    }

    private Map<String, Object> payload(Settings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", view(settings));
        result.put("system", system());
        result.put("options", options());
        return result;
    }

    /** 对外视图：不含令牌。 */
    private Map<String, Object> view(Settings settings) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("type", settings.account.type);
        account.put("playerName", settings.account.playerName);
        account.put("microsoftName", settings.account.microsoftName);
        account.put("microsoftUuid", settings.account.microsoftUuid);
        account.put("loggedIn", !settings.account.accessToken.isBlank());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("launch", Map.of("isolateMode", settings.launch.isolateMode,
                "priority", settings.launch.priority));
        view.put("memory", Map.of("mode", settings.memory.mode, "customMb", settings.memory.customMb));
        view.put("advanced", Map.of(
                "jvmArgs", settings.advanced.jvmArgs,
                "gameArgs", settings.advanced.gameArgs,
                "preLaunchCommand", settings.advanced.preLaunchCommand,
                "javaPath", settings.advanced.javaPath,
                "gcMode", settings.advanced.gcMode,
                "disableJavaLaunchWrapper", settings.advanced.disableJavaLaunchWrapper,
                "disableLwjglUnsafeAgent", settings.advanced.disableLwjglUnsafeAgent,
                "highPerformanceGpu", settings.advanced.highPerformanceGpu,
                "curseForgeApiKey", settings.advanced.curseForgeApiKey == null ? "" : settings.advanced.curseForgeApiKey));
        view.put("account", account);
        view.put("lastInstance", settings.lastInstance);
        return view;
    }

    private Map<String, Object> system() {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("totalMemoryMb", Settings.MemoryInfo.totalMb());
        system.put("availableMemoryMb", Settings.MemoryInfo.availableMb());
        system.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        system.put("arch", System.getProperty("os.arch"));
        system.put("minecraftDir", properties.minecraftPath().toString());
        system.put("dataDir", properties.dataDirPath().toString());
        system.put("settingsFile", settingsService.settingsPath().toString());
        system.put("launcherVersion", properties.getLauncherVersion());
        system.put("launcherName", properties.getLauncherName());
        system.put("hostMemoryMaxMb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        return system;
    }

    private Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("isolateModes", List.of(
                option("off", "关闭", "所有版本共用 .minecraft/mods 与存档"),
                option("modable", "隔离可安装 Mod 的版本", "Forge / Fabric 等版本单独存放 Mod"),
                option("unstable", "隔离非正式版", "快照、愚人节版本单独存放存档"),
                option("modableOrUnstable", "隔离可安装 Mod 和非正式版", "默认，兼顾两者"),
                option("all", "隔离所有版本", "每个版本都有独立存档与 Mod")));
        options.put("priorities", List.of(
                option("low", "低", "游戏让出 CPU 给其他程序"),
                option("normal", "中", "系统默认"),
                option("high", "高", "游戏优先占用 CPU")));
        options.put("gcModes", List.of(
                option("zgc", "尽量使用 ZGC", "Java 15+ 用 ZGC，低延迟"),
                option("zgcGen", "尽量使用分代 ZGC", "Java 21+ 用分代 ZGC，效果最好"),
                option("g1", "标准 G1GC", "兼容性最好"),
                option("g1Tuned", "调优 G1GC", "针对 Minecraft 调过参数"),
                option("none", "不指定", "完全交给版本 JSON 与自定义参数")));
        return options;
    }

    private Map<String, Object> option(String value, String label, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("label", label);
        map.put("description", description);
        return map;
    }
}
