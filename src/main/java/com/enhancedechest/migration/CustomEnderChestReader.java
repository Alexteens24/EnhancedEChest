package com.enhancedechest.migration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads a CustomEnderChest (maiminhdung) installation's {@code storage.type: yml} player files and
 * decodes them into Bukkit {@link ItemStack} arrays, ready to be re-encoded into EnhancedEchest's own
 * storage.
 *
 * <p>Unlike AxVaults/PlayerVaultsX, CustomEnderChest gives each player a single ender chest (sized by
 * their highest-ranked permission), not several numbered vaults — matching EnhancedEchest's own base
 * chest #1. Each player's chest lives in a flat file {@code plugins/CustomEnderChest/playerdata/<uuid>.yml}
 * with the shape:
 * <pre>
 * player-name: Steve
 * enderchest-size: 27
 * enderchest-inventory:
 * - DataVersion: 4671
 *   id: minecraft:diamond_sword
 *   count: 1
 *   schema_version: 1
 * - null
 * - ...
 * </pre>
 * {@code enderchest-inventory} has exactly {@code enderchest-size} entries, each either {@code null}
 * (empty slot) or a plain map in Bukkit's standard modern item-serialization shape (the same
 * {@code id}/{@code count}/{@code components}/{@code DataVersion} keys {@link ItemStack#serialize()}
 * itself produces since 1.20.5, plus CustomEnderChest's own {@code schema_version} marker, which
 * {@link ItemStack#deserialize(Map)} simply ignores). Since the list is nested inside
 * {@code enderchest-inventory} rather than a direct configuration-section key, Bukkit's YAML loader
 * hands each map back as a plain {@code Map<String, Object>} (not wrapped in a
 * {@code ConfigurationSection}), so it can be passed to {@link ItemStack#deserialize(Map)} as-is.
 *
 * <p>CustomEnderChest's {@code h2} (default) and {@code mysql} backends are not read by this class —
 * an admin must set {@code storage.type: yml} in {@code CustomEnderChest/config.yml} and restart the
 * source server first (the same one-time switch AxVaults migration already asks for).
 *
 * <p>Stateless and holds no resources. YAML parsing and item deserialization are read-only against the
 * frozen server registries and safe off the main thread (the migration runs on the shared DB executor).
 */
public final class CustomEnderChestReader {

    /** One player's CustomEnderChest data as decoded for migration. */
    public record PlayerData(UUID owner, ItemStack[] items) {}

    /** The {@code playerdata} folder under {@code plugins/CustomEnderChest} holding one YAML file per player. */
    private final Path playerDataFolder;
    private final Logger log;

    public CustomEnderChestReader(Path customEnderChestFolder, Logger log) {
        this.playerDataFolder = customEnderChestFolder.resolve("playerdata");
        this.log = log;
    }

    /** True if CustomEnderChest YAML player data is present (the {@code playerdata} folder holds at least one file). */
    public boolean sourceAvailable() {
        if (!Files.isDirectory(playerDataFolder)) {
            return false;
        }
        try (Stream<Path> files = Files.list(playerDataFolder)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".yml"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the owner UUIDs of every readable player file in {@code playerdata}, skipping bad names. */
    public List<UUID> listOwners() throws Exception {
        List<UUID> owners = new ArrayList<>();
        if (!Files.isDirectory(playerDataFolder)) {
            return owners;
        }
        try (Stream<Path> files = Files.list(playerDataFolder)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml")).forEach(p -> {
                String name = p.getFileName().toString();
                String stem = name.substring(0, name.length() - ".yml".length());
                try {
                    owners.add(UUID.fromString(stem));
                } catch (Exception e) {
                    log.warn("[CustomEnderChest] Skipping file with non-UUID name '{}'", name);
                }
            });
        }
        return owners;
    }

    /** Reads and decodes a single player's ender chest, or null if they have no file. */
    public @Nullable PlayerData read(UUID owner) throws Exception {
        File file = playerDataFolder.resolve(owner + ".yml").toFile();
        if (!file.isFile()) {
            return null;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<?> raw = yaml.getList("enderchest-inventory");
        if (raw == null) {
            return new PlayerData(owner, new ItemStack[0]);
        }

        ItemStack[] items = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object element = raw.get(i);
            if (element == null) {
                continue;
            }
            if (!(element instanceof Map)) {
                throw new IllegalArgumentException("Slot " + i + " of " + owner
                        + " is not an item map (" + element.getClass() + ")");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) element;
            items[i] = ItemStack.deserialize(map);
        }
        return new PlayerData(owner, items);
    }
}
