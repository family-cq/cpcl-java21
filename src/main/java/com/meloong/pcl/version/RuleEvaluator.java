package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 版本 JSON 的 rules 判定，移植自 <c>ModMinecraft.McJsonRuleCheck</c>。
 *
 * <p>行为与原实现一致：按顺序累积 allow / disallow，最后一条命中的规则决定结果；
 * 未命中任何规则时返回 false，没有 rules 字段时返回 true。
 */
@Component
public class RuleEvaluator {

    /** 运行时环境，对应原工程的 OSVersion / CPU 架构。 */
    public record Environment(String osName, String osVersion, String arch, Map<String, Boolean> features) {

        public static Environment current() {
            return new Environment(detectOsName(), System.getProperty("os.version", ""),
                    System.getProperty("os.arch", ""), Map.of());
        }

        public static String detectOsName() {
            String name = System.getProperty("os.name", "").toLowerCase();
            if (name.contains("win")) {
                return "windows";
            }
            if (name.contains("mac") || name.contains("darwin")) {
                return "osx";
            }
            return "linux";
        }

        public Environment withFeatures(Map<String, Boolean> extra) {
            if (extra == null || extra.isEmpty()) {
                return this;
            }
            Map<String, Boolean> merged = new HashMap<>(features);
            merged.putAll(extra);
            return new Environment(osName, osVersion, arch, merged);
        }
    }

    /** 判定整组 rules 是否通过。 */
    public boolean check(JsonNode rules) {
        return check(rules, Environment.current());
    }

    public boolean check(JsonNode rules, Environment environment) {
        if (rules == null || rules.isNull() || !rules.isArray()) {
            return true;
        }
        boolean required = false;
        for (JsonNode rule : rules) {
            boolean matched = true;
            JsonNode os = rule.get("os");
            if (os != null && os.isObject()) {
                JsonNode osNameNode = os.get("name");
                if (osNameNode != null) {
                    String osName = osNameNode.asText();
                    if ("unknown".equals(osName)) {
                        // 不限制
                    } else if (osName.equals(environment.osName())) {
                        JsonNode versionNode = os.get("version");
                        if (versionNode != null) {
                            matched = matched && regexMatch(environment.osVersion(), versionNode.asText());
                        }
                    } else {
                        matched = false;
                    }
                }
                JsonNode archNode = os.get("arch");
                if (archNode != null) {
                    String arch = archNode.asText();
                    matched = matched && !"x86".equals(arch) && !arch.contains("arm");
                }
            }
            JsonNode features = rule.get("features");
            if (features != null && features.isObject()) {
                for (Iterator<String> it = features.fieldNames(); it.hasNext(); ) {
                    String key = it.next();
                    // 与原实现一致：quick_play 与 is_demo_user 一律不启用
                    if ("quick_play".equals(key) || "is_demo_user".equals(key)) {
                        matched = false;
                        break;
                    }
                    if (!Boolean.TRUE.equals(environment.features().get(key))) {
                        matched = false;
                        break;
                    }
                }
            }
            String action = rule.path("action").asText();
            if ("allow".equals(action)) {
                if (matched) {
                    required = true;
                }
            } else if (matched) {
                required = false;
            }
        }
        return required;
    }

    private static boolean regexMatch(String value, String pattern) {
        try {
            return Pattern.compile(pattern).matcher(value == null ? "" : value).find();
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }

    /** 当前系统下的 natives classifier 名，例如 natives-windows。 */
    public static String nativesClassifier() {
        String os = Environment.detectOsName();
        String arch = System.getProperty("os.arch", "amd64");
        if ("osx".equals(os)) {
            return arch.contains("aarch64") ? "natives-macos-arm64" : "natives-macos";
        }
        return switch (os) {
            case "windows" -> arch.contains("64") ? "natives-windows" : "natives-windows-x86";
            default -> arch.contains("64") ? "natives-linux" : "natives-linux-x86";
        };
    }

    /** natives 键名（版本 JSON 中的 natives 是 os 名 → classifier）。 */
    public static Set<String> nativesKeys() {
        Set<String> keys = new HashSet<>();
        keys.add(Environment.detectOsName());
        return keys;
    }
}
