package dev.replenishplusplus.pad;

import dev.replenishplusplus.ReplenishPlusPlus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeleportPadManager {

    public static final int MAX_PADS = 56;

    private final ReplenishPlusPlus plugin;
    private final NamespacedKey padItemKey;
    private final NamespacedKey displayKey;
    private final Map<String, TeleportPad> pads = new HashMap<>();
    private final Map<BlockKey, UUID> displayIds = new HashMap<>();

    public TeleportPadManager(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
        this.padItemKey = new NamespacedKey(plugin, "teleport-pad");
        this.displayKey = new NamespacedKey(plugin, "teleport-pad-display");
        load();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                syncChunkDisplays(chunk);
            }
        }
    }

    public TeleportPad get(BlockKey key) {
        return pads.get(key.serialize());
    }

    public TeleportPad at(Block block) {
        return pads.get(keyOf(block).serialize());
    }

    public static BlockKey keyOf(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public List<TeleportPad> ownedBy(UUID owner) {
        List<TeleportPad> owned = new ArrayList<>();
        for (TeleportPad pad : pads.values()) {
            if (pad.owner().equals(owner)) owned.add(pad);
        }
        owned.sort((a, b) -> a.key().serialize().compareTo(b.key().serialize()));
        return owned;
    }

    public int countOwned(UUID owner) {
        int count = 0;
        for (TeleportPad pad : pads.values()) {
            if (pad.owner().equals(owner)) count++;
        }
        return count;
    }

    public boolean place(TeleportPad pad) {
        if (countOwned(pad.owner()) >= MAX_PADS || pads.containsKey(pad.key().serialize())) return false;
        pads.put(pad.key().serialize(), pad);
        save();
        spawnDisplay(get(pad.key()));
        return true;
    }

    public void update(TeleportPad pad) {
        pads.put(pad.key().serialize(), pad);
        save();
        refreshDisplay(pad);
    }

    public void remove(BlockKey key) {
        if (pads.remove(key.serialize()) == null) return;
        for (TeleportPad pad : List.copyOf(pads.values())) {
            if (key.equals(pad.destination())) {
                pads.put(pad.key().serialize(), new TeleportPad(pad.key(), pad.owner(), pad.iconId(), pad.name(), null, pad.arrival()));
            }
        }
        save();
        removeDisplay(key);
    }

    public void clearDestination(BlockKey padKey) {
        TeleportPad pad = get(padKey);
        if (pad == null || pad.destination() == null) return;
        update(new TeleportPad(pad.key(), pad.owner(), pad.iconId(), pad.name(), null, pad.arrival()));
    }

    public String firstFreeIcon(UUID owner) {
        Set<String> used = new HashSet<>();
        for (TeleportPad pad : pads.values()) {
            if (pad.owner().equals(owner)) used.add(pad.iconId());
        }
        for (PadIcons.Icon icon : PadIcons.all()) {
            if (!used.contains(icon.id())) return icon.id();
        }
        return PadIcons.defaultIcon().id();
    }

    private void spawnDisplay(TeleportPad pad) {
        if (pad == null) return;
        World world = Bukkit.getWorld(pad.key().world());
        if (world == null) return;
        Block block = world.getBlockAt(pad.key().x(), pad.key().y(), pad.key().z());
        if (block.getType() != Material.END_PORTAL_FRAME) return;
        removeStaleDisplays(block, pad.key());

        ItemStack iconItem = new ItemStack(PadIcons.byId(pad.iconId()).material());
        Location spawnLocation = block.getLocation().add(0.5, 1.2, 0.5);
        ItemDisplay display = world.spawn(spawnLocation, ItemDisplay.class, d -> {
            d.setItemStack(iconItem);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new Quaternionf(),
                    new Vector3f(0.4f, 0.4f, 0.4f),
                    new Quaternionf()));
            d.setPersistent(false);
            d.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, pad.key().serialize());
        });
        displayIds.put(pad.key(), display.getUniqueId());
    }

    private void removeStaleDisplays(Block block, BlockKey key) {
        String serialized = key.serialize();
        for (Entity entity : block.getChunk().getEntities()) {
            if (entity instanceof ItemDisplay
                    && serialized.equals(entity.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING))) {
                entity.remove();
            }
        }
    }

    public boolean isPadDisplay(Entity entity) {
        return entity instanceof ItemDisplay
                && entity.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING);
    }

    private void refreshDisplay(TeleportPad pad) {
        removeDisplay(pad.key());
        spawnDisplay(pad);
    }

    private void removeDisplay(BlockKey key) {
        UUID id = displayIds.remove(key);
        if (id != null) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null) return;
        Block block = world.getBlockAt(key.x(), key.y(), key.z());
        removeStaleDisplays(block, key);
    }

    public void syncChunkDisplays(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof ItemDisplay
                    && entity.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
        String worldName = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        for (TeleportPad pad : pads.values()) {
            BlockKey key = pad.key();
            if (key.world().equals(worldName) && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ) {
                spawnDisplay(pad);
            }
        }
    }

    public void pickUp(Player player, TeleportPad pad) {
        remove(pad.key());
        World world = Bukkit.getWorld(pad.key().world());
        if (world != null) {
            Block block = world.getBlockAt(pad.key().x(), pad.key().y(), pad.key().z());
            if (block.getType() == Material.END_PORTAL_FRAME) block.setType(Material.AIR);
        }
        givePadItem(player);
    }

    public void givePadItem(Player player) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(createPadItem());
        for (ItemStack rest : leftover.values()) {
            if (rest != null && rest.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    public ItemStack createPadItem() {
        ItemStack item = new ItemStack(Material.END_PORTAL_FRAME);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Teleport Pad", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to warp to its destination.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak-right-click to configure it.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(padItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isPadItem(ItemStack item) {
        if (item == null || item.getType() != Material.END_PORTAL_FRAME) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(padItemKey, PersistentDataType.BYTE);
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file());
        int broken = 0;
        for (Map<?, ?> entry : yaml.getMapList("pads")) {
            Object keyRaw = entry.get("key");
            Object ownerRaw = entry.get("owner");
            BlockKey key = keyRaw instanceof String s ? BlockKey.parse(s) : null;
            if (key == null || !(ownerRaw instanceof String ownerText)) {
                broken++;
                continue;
            }
            try {
                UUID owner = UUID.fromString(ownerText);
                Object iconRaw = entry.get("icon");
                String iconId = iconRaw instanceof String s && !s.isBlank() ? s : PadIcons.defaultIcon().id();
                Object nameRaw = entry.get("name");
                String name = nameRaw instanceof String s ? s : null;
                Object destRaw = entry.get("destination");
                BlockKey destination = destRaw instanceof String s ? BlockKey.parse(s) : null;
                Object arrivalRaw = entry.get("arrival");
                PadDirection arrival;
                try {
                    arrival = PadDirection.valueOf(arrivalRaw instanceof String s ? s : PadDirection.LAST.name());
                } catch (IllegalArgumentException e) {
                    arrival = PadDirection.LAST;
                }
                pads.put(key.serialize(), new TeleportPad(key, owner, iconId, name, destination, arrival));
            } catch (IllegalArgumentException e) {
                broken++;
            }
        }
        if (broken > 0) {
            plugin.getLogger().warning("[Pads] Skipped " + broken + " broken pad entries in pads.yml.");
        }
    }

    public void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TeleportPad pad : pads.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", pad.key().serialize());
            entry.put("owner", pad.owner().toString());
            entry.put("icon", pad.iconId());
            if (pad.name() != null) entry.put("name", pad.name());
            if (pad.destination() != null) entry.put("destination", pad.destination().serialize());
            entry.put("arrival", pad.arrival().name());
            list.add(entry);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pads", list);
        try {
            yaml.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("[Pads] Could not save pads.yml: " + e.getMessage());
        }
    }

    private File file() {
        return new File(plugin.getDataFolder(), "pads.yml");
    }
}
