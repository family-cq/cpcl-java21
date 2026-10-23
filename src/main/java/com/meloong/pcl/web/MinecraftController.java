package com.meloong.pcl.web;

import com.meloong.pcl.download.VersionCategories;
import com.meloong.pcl.download.VersionManifestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/minecraft")
public class MinecraftController {

    private final VersionManifestService manifestService;

    public MinecraftController(VersionManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /**
     * 版本清单。
     *
     * @param category latest / release / snapshot / ancient / fool，缺省返回全部
     */
    @GetMapping("/versions")
    public Map<String, Object> versions(@RequestParam(required = false) String category,
                                        @RequestParam(required = false) String type) {
        List<VersionManifestService.ManifestVersion> all = manifestService.versions();
        Map<String, String> latest = manifestService.latest();

        List<VersionRow> rows = new ArrayList<>(all.size());
        for (VersionManifestService.ManifestVersion version : all) {
            rows.add(new VersionRow(version.id(), version.type(), version.releaseTime(),
                    VersionCategories.categoryOf(version.id(), version.type())));
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : List.of(VersionCategories.LATEST, VersionCategories.RELEASE,
                VersionCategories.SNAPSHOT, VersionCategories.ANCIENT, VersionCategories.FOOL)) {
            counts.put(key, 0);
        }
        for (VersionRow row : rows) {
            counts.merge(row.category(), 1, Integer::sum);
        }
        counts.put(VersionCategories.LATEST, 2);

        List<VersionRow> filtered;
        String effective = category;
        if (effective == null || effective.isBlank()) {
            if (type != null && !type.isBlank()) {
                filtered = rows.stream().filter(row -> type.equalsIgnoreCase(row.type())).toList();
            } else {
                filtered = rows;
            }
        } else {
            filtered = switch (effective) {
                case VersionCategories.LATEST -> rows.stream()
                        .filter(row -> row.id().equals(latest.get("release")) || row.id().equals(latest.get("snapshot")))
                        .toList();
                default -> rows.stream().filter(row -> effective.equals(row.category())).toList();
            };
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", effective == null ? "" : effective);
        result.put("latest", latest);
        result.put("counts", counts);
        result.put("total", filtered.size());
        result.put("versions", filtered);
        return result;
    }

    @PostMapping("/install/{id}")
    public VersionManifestService.InstallReport install(@PathVariable String id,
                                                        @RequestParam(defaultValue = "false") boolean assets)
            throws IOException {
        return manifestService.install(id, assets);
    }

    public record VersionRow(String id, String type, String releaseTime, String category) {
    }
}
