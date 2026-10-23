package com.meloong.pcl.version;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本名相关工具，移植自 <c>PCLCS/Minecraft/McVersion.cs</c> 的静态方法。
 */
public final class McVersionNames {

    /** 原版版本号正则，对应 McVersion.cs 中的 VersionPattern。 */
    public static final Pattern VERSION_PATTERN = Pattern.compile(
            "(([1-9][0-9]w[0-9]{2}[a-g])|((1|[2-9][0-9])\\.[0-9]+(\\.[0-9]+)?(-(pre|rc|snapshot-?)[1-9]*| Pre-Release( [1-9])?)?))(_unobfuscated)?");

    private static final Pattern STANDARD_DROP = Pattern.compile("^[2-9]\\d\\.\\d+");
    private static final Pattern LEGACY_PREFIX = Pattern.compile("^1\\.\\d");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    private static final List<String> FOOL_NAMES = List.of(
            "15w14a", "1.rv-pre1", "3d shareware v1.34", "20w14∞",
            "22w13oneblockatatime", "23w13a_or_b", "24w14potato", "25w14craftmine", "26w14a");

    private McVersionNames() {
    }

    /**
     * 将版本名转换为可比较的三段式版本号。
     *
     * <p>旧格式（1.x）会把次版本移到主版本，例如 1.20.3 → 20.0.3；愚人节版本返回其基准版本；
     * 无法识别时返回 {@link VersionNumber#UNKNOWN}。
     */
    public static VersionNumber nameToVersion(String name) {
        if (name == null) {
            return VersionNumber.UNKNOWN;
        }
        name = name.toLowerCase().replace("_unobfuscated", "").replace(" unobfuscated", "");
        // 愚人节版本
        switch (name) {
            case "15w14a" -> {
                return nameToVersion("1.8.3");
            }
            case "24w14potato" -> {
                return nameToVersion("1.20.4");
            }
            case "25w14craftmine" -> {
                return nameToVersion("1.21.4");
            }
            case "26w14a" -> {
                return nameToVersion("26.1.1");
            }
            default -> {
            }
        }
        if (name.startsWith("2.0")) {
            return nameToVersion("1.5.1");
        }
        if (name.contains(".rv-pre")) {
            return nameToVersion("1.9.2");
        }
        if (name.contains("shareware")) {
            return nameToVersion("1.13.2");
        }
        if (name.startsWith("20w14") && !name.equals("20w14a")) {
            return nameToVersion("1.15.2");
        }
        if (name.contains("oneblockatatime")) {
            return nameToVersion("1.18.2");
        }
        if (name.contains("23w13a") && !name.equals("20w13a")) {
            return nameToVersion("1.19.4");
        }
        // 其余版本
        String[] segments = name.split("[ _\\-.]", -1);
        if (name.startsWith("1.")) {
            return new VersionNumber(parseSegment(segments, 1), 0, parseSegment(segments, 2));
        }
        if (STANDARD_DROP.matcher(name).find()) {
            return new VersionNumber(parseSegment(segments, 0), parseSegment(segments, 1), parseSegment(segments, 2));
        }
        return VersionNumber.UNKNOWN;
    }

    private static int parseSegment(String[] segments, int index) {
        if (index >= segments.length) {
            return 0;
        }
        // 只读取该段开头连续的数字，忽略其后的预览版标记
        Matcher matcher = LEADING_DIGITS.matcher(segments[index]);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    /** 将版本名转换为 Drop 序数；无法转换时返回 209（对应 1.20）。 */
    public static int versionToDrop(String vanillaName) {
        VersionNumber version = nameToVersion(vanillaName);
        return version.valid() ? version.major() * 10 + version.minor() : 209;
    }

    /** 将 Drop 序数转换为版本字符串。 */
    public static String dropToVersion(int drop) {
        if (drop >= 250) {
            return (drop / 10) + "." + (drop % 10);
        }
        return drop != 209 ? "1." + (drop / 10) : "1.20";
    }

    /** 判断版本字符串是否符合 Minecraft 原版格式，例如 1.x、26.x。 */
    public static boolean isStandardFormat(String version) {
        if (version == null) {
            return false;
        }
        if (LEGACY_PREFIX.matcher(version).find()) {
            return true;
        }
        Matcher matcher = STANDARD_DROP.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        try {
            return Double.parseDouble(matcher.group()) >= 26;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** 是否为快照、预发布版、发布候选或实验性版本。 */
    public static boolean isSnapshotName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("w") || lower.contains("snapshot") || lower.contains("rc")
                || lower.contains("pre") || lower.contains("experimental") || lower.contains("-");
    }

    /** 是否为愚人节版本。 */
    public static boolean isFoolName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        if (lower.startsWith("2.0") || lower.startsWith("20w14inf")) {
            return true;
        }
        return FOOL_NAMES.contains(lower);
    }

    /** 提取字符串中第一个匹配 {@link #VERSION_PATTERN} 的版本号。 */
    public static String seekVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
