package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.Tag;

public enum HarvestTool {
    HOE("Hoe"),
    AXE("Axe");

    private final String displayName;

    HarvestTool(String displayName) {
        this.displayName = displayName;
    }

    public boolean matches(Material material) {
        return switch (this) {
            case HOE -> Tag.ITEMS_HOES.isTagged(material);
            case AXE -> Tag.ITEMS_AXES.isTagged(material);
        };
    }

    public String displayName() {
        return displayName;
    }
}
