package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.List;

public record SimpleCropInfo(
        int maximumAge,
        Material anchor,
        BlockData[] ageStates) implements CropInfo {

    private static final List<BlockFace> VALID_NEIGHBOR_FACES = List.of(BlockFace.DOWN);

    @Override
    public boolean plantsOn(Material neighbor) {
        return neighbor == anchor;
    }

    @Override
    public List<BlockFace> validNeighborFaces() {
        return VALID_NEIGHBOR_FACES;
    }

    public BlockData stateFor(int age) {
        return ageStates[age];
    }
}
