package io.github.darkstarworks.pluginpulse.version;

import java.util.Arrays;
import java.util.Locale;

/**
 * A parsed plugin version: numeric dot-separated segments plus an optional
 * pre-release suffix ({@code 1.2.0-beta1 < 1.2.0}).
 *
 * <p>A distribution "track" suffix (e.g. {@code 1.7.3-mc26}) is stripped before
 * parsing when the track is supplied, so both tracks of the same release compare
 * as equal. Anything after the first {@code -} that is not the track is treated
 * as a pre-release identifier.</p>
 */
public final class Version implements Comparable<Version> {

    private final int[] segments;
    private final String prerelease; // null for a full release
    private final String raw;

    private Version(int[] segments, String prerelease, String raw) {
        this.segments = segments;
        this.prerelease = prerelease;
        this.raw = raw;
    }

    /** Parse without any track handling. */
    public static Version parse(String raw) {
        return parse(raw, null);
    }

    /**
     * Parse a version string, tolerating a leading {@code v}/{@code V} and an
     * optional {@code -<track>} suffix which is removed before comparison.
     */
    public static Version parse(String raw, String track) {
        String s = raw.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')
                && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        if (track != null && !track.isBlank()) {
            String suffix = "-" + track.toLowerCase(Locale.ROOT);
            if (s.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
            }
        }
        String pre = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1);
            s = s.substring(0, dash);
        }
        String[] parts = s.split("\\.");
        int[] seg = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            seg[i] = parseIntSafe(parts[i]);
        }
        return new Version(seg, pre, raw);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            // Salvage a leading numeric run ("2rc1" -> 2), otherwise 0.
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == 0) return 0;
            try {
                return Integer.parseInt(s.substring(0, i));
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    public boolean isPrerelease() {
        return prerelease != null;
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(Version o) {
        int len = Math.max(segments.length, o.segments.length);
        for (int i = 0; i < len; i++) {
            int a = i < segments.length ? segments[i] : 0;
            int b = i < o.segments.length ? o.segments[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        if (prerelease == null && o.prerelease == null) return 0;
        if (prerelease == null) return 1;   // release > pre-release
        if (o.prerelease == null) return -1;
        return prerelease.compareToIgnoreCase(o.prerelease);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(segments);
    }

    @Override
    public String toString() {
        return raw;
    }
}
