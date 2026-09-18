package dev.replenishplusplus.listener;

import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.util.SeedIndex;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SeedCacheInvalidationListener implements Listener {

    private static final long INVALIDATION_COOLDOWN_MS = 50L;

    private static final Set<Material> SEED_TYPES = Arrays.stream(CropType.values())
            .map(CropType::seed)
            .collect(Collectors.toUnmodifiableSet());

    private final Map<UUID, Long> lastInvalidation = new HashMap<>();

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastInvalidation.remove(event.getPlayer().getUniqueId());
        SeedIndex.invalidate(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) invalidateWithCooldown(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isRelevantSeed(event.getOldCursor()) || isRelevantSeed(event.getCursor())) invalidateWithCooldown(player);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isRelevantSeed(event.getMainHandItem()) || isRelevantSeed(event.getOffHandItem())) invalidateWithCooldown(event.getPlayer());
    }

    @EventHandler
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isRelevantSeed(event.getItem().getItemStack())) return;
        invalidateWithCooldown(player);
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (isRelevantSeed(event.getItemDrop().getItemStack())) invalidateWithCooldown(event.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && isRelevantSeed(event.getItem())) invalidateWithCooldown(event.getPlayer());
    }

    private boolean isRelevantSeed(ItemStack item) {
        return item != null && SEED_TYPES.contains(item.getType());
    }

    private void invalidateWithCooldown(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long last = lastInvalidation.get(uuid);
        if (last == null || now - last >= INVALIDATION_COOLDOWN_MS) {
            lastInvalidation.put(uuid, now);
            SeedIndex.invalidate(player);
        }
    }
}
