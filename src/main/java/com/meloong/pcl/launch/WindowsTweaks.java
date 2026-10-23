package com.meloong.pcl.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows 侧的系统级小动作：进程优先级、独显偏好、启动前命令。
 *
 * <p>对应原工程里通过注册表 / Win32 API 完成的几项设置，这里用 PowerShell 调用，
 * 避免为 JNA 增加依赖；非 Windows 平台全部跳过。
 */
@Service
public class WindowsTweaks {

    private static final Logger log = LoggerFactory.getLogger(WindowsTweaks.class);

    public boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 调整已启动进程的优先级（Idle / BelowNormal / Normal / AboveNormal / High / RealTime）。 */
    public void applyPriority(long pid, String priorityClass) {
        if (!supported() || priorityClass == null || "Normal".equalsIgnoreCase(priorityClass)) {
            return;
        }
        String script = String.format("(Get-Process -Id %d -ErrorAction Stop).PriorityClass = '%s'",
                pid, priorityClass);
        if (runPowerShell(script, 15)) {
            log.info("已设置游戏进程 {} 优先级为 {}", pid, priorityClass);
        } else {
            log.warn("设置进程优先级失败：{}", priorityClass);
        }
    }

    /**
     * 为 Java 可执行文件登记「高性能显卡」偏好（Windows 10 1803+ 的图形设置项）。
     */
    public void preferHighPerformanceGpu(Path javaExe) {
        if (!supported() || javaExe == null) {
            return;
        }
        List<Path> targets = new ArrayList<>();
        targets.add(javaExe);
        String fileName = javaExe.getFileName().toString();
        if (fileName.equalsIgnoreCase("java.exe")) {
            Path javaw = javaExe.resolveSibling("javaw.exe");
            targets.add(javaw);
        }
        for (Path target : targets) {
            String script = String.format(
                    "reg add \"HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences\" /v '%s' /t REG_SZ /d 'GpuPreference=2;' /f | Out-Null",
                    target.toString().replace("'", "''"));
            if (runPowerShell(script, 15)) {
                log.info("已为 {} 指定高性能显卡", target);
            }
        }
    }

    /** 启动前命令：在游戏目录下执行，失败只记录不阻塞。 */
    public void runPreLaunch(String command, Path workDir) {
        if (command == null || command.isBlank()) {
            return;
        }
        try {
            ProcessBuilder builder = supported()
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("/bin/sh", "-c", command);
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("启动前命令超时：{}", command);
            } else {
                log.info("启动前命令已执行（退出码 {}）：{}", process.exitValue(), command);
            }
        } catch (Exception ex) {
            log.warn("启动前命令执行失败：{}（{}）", command, ex.getMessage());
        }
    }

    private boolean runPowerShell(String script, int timeoutSeconds) {
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (var reader = process.getInputStream()) {
                reader.readAllBytes();
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            log.debug("PowerShell 不可用：{}", ex.getMessage());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 读取当前代码页，便于处理 PowerShell 输出（预留）。 */
    public Charset defaultCharset() {
        return Charset.defaultCharset();
    }
}
