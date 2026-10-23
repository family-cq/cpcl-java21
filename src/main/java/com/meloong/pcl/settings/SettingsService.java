package com.meloong.pcl.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置的读写与生效，对应原工程 Settings 模块。
 *
 * <p>设置保存在「程序目录/data/settings.json」，不写注册表也不写用户目录。
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final PclProperties properties;
    private final ObjectMapper mapper;

    private volatile Settings current = new Settings();

    public SettingsService(PclProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void init() {
        Path file = settingsPath();
        if (Files.isRegularFile(file)) {
            try {
                current = mapper.readValue(Files.readString(file), Settings.class);
            } catch (Exception ex) {
                log.warn("读取设置失败，使用默认值：{}", ex.getMessage());
                current = new Settings();
            }
        }
        current.normalize();
        apply(current);
        log.info("设置文件：{}", file);
    }

    public Path settingsPath() {
        return properties.dataDirPath().resolve("data").resolve("settings.json");
    }

    public Settings get() {
        return current.copy();
    }

    /** 内部使用的原对象（只读约定）。 */
    public Settings raw() {
        return current;
    }

    public synchronized Settings save(Settings inbound) {
        if (inbound == null) {
            throw new IllegalArgumentException("设置内容为空");
        }
        inbound.normalize();
        // 账户令牌只允许由登录流程写入，避免前端把它清空
        inbound.account.accessToken = current.account.accessToken;
        inbound.account.refreshToken = current.account.refreshToken;
        inbound.account.expiresAt = current.account.expiresAt;
        inbound.account.microsoftName = current.account.microsoftName;
        inbound.account.microsoftUuid = current.account.microsoftUuid;
        current = inbound;
        persist();
        apply(current);
        return current.copy();
    }

    /** 登录成功后写回账户信息。 */
    public synchronized void updateAccount(Settings.Account account) {
        current.account = account;
        current.normalize();
        persist();
    }

    public synchronized void updateLastInstance(String instance) {
        if (instance != null && !instance.equals(current.lastInstance)) {
            current.lastInstance = instance;
            persist();
        }
    }

    /** 记录手动添加的 Java 路径，重启后由 JavaLocator 自动并入候选列表。 */
    public synchronized void addCustomJava(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalized = java.nio.file.Paths.get(path.trim())
                .toAbsolutePath().normalize().toString();
        if (current.customJavas.contains(normalized)) {
            return;
        }
        current.customJavas.add(normalized);
        current.normalize();
        persist();
        log.info("已保存自定义 Java 路径：{}", normalized);
    }

    /** 从配置中移除手动添加的 Java 路径。 */
    public synchronized void removeCustomJava(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalized = java.nio.file.Paths.get(path.trim())
                .toAbsolutePath().normalize().toString();
        if (current.customJavas.removeIf(item -> item.equalsIgnoreCase(normalized))) {
            persist();
        }
    }

    private void persist() {
        try {
            Path file = settingsPath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(current));
        } catch (IOException ex) {
            log.error("保存设置失败：{}", ex.getMessage());
        }
    }

    /** 把设置同步到启动链路使用的属性对象上。 */
    private void apply(Settings settings) {
        properties.setGcMode(Settings.gcModeOf(settings.advanced.gcMode));
        properties.setExtraJvmArgs(splitArgs(settings.advanced.jvmArgs));
        properties.setExtraGameArgs(splitArgs(settings.advanced.gameArgs));
        // 内存交由 LaunchService 通过 LaunchRequest 传入（自动 / 自定义）
        properties.setDefaultMemoryMb(0);
    }

    /** 按空格切分参数，支持双引号包裹。 */
    public static List<String> splitArgs(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        StringBuilder buffer = new StringBuilder();
        boolean quoted = false;
        for (char ch : raw.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(ch) && !quoted) {
                if (buffer.length() > 0) {
                    result.add(buffer.toString());
                    buffer.setLength(0);
                }
                continue;
            }
            buffer.append(ch);
        }
        if (buffer.length() > 0) {
            result.add(buffer.toString());
        }
        return result;
    }

    /** 本次启动分配的内存（MB），0 表示交给自动算法。 */
    public int memoryMb() {
        Settings settings = current;
        return "custom".equals(settings.memory.mode) ? settings.memory.customMb : 0;
    }

    /**
     * 是否对当前版本启用隔离。
     *
     * @param modable  该版本是否可安装 Mod（Forge / Fabric 等）
     * @param unstable 是否为非正式版（快照、愚人节、不可靠版本）
     */
    public boolean isolate(boolean modable, boolean unstable) {
        String mode = current.launch.isolateMode;
        return switch (mode == null ? "" : mode) {
            case "off" -> false;
            case "modable" -> modable;
            case "unstable" -> unstable;
            case "all" -> true;
            default -> modable || unstable;
        };
    }

    /** 进程优先级 → Windows 的 PriorityClass 名称。 */
    public String priorityClass() {
        return switch (current.launch.priority == null ? "" : current.launch.priority) {
            case "low" -> "Idle";
            case "high" -> "High";
            default -> "Normal";
        };
    }
}
