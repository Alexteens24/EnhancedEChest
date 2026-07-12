package com.enhancedechest.migration;

import com.enhancedechest.migration.CustomEnderChestReader.PlayerData;
import com.enhancedechest.model.EnderChestData;
import com.enhancedechest.serialization.ContainerCodec;
import com.enhancedechest.storage.EnderChestStorage;
import com.enhancedechest.telemetry.Telemetry;
import lombok.RequiredArgsConstructor;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Migrates ender chests from a CustomEnderChest (maiminhdung) installation into EnhancedEchest's
 * storage.
 *
 * <p>Unlike AxVaults/PlayerVaultsX, CustomEnderChest gives each player a single ender chest, so it is
 * imported into EnhancedEchest's own base chest #1 — never into a different index. Chest #1 is only
 * written when it does not already hold contents, which makes the migration safe to re-run and
 * prevents it from ever overwriting a chest the player has already used.
 *
 * <p>All work here is synchronous; callers dispatch it onto the shared DB executor. The chest items
 * are already decoded by {@link CustomEnderChestReader}; this class only resizes/encodes/writes into
 * storage.
 */
@RequiredArgsConstructor
public final class CustomEnderChestMigrationService {

    private final EnderChestStorage storage;
    private final ContainerCodec codec;
    private final Logger logger;
    private final Telemetry telemetry;
    /** The server {@code plugins/} directory; the CustomEnderChest data lives in {@code plugins/CustomEnderChest}. */
    private final Path pluginsFolder;

    /** Outcome of a migration run. */
    public record Result(int playersMigrated, int playersSkipped, int playersFailed) {}

    /** True if CustomEnderChest YAML player data is present to migrate from. */
    public boolean sourceAvailable() {
        return reader().sourceAvailable();
    }

    private CustomEnderChestReader reader() {
        return new CustomEnderChestReader(pluginsFolder.resolve("CustomEnderChest"), logger);
    }

    /** Migrates every player found in the CustomEnderChest playerdata folder. */
    public Result migrateAll() throws Exception {
        CustomEnderChestReader reader = reader();
        List<UUID> owners = reader.listOwners();
        logger.info("[CustomEnderChest] Found {} player file(s)", owners.size());

        int migrated = 0, skipped = 0, failed = 0;
        for (UUID owner : owners) {
            try {
                if (migrateOne(reader, owner)) migrated++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                logger.error("[CustomEnderChest] Failed migrating {}", owner, e);
                telemetry.error(e, "migrate.customenderchest.player");
            }
        }
        return new Result(migrated, skipped, failed);
    }

    /** Migrates a single player by UUID. */
    public Result migratePlayer(UUID owner) throws Exception {
        CustomEnderChestReader reader = reader();
        if (!Files.exists(pluginsFolder.resolve("CustomEnderChest").resolve("playerdata").resolve(owner + ".yml"))) {
            return new Result(0, 0, 0);
        }
        boolean ok = migrateOne(reader, owner);
        return new Result(ok ? 1 : 0, ok ? 0 : 1, 0);
    }

    /**
     * Imports one player's ender chest into chest #1. Returns {@code true} if it was written,
     * {@code false} if chest #1 already held contents (skipped, left untouched).
     */
    private boolean migrateOne(CustomEnderChestReader reader, UUID owner) throws Exception {
        PlayerData data = reader.read(owner);
        if (data == null) {
            return false;
        }

        EnderChestData existing = storage.loadChest(owner, 1);
        if (existing != null && existing.containerData() != null) {
            // Chest #1 already holds contents — never clobber it.
            return false;
        }

        int needed = fitSize(data.items().length);
        int size;
        if (existing == null) {
            storage.ensureChest(owner, 1, needed);
            size = needed;
        } else {
            size = Math.max(existing.size(), needed);
            if (size != existing.size()) {
                storage.resizeChest(owner, 1, size);
            }
        }

        ItemStack[] slots = new ItemStack[size];
        int copy = Math.min(data.items().length, size);
        System.arraycopy(data.items(), 0, slots, 0, copy);

        byte[] encoded;
        try {
            encoded = codec.encode(slots);
        } catch (Exception e) {
            logger.error("[CustomEnderChest] Encode failed for {} — skipping", owner, e);
            telemetry.error(e, "migrate.customenderchest.encode");
            return false;
        }
        storage.saveChest(owner, 1, encoded);

        logger.info("[CustomEnderChest] Imported ender chest ({} slots) for {}", size, owner);
        return true;
    }

    /** Rounds a slot count up to a valid chest size: a multiple of 9, clamped to 9..54. */
    private static int fitSize(int slots) {
        int rounded = ((Math.max(slots, 1) + ContainerCodec.SLOT_STEP - 1) / ContainerCodec.SLOT_STEP)
                * ContainerCodec.SLOT_STEP;
        return Math.clamp(rounded, ContainerCodec.SLOT_STEP, ContainerCodec.MAX_SIZE);
    }
}
