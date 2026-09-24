package dev.replenishplusplus.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiBuildCheckTest {

    private static final String SERVER_VERSION =
            "This server is running Paper version 26.3-35-main@dd9d103 (2026-09-22T19:19:23Z)"
                    + " (Implementing API version 26.3.build.35-alpha)";

    @Test
    void parsesTheApiBuildFromTheVersionString() {
        assertEquals(35, ApiBuildCheck.runningApiBuild(SERVER_VERSION));
        assertEquals(7, ApiBuildCheck.runningApiBuild("Paper version 26.4-1-main@deadbeef (Implementing API version 26.4.build.7-alpha)"));
    }

    @Test
    void returnsNullWhenNoBuildIsPresent() {
        assertNull(ApiBuildCheck.runningApiBuild("This server is running some other jar"));
        assertNull(ApiBuildCheck.runningApiBuild(null));
    }

    @Test
    void warnsOnlyWhenTheServerIsNewer() {
        String warning = ApiBuildCheck.newerApiWarning(SERVER_VERSION, 34);
        assertTrue(warning.contains("35") && warning.contains("34"));
        assertNull(ApiBuildCheck.newerApiWarning(SERVER_VERSION, 35));
        assertNull(ApiBuildCheck.newerApiWarning(SERVER_VERSION, 36));
    }
}
