package dev.replenishplusplus;

import dev.replenishplusplus.command.ReplenishPlusPlusCommand;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.config.PlayerToggleManager;
import dev.replenishplusplus.crop.AgeMetaRegistry;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.listener.ReplenishPlusPlusListener;
import dev.replenishplusplus.listener.SeedCacheInvalidationListener;
import dev.replenishplusplus.queue.QueueStats;
import dev.replenishplusplus.queue.ReplantQueue;
import dev.replenishplusplus.update.UpdateChecker;
import dev.replenishplusplus.update.UpdateNotificationListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class ReplenishPlusPlus extends JavaPlugin {

    private final AtomicReference<ConfigCache> configCacheRef =
            new AtomicReference<>(ConfigCache.defaults());

    private AgeMetaRegistry ageMetaRegistry;
    private volatile ReplantQueue replantQueue;
    private UpdateChecker updateChecker;
    private PlayerToggleManager playerToggleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ageMetaRegistry = new AgeMetaRegistry(this);
        playerToggleManager = new PlayerToggleManager(this);
        reloadLocalConfig();

        ConfigCache config = getConfigCache();
        int enabledCrops = CropType.values().length - config.disabledCrops().size();

        sendConsole("Loaded successfully.");
        sendConsole("Supported crops: <white>" + enabledCrops);
        if (!config.disabledCrops().isEmpty()) {
            StringBuilder disabled = new StringBuilder();
            for (CropType crop : config.disabledCrops()) {
                if (!disabled.isEmpty()) disabled.append(", ");
                disabled.append(crop.name().toLowerCase(Locale.ROOT));
            }
            sendConsole("<yellow>Disabled crops: <white>" + disabled);
        }
        sendConsole("Replants per tick: <white>" + config.maxReplantsPerTick());
        sendConsole("Queue capacity: <white>" + config.maxReplantsQueued());
        sendConsole("Delay: <white>" + config.replantDelayTicks() + " tick");
        sendConsole("Running version: <white>v" + getPluginMeta().getVersion());

        updateChecker = new UpdateChecker(this, config.checkUpdates());

        getServer().getPluginManager().registerEvents(new ReplenishPlusPlusListener(this, ageMetaRegistry), this);
        getServer().getPluginManager().registerEvents(new SeedCacheInvalidationListener(), this);
        getServer().getPluginManager().registerEvents(new UpdateNotificationListener(this), this);

        updateChecker.check();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> new ReplenishPlusPlusCommand(this).register(event.registrar()));
    }

    @Override
    public void onDisable() {
        if (replantQueue != null) {
            replantQueue.flush();
        }
    }

    public List<String> reloadLocalConfig() {
        List<String> issues = new ArrayList<>();
        try {
            reloadConfig();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Config reload failed - keeping previous settings", e);
            issues.add("config.yml could not be loaded - keeping previous settings (" + e.getMessage() + ")");
            return List.copyOf(issues);
        }

        ConfigCache cache = ConfigCache.from(getConfig(), issues);
        for (String issue : issues) {
            getLogger().warning("[Config] " + issue);
        }

        configCacheRef.set(cache);
        restartQueue(cache.maxReplantsPerTick(), cache.maxReplantsQueued());
        return List.copyOf(issues);
    }

    private void restartQueue(int maxPerTick, int maxQueued) {
        ReplantQueue oldQueue = replantQueue;
        ReplantQueue newQueue = new ReplantQueue(this, maxPerTick, maxQueued, ageMetaRegistry);
        newQueue.start();
        replantQueue = newQueue;

        if (oldQueue != null) {
            int flushed = oldQueue.flush();
            if (flushed > 0) {
                sendConsole("<yellow>Flushed " + flushed + " pending replant(s) before queue restart.");
            }
        }
    }

    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public PlayerToggleManager getPlayerToggleManager() { return playerToggleManager; }

    public ConfigCache getConfigCache() { return configCacheRef.get(); }
    public boolean isEnabledGlobally() { return getConfigCache().enabled(); }

    public void setGloballyEnabled(boolean enabled) {
        configCacheRef.updateAndGet(current -> current.withEnabled(enabled));
    }

    public boolean isCropEnabled(CropType crop) {
        return getConfigCache().isCropEnabled(crop);
    }

    public void enqueueReplant(Block block, int delayTicks, int targetAge,
                               BlockFace cocoaFacing, UUID playerId, boolean seedConsumed) {
        ReplantQueue queue = this.replantQueue;
        if (queue != null) {
            queue.enqueue(block, delayTicks, targetAge, cocoaFacing, playerId, seedConsumed);
        }
    }

    public QueueStats getQueueStats() {
        ReplantQueue queue = this.replantQueue;
        return queue != null ? queue.getStats() : new QueueStats(0, 0, 0);
    }

    private void sendConsole(String message) {
        getServer().getConsoleSender().sendMessage(Messages.MINI_MESSAGE.deserialize(Messages.PREFIX + message));
    }
}