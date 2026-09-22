package dev.replenishplusplus.update;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotificationListener implements Listener {

    private final ReplenishPlusPlus plugin;

    public UpdateNotificationListener(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
        if (plugin.getUpdateChecker().isEnabled()) {
            plugin.getUpdateChecker().onCheckCompleted(this::notifyOnlineOps);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UpdateChecker uc = plugin.getUpdateChecker();
        if (!uc.isEnabled() || uc.isCheckPending() || uc.isCheckFailed()) return;
        if (!event.getPlayer().hasPermission("replenishplusplus.update")) return;
        notify(event.getPlayer(), uc);
    }

    private void notifyOnlineOps() {
        UpdateChecker uc = plugin.getUpdateChecker();
        if (!uc.isUpdateAvailable()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("replenishplusplus.update")) notify(player, uc);
        }
    }

    private void notify(Player player, UpdateChecker uc) {
        if (!uc.isUpdateAvailable()) return;
        player.sendMessage(Messages.prefixed("update.available",
                Placeholder.unparsed("current", uc.getCurrentVersion()),
                Placeholder.unparsed("latest", uc.getLatestVersion())));
        player.sendMessage(Messages.prefixedRaw("<gray>Download: " + uc.downloadLink()));
    }
}
