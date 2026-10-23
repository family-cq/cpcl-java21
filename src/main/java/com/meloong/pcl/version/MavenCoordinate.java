package com.meloong.pcl.version;

/**
 * Maven 坐标解析，对应原工程 <c>McLibGet</c>（name → libraries 下的相对路径）。
 *
 * <p>支持 <c>group:artifact:version[:classifier][@extension]</c> 三种写法。
 */
public record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {

    public static MavenCoordinate parse(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("空的 Maven 坐标");
        }
        String extension = "jar";
        String body = name.trim();
        int at = body.lastIndexOf('@');
        if (at > 0) {
            extension = body.substring(at + 1);
            body = body.substring(0, at);
        }
        String[] parts = body.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("无法解析 Maven 坐标：" + name);
        }
        String classifier = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : null;
        return new MavenCoordinate(parts[0], parts[1], parts[2], classifier, extension);
    }

    /** 转换为 libraries 下的相对路径，例如 net/minecraft/client/1.20.1/client-1.20.1.jar。 */
    public String toRelativePath() {
        StringBuilder sb = new StringBuilder();
        sb.append(group.replace('.', '/')).append('/')
                .append(artifact).append('/')
                .append(version).append('/')
                .append(artifact).append('-').append(version);
        if (classifier != null) {
            sb.append('-').append(classifier);
        }
        sb.append('.').append(extension);
        return sb.toString();
    }
}
