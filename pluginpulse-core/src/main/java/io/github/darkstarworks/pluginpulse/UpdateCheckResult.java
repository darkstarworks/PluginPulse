package io.github.darkstarworks.pluginpulse;

/**
 * Outcome of one update check.
 */
public record UpdateCheckResult(Status status, UpdateInfo info, Throwable error) {

    public enum Status { UP_TO_DATE, UPDATE_AVAILABLE, IGNORED, FAILED }

    public static UpdateCheckResult upToDate() {
        return new UpdateCheckResult(Status.UP_TO_DATE, null, null);
    }

    public static UpdateCheckResult available(UpdateInfo info) {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, info, null);
    }

    public static UpdateCheckResult ignored(UpdateInfo info) {
        return new UpdateCheckResult(Status.IGNORED, info, null);
    }

    public static UpdateCheckResult failed(Throwable error) {
        return new UpdateCheckResult(Status.FAILED, null, error);
    }
}
