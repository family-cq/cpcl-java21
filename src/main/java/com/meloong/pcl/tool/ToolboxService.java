package com.meloong.pcl.tool;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 百宝箱：今日人品、内存优化、清理垃圾。
 */
@Service
public class ToolboxService {

    private static final Logger log = LoggerFactory.getLogger(ToolboxService.class);

    private final PclProperties properties;

    public ToolboxService(PclProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------ 今日人品

    private static final String[][] PRAISE = {
            {"今天不宜启动游戏，建议先把作业写完。", "运气在低谷，但低谷也是风景。", "别灰心，明天翻盘的概率很大。"},
            {"运气平平，适合挂机钓鱼。", "今天的你像一杯温水，平稳但不失温度。", "普通的一天也值得好好过。"},
            {"运气不错，挖矿时记得多带几把镐。", "小有惊喜的一天，注意脚下别摔进岩浆。", "状态在线，可以挑战一下困难模式。"},
            {"运气很好，开箱可能出好东西。", "今天的你和附魔台很合拍。", "幸运女神今天对你眨眼了。"},
            {"人品爆发，建议立刻去抽卡（不是）。", "今天的你，走进下界都能捡到钻石。", "欧气缠身，小心别把运气一次用完。"}
    };

    /** 今日人品：同一天同一名字结果固定，1~1000。 */
    public Map<String, Object> luck(String playerName) {
        String name = playerName == null || playerName.isBlank()
                ? Settings.MemoryInfo.totalMb() + "-player"
                : playerName.trim();
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Random random = new Random(day.hashCode() * 31L + name.toLowerCase(Locale.ROOT).hashCode());
        int value = 1 + random.nextInt(1000);
        String[] tier = PRAISE[Math.min(PRAISE.length - 1, value / 200)];
        String praise = tier[random.nextInt(tier.length)];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value);
        result.put("level", level(value));
        result.put("praise", praise);
        result.put("date", LocalDate.now().toString());
        result.put("playerName", name);
        return result;
    }

    private String level(int value) {
        if (value <= 200) {
            return "大凶";
        }
        if (value <= 400) {
            return "小凶";
        }
        if (value <= 600) {
            return "平";
        }
        if (value <= 800) {
            return "小吉";
        }
        if (value <= 950) {
            return "大吉";
        }
        return "欧皇";
    }

    // ------------------------------------------------------------ 内存优化

    /**
     * 内存优化：对每个进程调用 psapi!EmptyWorkingSet，把可换出的工作集交还系统。
     * 这是各类「内存优化」工具的实际做法，效果取决于系统当前压力。
     */
    public Map<String, Object> optimizeMemory() {
        long before = Settings.MemoryInfo.availableMb();
        int handled = 0;
        String message = "已完成";
        if (!isWindows()) {
            message = "当前系统不是 Windows，仅统计内存";
        } else {
            // 显式 OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_SET_QUOTA) 再 EmptyWorkingSet，
            // 比 Process.Handle 更稳；成功数通过 count=N 回传，便于界面诊断
            String script = "Add-Type -MemberDefinition "
                    + "'[DllImport(\"kernel32.dll\", SetLastError=true)] public static extern IntPtr OpenProcess(uint a, bool i, int pid); "
                    + "[DllImport(\"psapi.dll\")] public static extern bool EmptyWorkingSet(IntPtr h); "
                    + "[DllImport(\"kernel32.dll\")] public static extern bool CloseHandle(IntPtr h);' "
                    + "-Name Ps -Namespace W32; "
                    + "$n = 0; "
                    + "Get-Process | ForEach-Object { try { "
                    + "$h = [W32.Ps]::OpenProcess(0x0500, $false, $_.Id); "
                    + "if ($h -ne [IntPtr]::Zero) { if ([W32.Ps]::EmptyWorkingSet($h)) { $n++ }; "
                    + "[void][W32.Ps]::CloseHandle($h) } } catch {} }; "
                    + "Write-Output \"count=$n\"";
            handled = runPowerShell(script);
            if (handled < 0) {
                message = "优化失败：无法调用系统接口（可尝试以管理员身份运行）";
                handled = 0;
            } else if (handled == 0) {
                message = "没有可以换出的内存页（系统当前压力不大，属正常现象）";
            }
        }
        // 触发一次 GC，释放启动器自身占用
        System.gc();
        sleep(800);
        long after = Settings.MemoryInfo.availableMb();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("beforeMb", before);
        result.put("afterMb", after);
        result.put("freedMb", Math.max(0, after - before));
        result.put("processes", handled);
        result.put("message", message);
        log.info("内存优化：{} MB → {} MB（处理 {} 个进程）", before, after, handled);
        return result;
    }

