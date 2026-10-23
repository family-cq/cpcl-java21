package com.meloong.pcl.web;

import com.meloong.pcl.download.InstallJobService;
import com.meloong.pcl.download.LoaderInstaller;
import com.meloong.pcl.download.ModpackInstaller;
import com.meloong.pcl.download.VersionManifestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/install")
public class InstallController {

    private final InstallJobService jobService;
    private final VersionManifestService manifestService;
    private final LoaderInstaller loaderInstaller;
    private final ModpackInstaller modpackInstaller;

    public InstallController(InstallJobService jobService, VersionManifestService manifestService,
                             LoaderInstaller loaderInstaller, ModpackInstaller modpackInstaller) {
        this.jobService = jobService;
        this.manifestService = manifestService;
        this.loaderInstaller = loaderInstaller;
        this.modpackInstaller = modpackInstaller;
    }

    /** 原版安装（后台任务）。 */
    @PostMapping("/version")
    public InstallJobService.JobView installVersion(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.getOrDefault("id", ""));
        boolean assets = Boolean.TRUE.equals(body.get("assets"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("缺少版本号");
        }
        return jobService.submit("version", "安装原版 " + id, progress -> {
            progress.update(5, "获取版本信息…");
            try {
                VersionManifestService.InstallReport report = manifestService.install(id, assets, progress);
                progress.update(95, "校验完成，新增 " + report.downloaded() + " 个文件");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instance", id);
                result.put("downloaded", report.downloaded());
                result.put("skipped", report.skipped());
                result.put("failed", report.failed());
                result.put("errors", report.errors());
                return result;
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        });
    }

    /** 加载器安装（后台任务）。 */
    @PostMapping("/loader")
    public InstallJobService.JobView installLoader(@RequestBody Map<String, Object> body) {
        String mc = String.valueOf(body.getOrDefault("mcVersion", ""));
        String loader = String.valueOf(body.getOrDefault("loader", ""));
        String version = body.get("version") == null ? "" : String.valueOf(body.get("version"));
        if (mc.isBlank() || loader.isBlank()) {
            throw new IllegalArgumentException("缺少原版版本或加载器");
        }
        String title = "安装 " + loader + (version.isBlank() ? "" : " " + version) + "（" + mc + "）";
        return jobService.submit("loader", title, LoaderInstaller.STAGES, progress -> {
            try {
                return loaderInstaller.install(mc, loader, version, progress);
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        });
    }

    /** 某个原版版本可选的加载器版本。 */
    @GetMapping("/loaders")
    public List<LoaderInstaller.LoaderOption> loaders(@RequestParam String mc) {
        return loaderInstaller.options(mc);
    }

    /**
     * 上传整合包（拖拽 / 选择文件）。
     *
     * <p>同名实例已存在且未指定 {@code conflict} 时不启动安装，返回
     * {@code {conflict:true, pendingId, name, …}}，由前端询问用户「覆盖 / 另建」后
     * 调用 {@link #confirmModpack} 继续。
     *
     * @param instance 可选：裸 zip（无版本清单且识别失败）时解压到的目标实例
     * @param conflict 可选：{@code overwrite} 覆盖同名实例 / {@code rename} 另建
     */
    @PostMapping(value = "/modpack", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object modpack(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "instance", required = false) String instance,
                          @RequestParam(value = "conflict", required = false) String conflict)
            throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("缺少文件");
        }
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".zip") && !lower.endsWith(".mrpack")) {
            throw new IllegalArgumentException("仅支持 .zip / .mrpack 整合包");
        }
        Path saved;
        try (var in = file.getInputStream()) {
            saved = modpackInstaller.saveUpload(fileName, in);
        }
        String displayName = fileName.replaceFirst("\\.[^.]+$", "");
        String targetInstance = instance == null ? "" : instance;

        // 快速解析清单，按包名判断是否会与现有实例重名
        //（裸 zip 优先自动识别并新建实例，因此统一检查）
        ModpackInstaller.PackInfo info = modpackInstaller.inspect(saved);
        String desired = modpackInstaller.desiredInstanceId(info, displayName);
        if ((conflict == null || conflict.isBlank())
                && Files.exists(modpackInstaller.versionsPath().resolve(desired))) {
            ModpackInstaller.Pending pending = modpackInstaller.holdPending(
                    saved, displayName, targetInstance, info, desired);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("conflict", true);
            response.put("pendingId", pending.id());
            response.put("name", desired);
            response.put("format", info.format());
            response.put("mcVersion", info.mcVersion());
            response.put("loader", info.loader());
            response.put("loaderVersion", info.loaderVersion());
            response.put("fileCount", info.fileCount());
            return response;
        }
        return submitPackJob(saved, displayName, targetInstance, conflict);
    }

    /** 用户对重复包做出选择后继续安装。 */
    @PostMapping("/modpack/confirm")
    public InstallJobService.JobView confirmModpack(@RequestBody Map<String, Object> body) {
        String pendingId = String.valueOf(body.getOrDefault("pendingId", ""));
        String conflict = String.valueOf(body.getOrDefault("conflict", ""));
        if (!ModpackInstaller.CONFLICT_OVERWRITE.equals(conflict)
                && !ModpackInstaller.CONFLICT_RENAME.equals(conflict)) {
            throw new IllegalArgumentException("conflict 必须是 overwrite 或 rename");
        }
        ModpackInstaller.Pending pending = modpackInstaller.takePending(pendingId);
        return submitPackJob(pending.file(), pending.displayName(), pending.targetInstance(), conflict);
    }

    private InstallJobService.JobView submitPackJob(Path saved, String displayName,
                                                    String targetInstance, String conflict) {
        // 带上并行流水线阶段清单，前端据此渲染步骤条
        return jobService.submit("modpack", "安装整合包 " + displayName, ModpackInstaller.STAGES, progress -> {
            try {
                return modpackInstaller.install(saved, displayName, targetInstance, conflict, progress);
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            } finally {
                // 转存的临时包用完即删，节省磁盘
                try {
                    Files.deleteIfExists(saved);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @GetMapping("/jobs/{id}")
    public InstallJobService.JobView job(@PathVariable String id) {
        return jobService.get(id);
    }

    @GetMapping("/jobs")
    public List<InstallJobService.JobView> jobs() {
        return jobService.list();
    }
}
