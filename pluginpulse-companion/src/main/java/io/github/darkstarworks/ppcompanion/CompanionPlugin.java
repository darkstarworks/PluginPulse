package io.github.darkstarworks.ppcompanion;

import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.UpdateSubcommand;
import io.github.darkstarworks.pluginpulse.Updater;
import io.github.darkstarworks.pluginpulse.source.GitHubReleasesSource;
import io.github.darkstarworks.pluginpulse.source.HangarSource;
import io.github.darkstarworks.pluginpulse.source.JenkinsSource;
import io.github.darkstarworks.pluginpulse.source.ModrinthSource;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A drop-in updater plugin for server owners. Reads {@code config.yml}, and for
 * each configured &amp; installed target plugin drives a {@link Updater} on its
 * behalf — no changes to the target plugin, no code.
 *
 * <p>Everything is done through the public builder API: the companion never
 * touches a target's internals, only its jar (via PluginPulse's normal
 * update-folder staging) and Bukkit's plugin manager.</p>
 */
public final class CompanionPlugin extends JavaPlugin {

    /** name -> live updater for each managed, installed target. */
    private final Map<String, Updater> updaters = new LinkedHashMap<>();
    /** name -> command delegate for each managed target. */
    private final Map<String, UpdateSubcommand> subcommands = new LinkedHashMap<>();
    /** name -> the plugin that was configured but not installed / not usable. */
    private final Map<String, String> skipped = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String contact = getConfig().getString("user-agent-contact", "").trim();
        long intervalHours = Math.max(1L, getConfig().getLong("check-interval-hours", 6L));

