package com.enhancedechest.update;

import com.enhancedechest.lang.LanguageManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

@RequiredArgsConstructor
public final class UpdateNotifyListener implements Listener {

    private final JavaPlugin plugin;
    private final UpdateChecker checker;
    private final LanguageManager lang;

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!checker.isUpdateAvailable()) return;
        Player player = event.getPlayer();
        if (!player.isOp()) return;

        // Slight delay so the player is fully loaded before receiving messages.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(lang.get("update.available",
                    "current", checker.getCurrentVersion(),
                    "latest",  checker.getLatestVersion()));
            player.sendMessage(lang.get("update.download",
                    "url", UpdateChecker.MODRINTH_PAGE));
        }, 40L);
    }
}
