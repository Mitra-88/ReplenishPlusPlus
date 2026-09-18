package dev.replenishplusplus.config;

import dev.replenishplusplus.ReplenishPlusPlus;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerToggleManager {

    private final ReplenishPlusPlus plugin;
    private final NamespacedKey toggleKey;
    private final File file;
    private FileConfiguration config;
    private final Map<UUID, Boolean> legacyToggles = new HashMap<>();
    private final Object configLock = new Object();

    public PlayerToggleManager(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
        this.toggleKey = new NamespacedKey(plugin, "enabled");
        this.file = new File(plugin.getDataFolder(), "players.yml");
        loadLegacy();
    }

    private void loadLegacy() {
        config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                legacyToggles.put(UUID.fromString(key), config.getBoolean(key));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public boolean isDisabled(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Boolean toggle = pdc.get(toggleKey, PersistentDataType.BOOLEAN);
        if (toggle != null) return !toggle;

        Boolean legacy = legacyToggles.get(player.getUniqueId());
        if (legacy != null) {
            pdc.set(toggleKey, PersistentDataType.BOOLEAN, legacy);
            return !legacy;
        }

        return false;
    }

    public boolean toggle(Player player) {
        boolean nowEnabled = isDisabled(player);
        player.getPersistentDataContainer().set(toggleKey, PersistentDataType.BOOLEAN, nowEnabled);
        legacyToggles.put(player.getUniqueId(), nowEnabled);
        saveLegacyAsync(player.getUniqueId(), nowEnabled);
        return nowEnabled;
    }

    private void saveLegacyAsync(UUID uuid, boolean enabled) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (configLock) {
                config.set(uuid.toString(), enabled);
                try {
                    config.save(file);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not save players.yml: " + e.getMessage());
                }
            }
        });
    }
}
