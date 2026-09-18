package dev.replenishplusplus.crop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;

public final class AgeMetaRegistry {

    private final Map<Material, CropInfo> registry;

    public AgeMetaRegistry(Plugin plugin) {
        this.registry = new EnumMap<>(Material.class);
        for (CropType crop : CropType.values()) {
            try {
                register(crop);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.WARNING, "Age meta scan skipped for " + crop.material(), error);
            }
        }
    }

    private void register(CropType crop) {
        Material material = crop.material();
        BlockData base = Bukkit.createBlockData(material);
        if (!(base instanceof Ageable ageable)) return;

        int maxAge = ageable.getMaximumAge();
        CropInfo info = crop == CropType.COCOA ? buildCocoa(base, maxAge) : buildSimple(crop, base, maxAge);
        registry.put(material, info);
    }

    private SimpleCropInfo buildSimple(CropType crop, BlockData base, int maxAge) {
        BlockData[] states = new BlockData[maxAge + 1];
        for (int age = 0; age <= maxAge; age++) {
            BlockData data = base.clone();
            ((Ageable) data).setAge(age);
            states[age] = data;
        }
        Material anchor = crop == CropType.NETHER_WART ? Material.SOUL_SAND : Material.FARMLAND;
        return new SimpleCropInfo(maxAge, anchor, states);
    }

    private CocoaCropInfo buildCocoa(BlockData base, int maxAge) {
        BlockData[][] states = new BlockData[maxAge + 1][CocoaCropInfo.FACES.size()];
        for (int age = 0; age <= maxAge; age++) {
            for (int face = 0; face < CocoaCropInfo.FACES.size(); face++) {
                BlockData data = base.clone();
                ((Ageable) data).setAge(age);
                ((Directional) data).setFacing(CocoaCropInfo.FACES.get(face));
                states[age][face] = data;
            }
        }
        return new CocoaCropInfo(maxAge, states);
    }

    public CropInfo get(Material material) {
        return registry.get(material);
    }
}
