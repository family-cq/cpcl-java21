package com.meloong.pcl.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对照 <c>McVersion.cs</c> 的版本号换算规则。
 */
class McVersionNamesTest {

    @Test
    void legacyFormatMovesMinorToMajor() {
        assertEquals(new VersionNumber(20, 0, 1), McVersionNames.nameToVersion("1.20.1"));
        assertEquals(new VersionNumber(21, 0, 5), McVersionNames.nameToVersion("1.21.5"));
        assertEquals(new VersionNumber(12, 0, 2), McVersionNames.nameToVersion("1.12.2"));
    }

    @Test
    void newDropFormatKeepsAllSegments() {
        assertEquals(new VersionNumber(26, 1, 0), McVersionNames.nameToVersion("26.1"));
        assertEquals(new VersionNumber(26, 3, 2), McVersionNames.nameToVersion("26.3.2"));
    }

    @Test
    void snapshotsAndFoolVersionsAreDetected() {
        assertTrue(McVersionNames.isSnapshotName("24w14a"));
        assertTrue(McVersionNames.isSnapshotName("1.20.5-pre1"));
        assertTrue(McVersionNames.isSnapshotName("1.20.5-rc1"));
        assertFalse(McVersionNames.isSnapshotName("1.20.1"));

        assertTrue(McVersionNames.isFoolName("24w14potato"));
        assertTrue(McVersionNames.isFoolName("2.0"));
        assertFalse(McVersionNames.isFoolName("1.20.1"));
    }

    @Test
    void foolVersionsMapToBaseRelease() {
        assertEquals(McVersionNames.nameToVersion("1.5.1"), McVersionNames.nameToVersion("2.0"));
        assertEquals(McVersionNames.nameToVersion("1.20.4"), McVersionNames.nameToVersion("24w14potato"));
    }

    @Test
    void dropConversionRoundTrip() {
        assertEquals(210, McVersionNames.versionToDrop("1.21.5"));
        assertEquals("1.21", McVersionNames.dropToVersion(210));
        assertEquals("26.3", McVersionNames.dropToVersion(263));
        // 无法识别的旧快照固定为 209
        assertEquals(209, McVersionNames.versionToDrop("16w01a"));
    }

    @Test
    void standardFormatCheck() {
        assertTrue(McVersionNames.isStandardFormat("1.20"));
        assertTrue(McVersionNames.isStandardFormat("26.1"));
        assertFalse(McVersionNames.isStandardFormat("16w01a"));
        assertFalse(McVersionNames.isStandardFormat(null));
    }
}
