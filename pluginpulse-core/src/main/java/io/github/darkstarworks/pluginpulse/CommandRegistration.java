package io.github.darkstarworks.pluginpulse;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Self-registers a root command so injected/one-file adopters get a working
 * {@code /<root> update ...} command without declaring it in a descriptor.
 *
 * <p>An injected jar cannot hook the host's arbitrary command dispatch, and a
 * one-file adopter shouldn't be forced to write a command class. When a
 * {@code command-root} is configured this registers a tiny {@link Command}
 * straight into the server's {@link CommandMap} whose {@code execute} delegates
 * to the bundled {@link UpdateSubcommand}.</p>
 *
 * <p><b>Fail-soft by contract.</b> The command-map / Brigadier reflection
 * targets differ across Spigot, Paper, Folia and JPMS layers; every step here
 * catches its own failure and degrades to "no self-registered command" rather
 * than throwing into the host's lifecycle. If the name is already taken (the
 * normal case for a plugin that declares its command in {@code plugin.yml})
 * registration is skipped, leaving the host's own command untouched.</p>
 */
final class CommandRegistration {

    private final JavaPlugin plugin;
    private final String label;
    private final UpdateSubcommand handler;
    private final String permission;

    private Command registered;

    CommandRegistration(JavaPlugin plugin, String commandRoot, UpdateSubcommand handler, String permission) {
        this.plugin = plugin;
        this.label = normalize(commandRoot);
        this.handler = handler;
        this.permission = permission;
    }

    /** Strip a leading slash and any arguments, lower-case the bare command name. */
    static String normalize(String commandRoot) {
        String s = commandRoot.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        return s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Register the command if the name is free. Returns silently (fail-soft) on
     * any reflection failure or if the name is taken.
     */
    void register() {
        if (label.isEmpty()) return;
        try {
            CommandMap commandMap = commandMap();
            if (commandMap == null) return;
            if (commandMap.getCommand(label) != null) {
                // Name already owned (e.g. the host declares it in plugin.yml).
                // Leave it alone — the host wires update delegation itself.
                plugin.getLogger().fine("PluginPulse: command '" + label
                        + "' already registered; skipping self-registration.");
                return;
            }
            Command command = new PulseCommand(label);
            commandMap.register(plugin.getName().toLowerCase(java.util.Locale.ROOT), command);
            this.registered = command;
            syncCommands();
            plugin.getLogger().fine("PluginPulse: self-registered command '/" + label + "'.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE,
                    "PluginPulse: could not self-register command '/" + label + "' — "
                            + "run updates via the host's own command instead.", t);
        }
    }

    /** Remove the self-registered command, if any. Fail-soft. */
    void unregister() {
        Command command = this.registered;
        this.registered = null;
        if (command == null) return;
        try {
            CommandMap commandMap = commandMap();
            if (commandMap == null) return;
            command.unregister(commandMap);
            Map<String, Command> known = knownCommands(commandMap);
            if (known != null) {
                known.entrySet().removeIf(e -> e.getValue() == command);
            }
            syncCommands();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "PluginPulse: command unregister failed.", t);
        }
    }

    /**
     * The server's live command map. {@code getCommandMap()} is a public method
     * on both CraftServer (Spigot) and Paper, so reflection on the server class
     * works everywhere without depending on Paper-only {@code Bukkit#getCommandMap}.
     */
    private static CommandMap commandMap() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        try {
            Method m = server.getClass().getMethod("getCommandMap");
            m.setAccessible(true);
            return (CommandMap) m.invoke(server);
        } catch (NoSuchMethodException e) {
            Field f = server.getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            return (CommandMap) f.get(server);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap commandMap) {
        try {
            Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
            field.setAccessible(true);
            return (Map<String, Command>) field.get(commandMap);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Rebuild the Brigadier dispatcher so tab-completion sees the change. */
    private static void syncCommands() {
        try {
            Method sync = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            sync.setAccessible(true);
            sync.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            // Older/leaner servers rebuild lazily; stale tab-complete at worst.
        }
    }

    /** The command backing the self-registered root; delegates to {@link UpdateSubcommand}. */
    private final class PulseCommand extends Command {

        PulseCommand(String name) {
            super(name);
            setDescription("Manage " + plugin.getName() + " updates.");
            setUsage("/" + name + " update <check|download|apply|ignore|unignore|restore|status>");
            setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            handler.handle(sender, tail(args));
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return handler.tabComplete(tail(args));
        }

        /**
         * Accept both the clickable-button form {@code /<root> update check}
         * (buttons dispatch "<commandRoot> update ...") and the bare
         * {@code /<root> check} form.
         */
        private String[] tail(String[] args) {
            if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
                return Arrays.copyOfRange(args, 1, args.length);
            }
            return args;
        }
    }
}
