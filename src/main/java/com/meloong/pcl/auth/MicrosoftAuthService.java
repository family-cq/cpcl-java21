package com.meloong.pcl.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.settings.Settings;
import com.meloong.pcl.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微软正版登录（OAuth 2.0 设备码流程 → Xbox Live → XSTS → Minecraft Services）。
 *
 * <p>微软早已停用账号密码直登，因此这里用设备码流程：界面展示一次性的代码，
 * 玩家在浏览器里输入后，后端轮询拿到令牌，再换成 Minecraft 的 accessToken。
 * 客户端 ID 需要在配置里填写（每个启动器都应使用自己注册的 Azure 应用）。
 */
@Service
public class MicrosoftAuthService {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftAuthService.class);

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE = "https://login.microsoftonline.com/%s/oauth2/v2.0/devicecode";
    private static final String TOKEN = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String XBL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    private final PclProperties properties;
    private final SettingsService settingsService;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public MicrosoftAuthService(PclProperties properties, SettingsService settingsService,
                                RestTemplate restTemplate, ObjectMapper mapper) {
        this.properties = properties;
        this.settingsService = settingsService;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    public record DeviceFlow(String deviceCode, String userCode, String verificationUri,
                             int expiresIn, int interval, String message) {
    }

    public record Profile(String name, String uuid, String accessToken) {
    }

    public record LoginResult(String state, String message, Profile profile) {
    }

    private String clientId() {
        String id = properties.getAuthClientId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("未配置微软客户端 ID：请在 application.yml 的 pcl.auth-client-id "
                    + "或环境变量 PCL_MS_CLIENT_ID 中填入你自己注册的 Azure 应用 ID，然后重启启动器");
        }
        return id.trim();
    }

    private String tenant() {
        String tenant = properties.getAuthTenant();
        return tenant == null || tenant.isBlank() ? "consumers" : tenant.trim();
    }

    /** 申请设备码。 */
    public DeviceFlow start() {
        String cid = clientId();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", cid);
        form.add("scope", SCOPE);
        JsonNode response = postForm(String.format(DEVICE_CODE, tenant()), form);
        return new DeviceFlow(response.path("device_code").asText(),
                response.path("user_code").asText(),
                response.path("verification_uri").asText(),
                response.path("expires_in").asInt(900),
                Math.max(2, response.path("interval").asInt(5)),
                response.path("message").asText());
    }

    /** 轮询一次设备码状态。 */
    public LoginResult poll(String deviceCode) {
        String cid = clientId();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", cid);
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        form.add("device_code", deviceCode);

        JsonNode token;
        try {
            token = postForm(String.format(TOKEN, tenant()), form);
        } catch (HttpStatusCodeException ex) {
            JsonNode error = readError(ex);
            String code = error.path("error").asText();
            return switch (code) {
                case "authorization_pending" -> new LoginResult("pending", "等待在浏览器中完成登录…", null);
                case "slow_down" -> new LoginResult("pending", "操作过快，稍后再试…", null);
                case "expired_token" -> new LoginResult("expired", "设备码已过期，请重新获取", null);
                case "authorization_declined" -> new LoginResult("failed", "已取消登录", null);
                default -> new LoginResult("failed",
                        "登录失败：" + (error.path("error_description").asText(code)), null);
            };
        }

        String msToken = token.path("access_token").asText();
        String refresh = token.path("refresh_token").asText();
        long expiresIn = token.path("expires_in").asLong(3600);

        String xblToken;
        String uhs;
        try {
            JsonNode xbl = postJson(XBL, Map.of(
                    "Properties", Map.of(
                            "AuthMethod", "RPS",
                            "SiteName", "user.auth.xboxlive.com",
                            "RpsTicket", "d=" + msToken),
                    "RelyingParty", "http://auth.xboxlive.com",
                    "TokenType", "JWT"));
            xblToken = xbl.path("Token").asText();
            uhs = xbl.path("DisplayClaims").path("xui").path(0).path("uhs").asText();
        } catch (HttpStatusCodeException ex) {
            return new LoginResult("failed", "Xbox Live 认证失败：" + ex.getStatusCode(), null);
        }

        String xstsToken;
        try {
            JsonNode xsts = postJson(XSTS, Map.of(
                    "Properties", Map.of("SandboxId", "RESTRICTED", "UserTokens", List.of(xblToken)),
                    "RelyingParty", "rp://api.minecraftservices.com/",
                    "TokenType", "JWT"));
            xstsToken = xsts.path("Token").asText();
            String xstsUhs = xsts.path("DisplayClaims").path("xui").path(0).path("uhs").asText();
            if (!xstsUhs.isBlank()) {
                uhs = xstsUhs;
            }
        } catch (HttpStatusCodeException ex) {
            long xerr = readError(ex).path("XErr").asLong();
            String reason;
            if (xerr == 2148916233L) {
                reason = "该微软账号没有 Xbox 账号，请先在 xbox.com 创建";
            } else if (xerr == 2148916235L) {
                reason = "该账号所在地区不支持 Xbox Live";
            } else if (xerr == 2148916238L) {
                reason = "未成年人账号需要先加入家庭组";
            } else {
                reason = "XSTS 认证失败（XErr=" + xerr + "）";
            }
            return new LoginResult("failed", reason, null);
        }

        String mcToken;
        try {
            JsonNode login = postJson(MC_LOGIN, Map.of("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken));
            mcToken = login.path("access_token").asText();
        } catch (HttpStatusCodeException ex) {
            return new LoginResult("failed", "Minecraft 服务认证失败：" + ex.getStatusCode(), null);
        }

        JsonNode profile;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(mcToken);
            profile = restTemplate.exchange(MC_PROFILE, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                return new LoginResult("failed", "该账号没有购买 Minecraft: Java 版", null);
            }
            return new LoginResult("failed", "获取角色信息失败：" + ex.getStatusCode(), null);
        }
        if (profile == null) {
            return new LoginResult("failed", "获取角色信息失败：返回为空", null);
        }

        Settings.Account account = settingsService.raw().account;
        account.type = "microsoft";
        account.microsoftName = profile.path("name").asText();
        account.microsoftUuid = profile.path("id").asText();
        account.accessToken = mcToken;
        account.refreshToken = refresh;
        account.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        settingsService.updateAccount(account);
        log.info("正版登录成功：{}（{}）", account.microsoftName, account.microsoftUuid);
        return new LoginResult("success", "登录成功",
                new Profile(account.microsoftName, account.microsoftUuid, mcToken));
    }

    /** 退出正版账号。 */
    public void logout() {
        Settings.Account account = settingsService.raw().account;
        account.type = "offline";
        account.accessToken = "";
        account.refreshToken = "";
        account.microsoftName = "";
        account.microsoftUuid = "";
        account.expiresAt = 0;
        settingsService.updateAccount(account);
    }

    /** 切换到离线账户。 */
    public void useOffline(String playerName) {
        Settings.Account account = settingsService.raw().account;
        account.type = "offline";
        account.playerName = playerName == null || playerName.isBlank() ? "Steve" : playerName.trim();
        settingsService.updateAccount(account);
    }

    private JsonNode postForm(String url, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
        JsonNode body = response.getBody();
        return body == null ? mapper.createObjectNode() : body;
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        JsonNode result = response.getBody();
        return result == null ? mapper.createObjectNode() : result;
    }

    private JsonNode readError(HttpStatusCodeException ex) {
        try {
            return mapper.readTree(ex.getResponseBodyAsString());
        } catch (Exception ignored) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("error", ex.getStatusCode().toString());
            fallback.put("error_description", ex.getStatusText());
            return mapper.valueToTree(fallback);
        }
    }
}
