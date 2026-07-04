package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Generic self-hosted manifest source. Fetches a JSON document of the form:
 *
 * <pre>{@code
 * {
 *   "version": "1.0.4",
 *   "changelog": "Fixed ...",
 *   "download": "https://example.com/dl/plugin-1.0.4.jar",
 *   "filename": "plugin-1.0.4.jar",
 *   "sha256": "…", "sha512": "…",       // any subset
 *   "size": 123456,
 *   "restart-required": true,
 *   "page": "https://example.com/plugin",
 *   "tracks": { "mc26": { ...same fields, override per track... } }
 * }
 * }</pre>
 *
 * <p>Custom headers/query parameters (e.g. a licence key) are passed through
 * on every request, which is how self-hosted stores authenticate downloads.</p>
 */
public final class CustomJsonSource implements UpdateSource {

    private final String manifestUrl;
    private final Map<String, String> headers;

    public CustomJsonSource(String manifestUrl) {
        this(manifestUrl, Map.of());
    }

    public CustomJsonSource(String manifestUrl, Map<String, String> headers) {
        this.manifestUrl = manifestUrl;
        this.headers = Map.copyOf(headers);
    }

    /** Headers sent with the manifest request; the download pipeline reuses them. */
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        String json = ctx.http().get(manifestUrl, headers);
        return parse(json, ctx.track());
    }

    static UpdateInfo parse(String json, String track) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject effective = root;
        if (track != null && !track.isBlank() && root.has("tracks")) {
            JsonObject tracks = root.getAsJsonObject("tracks");
            if (tracks.has(track)) {
                // Track object overrides top-level fields.
                JsonObject merged = root.deepCopy();
                JsonObject overrides = tracks.getAsJsonObject(track);
                for (String key : overrides.keySet()) {
                    merged.add(key, overrides.get(key));
                }
                effective = merged;
            }
        }
        Map<String, String> hashes = new HashMap<>();
        for (String algo : new String[]{"sha512", "sha256", "sha1"}) {
            if (effective.has(algo) && !effective.get(algo).isJsonNull()) {
                hashes.put(algo, effective.get(algo).getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return new UpdateInfo(
                effective.get("version").getAsString(),
                optString(effective, "changelog"),
                optString(effective, "download"),
                optString(effective, "filename"),
                hashes,
                effective.has("size") ? effective.get("size").getAsLong() : -1,
                !effective.has("restart-required") || effective.get("restart-required").getAsBoolean(),
                optString(effective, "page")
        );
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    @Override
    public String name() {
        return "custom-json";
    }
}
