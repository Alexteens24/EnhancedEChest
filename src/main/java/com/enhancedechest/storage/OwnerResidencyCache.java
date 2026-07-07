package com.enhancedechest.storage;

import com.enhancedechest.storage.ChestCacheState.DirtyBatch;
import com.enhancedechest.storage.EnderChestStorage.RawChestRow;
import com.enhancedechest.storage.EnderChestStorage.RawPlayerRow;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The coherence engine behind {@link CachedStorage}: the load-on-miss residency protocol, the
 * write-back flush, and idle eviction. It owns the single lock, the residency bookkeeping
 * ({@link #resident}, {@link #loading}, {@link #pinned}) and drives the SQL {@link StorageBackend};
 * all row work is delegated to {@link ChestCacheState}. The class is deliberately generic — it knows
 * only "owners" and "dirty rows", never ender-chest semantics (those live in {@link CachedStorage}).
 *
 * <p><b>Residency invariant (load-bearing):</b> an owner's rows exist in memory iff the owner is in
 * {@link #resident}; every per-owner operation runs through {@link #withOwner}, which re-checks
 * residency and executes the operation <i>in the same lock hold</i> — so an eviction can never
 * interleave between the load and the operation. Corollaries: dirty ⇒ resident (only resident owners
 * are ever mutated), and eviction takes only clean owners, so a non-resident owner's SQL rows are
 * always current. Eviction ({@link #evictIdle} and the eviction step of {@link #flushOwner})
 * additionally holds {@link #flushLock}, so it never observes an owner as clean during a flush's
 * in-flight (and possibly failing) JDBC write, when that owner's keys are transiently out of the
 * dirty sets but not yet persisted.
 *
 * <p><b>Thread-safety:</b> every state mutation happens under {@link #lock} (all cheap map work);
 * cache-miss JDBC reads and all flush JDBC writes happen <b>outside</b> it, so a slow database never
 * blocks gameplay reads/writes. Concurrent misses on the same owner are collapsed to one backend read
 * via {@link #loading}. Rows that fail to flush are re-marked dirty and retried on the next autosave.
 * Lock ordering is always {@code flushLock -> lock}.
 */
final class OwnerResidencyCache {

    private final StorageBackend backend;
    private final ChestCacheState state;
    private final Logger logger;

    private final Object lock = new Object();
    /** Owners whose rows are materialized in memory. Guarded by {@link #lock}. */
    private final Set<UUID> resident = new HashSet<>();
    /** One in-flight backend read per owner; concurrent misses wait on it. Guarded by {@link #lock}. */
    private final Map<UUID, CompletableFuture<Void>> loading = new HashMap<>();
    /** Online owners — never evicted. Maintained by the join/quit listener, read by eviction. */
    private final Set<UUID> pinned = ConcurrentHashMap.newKeySet();

    /** Serializes flushes (autosave, quit, backup-triggered, shutdown) so backend writes never interleave. */
    private final Object flushLock = new Object();

    OwnerResidencyCache(StorageBackend backend, ChestCacheState state, Logger logger) {
        this.backend = backend;
        this.state = state;
        this.logger = logger;
    }

    // ---- online lifecycle (join/quit listener) ----

    /** Marks an owner online: their rows survive every eviction until {@link #unpin}. */
    void pin(UUID owner) {
        pinned.add(owner);
    }

    /** Unmarks an owner as online. Their rows stay resident until flushed clean and evicted. */
    void unpin(UUID owner) {
        pinned.remove(owner);
    }

    // ---- residency core (the load-on-miss protocol) ----

    /**
     * Runs {@code op} under {@link #lock} with {@code owner} guaranteed resident <i>during</i> the
     * operation. On a miss the owner's rows are read from the backend outside the lock (deduped via
     * {@link #loading}), then residency is re-checked — the loop closes the race where an eviction
     * lands between the load completing and the operation acquiring the lock. A backend read failure
     * propagates to the caller, exactly like a failed SQL query did before the cache existed.
     */
    <T> T withOwner(UUID owner, Supplier<T> op) {
        while (true) {
            synchronized (lock) {
                if (resident.contains(owner)) {
                    return op.get();
                }
            }
            loadOwner(owner);
        }
    }

    /** {@link #withOwner} for void operations. */
    void mutateOwner(UUID owner, Runnable op) {
        withOwner(owner, () -> {
            op.run();
            return null;
        });
    }

    /** {@link #withOwner} needing two owners resident in the same lock hold (transfer). */
    <T> T withOwners(UUID first, UUID second, Supplier<T> op) {
        while (true) {
            UUID missing;
            synchronized (lock) {
                if (!resident.contains(first)) {
                    missing = first;
                } else if (!resident.contains(second)) {
                    missing = second;
                } else {
                    return op.get();
                }
            }
            loadOwner(missing);
        }
    }

    /**
     * Runs {@code op} under {@link #lock} without pinning any particular owner resident — for the
     * whole-database reads ({@link CachedStorage#findExpired}, the {@code /ee import} cache refresh)
     * that scan whatever owners happen to be resident. Callers inside {@code op} may consult
     * {@link #isResidentLocked}.
     */
    <T> T runLocked(Supplier<T> op) {
        synchronized (lock) {
            return op.get();
        }
    }

    /** Whether an owner is currently resident. Callers hold {@link #lock} (i.e. inside {@link #runLocked}). */
    boolean isResidentLocked(UUID owner) {
        return resident.contains(owner);
    }

    /**
     * Reads one owner's rows from the backend and materializes them, collapsing concurrent misses to
     * a single read: the first caller becomes the loader, later callers wait on its future. The JDBC
     * reads run outside {@link #lock}; the rows are applied and residency flipped in one lock hold.
     */
    private void loadOwner(UUID owner) {
        CompletableFuture<Void> mine = null;
        CompletableFuture<Void> theirs;
        synchronized (lock) {
            if (resident.contains(owner)) {
                return;
            }
            theirs = loading.get(owner);
            if (theirs == null) {
                mine = new CompletableFuture<>();
                loading.put(owner, mine);
            }
        }
        if (theirs != null) {
            theirs.join();                         // propagate the loader's failure, if any
            return;
        }
        try {
            String key = owner.toString();
            List<RawChestRow> rows = backend.loadChests(key);
            RawPlayerRow playerRow = backend.loadPlayer(key);
            synchronized (lock) {
                if (!resident.contains(owner)) {
                    for (RawChestRow c : rows) {
                        state.applyRawChest(c);
                    }
                    if (playerRow != null) {
                        state.applyRawPlayer(playerRow);
                    }
                    resident.add(owner);
                }
                loading.remove(owner);
            }
            mine.complete(null);
        } catch (Throwable t) {
            synchronized (lock) {
                loading.remove(owner);
            }
            mine.completeExceptionally(t);
            throw t;
        }
    }

    // ---- write-back & eviction ----

    /**
     * Writes every dirty row back to the SQL backend, in one transaction per table. Called by the
     * autosave timer, at shutdown, and before backup / import / count touch the SQL side directly. On
     * failure the rows are re-marked dirty (for the next autosave) and the exception propagates.
     *
     * @return the number of rows written or deleted (0 when nothing was dirty)
     */
    int flush() {
        return flushInternal(null);
    }

    /** Flush implementation; {@code only} non-null restricts it to that owner's dirty rows. */
    private int flushInternal(UUID only) {
        synchronized (flushLock) {
            DirtyBatch batch;
            synchronized (lock) {
                batch = state.collectDirty(only);
                if (batch.isEmpty()) {
                    return 0;
                }
            }
            try {
                if (!batch.chestUpserts().isEmpty() || !batch.chestDeletes().isEmpty()) {
                    backend.flushChests(batch.chestUpserts(), batch.chestDeletes());
                }
                if (!batch.playerRows().isEmpty()) {
                    backend.flushPlayers(batch.playerRows());
                }
            } catch (RuntimeException e) {
                // Put the keys back so the next autosave retries them with their then-current state.
                synchronized (lock) {
                    state.restoreDirty(batch);
                }
                throw e;
            }
            return batch.chestUpserts().size() + batch.chestDeletes().size() + batch.playerRows().size();
        }
    }

    /**
     * Writes one owner's dirty rows back to SQL, then evicts them if they ended up clean and are
     * still offline — the quit path, so a leaver's changes reach the database within seconds and
     * their memory is released. A rejoin before this runs simply re-pins the owner: the eviction
     * check fails and the still-warm rows are reused. On flush failure the rows are re-marked dirty
     * (next autosave retries) and the owner stays resident; the exception propagates to the caller.
     */
    void flushOwner(UUID owner) {
        flushInternal(owner);
        // Hold flushLock across the cleanliness check + eviction for the same reason as evictIdle:
        // never decide an owner is clean (and drop their rows) while some other flush's JDBC write is
        // in flight with that owner's keys transiently removed from the dirty sets. Ordering is
        // flushLock -> lock, matching flushInternal; flushInternal above has already released it.
        synchronized (flushLock) {
            synchronized (lock) {
                if (!pinned.contains(owner) && state.isClean(owner)) {
                    state.dropOwner(owner);
                    resident.remove(owner);
                }
            }
        }
    }

    /**
     * Evicts every owner that is offline (unpinned) and fully flushed (clean). Called after each
     * successful autosave flush, so owners loaded for one-off reasons — an admin command on an
     * offline player, an expiry sweep, a quit whose write-back raced a pending save — are released
     * within one autosave interval. Dirty owners are never evicted (their data exists only here).
     *
     * @return the number of owners evicted
     */
    int evictIdle() {
        // flushLock is held (outer) so eviction can never run during a flush's in-flight JDBC window.
        // A flush removes an owner's keys from the dirty sets under lock *before* its backend write,
        // then re-adds them only if the write fails. Between that removal and a failure re-add the
        // owner looks clean while its rows are not yet persisted; evicting there would drop rows a
        // failed flush re-marks dirty with no backing row in memory, so the next flush would classify
        // them as deletes and wipe the owner from the database. Holding flushLock serializes against
        // that whole window. Ordering is flushLock -> lock, matching flushInternal (no deadlock).
        synchronized (flushLock) {
            synchronized (lock) {
                Set<UUID> dirtyOwners = state.dirtyOwners();
                int evicted = 0;
                Iterator<UUID> it = resident.iterator();
                while (it.hasNext()) {
                    UUID owner = it.next();
                    if (pinned.contains(owner) || dirtyOwners.contains(owner)) {
                        continue;
                    }
                    state.dropOwner(owner);
                    it.remove();
                    evicted++;
                }
                return evicted;
            }
        }
    }
}
