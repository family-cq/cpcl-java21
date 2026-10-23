package com.meloong.pcl.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mods.toml 依赖解析。
 *
 * <p>重点是 {@code mandatory} 的解析：Forge 里它是不带引号的布尔值，
 * 早期按 {@code key="..."} 匹配取到空串，把可选依赖当成必装，
 * 结果补装出来的 Mod 被 FML 报 {@code Unsupported installed optional dependencies}。
 */
class ModDependencyResolverTest {

    /** 只用到 ObjectMapper，其余依赖解析阶段不需要。 */
    private final ModDependencyResolver resolver =
            new ModDependencyResolver(null, new ObjectMapper(), null, null, null);

    /** 取自 geckolib-forge-1.20.1-4.8.4 的真实片段。 */
    private static final String GECKOLIB = """
            modLoader="javafml"
            loaderVersion="[47,)"

            [[mods]]
            modId="geckolib"
            version="4.8.4"
            displayName="GeckoLib"

            [[dependencies.geckolib]]
                modId="forge"
                mandatory=true
                versionRange="[47.1,)"
                ordering="NONE"
                side="BOTH"
            [[dependencies.geckolib]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,)"
                ordering="NONE"
                side="BOTH"
            [[dependencies.geckolib]]
                modId="geckoanimfix"
                mandatory=false
                versionRange="[9.removeoculusgeckolibcompat,)"
                ordering="NONE"
                side="BOTH"
            """;

    @Test
    void 不带引号的布尔值也要识别出来() {
        ModDependencyResolver.JarMeta meta = resolver.parseToml(GECKOLIB);

        assertEquals("geckolib", meta.modId());
        assertEquals("4.8.4", meta.version());

        ModDependencyResolver.Dep animfix = meta.deps().stream()
                .filter(dep -> dep.modId().equals("geckoanimfix"))
                .findFirst()
                .orElseThrow();
        assertFalse(animfix.mandatory(), "mandatory=false 应识别为可选依赖");

        List<String> required = meta.deps().stream()
                .filter(ModDependencyResolver.Dep::mandatory)
                .map(ModDependencyResolver.Dep::modId)
                .toList();
        assertEquals(List.of("forge", "minecraft"), required);
    }

    @Test
    void 缺省_mandatory_按必装处理() {
        String toml = """
                [[mods]]
                modId="demo"

                [[dependencies.demo]]
                    modId="cloth_config"
                    versionRange="[11,)"
                """;
        ModDependencyResolver.JarMeta meta = resolver.parseToml(toml);
        assertTrue(meta.deps().get(0).mandatory(), "FML 里缺省即必装，不能漏装");
    }

    @Test
    void 带引号的_false_和行尾注释也要识别() {
        String toml = """
                [[mods]]
                modId="demo"

                [[dependencies.demo]]
                    modId="quoted"
                    mandatory="false"
                    versionRange="[1,)"
                [[dependencies.demo]]
                    modId="commented"
                    mandatory=false # 可选
                    versionRange="[1,)"
                """;
        ModDependencyResolver.JarMeta meta = resolver.parseToml(toml);
        assertFalse(meta.deps().get(0).mandatory());
        assertFalse(meta.deps().get(1).mandatory());
        assertEquals("[1,)", meta.deps().get(1).versionRange());
    }

    @Test
    void 只取_mods_段作为自身_id() {
        String toml = """
                [[mods]]
                modId="bomd"

                [[dependencies.bomd]]
                    modId="geckolib"
                    mandatory=true
                    versionRange="[1,)"
                """;
        ModDependencyResolver.JarMeta meta = resolver.parseToml(toml);
        assertEquals("bomd", meta.modId());
        assertEquals("geckolib", meta.deps().get(0).modId());
    }

    @Test
    void 补丁表版本比较() {
        // 2.4-Fix < 3.0：老包自带的 lionfishapi 2.4-Fix 不满足灾变 3.31 的 [3.0,)
        org.junit.jupiter.api.Assertions.assertTrue(ModDependencyResolver.versionLess("2.4-Fix", "3.0"));
        org.junit.jupiter.api.Assertions.assertTrue(ModDependencyResolver.versionLess("2.4", "3.0"));
        org.junit.jupiter.api.Assertions.assertFalse(ModDependencyResolver.versionLess("3.0", "3.0"));
        org.junit.jupiter.api.Assertions.assertFalse(ModDependencyResolver.versionLess("3.31", "3.0"));
    }

