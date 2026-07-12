package com.enhancedechest.storage;

import com.enhancedechest.crossserver.CrossServerCoordinator;
import com.enhancedechest.storage.sql.SqliteStorage;
import com.enhancedechest.telemetry.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-server leg of the residency invariant, driven through {@link CachedStorage} +
 * {@link OwnerResidencyCache} with a recording fake coordinator over the real SQLite backend:
 * <i>resident ⇒ lock held</i> (acquired before the backend read on every load path), locks released
 * only by the two eviction paths (quit write-back, idle eviction) and only for owners that flushed
 * clean, and a release that races a concurrent load forcing that load to re-acquire + re-read.
 */
class CrossServerCacheTest {

    /** Records the protocol; lock state is local (no Redis) but follows the real contract. */
    static final class FakeCoordinator implements CrossServerCoordinator {
        final Set<UUID> held = ConcurrentHashMap.newKeySet();
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger finishedReleases = new AtomicInteger();
        /** When set, the next {@link #isHeld} check reports false once (simulating a raced release). */
        volatile UUID dropOnNextIsHeldCheck;

        @Override public void acquireOwner(UUID owner) {
            acquires.incrementAndGet();
            held.add(owner);
        }

        @Override public boolean isHeld(UUID owner) {
            if (owner.equals(dropOnNextIsHeldCheck)) {
                dropOnNextIsHeldCheck = null;
                held.remove(owner);
                return false;
            }
            return held.contains(owner);
        }

        @Override public boolean isHeldElsewhere(UUID owner) { return false; }

        @Override public void beginRelease(UUID owner) { held.remove(owner); }

        @Override public void finishRelease(UUID owner) { finishedReleases.incrementAndGet(); }
    }

    @TempDir
    Path dir;

    FakeCoordinator coordinator;
    CachedStorage storage;

    @BeforeEach
    void setUp() {
        coordinator = new FakeCoordinator();
        storage = new CachedStorage(new SqliteStorage(dir, "crossserver.db", "echest_"),
                LoggerFactory.getLogger("cross-server-test"), Telemetry.NOOP, coordinator);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void loadOnMissAcquiresTheOwnerLock() {
        UUID owner = UUID.randomUUID();
        storage.listChests(owner);

        assertEquals(1, coordinator.acquires.get(), "first touch acquires the lock");
        assertTrue(coordinator.held.contains(owner), "owner stays held while resident");

        storage.listChests(owner);
        assertEquals(1, coordinator.acquires.get(), "resident owner is served without re-acquiring");
    }

    @Test
    void quitWriteBackFlushesThenReleases() {
        UUID owner = UUID.randomUUID();
        int index = storage.createChest(owner, 27, null);

        storage.flushOwner(owner);                 // quit path: flush + evict + release

        assertFalse(coordinator.held.contains(owner), "evicted owner's lock is released");
        assertEquals(1, coordinator.finishedReleases.get());

        // The next touch re-acquires and reloads the flushed rows from SQL.
        List<?> chests = storage.listChests(owner);
        assertEquals(1, chests.size(), "flushed row survives the round-trip");
        assertEquals(2, coordinator.acquires.get());
        assertEquals(index, 1);
    }

    @Test
    void pinnedOwnerIsNeverReleased() {
        UUID owner = UUID.randomUUID();
        storage.pin(owner);
        storage.createChest(owner, 27, null);

        storage.flushOwner(owner);                 // online: flush only, no eviction
        storage.flush();
        storage.evictIdle();

        assertTrue(coordinator.held.contains(owner), "online owner keeps their lock");
        assertEquals(0, coordinator.finishedReleases.get());
    }

    @Test
    void idleEvictionReleasesOnlyCleanOwners() {
        UUID clean = UUID.randomUUID();
        UUID dirty = UUID.randomUUID();
        storage.createChest(clean, 27, null);
        storage.createChest(dirty, 27, null);
        storage.flush();                           // both clean now
        storage.saveChest(dirty, 1, new byte[] {2, 0});  // re-dirty one

        int evicted = storage.evictIdle();

        assertEquals(1, evicted);
        assertFalse(coordinator.held.contains(clean), "clean offline owner released");
        assertTrue(coordinator.held.contains(dirty), "dirty owner stays resident and locked");
    }

    @Test
    void racedReleaseForcesReacquireAndRereadBeforeResidency() {
        UUID owner = UUID.randomUUID();
        storage.createChest(owner, 27, null);
        storage.flushOwner(owner);                 // evict + release so the next touch is a fresh load
        int acquiresBefore = coordinator.acquires.get();

        // Simulate an eviction's release landing between the load's acquire and its residency flip:
        // the under-lock isHeld re-check fails once, so the load must re-acquire and re-read.
        coordinator.dropOnNextIsHeldCheck = owner;
        List<?> chests = storage.listChests(owner);

        assertEquals(1, chests.size());
        assertEquals(acquiresBefore + 2, coordinator.acquires.get(),
                "a failed isHeld re-check costs exactly one extra acquire + re-read");
        assertTrue(coordinator.held.contains(owner));
    }
}
