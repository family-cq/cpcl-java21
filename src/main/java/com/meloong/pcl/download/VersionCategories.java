package com.meloong.pcl.download;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 版本分类，对应原工程下载页的五个标签：最新版本 / 正式版 / 预览版 / 远古版本 / 愚人节版本。
 */
public final class VersionCategories {

    public static final String LATEST = "latest";
    public static final String RELEASE = "release";
    public static final String SNAPSHOT = "snapshot";
    public static final String ANCIENT = "ancient";
    public static final String FOOL = "fool";

    /**
     * 愚人节版本名单（对应原工程 McVersionFool 的判定表）。
     * 官方清单里它们大多被标成 snapshot，只能靠名单识别。
     */
    public static final Set<String> APRIL_FOOLS = new LinkedHashSet<>(Set.of(
            "2.0",
            "15w14a",
            "1.RV-Pre1",
            "3D Shareware v1.34",
            "20w14infinite",
            "22w13oneBlockAtATime",
            "23w13a_or_b",
            "24w14potato",
            "25w14craftmine"));

    private VersionCategories() {
    }

    /** 判断版本属于哪一类。 */
    public static String categoryOf(String id, String type) {
        if (APRIL_FOOLS.contains(id)) {
            return FOOL;
        }
        return switch (type == null ? "" : type) {
            case "release" -> RELEASE;
            case "snapshot" -> SNAPSHOT;
            default -> ANCIENT;
        };
    }

    public static String label(String category) {
        return switch (category == null ? "" : category) {
            case LATEST -> "最新版本";
            case RELEASE -> "正式版";
            case SNAPSHOT -> "预览版";
            case ANCIENT -> "远古版本";
            case FOOL -> "愚人节版本";
            default -> category;
        };
    }
}
