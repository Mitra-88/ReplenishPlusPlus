package dev.replenishplusplus.config;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

public record SoundEffect(boolean enabled, Sound sound, float volume, float pitch) {

    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 1.0f;
    public static final float MIN_PITCH  = 0.5f;
    public static final float MAX_PITCH  = 2.0f;

    public SoundEffect {
        volume = clamp(volume, MIN_VOLUME, MAX_VOLUME);
        pitch  = clamp(pitch,  MIN_PITCH,  MAX_PITCH);
    }

    public void play(Player player) {
        if (!enabled) return;
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        if (value < min) return min;
        return Math.min(value, max);
    }
}