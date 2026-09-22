package dev.replenishplusplus.dev;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.crop.HarvestTool;
import dev.replenishplusplus.util.TextUtil;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static final long MAX_PROFILER_SECONDS = 86_400L;

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
    private final long[] windowByCrop = new long[CropType.values().length];
    private final Map<Material, Long> windowToolUses = new HashMap<>();
    private final Map<Material, Map<Enchantment, Integer>> windowToolEnchants = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask scheduledStop;
    private boolean counting;
    private UUID reportActor;
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

    public void onRespawn(Player player) {
        if (!enabled.contains(player.getUniqueId())) return;
        if (!plugin.getConfigCache().dev().fastWater()) return;
        restoreWaterSpeed(player);
        applyWaterSpeed(player);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        cancelScheduledStop();
        for (UUID id : enabled) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restoreWaterSpeed(player);
        }
        enabled.clear();
        lastCrop.clear();
        counting = false;
        reportActor = null;
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

    public void onHarvest(UUID playerId, CropType crop, boolean mature, ItemStack tool) {
        if (enabled.isEmpty() || !enabled.contains(playerId)) return;
        lastCrop.put(playerId, crop);
        if (!mature) return;
        totalHarvests++;
        if (!counting) return;
        windowByCrop[crop.ordinal()]++;
        if (tool == null || tool.getType().isAir()) return;
        windowToolUses.merge(tool.getType(), 1L, Long::sum);
        Map<Enchantment, Integer> enchants = windowToolEnchants.computeIfAbsent(tool.getType(), _ -> new HashMap<>());
        for (Map.Entry<Enchantment, Integer> entry : tool.getEnchantments().entrySet()) {
            enchants.merge(entry.getKey(), entry.getValue(), Math::max);
        }
    }

    public void onSparkCommand(String raw, UUID actor) {
        if (!plugin.getConfigCache().dev().harvestCounter()) return;
        switch (parseSparkCommand(raw)) {
            case START -> {
                startCounting(actor);
                scheduleAutoStop(parseProfilerDurationSeconds(raw));
            }
            case STOP -> stopCounting();
            case TOGGLE -> {
                if (counting) {
                    stopCounting();
                } else {
                    startCounting(actor);
                    scheduleAutoStop(parseProfilerDurationSeconds(raw));
                }
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

    static long parseProfilerDurationSeconds(String raw) {
        if (raw == null) return 0;
        String command = raw.trim();
        int offset = !command.isEmpty() && command.charAt(0) == '/' ? 1 : 0;
        if (command.length() - offset < 20 || !command.regionMatches(true, offset, "spark profiler start", 0, 20)) {
            return 0;
        }
        int cursor = offset + 20;
        while (cursor < command.length()) {
            while (cursor < command.length() && (command.charAt(cursor) == ' ' || command.charAt(cursor) == '\t')) cursor++;
            int tokenStart = cursor;
            while (cursor < command.length() && command.charAt(cursor) != ' ' && command.charAt(cursor) != '\t') cursor++;
            if (tokenStart == cursor) break;
            String token = command.substring(tokenStart, cursor);
            if (token.equalsIgnoreCase("--timeout")) continue;
            if (isDigits(token)) {
                long seconds = Long.parseLong(token);
                return seconds > 0 ? Math.min(seconds, MAX_PROFILER_SECONDS) : 0;
            }
        }
        return 0;
    }

    private static boolean isDigits(String token) {
        if (token.isEmpty()) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void startCounting(UUID actor) {
        if (counting) return;
        counting = true;
        reportActor = actor;
        Arrays.fill(windowByCrop, 0);
        windowToolUses.clear();
        windowToolEnchants.clear();
        windowBaselineHarvests = totalHarvests;
        windowBaselineCleared = totalCleared;
        windowStartNanos = System.nanoTime();
        announce(reportActor, Messages.prefixedRaw(
                "<gradient:#FFD700:#FF5555>Harvest tracking started</gradient><gray> - you had <white>"
                        + String.format(Locale.ROOT, "%,d", totalHarvests) + "<gray> crops before this window."));
    }

    private void stopCounting() {
        if (!counting) return;
        counting = false;
        cancelScheduledStop();
        UUID actor = reportActor;
        reportActor = null;
        long harvested = totalHarvests - windowBaselineHarvests;
        long cleared = totalCleared - windowBaselineCleared;
        double seconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1_000_000_000.0);
        announce(actor, buildReport(harvested, seconds, cleared));
    }

    private void scheduleAutoStop(long seconds) {
        cancelScheduledStop();
        if (seconds <= 0) return;
        scheduledStop = plugin.getServer().getScheduler().runTaskLater(plugin, this::stopCounting, seconds * 20L);
    }

    private void cancelScheduledStop() {
        if (scheduledStop != null) {
            scheduledStop.cancel();
            scheduledStop = null;
        }
    }

    private Component buildReport(long harvested, double seconds, long cleared) {
        StringBuilder byCrop = new StringBuilder();
        for (CropType crop : CropType.values()) {
            long count = windowByCrop[crop.ordinal()];
            if (count <= 0) continue;
            if (!byCrop.isEmpty()) byCrop.append("<dark_gray> · </dark_gray>");
            byCrop.append("<white>").append(String.format(Locale.ROOT, "%,d", count))
                    .append("<gray> ").append(crop.displayName().toLowerCase(Locale.ROOT));
        }
        if (byCrop.isEmpty()) byCrop.append("<dark_gray>none");

        StringBuilder byTool = new StringBuilder();
        List<Map.Entry<Material, Long>> usedTools = new ArrayList<>(windowToolUses.entrySet());
        usedTools.sort(Map.Entry.<Material, Long>comparingByValue().reversed());
        for (Map.Entry<Material, Long> entry : usedTools) {
            byTool.append("<dark_gray>» <gradient:#55FFB4:#007FFF>Harvesting tool: <white>")
                    .append(TextUtil.prettyName(entry.getKey().name()))
                    .append(enchantsFor(entry.getKey()))
                    .append("<dark_gray> · <gray>")
                    .append(String.format(Locale.ROOT, "%,d", entry.getValue()))
                    .append(" harvests\n");
        }

        String report = """
                <gradient:#FFD700:#FF5555><bold>          ✦ Harvest Report ✦</bold></gradient>
                <dark_gray>» <gray>Profiler window: <white>%s
                <dark_gray>» <gradient:#55FFB4:#007FFF>Harvested: <white>%,d <gray>crops <dark_gray>(<white>%s<gray>/s)
                <dark_gray>» <gray>By crop: %s
                %s<dark_gray>» <gradient:#FFAA00:#FF5555>Auto-cleared: <white>%,d <gray>items
                <dark_gray>» <gray>Session total: <white>%,d <gray>crops harvested<dark_gray>, <white>%,d <gray>items cleared
                <gradient:#FFD700:#FF5555><st>                                       </st></gradient>"""
                .formatted(formatDuration(seconds), harvested,
                        String.format(Locale.ROOT, "%,.2f", harvested / seconds),
                        byCrop.toString(), byTool.toString(), cleared, totalHarvests, totalCleared);
        return Messages.MINI_MESSAGE.deserialize(report);
    }

    private String enchantsFor(Material toolMaterial) {
        Map<Enchantment, Integer> enchants = windowToolEnchants.get(toolMaterial);
        if (enchants == null || enchants.isEmpty()) return "";
        List<Enchantment> sorted = new ArrayList<>(enchants.keySet());
        sorted.sort(Comparator.comparing((Enchantment enchantment) -> enchantment.getKey().value()));
        StringBuilder text = new StringBuilder(" <dark_gray>(");
        boolean first = true;
        for (Enchantment enchantment : sorted) {
            if (!first) text.append("<gray>, ");
            text.append("<white>").append(TextUtil.prettyName(enchantment.getKey().value()))
                    .append(" ").append(roman(enchants.get(enchantment)));
            first = false;
        }
        return text.append("<dark_gray>)").toString();
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
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
        if (enabled.isEmpty() || !plugin.getConfigCache().dev().fastGrowth()) return;
        if (!CROP_MATERIALS.contains(block.getType())) return;
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

    private void announce(UUID actor, Component component) {
        plugin.getServer().getConsoleSender().sendMessage(component);
        Player actorPlayer = actor == null ? null : Bukkit.getPlayer(actor);
        if (actorPlayer != null) actorPlayer.sendMessage(component);
        for (UUID id : enabled) {
            if (id.equals(actor)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(component);
        }
    }
}
