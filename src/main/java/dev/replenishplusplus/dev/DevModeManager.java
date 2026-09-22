package dev.replenishplusplus.dev;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.crop.HarvestTool;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DevModeManager {

    public enum SparkAction { START, STOP, TOGGLE, OTHER }

    private static final int FREE_SLOTS_BEFORE_CLEAR = 3;
    private static final int KEEP_SEED_AMOUNT = 64;
    private static final int STORAGE_SIZE = 36;
    private static final long CLEAR_TASK_PERIOD_TICKS = 20L;
    private static final int GROWTH_RADIUS = 12;
    private static final int GROWTH_Y_RANGE = 2;

    private static final Set<Material> CROP_MATERIALS;

    private static final Set<Material> SEED_MATERIALS;

    static {
        EnumSet<Material> crops = EnumSet.noneOf(Material.class);
        EnumSet<Material> seeds = EnumSet.noneOf(Material.class);
        for (CropType crop : CropType.values()) {
            crops.add(crop.material());
            seeds.add(crop.seed());
        }
        CROP_MATERIALS = crops;
        SEED_MATERIALS = seeds;
    }

    private final ReplenishPlusPlus plugin;
    private final NamespacedKey waterSpeedKey;
    private final AttributeModifier waterSpeedModifier;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<UUID, CropType> lastCrop = new HashMap<>();
    private BukkitTask tickTask;
    private boolean counting;
    private long totalHarvests;
    private long totalCleared;
    private long windowBaselineHarvests;
    private long windowBaselineCleared;
    private long windowStartNanos;

    public DevModeManager(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
        this.waterSpeedKey = new NamespacedKey(plugin, "dev-mode-water-speed");
        this.waterSpeedModifier = new AttributeModifier(waterSpeedKey, 1.0, AttributeModifier.Operation.ADD_NUMBER);
    }

    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (enabled.remove(id)) {
            restoreWaterSpeed(player);
            lastCrop.remove(id);
            stopTaskIfIdle();
            return false;
        }
        enabled.add(id);
        ConfigCache.DevOptions dev = plugin.getConfigCache().dev();
        if (dev.fastWater()) {
            applyWaterSpeed(player);
        }
        if (dev.fastGrowth()) {
            growField(player);
        }
        startTaskIfIdle();
        return true;
    }

    public void quit(UUID playerId) {
        if (enabled.remove(playerId)) stopTaskIfIdle();
        lastCrop.remove(playerId);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (UUID id : enabled) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restoreWaterSpeed(player);
        }
        enabled.clear();
        lastCrop.clear();
        counting = false;
    }

    public boolean isActive(UUID playerId) {
        return enabled.contains(playerId);
    }

    public boolean anyActive() {
        return !enabled.isEmpty();
    }

    public boolean noIceActive() {
        return anyActive() && plugin.getConfigCache().dev().noIce();
    }

    public void onHarvest(UUID playerId, CropType crop, boolean mature) {
        if (enabled.isEmpty() || !enabled.contains(playerId)) return;
        lastCrop.put(playerId, crop);
        if (mature) totalHarvests++;
    }

    public void onSparkAction(SparkAction action) {
        if (!plugin.getConfigCache().dev().harvestCounter()) return;
        switch (action) {
            case START -> startCounting();
            case STOP -> stopCounting();
            case TOGGLE -> {
                if (counting) stopCounting();
                else startCounting();
            }
            case OTHER -> {}
        }
    }

    static SparkAction parseSparkCommand(String raw) {
        if (raw == null || raw.length() < 14) return SparkAction.OTHER;
        String command = raw.trim();
        if (command.isEmpty()) return SparkAction.OTHER;
        int offset = command.charAt(0) == '/' ? 1 : 0;
        if (command.length() - offset < 14 || !command.regionMatches(true, offset, "spark profiler", 0, 14)) {
            return SparkAction.OTHER;
        }
        int restStart = offset + 14;
        if (restStart == command.length()) return SparkAction.TOGGLE;
        char separator = command.charAt(restStart);
        if (separator != ' ' && separator != '\t') return SparkAction.OTHER;
        int argStart = restStart + 1;
        while (argStart < command.length() && command.charAt(argStart) == ' ') argStart++;
        int argEnd = argStart;
        while (argEnd < command.length() && command.charAt(argEnd) != ' ' && command.charAt(argEnd) != '\t') argEnd++;
        if (argStart == argEnd) return SparkAction.TOGGLE;
        return switch (command.substring(argStart, argEnd).toLowerCase(Locale.ROOT)) {
            case "start" -> SparkAction.START;
            case "stop" -> SparkAction.STOP;
            default -> command.charAt(argStart) == '-' ? SparkAction.TOGGLE : SparkAction.OTHER;
        };
    }

    private void startCounting() {
        if (counting) return;
        counting = true;
        windowBaselineHarvests = totalHarvests;
        windowBaselineCleared = totalCleared;
        windowStartNanos = System.nanoTime();
        announce(Messages.prefixedRaw(
                "<gradient:#FFD700:#FF5555>Harvest tracking started</gradient><gray> - you had <white>"
                        + String.format(Locale.ROOT, "%,d", totalHarvests) + "<gray> crops before this window."));
    }

    private void stopCounting() {
        if (!counting) return;
        counting = false;
        long harvested = totalHarvests - windowBaselineHarvests;
        long cleared = totalCleared - windowBaselineCleared;
        double seconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1_000_000_000.0);
        String perSecond = String.format(Locale.ROOT, "%.2f", harvested / seconds);
        announce(buildReport(windowBaselineHarvests, harvested, perSecond, cleared, formatDuration(seconds)));
    }

    private static Component buildReport(long before, long harvested, String perSecond, long cleared, String duration) {
        return Messages.MINI_MESSAGE.deserialize("""
                <gradient:#FFD700:#FF5555><bold>          ✦ Harvest Report ✦</bold></gradient>
                <dark_gray>» <gray>Profiler window: <white>%s
                <dark_gray>» <gray>Crops before: <white>%s
                <dark_gray>» <gradient:#55FFB4:#007FFF>Harvested: <white>%s
                <dark_gray>» <gradient:#55FFB4:#007FFF>Blocks per second: <white>%s
                <dark_gray>» <gradient:#FFAA00:#FF5555>Crops cleared: <white>%s
                <gradient:#FFD700:#FF5555><st>                                                   </st></gradient>"""
                .formatted(duration,
                        String.format(Locale.ROOT, "%,d", before),
                        String.format(Locale.ROOT, "%,d", harvested),
                        perSecond,
                        String.format(Locale.ROOT, "%,d", cleared)));
    }

    private static String formatDuration(double seconds) {
        long total = (long) seconds;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    private void startTaskIfIdle() {
        if (tickTask != null) return;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::clearTick, CLEAR_TASK_PERIOD_TICKS, CLEAR_TASK_PERIOD_TICKS);
    }

    private void stopTaskIfIdle() {
        if (enabled.isEmpty() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void clearTick() {
        if (!plugin.getConfigCache().dev().inventoryClear()) return;
        for (UUID id : enabled) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) clearIfNearlyFull(player);
        }
    }

    public void onCropPlaced(Block block) {
        if (enabled.isEmpty() || !CROP_MATERIALS.contains(block.getType())) return;
        BlockData data = block.getBlockData();
        if (!(data instanceof Ageable ageable) || ageable.getAge() >= ageable.getMaximumAge()) return;
        ageable.setAge(ageable.getMaximumAge());
        block.setBlockData(ageable, false);
    }

    private void growField(Player player) {
        Location base = player.getLocation();
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();
        World world = player.getWorld();
        for (int dy = -GROWTH_Y_RANGE; dy <= GROWTH_Y_RANGE; dy++) {
            for (int dx = -GROWTH_RADIUS; dx <= GROWTH_RADIUS; dx++) {
                for (int dz = -GROWTH_RADIUS; dz <= GROWTH_RADIUS; dz++) {
                    if (!CROP_MATERIALS.contains(world.getType(baseX + dx, baseY + dy, baseZ + dz))) continue;
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    BlockData data = block.getBlockData();
                    if (!(data instanceof Ageable ageable) || ageable.getAge() >= ageable.getMaximumAge()) continue;
                    ageable.setAge(ageable.getMaximumAge());
                    block.setBlockData(ageable, false);
                }
            }
        }
    }

    private void clearIfNearlyFull(Player player) {
        PlayerInventory inventory = player.getInventory();
        int free = 0;
        for (int i = 0; i < STORAGE_SIZE; i++) {
            if (inventory.getItem(i) == null && ++free > FREE_SLOTS_BEFORE_CLEAR) return;
        }
        CropType crop = lastCrop.get(player.getUniqueId());
        int seedsKept = 0;
        for (int i = 0; i < STORAGE_SIZE; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            Material type = item.getType();
            if (HarvestTool.HOE.matches(type) || HarvestTool.AXE.matches(type)) continue;
            if (isKeptSeed(type, crop)) {
                int keepHere = Math.min(item.getAmount(), KEEP_SEED_AMOUNT - seedsKept);
                if (keepHere <= 0) {
                    totalCleared += item.getAmount();
                    inventory.setItem(i, null);
                    continue;
                }
                if (keepHere < item.getAmount()) {
                    totalCleared += item.getAmount() - keepHere;
                    item.setAmount(keepHere);
                }
                seedsKept += keepHere;
                continue;
            }
            totalCleared += item.getAmount();
            inventory.setItem(i, null);
        }
    }

    private static boolean isKeptSeed(Material type, CropType crop) {
        return crop != null ? type == crop.seed() : SEED_MATERIALS.contains(type);
    }

    private void applyWaterSpeed(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);
        if (attribute != null) attribute.addTransientModifier(waterSpeedModifier);
    }

    private void restoreWaterSpeed(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);
        if (attribute != null) attribute.removeModifier(waterSpeedKey);
    }

    private void announce(Component component) {
        plugin.getServer().getConsoleSender().sendMessage(component);
        for (UUID id : enabled) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(component);
        }
    }
}
