package dev.replenishplusplus.listener;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.config.PlayerToggleManager;
import dev.replenishplusplus.crop.AgeMetaRegistry;
import dev.replenishplusplus.crop.CocoaCropInfo;
import dev.replenishplusplus.crop.CropInfo;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.dev.DevModeManager;
import dev.replenishplusplus.util.DropPickupManager;
import dev.replenishplusplus.util.LocationUtil;
import dev.replenishplusplus.util.SeedIndex;
import dev.replenishplusplus.util.TextUtil;
import dev.replenishplusplus.util.VanillaCropDrops;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class ReplenishPlusPlusListener implements Listener {

    private static final long WRONG_TOOL_COOLDOWN_MS = 2000L;
    private static final long NEED_SEED_COOLDOWN_MS = 5000L;

    private final ReplenishPlusPlus plugin;
    private final AgeMetaRegistry ageMetaRegistry;
    private final PlayerToggleManager playerToggleManager;
    private final DevModeManager devModeManager;
    private final Map<BlockBreakEvent, HarvestPlan> pendingHarvests = new WeakHashMap<>();
    private final Map<UUID, Long> wrongToolCooldown = new HashMap<>();
    private final Map<UUID, Long> needSeedCooldown = new HashMap<>();

    public ReplenishPlusPlusListener(ReplenishPlusPlus plugin, AgeMetaRegistry ageMetaRegistry) {
        this.plugin = plugin;
        this.ageMetaRegistry = ageMetaRegistry;
        this.playerToggleManager = plugin.getPlayerToggleManager();
        this.devModeManager = plugin.getDevModeManager();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        try {
            prepareHarvest(event);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Unexpected error during crop break handling for player " + event.getPlayer().getName() + " at " + LocationUtil.describe(event.getBlock()), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakCommit(BlockBreakEvent event) {
        HarvestPlan plan = pendingHarvests.remove(event);
        if (plan == null || event.isCancelled() || event.isDropItems()) return;

        Player player = event.getPlayer();
        boolean replant = !plan.seedConsumed() || SeedIndex.consume(player, plan.crop().seed());
        if (!replant) notifyNeedSeed(player, plan.config(), plan.crop());
        devModeManager.onHarvest(player.getUniqueId(), plan.crop(), plan.mature(), plan.tool());

        if (replant) {
            int maxDelay = plan.config().replantDelayTicks();
            int delay = 1 + ThreadLocalRandom.current().nextInt(maxDelay);
            plugin.enqueueReplant(event.getBlock(), delay, plan.replantedAge(),
                    plan.cocoaFacing(), player.getUniqueId(), plan.seedConsumed());
        }
        distributeDrops(player, event.getBlock(), plan.config(), plan.drops());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        wrongToolCooldown.remove(event.getPlayer().getUniqueId());
        needSeedCooldown.remove(event.getPlayer().getUniqueId());
    }

    private void prepareHarvest(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isInNonSurvivalMode(player)) return;
        if (!event.isDropItems()) return;

        Block block = event.getBlock();
        CropType crop = CropType.fromMaterial(block.getType());
        if (crop == null) return;

        ConfigCache config = plugin.getConfigCache();
        if (!config.enabled()) return;
        if (!config.isCropEnabled(crop)) return;
        if (playerToggleManager.isDisabled(player)) return;
        if (config.sneakToBypass() && player.isSneaking()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!crop.requiredTool().matches(tool.getType())) {
            notifyWrongTool(player, config, crop);
            return;
        }

        CropInfo info = ageMetaRegistry.get(crop.material());
        if (info == null || info.lacksAnchorAt(block.getWorld(), block.getX(), block.getY(), block.getZ())) return;

        BlockData blockData = block.getBlockData();
        if (!(blockData instanceof Ageable ageable)) return;

        int originalAge = ageable.getAge();
        boolean wasMature = originalAge >= info.maximumAge();
        Collection<ItemStack> drops = wasMature ? matureDrops(block, crop, tool, player) : Collections.emptyList();

        boolean seedConsumed = false;
        if (wasMature && config.requirePlayerSeed()) {
            if (!SeedIndex.hasSeed(player, crop.seed())) {
                notifyNeedSeed(player, config, crop);
                return;
            }
            seedConsumed = true;
        }

        int replantedAge = devModeManager.anyActive() && config.dev().fullAgeReplant() && devModeManager.isActive(player.getUniqueId())
                ? info.maximumAge()
                : wasMature ? 0 : originalAge;
        event.setDropItems(false);
        pendingHarvests.put(event, new HarvestPlan(config, crop, drops, replantedAge,
                info instanceof CocoaCropInfo cocoa ? determineCocoaFacing(cocoa, block, blockData, player) : null,
                seedConsumed, wasMature, tool));
    }

    private boolean isInNonSurvivalMode(Player player) {
        return switch (player.getGameMode()) {
            case CREATIVE, SPECTATOR, ADVENTURE -> true;
            case SURVIVAL -> false;
        };
    }

    private Collection<ItemStack> matureDrops(Block block, CropType crop, ItemStack tool, Player player) {
        int fortune = Math.max(0, tool.getEnchantmentLevel(Enchantment.FORTUNE));
        int[] counts = VanillaCropDrops.counts(crop, fortune, ThreadLocalRandom.current());
        if (counts == null) {
            return block.getDrops(tool, player);
        }
        return stacksFor(crop, counts);
    }

    private static List<ItemStack> stacksFor(CropType crop, int[] counts) {
        return switch (crop) {
            case WHEAT -> stacks(counts, Material.WHEAT, Material.WHEAT_SEEDS);
            case CARROTS -> stacks(counts, Material.CARROT);
            case POTATOES -> stacks(counts, Material.POTATO, Material.POISONOUS_POTATO);
            case BEETROOTS -> stacks(counts, Material.BEETROOT, Material.BEETROOT_SEEDS);
            case NETHER_WART -> stacks(counts, Material.NETHER_WART);
            case COCOA -> stacks(counts, Material.COCOA_BEANS);
        };
    }

    private static List<ItemStack> stacks(int[] counts, Material... materials) {
        List<ItemStack> stacks = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) stacks.add(new ItemStack(materials[i], counts[i]));
        }
        return stacks;
    }

    private BlockFace findAnchorFace(CropInfo info, Block block) {
        for (BlockFace face : info.validNeighborFaces()) {
            if (info.plantsOn(block.getRelative(face).getType())) return face;
        }
        return null;
    }

    private void notifyWrongTool(Player player, ConfigCache config, CropType crop) {
        UUID playerId = player.getUniqueId();
        if (isOnCooldown(wrongToolCooldown, playerId, WRONG_TOOL_COOLDOWN_MS)) return;
        wrongToolCooldown.put(playerId, System.currentTimeMillis());
        config.messageStyle().send(player, Messages.prefixed("harvest.wrong-tool",
                Placeholder.unparsed("crop", crop.displayName()),
                Placeholder.unparsed("tool", crop.requiredTool().displayName())));
        config.deniedToolSound().play(player);
    }

    private void notifyNeedSeed(Player player, ConfigCache config, CropType crop) {
        UUID playerId = player.getUniqueId();
        if (isOnCooldown(needSeedCooldown, playerId, NEED_SEED_COOLDOWN_MS)) return;
        needSeedCooldown.put(playerId, System.currentTimeMillis());
        config.messageStyle().send(player, Messages.prefixed("harvest.need-seed",
                Placeholder.unparsed("count", "1"),
                Placeholder.unparsed("seed", TextUtil.prettyName(crop.seed().name()))));
        config.deniedSeedSound().play(player);
    }

    private void distributeDrops(Player player, Block block, ConfigCache config, Collection<ItemStack> drops) {
        if (config.directPickup()) {
            DropPickupManager.giveOrDrop(player, block, drops, config);
            return;
        }
        Location dropLocation = block.getLocation().toCenterLocation();
        for (ItemStack drop : drops) {
            block.getWorld().dropItemNaturally(dropLocation, drop);
        }
    }

    private BlockFace determineCocoaFacing(CocoaCropInfo cocoa, Block block, BlockData originalData, Player player) {
        BlockFace originalFacing = originalData instanceof Directional directional ? directional.getFacing() : null;
        if (originalFacing != null && cocoa.plantsOn(block.getRelative(originalFacing).getType())) return originalFacing;

        BlockFace playerFacing = player.getFacing();
        if (cocoa.plantsOn(block.getRelative(playerFacing).getType())) return playerFacing;

        return findAnchorFace(cocoa, block);
    }

    private boolean isOnCooldown(Map<UUID, Long> cooldowns, UUID playerId, long durationMs) {
        Long last = cooldowns.get(playerId);
        return last != null && System.currentTimeMillis() - last < durationMs;
    }

    private record HarvestPlan(
            ConfigCache config,
            CropType crop,
            Collection<ItemStack> drops,
            int replantedAge,
            BlockFace cocoaFacing,
            boolean seedConsumed,
            boolean mature,
            ItemStack tool) {}
}
