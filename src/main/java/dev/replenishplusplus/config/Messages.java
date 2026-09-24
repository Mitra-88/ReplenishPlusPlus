package dev.replenishplusplus.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

public final class Messages {

    public static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    public static final String DOT = "<dark_gray>• ";
    public static final String LINE = "<gradient:#FFD700:#FF9D00><st>                                                                               </st></gradient>";

    private static final String DEFAULT_PREFIX = "<gradient:#FFD700:#FF9D00><bold>Replenish++</bold></gradient> <dark_gray>»</dark_gray>";

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("prefix", DEFAULT_PREFIX),
            Map.entry("harvest.wrong-tool", "<gray>You need a <yellow><tool> <gray>to farm <yellow><crop><gray>."),
            Map.entry("harvest.need-seed", "<gray>Out of <yellow><seed><gray>! Grab <yellow><count>x <gray>to keep the auto-replant going."),
            Map.entry("harvest.inventory-full", "<yellow>Inventory full <dark_gray>· <gray>leftovers dropped on the ground."),
            Map.entry("toggle.on", "<gray>Auto-replant <green>on</green> <dark_gray>· <gray>break a crop, it replants itself."),
            Map.entry("toggle.off", "<gray>Auto-replant <red>off</red> <dark_gray>· <gray>crops break like vanilla."),
            Map.entry("toggle.global-on", "<gray>Global replanting <green>on</green> <dark_gray>· <gray>everyone's crops replant again."),
            Map.entry("toggle.global-off", "<gray>Global replanting <red>off</red> <dark_gray>· <gray>nobody's crops replant."),
            Map.entry("toggle.global-not-saved", "<yellow>config.yml looks broken, so this wasn't saved. Fix the file and run /rpp reload, or it resets on restart."),
            Map.entry("pad.given", "<gray>Here's a pad <dark_gray>· <gray>place it, step on it, warp. Sneak-right-click it to set where it goes."),
            Map.entry("pad.placed", "<gray>Pad placed <dark_gray>· <gray>sneak-right-click it to set where it warps you."),
            Map.entry("pad.picked-up", "<green>Pad picked up."),
            Map.entry("pad.not-yours", "<red>That pad isn't yours."),
            Map.entry("pad.no-destination", "<gray>This pad has no destination yet <dark_gray>· <gray>pick one below."),
            Map.entry("pad.destination-set", "<gray>Destination set to <yellow><pad><gray>. Step on the pad to warp."),
            Map.entry("pad.destination-gone", "<red>The pad it warped to is gone <dark_gray>· <gray>pick a new one."),
            Map.entry("pad.renamed", "<gray>Pad renamed to <yellow><name><gray>."),
            Map.entry("pad.name-cleared", "<gray>Pad name cleared."),
            Map.entry("pad.name-prompt", "<gray>Type the new name in chat <dark_gray>(<gray>empty message clears it<dark_gray>)<gray>."),
            Map.entry("pad.limit", "<red>Pad limit reached <dark_gray>(<white><limit><red><dark_gray>)<red>. Pick some up first."),
            Map.entry("pad.warped", "<gray>Warped to <yellow><pad><gray>."),
            Map.entry("update.available", "<yellow>Replenish++ <gold>v<latest> <yellow>is out! <dark_gray>(<gray>you're on <white>v<current><dark_gray>)"),
            Map.entry("menu.main", """

                    <dark_gray>────────  <gradient:#FFD700:#FF9D00><bold>Replenish++</bold></gradient> <gray>v<version>  <dark_gray>────────
                    <yellow>/rpp help <dark_gray>· <gray>how the plugin works
                    <yellow>/rpp status <dark_gray>· <gray>what's on right now
                    <yellow>/rpp toggle <dark_gray>· <gray>replanting just for you
                    <yellow>/rpp toggle global <dark_gray>· <gray>replanting for everyone
                    <yellow>/rpp pad <dark_gray>· <gray>grab a Teleport Pad
                    <yellow>/rpp version <dark_gray>· <gray>version + update check
                    <yellow>/rpp reload <dark_gray>· <gray>reload config + messages
                    """),
            Map.entry("menu.help", """

                    <dark_gray>────────  <gradient:#FFD700:#FF9D00><bold>Replenish++</bold></gradient>  <dark_gray>────────
                    <gradient:#FFD700:#FF9D00><bold>Farming</bold></gradient>
                    <dark_gray>  · <gray>Break a crop with the right tool, a <yellow>hoe <gray>for crops, an <yellow>axe <gray>for cocoa.
                    <dark_gray>  · <gray>It replants itself and eats one seed from your inventory.
                    <dark_gray>  · <gray>Sneak while breaking if you want the vanilla behavior.
                    <dark_gray>  · <gray>Keep some spare seeds, at full speed you burn through them fast.
                    <gradient:#FFD700:#FF9D00><bold>Teleport Pads</bold></gradient>
                    <dark_gray>  · <gray>Grab one with <yellow>/rpp pad<gray>, place it, step on it to warp.
                    <dark_gray>  · <gray>Sneak-right-click a pad to set where it goes.
                    <gradient:#FFD700:#FF9D00><bold>Good to know</bold></gradient>
                    <dark_gray>  · <gray><yellow>/rpp toggle <gray>turns replanting off just for you.
                    <dark_gray>  · <gray><yellow>/rpp status <gray>shows everything that's on.
                    """));

    private static volatile Map<String, String> messages = DEFAULTS;

    private Messages() {}

    public static void load(FileConfiguration file) {
        Map<String, String> overlay = new HashMap<>(DEFAULTS);
        for (String key : DEFAULTS.keySet()) {
            if (file.isString(key)) {
                overlay.put(key, file.getString(key));
            }
        }
        messages = Map.copyOf(overlay);
    }

    public static String text(String key) {
        return messages.getOrDefault(key, DEFAULTS.get(key));
    }

    public static Component prefixed(String key, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(text("prefix") + " " + text(key), resolvers);
    }

    public static Component prefixedRaw(String miniMessage) {
        return MINI_MESSAGE.deserialize(text("prefix") + " " + miniMessage);
    }

    public static Component bare(String miniMessage, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(miniMessage, resolvers);
    }

    public static Component component(String key, TagResolver... resolvers) {
        return bare(text(key), resolvers);
    }
}
