package dev.replenishplusplus.dev;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerCommandEvent;

public final class DevModeListener implements Listener {

    private final DevModeManager devMode;

    public DevModeListener(DevModeManager devMode) {
        this.devMode = devMode;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        devMode.onCropPlaced(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!devMode.noIceActive()) return;
        Material formed = event.getNewState().getType();
        if (formed == Material.ICE || formed == Material.FROSTED_ICE) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTrample(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.FARMLAND) return;
        if (!devMode.isDevTrampler(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent event) {
        if (!devMode.noTrampleActive()) return;
        if (event.getBlock().getType() != Material.FARMLAND) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        devMode.onSparkCommand(event.getMessage(), event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onConsoleCommand(ServerCommandEvent event) {
        devMode.onSparkCommand(event.getCommand(), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        devMode.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        devMode.onRespawn(event.getPlayer());
    }
}
