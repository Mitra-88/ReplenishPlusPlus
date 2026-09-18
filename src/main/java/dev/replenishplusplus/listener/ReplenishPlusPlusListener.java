package dev.replenishplusplus.listener;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.crop.AgeMetaRegistry;
import dev.replenishplusplus.crop.CocoaCropInfo;
import dev.replenishplusplus.crop.CropInfo;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.util.DropPickupManager;
import dev.replenishplusplus.util.LocationUtil;
import dev.replenishplusplus.util.SeedIndex;
import dev.replenishplusplus.util.TextUtil;
import net.kyori.adventure.text.Component;
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
    private final Map<BlockBreakEvent, HarvestPlan> pendingHarvests = new WeakHashMap<>();
    private final Map<UUID, Long> messageCooldown = new HashMap<>();

    public ReplenishPlusPlusListener(ReplenishPlusPlus plugin, AgeMetaRegistry ageMetaRegistry) {
        this.plugin = plugin;
        this.ageMetaRegistry = ageMetaRegistry;
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
        if (plan == null || event.isCancelled()) return;

        Player player = event.getPlayer();
        boolean replant = !plan.seedConsumed() || SeedIndex.consume(player, plan.crop().seed());
        if (!replant) notifyNeedSeed(player, plan.config(), plan.crop());

        if (replant) {
            plugin.enqueueReplant(event.getBlock(), plan.config().replantDelayTicks(), plan.replantedAge(),
                    plan.cocoaFacing(), player.getUniqueId(), plan.seedConsumed());
        }
        distributeDrops(player, event.getBlock(), plan.config(), plan.drops());
    }

    private void prepareHarvest(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isInNonSurvivalMode(player)) return;
        if (!event.isDropItems()) return;

        ConfigCache config = plugin.getConfigCache();
        if (!config.enabled()) return;
        if (plugin.getPlayerToggleManager().isDisabled(player)) return;
        if (config.sneakToBypass() && player.isSneaking()) return;

        Block block = event.getBlock();
        CropType crop = CropType.fromMaterial(block.getType());
        if (crop == null || !plugin.isCropEnabled(crop)) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!crop.requiredTool().matches(tool.getType())) {
            notifyWrongTool(player, config, crop);
            return;
        }

        CropInfo info = ageMetaRegistry.get(crop.material());
        if (info == null || !hasValidAnchor(block, info)) return;

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

        event.setDropItems(false);
        pendingHarvests.put(event, new HarvestPlan(config, crop, drops, wasMature ? 0 : originalAge,
                info instanceof CocoaCropInfo cocoa ? determineCocoaFacing(cocoa, block, blockData, player) : null,
                seedConsumed));
    }

    private boolean isInNonSurvivalMode(Player player) {
        return switch (player.getGameMode()) {
            case CREATIVE, SPECTATOR, ADVENTURE -> true;
            case SURVIVAL -> false;
        };
    }

    private boolean hasValidAnchor(Block block, CropInfo info) {
        return findAnchorFace(info, block) != null;
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
        String template = normalizeTemplate(config.requiresToolMessage(), "crop", "tool");
        Component component = Messages.MINI_MESSAGE.deserialize(
                template,
                Placeholder.parsed("crop", crop.displayName()),
                Placeholder.parsed("tool", crop.requiredTool().displayName())
        );
        config.messageStyle().send(player, component);
        config.deniedToolSound().play(player);
    }

    private void notifyNeedSeed(Player player, ConfigCache config, CropType crop) {
        if (isMessageCooldownActive(player)) return;
        messageCooldown.put(player.getUniqueId(), System.currentTimeMillis());
        String template = normalizeTemplate(config.needSeedMessage(), "count", "seed");
        Component component = Messages.MINI_MESSAGE.deserialize(
                template,
                Placeholder.parsed("count", "1"),
                Placeholder.parsed("seed", TextUtil.prettyName(crop.seed().name()))
        );
        config.messageStyle().send(player, component);
        config.deniedSeedSound().play(player);
    }

    private static String normalizeTemplate(String raw, String... tags) {
        String result = raw;
        for (String tag : tags) {
            result = result.replace("{" + tag + "}", "<" + tag + ">");
        }
        return result;
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
            boolean seedConsumed) {}
}
