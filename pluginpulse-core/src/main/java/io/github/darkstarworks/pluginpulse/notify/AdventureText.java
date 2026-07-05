package io.github.darkstarworks.pluginpulse.notify;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * All Adventure/MiniMessage usage is quarantined here. This class is only
 * referenced (and therefore only class-loaded) when {@link UpdateNotifier}
 * has confirmed Adventure is present on the server — so it never triggers a
 * {@code NoClassDefFoundError} on Spigot, which does not bundle Adventure.
 */
final class AdventureText {

    private AdventureText() {
    }

    /** Deserialize a MiniMessage string and send it to a recipient. */
    static void send(CommandSender target, String miniMessage) {
        target.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    /** Deserialize and send to the server console. */
    static void console(String miniMessage) {
        Bukkit.getConsoleSender().sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }
}