    /** 取自 L_Enders_Cataclysm-3.31 的真实声明。 */
    @Test
    void maven_区间满足性判断() {
        // Cataclysm 声明 lionfishapi [3.0,)：2.4-Fix 不满足、3.0 与 3.12 满足
        assertFalse(ModDependencyResolver.satisfies("2.4-Fix", "[3.0,)"));
        assertTrue(ModDependencyResolver.satisfies("3.0", "[3.0,)"));
        assertTrue(ModDependencyResolver.satisfies("3.12", "[3.0,)"));

        // 闭区间与上界
        assertTrue(ModDependencyResolver.satisfies("1.20.1", "[1.20.1,1.21)"));
        assertFalse(ModDependencyResolver.satisfies("1.21", "[1.20.1,1.21)"));
        assertTrue(ModDependencyResolver.satisfies("1.21", "[1.20.1,1.21]"));
        assertFalse(ModDependencyResolver.satisfies("1.19", "[1.20.1,)"));

        // 单值精确区间
        assertTrue(ModDependencyResolver.satisfies("1.20.1", "[1.20.1]"));
        assertFalse(ModDependencyResolver.satisfies("1.20.2", "[1.20.1]"));

        // 裸版本是软约束（Forge 语义），任何版本都接受
        assertTrue(ModDependencyResolver.satisfies("9.9.9", "1.20.1"));
        // 通配与空区间
        assertTrue(ModDependencyResolver.satisfies("anything", "*"));
        assertTrue(ModDependencyResolver.satisfies("anything", ""));
    }

    /** 类引用扫描的缺口包归属判定。 */
    @Test
    void 缺口包归属与分组() throws Exception {
        var method = ModDependencyResolver.class.getDeclaredMethod("gapPkgOf", String.class);
        method.setAccessible(true);
        assertEquals("dev/ftb/mods/ftbquests",
                method.invoke(null, "dev/ftb/mods/ftbquests/team/Team"));
        assertEquals("net/exmo/exmodifier/util",
                method.invoke(null, "net/exmo/exmodifier/util/ExUtil"));

        var owned = ModDependencyResolver.class.getDeclaredMethod("ownedByInstalled", String.class, Set.class);
        owned.setAccessible(true);
        Set<String> provided = Set.of("net/exmo/exmodifier/util", "com/github/L_Ender/cataclysm/event");
        assertTrue((Boolean) owned.invoke(null, "net/exmo/exmodifier/util/ExUtil", provided),
                "已装 Mod 提供的包不应再算缺口");
        assertFalse((Boolean) owned.invoke(null, "dev/ftb/mods/ftbquests/team/Team", provided));

        var covered = ModDependencyResolver.class.getDeclaredMethod("coveredBy", String.class, Set.class);
        covered.setAccessible(true);
        assertTrue((Boolean) covered.invoke(null, "net/exmo/exmodifier/util", provided));
        assertFalse((Boolean) covered.invoke(null, "dev/ftb/mods/ftbquests", provided));
    }

    /**
     * 升级判定必须极其保守：version 字段是占位符 ${file.jarVersion} 的 Mod
     * （Cucumber/TerraBlender 实测都是）清洗后是 0，直接比区间会误杀，
     * 只允许「版本可解析 + 单侧下界 [x,) + 确实低于下界」时升级。
     */
    @Test
    void 占位符版本绝不触发升级() {
        // 真实场景：lionfishapi 2.4-Fix 低于 Cataclysm 声明的 [3.0,)
        assertTrue(ModDependencyResolver.upgradeNeeded("2.4-Fix", "[3.0,)"));
        // 占位符 / 空版本：绝不升级
        assertFalse(ModDependencyResolver.upgradeNeeded("${file.jarVersion}", "[3.0,)"));
        assertFalse(ModDependencyResolver.upgradeNeeded("", "[3.0,)"));
        assertFalse(ModDependencyResolver.upgradeNeeded(null, "[3.0,)"));
        // 有上界或精确区间：不碰（判断错了会装进错误版本）
        assertFalse(ModDependencyResolver.upgradeNeeded("2.0", "[1.0,3.0)"));
        assertFalse(ModDependencyResolver.upgradeNeeded("5.0.9", "[6.0.9]"));
        // 裸版本软约束：不升级
        assertFalse(ModDependencyResolver.upgradeNeeded("1.0", "3.0"));
        // 已满足下界：不升级
        assertFalse(ModDependencyResolver.upgradeNeeded("3.0", "[3.0,)"));
        assertFalse(ModDependencyResolver.upgradeNeeded("5.14.1+1.20.1", "[5.3.1+1.20.1,)"));
    }
}
