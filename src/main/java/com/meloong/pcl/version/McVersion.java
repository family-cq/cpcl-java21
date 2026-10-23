package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从版本 JSON 识别出的 Minecraft 版本与已安装组件信息。
 *
 * <p>移植自 <c>PCLCS/Minecraft/McVersion.cs</c>：版本号推断顺序、加载器识别正则、快照/愚人节判定均保持一致。
 */
public final class McVersion {

    private static final Pattern OPTIFINE = Pattern.compile("(?<=HD_U_)[^\":/]+");
    private static final Pattern LITELOADER = Pattern.compile("(?<=com\\.mumfrey:liteloader:)[^\":/]+");
    private static final Pattern FABRIC = Pattern.compile(
            "(?<=(net\\.fabricmc:fabric-loader:|org\\.quiltmc:quilt-loader:))[0-9.]+(\\+build\\.[0-9]+)?");
    private static final Pattern FORGE_1 = Pattern.compile("(?<=forge:[0-9.]+(?:_pre[0-9]*)?-)[0-9.]+");
    private static final Pattern FORGE_2 = Pattern.compile("(?<=net\\.minecraftforge:minecraftforge:)[0-9.]+");
    private static final Pattern FORGE_3 = Pattern.compile("(?<=net\\.minecraftforge:fmlloader:[0-9.]+-)[0-9.]+");
    private static final Pattern LIB_FORGE = Pattern.compile("(?<=net.minecraftforge:forge:)[0-9]{1,2}.[0-9+.]+");
    private static final Pattern LIB_FMLLOADER = Pattern.compile("(?<=net.minecraftforge:fmlloader:)[0-9]{1,2}.[0-9+.]+");
    private static final Pattern LIB_OPTIFINE = Pattern.compile("(?<=optifine:OptiFine:)[0-9]{1,2}.[0-9+.]+");
    private static final Pattern LIB_INTERMEDIARY = Pattern.compile("(?<=((fabricmc)|(quiltmc)):intermediary:)[^\"]*");
    private static final Pattern DOWNLOAD_VERSION = Pattern.compile("(?<=launcher.mojang.com/mc/game/)[^/]*");

    private final String vanillaName;
    private final VersionNumber version;
    private final boolean reliable;
    private final Instant releaseTime;
    private final boolean fool;
    private final boolean snapshot;
    private final String optiFine;
    private final Map<ModLoader, String> modLoaderVersions;

    private McVersion(String vanillaName, VersionNumber version, boolean reliable, Instant releaseTime,
                      boolean fool, boolean snapshot, String optiFine, Map<ModLoader, String> modLoaderVersions) {
        this.vanillaName = vanillaName;
        this.version = version;
        this.reliable = reliable;
        this.releaseTime = releaseTime;
        this.fool = fool;
        this.snapshot = snapshot;
        this.optiFine = optiFine;
        this.modLoaderVersions = Collections.unmodifiableMap(modLoaderVersions);
    }

