package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modrinth (api.modrinth.com/v2). Rate limit is 300 requests/minute per IP;
 * a unique identifying User-Agent is mandatory and supplied by {@link HttpSupport}.
 */
public final class ModrinthSource implements UpdateSource {

    private static final String API = "https://api.modrinth.com/v2";

    private final String projectSlug;
    private final List<String> loaders;
    private final List<String> gameVersions;

    public ModrinthSource(String projectSlug) {
        this(projectSlug, List.of(), List.of());
    }

    /**
     * @param loaders      optional loader filter, e.g. {@code ["paper"]}
     * @param gameVersions optional game-version filter, e.g. {@code ["1.21.1"]}
     */
    public ModrinthSource(String projectSlug, List<String> loaders, List<String> gameVersions) {
        this.projectSlug = projectSlug;
        this.loaders = List.copyOf(loaders);
        this.gameVersions = List.copyOf(gameVersions);
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        StringBuilder url = new StringBuilder(API)
                .append("/project/").append(projectSlug).append("/version");
        String sep = "?";
        if (!loaders.isEmpty()) {
            url.append(sep).append("loaders=").append(encodeJsonList(loaders));
            sep = "&";
        }
        if (!gameVersions.isEmpty()) {
            url.append(sep).append("game_versions=").append(encodeJsonList(gameVersions));
        }
        String json = ctx.http().get(url.toString());
        UpdateInfo info = parse(json, ctx.track(), projectSlug);
        if (info == null) {
            throw new IllegalStateException("Modrinth project " + projectSlug + " has no matching versions");
        }
        return info;
    }

    /**
     * Parse the version-list response (newest first). When a track is set,
     * prefers the newest version whose number ends with {@code -<track>};
     * otherwise prefers versions without any {@code -} suffix beyond the first
     * plain entry.
     */
    static UpdateInfo parse(String json, String track, String projectSlug) {
        JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
        JsonObject chosen = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            String number = v.get("version_number").getAsString();
            if (track != null && !track.isBlank()) {
                if (number.toLowerCase(Locale.ROOT).endsWith("-" + track.toLowerCase(Locale.ROOT))) {
                    chosen = v;
                    break;
                }
            } else if (!number.contains("-")) {
                // No track configured: skip suffixed builds (other tracks,
                // pre-releases) — otherwise a dual-track project's newest
                // "-mc26" upload would be served to every server.
                chosen = v;
                break;
            }
        }
        if (chosen == null && !versions.isEmpty()) {
            // Requested track never published (or every version is suffixed):
            // fall back to the newest entry.
            chosen = versions.get(0).getAsJsonObject();
        }
        if (chosen == null) return null;

        JsonObject file = pickPrimaryFile(chosen.getAsJsonArray("files"));
        Map<String, String> hashes = new HashMap<>();
        String downloadUrl = null;
        String fileName = null;
        long size = -1;
        if (file != null) {
            downloadUrl = file.get("url").getAsString();
            fileName = file.get("filename").getAsString();
            if (file.has("size")) size = file.get("size").getAsLong();
            JsonObject hashObj = file.getAsJsonObject("hashes");
            if (hashObj != null) {
                for (String algo : hashObj.keySet()) {
                    hashes.put(algo.toLowerCase(Locale.ROOT), hashObj.get(algo).getAsString());
                }
            }
        }
        String changelog = chosen.has("changelog") && !chosen.get("changelog").isJsonNull()
                ? chosen.get("changelog").getAsString() : "";
        String versionId = chosen.get("id").getAsString();
        return new UpdateInfo(
                chosen.get("version_number").getAsString(),
                changelog,
                downloadUrl,
                fileName,
                hashes,
                size,
                true,
                "https://modrinth.com/project/" + projectSlug + "/version/" + versionId
        );
    }

    private static JsonObject pickPrimaryFile(JsonArray files) {
        if (files == null || files.isEmpty()) return null;
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
        }
        return files.get(0).getAsJsonObject();
    }

    private static String encodeJsonList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i)).append('"');
        }
        sb.append(']');
        return URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "modrinth";
    }
}
