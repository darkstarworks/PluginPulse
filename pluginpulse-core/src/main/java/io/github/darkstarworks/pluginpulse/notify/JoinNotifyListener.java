package io.github.darkstarworks.pluginpulse.notify;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.Updater;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Shows the pending-update notice to permitted players when they log in,
 * deferred one tick so the join message stream has settled.
 */
public final class JoinNotifyListener implements Listener {

    private final Updater updater;

    public JoinNotifyListener(Updater updater) {
        this.updater = updater;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UpdateInfo pending = updater.pendingUpdate();
        if (pending == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission(updater.permission())) return;
        updater.scheduler().runAtEntity(player, () -> {
            if (player.isOnline()) {
                updater.notifier().notifySender(player, updater.currentVersion(), pending);
            }
        });
    }
}
