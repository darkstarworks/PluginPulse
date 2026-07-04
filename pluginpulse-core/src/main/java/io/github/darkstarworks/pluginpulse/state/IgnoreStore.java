package io.github.darkstarworks.pluginpulse.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persisted updater state: versions the admin chose to ignore, plus the last
 * check timestamp and last-known latest version. The cache lets the updater
 * survive GitHub's shared 60/hour unauthenticated rate limit across restarts
 * without re-checking on every boot.
 *
 * <p>Stored at {@code <dataFolder>/pluginpulse/state.json}.</p>
 */
public final class IgnoreStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    private final Set<String> ignoredVersions = new HashSet<>();
    private long lastCheckEpochMs;
    private String lastKnownLatest;

    public IgnoreStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("ignoredVersions")) {
                root.getAsJsonArray("ignoredVersions")
                        .forEach(el -> ignoredVersions.add(el.getAsString().toLowerCase(Locale.ROOT)));
            }
            if (root.has("lastCheckEpochMs")) lastCheckEpochMs = root.get("lastCheckEpochMs").getAsLong();
            if (root.has("lastKnownLatest") && !root.get("lastKnownLatest").isJsonNull()) {
                lastKnownLatest = root.get("lastKnownLatest").getAsString();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read updater state " + file + ": " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            var arr = new com.google.gson.JsonArray();
            ignoredVersions.forEach(arr::add);
            root.add("ignoredVersions", arr);
            root.addProperty("lastCheckEpochMs", lastCheckEpochMs);
            root.addProperty("lastKnownLatest", lastKnownLatest);
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write updater state " + file + ": " + e.getMessage());
        }
    }

    public synchronized boolean isIgnored(String version) {
        return ignoredVersions.contains(version.toLowerCase(Locale.ROOT));
    }

    public synchronized void ignore(String version) {
        ignoredVersions.add(version.toLowerCase(Locale.ROOT));
        save();
    }

    public synchronized void unignore(String version) {
        ignoredVersions.remove(version.toLowerCase(Locale.ROOT));
        save();
    }

    public synchronized long lastCheckEpochMs() {
        return lastCheckEpochMs;
    }

    public synchronized String lastKnownLatest() {
        return lastKnownLatest;
    }

    public synchronized void recordCheck(String latestVersion) {
        this.lastCheckEpochMs = System.currentTimeMillis();
        this.lastKnownLatest = latestVersion;
        save();
    }
}
