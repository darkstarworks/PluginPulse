package io.github.darkstarworks.pluginpulse.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP client for all sources. Sends a unique identifying User-Agent
 * (required by Modrinth, good citizenship everywhere else) and surfaces
 * rate-limit responses as descriptive errors so the updater can back off
 * until the next scheduled check.
 */
public final class HttpSupport {

    private final HttpClient client;
    private final String userAgent;

    public HttpSupport(String userAgent) {
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String userAgent() {
        return userAgent;
    }

    /** GET a text resource. Throws {@link IOException} on any non-2xx status. */
    public String get(String url) throws IOException, InterruptedException {
        return get(url, Map.of());
    }

    public String get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status / 100 != 2) {
            String detail = "";
            if (status == 429 || status == 403) {
                String remaining = response.headers().firstValue("X-RateLimit-Remaining")
                        .or(() -> response.headers().firstValue("X-Ratelimit-Remaining")).orElse(null);
                String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                if ("0".equals(remaining) || retryAfter != null) {
                    detail = " (rate limited" + (retryAfter != null ? ", retry after " + retryAfter + "s" : "") + ")";
                }
            }
            throw new IOException("HTTP " + status + detail + " from " + url);
        }
        return response.body();
    }

    /** The underlying client, for download streaming in later phases. */
    public HttpClient client() {
        return client;
    }
}