    /**
     * 通过实例名与版本 JSON 识别版本信息，对应 <c>McVersion(string instanceName, JObject json)</c>。
     */
    public static McVersion detect(String instanceName, JsonNode json) {
        if (json == null) {
            json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        String jsonText = json.toString();

        Instant releaseTime = Instant.EPOCH;
        JsonNode releaseNode = json.get("releaseTime");
        if (releaseNode != null && !releaseNode.isNull()) {
            try {
                releaseTime = Instant.parse(releaseNode.asText());
            } catch (Exception ignored) {
                // 与 PCL 一致：无法解析时保持 1970 年
            }
        }

        Guess guess = guessVanillaName(instanceName, json, releaseTime);
        String vanillaName = (guess.name() == null ? "Unknown" : guess.name())
                .replace("_unobfuscated", "").replace(" Unobfuscated", "");

        String jsonType = json.path("type").asText("");

        String optiFine = null;
        if (containsIgnoreCase(jsonText, "optifine")) {
            Matcher matcher = OPTIFINE.matcher(jsonText);
            optiFine = matcher.find() ? matcher.group() : "未知版本";
        }

        Instant fixed = releaseTime.atOffset(ZoneOffset.UTC).plusHours(2).toInstant();
        LocalDate fixedDate = fixed.atOffset(ZoneOffset.UTC).toLocalDate();
        boolean fool = "fool".equals(jsonType) || McVersionNames.isFoolName(vanillaName)
                || (fixedDate.getMonthValue() == 4 && fixedDate.getDayOfMonth() == 1 && "snapshot".equals(jsonType));
        boolean snapshot = McVersionNames.isSnapshotName(vanillaName)
                || containsIgnoreCase(instanceName, "combat")
                || "snapshot".equals(jsonType) || "pending".equals(jsonType) || "fool".equals(jsonType);

        Map<ModLoader, String> loaders = new LinkedHashMap<>();
        if (containsIgnoreCase(jsonText, "liteloader")) {
            loaders.put(ModLoader.LITELOADER, seek(LITELOADER, jsonText, "未知版本"));
        }
        if (containsIgnoreCase(jsonText, "net.fabricmc:fabric-loader")
                || containsIgnoreCase(jsonText, "org.quiltmc:quilt-loader")) {
            String raw = seek(FABRIC, jsonText, "未知版本");
            // 原实现直接 Remove("+build")，会把 0.15.11+build.3 拼成 0.15.113；此处保留分隔，语义更清晰
            loaders.put(containsIgnoreCase(jsonText, "quilt-loader") ? ModLoader.QUILT : ModLoader.FABRIC,
                    raw.replace("+build.", ".").replace("+build", ""));
        } else if (containsIgnoreCase(jsonText, "net.neoforge")) {
            String value = findArgumentValue(json, "--fml.neoForgeVersion");
            if (value == null) {
                value = findArgumentValue(json, "--fml.forgeVersion");
            }
            loaders.put(ModLoader.NEOFORGE, value == null ? "未知版本" : value);
        } else if (containsIgnoreCase(jsonText, "minecraftforge")) {
            String value = seek(FORGE_1, jsonText, null);
            if (value == null) {
                value = seek(FORGE_2, jsonText, null);
            }
            if (value == null) {
                value = seek(FORGE_3, jsonText, null);
            }
            loaders.put(ModLoader.FORGE, value == null ? "未知版本" : value);
        }

        return new McVersion(vanillaName, McVersionNames.nameToVersion(vanillaName), guess.reliable(),
                releaseTime, fool, snapshot, optiFine, loaders);
    }

    private record Guess(String name, boolean reliable) {
    }

    /** 对应 McVersion.cs 的 GetVanillaName。 */
    private static Guess guessVanillaName(String instanceName, JsonNode json, Instant releaseTime) {
        boolean reliable = true;
        int year = releaseTime.atOffset(ZoneOffset.UTC).getYear();
        if (year > 2000 && year < 2013) {
            return new Guess("Old", reliable);
        }
        String type = json.path("type").asText("");
        if ("pending".equals(type)) {
            return new Guess("pending", reliable);
        }
        // PCL 下载的版本信息
        if (json.hasNonNull("clientVersion")) {
            return new Guess(json.get("clientVersion").asText(), reliable);
        }
        // HMCL 下载的版本信息
        JsonNode patches = json.get("patches");
        if (patches != null && patches.isArray()) {
            for (JsonNode patch : patches) {
                if ("game".equals(patch.path("id").asText()) && patch.hasNonNull("version")) {
                    return new Guess(patch.get("version").asText(), reliable);
                }
            }
        }
        // Forge / NeoForge 参数
        String fml = findArgumentValue(json, "--fml.mcVersion");
        if (fml != null && !fml.isBlank()) {
            return new Guess(fml, reliable);
        }
        // 继承版本
        String inherit = json.path("inheritsFrom").asText("");
        if (!inherit.isBlank()) {
            String jar = json.path("jar").asText("");
            return new Guess(jar.isBlank() ? inherit : jar, reliable);
        }
        // 下载地址
        JsonNode downloads = json.get("downloads");
        if (downloads != null) {
            Matcher matcher = DOWNLOAD_VERSION.matcher(downloads.toString());
            if (matcher.find()) {
                return new Guess(matcher.group(), reliable);
            }
        }
        // 加载器版本号
        String librariesText = json.path("libraries").toString();
        for (Pattern pattern : new Pattern[]{LIB_FORGE, LIB_FMLLOADER, LIB_OPTIFINE, LIB_INTERMEDIARY}) {
            String match = seek(pattern, librariesText, null);
            if (match != null) {
                return new Guess(match, reliable);
            }
        }
        // jar 项
        if (json.hasNonNull("jar")) {
            return new Guess(json.get("jar").asText(), reliable);
        }
        // JSON 的 ID
        String fromId = McVersionNames.seekVersion(json.path("id").asText(""));
        if (fromId != null) {
            return new Guess(fromId, reliable);
        }
        reliable = false;
        // 文件夹名
        String fromFolder = McVersionNames.seekVersion(instanceName);
        if (fromFolder != null) {
            return new Guess(fromFolder, false);
        }
        // JSON 中出现的版本号（排除 libraries）
        return new Guess(McVersionNames.seekVersion(json.toString()), false);
    }

    /** 读取 arguments.game 中紧跟在指定开关后的值。 */
    public static String findArgumentValue(JsonNode json, String name) {
        JsonNode game = json.path("arguments").path("game");
        if (!game.isArray()) {
            return null;
        }
        for (int i = 0; i + 1 < game.size(); i++) {
            if (name.equals(game.get(i).asText())) {
                return game.get(i + 1).asText();
            }
        }
        return null;
    }

    private static String seek(Pattern pattern, String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : fallback;
    }

    private static boolean containsIgnoreCase(String text, String search) {
        return text != null && search != null && text.toLowerCase().contains(search.toLowerCase());
    }

    // ---------------------------------------------------------------- 访问器

    public String vanillaName() {
        return vanillaName;
    }

    public VersionNumber version() {
        return version;
    }

    public boolean reliable() {
        return reliable;
    }

    public Instant releaseTime() {
        return releaseTime;
    }

    public boolean fool() {
        return fool;
    }

    public boolean snapshot() {
        return snapshot;
    }

    public String optiFine() {
        return optiFine;
    }

    public boolean hasOptiFine() {
        return optiFine != null;
    }

    /** 可供比较的原版 Drop 序数，例如 1.21.5 为 215。 */
    public int drop() {
        return version.valid() ? version.major() * 10 + version.minor() : 209;
    }

    public Map<ModLoader, String> modLoaderVersions() {
        return modLoaderVersions;
    }

    public boolean hasLoader(ModLoader loader) {
        return modLoaderVersions.containsKey(loader);
    }

    public boolean modable() {
        return modLoaderVersions.keySet().stream().anyMatch(ModLoader.modable()::contains);
    }

    public EnumMap<ModLoader, String> loaderMap() {
        return new EnumMap<>(modLoaderVersions);
    }

    @Override
    public String toString() {
        return vanillaName + (modLoaderVersions.isEmpty() ? "" : " " + modLoaderVersions);
    }
}
