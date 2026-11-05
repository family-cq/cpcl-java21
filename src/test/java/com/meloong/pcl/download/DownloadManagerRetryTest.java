package com.meloong.pcl.download;

import com.meloong.pcl.config.PclProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载重试上限：超时/失败最多重试 2 次（含首次共 3 次），不再无限重试。
 *
 * <p>用必然下载不到的 file:// 地址制造失败（比连不通的端口快，不受代理影响），
 * 校验异常信息里的重试次数文案，避免以后把上限改回 3 次以上。
 */
class DownloadManagerRetryTest {

    @Test
    void 下载失败最多重试两次() throws Exception {
        PclProperties properties = new PclProperties();
        DownloadManager manager = new DownloadManager(properties, new DownloadSources(properties));
        Path dir = Files.createTempDirectory("pcl-retry-test");
        NetFile file = NetFile.of("file:///D:/definitely-not-exists/pcl-retry-test.jar",
                dir.resolve("never-download.jar"), null, 0, "测试文件", SourceKind.LIBRARY);

        long start = System.currentTimeMillis();
        IOException ex = assertThrows(IOException.class, () -> manager.download(file));
        long cost = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().startsWith("已重试 2 次仍失败"),
                "失败信息应写明最多重试 2 次，实际：" + ex.getMessage());
        // 3 次尝试之间只有 0.5s + 1s 的退避，本地必然失败不会拖太久
        assertTrue(cost < 15_000, "重试上限失效，耗时 " + cost + "ms");
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "失败的临时文件应被清理");
        }
    }
}
