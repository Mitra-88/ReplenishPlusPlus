package dev.replenishplusplus.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class ConfigReader {

    private ConfigReader() {}

    public static boolean boolValue(FileConfiguration config, String path, boolean def, List<String> issues) {
        String raw = config.getString(path);
        if (raw == null) return def;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        issues.add(path + ": '" + raw + "' is not true/false - using " + def);
        return def;
    }

    public static int intValue(FileConfiguration config, String path, int def, List<String> issues) {
        String raw = config.getString(path);
        if (raw == null) return def;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            issues.add(path + ": '" + raw + "' is not a whole number - using " + def);
            return def;
        }
    }

    public static float floatValue(FileConfiguration config, String path, float def, List<String> issues) {
        String raw = config.getString(path);
        if (raw == null) return def;
        try {
            float value = Float.parseFloat(raw.trim());
            if (Float.isNaN(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            issues.add(path + ": '" + raw + "' is not a number - using " + def);
            return def;
        }
    }
}
