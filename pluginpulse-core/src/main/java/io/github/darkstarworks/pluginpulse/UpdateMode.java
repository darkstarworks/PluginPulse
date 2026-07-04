package io.github.darkstarworks.pluginpulse;

/**
 * How far the updater is allowed to go on its own. Each mode includes
 * everything the previous one does.
 */
public enum UpdateMode {
    /** Check silently; results only available via the API / manual command. */
    CHECK_ONLY,
    /** Check and notify console + permitted players. Default. */
    NOTIFY,
    /** Additionally allow on-demand download + staging via command (v0.2+). */
    DOWNLOAD,
    /** Automatically download and stage updates into the server update folder (v0.2+). */
    AUTO_STAGE;

    public boolean atLeast(UpdateMode other) {
        return ordinal() >= other.ordinal();
    }
}
