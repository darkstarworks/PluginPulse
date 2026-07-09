package io.github.darkstarworks.pluginpulse;

import io.github.darkstarworks.pluginpulse.source.GitHubReleasesSource;
import io.github.darkstarworks.pluginpulse.source.HangarSource;
import io.github.darkstarworks.pluginpulse.source.JenkinsSource;
import io.github.darkstarworks.pluginpulse.source.ModrinthSource;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * One-call entry point for the common case. Drop a {@code pluginpulse.yml} into
 * your plugin's resources, then:
 *
 * <pre>{@code
 * public void onEnable()  { PluginPulse.bootstrap(this); }
 * public void onDisable() { PluginPulse.shutdown(this); }
 * // in your command:  PluginPulse.handleUpdateCommand(this, sender, argsAfterUpdate);
 * }</pre>
 *
 * <p>No builder knowledge required. Power users who need custom sources,
 * message overrides, a scheduler adapter, or the hot-reload engine should use
 * {@link Updater#builder(JavaPlugin)} directly instead.</p>
 *
 * <p>Every method fails soft: a missing or malformed {@code pluginpulse.yml}
 * logs a warning and returns {@code null}/{@code false} — it never throws into
 * the host plugin's lifecycle.</p>
 */
public final class PluginPulse {

    private static final ConcurrentHashMap<String, Updater> UPDATERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UpdateSubcommand> SUBCOMMANDS = new ConcurrentHashMap<>();

    private PluginPulse() {
    }

    /**
     * Read {@code pluginpulse.yml} from the plugin jar, build an {@link Updater}
     * and start it. Server owners can override {@code mode} and
     * {@code check-interval-hours} via an {@code update:} section in the host
     * plugin's own {@code config.yml}.
     *
     * @return the started updater, or {@code null} if disabled/misconfigured
     */
    public static Updater bootstrap(JavaPlugin plugin) {
        try {
            InputStream in = plugin.getResource("pluginpulse.yml");
            if (in == null) {
                plugin.getLogger().warning("PluginPulse: no pluginpulse.yml found in the jar — updater disabled.");
                return null;
            }
            YamlConfiguration cfg;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                cfg = YamlConfiguration.loadConfiguration(reader);
            }

            String modrinth = trimToNull(cfg.getString("modrinth"));
            String github = trimToNull(cfg.getString("github"));
            String hangar = trimToNull(cfg.getString("hangar"));
            String jenkins = trimToNull(cfg.getString("jenkins"));
            if (modrinth == null && github == null && hangar == null && jenkins == null) {
                plugin.getLogger().warning("PluginPulse: pluginpulse.yml has no modrinth/github/hangar/jenkins source — updater disabled.");
                return null;
            }

            // mode + interval: pluginpulse.yml defaults, overridable by the host's config.yml update.* keys.
            String modeName = cfg.getString("mode", "notify");
            long intervalHours = cfg.getLong("check-interval-hours", 6L);
            if (plugin.getConfig().isConfigurationSection("update")) {
                modeName = plugin.getConfig().getString("update.mode", modeName);
                intervalHours = plugin.getConfig().getLong("update.check-interval-hours", intervalHours);
            }
            if ("off".equalsIgnoreCase(modeName)) {
                return null;
            }

            Updater.Builder builder = Updater.builder(plugin)
                    .mode(parseMode(modeName))
                    .checkInterval(Duration.ofHours(Math.max(1L, intervalHours)));

            // First declared source is primary; the rest are ordered fallbacks.
            // The order is taken from the optional "source-order" list when present
            // (e.g. [hangar, modrinth]); any configured source omitted from the list
            // is appended afterwards, and the historical default is modrinth > github
            // > hangar > jenkins when no list is given (CI snapshots rank last).
            java.util.Map<String, io.github.darkstarworks.pluginpulse.source.UpdateSource> available = new java.util.LinkedHashMap<>();
            // Optional token for a PRIVATE GitHub repo — supplied literally or as
            // a ${ENV_VAR} reference (see Secrets). Authenticates both the version
            // check and the download; null/absent keeps the anonymous public path.
            String githubToken = Secrets.resolve(cfg.getString("github-token"));
            if (modrinth != null) available.put("modrinth", new ModrinthSource(modrinth));
            if (github != null) available.put("github", new GitHubReleasesSource(github, githubToken, null));
            if (hangar != null) available.put("hangar", new HangarSource(hangar));
            if (jenkins != null) {
                available.put("jenkins", new JenkinsSource(jenkins,
                        jenkinsArtifactFilter(plugin, cfg.getString("jenkins-artifact"))));
                // Jenkins archives raw CI artifacts with no checksums; with the
                // require-hash default (true) a download would always be refused.
                if (!cfg.contains("require-hash")) {
                    plugin.getLogger().info("PluginPulse: the jenkins source publishes no checksums — "
                            + "download/auto modes need require-hash: false in pluginpulse.yml.");
                }
            }

            java.util.List<String> ordered = new java.util.ArrayList<>();
            for (String raw : cfg.getStringList("source-order")) {
                if (raw == null) continue;
                String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (available.containsKey(key) && !ordered.contains(key)) ordered.add(key);
            }
            for (String key : available.keySet()) {
                if (!ordered.contains(key)) ordered.add(key);
            }

            boolean primarySet = false;
            for (String key : ordered) primarySet = addSource(builder, available.get(key), primarySet);

            String permission = trimToNull(cfg.getString("permission"));
            if (permission != null) builder.permission(permission);
            String commandRoot = trimToNull(cfg.getString("command-root"));
            if (commandRoot != null) builder.commandRoot(commandRoot);
            // Self-registration is on by default and no-ops when the host already
            // owns the command name; allow explicit opt-out via the descriptor.
            if (cfg.contains("self-register-command")) {
                builder.selfRegisterCommand(cfg.getBoolean("self-register-command", true));
            }
            String contact = trimToNull(cfg.getString("user-agent-contact"));
            if (contact != null) builder.userAgentContact(contact);
            String prefix = trimToNull(cfg.getString("prefix"));
            if (prefix != null) builder.prefix(prefix);
            // Dual-track convention: the "-<track>" release line is the modern
            // (Minecraft 26+) build. Apply it only when the server actually
            // runs that generation, so a 1.21 server never follows -mc26
            // releases and vice-versa. getBukkitVersion() ("1.21.8-R0.1-...")
            // works on both Spigot and Paper (getMinecraftVersion() is Paper-only).
            String track = trimToNull(cfg.getString("track"));
            if (track != null) {
                String mc = org.bukkit.Bukkit.getBukkitVersion().split("-")[0];
                if (!mc.startsWith("1.")) builder.track(track);
            }
            if (cfg.contains("require-hash")) builder.requireHash(cfg.getBoolean("require-hash", true));

            // Opt-in no-restart installs. Requires the pluginpulse-hotreload module
            // to be on the classpath; the engine itself still refuses at runtime
            // when a reload would be unsafe (Folia, dependents, bundled native lib).
            if (cfg.getBoolean("hot-reload", false)) {
                ReloadEngine engine = loadHotReloadEngine(plugin);
                if (engine != null) builder.reloadEngine(engine);
            }

            Updater updater = builder.build();
            updater.start();
            UPDATERS.put(plugin.getName(), updater);
            SUBCOMMANDS.put(plugin.getName(), new UpdateSubcommand(updater));
            return updater;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "PluginPulse: bootstrap failed — updater disabled.", t);
            return null;
        }
    }

    /** The updater started for this plugin, or {@code null}. */
    public static Updater get(JavaPlugin plugin) {
        return UPDATERS.get(plugin.getName());
    }

    /**
     * Delegate a {@code /<plugin> update ...} subcommand to the bundled handler.
     *
     * @param argsAfterUpdate the arguments after the {@code update} keyword
     * @return {@code false} if no updater is active or the sender lacks permission
     */
    public static boolean handleUpdateCommand(JavaPlugin plugin, CommandSender sender, String[] argsAfterUpdate) {
        UpdateSubcommand handler = SUBCOMMANDS.get(plugin.getName());
        if (handler == null) {
            sender.sendMessage(plugin.getName() + ": the updater is not active.");
            return false;
        }
        return handler.handle(sender, argsAfterUpdate);
    }

    /** Stop this plugin's updater. Safe to call even if bootstrap returned null. */
    public static void shutdown(JavaPlugin plugin) {
        Updater updater = UPDATERS.remove(plugin.getName());
        SUBCOMMANDS.remove(plugin.getName());
        if (updater != null) updater.shutdown();
    }

    /** Load the optional hot-reload engine, logging when the module isn't bundled. */
    private static ReloadEngine loadHotReloadEngine(JavaPlugin plugin) {
        ReloadEngine engine = ReloadEngines.tryLoad();
        if (engine == null) {
            plugin.getLogger().warning("PluginPulse: hot-reload is enabled but the pluginpulse-hotreload "
                    + "module isn't bundled — updates will stage for a restart instead.");
        }
        return engine;
    }

    /**
     * Compile the optional {@code jenkins-artifact} regex; an invalid pattern
     * logs a warning and falls back to the default jar filter instead of
     * disabling the whole updater.
     */
    private static java.util.function.Predicate<String> jenkinsArtifactFilter(JavaPlugin plugin, String regex) {
        String r = trimToNull(regex);
        if (r == null) return null;
        try {
            return JenkinsSource.artifactRegex(r);
        } catch (java.util.regex.PatternSyntaxException e) {
            plugin.getLogger().warning("PluginPulse: invalid jenkins-artifact regex '" + r
                    + "' — using the default .jar filter instead.");
            return null;
        }
    }

    private static boolean addSource(Updater.Builder builder, io.github.darkstarworks.pluginpulse.source.UpdateSource source, boolean primarySet) {
        if (primarySet) {
            builder.fallbackSource(source);
        } else {
            builder.source(source);
        }
        return true;
    }

    private static UpdateMode parseMode(String name) {
        return switch (name.toLowerCase()) {
            case "check-only", "check", "silent" -> UpdateMode.CHECK_ONLY;
            case "download" -> UpdateMode.DOWNLOAD;
            case "auto-stage", "auto" -> UpdateMode.AUTO_STAGE;
            default -> UpdateMode.NOTIFY;
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
