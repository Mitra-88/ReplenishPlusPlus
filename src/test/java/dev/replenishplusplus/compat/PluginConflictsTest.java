package dev.replenishplusplus.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConflictsTest {

    @Test
    void matchesAreExactAndCaseInsensitive() {
        assertEquals(
                List.of("mcMMO"),
                PluginConflicts.scan(Set.of("mcmmo"), List.of("EssentialsX", "mcMMO", "WorldEdit")));
    }

    @Test
    void substringsNeverMatch() {
        assertTrue(PluginConflicts.scan(
                Set.of("Replenish"),
                List.of("ReplenishPlusPlus", "Replenisher", "MyReplenish")).isEmpty());
    }

    @Test
    void multipleMatchesSortCaseInsensitively() {
        assertEquals(
                List.of("ALPHA", "Beta"),
                PluginConflicts.scan(Set.of("BETA", "alpha"), List.of("Beta", "ALPHA")));
    }

    @Test
    void emptyKnownListYieldsNoMatches() {
        assertTrue(PluginConflicts.scan(Set.of(), List.of("Anything")).isEmpty());
    }
}
