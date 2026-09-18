package dev.replenishplusplus.update;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotificationListener implements Listener {

    private final ReplenishPlusPlus plugin;

    public UpdateNotificationListener(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
        UpdateChecker uc = plugin.getUpdateChecker();
        if (uc != null && uc.isEnabled()) {
            uc.onCheckCompleted(this::notifyOnlineOps);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UpdateChecker uc = plugin.getUpdateChecker();
        if (uc == null || !uc.isEnabled() || uc.isCheckPending()) return;
        if (!event.getPlayer().hasPermission("replenishplusplus.update")) return;
        notify(event.getPlayer(), uc);
    }

    private void notifyOnlineOps() {
        UpdateChecker uc = plugin.getUpdateChecker();
        if (uc == null || !uc.isUpdateAvailable()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("replenishplusplus.update")) notify(player, uc);
        }
    }

    private void notify(Player player, UpdateChecker uc) {
        if (!uc.isUpdateAvailable()) return;
        player.sendMessage(Messages.MINI_MESSAGE.deserialize(Messages.prefixed(
                "<yellow>A new version is available! <dark_gray>(<white>v" + uc.getCurrentVersion() +
                        " <gray>➔ <yellow>v" + uc.getLatestVersion() + "<dark_gray>)")));
        player.sendMessage(Messages.MINI_MESSAGE.deserialize(Messages.prefixed(
                "<gray>Download: <aqua><click:open_url:'" + UpdateChecker.RELEASES_URL + "'>" +
                        "<hover:show_text:'<gray>Click to open release page'><u>github.com/Mitra-88/ReplenishPlusPlus</u></click>")));
    }
}
