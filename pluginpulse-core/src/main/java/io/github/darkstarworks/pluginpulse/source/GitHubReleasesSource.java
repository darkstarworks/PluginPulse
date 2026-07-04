package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * GitHub Releases. Unauthenticated requests share a 60/hour-per-IP limit, so
 * the updater's persisted last-check cache and long intervals matter here.
 *
 * <p>Lists recent releases (rather than {@code /releases/latest}) so that a
 * configured track (e.g. {@code -mc26} tags) can be matched even when the most
 * recent release belongs to the other track. Asset hashes come from GitHub's
 * {@code digest} field when present, else from a {@code <asset>.sha256}
 * sidecar asset.</p>
 */
public final class GitHubReleasesSource implements UpdateSource {

    private static final Predicate<String> DEFAULT_ASSET_FILTER = name -> {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-javadoc");
    };

    private final String repo; // "owner/name"
    private final String token; // optional, for private repos
    private final Predicate<String> assetFilter;

    public GitHubReleasesSource(String repo) {
        this(repo, null, DEFAULT_ASSET_FILTER);
    }

    public GitHubReleasesSource(String repo, String token, Predicate<String> assetFilter) {
        this.repo = repo;
        this.token = token;
        this.assetFilter = assetFilter != null ? assetFilter : DEFAULT_ASSET_FILTER;
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        String json = ctx.http().get("https://api.github.com/repos/" + repo + "/releases?per_page=15", headers);
        Parsed parsed = parse(json, ctx.track(), assetFilter);
        if (parsed == null) {
            throw new IllegalStateException("GitHub repo " + repo + " has no matching releases");
        }
        // Sidecar checksum fetch requires a second request; only when no digest.
        if (parsed.info.hashes().isEmpty() && parsed.sha256SidecarUrl != null) {
            try {
                String body = ctx.http().get(parsed.sha256SidecarUrl, headers).trim();
                String hex = body.split("\\s+")[0];
                if (hex.matches("[0-9a-fA-F]{64}")) {
                    return withHash(parsed.info, "sha256", hex.toLowerCase(Locale.ROOT));
                }
            } catch (Exception e) {
                ctx.logger().fine("Sidecar checksum fetch failed: " + e.getMessage());
            }
        }
        return parsed.info;
    }

    record Parsed(UpdateInfo info, String sha256SidecarUrl) {
    }

    /**
     * Pick the newest non-draft, non-prerelease release matching the track
     * (tag ends with {@code -<track>}; without a track, tags with any
     * {@code -suffix} are skipped unless nothing else matches).
     */
    static Parsed parse(String json, String track, Predicate<String> assetFilter) {
        JsonArray releases = JsonParser.parseString(json).getAsJsonArray();
        JsonObject chosen = null;
        JsonObject fallback = null;
        for (JsonElement el : releases) {
            JsonObject r = el.getAsJsonObject();
            if (r.get("draft").getAsBoolean() || r.get("prerelease").getAsBoolean()) continue;
            String tag = r.get("tag_name").getAsString().toLowerCase(Locale.ROOT);
            if (track != null && !track.isBlank()) {
                if (tag.endsWith("-" + track.toLowerCase(Locale.ROOT))) {
                    chosen = r;
                    break;
                }
            } else if (!tag.contains("-")) {
                chosen = r;
                break;
            }
            if (fallback == null) fallback = r;
        }
        if (chosen == null) chosen = fallback;
        if (chosen == null) return null;

        String version = chosen.get("tag_name").getAsString();
        String changelog = chosen.has("body") && !chosen.get("body").isJsonNull()
                ? chosen.get("body").getAsString() : "";
        String pageUrl = chosen.get("html_url").getAsString();

        JsonObject asset = null;
        String sidecarUrl = null;
        JsonArray assets = chosen.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement el : assets) {
                JsonObject a = el.getAsJsonObject();
                if (asset == null && assetFilter.test(a.get("name").getAsString())) {
                    asset = a;
                }
            }
            if (asset != null) {
                String want = asset.get("name").getAsString() + ".sha256";
                for (JsonElement el : assets) {
                    JsonObject a = el.getAsJsonObject();
                    if (a.get("name").getAsString().equalsIgnoreCase(want)) {
                        sidecarUrl = a.get("browser_download_url").getAsString();
                        break;
                    }
                }
            }
        }

        Map<String, String> hashes = new HashMap<>();
        String downloadUrl = null;
        String fileName = null;
        long size = -1;
        if (asset != null) {
            downloadUrl = asset.get("browser_download_url").getAsString();
            fileName = asset.get("name").getAsString();
            if (asset.has("size")) size = asset.get("size").getAsLong();
            if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
                String digest = asset.get("digest").getAsString(); // "sha256:<hex>"
                int colon = digest.indexOf(':');
                if (colon > 0) {
                    hashes.put(digest.substring(0, colon).toLowerCase(Locale.ROOT), digest.substring(colon + 1));
                }
            }
        }
        UpdateInfo info = new UpdateInfo(version, changelog, downloadUrl, fileName, hashes, size, true, pageUrl);
        return new Parsed(info, sidecarUrl);
    }

    private static UpdateInfo withHash(UpdateInfo info, String algo, String hex) {
        Map<String, String> hashes = new HashMap<>(info.hashes());
        hashes.put(algo, hex);
        return new UpdateInfo(info.version(), info.changelog(), info.downloadUrl(), info.fileName(),
                hashes, info.sizeBytes(), info.restartRequired(), info.releasePageUrl());
    }

    @Override
    public String name() {
        return "github";
    }
}