    private int runPowerShell(String script) {
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.strip()).append('\n');
                }
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (process.exitValue() != 0) {
                log.warn("PowerShell 退出码 {}：{}", process.exitValue(), output);
                return -1;
            }
            Matcher matcher = Pattern.compile("count=(\\d+)").matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (Exception ex) {
            log.warn("调用 PowerShell 失败：{}", ex.getMessage());
            return -1;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 清理垃圾

    /** 扫描可清理的日志 / 崩溃报告，返回文件清单。 */
    public Map<String, Object> scanGarbage() {
        Path root = properties.minecraftPath();
        List<Map<String, Object>> files = new ArrayList<>();
        long total = 0;
        if (!Files.isDirectory(root)) {
            return report(files, total, root);
        }

        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("crash-reports"));
        roots.add(root.resolve("logs"));
        Path versionsDir = properties.versionsPath();
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> stream = Files.list(versionsDir)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    roots.add(dir.resolve("crash-reports"));
                    roots.add(dir.resolve("logs"));
                });
            } catch (IOException ex) {
                log.warn("扫描版本目录失败：{}", ex.getMessage());
            }
        }

        for (Path dir : roots) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    long size = fileSize(file);
                    if (size <= 0 && !Files.isRegularFile(file)) {
                        continue;
                    }
                    files.add(entry(file, size));
                    total += size;
                }
            } catch (IOException ex) {
                log.warn("扫描 {} 失败：{}", dir, ex.getMessage());
            }
        }

        // 游戏目录下的 hs_err_pid / replay_pid 崩溃日志
        try (Stream<Path> stream = Files.walk(root, 3)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith("hs_err_pid") || name.startsWith("replay_pid")) {
                    long size = fileSize(file);
                    files.add(entry(file, size));
                    total += size;
                }
            }
        } catch (IOException ex) {
            log.warn("扫描崩溃日志失败：{}", ex.getMessage());
        }

        files.sort(Comparator.comparingLong((Map<String, Object> map) -> (long) map.get("size")).reversed());
        return report(files, total, root);
    }

    /** 删除扫描到的文件；只删除 minecraft 目录内的目标。 */
    public Map<String, Object> cleanGarbage() {
        Path root = properties.minecraftPath().toAbsolutePath().normalize();
        Map<String, Object> scan = scanGarbage();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) scan.get("files");
        int deleted = 0;
        long freed = 0;
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> file : files) {
            Path path = Path.of(String.valueOf(file.get("path"))).toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                errors.add("跳过目录外文件：" + path);
                continue;
            }
            try {
                long size = fileSize(path);
                Files.deleteIfExists(path);
                deleted++;
                freed += size;
            } catch (IOException ex) {
                errors.add(path.getFileName() + "：" + ex.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("freedBytes", freed);
        result.put("errors", errors);
        log.info("清理完成：删除 {} 个文件，释放 {} 字节", deleted, freed);
        return result;
    }

    private Map<String, Object> report(List<Map<String, Object>> files, long total, Path root) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", root.toString());
        result.put("count", files.size());
        result.put("totalBytes", total);
        result.put("files", files);
        return result;
    }

    private Map<String, Object> entry(Path file, long size) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", file.toString());
        map.put("size", size);
        return map;
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0;
        }
    }
}
