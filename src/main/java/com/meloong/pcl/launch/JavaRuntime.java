package com.meloong.pcl.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个 Java 运行时，移植自 <c>PCLCS/Java.cs</c>。
 *
 * <p>检查方式与原实现一致：执行 <c>java -XshowSettings:properties -version</c>，
 * 从中解析版本号、位数与 <c>file.encoding</c> / <c>native.encoding</c>。
 */
public final class JavaRuntime {

    private static final Logger log = LoggerFactory.getLogger(JavaRuntime.class);

    private static final Pattern VERSION_IN_QUOTES = Pattern.compile("version \"([^\"]+)\"");
    private static final Pattern OPENJDK = Pattern.compile("openjdk ([0-9]+)");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    private final Path binFolder;
    private final Path executable;

    private volatile boolean checked;
    private volatile boolean available;
    private volatile int major = -1;
    private volatile String fullVersion = "";
    private volatile String fileEncoding;
    private volatile String nativeEncoding;
    private volatile String rawOutput;

    private JavaRuntime(Path binFolder, Path executable) {
        this.binFolder = binFolder;
        this.executable = executable;
    }

    public static JavaRuntime ofBinFolder(Path binFolder) {
        return new JavaRuntime(binFolder, binFolder.resolve(executableName()));
    }

    public static JavaRuntime ofExecutable(Path executable) {
        return new JavaRuntime(executable.getParent(), executable);
    }

    private static String executableName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    public Path binFolder() {
        return binFolder;
    }

    public Path executable() {
        return executable;
    }

    public int major() {
        return major;
    }

    public String fullVersion() {
        return fullVersion;
    }

    public String fileEncoding() {
        return fileEncoding;
    }

    public String nativeEncoding() {
        return nativeEncoding;
    }

    public boolean available() {
        return available;
    }

    /** 是否已检查过。 */
    public boolean checked() {
        return checked;
    }

    /** 检查该 Java 是否可用并解析版本信息，对应 <c>Java.CheckAsync</c>。 */
    public synchronized boolean check() {
        if (checked) {
            return available;
        }
        checked = true;
        available = false;
        if (!Files.isRegularFile(executable)) {
            log.debug("未找到 java 可执行文件：{}", executable);
            return false;
        }
        try {
            String output = run(List.of(executable.toString(), "-XshowSettings:properties", "-version"), 15);
            rawOutput = output;
            if (output.isBlank()) {
                return false;
            }
            String lower = output.toLowerCase();
            if (lower.contains("/lib/ext exists")) {
                log.warn("无法运行该 Java，请删除 lib/ext 目录后重试：{}", executable);
                return false;
            }
            if (lower.contains("a fatal error") || lower.contains("error: ")) {
                log.warn("无法运行该 Java：{}", executable);
                return false;
            }
            String versionString = parseVersionString(output);
            if (versionString == null) {
                log.debug("未找到该 Java 的版本号：{}", executable);
                return false;
            }
            if (versionString.startsWith("1.")) {
                versionString = versionString.substring(2);
            }
            String[] segments = versionString.split("[._+-]");
            int parsedMajor = segments.length > 0 ? leadingInt(segments[0]) : -1;
            if (parsedMajor <= 4 || parsedMajor >= 100) {
                log.warn("分析 Java 版本失败（{}）：{}", executable, versionString);
                return false;
            }
            // 与原实现一致：仅接受 64 位 Java；非 Windows 平台跳过该判断。
            // 注意 java -XshowSettings 输出为 "64-Bit"（大写 B），因此这里忽略大小写，
            // 并用 sun.arch.data.model 兜底。
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                boolean is64Bit = lower.contains("64-bit") || "64".equals(getProperty(output, "sun.arch.data.model"));
                if (!is64Bit) {
                    log.warn("该 Java 为 32 位版本，请安装 64 位的 Java：{}", executable);
                    return false;
                }
            }
            major = parsedMajor;
            fullVersion = String.join(".", segments).replaceAll("\\.+$", "");
            fileEncoding = getProperty(output, "file.encoding");
            nativeEncoding = getProperty(output, "native.encoding");
            if (nativeEncoding == null) {
                nativeEncoding = fileEncoding;
            }
            available = true;
        } catch (Exception ex) {
            log.debug("检查 Java 失败（{}）：{}", executable, ex.getMessage());
            available = false;
        }
        return available;
    }

    private static String parseVersionString(String output) {
        Matcher matcher = VERSION_IN_QUOTES.matcher(output);
        if (matcher.find()) {
            return matcher.group(1).replace("_", ".").replace("+", ".");
        }
        matcher = OPENJDK.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int leadingInt(String value) {
        Matcher matcher = LEADING_DIGITS.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static String getProperty(String output, String name) {
        String prefix = name + " = ";
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    static String run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("执行超时：" + String.join(" ", command));
        }
        return output.toString();
    }

    /** 用户友好的描述，对应 <c>Java.ToString()</c>。 */
    @Override
    public String toString() {
        return "Java " + (major < 0 ? "尚未检查" : major) + " (" + fullVersion + ")：" + binFolder;
    }

    /** 供 JSON 输出使用的信息。 */
    public record Info(String path, String version, int major, boolean available,
                       String fileEncoding, String nativeEncoding) {
    }

    public Info info() {
        String version = fullVersion == null || fullVersion.isBlank()
                ? (major < 0 ? "unknown" : String.valueOf(major))
                : fullVersion;
        return new Info(executable.toString(), version, major, available, fileEncoding, nativeEncoding);
    }

    /** 用于测试/内部构造的属性列表。 */
    public List<String> describe() {
        List<String> result = new ArrayList<>();
        result.add(executable.toString());
        result.add("nativeEncoding=" + nativeEncoding);
        return result;
    }
}
