package dev.replenishplusplus.config;

import dev.replenishplusplus.ReplenishPlusPlus;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerToggleManager {

    private final NamespacedKey toggleKey;
    private final Map<UUID, Boolean> enabledMirror = new HashMap<>();

    public PlayerToggleManager(ReplenishPlusPlus plugin) {
        this.toggleKey = new NamespacedKey(plugin, "enabled");
    }

    public boolean isDisabled(Player player) {
        Boolean cached = enabledMirror.get(player.getUniqueId());
        if (cached == null) {
            Boolean stored = player.getPersistentDataContainer().get(toggleKey, PersistentDataType.BOOLEAN);
            cached = stored == null || stored;
            enabledMirror.put(player.getUniqueId(), cached);
        }
        return !cached;
    }

    public boolean toggle(Player player) {
        boolean nowEnabled = isDisabled(player);
        player.getPersistentDataContainer().set(toggleKey, PersistentDataType.BOOLEAN, nowEnabled);
        enabledMirror.put(player.getUniqueId(), nowEnabled);
        return nowEnabled;
    }

    public void evict(UUID playerId) {
        enabledMirror.remove(playerId);
    }
}
