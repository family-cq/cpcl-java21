package com.meloong.pcl.version;

import java.util.List;

/**
 * Mod 加载器类型，对应原工程 <c>ModLoaders</c> 标志枚举。
 */
public enum ModLoader {

    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    LITELOADER("LiteLoader");

    private final String display;

    ModLoader(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** 可以安装 Mod 的加载器（对应 PCL 的 McInstance.Modable）。 */
    public static List<ModLoader> modable() {
        return List.of(FORGE, NEOFORGE, FABRIC, QUILT, LITELOADER);
    }
}
