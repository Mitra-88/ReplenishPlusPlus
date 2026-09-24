package dev.replenishplusplus.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionCheckTest {

    @Test
    void parsesPlainVersions() {
        assertArrayEquals(new int[] {26, 3}, ServerVersionCheck.parse("26.3"));
        assertArrayEquals(new int[] {1, 21, 4}, ServerVersionCheck.parse(" 1.21.4 "));
    }

    @Test
    void blankAndNonNumericVersionsYieldNull() {
        assertNull(ServerVersionCheck.parse(null));
        assertNull(ServerVersionCheck.parse(""));
        assertNull(ServerVersionCheck.parse("26.4-pre"));
    }

    @Test
    void serverIsNewerComparesElementWise() {
        assertTrue(ServerVersionCheck.serverIsNewer(new int[] {26, 3}, new int[] {26, 3, 1}));
        assertTrue(ServerVersionCheck.serverIsNewer(new int[] {26, 3}, new int[] {26, 4}));
        assertFalse(ServerVersionCheck.serverIsNewer(new int[] {26, 4}, new int[] {26, 3}));
        assertFalse(ServerVersionCheck.serverIsNewer(new int[] {26, 3}, new int[] {26, 3}));
    }
}
