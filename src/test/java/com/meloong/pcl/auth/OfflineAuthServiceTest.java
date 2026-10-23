package com.meloong.pcl.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineAuthServiceTest {

    @Test
    void offlineUuidIsStableAndUppercaseFree() {
        String uuid = OfflineAuthService.offlineUuid("Notch");
        assertEquals(32, uuid.length());
        assertTrue(uuid.matches("[0-9a-f]{32}"));
        assertEquals(uuid, OfflineAuthService.offlineUuid("Notch"));
        assertNotEquals(uuid, OfflineAuthService.offlineUuid("Herobrine"));
    }

    @Test
    void offlineUuidMatchesMinecraftConvention() {
        // UUID.nameUUIDFromBytes("OfflinePlayer:" + name) 与 Minecraft 服务端的离线 UUID 算法一致
        String expected = java.util.UUID.nameUUIDFromBytes(
                "OfflinePlayer:Steve".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        assertEquals(expected, OfflineAuthService.offlineUuid("Steve"));
    }

    @Test
    void loginDataUsesLegacyUserType() {
        OfflineAuthService.LoginData data = new OfflineAuthService().offline("Alex");
        assertEquals("Alex", data.name());
        assertEquals("legacy", data.userType());
        assertEquals("0", data.accessToken());
    }
}
