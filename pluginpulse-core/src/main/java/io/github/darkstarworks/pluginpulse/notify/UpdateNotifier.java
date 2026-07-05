package io.github.darkstarworks.pluginpulse.notify;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and sends update notices. On Paper these render as rich MiniMessage
 * (colours, hover, clickable links); on Spigot — which does not bundle
 * Adventure — they fall back to plain text with the download URL spelled out,
 * so the updater still works everywhere.
 *
 * <p>Every MiniMessage template can be overridden by the host plugin;
 * placeholders are literal tokens: {@code <prefix>}, {@code <current>},
 * {@code <latest>}, {@code <page>}, {@code <cmdroot>}. Overrides only affect
 * the Adventure path; the plain fallback uses a fixed, readable format.</p>
 */
public final class UpdateNotifier {

    public static final String KEY_CONSOLE = "console";
    public static final String KEY_PLAYER = "player";
    public static final String KEY_BUTTONS = "buttons";

    /** Whether Adventure/MiniMessage is available (Paper yes, Spigot no). */
    private static final boolean ADVENTURE_PRESENT = detectAdventure();

    private static boolean detectAdventure() {
        try {
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

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
        if (ADVENTURE_PRESENT) {
            AdventureText.console(render(KEY_CONSOLE, current, info));
            for (String line : info.changelog().split("\n")) {
                if (!line.isBlank()) AdventureText.console(line);
            }
        } else {
            Bukkit.getConsoleSender().sendMessage(
                    stripTags(prefix) + " Update available: " + info.version() + " (current: " + current + ")");
            for (String line : info.changelog().split("\n")) {
                if (!line.isBlank()) Bukkit.getConsoleSender().sendMessage(stripTags(line));
            }
        }
    }

    /** Player/CommandSender notice with clickable download link (+ optional buttons). */
    public void notifySender(CommandSender sender, String current, UpdateInfo info) {
        if (ADVENTURE_PRESENT) {
            String template = messages.get(KEY_PLAYER);
            if (commandRoot != null && !commandRoot.isBlank()) {
                template += messages.get(KEY_BUTTONS);
            }
            AdventureText.send(sender, render(KEY_PLAYER, current, info, template));
        } else {
            String page = pageUrl(info);
            StringBuilder msg = new StringBuilder(stripTags(prefix))
                    .append(" Update available: ").append(info.version())
                    .append(" (current: ").append(current).append(").");
            if (!page.isEmpty()) msg.append(" Download: ").append(page);
            if (commandRoot != null && !commandRoot.isBlank()) {
                msg.append("  Run '").append(commandRoot).append(" update download' to install.");
            }
            sender.sendMessage(msg.toString());
        }
    }

    /** Rendered MiniMessage string for {@code key} (Adventure path only). */
    public String render(String key, String current, UpdateInfo info) {
        return render(key, current, info, messages.get(key));
    }

    private String render(String key, String current, UpdateInfo info, String template) {
        return template
                .replace("<prefix>", prefix)
                .replace("<current>", current)
                .replace("<latest>", info.version())
                .replace("<page>", pageUrl(info))
                .replace("<cmdroot>", commandRoot == null ? "" : commandRoot);
    }

    private static String pageUrl(UpdateInfo info) {
        return info.releasePageUrl() != null ? info.releasePageUrl()
                : info.downloadUrl() != null ? info.downloadUrl() : "";
    }

    /** Drop MiniMessage tags for the plain-text (Spigot) fallback. */
    static String stripTags(String s) {
        return s.replace("<newline>", " ").replaceAll("<[^>]*>", "").trim();
    }
}
