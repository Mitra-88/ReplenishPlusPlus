package dev.replenishplusplus.compat;

public final class ServerVersionCheck {

    private ServerVersionCheck() {}

    public static int[] parse(String version) {
        if (version == null || version.isBlank()) return null;
        String[] parts = version.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    public static boolean serverIsNewer(int[] plugin, int[] server) {
        for (int i = 0; i < Math.max(plugin.length, server.length); i++) {
            int p = i < plugin.length ? plugin[i] : 0;
            int s = i < server.length ? server[i] : 0;
            if (s != p) return s > p;
        }
        return false;
    }
}
