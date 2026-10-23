package com.meloong.pcl.web;

import com.meloong.pcl.config.PclProperties;
import com.meloong.pcl.version.ResolvedVersion;
import com.meloong.pcl.version.VersionResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 供 Web 层使用的实例路径门面，避免 Controller 直接拼路径。
 */
@Component
public class VersionFacade {

    private final PclProperties properties;
    private final VersionResolver resolver;

    public VersionFacade(PclProperties properties, VersionResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /** 实例的游戏目录：默认版本隔离（versions/&lt;name&gt;）。 */
    public Path gameDir(String name) {
        return properties.versionsPath().resolve(name);
    }

    public ResolvedVersion resolve(String name) throws IOException {
        return resolver.resolve(name);
    }

    public PclProperties properties() {
        return properties;
    }
}
