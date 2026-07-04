package io.github.darkstarworks.pluginpulse.hotreload;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fully unloads a plugin: disable, unregister every registration Bukkit knows
 * about, remove the plugin manager bookkeeping, and close the classloader so
 * the jar file lock is released (mandatory on Windows before the jar can be
 * replaced).
 */
final class PluginUnloader {

    private PluginUnloader() {
    }

    static void unload(Plugin plugin, Logger logger) throws Exception {
        String name = plugin.getName();

        Bukkit.getPluginManager().disablePlugin(plugin);
        // disablePlugin already cancels tasks/unregisters listeners & services,
        // but plugins that misbehave in onDisable can leave stragglers — sweep again.
        HandlerList.unregisterAll(plugin);
        Bukkit.getScheduler().cancelTasks(plugin);
        Bukkit.getServicesManager().unregisterAll(plugin);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);

        removeCommands(plugin);
        PluginManagerAccess.removeFromManager(Bukkit.getPluginManager(), plugin);

        // Closing the PluginClassLoader releases the jar's file handle.
        // Classes already loaded keep working (the current call stack included);
        // classes not yet loaded from this jar will fail from here on.
        ClassLoader loader = plugin.getClass().getClassLoader();
        if (loader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not close classloader of " + name
                        + " — the old jar may stay locked until restart", e);
            }
        }
        logger.info("Unloaded plugin " + name + ".");
    }

    /**
     * Remove the plugin's commands (and their aliases) from the command map.
     * Modern Paper's public {@code getKnownCommands()} returns an unmodifiable
     * view, so the backing {@code knownCommands} field is mutated via
     * reflection, then the Brigadier dispatcher is re-synced.
     */
    private static void removeCommands(Plugin plugin) throws ReflectiveOperationException {
        Map<String, Command> known = mutableKnownCommands();
        // Paper 1.20.6+ backs this with a Brigadier forwarding map whose
        // iterators don't support remove() — but Map#remove(key) works.
        // Snapshot the matching keys first, then remove by key.
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Command> entry : Map.copyOf(known).entrySet()) {
            if (entry.getValue() instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            Command command = known.get(key);
            known.remove(key);
            if (command != null) {
                command.unregister(Bukkit.getCommandMap());
            }
        }
        if (!toRemove.isEmpty()) {
            syncBrigadierCommands();
        }
    }

    /** The live (mutable) backing map behind the command map's known commands. */
    private static Map<String, Command> mutableKnownCommands() throws ReflectiveOperationException {
        Object commandMap = Bukkit.getCommandMap();
        Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Command> known = (Map<String, Command>) field.get(commandMap);
        return known;
    }

    /** Rebuild the Brigadier command tree after direct map mutation (CraftServer#syncCommands). */
    private static void syncBrigadierCommands() {
        try {
            Method sync = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            sync.setAccessible(true);
            sync.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            // Older servers rebuild lazily; stale tab-complete entries at worst.
        }
    }
}
