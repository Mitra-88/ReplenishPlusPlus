package dev.replenishplusplus.config;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Locale;

public enum MessageStyle {
    CHAT,
    ACTION_BAR,
    NONE;

    public void send(Player player, Component component) {
        switch (this) {
            case CHAT -> player.sendMessage(component);
            case ACTION_BAR -> player.sendActionBar(component);
            case NONE -> {}
        }
    }

    public static MessageStyle parse(String value) {
        if (value == null) return CHAT;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CHAT" -> CHAT;
            case "ACTION_BAR" -> ACTION_BAR;
            case "NONE" -> NONE;
            default -> null;
        };
    }
}
