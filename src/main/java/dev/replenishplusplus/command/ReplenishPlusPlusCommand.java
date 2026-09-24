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
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
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
                .requires(needs("replenishplusplus.use"))
                .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.use", this::sendMainMenu))
                .then(needsLiteral("help", "replenishplusplus.use", this::sendHelp))
                .then(needsLiteral("status", "replenishplusplus.status", this::sendStatus))
                .then(needsLiteral("reload", "replenishplusplus.reload", this::handleReload))
                .then(Commands.literal("toggle")
                        .requires(needs("replenishplusplus.toggle"))
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.toggle", this::handlePersonalToggle))
                        .then(needsLiteral("global", "replenishplusplus.toggle.global", this::handleGlobalToggle)))
                .then(needsLiteral("dev", "replenishplusplus.dev", this::handleDevToggle))
                .then(needsLiteral("pad", "replenishplusplus.pad", this::handlePadGive))
                .then(needsLiteral("version", "replenishplusplus.version", this::sendVersion))
                .then(Commands.literal("debug")
                        .requires(needs("replenishplusplus.debug"))
                        .executes(ctx -> execute(ctx.getSource(), "replenishplusplus.debug", this::handleDiagnostics))
                        .then(needsLiteral("queue", "replenishplusplus.debug", this::handleDebugQueue)));

        var rootNode = root.build();

        commands.register(rootNode);
        commands.register(Commands.literal("rpp").requires(needs("replenishplusplus.use")).redirect(rootNode).build());
        commands.register(Commands.literal("replenish").requires(needs("replenishplusplus.use")).redirect(rootNode).build());
    }

    private static java.util.function.Predicate<CommandSourceStack> needs(String permission) {
        return source -> source.getSender().hasPermission(permission);
    }

    private LiteralArgumentBuilder<CommandSourceStack> needsLiteral(
            String name, String permission, Consumer<CommandSender> action) {
        return Commands.literal(name)
                .requires(needs(permission))
                .executes(ctx -> execute(ctx.getSource(), permission, action));
    }

    private int execute(CommandSourceStack source, String permission, Consumer<CommandSender> action) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(permission)) {
            sendPrefixed(sender, "<red>You don't have permission to do that. <dark_gray>(<gray>requires " + permission + "<dark_gray>)");
            return 0;
        }
        action.accept(sender);
        return Command.SINGLE_SUCCESS;
    }

    private void sendMainMenu(CommandSender sender) {
        sender.sendMessage(Messages.component("menu.main",
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion())));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Messages.component("menu.help"));
    }

    private void handlePersonalToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPrefixed(sender, "<red>Only players can use the personal toggle. Use <white>/rpp toggle global <red>for the global toggle.");
            return;
        }
        boolean nowEnabled = plugin.getPlayerToggleManager().toggle(player);
        sender.sendMessage(Messages.prefixed(nowEnabled ? "toggle.on" : "toggle.off"));
    }

    private void handleGlobalToggle(CommandSender sender) {
        boolean nowEnabled = !plugin.isEnabledGlobally();
        plugin.setGloballyEnabled(nowEnabled);
        sender.sendMessage(Messages.prefixed(nowEnabled ? "toggle.global-on" : "toggle.global-off"));

        if (plugin.isConfigFileBroken()) {
            sender.sendMessage(Messages.prefixed("toggle.global-not-saved"));
            return;
        }
        plugin.getConfig().set("enabled", nowEnabled);
        plugin.saveConfig();
    }

    private void handleDevToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPrefixed(sender, "<red>Only players can toggle dev mode.");
            return;
        }
        boolean enabled = plugin.getDevModeManager().toggle(player);
        if (enabled) {
            sendPrefixed(sender, "Dev mode <green><bold>ENABLED</bold></green><gray>: crops replant fully grown, placed crops pop instantly, water acts like land, ice can't form anywhere, and your inventory auto-clears (tools plus one seed stack stay). Profile with <white>/spark profiler<gray>, the harvest report prints on stop.");
        } else {
            sendPrefixed(sender, "Dev mode <red><bold>DISABLED</bold></red><gray>: everything is back to vanilla for you.");
        }
    }

    private void handlePadGive(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPrefixed(sender, "<red>Only players can receive a Teleport Pad.");
            return;
        }
        plugin.getPadManager().givePadItem(player);
        sender.sendMessage(Messages.prefixed("pad.given"));
    }
    private void handleReload(CommandSender sender) {
        send(sender, "<gray>Reloading configuration...");

        List<String> issues = plugin.reloadLocalConfig();
        ConfigCache cfg = plugin.getConfigCache();

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <gradient:#FFD700:#FF9D00><bold>Config Reloaded</bold></gradient> <dark_gray>]\n\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replanting: ").append(onOff(cfg.enabled())).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replant delay: up to <white>").append(cfg.replantDelayTicks()).append(" <gray>tick(s)\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replants per tick: <white>").append(cfg.maxReplantsPerTick()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Queue capacity: <white>").append(cfg.maxReplantsQueued()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Give drops directly to player: ").append(yesNo(cfg.directPickup(), "No, drop on ground")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Require a seed to replant: ").append(yesNo(cfg.requirePlayerSeed(), "No")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Sneak to bypass: ").append(yesNo(cfg.sneakToBypass(), "No")).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Message style: <white>").append(cfg.messageStyle()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Update checks: ").append(onOff(cfg.checkUpdates())).append(" <dark_gray>(startup only)\n");
        List<SoundEffect> sounds = List.of(cfg.pickupSound(), cfg.inventoryFullSound(), cfg.deniedToolSound(),
                cfg.deniedSeedSound(), cfg.replantFailedSound());
        long enabledSounds = sounds.stream().filter(SoundEffect::enabled).count();
        sb.append("  ").append(Messages.DOT).append("<gray>Sounds: <white>").append(enabledSounds)
                .append("/").append(sounds.size()).append("<gray> enabled\n");
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
        sb.append("\n<dark_gray>      [ <gradient:#FFD700:#FF9D00><bold>Replenish++</bold></gradient> <gray>v").append(version).append(" <dark_gray>]\n\n");
        sb.append(cfg.enabled()
                ? "  <green>✔ <white>Replanting is on\n"
                : "  <red>✘ <white>Replanting is off\n");
        sb.append(cfg.requirePlayerSeed()
                ? "  <green>✔ <white>Replanting eats one seed\n"
                : "  <red>✘ <white>Replanting is free\n");
        sb.append(cfg.directPickup()
                ? "  <green>✔ <white>Drops go straight to you\n"
                : "  <red>✘ <white>Drops fall on the ground\n");
        sb.append(cfg.sneakToBypass()
                ? "  <green>✔ <white>Sneaking skips replanting\n\n"
                : "  <red>✘ <white>Sneaking doesn't skip\n\n");

        sb.append("<gradient:#FFD700:#FF9D00><bold>Timing</bold></gradient>\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Replants after <white>").append(cfg.replantDelayTicks()).append(" <gray>tick(s) at most\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Up to <white>").append(cfg.maxReplantsPerTick()).append(" <gray>replants per tick\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Queue holds <white>").append(cfg.maxReplantsQueued()).append("\n\n");

        sb.append("<gradient:#FFD700:#FF9D00><bold>Crops</bold></gradient>\n\n");
        for (CropType crop : CropType.values()) {
            boolean on = plugin.isCropEnabled(crop);
            sb.append("  ").append(on ? "<green>✔" : "<red>✘").append(" <gray>").append(crop.displayName()).append("\n");
        }
        sb.append("\n<gradient:#FFD700:#FF9D00><bold>Sounds</bold></gradient>\n");
        appendSoundLine(sb, "Pickup",          cfg.pickupSound());
        appendSoundLine(sb, "Inventory full",  cfg.inventoryFullSound());
        appendSoundLine(sb, "Denied (tool)",   cfg.deniedToolSound());
        appendSoundLine(sb, "Denied (seed)",   cfg.deniedSeedSound());
        appendSoundLine(sb, "Replant failed",  cfg.replantFailedSound());
        sb.append("\n<gray>Edited config.yml or en_us.yml? <dark_gray>/<gray>rpp reload <gray>applies it.\n\n");
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private void handleDebugQueue(CommandSender sender) {
        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <gradient:#FFD700:#FF9D00><bold>Queue Debug</bold></gradient> <dark_gray>]\n\n");
        appendQueueLines(sb);
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private void appendQueueLines(StringBuilder sb) {
        QueueStats stats = plugin.getQueueStats();
        double usagePercent = 100.0 * stats.pendingCount() / stats.maxPoolSize();
        sb.append("  ").append(Messages.DOT).append("<gray>Pending replants: <white>").append(stats.pendingCount()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Pool size: <white>").append(stats.currentPoolSize())
                .append("<dark_gray>/<white>").append(stats.maxPoolSize()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Capacity used: <white>")
                .append(String.format(Locale.ROOT, "%.1f%%", usagePercent)).append("\n");
        if (stats.pendingCount() > stats.maxPoolSize() / 2) {
            sb.append("  ").append(Messages.DOT).append("<yellow>⚠ <gray>Queue is over 50% full, consider raising maxReplantsQueued.\n");
        } else {
            sb.append("  ").append(Messages.DOT).append("<green>✔ <gray>Queue is healthy.\n");
        }
    }

    private void handleDiagnostics(CommandSender sender) {
        ConfigCache cfg = plugin.getConfigCache();
        double[] tps = Bukkit.getTPS();
        TickSample ticks = sampleTicks(Bukkit.getTickTimes());
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long heapMax = runtime.maxMemory() / (1024L * 1024L);
        int heapPercent = heapMax == 0 ? 0 : (int) (100 * heapUsed / heapMax);

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <gradient:#FFD700:#FF9D00><bold>Replenish++ Diagnostics</bold></gradient> <dark_gray>]\n\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Players online: <white>").append(Bukkit.getOnlinePlayers().size())
                .append("<dark_gray>/<white>").append(Bukkit.getMaxPlayers()).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Uptime: <white>")
                .append(formatUptime(ManagementFactory.getRuntimeMXBean().getUptime())).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>TPS <dark_gray>(<gray>1m · 5m · 15m<dark_gray>): ")
                .append(tpsText(tps[0])).append(" <dark_gray>· ").append(tpsText(tps[1]))
                .append(" <dark_gray>· ").append(tpsText(tps[2])).append("\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Server feels ").append(tpsVerdict(tps[1])).append("\n");
        Spark spark = detectSpark();
        if (spark != null) {
            appendSparkLines(sb, spark);
        } else {
            sb.append("  ").append(Messages.DOT).append("<gray>Spark is not installed, long-window stats unavailable.\n");
        }
        if (ticks.count() > 0) {
            sb.append("  ").append(Messages.DOT).append("<gray>Recent ticks (min · median · avg · max): <white>")
                    .append(formatMs(ticks.min())).append(" <dark_gray>· <white>").append(formatMs(ticks.median()))
                    .append(" <dark_gray>· <white>").append(formatMs(ticks.average()))
                    .append(" <dark_gray>· <white>").append(formatMs(ticks.max())).append(" ms\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Lagged ticks over 50ms: ")
                    .append(laggedText(ticks)).append("\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Median tick budget used: <white>")
                    .append(String.format(Locale.ROOT, "%.1f%%", 100 * ticks.median() / 50.0)).append("\n");
        }
        sb.append("  ").append(Messages.DOT).append("<gray>Heap: <white>").append(heapUsed).append("<gray>/<white>")
                .append(heapMax).append(" MB ").append(heapColor(heapPercent)).append("\n\n");

        sb.append("<gradient:#FFD700:#FF9D00><bold>Replant queue</bold></gradient>\n");
        appendQueueLines(sb);
        sb.append("  ").append(Messages.DOT).append("<gray>Delay window: <white>random 1..").append(cfg.replantDelayTicks())
                .append(" <gray>ticks\n");
        sb.append("  ").append(Messages.DOT).append("<gray>Max per tick: <white>").append(cfg.maxReplantsPerTick()).append("\n\n");

        sb.append(Messages.LINE);
        sb.append("  <dark_gray>\nIf TPS dips, spark gives the full picture; this screen is a quick read.\n");
        sb.append(Messages.LINE);

        send(sender, sb.toString());
    }

    private record TickSample(int count, double min, double median, double average, double max, int lagged) {}

    private Spark detectSpark() {
        Plugin sparkPlugin = Bukkit.getPluginManager().getPlugin("spark");
        if (sparkPlugin == null || !sparkPlugin.isEnabled()) return null;
        try {
            return SparkProvider.get();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void appendSparkLines(StringBuilder sb, Spark spark) {
        GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
        if (mspt != null) {
            DoubleAverageInfo fiveMinutes = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_5);
            DoubleAverageInfo oneMinute = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_1);
            sb.append("  ").append(Messages.DOT).append("<gray>MSPT (spark, 5m window): <white>median ")
                    .append(formatMs(fiveMinutes.median())).append(" <dark_gray>· <white>p95 ")
                    .append(formatMs(fiveMinutes.percentile95th())).append(" <dark_gray>· <white>max ")
                    .append(formatMs(fiveMinutes.max())).append(" ms\n");
            sb.append("  ").append(Messages.DOT).append("<gray>MSPT (spark, 1m window): <white>median ")
                    .append(formatMs(oneMinute.median())).append(" <dark_gray>· <white>p95 ")
                    .append(formatMs(oneMinute.percentile95th())).append(" <dark_gray>· <white>max ")
                    .append(formatMs(oneMinute.max())).append(" ms\n");
        }
        DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess = spark.cpuProcess();
        DoubleStatistic<StatisticWindow.CpuUsage> cpuSystem = spark.cpuSystem();
        double processLoad = cpuProcess.poll(StatisticWindow.CpuUsage.MINUTES_1) * 100.0;
        double systemLoad = cpuSystem.poll(StatisticWindow.CpuUsage.MINUTES_1) * 100.0;
        sb.append("  ").append(Messages.DOT).append("<gray>CPU (spark, 1m window): <white>process ")
                .append(String.format(Locale.ROOT, "%.1f", processLoad)).append("%<dark_gray> · <white>system ")
                .append(String.format(Locale.ROOT, "%.1f", systemLoad)).append("%\n");
    }

    private static TickSample sampleTicks(long[] tickTimes) {
        if (tickTimes.length == 0) {
            return new TickSample(0, 0, 0, 0, 0, 0);
        }
        long[] sorted = tickTimes.clone();
        Arrays.sort(sorted);
        double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2] / 1_000_000.0
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2_000_000.0;
        long total = 0;
        int lagged = 0;
        for (long tick : sorted) {
            total += tick;
            if (tick > 50_000_000L) lagged++;
        }
        return new TickSample(
                sorted.length,
                sorted[0] / 1_000_000.0,
                median,
                total / (double) sorted.length / 1_000_000.0,
                sorted[sorted.length - 1] / 1_000_000.0,
                lagged);
    }

    private static String tpsText(double tps) {
        double clamped = Math.min(tps, 20.0);
        String color = tps >= 19.0 ? "green" : tps >= 15.0 ? "yellow" : "red";
        return "<" + color + ">" + String.format(Locale.ROOT, "%.2f", clamped) + "</" + color + ">";
    }

    private static String tpsVerdict(double tps5m) {
        if (tps5m >= 19.0) return "<green>smooth</green>";
        if (tps5m >= 15.0) return "<yellow>degraded</yellow>";
        return "<red>lagging</red>";
    }

    private static String laggedText(TickSample ticks) {
        if (ticks.lagged() == 0) return "<green>0 of " + ticks.count() + " (0%)</green>";
        long percent = 100L * ticks.lagged() / ticks.count();
        String color = percent <= 5 ? "yellow" : "red";
        return "<" + color + ">" + ticks.lagged() + " of " + ticks.count() + " (" + percent + "%)</" + color + ">";
    }

    private static String heapColor(int percent) {
        if (percent < 75) return "<dark_gray>(" + percent + "%)";
        if (percent < 90) return "<yellow>(" + percent + "%)";
        return "<red>(" + percent + "%)";
    }

    private static String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m " + seconds + "s";
    }

    private static String formatMs(double millis) {
        return String.format(Locale.ROOT, "%.2f", millis);
    }

    private void sendVersion(CommandSender sender) {
        UpdateChecker uc = plugin.getUpdateChecker();
        String runningVersion = "v" + plugin.getPluginMeta().getVersion();

        var sb = new StringBuilder();
        sb.append("\n<dark_gray>      [ <gradient:#FFD700:#FF9D00><bold>Version Info</bold></gradient> <dark_gray>]\n\n");

        if (!uc.isEnabled()) {
            sb.append("  ").append(Messages.DOT).append("<gray>You're running: <white>").append(runningVersion).append("\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Update checks: <red>Disabled in config.yml\n");
        } else if (uc.isCheckPending()) {
            sb.append("  ").append(Messages.DOT).append("<gray>You're running: <white>").append(runningVersion).append("\n");
            sb.append("  ").append(Messages.DOT).append("<gray>Update check: <white>Still checking, try again shortly\n");
        } else if (uc.isUpdateAvailable()) {
            sb.append("  ").append(Messages.DOT).append(Messages.text("update.available")
                    .replace("<current>", uc.getCurrentVersion())
                    .replace("<latest>", uc.getLatestVersion())).append('\n');
            sb.append("  ").append(Messages.DOT)
                    .append("<gray>Download: ").append(uc.downloadLink()).append('\n');
        } else if (uc.isCheckFailed()) {
            sb.append("  ").append(Messages.DOT).append("<red>Update check failed <dark_gray>(<gray>see server log for details<dark_gray>)\n");
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

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Messages.bare(message));
    }

    private static void sendPrefixed(CommandSender sender, String message) {
        sender.sendMessage(Messages.prefixedRaw(message));
    }
}
