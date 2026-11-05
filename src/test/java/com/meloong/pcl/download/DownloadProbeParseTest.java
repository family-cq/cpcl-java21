package com.meloong.pcl.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片下载的探测解析：forgecdn 的 edge 域名会先 302 到 mediafilez，
 * 只有最终响应头里的长度才是真实文件大小，Range 也只在最终地址上有效。
 */
class DownloadProbeParseTest {

    private static String join(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void 跨过302取最终地址与长度() {
        String output = join(
                "HTTP/1.1 302 Found",
                "Location: https://mediafilez.forgecdn.net/files/9395/720/jei.jar",
                "Content-Length: 0",
                "",
                "HTTP/1.1 200 OK",
                "Content-Length: 4878734",
                "Accept-Ranges: bytes",
                "",
                "https://mediafilez.forgecdn.net/files/9395/720/jei.jar");

        DownloadManager.Probe probe = DownloadManager.parseProbe(
                "https://edge.forgecdn.net/files/9395/720/jei.jar", output);

        assertNotNull(probe);
        assertEquals(4878734L, probe.length());
        assertTrue(probe.ranges(), "Accept-Ranges: bytes 应判定可切分片");
        assertEquals("https://mediafilez.forgecdn.net/files/9395/720/jei.jar", probe.url());
    }

    @Test
    void 从ContentRange取总大小() {
        String output = join(
                "HTTP/1.1 206 Partial Content",
                "Content-Range: bytes 0-0/73916104",
                "Content-Length: 1",
                "",
                "https://mediafilez.forgecdn.net/files/1/1/big.jar");

        DownloadManager.Probe probe = DownloadManager.parseProbe("https://edge/big.jar", output);

        assertNotNull(probe);
        assertEquals(73916104L, probe.length());
        assertTrue(probe.ranges());
        assertEquals("https://mediafilez.forgecdn.net/files/1/1/big.jar", probe.url());
    }

    @Test
    void 不支持Range时只给长度() {
        String output = join(
                "HTTP/1.1 200 OK",
                "Content-Length: 2048",
                "",
                "https://example.com/a.jar");

        DownloadManager.Probe probe = DownloadManager.parseProbe("https://example.com/a.jar", output);

        assertNotNull(probe);
        assertEquals(2048L, probe.length());
        assertFalse(probe.ranges(), "没有 Accept-Ranges 不能切分片");
    }

    @Test
    void 拿不到长度就放弃分片() {
        String output = join(
                "HTTP/1.1 200 OK",
                "Transfer-Encoding: chunked",
                "",
                "https://example.com/a.jar");

        assertNull(DownloadManager.parseProbe("https://example.com/a.jar", output));
    }

    @Test
    void 忽略跳转中间那段响应头() {
        // 302 的 Content-Length: 0 不能当成文件大小
        String output = join(
                "HTTP/1.1 301 Moved Permanently",
                "Content-Length: 0",
                "",
                "HTTP/1.1 302 Found",
                "Content-Length: 0",
                "",
                "HTTP/1.1 200 OK",
                "Content-Length: 123456",
                "Accept-Ranges: bytes",
                "",
                "https://cdn.example.com/final.jar");

        DownloadManager.Probe probe = DownloadManager.parseProbe("https://short.link/x", output);

        assertNotNull(probe);
        assertEquals(123456L, probe.length());
        assertEquals("https://cdn.example.com/final.jar", probe.url());
    }

    @Test
    void 只有一字节也认() {
        String output = join(
                "HTTP/1.1 206 Partial Content",
                "Content-Range: bytes 0-0/1",
                "",
                "https://example.com/one.bin");
        DownloadManager.Probe probe = DownloadManager.parseProbe("https://example.com/one.bin", output);
        assertNotNull(probe);
        assertEquals(1L, probe.length());
    }
}
