package dev.replenishplusplus.crop;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public enum CropType {
    WHEAT       (Material.WHEAT,        Material.WHEAT_SEEDS,    HarvestTool.HOE, "Wheat"),
    CARROTS     (Material.CARROTS,      Material.CARROT,         HarvestTool.HOE, "Carrots"),
    POTATOES    (Material.POTATOES,     Material.POTATO,         HarvestTool.HOE, "Potatoes"),
    NETHER_WART (Material.NETHER_WART,  Material.NETHER_WART,    HarvestTool.HOE, "Nether Wart"),
    COCOA       (Material.COCOA,        Material.COCOA_BEANS,    HarvestTool.AXE, "Cocoa"),
    BEETROOTS   (Material.BEETROOTS,    Material.BEETROOT_SEEDS, HarvestTool.HOE, "Beetroots");

    private static final Map<Material, CropType> BY_MATERIAL = new EnumMap<>(Material.class);

    static {
        for (CropType crop : values()) {
            BY_MATERIAL.put(crop.material, crop);
        }
    }

    private final Material material;
    private final Material seed;
    private final HarvestTool requiredTool;
    private final String displayName;

    CropType(Material material, Material seed, HarvestTool requiredTool, String displayName) {
        this.material = material;
        this.seed = seed;
        this.requiredTool = requiredTool;
        this.displayName = displayName;
    }

    public Material material()        { return material; }
    public Material seed()            { return seed; }
    public HarvestTool requiredTool() { return requiredTool; }
    public String displayName()       { return displayName; }

    public static CropType fromMaterial(Material material) {
        return material == null ? null : BY_MATERIAL.get(material);
    }

    public static CropType fromName(String configKey) {
        if (configKey == null) return null;
        try {
            return CropType.valueOf(configKey.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
