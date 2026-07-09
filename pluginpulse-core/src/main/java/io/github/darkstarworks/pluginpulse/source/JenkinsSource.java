package io.github.darkstarworks.pluginpulse.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.darkstarworks.pluginpulse.UpdateInfo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A Jenkins CI job (e.g. {@code https://ci.athion.net/job/FastAsyncWorldEdit/}).
 * Reads the job's <em>last successful build</em> via the JSON API and offers its
 * archived {@code .jar} artifact as the latest version.
 *
 * <p>Jobs commonly archive several jars (FAWE ships Bukkit/CLI/Paper, LuckPerms
 * ten platforms), so an artifact filter — a case-insensitive regex matched
 * against the file name — picks the right one; without a filter the first
 * {@code .jar} that isn't a {@code -sources}/{@code -javadoc} jar wins.</p>
 *
 * <p>The version is derived from the artifact file name by taking everything
 * from the first dash-digit boundary ({@code FastAsyncWorldEdit-Paper-2.15.3-SNAPSHOT-1348.jar}
 * → {@code 2.15.3-SNAPSHOT-1348}); when no version-looking part exists the
 * Jenkins build number is used. Jenkins publishes no usable checksums, so
 * {@link UpdateInfo#hashes()} is always empty — download/auto modes need
 * {@code require-hash: false} or staging will (correctly) refuse the file.</p>
 */
public final class JenkinsSource implements UpdateSource {

    private static final Predicate<String> DEFAULT_ARTIFACT_FILTER = name -> {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-javadoc");
    };

    private final String jobUrl; // normalized, no trailing slash
    private final Predicate<String> artifactFilter;

    public JenkinsSource(String jobUrl) {
        this(jobUrl, null);
    }

    public JenkinsSource(String jobUrl, Predicate<String> artifactFilter) {
        String u = jobUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("Jenkins job URL must be absolute (got: " + jobUrl + ")");
        }
        this.jobUrl = u;
        this.artifactFilter = artifactFilter != null ? artifactFilter : DEFAULT_ARTIFACT_FILTER;
    }

    /**
     * Turn a user-supplied regex (from {@code jenkins-artifact}) into an artifact
     * filter. Matched case-insensitively anywhere in the file name.
     *
     * @throws java.util.regex.PatternSyntaxException on an invalid regex —
     *         callers warn and fall back to the default filter
     */
    public static Predicate<String> artifactRegex(String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return name -> p.matcher(name).find();
    }

    @Override
    public UpdateInfo fetchLatest(SourceContext ctx) throws Exception {
        String json = ctx.http().get(jobUrl
                + "/lastSuccessfulBuild/api/json?tree=number,url,result,artifacts[fileName,relativePath]");
        UpdateInfo info = parse(json, artifactFilter);
        if (info == null) {
            throw new IllegalStateException("Jenkins job " + jobUrl
                    + " has no successful build with a matching .jar artifact");
        }
        return info;
    }

    /** Parse a {@code lastSuccessfulBuild/api/json} payload. Null when no artifact matches. */
    static UpdateInfo parse(String json, Predicate<String> artifactFilter) {
        String body = json == null ? "" : json.trim();
        // Some Jenkins servers block anonymous API access (a proxy rule on
        // ci.citizensnpcs.co, a Cloudflare challenge on ci.dmulloy2.net) and
        // answer with an HTML page; say so instead of a raw JSON parse error.
        if (!body.startsWith("{")) {
            throw new IllegalStateException("the Jenkins server answered with "
                    + (body.startsWith("<") ? "an HTML page" : "something that isn't JSON")
                    + " instead of build data — it is probably blocking anonymous API access"
                    + " (login wall, proxy rule, or Cloudflare challenge), so this job can't be followed");
        }
        JsonObject build = JsonParser.parseString(body).getAsJsonObject();
        // lastSuccessfulBuild is SUCCESS by definition; guard anyway in case the
        // configured URL points somewhere else (a specific build, a wrong job).
        if (build.has("result") && !build.get("result").isJsonNull()
                && !"SUCCESS".equalsIgnoreCase(build.get("result").getAsString())) {
            return null;
        }
        JsonArray artifacts = build.getAsJsonArray("artifacts");
        if (artifacts == null) return null;
        JsonObject artifact = null;
        for (JsonElement el : artifacts) {
            JsonObject a = el.getAsJsonObject();
            if (artifactFilter.test(a.get("fileName").getAsString())) {
                artifact = a;
                break;
            }
        }
        if (artifact == null) return null;

        String fileName = artifact.get("fileName").getAsString();
        String buildUrl = build.get("url").getAsString();
        if (!buildUrl.endsWith("/")) buildUrl += "/";
        String downloadUrl = buildUrl + "artifact/" + encodePath(artifact.get("relativePath").getAsString());
        String version = deriveVersion(fileName, build.get("number").getAsLong());
        // No checksums and always a restart: CI builds carry no publisher metadata.
        return new UpdateInfo(version, "", downloadUrl, fileName, Map.of(), -1, true, buildUrl);
    }

    /**
     * Version from the artifact name: everything after the first {@code -} that
     * is followed by a digit, with {@code .jar} stripped
     * ({@code LuckPerms-Bukkit-5.5.59.jar} → {@code 5.5.59}). Falls back to the
     * Jenkins build number when the name has no version-looking part.
     */
    static String deriveVersion(String fileName, long buildNumber) {
        String base = fileName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        for (int i = 1; i < base.length(); i++) {
            if (base.charAt(i - 1) == '-' && Character.isDigit(base.charAt(i))) {
                return base.substring(i);
            }
        }
        return Long.toString(buildNumber);
    }

    /** Percent-encode each path segment; Jenkins relative paths can contain spaces. */
    private static String encodePath(String relativePath) {
        String[] segments = relativePath.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    @Override
    public String name() {
        return "jenkins";
    }
}
