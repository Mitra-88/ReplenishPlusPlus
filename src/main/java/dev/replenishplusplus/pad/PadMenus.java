package dev.replenishplusplus.pad;

import dev.replenishplusplus.config.Messages;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PadMenus {

    private static final int PAGE_SIZE = 45;
    private static final String GOLD = "<gradient:#FFD700:#FF9D00>";
    private static final String AQUA = "<gradient:#55FFB4:#007FFF>";
    private static final String END = "</gradient>";

    private static final List<String> HIDDEN_COMPONENT_KEYS = List.of(
            "minecraft:enchantments",
            "minecraft:jukebox_playable",
            "minecraft:painting/variant",
            "minecraft:map_id",
            "minecraft:fireworks",
            "minecraft:attribute_modifiers",
            "minecraft:unbreakable",
            "minecraft:written_book_content",
            "minecraft:banner_patterns",
            "minecraft:trim",
            "minecraft:potion_contents",
            "minecraft:dyed_color",
            "minecraft:charged_projectiles"
    );

    private static Set<DataComponentType> hiddenComponents;

    private PadMenus() {}

    private static Set<DataComponentType> hiddenComponents() {
        if (hiddenComponents == null) {
            Registry<DataComponentType> registry = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.DATA_COMPONENT_TYPE);
            Set<DataComponentType> resolved = new HashSet<>();
            for (String key : HIDDEN_COMPONENT_KEYS) {
                NamespacedKey named = NamespacedKey.fromString(key);
                if (named == null) continue;
                DataComponentType type = registry.get(named);
                if (type != null) resolved.add(type);
            }
            hiddenComponents = resolved;
        }
        return hiddenComponents;
    }

    private static ItemStack finish(ItemStack item, ItemMeta meta) {
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hideTooltip(false)
                .addHiddenComponents(hiddenComponents().toArray(DataComponentType[]::new))
                .build());
        return item;
    }

    public static void openConfig(Player player, TeleportPad pad, TeleportPadManager pads) {
        PadMenuHolder holder = new PadMenuHolder(PadMenuHolder.Type.CONFIG, pad.key(), 0);
        Inventory menu = Bukkit.createInventory(holder, 27, title("Teleport Pad"));
        holder.inventory(menu);
        fill(menu);

        String destination;
        String destinationName = "";
        if (pad.destination() == null) {
            destination = "<gray>Not set yet";
        } else {
            TeleportPad target = pads.get(pad.destination());
            if (target == null) {
                destination = "<red>Missing pad";
            } else {
                destination = "<white><target>";
                destinationName = PadListener.label(target);
            }
        }
        PadIcons.Icon icon = PadIcons.byId(pad.iconId());
        menu.setItem(10, item(Material.ENDER_PEARL, GOLD + "<bold>Destination" + END,
                destination, "<dark_gray>Click to pick where this pad warps to",
                Placeholder.unparsed("target", destinationName)));
        menu.setItem(12, item(Material.COMPASS, GOLD + "<bold>Arrival Direction" + END,
                "<white>" + pad.arrival().title(), "<dark_gray>Click to cycle the 9 facings"));
        menu.setItem(14, item(icon.material(), GOLD + "<bold>Icon" + END,
                "<white>" + icon.title(), "<dark_gray>Click to change the pad's color"));
        String nameLore = pad.name() == null ? "<gray>None" : "<white><name>";
        menu.setItem(16, item(Material.NAME_TAG, GOLD + "<bold>Name" + END,
                nameLore, "<dark_gray>Click, then type the new name in chat",
                Placeholder.unparsed("name", pad.name() == null ? "" : pad.name())));
        menu.setItem(22, item(Material.BARRIER, "<red><bold>Pick Up",
                "<gray>Removes the pad and gives it back"));
        player.openInventory(menu);
    }

    public static void openDestinations(Player player, TeleportPad pad, List<TeleportPad> owned, int page) {
        List<TeleportPad> others = new ArrayList<>(owned);
        others.removeIf(other -> other.key().equals(pad.key()));
        int pages = Math.max(1, (others.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.clamp(page, 0, pages - 1);

        PadMenuHolder holder = new PadMenuHolder(PadMenuHolder.Type.DESTINATIONS, pad.key(), page);
        Inventory menu = Bukkit.createInventory(holder, 54, title("Pick a Destination"));
        holder.inventory(menu);

        int start = page * PAGE_SIZE;
        int end = Math.min(others.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            TeleportPad target = others.get(i);
            menu.setItem(i - start, selectorItem(target, target.key().equals(pad.destination())));
        }
        for (int i = end - start; i < PAGE_SIZE; i++) menu.setItem(i, filler());
        if (others.isEmpty()) {
            menu.setItem(22, item(Material.BARRIER, "<red><bold>No other pads",
                    "<gray>Place a second Teleport Pad first"));
        }
        navRow(menu, page, pages);
        player.openInventory(menu);
    }

    public static void openIcons(Player player, TeleportPad pad, int page) {
        List<PadIcons.Icon> icons = PadIcons.all();
        int pages = (icons.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.clamp(page, 0, pages - 1);

        PadMenuHolder holder = new PadMenuHolder(PadMenuHolder.Type.ICONS, pad.key(), page);
        Inventory menu = Bukkit.createInventory(holder, 54, title("Pick an Icon"));
        holder.inventory(menu);

        int start = page * PAGE_SIZE;
        int end = Math.min(icons.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            PadIcons.Icon icon = icons.get(i);
            menu.setItem(i - start, item(icon.material(), "<white>" + icon.title(), null));
        }
        for (int i = end - start; i < PAGE_SIZE; i++) menu.setItem(i, filler());
        navRow(menu, page, pages);
        player.openInventory(menu);
    }

    private static void navRow(Inventory menu, int page, int pages) {
        for (int i = PAGE_SIZE; i < 54; i++) menu.setItem(i, filler());
        menu.setItem(49, item(Material.BOOK, GOLD + "Page <white>" + (page + 1) + GOLD + " / <white>" + pages, null));
        if (page > 0) {
            menu.setItem(45, item(Material.ARROW, AQUA + "<bold>← Previous" + END, null));
        }
        if (page < pages - 1) {
            menu.setItem(53, item(Material.ARROW, AQUA + "<bold>Next →" + END, null));
        }
    }

    private static void fill(Inventory menu) {
        for (int i = 0; i < menu.getSize(); i++) menu.setItem(i, filler());
    }

    private static ItemStack filler() {
        return plain(Material.GRAY_STAINED_GLASS_PANE);
    }

    private static ItemStack plain(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        return finish(item, meta);
    }

    private static ItemStack selectorItem(TeleportPad pad, boolean current) {
        PadIcons.Icon icon = PadIcons.byId(pad.iconId());
        ItemStack item = new ItemStack(icon.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.MINI_MESSAGE.deserialize(
                (current ? AQUA + "<bold>" : "<white>") + "<name>" + (current ? END : ""),
                Placeholder.unparsed("name", PadListener.label(pad))));
        meta.lore(List.of(
                Messages.MINI_MESSAGE.deserialize("<gray>" + pad.key().world() + " <dark_gray>· <gray>"
                        + pad.key().x() + ", " + pad.key().y() + ", " + pad.key().z()),
                Messages.MINI_MESSAGE.deserialize(current
                        ? AQUA + "Currently selected" + END
                        : "<dark_gray>Click to warp here")));
        return finish(item, meta);
    }

    private static ItemStack item(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.MINI_MESSAGE.deserialize(name));
        if (lore != null) {
            meta.lore(List.of(Messages.MINI_MESSAGE.deserialize(lore)));
        }
        return finish(item, meta);
    }

    private static ItemStack item(Material material, String name, String lore1, String lore2) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.MINI_MESSAGE.deserialize(name));
        meta.lore(List.of(
                Messages.MINI_MESSAGE.deserialize(lore1),
                Messages.MINI_MESSAGE.deserialize(lore2)));
        return finish(item, meta);
    }

    private static ItemStack item(Material material, String name, String lore1, String lore2, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.MINI_MESSAGE.deserialize(name));
        meta.lore(List.of(
                Messages.MINI_MESSAGE.deserialize(lore1, resolver),
                Messages.MINI_MESSAGE.deserialize(lore2)));
        return finish(item, meta);
    }

    private static Component title(String text) {
        return Messages.MINI_MESSAGE.deserialize(
                "<dark_gray>» " + GOLD + "<bold>" + text + "</bold>" + END);
    }
}
