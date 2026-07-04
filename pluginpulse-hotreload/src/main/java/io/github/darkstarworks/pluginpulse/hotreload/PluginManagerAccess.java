package io.github.darkstarworks.pluginpulse.hotreload;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Removes a plugin from the server plugin manager's internal bookkeeping so a
 * replacement can be loaded under the same name.
 *
 * <p>Two known layouts, handled by one reflective walk:</p>
 * <ul>
 *   <li>Legacy Spigot/Paper: {@code SimplePluginManager} holds
 *       {@code List plugins} + {@code Map lookupNames} directly.</li>
 *   <li>Paper 1.20.5+: {@code SimplePluginManager} delegates via a
 *       {@code paperPluginManager} field to {@code PaperPluginManagerImpl},
 *       whose {@code instanceManager} ({@code PluginInstanceManagerImpl})
 *       holds the {@code plugins} list and {@code lookupNames} map.</li>
 * </ul>
 *
 * <p>The walk follows the delegation field names rather than concrete classes
 * so it fails soft (with a clear exception) on unknown server internals.</p>
 */
final class PluginManagerAccess {

    /** Delegation fields worth descending into, in discovery order. */
    private static final String[] DELEGATE_FIELDS = {"paperPluginManager", "instanceManager", "instanceManagerCompanion"};

    private PluginManagerAccess() {
    }

    /**
     * @throws IllegalStateException when no known bookkeeping layout is found
     */
    static void removeFromManager(Object pluginManager, Plugin plugin) throws ReflectiveOperationException {
        List<Object> holders = findBookkeepingHolders(pluginManager);
        if (holders.isEmpty()) {
            throw new IllegalStateException("Unrecognized plugin manager internals ("
                    + pluginManager.getClass().getName() + ") — cannot hot reload on this server version.");
        }
        // On modern Paper the legacy SimplePluginManager STILL declares (dead)
        // plugins/lookupNames fields while the live bookkeeping sits in the
        // delegated PaperPluginInstanceManager — scrub every holder found.
        for (Object holder : holders) {
            Object pluginsField = getFieldValue(holder, "plugins");
            if (pluginsField instanceof List<?> plugins) {
                plugins.remove(plugin);
            }
            Object lookupField = getFieldValue(holder, "lookupNames");
            if (lookupField instanceof Map<?, ?> lookup) {
                lookup.values().removeIf(v -> v == plugin);
            }
        }
    }

    /**
     * Breadth-first walk from the plugin manager through known delegation
     * fields, collecting every object that owns both {@code plugins} and
     * {@code lookupNames}.
     */
    static List<Object> findBookkeepingHolders(Object root) {
        List<Object> holders = new java.util.ArrayList<>();
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        int depth = 0;
        while (!queue.isEmpty() && depth++ < 8) {
            Object candidate = queue.poll();
            if (hasField(candidate, "plugins") && hasField(candidate, "lookupNames")) {
                holders.add(candidate);
            }
            for (String delegate : DELEGATE_FIELDS) {
                try {
                    Object next = getFieldValue(candidate, delegate);
                    if (next != null) queue.add(next);
                } catch (ReflectiveOperationException ignored) {
                    // field absent on this layout — keep walking
                }
            }
        }
        return holders;
    }

    private static boolean hasField(Object obj, String name) {
        return findField(obj.getClass(), name) != null;
    }

    private static Object getFieldValue(Object obj, String name) throws ReflectiveOperationException {
        Field field = findField(obj.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldException(name + " on " + obj.getClass().getName());
        }
        field.setAccessible(true);
        return field.get(obj);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try superclass
            }
        }
        return null;
    }
}
