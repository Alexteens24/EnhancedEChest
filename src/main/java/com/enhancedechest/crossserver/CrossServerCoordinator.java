package com.enhancedechest.crossserver;

import java.util.UUID;

/**
 * Coordination contract that makes the lazy write-back cache safe to run on several servers sharing
 * one database ({@code cross-server.enabled}). The rule the storage layer enforces with it:
 * <b>an owner may be resident in this server's cache only while this server holds that owner's
 * distributed lock</b> — acquired before the rows are read from SQL, released only after the owner's
 * dirty rows have been flushed back and evicted. Since eviction takes only clean owners, whoever
 * acquires the lock next always reads current SQL rows, so two servers can never hold (and later
 * flush) conflicting authoritative copies of the same player.
 *
 * <p>Release is split in two so the cache can keep its lock discipline: {@link #beginRelease} is the
 * cheap local intent, called <i>inside</i> the cache's state lock in the same critical section that
 * drops the owner's rows (so a concurrent load's {@link #isHeld} re-check under that lock can never
 * see stale ownership); {@link #finishRelease} does the actual network release and is called after
 * the lock is dropped (never network I/O inside the cache lock).
 *
 * <p>The {@link #NOOP} instance is the single-server mode: every acquire succeeds instantly and
 * {@link #isHeld} is always true, which collapses the whole protocol to the pre-cross-server
 * behavior. Call sites never null-check or branch (same pattern as {@code Telemetry}).
 */
public interface CrossServerCoordinator {

    /** Single-server no-op: acquires always succeed, nothing is ever released. */
    CrossServerCoordinator NOOP = new CrossServerCoordinator() {
        @Override public void acquireOwner(UUID owner) {}
        @Override public boolean isHeld(UUID owner) { return true; }
        @Override public boolean isHeldElsewhere(UUID owner) { return false; }
        @Override public void beginRelease(UUID owner) {}
        @Override public void finishRelease(UUID owner) {}
    };

    /**
     * Blocks until this server holds {@code owner}'s lock. Called on the async storage executor by
     * the cache's load-on-miss path, never on a tick thread. If another server holds the lock, that
     * server is asked (pub/sub) to flush + hand the owner over, which it does as soon as the player
     * has fully quit there; on a crashed holder the lock's TTL expires instead.
     *
     * @throws CrossServerLockException if the lock cannot be obtained within the acquire timeout —
     *         e.g. the player is still online on the other server. The caller treats this like a
     *         failed SQL read: the operation fails loudly instead of proceeding on stale data.
     */
    void acquireOwner(UUID owner);

    /**
     * Whether this server currently holds {@code owner}'s lock (cheap, local — no network I/O).
     * The cache re-checks this under its state lock right before flipping an owner resident, closing
     * the race where an eviction releases the lock between a load's acquire and its apply.
     */
    boolean isHeld(UUID owner);

    /**
     * Whether {@code owner}'s lock is currently held by a <i>different</i> server (network read).
     * Used by the expiry sweep to skip candidates whose owner is online on another server — that
     * server's own sweep handles them — instead of blocking a full acquire timeout on each.
     */
    boolean isHeldElsewhere(UUID owner);

    /**
     * Marks {@code owner}'s lock as no longer held locally. Must be called inside the cache's state
     * lock, in the same critical section that evicts the owner's rows. Pure local bookkeeping.
     */
    void beginRelease(UUID owner);

    /**
     * Completes a {@link #beginRelease}: deletes the lock key and notifies waiting servers. Called
     * outside the cache's state lock (network I/O). Failures are logged, never thrown — a lost
     * release degrades to the lock's TTL expiry.
     */
    void finishRelease(UUID owner);
}
