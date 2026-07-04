package io.github.darkstarworks.pluginpulse.source;

import io.github.darkstarworks.pluginpulse.UpdateInfo;

/**
 * A place releases are published (Modrinth, GitHub Releases, Hangar, a custom
 * JSON manifest, ...). Implementations are called from an async thread and may
 * block on network I/O.
 */
public interface UpdateSource {

    /**
     * Fetch the latest published release.
     *
     * @throws Exception on any network/parse failure; the updater will fall
     *                   back to the next configured source
     */
    UpdateInfo fetchLatest(SourceContext ctx) throws Exception;

    /** Short human-readable name for logging, e.g. {@code "modrinth"}. */
    String name();
}
