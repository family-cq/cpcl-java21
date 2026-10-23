package com.meloong.pcl.web;

import com.meloong.pcl.auth.MicrosoftAuthService;
import com.meloong.pcl.settings.Settings;
import com.meloong.pcl.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MicrosoftAuthService authService;
    private final SettingsService settingsService;

    public AuthController(MicrosoftAuthService authService, SettingsService settingsService) {
        this.authService = authService;
        this.settingsService = settingsService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Settings.Account account = settingsService.raw().account;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", account.type);
        result.put("playerName", account.playerName);
        result.put("microsoftName", account.microsoftName);
        result.put("microsoftUuid", account.microsoftUuid);
        result.put("loggedIn", !account.accessToken.isBlank());
        return result;
    }

    /** 申请设备码，界面拿到 userCode 后让玩家去浏览器输入。 */
    @PostMapping("/device")
    public MicrosoftAuthService.DeviceFlow device() {
        return authService.start();
    }

    /** 轮询登录结果。 */
    @PostMapping("/poll")
    public MicrosoftAuthService.LoginResult poll(@RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("deviceCode");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("缺少设备码");
        }
        return authService.poll(code);
    }

    /** 切换到离线账户。 */
    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestBody(required = false) Map<String, String> body) {
        authService.useOffline(body == null ? null : body.get("playerName"));
        return status();
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        authService.logout();
        return status();
    }
}
