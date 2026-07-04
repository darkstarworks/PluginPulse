package io.github.darkstarworks.pluginpulse.platform;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Minimal scheduler abstraction so the library runs on both Paper (Bukkit
 * scheduler) and Folia (regionized schedulers). Hosts with their own adapter
 * (e.g. an existing Folia abstraction) can supply it via the builder instead.
 */
public interface SchedulerAdapter {

    interface TaskHandle {
        void cancel();
    }

    void runAsync(Runnable task);

    /** Repeating async task; delay/period in ticks (1 tick = 50 ms). */
    TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);

    /** Global/main-thread execution for non-entity Bukkit API access. */
    void runGlobal(Runnable task);

    /** Execution on the thread owning the entity (player messaging on Folia). */
    void runAtEntity(Entity entity, Runnable task);

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static SchedulerAdapter create(Plugin plugin) {
        return isFolia() ? new FoliaSchedulerAdapter(plugin) : new BukkitSchedulerAdapter(plugin);
    }
}
