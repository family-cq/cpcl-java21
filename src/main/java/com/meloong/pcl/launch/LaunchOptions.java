package com.meloong.pcl.launch;

import com.meloong.pcl.auth.OfflineAuthService;

import java.util.List;

/**
 * 一次启动的选项，对应原工程 <c>McLaunchOptions</c>。
 *
 * @param instanceId  版本名（versions 下的文件夹名）
 * @param playerName  玩家名
 * @param uuid        角色 UUID，留空则按离线模式生成
 * @param accessToken 访问令牌
 * @param userType    user_type 参数，正版为 msa，离线为 legacy
 * @param memoryMb    分配内存（MB），<=0 时自动计算
 * @param fullscreen  是否全屏
 * @param width       窗口宽度
 * @param height      窗口高度
 * @param serverIp    自动进入的服务器，可带端口
 * @param isolate     是否启用版本隔离（游戏目录 = versions/&lt;id&gt;）
 * @param extraJvmArgs  额外 JVM 参数
 * @param extraGameArgs 额外游戏参数
 */
public record LaunchOptions(
        String instanceId,
        String playerName,
        String uuid,
        String accessToken,
        String userType,
        int memoryMb,
        boolean fullscreen,
        int width,
        int height,
        String serverIp,
        boolean isolate,
        List<String> extraJvmArgs,
        List<String> extraGameArgs) {

    public LaunchOptions {
        playerName = playerName == null || playerName.isBlank() ? "Steve" : playerName;
        userType = userType == null || userType.isBlank() ? "legacy" : userType;
        accessToken = accessToken == null ? "0" : accessToken;
        if (uuid == null || uuid.isBlank()) {
            uuid = OfflineAuthService.offlineUuid(playerName);
        }
        width = width <= 0 ? 854 : width;
        height = height <= 0 ? 480 : height;
        serverIp = serverIp == null ? "" : serverIp.trim();
        extraJvmArgs = extraJvmArgs == null ? List.of() : List.copyOf(extraJvmArgs);
        extraGameArgs = extraGameArgs == null ? List.of() : List.copyOf(extraGameArgs);
    }

    public static LaunchOptions forInstance(String instanceId) {
        return new LaunchOptions(instanceId, "Steve", null, "0", "legacy",
                0, false, 854, 480, "", true, List.of(), List.of());
    }
}
