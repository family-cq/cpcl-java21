package com.meloong.pcl.download;

import com.meloong.pcl.config.PclProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 下载源（镜像）管理，移植自 <c>ModDownload.DlSource*Get</c>。
 *
 * <p>BMCLAPI 的替换规则与原实现逐条对应；加载器（forge / fabricmc / neoforged）不追加官方源，
 * 因为官方源上并没有这些构件。
 */
@Component
public class DownloadSources {

    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    public static final String VERSION_MANIFEST_OFFICIAL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String VERSION_MANIFEST_MIRROR = BMCLAPI + "/mc/game/version_manifest_v2.json";

    private final PclProperties properties;

    public DownloadSources(PclProperties properties) {
        this.properties = properties;
    }

    /** 资源文件下载源顺序。 */
    public List<String> assets(String original) {
        String normalized = original.replace("http://resources.download.minecraft.net", "https://resources.download.minecraft.net");
        String mirror = normalized
                .replace("https://piston-data.mojang.com", BMCLAPI + "/assets")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/assets")
                .replace("https://resources.download.minecraft.net", BMCLAPI + "/assets");
        return order(List.of(normalized), List.of(mirror));
    }

    /** 支持库下载源顺序。 */
    public List<String> library(String original) {
        boolean loaderOnly = List.of("minecraftforge", "fabricmc", "neoforged", "quiltmc")
                .stream().anyMatch(original::contains);
        String mirrorMaven = original
                .replace("https://piston-data.mojang.com", BMCLAPI + "/maven")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/maven")
                .replace("https://libraries.minecraft.net", BMCLAPI + "/maven")
                // 加载器自身的 maven：官方域名在国内经常直接拒连（Connection refused），必须走镜像
                .replace("https://maven.neoforged.net/releases", BMCLAPI + "/maven")
                .replace("https://maven.minecraftforge.net", BMCLAPI + "/maven")
                .replace("https://maven.fabricmc.net", BMCLAPI + "/maven");
        String mirrorLibraries = original
                .replace("https://piston-data.mojang.com", BMCLAPI + "/libraries")
                .replace("https://piston-meta.mojang.com", BMCLAPI + "/libraries")
                .replace("https://libraries.minecraft.net", BMCLAPI + "/libraries");
        if (loaderOnly) {
            return List.of(mirrorMaven, mirrorLibraries);
        }
        return order(List.of(original), List.of(mirrorMaven, mirrorLibraries));
    }

    /** 元数据（版本 JSON、客户端 jar 等）下载源顺序。 */
    public List<String> meta(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("无对应的 json 下载地址");
        }
        String mirror = original
                .replace("https://piston-data.mojang.com", BMCLAPI)
                .replace("https://piston-meta.mojang.com", BMCLAPI)
                .replace("https://launcher.mojang.com", BMCLAPI);
        return order(List.of(original), List.of(mirror));
    }

    /** 按 kind 分派。 */
    public List<String> resolve(SourceKind kind, String original) {
        return switch (kind) {
            case ASSETS -> assets(original);
            case LIBRARY -> library(original);
            case META -> meta(original);
        };
    }

    /** 版本清单地址顺序。 */
    public List<String> versionManifest() {
        return order(List.of(VERSION_MANIFEST_OFFICIAL), List.of(VERSION_MANIFEST_MIRROR));
    }

    /**
     * 合并官方与镜像源并去重。
     * 对应原实现 DlSourceOrder：auto 时按偏好，mirror 时镜像优先，official 时官方优先。
     */
    private List<String> order(List<String> official, List<String> mirror) {
        String preference = properties.getDownloadSource() == null ? "auto" : properties.getDownloadSource().toLowerCase();
        Set<String> result = new LinkedHashSet<>();
        switch (preference) {
            case "official" -> {
                result.addAll(official);
                result.addAll(mirror);
            }
            case "mirror" -> {
                result.addAll(mirror);
                result.addAll(official);
            }
            default -> {
                // auto：国内环境默认镜像优先（对应 PCL 的 ToolDownloadSource 默认值 1）
                result.addAll(mirror);
                result.addAll(official);
            }
        }
        return new ArrayList<>(result);
    }
}
