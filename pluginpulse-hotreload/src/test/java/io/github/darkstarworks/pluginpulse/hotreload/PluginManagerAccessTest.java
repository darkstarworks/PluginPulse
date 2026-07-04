package io.github.darkstarworks.pluginpulse.hotreload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the bookkeeping-holder walk against fakes shaped like both known
 * server layouts (fields matter, classes don't).
 */
class PluginManagerAccessTest {

    /** Legacy layout: the manager itself holds plugins + lookupNames. */
    static class FakeSimplePluginManager {
        final List<Object> plugins = new ArrayList<>();
        final Map<String, Object> lookupNames = new HashMap<>();
    }

    /** Paper 1.20.5+ layout: manager -> paperPluginManager -> instanceManager. */
    static class FakeInstanceManager {
        final List<Object> plugins = new ArrayList<>();
        final Map<String, Object> lookupNames = new HashMap<>();
    }

    static class FakePaperPluginManagerImpl {
        final FakeInstanceManager instanceManager = new FakeInstanceManager();
    }

    static class FakeDelegatingSimplePluginManager {
        final FakePaperPluginManagerImpl paperPluginManager = new FakePaperPluginManagerImpl();
    }

    static class FakeUnknownManager {
        final String somethingElse = "nope";
    }

    /** Real modern Paper shape: legacy dead fields AND the live delegated manager. */
    static class FakeModernSimplePluginManager {
        final List<Object> plugins = new ArrayList<>();
        final Map<String, Object> lookupNames = new HashMap<>();
        final FakePaperPluginManagerImpl paperPluginManager = new FakePaperPluginManagerImpl();
    }

    @Test
    void findsLegacyLayoutDirectly() {
        FakeSimplePluginManager manager = new FakeSimplePluginManager();
        assertSame(manager, PluginManagerAccess.findBookkeepingHolders(manager).get(0));
    }

    @Test
    void walksPaperDelegationChain() {
        FakeDelegatingSimplePluginManager manager = new FakeDelegatingSimplePluginManager();
        assertSame(manager.paperPluginManager.instanceManager,
                PluginManagerAccess.findBookkeepingHolders(manager).get(0));
    }

    @Test
    void collectsBothLegacyAndDelegatedHolders() {
        // Modern Paper keeps dead legacy fields on SimplePluginManager while
        // the real bookkeeping lives in the delegated instance manager — the
        // walk must return BOTH so removal scrubs the live one.
        FakeModernSimplePluginManager manager = new FakeModernSimplePluginManager();
        List<Object> holders = PluginManagerAccess.findBookkeepingHolders(manager);
        assertEquals(2, holders.size());
        assertSame(manager, holders.get(0));
        assertSame(manager.paperPluginManager.instanceManager, holders.get(1));
    }

    @Test
    void unknownLayoutReturnsEmpty() {
        assertTrue(PluginManagerAccess.findBookkeepingHolders(new FakeUnknownManager()).isEmpty());
    }
}
