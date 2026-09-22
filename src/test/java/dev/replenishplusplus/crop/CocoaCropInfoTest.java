package dev.replenishplusplus.crop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CocoaCropInfoTest {

    @Test
    void faceOrdinalsRoundTripAcrossThePackedLayout() {
        assertEquals(4, CocoaCropInfo.FACES.size());
        for (var face : CocoaCropInfo.FACES) {
            int ordinal = CocoaCropInfo.faceOrdinal(face);
            assertTrue(ordinal >= 0 && ordinal < CocoaCropInfo.FACES.size(), "ordinal must fit the 2-bit queue field");
            assertEquals(face, CocoaCropInfo.face(ordinal));
        }
    }

    @Test
    void nullFacePacksAsTheFirstFace() {
        assertEquals(0, CocoaCropInfo.faceOrdinal(null));
        assertEquals(CocoaCropInfo.FACES.getFirst(), CocoaCropInfo.face(0));
    }
}
