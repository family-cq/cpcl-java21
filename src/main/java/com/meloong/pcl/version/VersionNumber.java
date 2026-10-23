package com.meloong.pcl.version;

/**
 * 可比较的三段式版本号，对应原工程 <c>McVersion.Vanilla</c>（System.Version）。
 *
 * <p>Mojang 在 1.x 时代把次版本号放在主版本号位置，例如 1.20.1 会被归一化为 20.0.1；
 * 无法识别（例如旧快照）时为 9999.0.0，<see cref="valid()"/> 返回 false。
 */
public record VersionNumber(int major, int minor, int patch) implements Comparable<VersionNumber> {

    public static final VersionNumber UNKNOWN = new VersionNumber(9999, 0, 0);

    public boolean valid() {
        return major < 1000;
    }

    @Override
    public int compareTo(VersionNumber other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        return result != 0 ? result : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
