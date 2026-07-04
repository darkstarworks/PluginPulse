package io.github.darkstarworks.pluginpulse.hotreload;

import io.github.darkstarworks.pluginpulse.ReloadEngine;
import io.github.darkstarworks.pluginpulse.download.PluginJarLocator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opt-in no-restart update application: unload the running plugin, swap its
 * jar for the staged one, and load + enable the replacement. Rolls back to
 * the backup jar when the new version fails to load or enable.
 *
 * <p>Hard limits, by design:</p>
 * <ul>
 *   <li>Refused on Folia.</li>
 *   <li>Refused while other enabled plugins depend (hard or soft) on the target.</li>
 *   <li>Static state, executors and objects other plugins captured from the old
 *       instance are the host's responsibility — repeated reloads can leak
 *       metaspace. Restart-install remains the recommended path.</li>
 * </ul>
 */
public final class HotReloadEngine implements ReloadEngine {

    private HotReloadEngine() {
    }

    public static HotReloadEngine create() {
        return new HotReloadEngine();
    }

    @Override
    public String refusalReason(JavaPlugin plugin) {
        return SafetyChecks.refusalReason(plugin);
    }

    @Override
    public void reload(JavaPlugin plugin, Path newJar, Path backupJar) throws Exception {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Hot reload must run on the main thread");
        }
        String refusal = SafetyChecks.refusalReason(plugin);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        Logger logger = Bukkit.getLogger();
        Path liveJar = PluginJarLocator.locate(plugin, null);
        String name = plugin.getName();

        // Everything executed after the classloader closes must already be
        // loaded — touch our own classes now so no lazy load hits a closed jar.
        preloadOwnClasses();

        PluginUnloader.unload(plugin, logger);

        try {
            Files.move(newJar, liveJar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Old jar still locked (classloader close failed?) — nothing was
            // broken yet beyond the unload; a restart will pick up the staged jar.
            throw new IOException("Could not replace " + liveJar.getFileName()
                    + " (file still locked?) — the update will apply on restart instead", e);
        }

        // Paper remaps plugin jars into <plugins>/.paper-remapped/<name>; the
        // cache can serve the OLD bytes for the swapped-in jar since the
        // filename is unchanged. Drop the cached copy so it re-remaps.
        try {
            Path remapped = liveJar.getParent().resolve(".paper-remapped").resolve(liveJar.getFileName().toString());
            Files.deleteIfExists(remapped);
        } catch (IOException e) {
            logger.fine("Could not clear remap cache: " + e.getMessage());
        }

        try {
            loadAndEnable(liveJar, logger);
            logger.info("Hot reload of " + name + " complete.");
        } catch (Exception loadFailure) {
            logger.log(Level.SEVERE, "New version of " + name + " failed to load — rolling back", loadFailure);
            rollback(liveJar, backupJar, name, logger, loadFailure);
        }
    }

    private void loadAndEnable(Path jar, Logger logger) throws Exception {
        Plugin loaded = Bukkit.getPluginManager().loadPlugin(jar.toFile());
        if (loaded == null) {
            throw new IllegalStateException("Server returned no plugin instance for " + jar.getFileName());
        }
        loaded.onLoad();
        Bukkit.getPluginManager().enablePlugin(loaded);
        if (!loaded.isEnabled()) {
            throw new IllegalStateException(loaded.getName() + " did not enable");
        }
    }

    private void rollback(Path liveJar, Path backupJar, String name, Logger logger, Exception cause) throws Exception {
        if (backupJar == null || !Files.exists(backupJar)) {
            throw new IllegalStateException("New version of " + name
                    + " failed and no backup is available — reinstall manually", cause);
        }
        Files.copy(backupJar, liveJar, StandardCopyOption.REPLACE_EXISTING);
        try {
            loadAndEnable(liveJar, logger);
            logger.warning("Rolled " + name + " back to the previous version after a failed hot reload.");
        } catch (Exception rollbackFailure) {
            rollbackFailure.addSuppressed(cause);
            throw new IllegalStateException("Rollback of " + name
                    + " ALSO failed — the plugin is not running; restart the server", rollbackFailure);
        }
    }

    /**
     * Force-load every class this engine touches after the plugin's
     * classloader is closed. When the engine is shaded into the plugin being
     * reloaded, lazy loading past that point would throw NoClassDefFoundError.
     */
    private static void preloadOwnClasses() {
        // Referencing the classes is enough to trigger loading.
        Class<?>[] needed = {
                PluginUnloader.class,
                PluginManagerAccess.class,
                SafetyChecks.class,
                PluginJarLocator.class,
                StandardCopyOption.class,
        };
        for (Class<?> c : needed) {
            c.getName();
        }
    }
}
