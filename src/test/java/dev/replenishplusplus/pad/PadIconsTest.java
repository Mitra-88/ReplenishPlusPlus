package dev.replenishplusplus.pad;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadIconsTest {

    @Test
    void exactlyFiftySixUniqueIcons() {
        assertEquals(56, PadIcons.all().size());
        Set<String> ids = new HashSet<>();
        for (PadIcons.Icon icon : PadIcons.all()) {
            assertTrue(ids.add(icon.id()), "duplicate icon id " + icon.id());
        }
    }

    @Test
    void unknownIdFallsBackToTheDefaultIcon() {
        assertEquals(PadIcons.defaultIcon(), PadIcons.byId("nope"));
        assertEquals(PadIcons.defaultIcon(), PadIcons.byId(PadIcons.defaultIcon().id()));
    }
}
