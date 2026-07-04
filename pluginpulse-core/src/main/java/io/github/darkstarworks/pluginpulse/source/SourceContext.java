package io.github.darkstarworks.pluginpulse.source;

import java.util.logging.Logger;

/**
 * Everything a source needs to make requests: the shared HTTP support (which
 * carries the mandatory identifying User-Agent), the configured distribution
 * track (may be null), and a logger.
 */
public record SourceContext(HttpSupport http, String track, Logger logger) {
}
