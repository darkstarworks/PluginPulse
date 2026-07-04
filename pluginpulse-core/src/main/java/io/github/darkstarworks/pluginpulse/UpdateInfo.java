package io.github.darkstarworks.pluginpulse;

import java.util.Map;

/**
 * Normalized description of the latest available release, whatever the source.
 *
 * @param version         version string as published (may include a track suffix)
 * @param changelog       release notes, plain text or MiniMessage; may be empty
 * @param downloadUrl     direct file download URL; may be null when the source
 *                        only exposes a landing page
 * @param fileName        published file name; may be null
 * @param hashes          algorithm (lowercase, e.g. "sha512") to hex digest; may be empty
 * @param sizeBytes       published file size, or -1 when unknown
 * @param restartRequired whether the publisher flagged this update as needing a
 *                        full restart (defaults to true when unknown)
 * @param releasePageUrl  human-facing page for this release; may be null
 */
public record UpdateInfo(
        String version,
        String changelog,
        String downloadUrl,
        String fileName,
        Map<String, String> hashes,
        long sizeBytes,
        boolean restartRequired,
        String releasePageUrl
) {
    public UpdateInfo {
        hashes = hashes == null ? Map.of() : Map.copyOf(hashes);
        changelog = changelog == null ? "" : changelog;
    }
}
