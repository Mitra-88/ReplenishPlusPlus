package dev.replenishplusplus.crop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropTypeTest {

    @Test
    void fromNameIsCaseAndSpaceInsensitive() {
        assertEquals(CropType.WHEAT, CropType.fromName("wheat"));
        assertEquals(CropType.NETHER_WART, CropType.fromName("NETHER WART"));
        assertEquals(CropType.COCOA, CropType.fromName(" Cocoa "));
        assertEquals(CropType.BEETROOTS, CropType.fromName("Beetroots"));
    }

    @Test
    void fromNameRejectsUnknownAndNullKeys() {
        assertNull(CropType.fromName("melon"));
        assertNull(CropType.fromName(""));
        assertNull(CropType.fromName(null));
    }

    @Test
    void fromMaterialRoundTripsEveryCropAndRejectsOthers() {
        for (CropType crop : CropType.values()) {
            assertEquals(crop, CropType.fromMaterial(crop.material()));
        }
        assertNull(CropType.fromMaterial(CropType.WHEAT.seed()));
        assertNull(CropType.fromMaterial(null));
    }

    @Test
    void seedMaterialsAreNeverCropBlocks() {
        for (CropType crop : CropType.values()) {
            if (crop == CropType.NETHER_WART) {
                assertEquals(crop.material(), crop.seed());
            } else {
                assertNull(CropType.fromMaterial(crop.seed()),
                        crop + "'s seed must not be a crop block, or seed consume/refund aliases into a harvest");
            }
        }
    }
}
