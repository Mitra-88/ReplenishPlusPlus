package dev.replenishplusplus.util;

import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class WarningThrottle {

    private static final long SUPPRESS_DURATION_MS = 3000L;
    private static final Map<Category, State> STATES = new ConcurrentHashMap<>();

    private WarningThrottle() {}

    public enum Category {
        ABANDONED_REPLANT,
        REPLANT_FAILED,
        AGE_DATA_MISSING,
        DELAY_TRUNCATION,
        QUEUE_BACKPRESSURE
    }

    private static final class State {
        String lastMessage = "";
        long lastLogTime = 0;
        int suppressedCount = 0;
    }

    public static void log(Plugin plugin, Level level, Category category, Supplier<String> message) {
        log(plugin, level, category, message, null);
    }

    public static void log(Plugin plugin, Level level, Category category, Supplier<String> message, Throwable error) {
        State state = STATES.computeIfAbsent(category, _ -> new State());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (now - state.lastLogTime < SUPPRESS_DURATION_MS) {
                state.suppressedCount++;
                return;
            }
            flushSuppressed(plugin, level, state);
            String resolved = message.get();
            state.lastMessage = resolved;
            state.lastLogTime = now;
            if (error == null) plugin.getLogger().log(level, resolved);
            else plugin.getLogger().log(level, resolved, error);
        }
    }

    private static void flushSuppressed(Plugin plugin, Level level, State state) {
        if (state.suppressedCount > 0) {
            plugin.getLogger().log(level, "Suppressed {0} warnings since last report: {1}", new Object[] { state.suppressedCount, state.lastMessage });
            state.suppressedCount = 0;
        }
    }
}