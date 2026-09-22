package dev.replenishplusplus.pad;

public record BlockKey(String world, int x, int y, int z) {

    public String serialize() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static BlockKey parse(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(";");
        if (parts.length != 4) return null;
        try {
            return new BlockKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
