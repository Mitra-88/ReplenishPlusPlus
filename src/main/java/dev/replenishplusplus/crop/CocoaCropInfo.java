package dev.replenishplusplus.crop;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.List;

public record CocoaCropInfo(
        int maximumAge,
        BlockData[][] ageFacingStates) implements CropInfo {

    public static final List<BlockFace> FACES = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    public static BlockFace face(int ordinal) {
        return FACES.get(ordinal);
    }

    public static int faceOrdinal(BlockFace face) {
        return face == null ? 0 : FACES.indexOf(face);
    }

    @Override
    public boolean plantsOn(Material neighbor) {
        return Tag.JUNGLE_LOGS.isTagged(neighbor);
    }

    @Override
    public List<BlockFace> validNeighborFaces() {
        return FACES;
    }

    public BlockData stateFor(int age, int faceOrdinal) {
        return ageFacingStates[age][faceOrdinal];
    }
}
