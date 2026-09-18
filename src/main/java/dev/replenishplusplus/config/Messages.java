package dev.replenishplusplus.config;

import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Messages {

    public static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static final String PREFIX = "<dark_gray>[<yellow>ReplenishPlusPlus<dark_gray>] <gray>";
    public static final String ARROW  = "<dark_gray>» ";
    public static final String DOT    = "<dark_gray>• ";
    public static final String LINE   = "<dark_gray><strikethrough>                                     ";

    public static String prefixed(String message) {
        return PREFIX + ARROW + message;
    }

    public static final String INVENTORY_FULL_DEFAULT =
            PREFIX + ARROW + "<gray>Your inventory was full, so some items dropped on the ground instead.";

    public static final String REQUIRES_TOOL_DEFAULT =
            PREFIX + ARROW + "<gray>You need a <yellow><tool> <gray>to harvest <yellow><crop><gray>.";

    public static final String NEED_SEED_DEFAULT =
            PREFIX + ARROW + "<gray>You need <yellow><count>x <seed> <gray>in your inventory to replant this.";

    private Messages() {}
}
