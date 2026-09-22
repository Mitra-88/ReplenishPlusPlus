package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.Collection;

public sealed interface CropInfo permits SimpleCropInfo, CocoaCropInfo {

    int maximumAge();

    boolean plantsOn(Material neighbor);

    Collection<BlockFace> validNeighborFaces();

    default boolean lacksAnchorAt(World world, int x, int y, int z) {
        for (BlockFace face : validNeighborFaces()) {
            if (plantsOn(world.getBlockAt(x + face.getModX(), y + face.getModY(), z + face.getModZ()).getType())) return false;
        }
        return true;
    }
}
