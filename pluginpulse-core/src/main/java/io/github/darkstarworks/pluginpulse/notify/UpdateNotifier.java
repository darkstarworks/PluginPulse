package io.github.darkstarworks.pluginpulse.notify;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and sends MiniMessage update notices. Every message template can be
 * overridden by the host plugin; placeholders are simple literal tokens:
 * {@code <prefix>}, {@code <current>}, {@code <latest>}, {@code <page>},
 * {@code <cmdroot>}.
 */
public final class UpdateNotifier {

    public static final String KEY_CONSOLE = "console";
    public static final String KEY_PLAYER = "player";
    public static final String KEY_BUTTONS = "buttons";

    private static final Map<String, String> DEFAULTS = Map.of(
            KEY_CONSOLE, "<prefix> <green>Update available:</green> <yellow><latest></yellow> <gray>(current: <current>)</gray>",
            KEY_PLAYER, "<prefix> <gray>You are using version <yellow><current></yellow>, "
                    + "latest version is <green><latest></green>."
                    + "<newline><click:open_url:'<page>'><hover:show_text:'<gray>Open the download page'>"
                    + "<aqua>[Download Latest Version]</aqua></hover></click>",
            KEY_BUTTONS, " <click:run_command:'<cmdroot> update ignore <latest>'>"
                    + "<hover:show_text:'<gray>Hide notifications for this version'><gray>[Ignore]</gray></hover></click>"
    );

    private final String prefix;
    private final String commandRoot; // e.g. "/tcp" — enables the [Ignore] button
    private final Map<String, String> messages;

    public UpdateNotifier(String prefix, String commandRoot, Map<String, String> overrides) {
        this.prefix = prefix;
        this.commandRoot = commandRoot;
        this.messages = new HashMap<>(DEFAULTS);
        this.messages.putAll(overrides);
    }

    public void notifyConsole(String pluginName, String current, UpdateInfo info) {
        Component header = render(KEY_CONSOLE, current, info);
        Bukkit.getConsoleSender().sendMessage(header);
        // Changelog lines go out raw through MiniMessage so hosts can style them.
        for (String line : info.changelog().split("\n")) {
            if (!line.isBlank()) {
                Bukkit.getConsoleSender().sendMessage(MiniMessage.miniMessage().deserialize(line));
            }
        }
    }

    /** Player/CommandSender notice with clickable download link (+ optional buttons). */
    public void notifySender(CommandSender sender, String current, UpdateInfo info) {
        String template = messages.get(KEY_PLAYER);
        if (commandRoot != null && !commandRoot.isBlank()) {
            template += messages.get(KEY_BUTTONS);
        }
        sender.sendMessage(deserialize(template, current, info));
    }

    public Component render(String key, String current, UpdateInfo info) {
        return deserialize(messages.get(key), current, info);
    }

    private Component deserialize(String template, String current, UpdateInfo info) {
        String page = info.releasePageUrl() != null ? info.releasePageUrl()
                : info.downloadUrl() != null ? info.downloadUrl() : "";
        String rendered = template
                .replace("<prefix>", prefix)
                .replace("<current>", current)
                .replace("<latest>", info.version())
                .replace("<page>", page)
                .replace("<cmdroot>", commandRoot == null ? "" : commandRoot);
        return MiniMessage.miniMessage().deserialize(rendered);
    }
}
