package dev.replenishplusplus.pad;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class PadMenuHolder implements InventoryHolder {

    public enum Type { CONFIG, DESTINATIONS, ICONS }

    private final Type type;
    private final BlockKey padKey;
    private final int page;
    private Inventory inventory;

    public PadMenuHolder(Type type, BlockKey padKey, int page) {
        this.type = type;
        this.padKey = padKey;
        this.page = page;
    }

    public Type type() {
        return type;
    }

    public BlockKey padKey() {
        return padKey;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
