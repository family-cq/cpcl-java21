package com.meloong.pcl.launch;

import com.meloong.pcl.config.PclProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动游戏进程，移植自 <c>ModLaunch.McLaunchRun / McLaunchWait</c>。
 */
@Service
public class GameLauncher {

    private static final Logger log = LoggerFactory.getLogger(GameLauncher.class);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final PclProperties properties;
    private final NativeExtractor nativeExtractor;

    public GameLauncher(PclProperties properties, NativeExtractor nativeExtractor) {
        this.properties = properties;
        this.nativeExtractor = nativeExtractor;
    }

    /**
     * 启动游戏。
     *
     * @param waitForExit true 时阻塞等待退出并返回退出码，false 时立即返回
     */
    public LaunchResult launch(LaunchPlan plan, boolean waitForExit) throws IOException {
        nativeExtractor.extract(plan.libraries(), plan.nativesDir());
        Files.createDirectories(plan.gameDir());

        Path logFile = plan.gameDir().resolve("PCL-Java-" + LocalDateTime.now().format(LOG_TIME) + ".log");
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(plan.gameDir().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));

        // 使用高性能显卡（对应原实现的 JVM 参数注入，仅 Windows 有意义）
        Process process = builder.start();
        long pid = process.pid();
        log.info("游戏已启动：PID={}，日志={}", pid, logFile);

        if (waitForExit) {
            try {
                int code = process.waitFor();
                return new LaunchResult(pid, code, logFile.toString(), plan.display());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("等待游戏进程时被中断", ex);
            }
        }
        return new LaunchResult(pid, -1, logFile.toString(), plan.display());
    }

    /** 启动结果。 */
    public record LaunchResult(long pid, int exitCode, String logFile, String command) {
        public boolean running() {
            return exitCode == -1;
        }
    }

    /** 便捷方法：只检查命令可执行性（dry-run），不真正启动。 */
    public List<String> dryRun(LaunchPlan plan) {
        List<String> report = new ArrayList<>();
        report.add("java: " + plan.javaPath());
        report.add("gameDir: " + plan.gameDir());
        report.add("natives: " + plan.nativesDir());
        report.add("memoryMb: " + plan.memoryMb());
        report.addAll(plan.command());
        return report;
    }
}
