package com.meloong.pcl.launch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.LibraryResolver;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.RuleEvaluator;
import com.meloong.pcl.version.VersionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 启动参数构建的端到端校验：占位符替换、去重、内存与 GC 参数、主类位置。
 */
class LaunchCommandBuilderTest {

    @TempDir
    Path minecraftDir;

    private LaunchCommandBuilder builder;
    private VersionResolver resolver;
    private JavaRuntime java;

    private static final String VERSION_JSON = """
            {
              "id": "1.20.1",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "assetIndex": {"id": "5", "url": "https://example.invalid/5.json", "sha1": "aa", "size": 1},
              "releaseTime": "2023-06-12T13:25:51+00:00",
              "libraries": [
                {"name": "com.mojang:logging:1.1.1"},
                {"name": "org.lwjgl:lwjgl:3.3.1"}
              ],
              "arguments": {
                "game": [
                  "--username", "${auth_player_name}",
                  "--version", "${version_name}",
                  "--gameDir", "${game_directory}",
                  "--assetsDir", "${assets_root}",
                  "--assetIndex", "${assets_index_name}",
                  "--uuid", "${auth_uuid}",
                  "--accessToken", "${auth_access_token}",
                  "--userType", "${user_type}",
                  "--versionType", "${version_type}",
                  "--width", "${resolution_width}",
                  "--height", "${resolution_height}",
                  {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"}
                ],
                "jvm": [
                  "-Djava.library.path=${natives_directory}",
                  "-cp", "${classpath}",
                  {"rules": [{"action": "allow", "os": {"name": "windows"}}], "value": "-Dos.name=Windows 10"}
                ]
              }
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        PclProperties properties = new PclProperties();
        properties.setMinecraftDir(minecraftDir.toString());
        properties.setLauncherVersion("2.99.0.0");

        ObjectMapper mapper = new ObjectMapper();
        resolver = new VersionResolver(mapper, properties);
        RuleEvaluator ruleEvaluator = new RuleEvaluator();
        LibraryResolver libraryResolver = new LibraryResolver(ruleEvaluator, properties);
        builder = new LaunchCommandBuilder(properties, ruleEvaluator, libraryResolver, new MemoryAllocator());

        Path dir = minecraftDir.resolve("versions").resolve("1.20.1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.20.1.json"), VERSION_JSON);

        String executable = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(executable != null, "无法获取当前 JVM 路径，跳过");
        java = JavaRuntime.ofExecutable(Path.of(executable));
        assumeTrue(java.check(), "当前 JVM 检查未通过，跳过");
    }

    @Test
    void buildsCompleteCommandWithPlaceholdersReplaced() throws IOException {
        ResolvedVersion version = resolver.resolve("1.20.1");
        LaunchPlan plan = builder.build(version, java,
                new LaunchOptions("1.20.1", "Alex", null, "0", "legacy", 2048, false, 1280, 720,
                        "", true, List.of(), List.of()), 3);

        List<String> command = plan.command();
        assertEquals(java.executable().toString(), command.get(0));
        assertTrue(command.contains("-Xmx2048m"), "应写入内存参数");
        assertTrue(command.contains("-Dlog4j2.formatMsgNoLookups=true") || java.major() < 8);
        assertTrue(command.contains(version.mainClass()), "主类必须在参数列表中");
        // 所有占位符都应被替换
        assertTrue(command.stream().noneMatch(arg -> arg.contains("${")), "存在未替换的占位符：" + command);
        // -cup 值与类路径一致
        int cpIndex = command.indexOf("-cp");
        assertTrue(cpIndex > 0);
        assertEquals(plan.classpath(), command.get(cpIndex + 1));
        assertTrue(plan.classpath().contains("libraries"));
        // 演示用户参数不应出现（features 不满足）
        assertFalse(command.contains("--demo"));
        // 启动器信息
        assertTrue(command.contains("PCL"));
        assertEquals(2048, plan.memoryMb());
        assertTrue(plan.display().contains(java.executable().toString()));
    }

    @Test
    void gcArgsFollowJavaMajor() throws IOException {
        ResolvedVersion version = resolver.resolve("1.20.1");
        LaunchPlan plan = builder.build(version, java,
                LaunchOptions.forInstance("1.20.1"), 0);
        boolean g1 = plan.command().contains("-XX:+UseG1GC");
        boolean zgc = plan.command().contains("-XX:+UseZGC");
        assertTrue(g1 || zgc, "应注入 GC 参数");
        if (!g1) {
            assertTrue(plan.command().contains("-XX:+UnlockExperimentalVMOptions"));
        }
        assertNotNull(plan.nativesDir());
        assertTrue(plan.memoryMb() >= 512);
    }

    @Test
    void splitArgumentsKeepsQuotedSegments() {
        List<String> parts = LaunchCommandBuilder.splitArguments("-Dname=\"my value\" --x 1 -Da=ok");
        assertEquals(List.of("-Dname=\"my value\"", "--x", "1", "-Da=ok"), parts);
    }

    @Test
    void deduplicatePrefersLaterGameArgValue() {
        List<String> args = List.of("--width", "800", "--width", "1280", "--tweakClass", "a", "--tweakClass", "b");
        List<String> result = LaunchCommandBuilder.deduplicate(args, false);
        assertEquals(List.of("--width", "1280", "--tweakClass", "a", "--tweakClass", "b"), result);
    }

    @Test
    void deduplicateKeepsNegativeNumberAsValue() {
        List<String> args = List.of("--width", "-50", "--height", "100");
        assertEquals(args, LaunchCommandBuilder.deduplicate(args, false));
    }
}
