package dev.replenishplusplus;

import dev.replenishplusplus.command.ReplenishPlusPlusCommand;
import dev.replenishplusplus.compat.PluginConflicts;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.config.PlayerToggleManager;
import dev.replenishplusplus.crop.AgeMetaRegistry;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.dev.DevModeListener;
import dev.replenishplusplus.dev.DevModeManager;
import dev.replenishplusplus.listener.ReplenishPlusPlusListener;
import dev.replenishplusplus.listener.SeedCacheInvalidationListener;
import dev.replenishplusplus.pad.PadListener;
import dev.replenishplusplus.pad.PadMenuListener;
import dev.replenishplusplus.pad.TeleportPadManager;
import dev.replenishplusplus.queue.QueueStats;
import dev.replenishplusplus.queue.ReplantQueue;
import dev.replenishplusplus.update.UpdateChecker;
import dev.replenishplusplus.update.UpdateNotificationListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
    private DevModeManager devModeManager;
    private TeleportPadManager padManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "en_us.yml").exists()) {
            saveResource("en_us.yml", false);
        }
        ageMetaRegistry = new AgeMetaRegistry(this);
        playerToggleManager = new PlayerToggleManager(this);
        devModeManager = new DevModeManager(this);
        padManager = new TeleportPadManager(this);
        reloadLocalConfig();
        if (replantQueue == null) {
            ConfigCache fallback = getConfigCache();
            restartQueue(fallback.maxReplantsPerTick(), fallback.maxReplantsQueued());
        }

        ConfigCache config = getConfigCache();
        int enabledCrops = CropType.values().length - config.disabledCrops().size();

        sendConsole("<gradient:#FFD700:#FF9D00>Loaded successfully</gradient> <dark_gray>· <white>v" + getPluginMeta().getVersion());
        sendConsole("<gradient:#FFD700:#FF9D00>Supported crops</gradient> <dark_gray>· <white>" + enabledCrops);
        if (!config.disabledCrops().isEmpty()) {
            StringBuilder disabled = new StringBuilder();
            for (CropType crop : config.disabledCrops()) {
                if (!disabled.isEmpty()) disabled.append(", ");
                disabled.append(crop.name().toLowerCase(Locale.ROOT));
            }
            sendConsole("<yellow>Disabled crops</yellow> <dark_gray>· <white>" + disabled);
        }
        sendConsole("<gradient:#FFD700:#FF9D00>Replants per tick</gradient> <dark_gray>· <white>" + config.maxReplantsPerTick());
        sendConsole("<gradient:#FFD700:#FF9D00>Queue capacity</gradient> <dark_gray>· <white>" + config.maxReplantsQueued());
        int delayTicks = config.replantDelayTicks();
        sendConsole("<gradient:#FFD700:#FF9D00>Replant delay</gradient> <dark_gray>· <white>up to " + delayTicks + (delayTicks == 1 ? " tick" : " ticks"));
        sendConsole("<gradient:#FFD700:#FF9D00>Running version</gradient> <dark_gray>· <white>v" + getPluginMeta().getVersion());

        List<String> installed = new ArrayList<>();
        for (Plugin plugin : getServer().getPluginManager().getPlugins()) {
            installed.add(plugin.getName());
        }
        List<String> conflicts = PluginConflicts.scan(PluginConflicts.KNOWN, installed);
        if (!conflicts.isEmpty()) {
            sendConsole("<yellow>Known incompatible plugins detected: <white>" + String.join(", ", conflicts)
                    + " <dark_gray>· <gray>reported to break Replenish++ features, staying enabled");
        }

        updateChecker = new UpdateChecker(this, config.checkUpdates());

        getServer().getPluginManager().registerEvents(new ReplenishPlusPlusListener(this, ageMetaRegistry), this);
        getServer().getPluginManager().registerEvents(new SeedCacheInvalidationListener(), this);
        getServer().getPluginManager().registerEvents(new UpdateNotificationListener(this), this);
        getServer().getPluginManager().registerEvents(new DevModeListener(devModeManager), this);
        getServer().getPluginManager().registerEvents(new PadListener(padManager), this);
        getServer().getPluginManager().registerEvents(new PadMenuListener(this, padManager), this);

        updateChecker.check();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> new ReplenishPlusPlusCommand(this).register(event.registrar()));
    }

    @Override
    public void onDisable() {
        if (devModeManager != null) {
            devModeManager.shutdown();
        }
        if (padManager != null) {
            padManager.save();
        }
        if (replantQueue != null) {
            replantQueue.flush();
        }
    }

    public List<String> reloadLocalConfig() {
        List<String> issues = new ArrayList<>();
        Messages.load(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "en_us.yml")));
        try {
            reloadConfig();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Config reload failed - keeping previous settings", e);
            issues.add("config.yml could not be loaded - keeping previous settings (" + e.getMessage() + ")");
            return List.copyOf(issues);
        }

        if (isConfigFileBroken()) {
            String issue = "config.yml is empty or could not be parsed (invalid YAML?) - keeping previous settings";
            getLogger().warning("[Config] " + issue);
            issues.add(issue);
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
                sendConsole("<gradient:#FFD700:#FF9D00>Queue flush</gradient> <dark_gray>· <white>" + flushed
                        + "<gray> pending replant(s) replanted before the restart");
            }
        }
    }

    public boolean isConfigFileBroken() {
        File configFile = new File(getDataFolder(), "config.yml");
        return configFile.isFile() && getConfig().getKeys(false).isEmpty();
    }

    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public PlayerToggleManager getPlayerToggleManager() { return playerToggleManager; }
    public DevModeManager getDevModeManager() { return devModeManager; }
    public TeleportPadManager getPadManager() { return padManager; }

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
        replantQueue.enqueue(block, delayTicks, targetAge, cocoaFacing, playerId, seedConsumed);
    }

    public QueueStats getQueueStats() {
        return replantQueue.getStats();
    }

    private void sendConsole(String message) {
        getServer().getConsoleSender().sendMessage(Messages.prefixedRaw(message));
    }
}