package com.enhancedechest.command.admin;

import com.enhancedechest.EnhancedEchestPlugin;
import com.enhancedechest.lang.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /enhancedechest import} — opens the DB→DB import dialog so an admin can copy all EnhancedEchest
 * data from an old database backend into the plugin's active backend. Player-only (it shows a dialog);
 * the guards (no other players online, source ≠ active destination, empty destination) and the actual
 * copy live in {@link com.enhancedechest.service.ChestOpener#performImport} /
 * {@link com.enhancedechest.migration.DatabaseImportService}.
 */
public final class ImportCommand {

    private ImportCommand() {}

    public static int execute(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        EnhancedEchestPlugin plugin =
                (EnhancedEchestPlugin) Bukkit.getPluginManager().getPlugin("EnhancedEchest");
        if (plugin == null || !plugin.isEnabled()) {
            sender.sendMessage(Component.text("[EnhancedEchest] Plugin is not available."));
            return 0;
        }
        LanguageManager lang = plugin.getLanguageManager();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("command.not-player"));
            return 0;
        }
        plugin.getChestOpener().showImportDialog(player);
        return 1;
    }
}
