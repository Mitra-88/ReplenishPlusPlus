package dev.replenishplusplus.util;

import org.bukkit.World;
import org.bukkit.block.Block;

public final class LocationUtil {

    private LocationUtil() {}

    public static String describe(Block block) {
        return describe(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public static String describe(World world, int x, int y, int z) {
        return world.getName() + ":" + x + "," + y + "," + z;
    }
}
