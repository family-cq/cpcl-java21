package com.meloong.pcl.community;

import com.meloong.pcl.download.InstallJobService;
import com.meloong.pcl.download.ModpackInstaller;
import com.meloong.pcl.download.VersionManifestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 社区资源接口：搜索、详情、下载到实例。
 */
@RestController
@RequestMapping("/api/community")
public class CommunityController {

    private final CommunityService communityService;
    private final VersionManifestService manifestService;
    private final InstallJobService jobs;
    private final ModpackInstaller modpackInstaller;

    public CommunityController(CommunityService communityService, VersionManifestService manifestService,
                               InstallJobService jobs, ModpackInstaller modpackInstaller) {
        this.communityService = communityService;
        this.manifestService = manifestService;
        this.jobs = jobs;
        this.modpackInstaller = modpackInstaller;
    }

    /** 搜索社区资源。 */
    @GetMapping("/search")
    public CommunityService.SearchResult search(
            @RequestParam(defaultValue = "mod") String type,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String gameVersion,
            @RequestParam(required = false) String loader,
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page) {
        return communityService.search(type, query, gameVersion, loader, source, category, page);
    }

    /** 项目详情 + 可选版本列表。 */
    @GetMapping("/{source}/project/{id}")
    public CommunityService.ProjectDetail project(@PathVariable String source, @PathVariable String id,
                                                  @RequestParam(required = false) String gameVersion,
                                                  @RequestParam(required = false) String loader) {
        return communityService.project(source, id, gameVersion, loader);
    }

    /**
     * 下载指定版本到当前实例的对应目录。
     *
     * <p>整合包会转成安装任务；同名实例已存在时返回 conflict，由前端确认后走
     * {@code /api/install/modpack/confirm}。
     */
    @PostMapping("/install")
    public Object install(@RequestBody CommunityService.InstallRequest request) throws Exception {
        Map<String, Object> result = communityService.install(request);
        if (Boolean.TRUE.equals(result.get("packJob"))) {
            String saved = String.valueOf(result.get("saved"));
            String displayName = String.valueOf(result.get("displayName"));
            return jobs.submit("modpack", "安装整合包 " + displayName, ModpackInstaller.STAGES, progress -> {
                try {
                    return modpackInstaller.install(java.nio.file.Path.of(saved), displayName, "", null, progress);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                } finally {
                    try {
                        Files.deleteIfExists(java.nio.file.Path.of(saved));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        return result;
    }

    /** 供「版本」下拉框使用的正式版列表（从新到旧）。 */
    @GetMapping("/game-versions")
    public List<String> gameVersions() {
        List<String> versions = new ArrayList<>();
        for (VersionManifestService.ManifestVersion version : manifestService.versions()) {
            if ("release".equals(version.type())) {
                versions.add(version.id());
            }
        }
        return versions;
    }
}
