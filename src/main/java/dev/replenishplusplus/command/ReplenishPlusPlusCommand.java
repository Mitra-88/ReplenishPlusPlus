package dev.replenishplusplus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.config.ConfigCache;
import dev.replenishplusplus.config.Messages;
import dev.replenishplusplus.config.SoundEffect;
import dev.replenishplusplus.config.SoundRegistryMapper;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.queue.QueueStats;
import dev.replenishplusplus.update.UpdateChecker;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ReplenishPlusPlusCommand {

    private final ReplenishPlusPlus plugin;

    public ReplenishPlusPlusCommand(ReplenishPlusPlus plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("replenishplusplus")
                .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.use", this::sendMainMenu))
                .then(Commands.literal("help")
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.use", this::sendHelp)))
                .then(Commands.literal("status")
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.status", this::sendStatus)))
                .then(Commands.literal("reload")
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.reload", this::handleReload)))
                .then(Commands.literal("toggle")
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.toggle", this::handlePersonalToggle))
                        .then(Commands.literal("global")
                                .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.toggle.global", this::handleGlobalToggle))))
                .then(Commands.literal("version")
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.version", this::sendVersion)))
                .then(Commands.literal("debug")
                        .then(Commands.literal("queue")
                                .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.debug", this::handleDebugQueue))));

        var rootNode = root.build();

        commands.register(rootNode);
        commands.register(Commands.literal("rpp").redirect(rootNode).build());
        commands.register(Commands.literal("replenish").redirect(rootNode).build());
    }

    private int execute(CommandSourceStack source, String permission, Consumer<CommandSender> action) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(permission)) {
            send(sender, Messages.prefixed("<red>You don't have permission to do that. <dark_gray>(<gray>requires " + permission + "<dark_gray>)"));
            return 0;
        }
        action.accept(sender);
        return Command.SINGLE_SUCCESS;
    }

    private void sendMainMenu(CommandSender sender) {
        String version = plugin.getPluginMeta().getVersion();
        String menu = """
                
                <dark_gray>      [ <yellow><bold>ReplenishPlusPlus <gray>v<version> <dark_gray>]       <reset>
                
                <yellow>/replenishplusplus help <dark_gray>- <gray>Shows a detailed guide on how to use the plugin.
                <yellow>/replenishplusplus status <dark_gray>- <gray>Shows current settings and enabled crops.
                <yellow>/replenishplusplus reload <dark_gray>- <gray>Reloads config.yml without restarting.
                <yellow>/replenishplusplus toggle <dark_gray>- <gray>Toggles auto-replant for <i>you</i> personally.
                <yellow>/replenishplusplus toggle global <dark_gray>- <gray>Toggles auto-replant for everyone (admin).
                <yellow>/replenishplusplus version <dark_gray>- <gray>Shows version and update info.
                <yellow>/rpp <dark_gray>- <gray>Short alias for this command.
                
                <line>""";
        sender.sendMessage(Messages.MINI_MESSAGE.deserialize(menu,
                Placeholder.parsed("version", version),
                Placeholder.parsed("line", Messages.LINE)));
    }

    private void sendHelp(CommandSender sender) {
        String help = """
                
                <dark_gray>      [ <yellow><bold>ReplenishPlusPlus <gray>Help Guide <dark_gray>]       <reset>
                
                <yellow>How it works:
                  <dot><gray>Use a <white>Hoe <gray>for normal crops, or an <white>Axe <gray>for Cocoa.
                  <dot><gray>Break the crop, and it will auto-replant instantly.
                  <dot><gray>If seeds are required, 1 seed is taken from your inventory.
                  <dot><gray>Use <white>/rpp toggle <gray>to turn auto-replant off for yourself.
                
                <yellow><bold>Pro Tip:
                  <dot><gray>It is best to have at least <white>4x <gray>of the seed of the crop to
                    <gray>avoid replanting it too fast and running out, making it think
                    <gray>you don't have enough seeds!
                
                <yellow>Commands:
                  <dot><white>/rpp status <dark_gray>- <gray>Shows current settings and enabled crops.
                  <dot><white>/rpp reload <dark_gray>- <gray>Reloads config.yml without restarting.
                  <dot><white>/rpp toggle <dark_gray>- <gray>Toggles auto-replant for you personally.
                  <dot><white>/rpp toggle global <dark_gray>- <gray>Toggles auto-replant for everyone (admin).
                  <dot><white>/rpp version <dark_gray>- <gray>Shows version and update info.
                  <dot><white>/rpp debug queue <dark_gray>- <gray>Shows replant queue statistics.
                
                <line>""";
        sender.sendMessage(Messages.MINI_MESSAGE.deserialize(help,
                Placeholder.parsed("dot", Messages.DOT),
                Placeholder.parsed("line", Messages.LINE)));
    }

    private void handlePersonalToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPrefixed(sender, "<red>Only players can use the personal toggle. Use <white>/rpp toggle global <red>for the global toggle.");
            return;
        }
        boolean nowEnabled = plugin.getPlayerToggleManager().toggle(player);
        String state  = nowEnabled ? "<green><bold>ENABLED" : "<red><bold>DISABLED";
        String detail = nowEnabled
                ? "Crops will auto-replant for you again."
                : "Crops will no longer auto-replant for you. Harvests behave like vanilla.";
        sendPrefixed(sender, "Your personal replanting is now " + state + "<gray>. " + detail);
    }

    private void handleGlobalToggle(CommandSender sender) {
        boolean nowEnabled = !plugin.isEnabledGlobally();
        plugin.setGloballyEnabled(nowEnabled);

        plugin.getConfig().set("enabled", nowEnabled);
        plugin.saveConfig();

        String state  = nowEnabled ? "<green><bold>ENABLED" : "<red><bold>DISABLED";
        String detail = nowEnabled
                ? "Crops will replant themselves again for everyone."
                : "Crops will no longer replant. Harvests behave like vanilla.";
        sendPrefixed(sender, "Global replanting is now " + state + "<gray>. " + detail);
    }

    private void handleReload(CommandSender sender) {
        send(sender, "<gray>Reloading configuration...");

        List<String> issues = plugin.reloadLocalConfig();
        ConfigCache cfg = plugin.getConfigCache();

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <yellow><bold>Config Reloaded <dark_gray>]       <reset>\n\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replanting: ").append(onOff(cfg.enabled())).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replant delay: <white>").append(cfg.replantDelayTicks()).append(" tick(s)\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replants per tick: <white>").append(cfg.maxReplantsPerTick()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Queue capacity: <white>").append(cfg.maxReplantsQueued()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Give drops directly to player: ").append(yesNo(cfg.directPickup(), "No, drop on ground")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Require a seed to replant: ").append(yesNo(cfg.requirePlayerSeed(), "No")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Sneak to bypass: ").append(yesNo(cfg.sneakToBypass(), "No")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Message style: <white>").append(cfg.messageStyle()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Sounds: <white>").append(countEnabledSounds(cfg)).append("/5 <gray>enabled\n");
        if (issues.isEmpty()) {
            sb.append("  ").append(Messages.DOT).append("<green>✔ <gray>No config issues found.\n\n");
        } else {
            sb.append("  ").append(Messages.DOT).append("<yellow>⚠ <gray>Config issues (<white>").append(issues.size()).append("<gray>):\n");
            for (String issue : issues) {
                sb.append("    <dark_gray>- <gray>").append(sanitize(issue)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("<gray>Your config.yml changes are now live.\n\n");
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private static String sanitize(String text) {
        return text.replace("<", "\\<");
    }

    private void sendStatus(CommandSender sender) {
        ConfigCache cfg = plugin.getConfigCache();
        String version = plugin.getPluginMeta().getVersion();

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <yellow><bold>ReplenishPlusPlus <gray>v").append(version).append(" <dark_gray>]       <reset>\n\n");
        sb.append(cfg.enabled()
                ? "  <green>✔ <white>Replanting is active\n"
                : "  <red>✘ <white>Replanting is disabled\n");
        sb.append(cfg.requirePlayerSeed()
                ? "  <green>✔ <white>Players must have a spare seed to replant\n"
                : "  <red>✘ <white>No seed needed to replant\n");
        sb.append(cfg.directPickup()
                ? "  <green>✔ <white>Harvested crops go straight to inventory\n"
                : "  <red>✘ <white>Harvested crops drop on the ground\n");
        sb.append(cfg.sneakToBypass()
                ? "  <green>✔ <white>Sneaking bypasses auto-replant\n\n"
                : "  <red>✘ <white>Sneaking does not bypass\n\n");

        sb.append("<yellow>Timing\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replants after: <white>").append(cfg.replantDelayTicks()).append(" tick(s)\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replants per tick: <white>").append(cfg.maxReplantsPerTick()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Queue capacity: <white>").append(cfg.maxReplantsQueued()).append("\n\n");

        sb.append("<yellow>Crops that auto-replant\n\n");
        for (CropType crop : CropType.values()) {
            boolean on = plugin.isCropEnabled(crop);
            sb.append("  ").append(on ? "<green>✔" : "<red>✘").append(" <gray>").append(crop.displayName()).append("\n");
        }
        sb.append("\n<yellow>Sounds\n");
        appendSoundLine(sb, "Pickup",          cfg.pickupSound());
        appendSoundLine(sb, "Inventory full",  cfg.inventoryFullSound());
        appendSoundLine(sb, "Denied (tool)",   cfg.deniedToolSound());
        appendSoundLine(sb, "Denied (seed)",   cfg.deniedSeedSound());
        appendSoundLine(sb, "Replant failed",  cfg.replantFailedSound());
        sb.append("\n<gray>Tip: <dark_gray>/<gray>rpp reload <gray>after editing config.yml.\n\n");
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private void handleDebugQueue(CommandSender sender) {
        QueueStats stats = plugin.getQueueStats();
        double usagePercent = stats.maxPoolSize() > 0
                ? 100.0 * stats.pendingCount() / stats.maxPoolSize()
                : 0.0;

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <yellow><bold>Queue Debug <dark_gray>]       <reset>\n\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Pending replants: <white>").append(stats.pendingCount()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Pool size: <white>").append(stats.currentPoolSize())
                .append("<dark_gray>/<white>").append(stats.maxPoolSize()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Capacity used: <white>")
                .append(String.format(Locale.ROOT, "%.1f%%", usagePercent)).append("\n\n");
        if (stats.pendingCount() > stats.maxPoolSize() / 2) {
            sb.append("  ").append(Messages.DOT).append("<yellow>⚠ <gray>Queue is over 50% full — consider raising maxReplantsQueued.\n\n");
        } else {
            sb.append("  ").append(Messages.DOT).append("<green>✔ <gray>Queue is healthy.\n\n");
        }
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private void sendVersion(CommandSender sender) {
        UpdateChecker uc = plugin.getUpdateChecker();
        String runningVersion = "v" + plugin.getPluginMeta().getVersion();

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <yellow><bold>Version Info <dark_gray>]       <reset>\n\n");

        if (uc == null || !uc.isEnabled()) {
            sb.append("  ").append(Messages.DOT).append("<gray>You're running: <white>").append(runningVersion).append("\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Update checks: <red>Disabled in config.yml\n");
        } else if (uc.isCheckPending()) {
            sb.append("  ").append(Messages.DOT).append("<gray>You're running: <white>").append(runningVersion).append("\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Update check: <white>Still checking, try again shortly\n");
        } else if (uc.isUpdateAvailable()) {
            sb.append("  ").append(Messages.DOT).append("<yellow>A new version is available! <dark_gray>(<white>v")
                    .append(uc.getCurrentVersion()).append(" <gray>➔ <yellow>v").append(uc.getLatestVersion()).append("<dark_gray>)\n");
            sb.append("  ").append(Messages.DOT)
                    .append("<gray>Download: <aqua><click:open_url:'").append(UpdateChecker.RELEASES_URL)
                    .append("'><hover:show_text:'<gray>Click to open release page'><u>github.com/Mitra-88/ReplenishPlusPlus</u></click>\n");
        } else if (uc.isLocalNewer()) {
            sb.append("  ").append(Messages.DOT).append("<light_purple>You're using a development build\n")
                    .append("    <dark_gray>• <gray>Current: <white>v").append(uc.getCurrentVersion()).append("\n")
                    .append("    <dark_gray>• <gray>Latest release: <white>v").append(uc.getLatestVersion()).append("\n")
                    .append("    <dark_gray>• <light_purple>Your build is newer than the latest public release.\n");
        } else {
            sb.append("  ").append(Messages.DOT).append("<green>You're up to date! <dark_gray>(<white>v")
                    .append(uc.getCurrentVersion()).append("<dark_gray>)\n");
        }

        sb.append("\n<gray>Server details\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Server: <white>").append(plugin.getServer().getVersion()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Java: <white>").append(System.getProperty("java.version"))
                .append(" <dark_gray>(<gray>").append(System.getProperty("java.vendor")).append("<dark_gray>)\n\n");
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private void appendSoundLine(StringBuilder sb, String label, SoundEffect sound) {
        if (!sound.enabled()) {
            sb.append("  ").append(Messages.DOT).append("<gray>").append(label).append(": <red>DISABLED\n");
            return;
        }
        sb.append("  ").append(Messages.DOT).append("<gray>").append(label).append(": <green>").append(SoundRegistryMapper.keyName(sound.sound()))
                .append(" <dark_gray>(<gray>vol <white>").append(formatFloat(sound.volume()))
                .append("<dark_gray>, <gray>pitch <white>").append(formatFloat(sound.pitch())).append("<dark_gray>)\n");
    }

    private static String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String onOff(boolean value) {
        return value ? "<green>On" : "<red>Off";
    }

    private static String yesNo(boolean value, String no) {
        return value ? "<green>Yes" : "<red>" + no;
    }

    private static int countEnabledSounds(ConfigCache cfg) {
        int count = 0;
        if (cfg.pickupSound().enabled())         count++;
        if (cfg.inventoryFullSound().enabled())  count++;
        if (cfg.deniedToolSound().enabled())     count++;
        if (cfg.deniedSeedSound().enabled())     count++;
        if (cfg.replantFailedSound().enabled())  count++;
        return count;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Messages.MINI_MESSAGE.deserialize(message));
    }

    private static void sendPrefixed(CommandSender sender, String message) {
        send(sender, Messages.prefixed(message));
    }
}
