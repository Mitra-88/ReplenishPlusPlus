package dev.replenishplusplus.util;

import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Collection;
import java.util.Map;

public final class DropPickupManager {

    private DropPickupManager() {}

    public static void giveOrDrop(Player player, Block block, Collection<ItemStack> drops, ConfigCache config) {
        PlayerInventory inventory = player.getInventory();
        boolean anyAdded = false;
        boolean anyDropped = false;
        Location dropLocation = null;

        for (ItemStack stack : drops) {
            if (stack == null || stack.getAmount() <= 0 || stack.getType().isAir()) continue;

            try {
                int originalAmount = stack.getAmount();
                Map<Integer, ItemStack> leftovers = inventory.addItem(stack);

                if (leftovers.isEmpty()) {
                    anyAdded = true;
                } else {
                    int leftoverAmount = 0;
                    for (ItemStack leftover : leftovers.values()) {
                        if (leftover == null || leftover.getAmount() <= 0) continue;
                        leftoverAmount += leftover.getAmount();
                        dropLocation = dropAt(block, dropLocation, leftover);
                        anyDropped = true;
                    }
                    if (leftoverAmount < originalAmount) anyAdded = true;
                }
            } catch (Exception e) {
                dropLocation = dropAt(block, dropLocation, stack);
                anyDropped = true;
            }
        }

        if (anyDropped) {
            config.messageStyle().send(player, Messages.prefixed("harvest.inventory-full"));
            config.inventoryFullSound().play(player);
        }
        if (anyAdded) {
            config.pickupSound().play(player);
        }
    }

    private static Location dropAt(Block block, Location dropLocation, ItemStack stack) {
        if (dropLocation == null) dropLocation = block.getLocation().toCenterLocation();
        dropLocation.getWorld().dropItemNaturally(dropLocation, stack);
        return dropLocation;
    }
}
