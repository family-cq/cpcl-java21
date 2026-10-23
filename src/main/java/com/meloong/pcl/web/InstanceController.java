package com.meloong.pcl.web;

import com.meloong.pcl.download.InstallJobService;
import com.meloong.pcl.download.ModDependencyResolver;
import com.meloong.pcl.download.VersionManifestService;
import com.meloong.pcl.instance.InstanceScanner;
import com.meloong.pcl.instance.InstanceService;
import com.meloong.pcl.instance.ModScanner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceScanner instanceScanner;
    private final InstanceService instanceService;
    private final ModScanner modScanner;
    private final VersionFacade versionFacade;
    private final ModDependencyResolver dependencyResolver;
    private final VersionManifestService manifestService;
    private final InstallJobService jobs;

    public InstanceController(InstanceScanner instanceScanner, InstanceService instanceService,
                              ModScanner modScanner, VersionFacade versionFacade,
                              ModDependencyResolver dependencyResolver, VersionManifestService manifestService,
                              InstallJobService jobs) {
        this.instanceScanner = instanceScanner;
        this.instanceService = instanceService;
        this.modScanner = modScanner;
        this.versionFacade = versionFacade;
        this.dependencyResolver = dependencyResolver;
        this.manifestService = manifestService;
        this.jobs = jobs;
    }

    /** 版本列表：轻量扫描（只看 versions 目录），选中版本后再走 /{name} 做完整检测。 */
    @GetMapping
    public List<InstanceScanner.InstanceInfo> list() {
        return instanceScanner.scanQuick();
    }

    @GetMapping("/{name}")
    public InstanceScanner.InstanceInfo detail(@PathVariable String name) throws IOException {
        return instanceScanner.info(name);
    }

    @GetMapping("/{name}/mods")
    public List<InstanceService.ModView> mods(@PathVariable String name,
                                              @RequestParam(value = "updates", required = false) Boolean updates) {
        return instanceService.mods(name, Boolean.TRUE.equals(updates));
    }

    @PostMapping("/{name}/mods/{file}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable String name, @PathVariable String file)
            throws IOException {
        Path modFile = modScanner.find(modsDir(name), file)
                .orElseThrow(() -> new IllegalArgumentException("Mod 不存在：" + file));
        boolean enabled = modScanner.toggle(modFile);
        return ResponseEntity.ok(Map.of("file", file, "enabled", enabled));
    }

    /** 删除版本：连同该版本目录下的存档、配置、Mod 一起删除。 */
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) throws IOException {
        return instanceScanner.delete(name);
    }

    /** 重命名版本（版本设置 → 概览）。 */
    @PostMapping("/{name}/rename")
    public Map<String, Object> rename(@PathVariable String name, @RequestBody Map<String, Object> body)
            throws IOException {
        String newName = body.get("newName") == null ? "" : String.valueOf(body.get("newName"));
        return instanceService.rename(name, newName);
    }

    /** 打开版本 / 存档 / Mod / 截图目录（版本设置 → 概览）。 */
    @PostMapping("/{name}/open")
    public Map<String, Object> open(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body)
            throws IOException {
        String target = body == null || body.get("target") == null ? "root" : String.valueOf(body.get("target"));
        return instanceService.openFolder(name, target);
    }

    /** 导出启动脚本（生成到版本目录下的 .bat / .sh）。 */
    @PostMapping("/{name}/export-script")
    public Map<String, Object> exportScript(@PathVariable String name) throws IOException {
        return instanceService.exportLaunchScript(name);
    }

    /** 补全文件：按版本 JSON 全量核对客户端 jar / 支持库 / 资源文件。 */
    @PostMapping("/{name}/complete")
    public InstallJobService.JobView complete(@PathVariable String name) {
        return jobs.submit("complete", "补全文件 " + name, progress -> {
            try {
                progress.update(5, "核对版本文件…");
                VersionManifestService.InstallReport report = manifestService.complete(name, true, progress);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instance", name);
                result.put("downloaded", report.downloaded());
                result.put("skipped", report.skipped());
                result.put("failed", report.failed());
                result.put("errors", report.errors());
                progress.update(100, report.failed() > 0
                        ? "补全完成，仍有 " + report.failed() + " 个文件失败"
                        : "文件已补全（新增 " + report.downloaded() + " 个）");
                return result;
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        });
    }

    /** 导出为整合包（Modrinth 格式 zip，输出到 .minecraft/exports）。 */
    @PostMapping("/{name}/export-pack")
    public InstallJobService.JobView exportPack(@PathVariable String name,
                                                @RequestBody(required = false) Map<String, Object> body) {
        String packName = body == null || body.get("packName") == null ? name : String.valueOf(body.get("packName"));
        String packVersion = body == null || body.get("packVersion") == null
                ? "1.0.0" : String.valueOf(body.get("packVersion"));
        if (!instanceService.beginExport(name)) {
            throw new IllegalStateException("该版本正在导出，请稍候");
        }
        return jobs.submit("export", "导出整合包 " + packName, progress -> {
            try {
                return instanceService.exportPack(name, packName, packVersion, progress);
            } catch (Exception ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            } finally {
                instanceService.endExport(name);
            }
        });
    }

    /**
     * 修复缺失前置 Mod。
     *
     * <p>启动崩溃里最常见的就是 {@code Missing or unsupported mandatory dependencies}，
     * 这里按 mods 目录里各 jar 声明的依赖，把缺的从 Modrinth / CurseForge 补上。
     */
    @PostMapping("/{name}/fix-deps")
    public InstallJobService.JobView fixDeps(@PathVariable String name) throws IOException {
        InstanceScanner.InstanceInfo info = instanceScanner.info(name);
        String loader = loaderOf(info);
        if (loader == null) {
            throw new IllegalArgumentException("实例 " + name + " 不是 Mod 加载器实例，无需修复前置");
        }
        Path mods = modsDir(name);
        String mcVersion = info.mcVersion();
        return jobs.submit("deps", "修复前置 Mod " + name, progress -> {
            progress.update(15, "扫描 " + name + " 的 Mod 依赖（Minecraft " + mcVersion + " / " + loader + "）…");
            ModDependencyResolver.Result result = dependencyResolver.complete(mods, loader, mcVersion);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("instance", name);
            out.put("installed", result.installedMods());
            out.put("unresolved", result.unresolved());
            out.put("depsInstalled", result.installedMods());
            out.put("depsUnresolved", result.unresolved());
            String message;
            if (result.installed() > 0 && result.unresolved().isEmpty()) {
                message = "已补装 " + result.installed() + " 个前置，重启游戏生效";
            } else if (result.installed() > 0) {
                message = "已补装 " + result.installed() + " 个前置，还有 " + result.unresolved().size()
                        + " 个需手动放进 mods";
            } else if (!result.unresolved().isEmpty()) {
                message = "还有 " + result.unresolved().size() + " 个前置需手动放进 mods";
            } else {
                message = "没有需要补装的前置";
            }
            progress.update(100, message);
            return out;
        });
    }

    private static String loaderOf(InstanceScanner.InstanceInfo info) {
        if (info.loaders() == null) {
            return null;
        }
        for (String name : info.loaders().keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("neoforge")) {
                return "neoforge";
            }
            if (lower.contains("forge")) {
                return "forge";
            }
            if (lower.contains("fabric")) {
                return "fabric";
            }
            if (lower.contains("quilt")) {
                return "quilt";
            }
        }
        return null;
    }

    private Path modsDir(String name) {
        return versionFacade.gameDir(name).resolve("mods");
    }
}
