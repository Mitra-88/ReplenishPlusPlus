package dev.replenishplusplus.pad;

import dev.replenishplusplus.config.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class PadListener implements Listener {

    private static final long WARP_COOLDOWN_MS = 2000L;

    private final TeleportPadManager pads;
    private final Map<UUID, Long> warpCooldowns = new HashMap<>();
    private final Map<BlockPlaceEvent, TeleportPad> pendingPlacements = new WeakHashMap<>();

    public PadListener(TeleportPadManager pads) {
        this.pads = pads;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (to.getBlock().getType() != Material.END_PORTAL_FRAME) return;
        TeleportPad pad = pads.at(to.getBlock());
        if (pad == null || pad.destination() == null) return;
        Player player = event.getPlayer();
        if (!pad.owner().equals(player.getUniqueId())) return;
        if (cooldownActive(player)) return;
        warp(player, pad);
    }

    private boolean cooldownActive(Player player) {
        Long last = warpCooldowns.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < WARP_COOLDOWN_MS;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warpCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        pads.syncChunkDisplays(event.getChunk());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!pads.isPadItem(event.getItemInHand())) return;
        Player player = event.getPlayer();
        if (pads.countOwned(player.getUniqueId()) >= TeleportPadManager.MAX_PADS) {
            event.setCancelled(true);
            send(player, "pad.limit", Placeholder.unparsed("limit", String.valueOf(TeleportPadManager.MAX_PADS)));
            return;
        }
        pendingPlacements.put(event, new TeleportPad(TeleportPadManager.keyOf(event.getBlockPlaced()),
                player.getUniqueId(), pads.firstFreeIcon(player.getUniqueId()), null, null, PadDirection.LAST));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlaceCommit(BlockPlaceEvent event) {
        TeleportPad pad = pendingPlacements.remove(event);
        if (pad == null || event.isCancelled()) return;
        pads.place(pad);
        send(event.getPlayer(), "pad.placed");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.END_PORTAL_FRAME) return;
        TeleportPad pad = pads.at(event.getBlock());
        if (pad == null) return;
        event.setCancelled(true);
        if (!pad.owner().equals(event.getPlayer().getUniqueId())) {
            send(event.getPlayer(), "pad.not-yours");
            return;
        }
        pads.pickUp(event.getPlayer(), pad);
        send(event.getPlayer(), "pad.picked-up");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.END_PORTAL_FRAME) return;
        TeleportPad pad = pads.at(block);
        if (pad == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!pad.owner().equals(player.getUniqueId())) {
            send(player, "pad.not-yours");
            return;
        }
        if (player.isSneaking()) {
            PadMenus.openConfig(player, pad, pads);
            return;
        }
        if (pad.destination() == null) {
            send(player, "pad.no-destination");
            PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), 0);
            return;
        }
        warp(player, pad);
    }

    private void warp(Player player, TeleportPad pad) {
        if (cooldownActive(player)) return;
        TeleportPad destination = pads.get(pad.destination());
        if (destination == null) {
            pads.clearDestination(pad.key());
            send(player, "pad.destination-gone");
            PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), 0);
            return;
        }
        World world = Bukkit.getWorld(destination.key().world());
        if (world == null) {
            send(player, "pad.destination-gone");
            return;
        }
        Block block = world.getBlockAt(destination.key().x(), destination.key().y(), destination.key().z());
        if (block.getType() != Material.END_PORTAL_FRAME) {
            pads.clearDestination(pad.key());
            send(player, "pad.destination-gone");
            PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), 0);
            return;
        }
        Location target = block.getLocation().add(0.5, 1.0, 0.5);
        Location current = player.getLocation();
        if (destination.arrival().keepsPlayerFacing()) {
            if (Math.abs(current.getYaw() - 90.0f) > 0.01f || Math.abs(current.getPitch() - 5.4f) > 0.01f) {
                target.setYaw(90.0f);
                target.setPitch(5.4f);
            }
        } else {
            target.setYaw(destination.arrival().yaw());
            target.setPitch(current.getPitch());
        }
        player.teleport(target);
        warpCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.sendActionBar(Messages.prefixed("pad.warped", Placeholder.unparsed("pad", label(destination))));
    }

    static String label(TeleportPad pad) {
        return pad.displayOr(PadIcons.byId(pad.iconId()).title() + " Pad");
    }

    static void send(Player player, String key, TagResolver... resolvers) {
        player.sendMessage(Messages.prefixed(key, resolvers));
    }
}
