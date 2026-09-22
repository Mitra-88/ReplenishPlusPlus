package dev.replenishplusplus.pad;

public enum PadDirection {
    LAST("Player's Last Direction", -1),
    NORTH("North", 180f),
    NORTH_EAST("North East", 225f),
    EAST("East", 270f),
    SOUTH_EAST("South East", 315f),
    SOUTH("South", 0f),
    SOUTH_WEST("South West", 45f),
    WEST("West", 90f),
    NORTH_WEST("North West", 135f);

    private final String title;
    private final float yaw;

    PadDirection(String title, float yaw) {
        this.title = title;
        this.yaw = yaw;
    }

    public String title() {
        return title;
    }

    public boolean keepsPlayerFacing() {
        return this == LAST;
    }

    public float yaw() {
        return yaw;
    }

    public PadDirection next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
