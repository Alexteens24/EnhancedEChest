package com.enhancedechest.crossserver;

/**
 * Thrown by {@link CrossServerCoordinator#acquireOwner} when an owner's lock could not be obtained
 * within the acquire timeout — typically because the player is still online on another server, or
 * that server is still writing their data back. Propagates through the storage layer exactly like a
 * failed SQL read: the operation fails loudly (the player sees the generic load-failed message, an
 * admin command errors) instead of proceeding on stale data.
 */
public final class CrossServerLockException extends RuntimeException {

    public CrossServerLockException(String message) {
        super(message);
    }
}
