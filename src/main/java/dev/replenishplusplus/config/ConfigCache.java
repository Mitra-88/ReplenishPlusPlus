package dev.replenishplusplus.config;

import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.queue.ReplantQueue;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public record ConfigCache(
        boolean enabled,
        boolean requirePlayerSeed,
        boolean directPickup,
        boolean sneakToBypass,
        MessageStyle messageStyle,
        int replantDelayTicks,
        int maxReplantsPerTick,
        int maxReplantsQueued,
        boolean checkUpdates,
        Set<CropType> disabledCrops,
        SoundEffect pickupSound,
        SoundEffect inventoryFullSound,
        SoundEffect deniedToolSound,
        SoundEffect deniedSeedSound,
        SoundEffect replantFailedSound,
        DevOptions dev) {

    public record DevOptions(
            boolean fastWater,
            boolean noIce,
            boolean inventoryClear,
            boolean fullAgeReplant,
            boolean harvestCounter,
            boolean fastGrowth) {}

    public static final int DEFAULT_REPLANT_DELAY_TICKS = 3;
    public static final int DEFAULT_MAX_REPLANTS_PER_TICK = 1024;
    public static final int DEFAULT_MAX_REPLANTS_QUEUED = 4096;
    public static final int MIN_REPLANTS_PER_TICK = 256;
    public static final int MIN_REPLANTS_QUEUED = 256;

    public ConfigCache {
        disabledCrops = Set.copyOf(disabledCrops);
    }

    public boolean isCropEnabled(CropType crop) {
        return !disabledCrops.contains(crop);
    }

    public ConfigCache withEnabled(boolean newEnabled) {
        return new ConfigCache(
                newEnabled, requirePlayerSeed, directPickup,
                sneakToBypass, messageStyle,
                replantDelayTicks, maxReplantsPerTick, maxReplantsQueued,
                checkUpdates, disabledCrops,
                pickupSound, inventoryFullSound, deniedToolSound, deniedSeedSound,
                replantFailedSound, dev);
    }

    public static ConfigCache defaults() {
        return from(new YamlConfiguration(), new ArrayList<>(0));
    }

    public static ConfigCache from(FileConfiguration config, List<String> issues) {
        int delayTicks = clampAtLeast(
                ConfigReader.intValue(config, "replantDelayTicks", DEFAULT_REPLANT_DELAY_TICKS, issues),
                DEFAULT_REPLANT_DELAY_TICKS, "replantDelayTicks", issues);
        if (delayTicks > ReplantQueue.MAX_DELAY_TICKS) {
            issues.add("replantDelayTicks: " + delayTicks + " exceeds the replant wheel capacity of "
                    + ReplantQueue.MAX_DELAY_TICKS + " ticks - delays will be capped");
        }
        int perTick = clampAtLeast(
                ConfigReader.intValue(config, "maxReplantsPerTick", DEFAULT_MAX_REPLANTS_PER_TICK, issues),
                MIN_REPLANTS_PER_TICK, "maxReplantsPerTick", issues);
        int queued = clampAtLeast(
                ConfigReader.intValue(config, "maxReplantsQueued", DEFAULT_MAX_REPLANTS_QUEUED, issues),
                MIN_REPLANTS_QUEUED, "maxReplantsQueued", issues);

        return new ConfigCache(
                ConfigReader.boolValue(config, "enabled", true, issues),
                ConfigReader.boolValue(config, "requirePlayerSeed", true, issues),
                ConfigReader.boolValue(config, "directPickup", true, issues),
                ConfigReader.boolValue(config, "sneakToBypass", true, issues),
                readStyle(config, issues),
                delayTicks, perTick, queued,
                ConfigReader.boolValue(config, "checkUpdates", true, issues),
                readCrops(config, issues),
                readSound(config, "pickup", Sound.ENTITY_ITEM_PICKUP, 1.0f, issues),
                readSound(config, "inventory-full", Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, issues),
                readSound(config, "denied-tool", Sound.ENTITY_VILLAGER_NO, 0.5f, issues),
                readSound(config, "denied-seed", Sound.ENTITY_VILLAGER_NO, 0.5f, issues),
                readSound(config, "replant-failed", Sound.ENTITY_ITEM_BREAK, 1.0f, issues),
                readDevOptions(config, issues));
    }
    private static DevOptions readDevOptions(FileConfiguration config, List<String> issues) {
        return new DevOptions(
                ConfigReader.boolValue(config, "dev.fast-water", true, issues),
                ConfigReader.boolValue(config, "dev.no-ice", true, issues),
                ConfigReader.boolValue(config, "dev.inventory-clear", true, issues),
                ConfigReader.boolValue(config, "dev.full-age-replant", true, issues),
                ConfigReader.boolValue(config, "dev.harvest-counter", true, issues),
                ConfigReader.boolValue(config, "dev.fast-growth", true, issues));
    }

    private static int clampAtLeast(int value, int min, String path, List<String> issues) {
        if (value >= min) return value;
        issues.add(path + ": " + value + " is below the minimum of " + min + " - using " + min);
        return min;
    }

    private static MessageStyle readStyle(FileConfiguration config, List<String> issues) {
        String raw = config.getString("messageStyle", "CHAT");
        MessageStyle style = MessageStyle.parse(raw);
        if (style != null) return style;
        issues.add("messageStyle: '" + raw + "' is not CHAT, ACTION_BAR, or NONE - using CHAT");
        return MessageStyle.CHAT;
    }

    private static SoundEffect readSound(FileConfiguration config, String key, Sound fallback, float fallbackPitch, List<String> issues) {
        String path = "sounds." + key;
        boolean enabled = ConfigReader.boolValue(config, path + ".enabled", true, issues);

        String soundName = ConfigReader.stringValue(config, path + ".sound", null, issues);
        Optional<Sound> resolved = SoundRegistryMapper.lookup(soundName);
        if (soundName != null && !soundName.isBlank() && resolved.isEmpty()) {
            issues.add(path + ".sound: '" + soundName + "' did not match any sound - falling back to " + SoundRegistryMapper.keyName(fallback));
        }

        float volume = ConfigReader.floatValue(config, path + ".volume", 1.0f, issues);
        float pitch = ConfigReader.floatValue(config, path + ".pitch", fallbackPitch, issues);
        if (volume < SoundEffect.MIN_VOLUME || volume > SoundEffect.MAX_VOLUME) {
            issues.add(path + ".volume: " + volume + " is outside 0.0-1.0 - it will be clamped");
        }
        if (pitch < SoundEffect.MIN_PITCH || pitch > SoundEffect.MAX_PITCH) {
            issues.add(path + ".pitch: " + pitch + " is outside 0.5-2.0 - it will be clamped");
        }
        return new SoundEffect(enabled, resolved.orElse(fallback), volume, pitch);
    }

    private static Set<CropType> readCrops(FileConfiguration config, List<String> issues) {
        Set<CropType> disabled = EnumSet.noneOf(CropType.class);
        ConfigurationSection section = config.getConfigurationSection("crops");
        if (section == null) {
            if (config.contains("crops")) {
                issues.add("crops: not a section of crop toggles - all crops stay enabled");
            }
            return disabled;
        }
        for (String key : section.getKeys(false)) {
            CropType crop = CropType.fromName(key);
            if (crop == null) {
                issues.add("crops." + key + ": unknown crop - ignoring (supported: " + supportedCrops() + ")");
                continue;
            }
            if (!ConfigReader.boolValue(config, "crops." + key, true, issues)) {
                disabled.add(crop);
            }
        }
        return disabled;
    }

    private static String supportedCrops() {
        StringBuilder sb = new StringBuilder();
        for (CropType crop : CropType.values()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(crop.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
