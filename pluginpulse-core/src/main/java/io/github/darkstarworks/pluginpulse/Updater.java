package io.github.darkstarworks.pluginpulse;

import io.github.darkstarworks.pluginpulse.download.BackupManager;
import io.github.darkstarworks.pluginpulse.download.DownloadPipeline;
import io.github.darkstarworks.pluginpulse.download.PluginJarLocator;
import io.github.darkstarworks.pluginpulse.notify.JoinNotifyListener;
import io.github.darkstarworks.pluginpulse.notify.UpdateNotifier;
import io.github.darkstarworks.pluginpulse.platform.SchedulerAdapter;
import io.github.darkstarworks.pluginpulse.source.CustomJsonSource;
import io.github.darkstarworks.pluginpulse.source.HttpSupport;
import io.github.darkstarworks.pluginpulse.source.SourceContext;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import io.github.darkstarworks.pluginpulse.state.IgnoreStore;
import io.github.darkstarworks.pluginpulse.state.PendingUpdateStore;
import io.github.darkstarworks.pluginpulse.version.Version;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Entry point. Build once in {@code onEnable}, call {@link #start()}, and call
 * {@link #shutdown()} in {@code onDisable}.
 *
 * <pre>{@code
 * Updater updater = Updater.builder(this)
 *     .source(new ModrinthSource("myplugin"))
 *     .fallbackSource(new GitHubReleasesSource("me/myplugin"))
 *     .mode(UpdateMode.NOTIFY)
 *     .permission("myplugin.admin")
 *     .userAgentContact("me@example.com")
 *     .build();
 * updater.start();
 * }</pre>
 */
public final class Updater {

    private final JavaPlugin plugin;
    private final String currentVersion;
    private final List<UpdateSource> sources;
    private final UpdateMode mode;
    private final Duration checkInterval;
    private final String permission;
    private final String track;
    private final String changelogUrl;
    private final SchedulerAdapter scheduler;
    private final UpdateNotifier notifier;
    private final HttpSupport http;
    private final IgnoreStore state;
    private final PendingUpdateStore pendingStore;
    private final boolean requireHash;
    private final int backupRetention;
    private final Supplier<File> jarFileSupplier;
    private final ReloadEngine reloadEngine;

    private volatile UpdateInfo pendingUpdate;
    private volatile UpdateCheckResult lastResult;
    private volatile Map<String, String> downloadHeaders = Map.of();
    private volatile String stagedVersion;
    private SchedulerAdapter.TaskHandle periodicTask;
    private JoinNotifyListener joinListener;

    private Updater(Builder b) {
        this.plugin = b.plugin;
        this.currentVersion = b.currentVersion;
        this.sources = List.copyOf(b.sources);
        this.mode = b.mode;
        this.checkInterval = b.checkInterval;
        this.permission = b.permission;
        this.track = b.track;
        this.changelogUrl = b.changelogUrl;
        this.scheduler = b.scheduler != null ? b.scheduler : SchedulerAdapter.create(b.plugin);
        this.http = new HttpSupport(buildUserAgent(b));
        this.notifier = new UpdateNotifier(b.prefix, b.commandRoot, b.messageOverrides);
        Path stateDir = b.plugin.getDataFolder().toPath().resolve("pluginpulse");
        this.state = new IgnoreStore(stateDir.resolve("state.json"), b.plugin.getLogger());
        this.pendingStore = new PendingUpdateStore(stateDir.resolve("pending-update.json"), b.plugin.getLogger());
        this.requireHash = b.requireHash;
        this.backupRetention = b.backupRetention;
        this.jarFileSupplier = b.jarFileSupplier;
        this.reloadEngine = b.reloadEngine;
    }

    private static String buildUserAgent(Builder b) {
        String contact = b.userAgentContact != null ? " (" + b.userAgentContact + ")" : "";
        return "pluginpulse/" + b.plugin.getName() + "/" + b.currentVersion + contact;
    }

    // ==== Lifecycle ====

    /** Register the join listener (mode >= NOTIFY) and begin periodic checks. */
    public void start() {
        processPendingMarker();
        if (mode.atLeast(UpdateMode.NOTIFY)) {
            joinListener = new JoinNotifyListener(this);
            Bukkit.getPluginManager().registerEvents(joinListener, plugin);
        }
        long periodTicks = checkInterval.toSeconds() * 20L;
        // Small startup delay + jitter so fleets of servers don't check in lockstep.
        long delayTicks = 100L + ThreadLocalRandom.current().nextLong(0, 200);
        periodicTask = scheduler.runAsyncRepeating(() -> runCheck(true, null), delayTicks, periodTicks);
    }

    public void shutdown() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        if (joinListener != null) {
            HandlerList.unregisterAll(joinListener);
            joinListener = null;
        }
    }

    /** Manual check (e.g. from a command). Reports the outcome to {@code sender}. */
    public void checkNow(CommandSender sender) {
        scheduler.runAsync(() -> runCheck(false, result -> scheduler.runGlobal(() -> {
            switch (result.status()) {
                case UPDATE_AVAILABLE -> notifier.notifySender(sender, currentVersion, result.info());
                case IGNORED -> sender.sendMessage(plugin.getName() + ": latest version "
                        + result.info().version() + " is on the ignore list (current: " + currentVersion + ").");
                case UP_TO_DATE -> sender.sendMessage(plugin.getName() + " is up to date (" + currentVersion + ").");
                case FAILED -> sender.sendMessage(plugin.getName() + ": update check failed - "
                        + (result.error() != null ? result.error().getMessage() : "unknown error"));
            }
        })));
    }

    /**
     * Reconcile the staged-update marker on boot: if we're now running the
     * staged version the update applied; otherwise the staged jar never got
     * swapped in (or keeps failing) and we warn rather than re-stage forever.
     */
    private void processPendingMarker() {
        PendingUpdateStore.Pending pending = pendingStore.load();
        if (pending == null) return;
        if (Version.parse(pending.stagedVersion(), track).compareTo(Version.parse(currentVersion, track)) == 0) {
            plugin.getLogger().info("Update to " + pending.stagedVersion() + " applied successfully."
                    + (pending.backupPath() != null ? " Previous version backed up at " + pending.backupPath() : ""));
            pendingStore.clear();
            return;
        }
        int attempts = pending.attempts() + 1;
        pendingStore.save(new PendingUpdateStore.Pending(pending.stagedVersion(), pending.backupPath(), attempts));
        stagedVersion = pending.stagedVersion();
        plugin.getLogger().warning("A staged update to " + pending.stagedVersion() + " has not applied after "
                + attempts + " boot(s); still running " + currentVersion + ". Check the server's plugin "
                + "update folder, or restore the previous jar with the update restore command.");
    }

    // ==== Download & stage (mode >= DOWNLOAD) ====

    /**
     * Download the pending update, verify it, back up the running jar and
     * stage the new one for install on the next restart. Reports progress to
     * {@code sender}. Requires a prior check to have found an update.
     */
    public void downloadAndStage(CommandSender sender) {
        if (!mode.atLeast(UpdateMode.DOWNLOAD)) {
            sender.sendMessage(plugin.getName() + ": downloads are disabled (mode " + mode + ").");
            return;
        }
        UpdateInfo info = pendingUpdate;
        if (info == null) {
            sender.sendMessage(plugin.getName() + ": no update pending — run a check first.");
            return;
        }
        if (info.version().equalsIgnoreCase(stagedVersion)) {
            sender.sendMessage(plugin.getName() + ": " + info.version()
                    + " is already staged — restart the server to apply it.");
            return;
        }
        sender.sendMessage(plugin.getName() + ": downloading " + info.version() + "...");
        scheduler.runAsync(() -> {
            String message = stage(info);
            scheduler.runGlobal(() -> sender.sendMessage(plugin.getName() + ": " + message));
        });
    }

    /** Stage the given update. Returns a human-readable outcome line. */
    private String stage(UpdateInfo info) {
        try {
            Path currentJar = PluginJarLocator.locate(plugin, jarFileSupplier);
            DownloadPipeline pipeline = buildPipeline();
            DownloadPipeline.StagingResult result =
                    pipeline.downloadAndStage(info, downloadHeaders, currentJar, currentVersion);
            pendingStore.save(new PendingUpdateStore.Pending(
                    info.version(), result.backupFile().toString(), 0));
            stagedVersion = info.version();
            String note = info.restartRequired()
                    ? "restart the server to apply it."
                    : "it will apply on the next restart.";
            plugin.getLogger().info("Staged update " + info.version() + " -> " + result.stagedFile());
            return "staged " + info.version() + " — " + note;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Update staging failed", e);
            return "update failed: " + e.getMessage();
        }
    }

    /**
     * Stage the most recent backup jar for install on the next restart —
     * the supported one-command recovery path after a bad update.
     */
    public void restoreBackup(CommandSender sender) {
        scheduler.runAsync(() -> {
            String message;
            try {
                Path currentJar = PluginJarLocator.locate(plugin, jarFileSupplier);
                BackupManager backups = buildBackupManager();
                Path latest = backups.latestBackup();
                if (latest == null) {
                    message = "no backups found.";
                } else {
                    buildPipeline().stageLocal(latest, currentJar);
                    pendingStore.clear();
                    stagedVersion = null;
                    message = "staged backup " + latest.getFileName() + " — restart the server to apply it.";
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Backup restore failed", e);
                message = "restore failed: " + e.getMessage();
            }
            String finalMessage = message;
            scheduler.runGlobal(() -> sender.sendMessage(plugin.getName() + ": " + finalMessage));
        });
    }

    /**
     * Apply a staged update immediately via the configured {@link ReloadEngine}
     * — no restart. Refused when no engine is configured, nothing is staged,
     * or the engine's safety checks fail. On success the running plugin
     * instance (including this updater) is replaced.
     */
    public void applyNow(CommandSender sender) {
        if (reloadEngine == null) {
            sender.sendMessage(plugin.getName() + ": hot reload is not available — restart the server to apply.");
            return;
        }
        String staged = stagedVersion;
        if (staged == null) {
            sender.sendMessage(plugin.getName() + ": nothing staged — run update download first.");
            return;
        }
        String refusal = reloadEngine.refusalReason(plugin);
        if (refusal != null) {
            sender.sendMessage(plugin.getName() + ": hot reload refused: " + refusal
                    + " Restart the server to apply the staged update.");
            return;
        }
        scheduler.runGlobal(() -> {
            try {
                Path currentJar = PluginJarLocator.locate(plugin, jarFileSupplier);
                Path stagedJar = Bukkit.getUpdateFolderFile().toPath()
                        .resolve(currentJar.getFileName().toString());
                if (!java.nio.file.Files.exists(stagedJar)) {
                    sender.sendMessage(plugin.getName() + ": staged jar not found — run update download again.");
                    return;
                }
                PendingUpdateStore.Pending pending = pendingStore.load();
                Path backup = pending != null && pending.backupPath() != null
                        ? Path.of(pending.backupPath()) : null;
                sender.sendMessage(plugin.getName() + ": hot-reloading to " + staged
                        + " — watch the console for the outcome.");
                plugin.getLogger().warning("Hot reload starting: " + currentVersion + " -> " + staged);
                // The marker is reconciled by the NEW instance's start().
                reloadEngine.reload(plugin, stagedJar, backup);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Hot reload failed", e);
                sender.sendMessage(plugin.getName() + ": hot reload failed: " + e.getMessage()
                        + " — a restart may be required.");
            }
        });
    }

    private DownloadPipeline buildPipeline() {
        Path dataDir = plugin.getDataFolder().toPath().resolve("pluginpulse");
        return new DownloadPipeline(http, dataDir.resolve("tmp"),
                Bukkit.getUpdateFolderFile().toPath(), buildBackupManager(), requireHash, plugin.getLogger());
    }

    private BackupManager buildBackupManager() {
        return new BackupManager(plugin.getDataFolder().toPath().resolve("pluginpulse").resolve("backups"),
                backupRetention, plugin.getLogger());
    }

    // ==== Check logic ====

    private void runCheck(boolean notify, Consumer<UpdateCheckResult> callback) {
        UpdateCheckResult result = doCheck();
        lastResult = result;
        if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            pendingUpdate = result.info();
            if (mode == UpdateMode.AUTO_STAGE && !result.info().version().equalsIgnoreCase(stagedVersion)) {
                plugin.getLogger().info("Auto-staging update " + result.info().version() + "...");
                plugin.getLogger().info(stage(result.info()));
            }
            if (notify && mode.atLeast(UpdateMode.NOTIFY)) {
                notifier.notifyConsole(plugin.getName(), currentVersion, result.info());
                scheduler.runGlobal(() -> Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission(permission))
                        .forEach(p -> scheduler.runAtEntity(p, () ->
                                notifier.notifySender(p, currentVersion, result.info()))));
            }
        } else {
            pendingUpdate = null;
        }
        if (callback != null) callback.accept(result);
    }

    private UpdateCheckResult doCheck() {
        Exception lastError = null;
        SourceContext ctx = new SourceContext(http, track, plugin.getLogger());
        for (UpdateSource source : sources) {
            try {
                UpdateInfo info = source.fetchLatest(ctx);
                // Authenticated sources carry their headers over to the download.
                downloadHeaders = source instanceof CustomJsonSource custom ? custom.headers() : Map.of();
                state.recordCheck(info.version());
                Version latest = Version.parse(info.version(), track);
                Version current = Version.parse(currentVersion, track);
                if (!latest.isNewerThan(current)) {
                    return UpdateCheckResult.upToDate();
                }
                if (state.isIgnored(info.version())) {
                    return UpdateCheckResult.ignored(info);
                }
                return UpdateCheckResult.available(withChangelogOverride(ctx, info));
            } catch (Exception e) {
                lastError = e;
                plugin.getLogger().log(Level.FINE,
                        "Update source '" + source.name() + "' failed: " + e.getMessage());
            }
        }
        if (lastError != null) {
            plugin.getLogger().warning("Update check failed (all sources): " + lastError.getMessage());
        }
        return UpdateCheckResult.failed(lastError);
    }

    private UpdateInfo withChangelogOverride(SourceContext ctx, UpdateInfo info) {
        if (changelogUrl == null) return info;
        try {
            String text = ctx.http().get(changelogUrl).trim();
            return new UpdateInfo(info.version(), text, info.downloadUrl(), info.fileName(),
                    info.hashes(), info.sizeBytes(), info.restartRequired(), info.releasePageUrl());
        } catch (Exception e) {
            ctx.logger().fine("Changelog fetch failed: " + e.getMessage());
            return info;
        }
    }

    // ==== Accessors ====

    /** The update found by the most recent check, or null when up to date/ignored. */
    public UpdateInfo pendingUpdate() {
        return pendingUpdate;
    }

    public UpdateCheckResult lastResult() {
        return lastResult;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public String permission() {
        return permission;
    }

    public UpdateNotifier notifier() {
        return notifier;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** Suppress notifications for a version until a newer one is released. */
    public void ignoreVersion(String version) {
        state.ignore(version);
        UpdateInfo pending = pendingUpdate;
        if (pending != null && pending.version().equalsIgnoreCase(version)) {
            pendingUpdate = null;
        }
    }

    public void unignoreVersion(String version) {
        state.unignore(version);
    }

    // ==== Builder ====

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private String currentVersion;
        private final List<UpdateSource> sources = new ArrayList<>();
        private UpdateMode mode = UpdateMode.NOTIFY;
        private Duration checkInterval = Duration.ofHours(6);
        private String permission;
        private String prefix;
        private String commandRoot;
        private String track;
        private String changelogUrl;
        private String userAgentContact;
        private SchedulerAdapter scheduler;
        private boolean requireHash = true;
        private int backupRetention = 3;
        private Supplier<File> jarFileSupplier;
        private ReloadEngine reloadEngine;
        private final Map<String, String> messageOverrides = new HashMap<>();

        @SuppressWarnings("deprecation") // getDescription() works on both Paper and Spigot;
        // getPluginMeta() is Paper-only and would NoSuchMethodError on Spigot.
        private Builder(JavaPlugin plugin) {
            this.plugin = plugin;
            this.currentVersion = plugin.getDescription().getVersion();
            this.permission = plugin.getName().toLowerCase() + ".update";
            this.prefix = "<gold>[" + plugin.getName() + "]</gold>";
        }

        /** Primary source, tried first. */
        public Builder source(UpdateSource source) {
            sources.add(0, source);
            return this;
        }

        /** Fallback sources, tried in the order added when earlier ones fail. */
        public Builder fallbackSource(UpdateSource source) {
            sources.add(source);
            return this;
        }

        public Builder currentVersion(String version) {
            this.currentVersion = version;
            return this;
        }

        public Builder mode(UpdateMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder checkInterval(Duration interval) {
            if (interval.toMinutes() < 15) {
                throw new IllegalArgumentException("Check interval must be at least 15 minutes");
            }
            this.checkInterval = interval;
            return this;
        }

        /** Permission required to see update notifications. Default: {@code <plugin>.update}. */
        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** MiniMessage prefix for all notices. Default: {@code <gold>[PluginName]</gold>}. */
        public Builder prefix(String miniMessagePrefix) {
            this.prefix = miniMessagePrefix;
            return this;
        }

        /** Root command (e.g. {@code "/tcp"}) that enables clickable action buttons. */
        public Builder commandRoot(String commandRoot) {
            this.commandRoot = commandRoot;
            return this;
        }

        /** Distribution track suffix (e.g. {@code "mc26"}) for dual-track releases. */
        public Builder track(String track) {
            this.track = track;
            return this;
        }

        /** Optional plain-text/MiniMessage changelog URL that overrides the source changelog. */
        public Builder changelogUrl(String url) {
            this.changelogUrl = url;
            return this;
        }

        /**
         * Contact info embedded in the User-Agent. Modrinth requires a
         * uniquely identifying User-Agent; supply an email or project URL.
         */
        public Builder userAgentContact(String contact) {
            this.userAgentContact = contact;
            return this;
        }

        /** Supply the host plugin's own scheduler abstraction instead of auto-detection. */
        public Builder scheduler(SchedulerAdapter scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Whether staging refuses downloads that publish no checksum.
         * Default true; consider false for GitHub repos without digest support.
         */
        public Builder requireHash(boolean requireHash) {
            this.requireHash = requireHash;
            return this;
        }

        /** How many jar backups to keep (default 3, minimum 1). */
        public Builder backupRetention(int count) {
            this.backupRetention = count;
            return this;
        }

        /**
         * Reflection-free way to hand over the running jar (call the protected
         * {@code getFile()} from your plugin subclass). Recommended for
         * ProGuard-processed plugins where reflection may break.
         */
        public Builder jarFileSupplier(Supplier<File> supplier) {
            this.jarFileSupplier = supplier;
            return this;
        }

        /**
         * Enable no-restart updates by supplying a {@link ReloadEngine}
         * (from the separate {@code pluginpulse-hotreload} artifact).
         * Off by default; restart-install is the recommended path.
         */
        public Builder reloadEngine(ReloadEngine engine) {
            this.reloadEngine = engine;
            return this;
        }

        /** Override a notifier message template (see {@link UpdateNotifier} keys). */
        public Builder message(String key, String miniMessageTemplate) {
            this.messageOverrides.put(key, miniMessageTemplate);
            return this;
        }

        public Updater build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one update source is required");
            }
            return new Updater(this);
        }
    }
}
