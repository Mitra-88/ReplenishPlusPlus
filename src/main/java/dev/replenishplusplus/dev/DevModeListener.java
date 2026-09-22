package dev.replenishplusplus.dev;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
}
