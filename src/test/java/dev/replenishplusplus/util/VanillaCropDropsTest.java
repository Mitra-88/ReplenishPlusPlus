package dev.replenishplusplus.util;

import dev.replenishplusplus.crop.CropType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaCropDropsTest {

    private static final int SAMPLES = 100_000;
    private static final long SEED = 260922L;
    private static final double PROBABILITY = 0.5714286;
    private static final double ONE_MINUS_PROBABILITY = 0.4285714;
    private static final double POISONOUS_CHANCE = 0.02;

    @Test
    void sampledCountsStayWithinVanillaBounds() {
        for (CropType crop : CropType.values()) {
            for (int fortune = 0; fortune <= 3; fortune++) {
                int[][] bounds = vanillaBounds(crop, fortune);
                Random random = new Random(SEED + crop.ordinal() * 17L + fortune);
                for (int i = 0; i < SAMPLES; i++) {
                    int[] counts = VanillaCropDrops.counts(crop, fortune, random);
                    assertEquals(bounds.length, counts.length, crop + " slot count");
                    for (int slot = 0; slot < counts.length; slot++) {
                        assertTrue(counts[slot] >= bounds[slot][0] && counts[slot] <= bounds[slot][1],
                                crop + " fortune " + fortune + " slot " + slot + " sampled " + counts[slot]);
                    }
                }
            }
        }
    }

    @Test
    void distributionsMatchTheTranscribedTables() {
        for (CropType crop : CropType.values()) {
            for (int fortune = 0; fortune <= 3; fortune++) {
                double[][] expected = vanillaMeanAndVariance(crop, fortune);
                Random random = new Random(SEED + crop.ordinal() * 31L + fortune);
                long[] totals = new long[expected.length];
                for (int i = 0; i < SAMPLES; i++) {
                    int[] counts = VanillaCropDrops.counts(crop, fortune, random);
                    for (int slot = 0; slot < counts.length; slot++) totals[slot] += counts[slot];
                }
                for (int slot = 0; slot < totals.length; slot++) {
                    double mean = (double) totals[slot] / SAMPLES;
                    double sigma = Math.sqrt(expected[slot][1] / SAMPLES);
                    assertTrue(Math.abs(mean - expected[slot][0]) <= 5 * sigma,
                            crop + " fortune " + fortune + " slot " + slot + " mean " + mean
                                    + " expected " + expected[slot][0]);
                }
            }
        }
    }

    private static int[][] vanillaBounds(CropType crop, int fortune) {
        return switch (crop) {
            case WHEAT, BEETROOTS -> new int[][] {{1, 1}, {1, 4 + fortune}};
            case CARROTS -> new int[][] {{1, 4 + fortune}};
            case POTATOES -> new int[][] {{1, 4 + fortune}, {0, 1}};
            case NETHER_WART -> new int[][] {{2, 4 + fortune}};
            case COCOA -> new int[][] {{3, 3}};
        };
    }

    private static double[][] vanillaMeanAndVariance(CropType crop, int fortune) {
        double trials = fortune + 3;
        double bonusMean = trials * PROBABILITY;
        double bonusVariance = trials * PROBABILITY * ONE_MINUS_PROBABILITY;
        double wartVariance = 2.0 / 3.0 + (double) (fortune * (fortune + 2)) / 12.0;
        double poisonVariance = POISONOUS_CHANCE * (1.0 - POISONOUS_CHANCE);
        return switch (crop) {
            case WHEAT, BEETROOTS -> new double[][] {{1.0, 0.0}, {1.0 + bonusMean, bonusVariance}};
            case CARROTS -> new double[][] {{1.0 + bonusMean, bonusVariance}};
            case POTATOES -> new double[][] {{1.0 + bonusMean, bonusVariance}, {POISONOUS_CHANCE, poisonVariance}};
            case NETHER_WART -> new double[][] {{3.0 + fortune / 2.0, wartVariance}};
            case COCOA -> new double[][] {{3.0, 0.0}};
        };
    }
}
