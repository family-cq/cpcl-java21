package com.meloong.pcl.download;

import com.meloong.pcl.config.PclProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Mod 复用：同名同内容的 jar 直接复制，不再联网下载。
 *
 * <p>国内到 cdn.modrinth.com 的单连接只有 30~40 KB/s，复用本机已有文件是最彻底的加速。
 */
class LocalModLibraryTest {

    @Test
    void 命中同名同大小的本地文件() throws Exception {
        Path root = Files.createTempDirectory("pcl-lib-hit");
        Path mods = root.resolve(".minecraft").resolve("mods");
        Files.createDirectories(mods);
        Path jar = mods.resolve("jei-1.20.1.jar");
        byte[] content = "fake-jar-content".getBytes();
        Files.write(jar, content);

        PclProperties properties = new PclProperties();
        properties.setDataDir(root.toString());
        LocalModLibrary library = new LocalModLibrary(properties);

        NetFile file = NetFile.of("https://cdn.modrinth.com/x.jar",
                root.resolve("instance").resolve("mods").resolve("jei-1.20.1.jar"),
                sha1(content), content.length, "测试 Mod", SourceKind.LIBRARY);

        assertEquals(jar, library.find(file).orElseThrow(), "应命中已有的同名 jar");
        assertTrue(library.scannedRoots().contains(mods), "mods 目录应进入扫描范围");
    }

    @Test
    void 版本目录与并列整合包里的mods都能命中() throws Exception {
        Path root = Files.createTempDirectory("pcl-lib-versions");
        Path shared = root.resolve("shared").resolve(".minecraft");
        Files.createDirectories(shared.resolve("mods"));
        Path versionMods = shared.resolve("versions").resolve("勇者之章3").resolve("mods");
        Files.createDirectories(versionMods);
        Path jar = versionMods.resolve("create-0.5.1.jar");
        byte[] content = "create".getBytes();
        Files.write(jar, content);

        PclProperties properties = new PclProperties();
        // 实例在 shared 的兄弟目录下，共享 .minecraft 在上一级
        properties.setMinecraftDir(shared.toString());
        LocalModLibrary library = new LocalModLibrary(properties);

        NetFile file = NetFile.of("https://cdn.modrinth.com/create.jar",
                root.resolve("instance").resolve("mods").resolve("create-0.5.1.jar"),
                sha1(content), content.length, "测试 Mod", SourceKind.LIBRARY);
        assertEquals(jar, library.find(file).orElseThrow(), "versions/*/mods 也应被索引");
    }

    @Test
    void 内容不一致时不命中() throws Exception {
        Path root = Files.createTempDirectory("pcl-lib-miss");
        Path mods = root.resolve(".minecraft").resolve("mods");
        Files.createDirectories(mods);
        Path jar = mods.resolve("same-name.jar");
        Files.write(jar, "old-version".getBytes());

        PclProperties properties = new PclProperties();
        properties.setDataDir(root.toString());
        LocalModLibrary library = new LocalModLibrary(properties);

        NetFile file = NetFile.of("https://cdn.modrinth.com/same-name.jar",
                root.resolve("instance").resolve("mods").resolve("same-name.jar"),
                sha1("new-version".getBytes()), "new-version".length(), "测试 Mod", SourceKind.LIBRARY);
        assertTrue(library.find(file).isEmpty(), "大小或 SHA1 不一致必须重新下载");
    }

    private String sha1(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
    }
}
