package dev.replenishplusplus.pad;

import java.util.UUID;

public record TeleportPad(
        BlockKey key,
        UUID owner,
        String iconId,
        String name,
        BlockKey destination,
        PadDirection arrival) {

    public TeleportPad {
        if (arrival == null) arrival = PadDirection.LAST;
    }

    public String displayOr(String fallback) {
        return name != null ? name : fallback;
    }
}