        ConfigurationSection plugins = getConfig().getConfigurationSection("plugins");
        if (plugins == null || plugins.getKeys(false).isEmpty()) {
            getLogger().info("No plugins configured yet — edit config.yml and run /pluginpulse reload.");
            return;
        }
        for (String name : plugins.getKeys(false)) {
            ConfigurationSection entry = plugins.getConfigurationSection(name);
            if (entry == null) {
                skipped.put(name, "malformed config entry");
                continue;
            }
            startTarget(name, entry, contact, intervalHours);
        }
        getLogger().info("Managing " + updaters.size() + " plugin(s); "
                + skipped.size() + " skipped.");
    }

    @Override
    public void onDisable() {
        stopAll();
    }

    private void startTarget(String name, ConfigurationSection entry, String contact, long intervalHours) {
        Plugin target = getServer().getPluginManager().getPlugin(name);
        if (target == null) {
            skipped.put(name, "not installed");
            return;
        }
        if (!(target instanceof JavaPlugin jp)) {
            skipped.put(name, "not a JavaPlugin (cannot be updated)");
            return;
        }

        List<UpdateSource> sources = new ArrayList<>();
        String modrinth = trimToNull(entry.getString("modrinth"));
        String github = trimToNull(entry.getString("github"));
        String hangar = trimToNull(entry.getString("hangar"));
        String jenkins = trimToNull(entry.getString("jenkins"));
        // Optional token for a PRIVATE GitHub repo (literal or ${ENV_VAR}); it
        // authenticates the check and the download alike.
        String githubToken = io.github.darkstarworks.pluginpulse.Secrets.resolve(entry.getString("github-token"));
        if (modrinth != null) sources.add(new ModrinthSource(modrinth));
        if (github != null) sources.add(new GitHubReleasesSource(github, githubToken, null));
        if (hangar != null) sources.add(new HangarSource(hangar));
        if (jenkins != null) {
            sources.add(new JenkinsSource(jenkins, jenkinsArtifactFilter(name, entry.getString("jenkins-artifact"))));
            // Jenkins archives raw CI artifacts with no checksums; with the
            // require-hash default (true) a download would always be refused.
            if (!entry.contains("require-hash")) {
                getLogger().info(name + ": the jenkins source publishes no checksums — "
                        + "download/auto modes need require-hash: false for this entry.");
            }
        }
        if (sources.isEmpty()) {
            skipped.put(name, "no modrinth/github/hangar/jenkins source configured");
            return;
        }

        UpdateMode mode = parseMode(entry.getString("mode", "notify"));
        if (mode == null) {
            skipped.put(name, "mode: off");
            return;
        }

        try {
            Updater.Builder builder = Updater.builder(jp)
                    .mode(mode)
                    .checkInterval(Duration.ofHours(intervalHours))
                    // The companion owns the /pluginpulse command; don't have each
                    // target self-register one of its own.
                    .selfRegisterCommand(false)
                    // Notifications and command use are gated by OUR permission so
                    // one grant covers every managed plugin.
                    .permission("pluginpulse.admin");
            // First declared source primary, rest fallbacks.
            builder.source(sources.get(0));
            for (int i = 1; i < sources.size(); i++) builder.fallbackSource(sources.get(i));
            if (!contact.isEmpty()) builder.userAgentContact(contact);
            String track = trimToNull(entry.getString("track"));
            if (track != null) builder.track(track);
            if (entry.contains("require-hash")) {
                builder.requireHash(entry.getBoolean("require-hash", true));
            }
            // Opt-in no-restart installs for this target. The engine still refuses
            // at runtime when unsafe (Folia, dependents, a bundled native library).
            if (entry.getBoolean("hot-reload", false)) {
                io.github.darkstarworks.pluginpulse.ReloadEngine engine =
                        io.github.darkstarworks.pluginpulse.ReloadEngines.tryLoad();
                if (engine != null) builder.reloadEngine(engine);
            }

            Updater updater = builder.build();
            updater.start();
            updaters.put(name, updater);
            subcommands.put(name, new UpdateSubcommand(updater));
        } catch (Exception e) {
            skipped.put(name, "failed to start: " + e.getMessage());
            getLogger().warning("Could not manage " + name + ": " + e.getMessage());
        }
    }

    private void stopAll() {
        updaters.values().forEach(Updater::shutdown);
        updaters.clear();
        subcommands.clear();
        skipped.clear();
    }

    // ==== Command ====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginpulse.admin")) {
            sender.sendMessage("You don't have permission to manage updates.");
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list", "status" -> sendStatus(sender);
            case "reload" -> {
                stopAll();
                reloadConfig();
                onEnable();
                sender.sendMessage("PluginPulse Companion reloaded — managing "
                        + updaters.size() + " plugin(s).");
            }
            case "check", "download", "install", "restore", "apply" -> dispatch(sender, action, args);
            default -> sender.sendMessage("Usage: /" + label
                    + " [list|check|download|restore|apply|reload] [plugin|all]");
        }
        return true;
    }

    /** Run an update action against one named target, or "all" (default). */
    private void dispatch(CommandSender sender, String action, String[] args) {
        String targetArg = args.length >= 2 ? args[1] : "all";
        if (subcommands.isEmpty()) {
            sender.sendMessage("No plugins are currently being managed.");
            return;
        }
        if ("all".equalsIgnoreCase(targetArg)) {
            subcommands.forEach((name, sub) -> {
                sender.sendMessage("[" + name + "]");
                sub.handle(sender, new String[]{action});
            });
            return;
        }
        UpdateSubcommand sub = resolve(targetArg);
        if (sub == null) {
            sender.sendMessage("Not a managed plugin: " + targetArg
                    + ". Managed: " + String.join(", ", subcommands.keySet()));
            return;
        }
        sub.handle(sender, new String[]{action});
    }

    /** Case-insensitive lookup of a managed target's command delegate. */
    private UpdateSubcommand resolve(String name) {
        UpdateSubcommand exact = subcommands.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, UpdateSubcommand> e : subcommands.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("PluginPulse Companion — managing " + updaters.size() + " plugin(s):");
        updaters.forEach((name, updater) -> {
            var pending = updater.pendingUpdate();
            String line = pending != null
                    ? name + ": update available " + pending.version()
                        + " (running " + updater.currentVersion() + ")"
                    : name + ": up to date (" + updater.currentVersion() + ")";
            sender.sendMessage(" - " + line);
        });
        if (!skipped.isEmpty()) {
            sender.sendMessage("Skipped:");
            skipped.forEach((name, why) -> sender.sendMessage(" - " + name + ": " + why));
        }
    }

    // ==== Helpers ====

    /** @return the mode, or null when the entry is explicitly "off". */
    static UpdateMode parseMode(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "off" -> null;
            case "check-only", "check", "silent" -> UpdateMode.CHECK_ONLY;
            case "download" -> UpdateMode.DOWNLOAD;
            case "auto-stage", "auto" -> UpdateMode.AUTO_STAGE;
            default -> UpdateMode.NOTIFY;
        };
    }

    /** Compile the optional artifact regex; invalid patterns warn and use the default filter. */
    private java.util.function.Predicate<String> jenkinsArtifactFilter(String name, String regex) {
        String r = trimToNull(regex);
        if (r == null) return null;
        try {
            return JenkinsSource.artifactRegex(r);
        } catch (java.util.regex.PatternSyntaxException e) {
            getLogger().warning(name + ": invalid jenkins-artifact regex '" + r
                    + "' — using the default .jar filter instead.");
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
