package io.github.darkstarworks.pluginpulse.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void basicOrdering() {
        assertTrue(Version.parse("1.7.3").isNewerThan(Version.parse("1.7.2")));
        assertTrue(Version.parse("1.10.0").isNewerThan(Version.parse("1.9.9")));
        assertTrue(Version.parse("2.0").isNewerThan(Version.parse("1.99.99")));
        assertFalse(Version.parse("1.7.3").isNewerThan(Version.parse("1.7.3")));
        assertFalse(Version.parse("1.7.2").isNewerThan(Version.parse("1.7.3")));
    }

    @Test
    void differingSegmentCounts() {
        assertTrue(Version.parse("1.7.0.1").isNewerThan(Version.parse("1.7")));
        assertFalse(Version.parse("1.7").isNewerThan(Version.parse("1.7.0")));
        assertEquals(0, Version.parse("1.7").compareTo(Version.parse("1.7.0.0")));
    }

    @Test
    void leadingVPrefix() {
        assertEquals(0, Version.parse("v1.7.3").compareTo(Version.parse("1.7.3")));
        assertTrue(Version.parse("V1.8.0").isNewerThan(Version.parse("v1.7.3")));
    }

    @Test
    void trackSuffixStrippedForComparison() {
        assertEquals(0, Version.parse("1.7.3-mc26", "mc26").compareTo(Version.parse("1.7.3", "mc26")));
        assertEquals(0, Version.parse("v1.7.3-mc26", "mc26").compareTo(Version.parse("1.7.3")));
        assertTrue(Version.parse("1.7.4-mc26", "mc26").isNewerThan(Version.parse("1.7.3-mc26", "mc26")));
    }

    @Test
    void unknownTrackTreatedAsPrerelease() {
        // Without the track configured, "-mc26" reads as a pre-release suffix.
        assertTrue(Version.parse("1.7.3").isNewerThan(Version.parse("1.7.3-mc26")));
    }

    @Test
    void prereleaseOrdering() {
        assertTrue(Version.parse("1.2.0").isNewerThan(Version.parse("1.2.0-beta1")));
        assertFalse(Version.parse("1.2.0-beta1").isNewerThan(Version.parse("1.2.0")));
        assertTrue(Version.parse("1.2.0-beta2").isNewerThan(Version.parse("1.2.0-beta1")));
        assertTrue(Version.parse("1.2.1-beta1").isNewerThan(Version.parse("1.2.0")));
        assertTrue(Version.parse("1.2.0-beta1").isPrerelease());
        assertFalse(Version.parse("1.2.0").isPrerelease());
    }

    @Test
    void prereleaseNumbersCompareNumerically() {
        // Embedded numbers order as numbers, not text (beta9 < beta10).
        assertTrue(Version.parse("1.2.0-beta10").isNewerThan(Version.parse("1.2.0-beta9")));
        assertTrue(Version.parse("1.2.0-beta02").isNewerThan(Version.parse("1.2.0-beta1")));
        // Jenkins CI builds: the build number rides in the pre-release part.
        assertTrue(Version.parse("2.15.3-SNAPSHOT-1000").isNewerThan(Version.parse("2.15.3-SNAPSHOT-999")));
        assertTrue(Version.parse("2.15.3-SNAPSHOT-1349").isNewerThan(Version.parse("2.15.3-SNAPSHOT-1348")));
        assertFalse(Version.parse("2.15.3-SNAPSHOT-1348").isNewerThan(Version.parse("2.15.3-SNAPSHOT-1348")));
        // Installed FAWE reports a ";commit" tail after the build number; the same
        // build must not look like an update, and a newer build still must.
        assertFalse(Version.parse("2.15.3-SNAPSHOT-1348").isNewerThan(Version.parse("2.15.3-SNAPSHOT-1348;bd70485")));
        assertTrue(Version.parse("2.15.3-SNAPSHOT-1349").isNewerThan(Version.parse("2.15.3-SNAPSHOT-1348;bd70485")));
        // A version bump outranks any build number.
        assertTrue(Version.parse("2.15.4-SNAPSHOT-1").isNewerThan(Version.parse("2.15.3-SNAPSHOT-1348")));
    }

    @Test
    void garbageSegmentsDoNotThrow() {
        assertEquals(0, Version.parse("1.x.3").compareTo(Version.parse("1.0.3")));
        assertEquals(0, Version.parse("1.2rc1.0").compareTo(Version.parse("1.2.0")));
        assertFalse(Version.parse("").isNewerThan(Version.parse("0")));
    }
}
