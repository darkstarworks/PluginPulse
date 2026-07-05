package io.github.darkstarworks.pluginpulse;

import io.github.darkstarworks.pluginpulse.source.GitHubReleasesSource;
import io.github.darkstarworks.pluginpulse.source.HangarSource;
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
            if (modrinth == null && github == null && hangar == null) {
                plugin.getLogger().warning("PluginPulse: pluginpulse.yml has no modrinth/github/hangar source — updater disabled.");
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
            boolean primarySet = false;
            if (modrinth != null) primarySet = addSource(builder, new ModrinthSource(modrinth), primarySet);
            if (github != null) primarySet = addSource(builder, new GitHubReleasesSource(github), primarySet);
            if (hangar != null) primarySet = addSource(builder, new HangarSource(hangar), primarySet);

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
