package dev.replenishplusplus.config;

import dev.replenishplusplus.crop.CropType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCacheTest {

    private static ConfigCache cacheWith(Set<CropType> disabled) {
        return new ConfigCache(true, true, true, true, MessageStyle.CHAT,
                1, 1024, 4096, true, disabled,
                null, null, null, null, null,
                new ConfigCache.DevOptions(true, true, true, true, true, true));
    }

    @Test
    void withEnabledSwapsOnlyTheEnabledFlag() {
        ConfigCache cache = cacheWith(Set.of());
        ConfigCache toggled = cache.withEnabled(false);

        assertFalse(toggled.enabled());
        assertEquals(cache.requirePlayerSeed(), toggled.requirePlayerSeed());
        assertEquals(cache.directPickup(), toggled.directPickup());
        assertEquals(cache.sneakToBypass(), toggled.sneakToBypass());
        assertEquals(cache.messageStyle(), toggled.messageStyle());
        assertEquals(cache.replantDelayTicks(), toggled.replantDelayTicks());
        assertEquals(cache.maxReplantsPerTick(), toggled.maxReplantsPerTick());
        assertEquals(cache.maxReplantsQueued(), toggled.maxReplantsQueued());
        assertEquals(cache.checkUpdates(), toggled.checkUpdates());
        assertEquals(cache.disabledCrops(), toggled.disabledCrops());
        assertEquals(cache.pickupSound(), toggled.pickupSound());
        assertEquals(cache.inventoryFullSound(), toggled.inventoryFullSound());
        assertEquals(cache.deniedToolSound(), toggled.deniedToolSound());
        assertEquals(cache.deniedSeedSound(), toggled.deniedSeedSound());
        assertEquals(cache.replantFailedSound(), toggled.replantFailedSound());
        assertEquals(cache.dev(), toggled.dev());
        assertTrue(cache.enabled());
    }

    @Test
    void isCropEnabledReflectsTheDisabledSet() {
        ConfigCache cache = cacheWith(Set.of(CropType.WHEAT, CropType.COCOA));
        assertFalse(cache.isCropEnabled(CropType.WHEAT));
        assertFalse(cache.isCropEnabled(CropType.COCOA));
        assertTrue(cache.isCropEnabled(CropType.CARROTS));
    }
}
