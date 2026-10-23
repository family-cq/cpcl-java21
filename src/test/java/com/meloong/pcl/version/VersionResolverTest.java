package com.meloong.pcl.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 inheritsFrom 合并语义：子版本优先、参数按子 → 父顺序拼接、支持库去重。
 */
class VersionResolverTest {

    @TempDir
    Path minecraftDir;

    private VersionResolver resolver;

    private static final String PARENT_JSON = """
            {
              "id": "1.20.1",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "assetIndex": {"id": "5", "url": "https://example.invalid/5.json", "sha1": "aa", "size": 1},
              "downloads": {"client": {"url": "https://example.invalid/client.jar", "sha1": "bb", "size": 100}},
              "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
              "releaseTime": "2023-06-12T13:25:51+00:00",
              "libraries": [
                {"name": "com.mojang:logging:1.1.1", "downloads": {"artifact": {"path": "com/mojang/logging/1.1.1/logging-1.1.1.jar", "sha1": "cc", "size": 10, "url": "https://example.invalid/logging.jar"}}},
                {"name": "org.lwjgl:lwjgl:3.3.1", "rules": [{"action": "allow", "os": {"name": "windows"}}]}
              ],
              "arguments": {
                "game": ["--username", "${auth_player_name}", "--version", "${version_name}"],
                "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
              }
            }
            """;

    private static final String CHILD_JSON = """
            {
              "id": "1.20.1-forge",
              "inheritsFrom": "1.20.1",
              "type": "release",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "libraries": [
                {"name": "net.minecraftforge:forge:1.20.1-47.2.0", "url": "https://maven.minecraftforge.net/"},
                {"name": "com.mojang:logging:1.1.1"}
              ],
              "arguments": {
                "game": ["--launchTarget", "forgeclient"],
                "jvm": ["--add-modules", "ALL-MODULE-PATH"]
              }
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        PclProperties properties = new PclProperties();
        properties.setMinecraftDir(minecraftDir.toString());
        resolver = new VersionResolver(new ObjectMapper(), properties);

        writeVersion("1.20.1", PARENT_JSON);
        writeVersion("1.20.1-forge", CHILD_JSON);
    }

    private void writeVersion(String id, String json) throws IOException {
        Path dir = minecraftDir.resolve("versions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    @Test
    void childOverridesMainClassAndKeepsParentLibraries() throws IOException {
        ResolvedVersion version = resolver.resolve("1.20.1-forge");
        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", version.mainClass());
        assertEquals("1.20.1", version.jarId());
        assertEquals("5", version.assetsIndexName());
        assertEquals(3, version.libraries().size(), "重复的 logging 坐标应只保留子版本一次");
        assertEquals("net.minecraftforge:forge:1.20.1-47.2.0", version.libraries().get(0).name());
    }

    @Test
    void argumentsAreConcatenatedChildFirst() throws IOException {
        ResolvedVersion version = resolver.resolve("1.20.1-forge");
        List<String> jvm = version.jvmArgs().stream().map(node -> node.asText()).toList();
        assertEquals("--add-modules", jvm.get(0));
        assertTrue(jvm.contains("${classpath}"));
        List<String> game = version.gameArgs().stream().map(node -> node.asText()).toList();
        assertEquals("--launchTarget", game.get(0));
        assertTrue(game.contains("${auth_player_name}"));
    }

    @Test
    void versionDetectionUsesInheritedNameAndLoader() throws IOException {
        ResolvedVersion version = resolver.resolve("1.20.1-forge");
        assertEquals("1.20.1", version.mcVersion().vanillaName());
        assertEquals(200, version.mcVersion().drop());
        assertTrue(version.mcVersion().hasLoader(ModLoader.FORGE));
        // PCL 的正则只截取 Forge 构建号（net.minecraftforge:forge:1.20.1-47.2.0 → 47.2.0）
        assertEquals("47.2.0", version.mcVersion().modLoaderVersions().get(ModLoader.FORGE));
        assertFalse(version.mcVersion().snapshot());
        assertEquals(17, version.requiredJavaMajor());
    }

    @Test
    void missingInheritedVersionDoesNotBreakResolution() throws IOException {
        writeVersion("orphan", """
                {"id":"orphan","inheritsFrom":"not-installed","mainClass":"x.Y","libraries":[]}
                """);
        ResolvedVersion version = resolver.resolve("orphan");
        assertEquals("x.Y", version.mainClass());
        assertEquals("not-installed", version.jarId());
    }
}
