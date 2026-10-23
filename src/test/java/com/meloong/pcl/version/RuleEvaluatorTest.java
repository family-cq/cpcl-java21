package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对照 <c>ModMinecraft.McJsonRuleCheck</c> 的规则判定。
 */
class RuleEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleEvaluator evaluator = new RuleEvaluator();

    private static final RuleEvaluator.Environment WINDOWS_10 =
            new RuleEvaluator.Environment("windows", "10.0", "amd64", Map.of());
    private static final RuleEvaluator.Environment WINDOWS_7 =
            new RuleEvaluator.Environment("windows", "6.1", "amd64", Map.of());
    private static final RuleEvaluator.Environment LINUX =
            new RuleEvaluator.Environment("linux", "5.15", "amd64", Map.of());

    @Test
    void noRulesMeansAllowed() throws Exception {
        assertTrue(evaluator.check(null, WINDOWS_10));
        // 与原实现一致：空数组没有任何 allow 命中，结果为 false
        assertFalse(evaluator.check(mapper.readTree("[]"), WINDOWS_10));
    }

    @Test
    void allowThenDisallowByOsVersion() throws Exception {
        JsonNode rules = mapper.readTree("""
                [{"action":"allow","os":{"name":"windows"}},
                 {"action":"disallow","os":{"name":"windows","version":"^10\\\\."}}]
                """);
        assertFalse(evaluator.check(rules, WINDOWS_10));
        assertTrue(evaluator.check(rules, WINDOWS_7));
        assertFalse(evaluator.check(rules, LINUX));
    }

    @Test
    void disallowRuleWithoutAllowDefaultsToFalse() throws Exception {
        JsonNode rules = mapper.readTree("""
                [{"action":"disallow","os":{"name":"osx"}}]
                """);
        assertFalse(evaluator.check(rules, WINDOWS_10));
    }

    @Test
    void x86AndArmArchAreExcluded() throws Exception {
        JsonNode rules = mapper.readTree("""
                [{"action":"allow","os":{"arch":"x86"}}]
                """);
        assertFalse(evaluator.check(rules, WINDOWS_10));

        JsonNode arm = mapper.readTree("""
                [{"action":"allow","os":{"arch":"arm64"}}]
                """);
        assertFalse(evaluator.check(arm, WINDOWS_10));
    }

    @Test
    void featureRulesFollowPclBehaviour() throws Exception {
        JsonNode demo = mapper.readTree("""
                [{"action":"allow","features":{"is_demo_user":true}}]
                """);
        assertFalse(evaluator.check(demo, WINDOWS_10));

        JsonNode quickPlay = mapper.readTree("""
                [{"action":"allow","features":{"has_quick_plays_support":true}}]
                """);
        assertFalse(evaluator.check(quickPlay, WINDOWS_10));

        RuleEvaluator.Environment withResolution = WINDOWS_10.withFeatures(Map.of("has_custom_resolution", true));
        JsonNode resolution = mapper.readTree("""
                [{"action":"allow","features":{"has_custom_resolution":true}}]
                """);
        assertTrue(evaluator.check(resolution, withResolution));
        assertFalse(evaluator.check(resolution, WINDOWS_10));
    }
}
