package com.meloong.pcl.web;

import com.meloong.pcl.config.PclProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final PclProperties properties;

    public ConfigController(PclProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> config() {
        return Map.of(
                "minecraftDir", properties.minecraftPath().toString(),
                "versionsDir", properties.versionsPath().toString(),
                "librariesDir", properties.librariesPath().toString(),
                "assetsDir", properties.assetsPath().toString(),
                "launcherName", properties.getLauncherName(),
                "launcherVersion", properties.getLauncherVersion(),
                "downloadThread", properties.getDownloadThread(),
                "downloadSource", properties.getDownloadSource(),
                "gcMode", properties.getGcMode(),
                "runtime", Map.of(
                        "javaVersion", System.getProperty("java.version"),
                        "javaVendor", System.getProperty("java.vendor"),
                        "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                        "arch", System.getProperty("os.arch"),
                        "inputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments(),
                        "memoryMaxMb", Runtime.getRuntime().maxMemory() / 1024 / 1024));
    }

    /** 便于前端判断哪些设置项可调。 */
    @GetMapping("/gc-modes")
    public List<Map<String, Object>> gcModes() {
        return List.of(
                Map.of("value", 0, "label", "自动（Java 21+ 分代 ZGC，15~20 ZGC，14- G1GC）"),
                Map.of("value", 1, "label", "保守（Java 21+ 分代 ZGC，其余 G1GC）"),
                Map.of("value", 2, "label", "仅 G1GC"),
                Map.of("value", 3, "label", "不指定，交由玩家自定义"),
                Map.of("value", 4, "label", "优化的 G1GC"));
    }
}
