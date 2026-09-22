package dev.replenishplusplus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoundEffectTest {

    @Test
    void volumeClampsIntoZeroToOne() {
        assertEquals(0.0f, new SoundEffect(true, null, -1.0f, 1.0f).volume());
        assertEquals(0.0f, new SoundEffect(true, null, 0.0f, 1.0f).volume());
        assertEquals(1.0f, new SoundEffect(true, null, 1.0f, 1.0f).volume());
        assertEquals(1.0f, new SoundEffect(true, null, 5.0f, 1.0f).volume());
        assertEquals(0.0f, new SoundEffect(true, null, Float.NaN, 1.0f).volume());
    }

    @Test
    void pitchClampsIntoPointFiveToTwo() {
        assertEquals(0.5f, new SoundEffect(true, null, 1.0f, 0.1f).pitch());
        assertEquals(0.5f, new SoundEffect(true, null, 1.0f, 0.5f).pitch());
        assertEquals(2.0f, new SoundEffect(true, null, 1.0f, 2.0f).pitch());
        assertEquals(2.0f, new SoundEffect(true, null, 1.0f, 9.0f).pitch());
        assertEquals(0.5f, new SoundEffect(true, null, 1.0f, Float.NaN).pitch());
    }
}
