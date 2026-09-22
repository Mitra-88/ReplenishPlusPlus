package dev.replenishplusplus.pad;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PadMenuListener implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int MAX_NAME_LENGTH = 32;

    private final ReplenishPlusPlus plugin;
    private final TeleportPadManager pads;
    private final Map<UUID, String> pendingNames = new ConcurrentHashMap<>();

    public PadMenuListener(ReplenishPlusPlus plugin, TeleportPadManager pads) {
        this.plugin = plugin;
        this.pads = pads;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PadMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked.getHolder() != holder) return;

        switch (holder.type()) {
            case CONFIG -> handleConfig(player, holder, event.getSlot());
            case DESTINATIONS -> handleDestinations(player, holder, event.getSlot());
            case ICONS -> handleIcons(player, holder, event.getSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PadMenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleConfig(Player player, PadMenuHolder holder, int slot) {
        TeleportPad pad = pads.get(holder.padKey());
        if (pad == null || !pad.owner().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        switch (slot) {
            case 10 -> PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), 0);
            case 12 -> {
                TeleportPad updated = new TeleportPad(pad.key(), pad.owner(), pad.iconId(), pad.name(), pad.destination(), pad.arrival().next());
                pads.update(updated);
                PadMenus.openConfig(player, updated, pads);
            }
            case 14 -> PadMenus.openIcons(player, pad, 0);
            case 16 -> {
                pendingNames.put(player.getUniqueId(), pad.key().serialize());
                player.closeInventory();
                PadListener.send(player, "pad.name-prompt");
            }
            case 22 -> {
                player.closeInventory();
                pads.pickUp(player, pad);
                PadListener.send(player, "pad.picked-up");
            }
            default -> {}
        }
    }

    private void handleDestinations(Player player, PadMenuHolder holder, int slot) {
        TeleportPad pad = pads.get(holder.padKey());
        if (pad == null || !pad.owner().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        int page = holder.page();
        if (slot == 45 && page > 0) {
            PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), page - 1);
            return;
        }
        if (slot == 53 && (page + 1) * PAGE_SIZE < pads.countOwned(player.getUniqueId()) - 1) {
            PadMenus.openDestinations(player, pad, pads.ownedBy(player.getUniqueId()), page + 1);
            return;
        }
        if (slot >= PAGE_SIZE) return;
        List<TeleportPad> others = pads.ownedBy(player.getUniqueId());
        others.removeIf(other -> other.key().equals(pad.key()));
        int index = page * PAGE_SIZE + slot;
        if (index >= others.size()) return;
        TeleportPad destination = others.get(index);
        pads.update(new TeleportPad(pad.key(), pad.owner(), pad.iconId(), pad.name(), destination.key(), pad.arrival()));
        player.closeInventory();
        PadListener.send(player, "pad.destination-set", Placeholder.unparsed("pad", PadListener.label(destination)));
    }

    private void handleIcons(Player player, PadMenuHolder holder, int slot) {
        TeleportPad pad = pads.get(holder.padKey());
        if (pad == null || !pad.owner().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        int page = holder.page();
        if (slot == 45 && page > 0) {
            PadMenus.openIcons(player, pad, page - 1);
            return;
        }
        if (slot == 53 && (page + 1) * PAGE_SIZE < PadIcons.all().size()) {
            PadMenus.openIcons(player, pad, page + 1);
            return;
        }
        if (slot >= PAGE_SIZE) return;
        int index = page * PAGE_SIZE + slot;
        if (index >= PadIcons.all().size()) return;
        PadIcons.Icon icon = PadIcons.all().get(index);
        pads.update(new TeleportPad(pad.key(), pad.owner(), icon.id(), pad.name(), pad.destination(), pad.arrival()));
        PadMenus.openConfig(player, pads.get(pad.key()), pads);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String padKey = pendingNames.remove(event.getPlayer().getUniqueId());
        if (padKey == null) return;
        event.setCancelled(true);
        String name = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> applyName(event.getPlayer(), padKey, name));
    }

    private void applyName(Player player, String padKey, String name) {
        TeleportPad pad = pads.get(BlockKey.parse(padKey));
        if (pad == null) return;
        String clean = name.isEmpty() || "cancel".equalsIgnoreCase(name)
                ? null
                : name.substring(0, Math.min(MAX_NAME_LENGTH, name.length()));
        pads.update(new TeleportPad(pad.key(), pad.owner(), pad.iconId(), clean, pad.destination(), pad.arrival()));
        if (clean == null) {
            player.sendMessage(Messages.prefixed("pad.name-cleared"));
        } else {
            player.sendMessage(Messages.prefixed("pad.renamed", Placeholder.unparsed("name", clean)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingNames.remove(event.getPlayer().getUniqueId());
    }
}
