package dev.replenishplusplus.pad;

public record BlockKey(String world, int x, int y, int z) {

    public String serialize() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public static BlockKey parse(String raw) {
        if (raw == null) return null;
        int third = raw.lastIndexOf(';');
        if (third < 0) return null;
        int second = raw.lastIndexOf(';', third - 1);
        if (second < 0) return null;
        int first = raw.lastIndexOf(';', second - 1);
        if (first < 0) return null;
        try {
            return new BlockKey(raw.substring(0, first), Integer.parseInt(raw.substring(first + 1, second)),
                    Integer.parseInt(raw.substring(second + 1, third)), Integer.parseInt(raw.substring(third + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
