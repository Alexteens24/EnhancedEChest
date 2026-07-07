package com.enhancedechest.listener;

import com.enhancedechest.service.ChestOpener;
import com.enhancedechest.service.PlayerSettingsCache;
import com.enhancedechest.storage.AutosaveService;
import com.enhancedechest.storage.CachedStorage;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.UUID;

/**
 * Drives the lifecycle of per-player transient state around join/quit:
 * <ul>
 *   <li><b>Join:</b> pin the player in the lazy storage cache (never evicted while online), then
 *       preload their settings — the preload's {@code loadSettings} call is also what materializes
 *       the player's chest rows into the cache, so it doubles as the join prefetch and their first
 *       chest open touches no database.</li>
 *   <li><b>Quit:</b> evict the settings cache entry and the sort-cooldown timestamp, unpin the
 *       storage cache entry, and schedule the delayed write-back + eviction of their rows
 *       ({@link AutosaveService#flushQuitterLater}) — so a leaver's changes reach the database within
 *       seconds and their memory is freed. A rejoin before that runs simply re-pins them; the
 *       eviction then declines and the still-warm rows are reused.</li>
 * </ul>
 *
 * <p>These handlers pair up exactly, so every per-player map stays bounded by the online-player count
 * and never leaks. (The join-then-immediate-quit race is handled inside
 * {@link PlayerSettingsCache#preloadSettings} for the settings cache, and by the clean-only eviction
 * inside {@code CachedStorage} for the storage cache.) Separate from {@link JoinMigrationListener},
 * which early-returns when migration is off and so must not be relied on to run the preload.
 */
@RequiredArgsConstructor
public final class PlayerSettingsListener implements Listener {

    private final PlayerSettingsCache settings;
    private final ChestOpener chestOpener;
    private final CachedStorage storage;
    private final AutosaveService autosave;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        storage.pin(uuid);
        settings.preloadSettings(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        settings.evictSettings(uuid);
        chestOpener.clearSortCooldown(uuid);
        storage.unpin(uuid);
        autosave.flushQuitterLater(uuid);
    }
}
