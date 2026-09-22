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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.logging.Level;

public final class ReplenishPlusPlusListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2000L;

    private final ReplenishPlusPlus plugin;
    private final AgeMetaRegistry ageMetaRegistry;
    private final PlayerToggleManager playerToggleManager;
    private final DevModeManager devModeManager;
    private final Map<BlockBreakEvent, HarvestPlan> pendingHarvests = new WeakHashMap<>();
    private final Map<UUID, Long> messageCooldown = new HashMap<>();

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
        devModeManager.onHarvest(player.getUniqueId(), plan.crop(), plan.mature());

        if (replant) {
            plugin.enqueueReplant(event.getBlock(), plan.config().replantDelayTicks(), plan.replantedAge(),
                    plan.cocoaFacing(), player.getUniqueId(), plan.seedConsumed());
        }
        distributeDrops(player, event.getBlock(), plan.config(), plan.drops());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        messageCooldown.remove(event.getPlayer().getUniqueId());
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
        Collection<ItemStack> drops = wasMature ? block.getDrops(tool, player) : Collections.emptyList();

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
                seedConsumed, wasMature));
    }

    private boolean isInNonSurvivalMode(Player player) {
        return switch (player.getGameMode()) {
            case CREATIVE, SPECTATOR, ADVENTURE -> true;
            case SURVIVAL -> false;
        };
    }

    private BlockFace findAnchorFace(CropInfo info, Block block) {
        for (BlockFace face : info.validNeighborFaces()) {
            if (info.plantsOn(block.getRelative(face).getType())) return face;
        }
        return null;
    }

    private void notifyWrongTool(Player player, ConfigCache config, CropType crop) {
        if (isMessageCooldownActive(player)) return;
        messageCooldown.put(player.getUniqueId(), System.currentTimeMillis());
        config.messageStyle().send(player, Messages.prefixed("harvest.wrong-tool",
                Placeholder.unparsed("crop", crop.displayName()),
                Placeholder.unparsed("tool", crop.requiredTool().displayName())));
        config.deniedToolSound().play(player);
    }

    private void notifyNeedSeed(Player player, ConfigCache config, CropType crop) {
        if (isMessageCooldownActive(player)) return;
        messageCooldown.put(player.getUniqueId(), System.currentTimeMillis());
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

    private boolean isMessageCooldownActive(Player player) {
        Long last = messageCooldown.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < MESSAGE_COOLDOWN_MS;
    }

    private record HarvestPlan(
            ConfigCache config,
            CropType crop,
            Collection<ItemStack> drops,
            int replantedAge,
            BlockFace cocoaFacing,
            boolean seedConsumed,
            boolean mature) {}
}
