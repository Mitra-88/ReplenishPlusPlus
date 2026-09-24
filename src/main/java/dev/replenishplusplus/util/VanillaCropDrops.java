package dev.replenishplusplus.util;

import dev.replenishplusplus.crop.CropType;

import java.util.Random;

public final class VanillaCropDrops {

    private static final double FORTUNE_PROBABILITY = 0.5714286;
    private static final int FORTUNE_EXTRA_TRIALS = 3;
    private static final double POISONOUS_POTATO_CHANCE = 0.02;

    private VanillaCropDrops() {}

    public static int[] counts(CropType crop, int fortune, Random random) {
        return switch (crop) {
            case WHEAT, BEETROOTS -> new int[] {1, 1 + binomialBonus(fortune, random)};
            case CARROTS -> new int[] {1 + binomialBonus(fortune, random)};
            case POTATOES -> new int[] {1 + binomialBonus(fortune, random), random.nextDouble() < POISONOUS_POTATO_CHANCE ? 1 : 0};
            case NETHER_WART -> new int[] {uniform(2, 4, random) + uniform(0, fortune, random)};
            case COCOA -> new int[] {3};
        };
    }

    private static int binomialBonus(int fortune, Random random) {
        int hits = 0;
        for (int i = 0; i < fortune + FORTUNE_EXTRA_TRIALS; i++) {
            if (random.nextDouble() < FORTUNE_PROBABILITY) hits++;
        }
        return hits;
    }

    private static int uniform(int min, int max, Random random) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }
}
