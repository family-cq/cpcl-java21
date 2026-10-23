package com.meloong.pcl.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 离线登录，移植自 <c>ModLaunch.McLoginLegacyUuid</c>。
 *
 * <p>PCL 用 <c>MD5("OfflinePlayer:" + name)</c> 生成离线 UUID（与 Minecraft 服务端一致），
 * 这里直接使用 Java 的 {@link UUID#nameUUIDFromBytes}——它同样是 MD5 并写入 version 3 与变体位。
 */
@Service
public class OfflineAuthService {

    public static String offlineUuid(String playerName) {
        String name = playerName == null || playerName.isBlank() ? "Steve" : playerName;
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return uuid.toString().replace("-", "");
    }

    /** 生成离线登录数据。 */
    public LoginData offline(String playerName) {
        return new LoginData(playerName, offlineUuid(playerName), "0", "legacy");
    }

    public record LoginData(String name, String uuid, String accessToken, String userType) {
    }
}
