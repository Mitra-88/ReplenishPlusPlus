package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.Collection;

public sealed interface CropInfo permits SimpleCropInfo, CocoaCropInfo {

    int maximumAge();

    boolean plantsOn(Material neighbor);

    Collection<BlockFace> validNeighborFaces();
}
