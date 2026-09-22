package dev.replenishplusplus.pad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadDirectionTest {

    @Test
    void yawMappingMatchesMinecraftCompass() {
        assertEquals(0f, PadDirection.SOUTH.yaw());
        assertEquals(45f, PadDirection.SOUTH_WEST.yaw());
        assertEquals(90f, PadDirection.WEST.yaw());
        assertEquals(135f, PadDirection.NORTH_WEST.yaw());
        assertEquals(180f, PadDirection.NORTH.yaw());
        assertEquals(225f, PadDirection.NORTH_EAST.yaw());
        assertEquals(270f, PadDirection.EAST.yaw());
        assertEquals(315f, PadDirection.SOUTH_EAST.yaw());
    }

    @Test
    void lastKeepsThePlayersFacingAndCycles() {
        assertTrue(PadDirection.LAST.keepsPlayerFacing());
        assertEquals(9, PadDirection.values().length);
        assertEquals(PadDirection.EAST, PadDirection.NORTH.next().next());
        assertEquals(PadDirection.LAST, PadDirection.NORTH_WEST.next());
    }
}
