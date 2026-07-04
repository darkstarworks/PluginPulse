package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.util.Locale;
import java.util.Map;

/**
 * Hangar (hangar.papermc.io). Verified endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/projects/{slug}/latestrelease} — plain-text version</li>
 *   <li>{@code GET /api/v1/projects/{slug}/versions/{name}} — JSON with
 *       {@code downloads.{PLATFORM}.fileInfo.{name,sizeBytes,sha256Hash}} and
 *       {@code downloadUrl}; {@code description} is the changelog</li>
 * </ul>
 */
public final class HangarSource implements UpdateSource {

    private static final String API = "https://hangar.papermc.io/api/v1";

    private final String projectSlug;
    private final String platform; // PAPER, VELOCITY, WATERFALL

    public HangarSource(String projectSlug) {
        this(projectSlug, "PAPER");
    }

    public HangarSource(String projectSlug, String platform) {
        this.projectSlug = projectSlug;
        this.platform = platform.toUpperCase(Locale.ROOT);
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        String latest = ctx.http().get(API + "/projects/" + projectSlug + "/latestrelease").trim();
        String json = ctx.http().get(API + "/projects/" + projectSlug + "/versions/" + latest);
        return parse(json, platform, projectSlug);
    }

    static UpdateInfo parse(String json, String platform, String projectSlug) {
        JsonObject v = JsonParser.parseString(json).getAsJsonObject();
        String version = v.get("name").getAsString();
        String changelog = v.has("description") && !v.get("description").isJsonNull()
                ? v.get("description").getAsString() : "";
        String author = v.has("author") && !v.get("author").isJsonNull()
                ? v.get("author").getAsString() : null;

        String downloadUrl = null;
        String fileName = null;
        long size = -1;
        Map<String, String> hashes = Map.of();
        JsonObject downloads = v.getAsJsonObject("downloads");
        if (downloads != null && downloads.has(platform)) {
            JsonObject dl = downloads.getAsJsonObject(platform);
            if (dl.has("downloadUrl") && !dl.get("downloadUrl").isJsonNull()) {
                downloadUrl = dl.get("downloadUrl").getAsString();
            } else if (dl.has("externalUrl") && !dl.get("externalUrl").isJsonNull()) {
                downloadUrl = dl.get("externalUrl").getAsString();
            }
            JsonObject fileInfo = dl.getAsJsonObject("fileInfo");
            if (fileInfo != null) {
                fileName = fileInfo.get("name").getAsString();
                if (fileInfo.has("sizeBytes")) size = fileInfo.get("sizeBytes").getAsLong();
                if (fileInfo.has("sha256Hash") && !fileInfo.get("sha256Hash").isJsonNull()) {
                    hashes = Map.of("sha256", fileInfo.get("sha256Hash").getAsString());
                }
            }
        }
        String pageUrl = author != null
                ? "https://hangar.papermc.io/" + author + "/" + projectSlug
                : "https://hangar.papermc.io/projects/" + projectSlug;
        return new UpdateInfo(version, changelog, downloadUrl, fileName, hashes, size, true, pageUrl);
    }

    @Override
    public String name() {
        return "hangar";
    }
}
