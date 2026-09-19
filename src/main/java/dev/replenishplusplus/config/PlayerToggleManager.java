package dev.replenishplusplus.config;

import dev.replenishplusplus.ReplenishPlusPlus;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class PlayerToggleManager {

    private final NamespacedKey toggleKey;

    public PlayerToggleManager(ReplenishPlusPlus plugin) {
        this.toggleKey = new NamespacedKey(plugin, "enabled");
    }

    public boolean isDisabled(Player player) {
        Boolean toggle = player.getPersistentDataContainer().get(toggleKey, PersistentDataType.BOOLEAN);
        return toggle != null && !toggle;
    }

    public boolean toggle(Player player) {
        boolean nowEnabled = isDisabled(player);
        player.getPersistentDataContainer().set(toggleKey, PersistentDataType.BOOLEAN, nowEnabled);
        return nowEnabled;
    }
}
