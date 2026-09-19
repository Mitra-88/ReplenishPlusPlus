package dev.replenishplusplus.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class ConfigReader {

    private ConfigReader() {}

    public static boolean boolValue(FileConfiguration config, String path, boolean def, List<String> issues) {
        if (wrongStructure(config, path)) {
            issues.add(path + ": expected true/false but found a section/list - using " + def);
            return def;
        }
        String raw = config.getString(path);
        if (raw == null) return def;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        issues.add(path + ": '" + raw + "' is not true/false - using " + def);
        return def;
    }

    public static int intValue(FileConfiguration config, String path, int def, List<String> issues) {
        if (wrongStructure(config, path)) {
            issues.add(path + ": expected a whole number but found a section/list - using " + def);
            return def;
        }
        String raw = config.getString(path);
        if (raw == null) return def;
        String trimmed = raw.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            issues.add(path + ": '" + raw + "' is not a whole number" + outOfRangeSuffix(trimmed) + " - using " + def);
            return def;
        }
    }

    private static String outOfRangeSuffix(String trimmed) {
        try {
            Long.parseLong(trimmed);
            return " (out of range)";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    public static float floatValue(FileConfiguration config, String path, float def, List<String> issues) {
        if (wrongStructure(config, path)) {
            issues.add(path + ": expected a number but found a section/list - using " + def);
            return def;
        }
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

    public static String stringValue(FileConfiguration config, String path, String def, List<String> issues) {
        if (wrongStructure(config, path)) {
            issues.add(path + ": expected a text value but found a section/list - using the default");
            return def;
        }
        String raw = config.getString(path);
        return raw == null ? def : raw;
    }

    // getString() returns null for section/list values, which is indistinguishable from an
    // absent key - detect them so a broken value is reported instead of silently defaulted.
    private static boolean wrongStructure(FileConfiguration config, String path) {
        return config.isConfigurationSection(path) || config.isList(path);
    }
}
