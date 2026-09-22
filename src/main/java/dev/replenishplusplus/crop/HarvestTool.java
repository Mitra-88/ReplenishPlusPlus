package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

public enum HarvestTool {
    HOE("Hoe", () -> Tag.ITEMS_HOES),
    AXE("Axe", () -> Tag.ITEMS_AXES);

    private final String displayName;
    private final Supplier<Tag<Material>> tagSource;
    private Set<Material> matchedMaterials;

    HarvestTool(String displayName, Supplier<Tag<Material>> tagSource) {
        this.displayName = displayName;
        this.tagSource = tagSource;
    }

    public boolean matches(Material material) {
        Set<Material> materials = matchedMaterials;
        if (materials == null) {
            materials = EnumSet.noneOf(Material.class);
            materials.addAll(tagSource.get().getValues());
            matchedMaterials = materials;
        }
        return materials.contains(material);
    }

    public String displayName() {
        return displayName;
    }
}
